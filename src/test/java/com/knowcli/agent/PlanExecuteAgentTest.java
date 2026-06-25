package com.knowcli.agent;

import com.knowcli.llm.DeepSeekClient;
import com.knowcli.plan.ExecutionPlan;
import com.knowcli.plan.Planner;
import com.knowcli.plan.Task;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {
    @Test
    void createPlanOnlyCreatesPlanWithoutExecutingTasks() throws IOException {
        ExecutionPlan plan = new ExecutionPlan("ship feature", List.of(
                new Task("read", "Read files", Task.TaskType.FILE_READ, List.of())
        ), "Read only");
        AtomicInteger calls = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(new StubPlanner(plan), prompt -> {
            calls.incrementAndGet();
            return "unused";
        });

        ExecutionPlan created = agent.createPlan("ship feature");

        assertSame(plan, created);
        assertEquals(0, calls.get());
        assertEquals(Task.TaskStatus.PENDING, plan.getTask("read").getStatus());
    }

    @Test
    void executePlanRunsExistingPlanWithoutCreatingANewOne() {
        ExecutionPlan plan = new ExecutionPlan("ship feature", List.of(
                new Task("read", "Read files", Task.TaskType.FILE_READ, List.of()),
                new Task("verify", "Verify result", Task.TaskType.VERIFICATION, List.of("read"))
        ), "Read then verify");
        List<String> prompts = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(new StubPlanner(new IOException("should not plan")), prompt -> {
            prompts.add(prompt);
            return "result-" + calls.incrementAndGet();
        });

        String result = agent.executePlan(plan);

        assertTrue(result.contains("COMPLETED"));
        assertTrue(result.contains("read, verify"));
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).contains("result-1"));
        assertEquals(Task.TaskStatus.COMPLETED, plan.getTask("verify").getStatus());
    }
@Test
    void executesTasksInPlanOrderAndPassesDependencyResults() {
        ExecutionPlan plan = new ExecutionPlan("ship feature", List.of(
                new Task("read", "Read files", Task.TaskType.FILE_READ, List.of()),
                new Task("verify", "Verify result", Task.TaskType.VERIFICATION, List.of("read"))
        ), "Read then verify");
        List<String> prompts = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(new StubPlanner(plan), prompt -> {
            prompts.add(prompt);
            return "result-" + calls.incrementAndGet();
        });

        String result = agent.run("ship feature");

        assertTrue(result.contains("COMPLETED"));
        assertTrue(result.contains("read, verify"));
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(0).contains("- id: read"));
        assertTrue(prompts.get(1).contains("依赖结果:"));
        assertTrue(prompts.get(1).contains("result-1"));
        assertEquals(Task.TaskStatus.COMPLETED, plan.getTask("verify").getStatus());
    }

    @Test
    void stopsOnFailureAndMarksRemainingTasksSkipped() {
        ExecutionPlan plan = new ExecutionPlan("ship feature", List.of(
                new Task("read", "Read files", Task.TaskType.FILE_READ, List.of()),
                new Task("write", "Write files", Task.TaskType.FILE_WRITE, List.of("read")),
                new Task("verify", "Verify result", Task.TaskType.VERIFICATION, List.of("write"))
        ), "Read write verify");
        PlanExecuteAgent agent = new PlanExecuteAgent(new StubPlanner(plan),
                prompt -> "LLM 调用失败: bad key");

        String result = agent.run("ship feature");

        assertTrue(result.contains("计划状态: FAILED"));
        assertTrue(result.contains("失败任务: read"));
        assertTrue(result.contains("跳过任务: write"));
        assertEquals(Task.TaskStatus.FAILED, plan.getTask("read").getStatus());
        assertEquals(Task.TaskStatus.SKIPPED, plan.getTask("write").getStatus());
        assertEquals(Task.TaskStatus.SKIPPED, plan.getTask("verify").getStatus());
    }

    @Test
    void reportsPlannerFailure() {
        PlanExecuteAgent agent = new PlanExecuteAgent(new StubPlanner(new IOException("bad json")),
                prompt -> "unused");

        String result = agent.run("ship feature");

        assertEquals("计划创建失败: bad json", result);
    }

    private static final class StubPlanner extends Planner {
        private final ExecutionPlan plan;
        private final IOException error;

        private StubPlanner(ExecutionPlan plan) {
            super(new DeepSeekClient("test-key", "http://localhost", "test-model"));
            this.plan = plan;
            this.error = null;
        }

        private StubPlanner(IOException error) {
            super(new DeepSeekClient("test-key", "http://localhost", "test-model"));
            this.plan = null;
            this.error = error;
        }

        @Override
        public ExecutionPlan createPlan(String goal) throws IOException {
            if (error != null) {
                throw error;
            }
            return plan;
        }
    }
}