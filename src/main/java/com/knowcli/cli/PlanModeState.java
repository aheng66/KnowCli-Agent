package com.knowcli.cli;

final class PlanModeState {
    private boolean awaitingPlanGoal;

    Result handle(String input) {
        String command = input == null ? "" : input.trim();
        if (command.equalsIgnoreCase("/plan")) {
            awaitingPlanGoal = true;
            return Result.message("\u8fdb\u5165Plan-and-Execute\u6a21\u5f0f\uff0c\u8bf7\u8f93\u5165\u8981\u89c4\u5212\u7684\u4efb\u52a1\uff1b\u8f93\u5165 \u53d6\u6d88 \u9000\u51fa\u3002");
        }

        if (awaitingPlanGoal) {
            if (command.equalsIgnoreCase("n") || command.equals("\u53d6\u6d88")) {
                awaitingPlanGoal = false;
                return Result.message("\u5df2\u53d6\u6d88Plan-and-Execute\u6a21\u5f0f");
            }
            awaitingPlanGoal = false;
            return Result.planGoal(command, false);
        }

        if (command.startsWith("/plan ")) {
            String goal = command.substring("/plan ".length()).trim();
            if (goal.isEmpty()) {
                return Result.message("Usage: /plan <task>");
            }
            return Result.planGoal(goal, true);
        }

        return Result.notPlan();
    }

    boolean isAwaitingPlanGoal() {
        return awaitingPlanGoal;
    }

    enum Action {
        NOT_PLAN,
        MESSAGE,
        PLAN_GOAL
    }

    record Result(Action action, String message, String goal, boolean announceEntry) {
        static Result notPlan() {
            return new Result(Action.NOT_PLAN, "", "", false);
        }

        static Result message(String message) {
            return new Result(Action.MESSAGE, message, "", false);
        }

        static Result planGoal(String goal, boolean announceEntry) {
            return new Result(Action.PLAN_GOAL, "", goal, announceEntry);
        }
    }
}