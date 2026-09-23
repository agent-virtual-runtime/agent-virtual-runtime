package com.avr.core;

import com.avr.api.AgentResult;
import com.avr.api.ExecutionContext;
import com.avr.api.RunState;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTaskManagerTest {
    @Test
    void createsRunsAndCollectsSubAgentResult() {
        AgentTaskManager manager = new AgentTaskManager(Runnable::run)
                .register("writer", "writes reports", request -> new AgentResult(
                        request.getRunId(), RunState.COMPLETED,
                        "finished: " + request.getPrompt(), 1, Collections.emptyList()));

        String taskId = manager.create(
                "writer", "draft report", new MemoryWorkspace("test"),
                ExecutionContext.empty(), 5);
        manager.start(taskId);
        AgentTaskSnapshot snapshot = manager.await(taskId, Duration.ofSeconds(1));

        assertEquals(AgentTaskStatus.COMPLETED, snapshot.getStatus());
        assertEquals("finished: draft report", snapshot.getResult());
        assertEquals(1, manager.tasks().size());
    }

    @Test
    void rejectsUnknownAgent() {
        AgentTaskManager manager = new AgentTaskManager(Runnable::run);
        assertThrows(IllegalArgumentException.class, () -> manager.create(
                "missing", "task", new MemoryWorkspace("test"),
                ExecutionContext.empty(), 5));
    }
}
