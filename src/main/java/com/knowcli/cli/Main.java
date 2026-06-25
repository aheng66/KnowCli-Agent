package com.knowcli.cli;

import com.knowcli.agent.Agent;
import com.knowcli.agent.PlanExecuteAgent;
import io.github.cdimascio.dotenv.Dotenv;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;

public final class Main {
    private Main() {
    }

    private static void printBanner() {
        System.out.print("""
                ╔══════════════════════════════════════════════════════════════════════╗
                ║  ██╗  ██╗███╗   ██╗ ██████╗ ██╗    ██╗ ██████╗██╗     ██╗            ║
                ║  ██║ ██╔╝████╗  ██║██╔═══██╗██║    ██║██╔════╝██║     ██║            ║
                ║  █████╔╝ ██╔██╗ ██║██║   ██║██║ █╗ ██║██║     ██║     ██║            ║
                ║  ██╔═██╗ ██║╚██╗██║██║   ██║██║███╗██║██║     ██║     ██║            ║
                ║  ██║  ██╗██║ ╚████║╚██████╔╝╚███╔███╔╝╚██████╗███████╗██║            ║
                ║  ╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝  ╚══╝╚══╝  ╚═════╝╚══════╝╚═╝            ║
                ╠══════════════════════════════════════════════════════════════════════╣
                ║                  本地智能编码助手  Author：Aheng666                     ║
                ║                   Read · Build · Test · Improve                      ║
                ╚══════════════════════════════════════════════════════════════════════╝

                """);
    }

    public static void main(String[] args) {
        printBanner();
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String apiKey = value(dotenv, "DEEPSEEK_API_KEY", "");

        if (apiKey.isBlank()) {
            System.err.println("Please set DEEPSEEK_API_KEY in .env first.");
            return;
        }

        Agent agent = new Agent(apiKey);
        PlanExecuteAgent planExecuteAgent = new PlanExecuteAgent(apiKey);
        PlanModeState planModeState = new PlanModeState();
        System.out.println("KnowCLI 已启动. 输入 'clear' 清除对话历史, 'exit' 退出.");
        System.out.println("使用 '/plan' 进入计划模式.");

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();
            PlanDecisionMenu decisionMenu = new PlanDecisionMenu(terminal);
            PlanInteraction planInteraction = new PlanInteraction(
                    planExecuteAgent,
                    decisionMenu::choose,
                    prompt -> readOptionalLine(lineReader, prompt),
                    System.out
            );

            while (true) {
                String input = readMainLine(lineReader, "> ");
                if (input == null) {
                    break;
                }
                if (input.isEmpty()) {
                    continue;
                }
                if (input.equalsIgnoreCase("exit")) {
                    break;
                }
                if (input.equalsIgnoreCase("clear")) {
                    agent.clearConversationHistory();
                    System.out.println("对话历史已清理.");
                    continue;
                }

                PlanModeState.Result planCommand = planModeState.handle(input);
                if (planCommand.action() == PlanModeState.Action.MESSAGE) {
                    System.out.println(planCommand.message());
                    continue;
                }
                if (planCommand.action() == PlanModeState.Action.PLAN_GOAL) {
                    planInteraction.run(planCommand.goal(), planCommand.announceEntry());
                    continue;
                }
                System.out.println(agent.run(input));
            }
        } catch (IOException exception) {
            System.err.println("Failed to initialize terminal: " + exception.getMessage());
        }
    }

    private static String readMainLine(LineReader lineReader, String prompt) {
        try {
            String line = lineReader.readLine(prompt);
            return line == null ? null : line.trim();
        } catch (EndOfFileException | UserInterruptException exception) {
            System.out.println();
            return null;
        }
    }

    private static String readOptionalLine(LineReader lineReader, String prompt) {
        try {
            String line = lineReader.readLine(prompt);
            return line == null ? "" : line.trim();
        } catch (EndOfFileException | UserInterruptException exception) {
            System.out.println();
            return "";
        }
    }
    private static String value(Dotenv dotenv, String key, String fallback) {
        String value = dotenv.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
