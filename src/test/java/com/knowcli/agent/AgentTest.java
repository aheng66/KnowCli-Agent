package com.knowcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowcli.llm.DeepSeekClient;
import com.knowcli.tool.ToolRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private Agent agent;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        DeepSeekClient client = new DeepSeekClient(
                "test-key", server.url("/").toString(), "test-model");
        agent = new Agent(client, new ToolRegistry());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void keepsSuccessfulConversationHistoryAcrossRuns() throws Exception {
        server.enqueue(assistantResponse("first answer"));
        server.enqueue(assistantResponse("second answer"));

        assertEquals("first answer", agent.run("first question"));
        assertEquals("second answer", agent.run("second question"));

        takeRequest();
        JsonNode secondRequest = requestJson(takeRequest());
        JsonNode messages = secondRequest.path("messages");
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("first question", messages.get(1).path("content").asText());
        assertEquals("first answer", messages.get(2).path("content").asText());
        assertEquals("second question", messages.get(3).path("content").asText());
    }

    @Test
    void executesToolAndSendsResultBackToModel() throws Exception {
        server.enqueue(jsonResponse(200, """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-1",
                        "type": "function",
                        "function": {
                          "name": "missing_tool",
                          "arguments": "{}"
                        }
                      }]
                    }
                  }],
                  "usage": {"prompt_tokens": 5, "completion_tokens": 2}
                }
                """));
        server.enqueue(assistantResponse("tool handled"));

        assertEquals("tool handled", agent.run("use a tool"));

        takeRequest();
        JsonNode followUp = requestJson(takeRequest());
        JsonNode messages = followUp.path("messages");
        assertEquals("assistant", messages.get(2).path("role").asText());
        assertEquals("call-1",
                messages.get(2).path("tool_calls").get(0).path("id").asText());
        assertEquals("tool", messages.get(3).path("role").asText());
        assertEquals("call-1", messages.get(3).path("tool_call_id").asText());
        assertTrue(messages.get(3).path("content").asText().contains("missing_tool"));
    }

    @Test
    void rollsBackFailedRunAndDoesNotRetryHttpErrors() throws Exception {
        server.enqueue(jsonResponse(401, "{\"error\":{\"message\":\"invalid key\"}}"));
        server.enqueue(assistantResponse("recovered"));

        String failure = agent.run("failed question");
        assertTrue(failure.contains("HTTP 401"));
        assertEquals(1, server.getRequestCount());

        assertEquals("recovered", agent.run("new question"));
        takeRequest();
        JsonNode recoveredRequest = requestJson(takeRequest());
        JsonNode messages = recoveredRequest.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("new question", messages.get(1).path("content").asText());
    }

    @Test
    void clearConversationStartsAgainWithSystemPrompt() throws Exception {
        server.enqueue(assistantResponse("first answer"));
        server.enqueue(assistantResponse("fresh answer"));

        assertEquals("first answer", agent.run("first question"));
        agent.clearConversationHistory();
        assertEquals("fresh answer", agent.run("fresh question"));

        takeRequest();
        JsonNode freshRequest = requestJson(takeRequest());
        JsonNode messages = freshRequest.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("fresh question", messages.get(1).path("content").asText());
    }

    private MockResponse assistantResponse(String content) {
        return jsonResponse(200, """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "%s"
                    }
                  }],
                  "usage": {"prompt_tokens": 5, "completion_tokens": 2}
                }
                """.formatted(content));
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request, "Expected an HTTP request");
        return request;
    }

    private JsonNode requestJson(RecordedRequest request) throws IOException {
        return mapper.readTree(request.getBody().readUtf8());
    }
}
