package com.knowcli.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class DeepSeekClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public static class ApiException extends IOException {
        private final int statusCode;

        public ApiException(String message) {
            this(-1, message, null);
        }

        public ApiException(String message, Throwable cause) {
            this(-1, message, cause);
        }

        public ApiException(int statusCode, String message) {
            this(statusCode, message, null);
        }

        private ApiException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    //构造器
    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    public DeepSeekClient(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, DEFAULT_MODEL);
    }

    public DeepSeekClient(String apiKey, String baseUrl, String model) {
        this.apiKey = requireNonBlank(apiKey, "apiKey");
        this.endpoint = requireNonBlank(baseUrl, "baseUrl")
                .replaceAll("/+$", "") + "/chat/completions";
        this.model = requireNonBlank(model, "model");
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    // 确保字符串非空
    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    // 定义消息类型（role、 content：消息文本、toolCalls：模型请求调用的工具列表、toolCallId：工具执行结果对应的调用 ID）
    public record Message(String role, String content,
                          List<ToolCall> toolCalls, String toolCallId) {
        public static Message system(String content) {
            return new Message("system", content, null, null);
        }
        public static Message user(String content) {
            return new Message("user", content, null, null);
        }
        public static Message assistant(String content) {
            return new Message("assistant", content, null, null);
        }
        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, toolCallId);
        }
    }
    // 工具调用信息定义 （本次工具调用的id，模型要调用的function）
    public record ToolCall(String id, Function function) {
        public record Function(String name, String arguments) {}
    }
    // 让 LLM 知道有哪些工具可用 name、description、parameters
    public record Tool(String name, String description, JsonNode parameters) {}

    // 定义LLM回复消息类型
    public record ChatResponse(String role, String content, String reasoningContent,
                               List<ToolCall> toolCalls, int inputTokens, int outputTokens) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * LLM对话
     * @param messages List<Message> 上下文消息
     * @param tools List<Tool> 可用工具列表
     * @return ChatResponse llm回复
     */
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }

        // 构建请求体
        ObjectNode requestJson = mapper.createObjectNode();
        requestJson.put("model", model);
        requestJson.put("stream", false);
        ArrayNode messageArray = requestJson.putArray("messages");

        // 添加历史消息（role、content）
        for (Message message : messages) {
            Objects.requireNonNull(message, "messages must not contain null");
            ObjectNode messageJson = messageArray.addObject();
            messageJson.put("role", requireNonBlank(message.role(), "message.role"));
            if (message.content() == null) {
                messageJson.putNull("content");
            } else {
                messageJson.put("content", message.content());
            }

            // 如果有工具调用，则序列化并添加 tool_calls
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode toolCallArray = messageJson.putArray("tool_calls");
                for (ToolCall toolCall : message.toolCalls()) {
                    appendToolCall(toolCallArray, toolCall);
                }
            }

            // 如果有工具执行结果，则添加 tool_call_id
            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                messageJson.put("tool_call_id", message.toolCallId());
            }
        }

        // 添加已注册的全部工具信息tools到请求体
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = requestJson.putArray("tools");
            for (Tool tool : tools) {
                Objects.requireNonNull(tool, "tools must not contain null");
                ObjectNode toolJson = toolArray.addObject();
                toolJson.put("type", "function");
                ObjectNode functionJson = toolJson.putObject("function");
                functionJson.put("name", requireNonBlank(tool.name(), "tool.name"));
                if (tool.description() != null) {
                    functionJson.put("description", tool.description());
                }
                if (tool.parameters() != null) {
                    functionJson.set("parameters", tool.parameters());
                }
            }
        }

        // 发送 HTTP 请求
        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(requestJson), JSON))
                .build();

        // 解析响应体，使用 try-with-resources 自动关闭资源
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body == null) {
                throw new ApiException(response.code(),
                        "DeepSeek API returned an empty response body (HTTP "
                        + response.code() + ")");
            }

            String responseText = body.string();
            if (!response.isSuccessful()) {
                throw new ApiException(response.code(),
                        "DeepSeek API request failed (HTTP "
                                + response.code() + "): " + responseText);
            }

            JsonNode responseJson;
            try {
                responseJson = mapper.readTree(responseText);
            } catch (JsonProcessingException exception) {
                throw new ApiException("DeepSeek API returned invalid JSON", exception);
            }
            if (responseJson == null || !responseJson.isObject()) {
                throw new ApiException("DeepSeek API returned an invalid response object");
            }
            JsonNode choices = responseJson.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new ApiException("DeepSeek API response does not contain choices[0]");
            }
            JsonNode messageJson = choices.get(0).path("message");
            if (!messageJson.isObject()) {
                throw new ApiException(
                        "DeepSeek API response does not contain choices[0].message");
            }

            // 提取消息内容、工具调用、token 使用等信息
            String role = nullableText(messageJson, "role");
            String content = nullableText(messageJson, "content");
            String reasoningContent = nullableText(messageJson, "reasoning_content");
            List<ToolCall> toolCalls = parseToolCalls(messageJson.path("tool_calls"));

            JsonNode usage = responseJson.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);

            // 返回 ChatResponse
            return new ChatResponse(role, content, reasoningContent, toolCalls,
                    inputTokens, outputTokens);
        }
    }

    /**
     * 添加工具调用信息tool_calls
     * @param target 要添加的目标节点
     * @param toolCall 信息中的工具调用信息
     */
    private void appendToolCall(ArrayNode target, ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCalls must not contain null");
        ToolCall.Function function = Objects.requireNonNull(
                toolCall.function(), "toolCall.function must not be null");

        ObjectNode toolCallJson = target.addObject();
        toolCallJson.put("id", requireNonBlank(toolCall.id(), "toolCall.id"));
        toolCallJson.put("type", "function");
        ObjectNode functionJson = toolCallJson.putObject("function");
        functionJson.put("name", requireNonBlank(function.name(), "toolCall.function.name"));
        functionJson.put("arguments", Objects.requireNonNullElse(function.arguments(), "{}"));
    }

    /**
     * 提取本轮的工具调用信息
     * @param toolCallsJson 工具调用信息JSON
     * @return List<ToolCall> 工具调用信息List
     */
    private List<ToolCall> parseToolCalls(JsonNode toolCallsJson) throws IOException {
        if (toolCallsJson.isMissingNode() || toolCallsJson.isNull()) {
            return List.of();
        }
        if (!toolCallsJson.isArray()) {
            throw new ApiException("DeepSeek API message.tool_calls is not an array");
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode toolCallJson : toolCallsJson) {
            JsonNode functionJson = toolCallJson.path("function");
            String id = requiredText(toolCallJson, "id", "tool call");
            String name = requiredText(functionJson, "name", "tool call function");
            String arguments = requiredText(functionJson, "arguments", "tool call function");
            toolCalls.add(new ToolCall(id, new ToolCall.Function(name, arguments)));
        }
        return List.copyOf(toolCalls);
    }

    private String requiredText(JsonNode node, String field, String context) throws IOException {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) {
            throw new ApiException("DeepSeek API " + context + " is missing " + field);
        }
        return value;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

}
