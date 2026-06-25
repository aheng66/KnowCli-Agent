package com.knowcli.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowcli.llm.DeepSeekClient;
import com.knowcli.llm.DeepSeekClient.ChatResponse;
import com.knowcli.llm.DeepSeekClient.Message;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 计划器：将用户目标转换为执行计划
 */
public class Planner {
    private final DeepSeekClient llmClient;
    private final String planningPrompt;
    private final ObjectMapper mapper = new ObjectMapper();

    public Planner(String apiKey) {
        this(new DeepSeekClient(apiKey));
    }

    public Planner(DeepSeekClient llmClient) {
        this(llmClient, loadPlanningPrompt());
    }

    Planner(DeepSeekClient llmClient, String planningPrompt) {
        if (llmClient == null) {
            throw new IllegalArgumentException("llmClient 不能为 null");
        }
        if (planningPrompt == null || planningPrompt.isBlank()) {
            throw new IllegalArgumentException("planningPrompt 不能为空");
        }
        this.llmClient = llmClient;
        this.planningPrompt = planningPrompt.strip();
    }

    // 创建执行计划
    public ExecutionPlan createPlan(String goal) throws IOException {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }

        ChatResponse response = llmClient.chat(
                List.of(
                        Message.system(planningPrompt),
                        Message.user("针对这个目标创建计划:\n" + goal.strip())
                ),
                List.of()
        );
        if (response.content() == null || response.content().isBlank()) {
            throw new IOException("Planner LLM 返回内容为空");
        }
        return parsePlan(goal, response.content());
    }

    // 解析JSON获得计划ExecutionPlan
    ExecutionPlan parsePlan(String goal, String jsonPlan) throws IOException {
        String cleaned = cleanJson(jsonPlan);
        JsonNode root;
        try {
            root = mapper.readTree(cleaned);
        } catch (JsonProcessingException exception) {
            throw new IOException("Planner 返回无效 JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("计划 JSON 必须是对象");
        }

        String summary = optionalText(root, "summary");
        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            throw new IOException("计划 JSON 必须包含非空的 tasks 数组");
        }

        List<Task> tasks = new ArrayList<>();
        for (JsonNode taskNode : tasksNode) {
            if (!taskNode.isObject()) {
                throw new IOException("每个 task 必须是对象");
            }
            String id = requiredText(taskNode, "id");
            String description = requiredText(taskNode, "description");
            Task.TaskType type = parseTaskType(requiredText(taskNode, "type"));
            List<String> dependencies = parseDependencies(taskNode.path("dependencies"));
            tasks.add(new Task(id, description, type, dependencies));
        }
        return new ExecutionPlan(goal, tasks, summary);
    }


    private ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) {
        throw new UnsupportedOperationException(
                "自动重新规划功能保留到后续版本。失败原因: " + failureReason);
    }

    //从 JSON 节点中解析任务的依赖列表
    private List<String> parseDependencies(JsonNode dependenciesNode) throws IOException {
        if (dependenciesNode.isMissingNode() || dependenciesNode.isNull()) {
            return List.of();
        }
        if (!dependenciesNode.isArray()) {
            throw new IOException("task dependencies 必须是数组");
        }
        List<String> dependencies = new ArrayList<>();
        for (JsonNode dependency : dependenciesNode) {
            if (!dependency.isTextual() || dependency.asText().isBlank()) {
                throw new IOException("task dependencies 必须包含非空字符串");
            }
            dependencies.add(dependency.asText().strip());
        }
        return dependencies;
    }

    //将字符串值解析为 Task.TaskType 枚举
    private Task.TaskType parseTaskType(String value) throws IOException {
        try {
            return Task.TaskType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IOException("未知的任务类型: " + value, exception);
        }
    }

    private String cleanJson(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            int fenceEnd = text.lastIndexOf("```");
            if (firstLineEnd >= 0 && fenceEnd > firstLineEnd) {
                return text.substring(firstLineEnd + 1, fenceEnd).strip();
            }
        }
        int objectStart = text.indexOf('{');
        int objectEnd = text.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return text.substring(objectStart, objectEnd + 1).strip();
        }
        return text;
    }

    //字段必须存在且有内容，否则直接抛异常终止流程
    private String requiredText(JsonNode node, String field) throws IOException {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IOException("缺少必填字段: " + field);
        }
        return value.strip();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String loadPlanningPrompt() {
        try (InputStream stream = Planner.class.getResourceAsStream("/PLANNING_PROMPT.txt")) {
            if (stream == null) {
                throw new IllegalStateException("缺少类路径资源: /PLANNING_PROMPT.txt");
            }
            String prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (prompt.isBlank()) {
                throw new IllegalStateException("计划提示词资源不能为空");
            }
            return prompt;
        } catch (IOException exception) {
            throw new IllegalStateException("加载计划提示词失败", exception);
        }
    }
}