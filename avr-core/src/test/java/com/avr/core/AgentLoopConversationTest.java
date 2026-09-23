package com.avr.core;

import com.avr.api.AgentRequest;
import com.avr.api.Llm;
import com.avr.api.LlmResponse;
import com.avr.api.Message;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolCall;
import com.avr.api.RunState;
import com.avr.core.tool.FileOpTool;
import com.avr.core.tool.PlanTool;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 Runtime 在模型调用前恢复多轮用户/助手上下文。 */
class AgentLoopConversationTest {
    @Test
    void includesHistoryBeforeCurrentPrompt() {
        List<Message> observed = new ArrayList<Message>();
        Llm model = new Llm() {
            @Override
            public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                observed.addAll(messages);
                return LlmResponse.answer("finished");
            }
        };

        new AgentLoop(model, DefaultToolRegistry.builder().build()).run(
                AgentRequest.builder()
                        .prompt("current question")
                        .history(Arrays.asList(
                                Message.user("previous question"),
                                Message.assistant("previous answer")))
                        .workspace(new MemoryWorkspace("conversation-test"))
                        .build());

        assertEquals(Message.Role.SYSTEM, observed.get(0).getRole());
        assertEquals("previous question", observed.get(1).getContent());
        assertEquals("previous answer", observed.get(2).getContent());
        assertEquals("current question", observed.get(3).getContent());
    }

    @Test
    void rejectsToolMessagesInPriorConversation() {
        assertThrows(IllegalArgumentException.class, () -> AgentRequest.builder()
                .prompt("hello")
                .workspace(new MemoryWorkspace("invalid-history"))
                .history(Arrays.asList(Message.tool("call-1", "result")))
                .build());
    }

    @Test
    void planModeRejectsFabricatedCompletionWithoutToolCalls() {
        Llm model = new Llm() {
            @Override
            public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                return LlmResponse.answer("The report is done.");
            }
        };
        AgentLoop loop = new AgentLoop(model, DefaultToolRegistry.builder()
                .register(new PlanTool()).build());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> loop.run(AgentRequest.builder()
                        .prompt("Write a report")
                        .workspace(new MemoryWorkspace("unverified"))
                        .planMode(true)
                        .build()));
        assertEquals(true, failure.getMessage().contains("completion check failed"));
    }

    @Test
    void planModeCompletesAfterVerifiedToolSequence() {
        AtomicInteger turns = new AtomicInteger();
        Llm model = new Llm() {
            @Override
            public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                if (turns.incrementAndGet() == 1) {
                    return LlmResponse.calls(Arrays.asList(
                            new ToolCall("plan-create", "plan.manage", Map.of(
                                    "op", "create", "steps", List.of(Map.of(
                                            "id", "report", "description", "Write report",
                                            "verifyPath", "/workspace/report.html", "minBytes", 10)))),
                            new ToolCall("file-write", "file.op", Map.of(
                                    "op", "write", "path", "/workspace/report.html",
                                    "content", "<html>done</html>")),
                            new ToolCall("plan-update", "plan.manage", Map.of(
                                    "op", "update", "stepId", "report",
                                    "status", "done")),
                            new ToolCall("plan-check", "plan.manage", Map.of("op", "check"))));
                }
                return LlmResponse.answer("Report written.");
            }
        };
        MemoryWorkspace workspace = new MemoryWorkspace("verified");
        AgentLoop loop = new AgentLoop(model, DefaultToolRegistry.builder()
                .register(new FileOpTool()).register(new PlanTool()).build());
        assertEquals(RunState.COMPLETED, loop.run(AgentRequest.builder()
                .prompt("Write a report").workspace(workspace).planMode(true).build()).getState());
        assertEquals("<html>done</html>", workspace.readText("/workspace/report.html"));
    }
}
