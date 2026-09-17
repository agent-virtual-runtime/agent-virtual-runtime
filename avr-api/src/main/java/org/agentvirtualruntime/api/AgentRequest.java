// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.Objects;

public final class AgentRequest {
    private final String prompt;
    private final Workspace workspace;
    private final int maxSteps;

    private AgentRequest(Builder builder) {
        this.prompt = Objects.requireNonNull(builder.prompt, "prompt");
        this.workspace = Objects.requireNonNull(builder.workspace, "workspace");
        this.maxSteps = builder.maxSteps;
        if (prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getPrompt() {
        return prompt;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public static final class Builder {
        private String prompt;
        private Workspace workspace;
        private int maxSteps = 20;

        public Builder prompt(String prompt) {
            this.prompt = prompt;
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

        public AgentRequest build() {
            return new AgentRequest(this);
        }
    }
}

