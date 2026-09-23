package com.avr.core;

import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.AgentRuntime;
import com.avr.api.CancellationSource;
import com.avr.api.ExecutionContext;
import com.avr.api.Workspace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/** 注册子 Agent，并管理任务创建、执行、结果收集与取消。 */
public final class AgentTaskManager {
    private final Map<String, RegisteredAgent> agents =
            new ConcurrentHashMap<String, RegisteredAgent>();
    private final Map<String, ManagedTask> tasks =
            new ConcurrentHashMap<String, ManagedTask>();
    private final Executor executor;

    public AgentTaskManager() {
        this(ForkJoinPool.commonPool());
    }

    public AgentTaskManager(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public AgentTaskManager register(
            String name, String description, AgentRuntime runtime) {
        RegisteredAgent value = new RegisteredAgent(name, description, runtime);
        if (agents.putIfAbsent(name, value) != null) {
            throw new IllegalArgumentException("duplicate agent: " + name);
        }
        return this;
    }

    public Map<String, String> agents() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        agents.values().stream().sorted((left, right) -> left.name.compareTo(right.name))
                .forEach(value -> result.put(value.name, value.description));
        return Collections.unmodifiableMap(result);
    }

    public String create(
            String agent,
            String prompt,
            Workspace workspace,
            ExecutionContext context,
            int maxSteps) {
        if (!agents.containsKey(agent)) {
            throw new IllegalArgumentException("unknown agent: " + agent);
        }
        String taskId = "agent-task-" + UUID.randomUUID();
        tasks.put(taskId, new ManagedTask(
                taskId, agent, prompt, workspace, context, maxSteps));
        return taskId;
    }

    public AgentTaskSnapshot start(String taskId) {
        ManagedTask task = require(taskId);
        synchronized (task) {
            if (task.status != AgentTaskStatus.CREATED) {
                return task.snapshot();
            }
            task.status = AgentTaskStatus.RUNNING;
            RegisteredAgent registered = agents.get(task.agent);
            task.future = CompletableFuture.runAsync(() -> run(registered, task), executor);
            return task.snapshot();
        }
    }

    public AgentTaskSnapshot await(String taskId, Duration timeout) {
        ManagedTask task = require(taskId);
        CompletableFuture<Void> future;
        synchronized (task) {
            future = task.future;
        }
        if (future != null && !future.isDone()) {
            try {
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                // 超时只返回 RUNNING 快照，不改变任务状态。
            } catch (Exception ignored) {
                // 执行异常已在 run 中写入任务快照。
            }
        }
        return snapshot(taskId);
    }

    public AgentTaskSnapshot snapshot(String taskId) {
        ManagedTask task = require(taskId);
        synchronized (task) {
            return task.snapshot();
        }
    }

    public List<AgentTaskSnapshot> tasks() {
        List<AgentTaskSnapshot> result = new ArrayList<AgentTaskSnapshot>();
        for (ManagedTask task : tasks.values()) {
            synchronized (task) {
                result.add(task.snapshot());
            }
        }
        result.sort((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()));
        return Collections.unmodifiableList(result);
    }

    public AgentTaskSnapshot cancel(String taskId) {
        ManagedTask task = require(taskId);
        synchronized (task) {
            task.cancellation.cancel();
            if (task.status == AgentTaskStatus.CREATED) {
                task.status = AgentTaskStatus.CANCELLED;
                task.completedAt = Instant.now();
            }
            return task.snapshot();
        }
    }

    private void run(RegisteredAgent registered, ManagedTask task) {
        try {
            AgentResult result = registered.runtime.run(AgentRequest.builder()
                    .runId(task.runId)
                    .agent(task.agent)
                    .prompt(task.prompt)
                    .workspace(task.workspace)
                    .context(task.context)
                    .maxSteps(task.maxSteps)
                    .cancellationToken(task.cancellation.token())
                    .build());
            synchronized (task) {
                task.result = result.getText();
                task.status = result.getState() == com.avr.api.RunState.CANCELLED
                        ? AgentTaskStatus.CANCELLED
                        : AgentTaskStatus.COMPLETED;
                task.completedAt = Instant.now();
            }
        } catch (RuntimeException exception) {
            synchronized (task) {
                task.status = AgentTaskStatus.FAILED;
                task.error = exception.getMessage();
                task.completedAt = Instant.now();
            }
        }
    }

    private ManagedTask require(String taskId) {
        ManagedTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("unknown agent task: " + taskId);
        }
        return task;
    }

    private static final class RegisteredAgent {
        private final String name;
        private final String description;
        private final AgentRuntime runtime;

        private RegisteredAgent(String name, String description, AgentRuntime runtime) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.runtime = Objects.requireNonNull(runtime, "runtime");
        }
    }

    private static final class ManagedTask {
        private final String taskId;
        private final String agent;
        private final String prompt;
        private final Workspace workspace;
        private final ExecutionContext context;
        private final int maxSteps;
        private final String runId = UUID.randomUUID().toString();
        private final Instant createdAt = Instant.now();
        private final CancellationSource cancellation = new CancellationSource();
        private AgentTaskStatus status = AgentTaskStatus.CREATED;
        private String result = "";
        private String error = "";
        private Instant completedAt;
        private CompletableFuture<Void> future;

        private ManagedTask(
                String taskId,
                String agent,
                String prompt,
                Workspace workspace,
                ExecutionContext context,
                int maxSteps) {
            this.taskId = taskId;
            this.agent = agent;
            this.prompt = Objects.requireNonNull(prompt, "prompt");
            this.workspace = Objects.requireNonNull(workspace, "workspace");
            this.context = Objects.requireNonNull(context, "context");
            this.maxSteps = maxSteps;
        }

        private AgentTaskSnapshot snapshot() {
            return new AgentTaskSnapshot(
                    taskId, agent, status, runId, result, error, createdAt, completedAt);
        }
    }
}
