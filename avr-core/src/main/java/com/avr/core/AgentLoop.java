package com.avr.core;

import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.AgentRuntime;
import com.avr.api.Llm;
import com.avr.api.LlmResponse;
import com.avr.api.Message;
import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventListener;
import com.avr.api.RuntimeEventTypes;
import com.avr.api.RunState;
import com.avr.api.Skill;
import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolPolicy;
import com.avr.api.ToolRegistry;
import com.avr.api.ToolResult;
import com.avr.api.WebSearchMode;
import com.avr.core.tool.PlanTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 默认的模型调用、工具执行和结果回填循环。 */
public final class AgentLoop implements AgentRuntime {
    private static final Logger LOGGER = Logger.getLogger(AgentLoop.class.getName());
    private static final int MAX_COMPLETION_RETRIES = 2;
    private static final String SYSTEM_PROMPT =
            "You are running inside Agent Virtual Runtime. "
                    + "Use only the tools provided by the runtime.";

    private final Llm llm;
    private final ToolRegistry tools;
    private final ToolPolicy policy;
    private final AgentLoopOptions options;
    private final List<RuntimeEventListener> eventListeners;

    public AgentLoop(Llm llm, ToolRegistry tools) {
        this(llm, tools, ToolPolicy.allowAll(), AgentLoopOptions.defaults());
    }

    public AgentLoop(
            Llm llm,
            ToolRegistry tools,
            ToolPolicy policy) {
        this(llm, tools, policy, AgentLoopOptions.defaults());
    }

    public AgentLoop(
            Llm llm,
            ToolRegistry tools,
            ToolPolicy policy,
            AgentLoopOptions options) {
        this(llm, tools, policy, options,
                Collections.<RuntimeEventListener>emptyList());
    }

    /** 使用 Runtime 级监听器创建 Agent Loop。 */
    public AgentLoop(
            Llm llm,
            ToolRegistry tools,
            ToolPolicy policy,
            AgentLoopOptions options,
            Collection<? extends RuntimeEventListener> eventListeners) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.options = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(eventListeners, "eventListeners");
        List<RuntimeEventListener> listeners =
                new ArrayList<RuntimeEventListener>(eventListeners);
        for (RuntimeEventListener listener : listeners) {
            Objects.requireNonNull(listener, "eventListener");
        }
        this.eventListeners = Collections.unmodifiableList(listeners);
    }

    @Override
    public AgentResult run(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        String runId = request.getRunId();
        AtomicLong sequence = new AtomicLong();
        emit(request, runId, sequence, RunState.CREATED,
                RuntimeEventTypes.RUN_CREATED, request.getAgent());
        emit(request, runId, sequence, RunState.PREPARING,
                RuntimeEventTypes.RUN_PREPARING,
                request.getWorkspace().id());

        List<Message> messages = new ArrayList<Message>();
        messages.add(Message.system(systemPrompt(request)));
        messages.addAll(request.getHistory());
        messages.add(Message.user(request.getPrompt()));
        int emptyResponses = 0;
        int noProgressRounds = 0;
        int completionRetries = 0;
        int softStepLimit = request.getMaxSteps();
        int hardStepLimit = hardStepLimit(
                softStepLimit, options.getMaxStepMultiplier());
        long deadline = System.nanoTime() + options.getLoopTimeout().toNanos();
        Set<String> successfulRoundFingerprints = new LinkedHashSet<String>();
        PlanTool planTool = planTool(request.isPlanMode());

        try {
            List<ToolDefinition> modelTools = modelTools(request.getWebSearchMode());
            for (int step = 1; step <= hardStepLimit; step++) {
                AgentResult timedOut = timedOut(
                        request, runId, sequence, step - 1, deadline);
                if (timedOut != null) {
                    return timedOut;
                }
                AgentResult cancelled = cancelled(request, runId, sequence, step - 1,
                        "cancelled before model call");
                if (cancelled != null) {
                    return cancelled;
                }

                if (step == softStepLimit + 1) {
                    emit(request, runId, sequence, RunState.CALLING_MODEL,
                            RuntimeEventTypes.RUN_EXTENDED,
                            "agent is still making progress", attributes(
                                    "softStepLimit", softStepLimit,
                                    "hardStepLimit", hardStepLimit,
                                    "step", step));
                }

                emit(request, runId, sequence, RunState.CALLING_MODEL,
                        RuntimeEventTypes.MODEL_CALL, String.valueOf(step),
                        attributes("toolDefinitionCount", modelTools.size(),
                                "webSearchMode",
                                request.getWebSearchMode().name().toLowerCase()));
                long modelStartedAt = System.nanoTime();
                LlmResponse response = llm.chat(
                        request.getModel(), messages,
                        modelTools,
                        request.getWebSearchMode() == WebSearchMode.NATIVE,
                        delta -> emit(request, runId, sequence, RunState.CALLING_MODEL,
                                RuntimeEventTypes.MODEL_DELTA, delta),
                        delta -> emit(request, runId, sequence, RunState.CALLING_MODEL,
                                RuntimeEventTypes.MODEL_REASONING_DELTA, delta));
                emit(request, runId, sequence, RunState.CALLING_MODEL,
                        RuntimeEventTypes.MODEL_COMPLETED,
                        response.getText(), attributes(
                                "step", step,
                                "durationMillis", elapsedMillis(modelStartedAt),
                                "toolCallCount", response.getToolCalls().size(),
                                "finishReason", response.getFinishReason() == null
                                        ? "unknown" : response.getFinishReason(),
                                "truncated", response.isTruncated()));

                if (response.isTruncated()) {
                    throw new IllegalStateException(
                            "model response was truncated by its output token limit");
                }

                if (response.getToolCalls().isEmpty()) {
                    if (response.getText().trim().isEmpty()
                            && emptyResponses < options.getMaxEmptyResponses()) {
                        emptyResponses++;
                        noProgressRounds++;
                        emit(request, runId, sequence, RunState.CALLING_MODEL,
                                RuntimeEventTypes.MODEL_EMPTY_RETRY,
                                String.valueOf(emptyResponses));
                        checkProgressFuse(noProgressRounds);
                        continue;
                    }
                    if (response.getText().trim().isEmpty()) {
                        throw new IllegalStateException("model returned an empty response");
                    }
                    boolean planComplete = !request.isPlanMode()
                            || planTool.isComplete(runId, request.getWorkspace());
                    if (!planComplete
                            || !request.getCompletionCheck().test(request.getWorkspace())) {
                        completionRetries++;
                        emit(request, runId, sequence, RunState.CALLING_MODEL,
                                RuntimeEventTypes.MODEL_COMPLETION_REJECTED,
                                planComplete ? "completion condition was not met"
                                        : "plan is missing or has unfinished/unverified steps",
                                attributes("attempt", completionRetries));
                        if (completionRetries > MAX_COMPLETION_RETRIES) {
                            throw new IllegalStateException(
                                    "agent completion check failed: plan or workspace task is unfinished");
                        }
                        messages.add(Message.assistant(response.getText()));
                        messages.add(Message.user(
                                "The task is not verified. Do not claim success. Create a plan "
                                        + "with plan.manage if needed, execute every step using "
                                        + "real tools, then call plan.manage check. The runtime "
                                        + "reconciles completed steps and matches evidence automatically; "
                                        + "do not provide or guess call IDs."));
                        continue;
                    }
                    messages.add(Message.assistant(response.getText()));
                    emit(request, runId, sequence, RunState.COMPLETED,
                            RuntimeEventTypes.RUN_COMPLETED,
                            response.getText());
                    return new AgentResult(runId, RunState.COMPLETED, response.getText(), step,
                            request.getWorkspace().artifacts());
                }

                emptyResponses = 0;
                messages.add(Message.assistant(
                        response.getText(), response.getToolCalls()));
                List<ToolResult> results = executeCalls(
                        response.getToolCalls(), request, runId, sequence);
                boolean hasSuccessfulTool = false;
                for (int index = 0; index < results.size(); index++) {
                    ToolCall call = response.getToolCalls().get(index);
                    ToolResult result = results.get(index);
                    messages.add(Message.tool(call.getId(), result.getContent()));
                    hasSuccessfulTool |= result.isSuccess();
                }
                String roundFingerprint = roundFingerprint(
                        response.getToolCalls(), results);
                boolean madeProgress = hasSuccessfulTool
                        && successfulRoundFingerprints.add(roundFingerprint);
                noProgressRounds = madeProgress ? 0 : noProgressRounds + 1;
                checkProgressFuse(noProgressRounds);
            }
            throw new IllegalStateException(
                    "agent exhausted step budget: softLimit=" + softStepLimit
                            + ", hardLimit=" + hardStepLimit);
        } catch (RuntimeException exception) {
            emit(request, runId, sequence, RunState.FAILED,
                    RuntimeEventTypes.RUN_FAILED, exception.getMessage());
            throw exception;
        } finally {
            if (planTool != null) {
                planTool.release(runId);
            }
        }
    }

    private List<ToolDefinition> modelTools(WebSearchMode webSearchMode) {
        List<ToolDefinition> result = new ArrayList<ToolDefinition>();
        boolean runtimeSearchRegistered = false;
        for (ToolDefinition definition : tools.definitions()) {
            if ("web.search".equals(definition.getName())) {
                runtimeSearchRegistered = true;
                if (webSearchMode != WebSearchMode.RUNTIME) {
                    continue;
                }
            }
            result.add(definition);
        }
        if (webSearchMode == WebSearchMode.RUNTIME && !runtimeSearchRegistered) {
            throw new IllegalStateException(
                    "runtime web search is enabled but no web.search tool is configured");
        }
        return Collections.unmodifiableList(result);
    }

    private PlanTool planTool(boolean required) {
        try {
            Tool tool = tools.require("plan.manage");
            if (tool instanceof PlanTool) {
                return (PlanTool) tool;
            }
        } catch (RuntimeException exception) {
            if (!required) {
                return null;
            }
        }
        if (required) {
            throw new IllegalStateException("plan mode requires the standard plan.manage tool");
        }
        return null;
    }

    private AgentResult timedOut(
            AgentRequest request,
            String runId,
            AtomicLong sequence,
            int steps,
            long deadline) {
        if (System.nanoTime() - deadline < 0) {
            return null;
        }
        emit(request, runId, sequence, RunState.TIMED_OUT,
                RuntimeEventTypes.RUN_TIMED_OUT,
                "agent exceeded loop timeout " + options.getLoopTimeout());
        return new AgentResult(runId, RunState.TIMED_OUT, "", steps,
                request.getWorkspace().artifacts());
    }

    private static int hardStepLimit(int softLimit, int multiplier) {
        long calculated = (long) softLimit * multiplier;
        return calculated > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) calculated;
    }

    /** 相同工具参数与结果重复出现时不再视为有效进展。 */
    private static String roundFingerprint(
            List<ToolCall> calls, List<ToolResult> results) {
        StringBuilder fingerprint = new StringBuilder();
        for (int index = 0; index < calls.size(); index++) {
            ToolCall call = calls.get(index);
            ToolResult result = results.get(index);
            fingerprint.append(call.getName()).append(':')
                    .append(call.getArguments().hashCode()).append(':')
                    .append(result.isSuccess()).append(':')
                    .append(result.getContent().hashCode()).append(';');
        }
        return fingerprint.toString();
    }

    private List<ToolResult> executeCalls(
            List<ToolCall> calls,
            AgentRequest request,
            String runId,
            AtomicLong sequence) {
        // 连续的并行工具组成一个批次；串行工具会先等待前一批完成，再单独执行。
        // 结果始终写回原始索引，确保回填模型时顺序与 tool_calls 完全一致。
        List<ToolResult> results = new ArrayList<ToolResult>(
                Collections.nCopies(calls.size(), null));
        List<PendingCall> parallelBatch = new ArrayList<PendingCall>();

        for (int index = 0; index < calls.size(); index++) {
            ToolCall call = calls.get(index);
            Tool tool = findTool(call);
            if (tool == null || tool.executionMode() == ToolExecutionMode.SEQUENTIAL) {
                completeBatch(parallelBatch, results, request, runId, sequence);
                parallelBatch.clear();
                results.set(index, executeOne(call, request, runId, sequence));
            } else {
                emit(request, runId, sequence, RunState.EXECUTING_TOOLS,
                        RuntimeEventTypes.TOOL_STARTED,
                        call.getName(), toolAttributes(call));
                long startedAt = System.nanoTime();
                Future<ToolResult> future = options.getToolExecutor()
                        .submit(() -> execute(call, request));
                parallelBatch.add(new PendingCall(
                        index, call, future, startedAt));
            }
        }
        completeBatch(parallelBatch, results, request, runId, sequence);
        return results;
    }

    private ToolResult executeOne(
            ToolCall call,
            AgentRequest request,
            String runId,
            AtomicLong sequence) {
        emit(request, runId, sequence, RunState.EXECUTING_TOOLS,
                RuntimeEventTypes.TOOL_STARTED,
                call.getName(), toolAttributes(call));
        long startedAt = System.nanoTime();
        Future<ToolResult> future = options.getToolExecutor()
                .submit(() -> execute(call, request));
        ToolResult result;
        try {
            result = future.get(
                    options.getToolTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            result = ToolResult.failure(
                    "ERROR TimeoutException: tool timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            result = ToolResult.failure(
                    "ERROR InterruptedException: tool interrupted");
        } catch (Exception exception) {
            result = ToolResult.failure(
                    "ERROR ExecutionException: " + rootMessage(exception));
        }
        emitToolResult(request, runId, sequence, call, result, startedAt);
        recordPlanEvidence(runId, call, result);
        return result;
    }

    private void completeBatch(
            List<PendingCall> batch,
            List<ToolResult> results,
            AgentRequest request,
            String runId,
            AtomicLong sequence) {
        // 同一并行批次共享一个截止时间，避免每个工具依次等待完整超时时间。
        long deadline = System.nanoTime() + options.getToolTimeout().toNanos();
        for (PendingCall pending : batch) {
            ToolResult result;
            long remaining = deadline - System.nanoTime();
            try {
                if (remaining <= 0) {
                    throw new TimeoutException("tool batch timed out");
                }
                result = pending.future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                pending.future.cancel(true);
                result = ToolResult.failure(
                        "ERROR TimeoutException: tool timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                result = ToolResult.failure(
                        "ERROR InterruptedException: tool interrupted");
            } catch (Exception exception) {
                result = ToolResult.failure(
                        "ERROR ExecutionException: " + rootMessage(exception));
            }
            results.set(pending.index, result);
            emitToolResult(request, runId, sequence, pending.call, result,
                    pending.startedAt);
            recordPlanEvidence(runId, pending.call, result);
        }
    }

    private void recordPlanEvidence(String runId, ToolCall call, ToolResult result) {
        PlanTool plan = planTool(false);
        if (plan != null) {
            plan.record(runId, call, result);
        }
    }

    private ToolResult execute(ToolCall call, AgentRequest request) {
        try {
            PlanTool plan = planTool(false);
            if (request.isPlanMode() && plan != null
                    && mutatesWorkspace(call) && !plan.hasPlan(request.getRunId())) {
                return ToolResult.failure(
                        "create a plan with plan.manage before changing the workspace");
            }
            Tool tool = tools.require(call.getName());
            policy.authorize(request.getContext(), request.getWorkspace(), call);
            return tool.execute(call,
                    new ToolContext(request.getWorkspace(), request.getContext(), request.getRunId()));
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    "ERROR " + exception.getClass().getSimpleName()
                            + ": " + exception.getMessage());
        }
    }

    private static boolean mutatesWorkspace(ToolCall call) {
        String name = call.getName();
        String op = String.valueOf(call.getArguments().get("op"));
        if ("file.op".equals(name)) {
            return !"list".equals(op) && !"read".equals(op) && !"search".equals(op);
        }
        if ("directory.manage".equals(name)) {
            return !"list".equals(op);
        }
        return "artifact.commit".equals(name);
    }

    private Tool findTool(ToolCall call) {
        try {
            return tools.require(call.getName());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void emitToolResult(
            AgentRequest request,
            String runId,
            AtomicLong sequence,
            ToolCall call,
            ToolResult result,
            long startedAt) {
        emit(request, runId, sequence, RunState.EXECUTING_TOOLS,
                result.isSuccess()
                        ? RuntimeEventTypes.TOOL_COMPLETED
                        : RuntimeEventTypes.TOOL_FAILED,
                call.getName(), toolResultAttributes(call, result, startedAt));
    }

    private AgentResult cancelled(
            AgentRequest request,
            String runId,
            AtomicLong sequence,
            int steps,
            String detail) {
        if (!request.getCancellationToken().isCancelled()) {
            return null;
        }
        emit(request, runId, sequence, RunState.CANCELLED,
                RuntimeEventTypes.RUN_CANCELLED, detail);
        return new AgentResult(runId, RunState.CANCELLED, "", steps,
                request.getWorkspace().artifacts());
    }

    private void checkProgressFuse(int rounds) {
        if (rounds >= options.getMaxNoProgressRounds()) {
            throw new IllegalStateException(
                    "agent made no progress for " + rounds + " consecutive rounds");
        }
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static String systemPrompt(AgentRequest request) {
        StringBuilder prompt = new StringBuilder(SYSTEM_PROMPT);
        if (request.isPlanMode()) {
            prompt.append("\nFor this task, create a plan with plan.manage before changing the workspace. "
                    + "Carry out each step using real tools, then call plan.manage check. "
                    + "The runtime reconciles completed steps and matches successful tool "
                    + "evidence automatically. update is optional for progress tracking. "
                    + "If a pending step verifies the wrong deliverable, revise it instead of "
                    + "repeating a failing update. For a split large deliverable, verify the "
                    + "output directory and its aggregate minBytes, not an index or README file. "
                    + "Do not describe imagined files or changes as completed.");
        }
        for (Skill skill : request.getSkills()) {
            prompt.append("\n\nSkill: ").append(skill.getName()).append('\n')
                    .append(skill.getInstructions());
        }
        return prompt.toString();
    }

    private void emit(
            AgentRequest request,
            String runId,
            AtomicLong sequence,
            RunState state,
            String type,
            String detail) {
        emit(request, runId, sequence, state, type, detail,
                Collections.<String, Object>emptyMap());
    }

    private void emit(
            AgentRequest request,
            String runId,
            AtomicLong sequence,
            RunState state,
            String type,
            String detail,
            Map<String, Object> attributes) {
        RuntimeEvent event = new RuntimeEvent(
                runId,
                request.getAgent(),
                request.getWorkspace().id(),
                sequence.incrementAndGet(),
                state,
                type,
                detail,
                attributes);
        for (RuntimeEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                // 可观测代码不能改变 Agent 的执行结果。
                LOGGER.log(Level.WARNING,
                        "Runtime event listener failed: {0}: {1}",
                        new Object[]{
                                listener.getClass().getName(),
                                exception.getMessage()
                        });
            }
        }
    }

    private Map<String, Object> toolAttributes(ToolCall call) {
        Map<String, Object> result = attributes(
                "toolCallId", call.getId(),
                "toolName", call.getName());
        Tool tool = findTool(call);
        if (tool != null) {
            result.put("displayName", tool.definition().getDisplayName());
            result.put("displayType", tool.definition().getDisplayType().name());
        }
        Map<String, Object> argumentSummary = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : call.getArguments().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && ((String) value).length() > 500) {
                argumentSummary.put(entry.getKey(),
                        "<" + ((String) value).length() + " chars>");
            } else {
                argumentSummary.put(entry.getKey(), value);
            }
        }
        result.put("arguments", argumentSummary);
        for (String key : new String[]{"path", "source", "target", "root", "entrypoint"}) {
            Object value = call.getArguments().get(key);
            if (value instanceof String) {
                result.put(key, value);
            }
        }
        return result;
    }

    private Map<String, Object> toolResultAttributes(
            ToolCall call, ToolResult result, long startedAt) {
        Map<String, Object> values = toolAttributes(call);
        values.put("success", result.isSuccess());
        values.put("durationMillis", elapsedMillis(startedAt));
        values.put("resultLength", result.getContent().length());
        if (result.getRowCount() != null) {
            values.put("rows", result.getRowCount());
        }
        if (result.getDisplayType() != null) {
            values.put("displayType", result.getDisplayType().name());
        }
        values.put("resultPreview", result.getContent().length() > 20_000
                ? result.getContent().substring(0, 20_000) + "\n...(truncated)"
                : result.getContent());
        return values;
    }

    private static Map<String, Object> attributes(Object... values) {
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            attributes.put(String.valueOf(values[index]), values[index + 1]);
        }
        return attributes;
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static final class PendingCall {
        private final int index;
        private final ToolCall call;
        private final Future<ToolResult> future;
        private final long startedAt;

        private PendingCall(
                int index,
                ToolCall call,
                Future<ToolResult> future,
                long startedAt) {
            this.index = index;
            this.call = call;
            this.future = future;
            this.startedAt = startedAt;
        }
    }
}
