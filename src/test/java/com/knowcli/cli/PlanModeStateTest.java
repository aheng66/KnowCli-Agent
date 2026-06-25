package com.knowcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanModeStateTest {
    @Test
    void slashPlanEntersAwaitingGoalModeWithoutPlanning() {
        PlanModeState state = new PlanModeState();

        PlanModeState.Result result = state.handle("/plan");

        assertEquals(PlanModeState.Action.MESSAGE, result.action());
        assertTrue(result.message().contains("Plan-and-Execute"));
        assertTrue(state.isAwaitingPlanGoal());
    }

    @Test
    void awaitingGoalAcceptsNextInputAsPlanGoal() {
        PlanModeState state = new PlanModeState();
        state.handle("/plan");

        PlanModeState.Result result = state.handle("add login tests");

        assertEquals(PlanModeState.Action.PLAN_GOAL, result.action());
        assertEquals("add login tests", result.goal());
        assertFalse(result.announceEntry());
        assertFalse(state.isAwaitingPlanGoal());
    }

    @Test
    void awaitingGoalCanBeCancelled() {
        PlanModeState state = new PlanModeState();
        state.handle("/plan");

        PlanModeState.Result result = state.handle("\u53d6\u6d88");

        assertEquals(PlanModeState.Action.MESSAGE, result.action());
        assertTrue(result.message().contains("Plan-and-Execute"));
        assertFalse(state.isAwaitingPlanGoal());
    }

    @Test
    void slashPlanWithTaskKeepsShortcutFlow() {
        PlanModeState state = new PlanModeState();

        PlanModeState.Result result = state.handle("/plan inspect README");

        assertEquals(PlanModeState.Action.PLAN_GOAL, result.action());
        assertEquals("inspect README", result.goal());
        assertTrue(result.announceEntry());
        assertFalse(state.isAwaitingPlanGoal());
    }

    @Test
    void nonPlanInputPassesThrough() {
        PlanModeState state = new PlanModeState();

        PlanModeState.Result result = state.handle("hello");

        assertEquals(PlanModeState.Action.NOT_PLAN, result.action());
        assertFalse(state.isAwaitingPlanGoal());
    }
}