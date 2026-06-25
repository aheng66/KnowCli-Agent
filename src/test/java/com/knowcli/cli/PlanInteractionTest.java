package com.knowcli.cli;

import com.knowcli.agent.PlanExecuteAgent;
import com.knowcli.llm.DeepSeekClient;
import com.knowcli.plan.ExecutionPlan;
import com.knowcli.plan.Planner;
import com.knowcli.plan.Task;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanInteractionTest {
    @Test
    void executeSelectionRunsCurrentPlanWithoutReplanning() {
        RecordingPlanner planner = new RecordingPlanner(oneTaskPlan("first"));
        AtomicInteger taskExecutions = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(planner, prompt -> {
            taskExecutions.incrementAndGet();
            return "done";
        });
        PlanInteraction interaction = newInteraction(agent, decisions(PlanDecision.EXECUTE), prompt -> "");

        interaction.run("ship feature", false);

        assertEquals(List.of("ship feature"), planner.goals());
        assertEquals(1, taskExecutions.get());
    }

    @Test
    void supplementSelectionReadsSupplementAndReplans() {
        RecordingPlanner planner = new RecordingPlanner(oneTaskPlan("first"), oneTaskPlan("second"));
        AtomicInteger taskExecutions = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(planner, prompt -> {
            taskExecutions.incrementAndGet();
            return "done";
        });
        PlanInteraction interaction = newInteraction(
                agent,
                decisions(PlanDecision.SUPPLEMENT, PlanDecision.EXECUTE),
                prompt -> "add verification"
        );

        interaction.run("ship feature", false);

        assertEquals(2, planner.goals().size());
        assertEquals("ship feature", planner.goals().get(0));
        assertTrue(planner.goals().get(1).contains("ship feature"));
        assertTrue(planner.goals().get(1).contains("add verification"));
        assertEquals(1, taskExecutions.get());
    }

    @Test
    void cancelSelectionDoesNotExecutePlan() {
        RecordingPlanner planner = new RecordingPlanner(oneTaskPlan("first"));
        AtomicInteger taskExecutions = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(planner, prompt -> {
            taskExecutions.incrementAndGet();
            return "done";
        });
        PlanInteraction interaction = newInteraction(agent, decisions(PlanDecision.CANCEL), prompt -> "");

        interaction.run("ship feature", false);

        assertEquals(List.of("ship feature"), planner.goals());
        assertEquals(0, taskExecutions.get());
    }

    private PlanInteraction newInteraction(PlanExecuteAgent agent,
                                           PlanInteraction.DecisionSource decisionSource,
                                           PlanInteraction.ConsoleLineReader lineReader) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        return new PlanInteraction(
                agent,
                decisionSource,
                lineReader,
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );
    }

    private PlanInteraction.DecisionSource decisions(PlanDecision... decisions) {
        AtomicInteger index = new AtomicInteger();
        return () -> decisions[Math.min(index.getAndIncrement(), decisions.length - 1)];
    }

    private static ExecutionPlan oneTaskPlan(String id) {
        return new ExecutionPlan("ship feature", List.of(
                new Task(id, "Do task " + id, Task.TaskType.VERIFICATION, List.of())
        ), "single task");
    }

    private static final class RecordingPlanner extends Planner {
        private final Deque<ExecutionPlan> plans = new ArrayDeque<>();
        private final List<String> goals = new ArrayList<>();

        private RecordingPlanner(ExecutionPlan... plans) {
            super(new DeepSeekClient("test-key", "http://localhost", "test-model"));
            this.plans.addAll(List.of(plans));
        }

        @Override
        public ExecutionPlan createPlan(String goal) throws IOException {
            goals.add(goal);
            if (plans.isEmpty()) {
                throw new IOException("no plan configured");
            }
            return plans.removeFirst();
        }

        private List<String> goals() {
            return goals;
        }
    }
}