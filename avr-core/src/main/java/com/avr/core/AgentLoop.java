package com.avr.core;

import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.AgentRuntime;
import com.avr.api.Llm;
import com.avr.api.LlmResponse;
import com.avr.api.Message;
import com.avr.api.RunEvent;
import com.avr.api.RunState;
import com.avr.api.Skill;
import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolPolicy;
import com.avr.api.ToolRegistry;
import com.avr.api.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** 默认的模型调用、工具执行和结果回填循环。 */
public final class AgentLoop implements AgentRuntime {
    private static final String SYSTEM_PROMPT =
            "You are running inside Agent Virtual Runtime. "
                    + "Use only the tools provided by the runtime.";

    private final Llm llm;
    private final ToolRegistry tools;
    private final ToolPolicy policy;
    private final AgentLoopOptions options;

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
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public AgentResult run(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        String runId = request.getRunId();
        AtomicLong sequence = new AtomicLong();
        emit(request, runId, sequence, RunState.CREATED, "run.created", request.getAgent());
        emit(request, runId, sequence, RunState.PREPARING, "run.preparing",
                request.getWorkspace().id());

        List<Message> messages = new ArrayList<Message>();
        messages.add(Message.system(systemPrompt(request)));
        messages.add(Message.user(request.getPrompt()));
        int emptyResponses = 0;
        int noProgressRounds = 0;

        try {
            for (int step = 1; step <= request.getMaxSteps(); step++) {
                AgentResult cancelled = cancelled(request, runId, sequence, step - 1,
                        "cancelled before model call");
                if (cancelled != null) {
                    return cancelled;
                }

                emit(request, runId, sequence, RunState.CALLING_MODEL,
                        "model.call", String.valueOf(step));
                LlmResponse response = llm.chat(
                        messages,
                        tools.definitions(),
                        delta -> emit(request, runId, sequence, RunState.CALLING_MODEL,
                                "model.delta", delta));

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
                                "model.empty_retry", String.valueOf(emptyResponses));
                        checkProgressFuse(noProgressRounds);
                        continue;
                    }
                    if (response.getText().trim().isEmpty()) {
                        throw new IllegalStateException("model returned an empty response");
                    }
                    messages.add(Message.assistant(response.getText()));
                    emit(request, runId, sequence, RunState.COMPLETED,
                            "run.completed", response.getText());
                    return new AgentResult(runId, RunState.COMPLETED, response.getText(), step,
                            request.getWorkspace().artifacts());
                }

                emptyResponses = 0;
                messages.add(Message.assistant(
                        response.getText(), response.getToolCalls()));
                List<ToolResult> results = executeCalls(
                        response.getToolCalls(), request, runId, sequence);
                boolean madeProgress = false;
                for (int index = 0; index < results.size(); index++) {
                    ToolCall call = response.getToolCalls().get(index);
                    ToolResult result = results.get(index);
                    messages.add(Message.tool(call.getId(), result.getContent()));
                    madeProgress |= result.isSuccess();
                }
                noProgressRounds = madeProgress ? 0 : noProgressRounds + 1;
                checkProgressFuse(noProgressRounds);
            }
            throw new IllegalStateException("agent exceeded maxSteps=" + request.getMaxSteps());
        } catch (RuntimeException exception) {
            emit(request, runId, sequence, RunState.FAILED,
                    "run.failed", exception.getMessage());
            throw exception;
        }
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
                        "tool.started", call.getName());
                Future<ToolResult> future = options.getToolExecutor()
                        .submit(() -> execute(call, request));
                parallelBatch.add(new PendingCall(index, call, future));
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
                "tool.started", call.getName());
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
        emitToolResult(request, runId, sequence, call, result);
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
            emitToolResult(request, runId, sequence, pending.call, result);
        }
    }

    private ToolResult execute(ToolCall call, AgentRequest request) {
        try {
            Tool tool = tools.require(call.getName());
            policy.authorize(request.getContext(), request.getWorkspace(), call);
            return tool.execute(call,
                    new ToolContext(request.getWorkspace(), request.getContext()));
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    "ERROR " + exception.getClass().getSimpleName()
                            + ": " + exception.getMessage());
        }
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
            ToolResult result) {
        emit(request, runId, sequence, RunState.EXECUTING_TOOLS,
                result.isSuccess() ? "tool.completed" : "tool.failed",
                call.getName());
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
        emit(request, runId, sequence, RunState.CANCELLED, "run.cancelled", detail);
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
        for (Skill skill : request.getSkills()) {
            prompt.append("\n\nSkill: ").append(skill.getName()).append('\n')
                    .append(skill.getInstructions());
        }
        return prompt.toString();
    }

    private static void emit(
            AgentRequest request,
            String runId,
            AtomicLong sequence,
            RunState state,
            String type,
            String detail) {
        request.getObserver().onEvent(new RunEvent(
                runId, sequence.incrementAndGet(), state, type,
                detail == null ? "" : detail));
    }

    private static final class PendingCall {
        private final int index;
        private final ToolCall call;
        private final Future<ToolResult> future;

        private PendingCall(
                int index,
                ToolCall call,
                Future<ToolResult> future) {
            this.index = index;
            this.call = call;
            this.future = future;
        }
    }
}
