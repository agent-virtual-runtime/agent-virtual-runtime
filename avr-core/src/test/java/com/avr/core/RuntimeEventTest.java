package com.avr.core;

import com.avr.api.AgentRequest;
import com.avr.api.LlmResponse;
import com.avr.api.RunSnapshot;
import com.avr.api.RunState;
import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventListener;
import com.avr.api.ToolPolicy;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
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
