package com.knowcli.agent;

import com.knowcli.llm.DeepSeekClient;
import com.knowcli.llm.DeepSeekClient.Message;
import com.knowcli.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Agent {
    private static final int MAX_STEPS = 8;
    private static final Pattern ACTION = Pattern.compile(
            "(?s)Action:\\s*([\\w.-]+)\\s*\\RAction Input:\\s*(.*)");

    private final DeepSeekClient client;
    private final ToolRegistry tools;

    public Agent(DeepSeekClient client, ToolRegistry tools) {
        this.client = client;
        this.tools = tools;
    }

    public String run(String question) throws IOException, InterruptedException {

        return "已达到最大推理步数，未能生成最终答案。";
    }

    private String systemPrompt() {
        return "prompt";
    }
}
