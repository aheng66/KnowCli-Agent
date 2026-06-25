package com.knowcli.cli;

import com.knowcli.agent.PlanExecuteAgent;
import com.knowcli.plan.ExecutionPlan;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

final class PlanInteraction {
    private final PlanExecuteAgent planExecuteAgent;
    private final DecisionSource decisionSource;
    private final ConsoleLineReader lineReader;
    private final PrintStream out;

    PlanInteraction(PlanExecuteAgent planExecuteAgent,
                    DecisionSource decisionSource,
                    ConsoleLineReader lineReader,
                    PrintStream out) {
        this.planExecuteAgent = Objects.requireNonNull(planExecuteAgent, "planExecuteAgent");
        this.decisionSource = Objects.requireNonNull(decisionSource, "decisionSource");
        this.lineReader = Objects.requireNonNull(lineReader, "lineReader");
        this.out = Objects.requireNonNull(out, "out");
    }

    void run(String initialGoal, boolean announceEntry) {
        if (announceEntry) {
            out.println("进入Plan-and-Execute模式");
        }
        String goal = initialGoal;

        while (true) {
            ExecutionPlan plan;
            try {
                plan = planExecuteAgent.createPlan(goal);
            } catch (IOException | IllegalArgumentException exception) {
                out.println("计划创建失败: " + exception.getMessage());
                return;
            }

            out.println(plan.visualize());
            while (true) {
                PlanDecision decision = decisionSource.choose();
                if (decision == PlanDecision.EXECUTE) {
                    out.println(planExecuteAgent.executePlan(plan));
                    return;
                }
                if (decision == PlanDecision.CANCEL) {
                    out.println("已取消Plan-and-Execute模式");
                    return;
                }

                String supplement = readSupplement();
                if (supplement.isBlank()) {
                    out.println("补充内容不能为空。");
                    continue;
                }
                goal = mergeSupplement(goal, supplement);
                break;
            }
        }
    }

    private String readSupplement() {
        String supplement = lineReader.readLine("请输入补充内容: ");
        return supplement == null ? "" : supplement.trim();
    }

    private String mergeSupplement(String goal, String supplement) {
        return goal + "\n\n用户补充要求:\n" + supplement;
    }

    @FunctionalInterface
    interface DecisionSource {
        PlanDecision choose();
    }

    @FunctionalInterface
    interface ConsoleLineReader {
        String readLine(String prompt);
    }
}