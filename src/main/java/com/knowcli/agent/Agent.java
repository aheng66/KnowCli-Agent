package com.knowcli.agent;

import com.knowcli.llm.DeepSeekClient;
import com.knowcli.llm.DeepSeekClient.ChatResponse;
import com.knowcli.llm.DeepSeekClient.Message;
import com.knowcli.llm.DeepSeekClient.ToolCall;
import com.knowcli.tool.ToolRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Agent {
    private static final int MAX_ITERATIONS = 20;
    private static final int MAX_LLM_ATTEMPTS = 5;
    private static final long TASK_TIMEOUT_SECONDS = 300;

    private final DeepSeekClient client;
    private final ToolRegistry toolRegistry;
    private final List<Message> conversationHistory;
    private final String systemPrompt;

    public Agent(String apiKey) {
        this(new DeepSeekClient(apiKey), new ToolRegistry(), loadSystemPrompt());
    }

    public Agent(DeepSeekClient client, ToolRegistry toolRegistry) {
        this(client, toolRegistry, loadSystemPrompt());
    }

    public Agent(DeepSeekClient client, ToolRegistry toolRegistry, String systemPrompt) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        this.systemPrompt = systemPrompt.strip();
    }

    public synchronized String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "用户输入不能为空";
        }

        List<Message> workingHistory = new ArrayList<>(conversationHistory);
        if (workingHistory.isEmpty()) {
            workingHistory.add(Message.system(systemPrompt));
        }
        workingHistory.add(Message.user(userInput));
        List<DeepSeekClient.Tool> toolDefinitions = toolRegistry.getToolDefinitions();
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(TASK_TIMEOUT_SECONDS);

        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "knowcli-agent-task");
            thread.setDaemon(true);
            return thread;
        });

        try {
            for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                ChatResponse response = callLlmWithRetry(
                        workingHistory, toolDefinitions, executor, deadlineNanos);

                String role = response.role() == null || response.role().isBlank()
                        ? "assistant"
                        : response.role();
                workingHistory.add(new Message(
                        role,
                        response.content(),
                        response.toolCalls(),
                        null
                ));

                if (!response.hasToolCalls()) {
                    if (response.content() == null || response.content().isBlank()) {
                        return "LLM 返回了空内容";
                    }
                    conversationHistory.clear();
                    conversationHistory.addAll(workingHistory);
                    return response.content();
                }

                for (ToolCall toolCall : response.toolCalls()) {
                    if (toolCall == null || toolCall.function() == null) {
                        return "LLM 返回了无效的工具调用";
                    }
                    String toolResult = executeToolWithDeadline(
                            toolCall, executor, deadlineNanos);
                    workingHistory.add(Message.tool(toolCall.id(), toolResult));
                }
            }
            return "已达到最大推理轮数（20），未能生成最终答案";
        } catch (DeepSeekClient.ApiException exception) {
            return "LLM 调用失败: " + exception.getMessage();
        } catch (TimeoutException exception) {
            return "任务执行超时：已超过 300 秒";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "任务执行被中断";
        } catch (IOException exception) {
            return "网络请求失败（最多已尝试 5 次）: " + exception.getMessage();
        } catch (ExecutionException exception) {
            return "工具执行失败: " + rootMessage(exception);
        } finally {
            executor.shutdownNow();
        }
    }

    public synchronized void clearConversationHistory() {
        conversationHistory.clear();
    }

    private ChatResponse callLlmWithRetry(
            List<Message> history,
            List<DeepSeekClient.Tool> toolDefinitions,
            ExecutorService executor,
            long deadlineNanos
    ) throws IOException, InterruptedException, TimeoutException {
        IOException lastNetworkError = null;

        for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
            try {
                return await(
                        executor.submit(() -> client.chat(history, toolDefinitions)),
                        deadlineNanos
                );
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof DeepSeekClient.ApiException apiException) {
                    throw apiException;
                }
                if (!(cause instanceof IOException ioException)) {
                    throw new IOException("LLM 调用发生异常", cause);
                }
                lastNetworkError = ioException;
            }

            if (attempt < MAX_LLM_ATTEMPTS) {
                long delaySeconds = 1L << (attempt - 1);
                sleepBeforeRetry(delaySeconds, deadlineNanos);
            }
        }

        throw lastNetworkError == null
                ? new IOException("LLM 调用失败")
                : lastNetworkError;
    }

    private String executeToolWithDeadline(
            ToolCall toolCall,
            ExecutorService executor,
            long deadlineNanos
    ) throws InterruptedException, ExecutionException, TimeoutException {
        return await(
                executor.submit(() -> toolRegistry.executeToolCalls(
                        toolCall.function().name(),
                        toolCall.function().arguments()
                )),
                deadlineNanos
        );
    }

    private <T> T await(Future<T> future, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            future.cancel(true);
            throw new TimeoutException("task deadline exceeded");
        }
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw exception;
        }
    }

    private void sleepBeforeRetry(long delaySeconds, long deadlineNanos)
            throws InterruptedException, TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        long delayNanos = TimeUnit.SECONDS.toNanos(delaySeconds);
        if (remainingNanos <= delayNanos) {
            throw new TimeoutException("task deadline exceeded during retry delay");
        }
        TimeUnit.NANOSECONDS.sleep(delayNanos);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private static String loadSystemPrompt() {
        try (InputStream stream = Agent.class.getResourceAsStream("/SYSTEM_PROMPT.txt")) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing classpath resource: /SYSTEM_PROMPT.txt");
            }
            String prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (prompt.isBlank()) {
                throw new IllegalStateException("System prompt resource must not be blank");
            }
            return prompt;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load system prompt", exception);
        }
    }
}
