package com.avr.api;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

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
    private final List<Message> history;
    private final String model;
    private final Predicate<Workspace> completionCheck;
    private final boolean planMode;
    private final WebSearchMode webSearchMode;

    private AgentRequest(Builder builder) {
        this.prompt = Objects.requireNonNull(builder.prompt, "prompt");
        this.runId = Objects.requireNonNull(builder.runId, "runId");
        this.workspace = Objects.requireNonNull(builder.workspace, "workspace");
        this.maxSteps = builder.maxSteps;
        this.agent = builder.agent;
        this.context = builder.context;
        this.skills = java.util.Collections.unmodifiableList(new java.util.ArrayList<Skill>(builder.skills));
        this.cancellationToken = builder.cancellationToken;
        this.history = Collections.unmodifiableList(new ArrayList<Message>(builder.history));
        this.model = builder.model;
        this.completionCheck = builder.completionCheck;
        this.planMode = builder.planMode;
        this.webSearchMode = Objects.requireNonNull(
                builder.webSearchMode, "webSearchMode");
        if (prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        for (Message message : history) {
            if (message == null || (message.getRole() != Message.Role.USER
                    && message.getRole() != Message.Role.ASSISTANT)
                    || !message.getToolCalls().isEmpty()) {
                throw new IllegalArgumentException(
                        "history supports only plain user and assistant messages");
            }
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
        private int maxSteps = 30;
        private String agent = "default";
        private ExecutionContext context = ExecutionContext.empty();
        private final java.util.List<Skill> skills = new java.util.ArrayList<Skill>();
        private CancellationToken cancellationToken = CancellationToken.none();
        private final List<Message> history = new ArrayList<Message>();
        private String model;
        private Predicate<Workspace> completionCheck = workspace -> true;
        private boolean planMode;
        private WebSearchMode webSearchMode = WebSearchMode.NONE;

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

        /** 加入当前输入之前的用户和助手消息。 */
        public Builder history(List<Message> messages) {
            this.history.clear();
            this.history.addAll(Objects.requireNonNull(messages, "messages"));
            return this;
        }

        /** 指定本次请求使用的模型；为空时由 LLM 实现选择默认模型。 */
        public Builder model(String model) {
            this.model = model == null || model.trim().isEmpty() ? null : model.trim();
            return this;
        }

        /** 可选的交付条件；不满足时运行循环会要求模型继续执行。 */
        public Builder completionCheck(Predicate<Workspace> completionCheck) {
            this.completionCheck = Objects.requireNonNull(completionCheck, "completionCheck");
            return this;
        }

        /** 要求本次运行先建立计划，并凭实际工具调用证据完成全部步骤。 */
        public Builder planMode(boolean planMode) {
            this.planMode = planMode;
            return this;
        }

        /**
         * 是否允许联网搜索。
         *
         * <p>为兼容旧调用，启用时默认走 AVR Runtime Tool，避免把厂商专有
         * tool 类型误发给 OpenAI Chat Completions 兼容服务。</p>
         */
        public Builder webSearch(boolean webSearch) {
            this.webSearchMode = webSearch
                    ? WebSearchMode.RUNTIME : WebSearchMode.NONE;
            return this;
        }

        /** 显式指定本次运行的联网搜索接入模式。 */
        public Builder webSearchMode(WebSearchMode webSearchMode) {
            this.webSearchMode = Objects.requireNonNull(
                    webSearchMode, "webSearchMode");
            return this;
        }

        public AgentRequest build() {
            return new AgentRequest(this);
        }
    }

    /** 兼容旧代码；任一联网模式均返回 true。 */
    public boolean isWebSearch() {
        return webSearchMode != WebSearchMode.NONE;
    }
}
