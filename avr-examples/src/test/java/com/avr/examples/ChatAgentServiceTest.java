package com.avr.examples;

import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.AgentRuntime;
import com.avr.api.RunState;
import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventTypes;
import com.avr.spring.AvrAgentProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证聊天入口使用 AgentRuntime、Skill 和会话级 Workspace。 */
class ChatAgentServiceTest {
    @Test
    void preservesHistoryAndWorkspaceAcrossRuns() {
        List<AgentRequest> requests = new ArrayList<AgentRequest>();
        ChatRunEventBridge bridge = new ChatRunEventBridge();
        AgentRuntime runtime = request -> {
            requests.add(request);
            bridge.onEvent(new RuntimeEvent(
                    request.getRunId(), request.getAgent(), request.getWorkspace().id(),
                    1, RunState.CALLING_MODEL,
                    RuntimeEventTypes.MODEL_CALL, "1", java.util.Collections.emptyMap()));
            return new AgentResult(request.getRunId(), RunState.COMPLETED,
                    "answer-" + requests.size(), 1, request.getWorkspace().artifacts());
        };
        ChatAgentService service = new ChatAgentService(
                runtime, bridge, new AvrAgentProperties(),
                "memory", "target/test-chat-workspaces");
        ChatAgentService.ChatSession first = service.open(null);
        ChatAgentService.ChatSession second = service.open(null);
        List<RuntimeEvent> events = new ArrayList<RuntimeEvent>();

        service.claim(first);
        service.run(first, "first question", events::add);
        service.release(first);
        service.claim(first);
        service.run(first, "second question", events::add);
        service.release(first);

        assertEquals(2, requests.size());
        assertEquals(0, requests.get(0).getHistory().size());
        assertEquals(2, requests.get(1).getHistory().size());
        assertEquals("first question", requests.get(1).getHistory().get(0).getContent());
        assertEquals("answer-1", requests.get(1).getHistory().get(1).getContent());
        assertEquals(first.getWorkspace(), requests.get(1).getWorkspace());
        assertNotEquals(first.getWorkspace().id(), second.getWorkspace().id());
        assertFalse(requests.get(0).getSkills().isEmpty());
        assertEquals("default", requests.get(0).getModel());
        assertTrue(first.getWorkspace().exists("/skills/chat/SKILL.md"));
        assertEquals(2, events.size());
    }

    @Test
    void keepsRunAndReplaysEventsAfterSubscriberReconnects() throws Exception {
        ChatRunEventBridge bridge = new ChatRunEventBridge();
        AgentRuntime runtime = request -> {
            bridge.onEvent(new RuntimeEvent(
                    request.getRunId(), request.getAgent(), request.getWorkspace().id(),
                    1, RunState.CALLING_MODEL,
                    RuntimeEventTypes.MODEL_DELTA, "partial ", Map.of()));
            bridge.onEvent(new RuntimeEvent(
                    request.getRunId(), request.getAgent(), request.getWorkspace().id(),
                    2, RunState.EXECUTING_TOOLS,
                    RuntimeEventTypes.TOOL_COMPLETED, "file.write",
                    Map.of("toolCallId", "call-1", "toolName", "file.write")));
            return new AgentResult(request.getRunId(), RunState.COMPLETED,
                    "partial answer", 2, request.getWorkspace().artifacts());
        };
        ChatAgentService service = new ChatAgentService(
                runtime, bridge, new AvrAgentProperties(),
                "memory", "target/test-chat-workspaces");
        ChatAgentService.ChatSession session = service.open(null);
        CountDownLatch completed = new CountDownLatch(1);

        ChatAgentService.ChatRun run = service.start(
                session, "question", null, subscriber(new ArrayList<RuntimeEvent>(), completed));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals("completed", run.getStatus());
        assertEquals("partial answer", run.getContent());
        assertEquals(2, run.getEvents().size());

        List<RuntimeEvent> replayed = new ArrayList<RuntimeEvent>();
        CountDownLatch replayCompleted = new CountDownLatch(1);
        service.subscribe(session, run.getRunId(), 1,
                subscriber(replayed, replayCompleted));

        assertTrue(replayCompleted.await(1, TimeUnit.SECONDS));
        assertEquals(1, replayed.size());
        assertEquals(2, replayed.get(0).getSequence());
    }

    @Test
    void classifiesFailuresWithoutExposingProviderResponseBody() {
        assertEquals("model-http:400", ChatAgentService.publicFailureCode(
                new IllegalStateException(
                        "model endpoint returned HTTP 400, body=private provider details")));
        assertEquals("step-limit", ChatAgentService.publicFailureCode(
                new IllegalStateException("agent exhausted step budget: softLimit=30")));
        assertEquals("model-stream-invalid", ChatAgentService.publicFailureCode(
                new IllegalStateException("invalid model SSE event JSON")));
    }

    @Test
    void preservesReasoningSeparatelyFromVisibleAnswer() throws Exception {
        ChatRunEventBridge bridge = new ChatRunEventBridge();
        AgentRuntime runtime = request -> {
            bridge.onEvent(new RuntimeEvent(
                    request.getRunId(), request.getAgent(), request.getWorkspace().id(),
                    1, RunState.CALLING_MODEL,
                    RuntimeEventTypes.MODEL_REASONING_DELTA, "check facts", Map.of()));
            return new AgentResult(request.getRunId(), RunState.COMPLETED,
                    "<think>compare results</think>final answer", 1,
                    request.getWorkspace().artifacts());
        };
        ChatAgentService service = new ChatAgentService(
                runtime, bridge, new AvrAgentProperties(),
                "memory", "target/test-chat-workspaces");
        ChatAgentService.ChatSession session = service.open(null);
        CountDownLatch completed = new CountDownLatch(1);

        ChatAgentService.ChatRun run = service.start(
                session, "question", null, subscriber(new ArrayList<RuntimeEvent>(), completed));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals("final answer", run.getContent());
        assertEquals("check facts\ncompare results", run.getReasoning());
        assertEquals("final answer", service.history(session).get(1).getContent());
    }

    @Test
    void preservesSeparateModelRoundsAndToolPreparationText() throws Exception {
        ChatRunEventBridge bridge = new ChatRunEventBridge();
        AgentRuntime runtime = request -> {
            String runId = request.getRunId();
            String agent = request.getAgent();
            String workspaceId = request.getWorkspace().id();
            bridge.onEvent(new RuntimeEvent(runId, agent, workspaceId, 1,
                    RunState.CALLING_MODEL, RuntimeEventTypes.MODEL_CALL, "1", Map.of()));
            bridge.onEvent(new RuntimeEvent(runId, agent, workspaceId, 2,
                    RunState.CALLING_MODEL, RuntimeEventTypes.MODEL_DELTA,
                    "先查看目录", Map.of()));
            bridge.onEvent(new RuntimeEvent(runId, agent, workspaceId, 3,
                    RunState.CALLING_MODEL, RuntimeEventTypes.MODEL_COMPLETED,
                    "先查看目录", Map.of("toolCallCount", 1)));
            bridge.onEvent(new RuntimeEvent(runId, agent, workspaceId, 4,
                    RunState.CALLING_MODEL, RuntimeEventTypes.MODEL_CALL, "2", Map.of()));
            bridge.onEvent(new RuntimeEvent(runId, agent, workspaceId, 5,
                    RunState.CALLING_MODEL, RuntimeEventTypes.MODEL_REASONING_DELTA,
                    "根据文件更新结论", Map.of()));
            bridge.onEvent(new RuntimeEvent(runId, agent, workspaceId, 6,
                    RunState.CALLING_MODEL, RuntimeEventTypes.MODEL_DELTA,
                    "最终回答", Map.of()));
            bridge.onEvent(new RuntimeEvent(runId, agent, workspaceId, 7,
                    RunState.CALLING_MODEL, RuntimeEventTypes.MODEL_COMPLETED,
                    "最终回答", Map.of("toolCallCount", 0)));
            return new AgentResult(runId, RunState.COMPLETED,
                    "最终回答", 2, request.getWorkspace().artifacts());
        };
        ChatAgentService service = new ChatAgentService(runtime, bridge,
                new AvrAgentProperties(), "memory", "target/test-chat-workspaces");
        CountDownLatch completed = new CountDownLatch(1);
        ChatAgentService.ChatRun run = service.start(service.open(null), "question", null,
                subscriber(new ArrayList<RuntimeEvent>(), completed));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(2, run.getModelRounds().size());
        assertEquals("先查看目录", run.getModelRounds().get(0).get("text"));
        assertEquals(1, run.getModelRounds().get(0).get("toolCallCount"));
        assertEquals("根据文件更新结论",
                run.getModelRounds().get(1).get("reasoning"));
        assertEquals("最终回答", run.getModelRounds().get(1).get("text"));
    }

    private static ChatAgentService.RunSubscriber subscriber(
            List<RuntimeEvent> events,
            CountDownLatch completed) {
        return new ChatAgentService.RunSubscriber() {
            @Override
            public void onEvent(RuntimeEvent event) {
                events.add(event);
            }

            @Override
            public void onComplete(AgentResult result) {
                completed.countDown();
            }

            @Override
            public void onError(String message) {
                completed.countDown();
            }
        };
    }
}
