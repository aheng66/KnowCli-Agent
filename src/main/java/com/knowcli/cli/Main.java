package com.knowcli.cli;

import com.knowcli.agent.Agent;
import com.knowcli.llm.DeepSeekClient;
import com.knowcli.tool.ToolRegistry;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.Scanner;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String apiKey = value(dotenv, "DEEPSEEK_API_KEY", "");
        String baseUrl = value(dotenv, "DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String model = value(dotenv, "DEEPSEEK_MODEL", "deepseek-chat");

        if (apiKey.isBlank()) {
            System.err.println("请先在 .env 中设置 DEEPSEEK_API_KEY。");
            return;
        }

        ToolRegistry tools = new ToolRegistry();

    }

    private static String value(Dotenv dotenv, String key, String fallback) {
        String value = dotenv.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
