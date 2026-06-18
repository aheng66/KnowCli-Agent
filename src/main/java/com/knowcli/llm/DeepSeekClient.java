package com.knowcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class DeepSeekClient {
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    // 定义消息类型
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
    record ToolCall(String id, Function function) {
        public record Function(String name, String arguments) {}
    }
    // 让 LLM 知道有哪些工具可用 name、description、parameters
    public record Tool(String name, String description, JsonNode parameters) {}

    public ChatResponse chat(List<Message> messages) throws IOException, InterruptedException {
        return new ChatResponse ();
    }

    record ChatResponse() {
    }

}
