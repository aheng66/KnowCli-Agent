package com.knowcli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowcli.llm.DeepSeekClient;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void createsPlanFromMockedJson() throws Exception {
        server.enqueue(assistantResponse("""
                {
                  "summary": "Read then verify",
                  "tasks": [
                    {"id":"read","description":"Read README","type":"FILE_READ","dependencies":[]},
                    {"id":"verify","description":"Verify answer","type":"VERIFICATION","dependencies":["read"]}
                  ]
                }
                """));
        DeepSeekClient client = new DeepSeekClient("test-key", server.url("/").toString(),
                "test-model");
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("inspect README");

        assertEquals("Read then verify", plan.getSummary());
        assertEquals(List.of("read", "verify"), plan.getExecutionOrder());
        assertEquals(Task.TaskType.FILE_READ, plan.getTask("read").getType());
        assertEquals(List.of("verify"), plan.getTask("read").getDependents());

        JsonNode request = requestJson(takeRequest());
        assertEquals("test-model", request.path("model").asText());
        assertFalse(request.has("tools"));
        assertTrue(request.path("messages").get(0).path("content").asText()
                .contains("输出 JSON 结构"));
        assertTrue(request.path("messages").get(1).path("content").asText()
                .contains("inspect README"));
    }

    @Test
    void acceptsMarkdownFencedJson() throws Exception {
        Planner planner = parserOnlyPlanner();

        ExecutionPlan plan = planner.parsePlan("goal", """
                ```json
                {
                  "summary": "Fenced",
                  "tasks": [
                    {"id":"think","description":"Think","type":"PLANNING","dependencies":[]}
                  ]
                }
                ```
                """);

        assertEquals("Fenced", plan.getSummary());
        assertEquals(List.of("think"), plan.getExecutionOrder());
    }

    @Test
    void rejectsInvalidJsonAndUnknownTaskType() {
        Planner planner = parserOnlyPlanner();

        assertThrows(IOException.class, () -> planner.parsePlan("goal", "not json"));
        assertThrows(IOException.class, () -> planner.parsePlan("goal", """
                {"summary":"bad","tasks":[
                  {"id":"x","description":"Bad","type":"NOPE","dependencies":[]}
                ]}
                """));
    }

    @Test
    void rejectsMissingDependencyAndCycle() {
        Planner planner = parserOnlyPlanner();

        assertThrows(IllegalArgumentException.class, () -> planner.parsePlan("goal", """
                {"summary":"bad","tasks":[
                  {"id":"write","description":"Write","type":"FILE_WRITE","dependencies":["read"]}
                ]}
                """));
        assertThrows(IllegalArgumentException.class, () -> planner.parsePlan("goal", """
                {"summary":"bad","tasks":[
                  {"id":"a","description":"A","type":"ANALYSIS","dependencies":["b"]},
                  {"id":"b","description":"B","type":"ANALYSIS","dependencies":["a"]}
                ]}
                """));
    }

    private Planner parserOnlyPlanner() {
        return new Planner(new DeepSeekClient("test-key", "http://localhost", "test-model"),
                "Return JSON only");
    }

    private MockResponse assistantResponse(String content) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode message = root.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 5);
        usage.put("completion_tokens", 2);
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(root));
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