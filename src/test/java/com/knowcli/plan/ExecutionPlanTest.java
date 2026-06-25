package com.knowcli.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanTest {
    @Test
    void ordersDependenciesBeforeDependentsAndWiresDependents() {
        Task verify = new Task("verify", "Verify result", Task.TaskType.VERIFICATION,
                List.of("analyze"));
        Task read = new Task("read", "Read files", Task.TaskType.FILE_READ, List.of());
        Task analyze = new Task("analyze", "Analyze files", Task.TaskType.ANALYSIS,
                List.of("read"));

        ExecutionPlan plan = new ExecutionPlan("inspect project",
                List.of(verify, read, analyze), "Inspect then verify");

        assertEquals(List.of("read", "analyze", "verify"), plan.getExecutionOrder());
        assertEquals(List.of("analyze"), plan.getTask("read").getDependents());
        assertEquals(List.of("verify"), plan.getTask("analyze").getDependents());
    }

    @Test
    void rejectsMissingDependenciesAndCycles() {
        Task missing = new Task("write", "Write file", Task.TaskType.FILE_WRITE,
                List.of("read"));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionPlan("missing dependency", List.of(missing), "bad"));

        Task first = new Task("first", "First", Task.TaskType.ANALYSIS, List.of("second"));
        Task second = new Task("second", "Second", Task.TaskType.ANALYSIS, List.of("first"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExecutionPlan("cycle", List.of(first, second), "bad"));
        assertTrue(error.getMessage().contains("cycle"));
    }

    @Test
    void visualizesPlanAndTracksStatus() {
        Task task = new Task("read", "Read files", Task.TaskType.FILE_READ, List.of());
        ExecutionPlan plan = new ExecutionPlan("inspect project", List.of(task), "One step");

        String visualization = plan.visualize();
        assertTrue(visualization.contains("计划: inspect project"));
        assertTrue(visualization.contains("[FILE_READ] read - Read files"));
        assertEquals(ExecutionPlan.PlanStatus.CREATED, plan.getStatus());

        plan.markStarted();
        assertEquals(ExecutionPlan.PlanStatus.RUNNING, plan.getStatus());
        plan.markCompleted();
        assertEquals(ExecutionPlan.PlanStatus.COMPLETED, plan.getStatus());
    }
}