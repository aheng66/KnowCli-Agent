package com.knowcli.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanDecisionMenuTest {
    private static final int ESC = 27;
    private static final int CTRL_C = 3;
    private static final int CTRL_D = 4;

    @Test
    void enterChoosesDefaultExecute() {
        assertEquals(PlanDecision.EXECUTE, choose('\n'));
    }

    @Test
    void downThenEnterChoosesSupplement() {
        assertEquals(PlanDecision.SUPPLEMENT, choose(ESC, '[', 'B', '\n'));
    }

    @Test
    void upFromFirstOptionWrapsToCancel() {
        assertEquals(PlanDecision.CANCEL, choose(ESC, '[', 'A', '\n'));
    }

    @Test
    void unrelatedKeyDoesNotChangeSelection() {
        assertEquals(PlanDecision.EXECUTE, choose('x', '\n'));
    }

    @Test
    void ctrlCChoosesCancel() {
        assertEquals(PlanDecision.CANCEL, choose(CTRL_C));
    }

    @Test
    void ctrlDChoosesCancel() {
        assertEquals(PlanDecision.CANCEL, choose(CTRL_D));
    }

    private PlanDecision choose(int... keys) {
        StringWriter output = new StringWriter();
        int[] index = {0};
        PlanDecisionMenu menu = new PlanDecisionMenu(
                () -> index[0] < keys.length ? keys[index[0]++] : -1,
                new PrintWriter(output)
        );
        return menu.choose();
    }
}