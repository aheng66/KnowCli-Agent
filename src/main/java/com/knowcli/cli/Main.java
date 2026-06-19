package com.knowcli.cli;

import com.knowcli.agent.Agent;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.Scanner;

public final class Main {
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
                ║                  本地智能编码助手  Author：Aheng666                  ║
                ║                   Read · Build · Test · Improve                      ║
                ╚══════════════════════════════════════════════════════════════════════╝

                """);
    }

    public static void main(String[] args) {
        printBanner();
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String apiKey = value(dotenv, "DEEPSEEK_API_KEY", "");

        if (apiKey.isBlank()) {
            System.err.println("请先在 .env 中设置 DEEPSEEK_API_KEY。");
            return;
        }

        Agent agent = new Agent(apiKey);
        System.out.println("KnowCLI 已启动。输入 'clear' 清空历史，输入 'exit' 退出。");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) {
                    System.out.println();
                    break;
                }

                String input = scanner.nextLine().trim();
                if (input.isEmpty()) {
                    continue;
                }
                if (input.equalsIgnoreCase("exit")) {
                    break;
                }
                if (input.equalsIgnoreCase("clear")) {
                    agent.clearConversationHistory();
                    System.out.println("会话历史已清空。");
                    continue;
                }

                System.out.println(agent.run(input));
            }
        }
    }

    private static String value(Dotenv dotenv, String key, String fallback) {
        String value = dotenv.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
