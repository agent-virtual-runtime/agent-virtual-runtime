package com.avr.core;

import com.avr.api.Message;
import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolResult;
import com.avr.api.Llm;
import com.avr.api.LlmResponse;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentToolExecutionTest {
    @Test
    void runsIndependentCallsConcurrentlyAndReturnsResultsInCallOrder() {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch started = new CountDownLatch(3);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximumActive = new AtomicInteger();
            Tool tool = parallelTool(started, active, maximumActive);
            DefaultToolRegistry registry = DefaultToolRegistry.builder()
                    .register(tool)
                    .build();
            RecordingLlm llm = new RecordingLlm();
            AgentLoopOptions options = AgentLoopOptions.builder()
                    .toolExecutor(executor)
                    .toolTimeout(Duration.ofSeconds(2))
                    .build();

            AgentResult result = new AgentLoop(
                    llm, registry, (context, workspace, call) -> { }, options)
                    .run(AgentRequest.builder()
                    .prompt("parallel")
                    .workspace(new MemoryWorkspace("test"))
                    .build());

            assertEquals("done", result.getText());
            assertTrue(maximumActive.get() >= 2);
            assertEquals(list("result-1", "result-2", "result-3"), llm.toolResults);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void convertsToolTimeoutIntoAToolFailure() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Tool slow = new Tool() {
                @Override
                public ToolDefinition definition() {
                    return new ToolDefinition("slow", "slow test tool");
                }

                @Override
                public ToolResult execute(
                        ToolCall call, ToolContext context) {
                    try {
                        Thread.sleep(1_000);
                        return ToolResult.success("late");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return ToolResult.failure("interrupted");
                    }
                }

                @Override
                public ToolExecutionMode executionMode() {
                    return ToolExecutionMode.SEQUENTIAL;
                }
            };
            DefaultToolRegistry registry = DefaultToolRegistry.builder()
                    .register(slow)
                    .build();
            ScriptedLlm llm = ScriptedLlm.of(
                    LlmResponse.calls(Collections.singletonList(
                            new ToolCall("slow-1", "slow",
                                    Collections.<String, Object>emptyMap()))),
                    LlmResponse.answer("recovered"));
            AgentLoopOptions options = AgentLoopOptions.builder()
                    .toolExecutor(executor)
                    .toolTimeout(Duration.ofMillis(20))
                    .maxNoProgressRounds(2)
                    .build();

            AgentResult result = new AgentLoop(
                    llm, registry, (context, workspace, call) -> { }, options)
                    .run(AgentRequest.builder()
                            .prompt("timeout")
                            .workspace(new MemoryWorkspace("test"))
                            .build());

            assertEquals("recovered", result.getText());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Tool parallelTool(
            CountDownLatch started,
            AtomicInteger active,
            AtomicInteger maximumActive) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("parallel", "test tool");
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext context) {
                int running = active.incrementAndGet();
                maximumActive.accumulateAndGet(running, Math::max);
                started.countDown();
                try {
                    started.await(1, TimeUnit.SECONDS);
                    return ToolResult.success("result-" + call.requireString("number"));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return ToolResult.failure("interrupted");
                } finally {
                    active.decrementAndGet();
                }
            }
        };
    }

    private static List<String> list(String... values) {
        List<String> result = new ArrayList<String>();
        Collections.addAll(result, values);
        return result;
    }

    private static final class RecordingLlm implements Llm {
        private int calls;
        private final List<String> toolResults = new ArrayList<String>();

        @Override
        public LlmResponse chat(
                List<Message> messages,
                List<ToolDefinition> tools) {
            if (calls++ == 0) {
                List<ToolCall> requested = new ArrayList<ToolCall>();
                for (int number = 1; number <= 3; number++) {
                    Map<String, Object> arguments = new LinkedHashMap<String, Object>();
                    arguments.put("number", String.valueOf(number));
                    requested.add(new ToolCall(
                            "call-" + number, "parallel", arguments));
                }
                return LlmResponse.calls(requested);
            }
            for (Message message : messages) {
                if (message.getRole() == Message.Role.TOOL) {
                    toolResults.add(message.getContent());
                }
            }
            return LlmResponse.answer("done");
        }
    }
}
