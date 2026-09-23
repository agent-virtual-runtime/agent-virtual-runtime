package com.avr.core;

import com.avr.api.AgentRequest;
import com.avr.api.LlmResponse;
import com.avr.api.RunSnapshot;
import com.avr.api.RunState;
import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventListener;
import com.avr.api.ToolPolicy;
import com.avr.api.ToolCall;
import com.avr.core.tool.FileOpTool;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEventTest {

    @Test
    void publishesEventsWithoutExposingListenerOnAgentRequest() {
        InMemoryRunTracker tracker = new InMemoryRunTracker(10, 20);
        java.util.concurrent.atomic.AtomicBoolean firstEvent =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        RuntimeEventListener brokenListener = event -> {
            if (firstEvent.compareAndSet(true, false)) {
                throw new IllegalStateException("monitor unavailable");
            }
        };

        AgentLoop loop = new AgentLoop(
                ScriptedLlm.of(LlmResponse.answer("done")),
                DefaultToolRegistry.builder().build(),
                ToolPolicy.allowAll(),
                AgentLoopOptions.defaults(),
                Arrays.asList(brokenListener, tracker));

        com.avr.api.AgentResult result = loop.run(AgentRequest.builder()
                .agent("report-agent")
                .prompt("create report")
                .workspace(new MemoryWorkspace("workspace-42"))
                .build());

        assertEquals(RunState.COMPLETED, result.getState());
        RunSnapshot snapshot = tracker.find(result.getRunId()).orElseThrow(
                () -> new AssertionError("missing run snapshot"));
        assertEquals("report-agent", snapshot.getAgent());
        assertEquals("workspace-42", snapshot.getWorkspaceId());
        assertEquals(RunState.COMPLETED, snapshot.getState());
        assertEquals("run.completed", snapshot.getLastEventType());

        List<RuntimeEvent> events = tracker.events(result.getRunId());
        assertFalse(events.isEmpty());
        assertTrue(events.stream()
                .anyMatch(event -> "model.completed".equals(event.getType())));
    }

    @Test
    void boundsRunsAndEventsKeptInMemory() {
        InMemoryRunTracker tracker = new InMemoryRunTracker(1, 2);

        tracker.onEvent(event("run-1", 1, RunState.CREATED));
        tracker.onEvent(event("run-1", 2, RunState.PREPARING));
        tracker.onEvent(event("run-1", 3, RunState.COMPLETED));

        assertEquals(2, tracker.events("run-1").size());

        tracker.onEvent(event("run-2", 1, RunState.CREATED));

        assertFalse(tracker.find("run-1").isPresent());
        assertTrue(tracker.find("run-2").isPresent());
    }

    @Test
    void toolEventsExposeDisplayMetadataAndBoundedResultPreview() {
        InMemoryRunTracker tracker = new InMemoryRunTracker(10, 20);
        MemoryWorkspace workspace = new MemoryWorkspace("workspace-tool-events");
        workspace.writeText("/workspace/report.md", "report content");
        AgentLoop loop = new AgentLoop(
                ScriptedLlm.of(
                        LlmResponse.calls(Collections.singletonList(new ToolCall(
                                "call-1", "file.op",
                                java.util.Map.<String, Object>of(
                                        "op", "read", "path", "/workspace/report.md")))),
                        LlmResponse.answer("done")),
                DefaultToolRegistry.builder().register(new FileOpTool()).build(),
                ToolPolicy.allowAll(), AgentLoopOptions.defaults(),
                Collections.singletonList(tracker));

        com.avr.api.AgentResult result = loop.run(AgentRequest.builder()
                .agent("report-agent")
                .prompt("read report")
                .workspace(workspace)
                .build());

        RuntimeEvent completed = tracker.events(result.getRunId()).stream()
                .filter(event -> "tool.completed".equals(event.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing tool event"));
        RuntimeEvent started = tracker.events(result.getRunId()).stream()
                .filter(event -> "tool.started".equals(event.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing tool start event"));
        assertEquals(started.getAttributes().get("toolCallId"),
                completed.getAttributes().get("toolCallId"));
        assertFalse(completed.getOccurredAt().isBefore(started.getOccurredAt()));
        assertEquals("操作文件", completed.getAttributes().get("displayName"));
        assertEquals("JSON", completed.getAttributes().get("displayType"));
        assertTrue(String.valueOf(completed.getAttributes().get("resultPreview"))
                .contains("report content"));
        assertTrue(((Number) completed.getAttributes().get("durationMillis")).longValue() >= 0);
    }

    private static RuntimeEvent event(
            String runId,
            long sequence,
            RunState state) {
        return new RuntimeEvent(
                runId,
                "test-agent",
                "test-workspace",
                sequence,
                state,
                "test.event",
                "",
                java.util.Collections.<String, Object>emptyMap());
    }
}
