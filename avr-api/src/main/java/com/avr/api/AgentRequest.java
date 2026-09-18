package com.avr.api;

import lombok.Getter;

import java.util.Objects;

/** 一次智能体运行所需的不可变输入。 */
@Getter
public final class AgentRequest {
    private final String prompt;
    private final String runId;
    private final Workspace workspace;
    private final int maxSteps;
    private final String agent;
    private final ExecutionContext context;
    private final java.util.List<Skill> skills;
    private final CancellationToken cancellationToken;

    private AgentRequest(Builder builder) {
        this.prompt = Objects.requireNonNull(builder.prompt, "prompt");
        this.runId = Objects.requireNonNull(builder.runId, "runId");
        this.workspace = Objects.requireNonNull(builder.workspace, "workspace");
        this.maxSteps = builder.maxSteps;
        this.agent = builder.agent;
        this.context = builder.context;
        this.skills = java.util.Collections.unmodifiableList(new java.util.ArrayList<Skill>(builder.skills));
        this.cancellationToken = builder.cancellationToken;
        if (prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
    }

    /** 创建运行请求构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 构建 {@link AgentRequest}。 */
    public static final class Builder {
        private String prompt;
        private String runId = java.util.UUID.randomUUID().toString();
        private Workspace workspace;
        private int maxSteps = 20;
        private String agent = "default";
        private ExecutionContext context = ExecutionContext.empty();
        private final java.util.List<Skill> skills = new java.util.ArrayList<Skill>();
        private CancellationToken cancellationToken = CancellationToken.none();

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = Objects.requireNonNull(runId, "runId");
            return this;
        }

        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder agent(String agent) {
            this.agent = Objects.requireNonNull(agent, "agent");
            return this;
        }

        public Builder context(ExecutionContext context) {
            this.context = Objects.requireNonNull(context, "context");
            return this;
        }

        public Builder skill(Skill skill) {
            this.skills.add(Objects.requireNonNull(skill, "skill"));
            return this;
        }

        public Builder cancellationToken(CancellationToken cancellationToken) {
            this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
            return this;
        }

        public AgentRequest build() {
            return new AgentRequest(this);
        }
    }
}
