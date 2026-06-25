package com.knowcli.agent;

import com.knowcli.llm.DeepSeekClient;
import com.knowcli.plan.ExecutionPlan;
import com.knowcli.plan.Planner;
import com.knowcli.plan.Task;
import com.knowcli.tool.ToolRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class PlanExecuteAgent {
    private final Planner planner;
    private final TaskExecutor taskExecutor;

    @FunctionalInterface
    public interface TaskExecutor {
        String execute(String taskPrompt);
    }

    public PlanExecuteAgent(String apiKey) {
        this(new DeepSeekClient(apiKey), new ToolRegistry());
    }

    public PlanExecuteAgent(DeepSeekClient llmClient, ToolRegistry toolRegistry) {
        this(new Planner(llmClient), new Agent(llmClient, toolRegistry));
    }

    public PlanExecuteAgent(Planner planner, Agent agent) {
        this(planner, agent::run);
    }

    public PlanExecuteAgent(Planner planner, TaskExecutor taskExecutor) {
        if (planner == null) {
            throw new IllegalArgumentException("planner 不能为 null");
        }
        if (taskExecutor == null) {
            throw new IllegalArgumentException("taskExecutor 不能为 null");
        }
        this.planner = planner;
        this.taskExecutor = taskExecutor;
    }

    public String run(String userInput) {
        ExecutionPlan plan;
        try {
            plan = createPlan(userInput);
        } catch (IOException | IllegalArgumentException exception) {
            return "计划创建失败: " + exception.getMessage();
        }

        System.out.println(plan.visualize());
        return executePlan(plan);
    }

    public ExecutionPlan createPlan(String userInput) throws IOException {
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("用户输入不能为空");
        }
        return planner.createPlan(userInput);
    }

    public String executePlan(ExecutionPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为 null");
        }

        plan.markStarted();

        for (String taskId : plan.getExecutionOrder()) {
            Task task = plan.getTask(taskId);
            if (task == null) {
                plan.markFailed("缺少任务: " + taskId);
                return buildResult(plan);
            }
            if (!task.isExecutable(plan.getTasks())) {
                task.markSkipped("依赖任务未完成");
                markRemainingSkipped(plan, taskId, "前置任务不可执行");
                plan.markFailed("任务依赖未完成: " + taskId);
                return buildResult(plan);
            }

            executeTask(plan, task);
            if (task.getStatus() == Task.TaskStatus.FAILED) {
                markRemainingSkipped(plan, taskId, "前置任务失败: " + task.getId());
                plan.markFailed("任务失败: " + task.getId());
                return buildResult(plan);
            }
        }

        plan.markCompleted();
        return buildResult(plan);
    }
    private void executeTask(ExecutionPlan plan, Task task) {
        task.markStarted();
        String result;
        try {
            result = taskExecutor.execute(buildTaskPrompt(plan, task));
        } catch (RuntimeException exception) {
            task.markFailed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
            return;
        }

        if (result == null || result.isBlank()) {
            task.markFailed("任务执行器返回了空结果");
            return;
        }
        if (isFailureResult(result)) {
            task.markFailed(result.strip());
            return;
        }
        task.markCompleted(result.strip());
    }

    private String buildTaskPrompt(ExecutionPlan plan, Task task) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你正在执行一个较大计划中的单个步骤。\n\n");
        prompt.append("原始目标:\n").append(plan.getGoal()).append("\n\n");
        prompt.append("当前任务:\n");
        prompt.append("- id: ").append(task.getId()).append('\n');
        prompt.append("- 类型: ").append(task.getType()).append('\n');
        prompt.append("- 描述: ").append(task.getDescription()).append("\n\n");

        if (!task.getDependencies().isEmpty()) {
            prompt.append("依赖结果:\n");
            for (String dependencyId : task.getDependencies()) {
                Task dependency = plan.getTask(dependencyId);
                prompt.append("- ").append(dependencyId).append(": ");
                prompt.append(dependency == null || dependency.getResult() == null
                        ? "(无结果)"
                        : dependency.getResult());
                prompt.append('\n');
            }
            prompt.append('\n');
        }

        prompt.append("只完成当前任务。返回简洁的任务结果；");
        prompt.append("如有必要，请包含修改的文件、运行的命令或得出的结论。");
        return prompt.toString();
    }

    private boolean isFailureResult(String result) {
        String text = result.strip();
        return startsWithAny(text,
                "LLM call failed",
                "Network request failed",
                "Tool execution failed",
                "Task execution timed out",
                "Task execution interrupted",
                "LLM returned empty content",
                "Reached maximum reasoning iterations",
                "Plan creation failed",
                "LLM 调用失败",
                "网络请求失败",
                "工具执行失败",
                "任务执行超时",
                "任务执行被中断",
                "LLM 返回了空内容",
                "已达到最大推理轮数",
                "计划创建失败");
    }
    private boolean startsWithAny(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void markRemainingSkipped(ExecutionPlan plan, String currentTaskId, String reason) {
        boolean afterCurrent = false;
        for (String taskId : plan.getExecutionOrder()) {
            if (taskId.equals(currentTaskId)) {
                afterCurrent = true;
                continue;
            }
            if (afterCurrent) {
                Task task = plan.getTask(taskId);
                if (task != null && task.getStatus() == Task.TaskStatus.PENDING) {
                    task.markSkipped(reason);
                }
            }
        }
    }

    private String buildResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();
        result.append("计划状态: ").append(plan.getStatus()).append('\n');
        if (!plan.getSummary().isBlank()) {
            result.append("摘要: ").append(plan.getSummary()).append('\n');
        }

        List<String> order = plan.getExecutionOrder();
        Map<String, Task> tasks = plan.getTasks();
        StringJoiner completed = new StringJoiner(", ");
        StringJoiner failed = new StringJoiner(", ");
        StringJoiner skipped = new StringJoiner(", ");

        for (String taskId : order) {
            Task task = tasks.get(taskId);
            if (task == null) {
                continue;
            }
            switch (task.getStatus()) {
                case COMPLETED -> completed.add(task.getId());
                case FAILED -> failed.add(task.getId() + " (" + task.getError() + ")");
                case SKIPPED -> skipped.add(task.getId() + " (" + task.getError() + ")");
                default -> {
                }
            }
        }

        result.append("已完成任务: ").append(emptyDash(completed.toString())).append('\n');
        result.append("失败任务: ").append(emptyDash(failed.toString())).append('\n');
        result.append("跳过任务: ").append(emptyDash(skipped.toString())).append('\n');
        result.append("任务结果:\n");
        for (String taskId : order) {
            Task task = tasks.get(taskId);
            if (task != null && task.getResult() != null && !task.getResult().isBlank()) {
                result.append("- ").append(task.getId()).append(": ")
                        .append(task.getResult()).append('\n');
            }
        }
        return result.toString().stripTrailing();
    }

    private String emptyDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}