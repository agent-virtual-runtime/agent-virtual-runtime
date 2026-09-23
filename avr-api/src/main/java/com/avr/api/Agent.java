package com.avr.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 配置了运行时、工作空间和 Skill 的可复用智能体入口。 */
public final class Agent {
    private final String name;
    private final AgentRuntime runtime;
    private final Workspace workspace;
    private final ExecutionContext context;
    private final List<Skill> skills;
    private final int maxSteps;
    private final CancellationToken cancellationToken;
    private final String model;

    private Agent(Builder builder) {
        this.name = builder.name;
        this.runtime = Objects.requireNonNull(builder.runtime, "runtime");
        this.workspace = Objects.requireNonNull(builder.workspace, "workspace");
        this.context = builder.context;
        this.skills = new ArrayList<Skill>(builder.skills);
        this.maxSteps = builder.maxSteps;
        this.cancellationToken = builder.cancellationToken;
        this.model = builder.model;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 在当前智能体的虚拟环境中同步执行一次输入。 */
    public AgentResult input(String prompt) {
        return runtime.run(request(prompt));
    }

    /** 异步执行一次输入。 */
    public CompletionStage<AgentResult> inputAsync(String prompt) {
        return runtime.runAsync(request(prompt));
    }

    private AgentRequest request(String prompt) {
        AgentRequest.Builder request = AgentRequest.builder()
                .agent(name)
                .prompt(prompt)
                .workspace(workspace)
                .context(context)
                .cancellationToken(cancellationToken)
                .maxSteps(maxSteps);
        request.model(model);
        for (Skill skill : skills) {
            request.skill(skill);
        }
        return request.build();
    }

    /** 按需组装智能体配置。 */
    public static final class Builder {
        private String name = "default";
        private AgentRuntime runtime;
        private Workspace workspace;
        private ExecutionContext context = ExecutionContext.empty();
        private final List<Skill> skills = new ArrayList<Skill>();
        private int maxSteps = 30;
        private CancellationToken cancellationToken = CancellationToken.none();
        private String model;

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder runtime(AgentRuntime runtime) {
            this.runtime = runtime;
            return this;
        }

        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
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

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder cancellationToken(CancellationToken cancellationToken) {
            this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Agent build() {
            return new Agent(this);
        }
    }
}
