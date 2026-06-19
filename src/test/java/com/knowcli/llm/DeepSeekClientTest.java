package com.knowcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private DeepSeekClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new DeepSeekClient("test-key", server.url("/").toString(), "test-model");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sendsMessagesAndParsesTextResponse() throws Exception {
        server.enqueue(jsonResponse(200, """
                {
                  "choices": [{
                    "finish_reason": "stop",
                    "message": {
                      "role": "assistant",
                      "content": "Hello",
                      "reasoning_content": null
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 4,
                    "total_tokens": 16
                  }
                }
                """));

        DeepSeekClient.ChatResponse result = client.chat(
                List.of(DeepSeekClient.Message.system("Be helpful"),
                        DeepSeekClient.Message.user("Hi")),
                List.of());

        assertEquals("assistant", result.role());
        assertEquals("Hello", result.content());
        assertNull(result.reasoningContent());
        assertEquals(12, result.inputTokens());
        assertEquals(4, result.outputTokens());
        assertFalse(result.hasToolCalls());

        RecordedRequest request = takeRequest();
        assertEquals("/chat/completions", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
        JsonNode requestJson = mapper.readTree(request.getBody().readUtf8());
        assertEquals("test-model", requestJson.path("model").asText());
        assertFalse(requestJson.path("stream").asBoolean());
        assertEquals("system", requestJson.path("messages").get(0).path("role").asText());
        assertEquals("Hi", requestJson.path("messages").get(1).path("content").asText());
        assertFalse(requestJson.has("tools"));
    }

    @Test
    void serializesToolsAndToolHistoryAndParsesToolCalls() throws Exception {
        server.enqueue(jsonResponse(200, """
                {
                  "choices": [{
                    "finish_reason": "tool_calls",
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-new",
                        "type": "function",
                        "function": {
                          "name": "read_file",
                          "arguments": "{\\\"path\\\":\\\"pom.xml\\\"}"
                        }
                      }]
                    }
                  }],
                  "usage": {"prompt_tokens": 20, "completion_tokens": 6}
                }
                """));

        DeepSeekClient.ToolCall previousCall = new DeepSeekClient.ToolCall(
                "call-old",
                new DeepSeekClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}"));
        DeepSeekClient.Message previousAssistant = new DeepSeekClient.Message(
                "assistant", null, List.of(previousCall), null);
        DeepSeekClient.Message toolResult = DeepSeekClient.Message.tool(
                "call-old", "README contents");

        JsonNode parameters = mapper.readTree("""
                {
                  "type": "object",
                  "properties": {"path": {"type": "string"}},
                  "required": ["path"]
                }
                """);
        DeepSeekClient.Tool tool = new DeepSeekClient.Tool(
                "read_file", "Read a file", parameters);

        DeepSeekClient.ChatResponse result = client.chat(
                List.of(previousAssistant, toolResult), List.of(tool));

        assertTrue(result.hasToolCalls());
        assertNull(result.content());
        assertEquals("call-new", result.toolCalls().get(0).id());
        assertEquals("read_file", result.toolCalls().get(0).function().name());
        assertEquals("{\"path\":\"pom.xml\"}",
                result.toolCalls().get(0).function().arguments());

        JsonNode requestJson = mapper.readTree(takeRequest().getBody().readUtf8());
        JsonNode assistantJson = requestJson.path("messages").get(0);
        assertTrue(assistantJson.path("content").isNull());
        assertEquals("function", assistantJson.path("tool_calls").get(0).path("type").asText());
        assertEquals("call-old",
                requestJson.path("messages").get(1).path("tool_call_id").asText());
        assertEquals("function", requestJson.path("tools").get(0).path("type").asText());
        assertEquals(parameters,
                requestJson.path("tools").get(0).path("function").path("parameters"));
    }

    @Test
    void throwsIOExceptionForHttpError() {
        server.enqueue(jsonResponse(401, "{\"error\":{\"message\":\"invalid key\"}}"));

        IOException error = assertThrows(IOException.class,
                () -> client.chat(List.of(DeepSeekClient.Message.user("Hi")), null));

        assertTrue(error.getMessage().contains("HTTP 401"));
        assertTrue(error.getMessage().contains("invalid key"));
    }

    @Test
    void throwsIOExceptionForMalformedSuccessfulResponse() {
        server.enqueue(jsonResponse(200, "{\"choices\":[]}"));

        IOException error = assertThrows(IOException.class,
                () -> client.chat(List.of(DeepSeekClient.Message.user("Hi")), null));

        assertTrue(error.getMessage().contains("choices[0]"));
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        if (request == null) {
            throw new AssertionError("Expected an HTTP request");
        }
        return request;
    }
}
