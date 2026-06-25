package com.knowcli.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 可执行的计划类
 */
public class ExecutionPlan {
    private final String id;
    private final String goal;
    private final Map<String, Task> tasks;
    private final List<String> executionOrder;
    private PlanStatus status;
    private String summary;
    private long startTime;
    private long endTime;

    public enum PlanStatus {
        CREATED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public ExecutionPlan(String goal, List<Task> tasks, String summary) {
        this(UUID.randomUUID().toString(), goal, toTaskMap(tasks), summary);
    }

    public ExecutionPlan(String id, String goal, Map<String, Task> tasks, String summary) {
        this.id = requireNonBlank(id, "id");
        this.goal = requireNonBlank(goal, "goal");
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks 不能为空");
        }
        this.tasks = new LinkedHashMap<>();
        for (Map.Entry<String, Task> entry : tasks.entrySet()) {
            Task task = Objects.requireNonNull(entry.getValue(), "tasks 不能包含 null 元素");
            if (!task.getId().equals(entry.getKey())) {
                throw new IllegalArgumentException(" task 的 map key 必须与 task id 匹配: " + entry.getKey());
            }
            if (this.tasks.put(task.getId(), task) != null) {
                throw new IllegalArgumentException("重复的 task id: " + task.getId());
            }
        }
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
        this.summary = summary == null ? "" : summary.strip();
        computeExecutionOrder();
    }

    public String getId() {
        return id;
    }

    public String getGoal() {
        return goal;
    }

    public Map<String, Task> getTasks() {
        return Collections.unmodifiableMap(tasks);
    }

    public Task getTask(String taskId) {
        return tasks.get(taskId);
    }

    public List<String> getExecutionOrder() {
        return List.copyOf(executionOrder);
    }

    public PlanStatus getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String summary) {
        this.status = PlanStatus.FAILED;
        if (summary != null && !summary.isBlank()) {
            this.summary = summary.strip();
        }
        this.endTime = System.currentTimeMillis();
    }

    public void markCancelled(String summary) {
        this.status = PlanStatus.CANCELLED;
        if (summary != null && !summary.isBlank()) {
            this.summary = summary.strip();
        }
        this.endTime = System.currentTimeMillis();
    }

    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getStatus() == Task.TaskStatus.FAILED);
    }

    /**
     * 计算执行顺序
     */
    public boolean computeExecutionOrder() {
        wireDependents();
        executionOrder.clear();
        Map<String, VisitState> states = new LinkedHashMap<>();
        for (String taskId : tasks.keySet()) {
            visit(taskId, states, new ArrayList<>());
        }
        return true;
    }

    //将执行计划格式化为可读的文本字符串，方便打印或日志输出
    public String visualize() {
        StringBuilder builder = new StringBuilder();
        builder.append("计划: ").append(goal).append('\n');
        if (!summary.isBlank()) {
            builder.append("摘要: ").append(summary).append('\n');
        }
        builder.append("状态: ").append(status).append('\n');
        builder.append("执行顺序:\n");
        for (int index = 0; index < executionOrder.size(); index++) {
            Task task = tasks.get(executionOrder.get(index));
            builder.append(index + 1)
                    .append(". [")
                    .append(task.getType())
                    .append("] ")
                    .append(task.getId())
                    .append(" - ")
                    .append(task.getDescription());
            if (!task.getDependencies().isEmpty()) {
                builder.append(" (依赖 ")
                        .append(String.join(", ", task.getDependencies()))
                        .append(')');
            }
            builder.append('\n');
        }
        return builder.toString().stripTrailing();
    }

    //构建任务的反向依赖关系
    private void wireDependents() {
        tasks.values().forEach(Task::clearDependents);
        for (Task task : tasks.values()) {
            for (String dependencyId : task.getDependencies()) {
                String dep = requireNonBlank(dependencyId, "dependency id");
                Task dependency = tasks.get(dep);
                if (dependency == null) {
                    throw new IllegalArgumentException(
                            "task " + task.getId() + " depends on missing task " + dep);
                }
                dependency.addDependent(task.getId());
            }
        }
    }

    //经典的DFS 环检测 + 拓扑排序方法，用于确定任务的执行顺序
    private void visit(String taskId, Map<String, VisitState> states, List<String> stack) {
        VisitState state = states.get(taskId);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            stack.add(taskId);
            throw new IllegalArgumentException("cycle detected in plan: " + String.join(" -> ", stack));
        }

        Task task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("missing task: " + taskId);
        }

        states.put(taskId, VisitState.VISITING);
        stack.add(taskId);
        for (String dependencyId : task.getDependencies()) {
            visit(dependencyId, states, new ArrayList<>(stack));
        }
        states.put(taskId, VisitState.VISITED);
        if (!executionOrder.contains(taskId)) {
            executionOrder.add(taskId);
        }
    }

    private enum VisitState {
        VISITING,
        VISITED
    }

    //将 List<Task> 转换为 Map<String, Task>，以任务 ID为键、任务本身为值
    private static Map<String, Task> toTaskMap(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks must not be empty");
        }
        Map<String, Task> result = new LinkedHashMap<>();
        for (Task task : tasks) {
            Objects.requireNonNull(task, "tasks must not contain null");
            if (result.put(task.getId(), task) != null) {
                throw new IllegalArgumentException("duplicate task id: " + task.getId());
            }
        }
        return result;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}