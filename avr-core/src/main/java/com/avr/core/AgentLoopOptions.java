package com.avr.core;

import lombok.Getter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/** {@link AgentLoop} 的并发、超时和循环保护配置。 */
@Getter
public final class AgentLoopOptions {
    private final ExecutorService toolExecutor;
    private final Duration toolTimeout;
    private final Duration loopTimeout;
    private final int maxStepMultiplier;
    private final int maxEmptyResponses;
    private final int maxNoProgressRounds;

    private AgentLoopOptions(Builder builder) {
        this.toolExecutor = builder.toolExecutor;
        this.toolTimeout = builder.toolTimeout;
        this.loopTimeout = builder.loopTimeout;
        this.maxStepMultiplier = builder.maxStepMultiplier;
        this.maxEmptyResponses = builder.maxEmptyResponses;
        this.maxNoProgressRounds = builder.maxNoProgressRounds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AgentLoopOptions defaults() {
        return builder().build();
    }

    /** 构建 Agent Loop 配置。 */
    public static final class Builder {
        private ExecutorService toolExecutor = ForkJoinPool.commonPool();
        private Duration toolTimeout = Duration.ofMinutes(5);
        private Duration loopTimeout = Duration.ofMinutes(90);
        private int maxStepMultiplier = 3;
        private int maxEmptyResponses = 1;
        private int maxNoProgressRounds = 5;

        public Builder toolExecutor(ExecutorService toolExecutor) {
            this.toolExecutor = Objects.requireNonNull(
                    toolExecutor, "toolExecutor");
            return this;
        }

        public Builder toolTimeout(Duration toolTimeout) {
            this.toolTimeout = Objects.requireNonNull(
                    toolTimeout, "toolTimeout");
            return this;
        }

        /** 设置一次 Agent 运行的整体时间预算。 */
        public Builder loopTimeout(Duration loopTimeout) {
            this.loopTimeout = Objects.requireNonNull(loopTimeout, "loopTimeout");
            return this;
        }

        /** 设置软步数上限可自动延展的倍数，1 表示不延展。 */
        public Builder maxStepMultiplier(int maxStepMultiplier) {
            if (maxStepMultiplier < 1) {
                throw new IllegalArgumentException("maxStepMultiplier must be positive");
            }
            this.maxStepMultiplier = maxStepMultiplier;
            return this;
        }

        public Builder maxEmptyResponses(int maxEmptyResponses) {
            this.maxEmptyResponses = positiveOrZero(maxEmptyResponses, "maxEmptyResponses");
            return this;
        }

        public Builder maxNoProgressRounds(int maxNoProgressRounds) {
            if (maxNoProgressRounds < 1) {
                throw new IllegalArgumentException("maxNoProgressRounds must be positive");
            }
            this.maxNoProgressRounds = maxNoProgressRounds;
            return this;
        }

        public AgentLoopOptions build() {
            if (toolTimeout.isZero() || toolTimeout.isNegative()) {
                throw new IllegalArgumentException("toolTimeout must be positive");
            }
            if (loopTimeout.isZero() || loopTimeout.isNegative()) {
                throw new IllegalArgumentException("loopTimeout must be positive");
            }
            return new AgentLoopOptions(this);
        }

        private static int positiveOrZero(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }
}
