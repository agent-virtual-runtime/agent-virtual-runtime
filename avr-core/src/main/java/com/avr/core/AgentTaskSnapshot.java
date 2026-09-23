package com.avr.core;

import lombok.Getter;

import java.time.Instant;

/** 多 Agent 管理器对外暴露的不可变任务快照。 */
@Getter
public final class AgentTaskSnapshot {
    private final String taskId;
    private final String agent;
    private final AgentTaskStatus status;
    private final String runId;
    private final String result;
    private final String error;
    private final Instant createdAt;
    private final Instant completedAt;

    AgentTaskSnapshot(
            String taskId,
            String agent,
            AgentTaskStatus status,
            String runId,
            String result,
            String error,
            Instant createdAt,
            Instant completedAt) {
        this.taskId = taskId;
        this.agent = agent;
        this.status = status;
        this.runId = runId;
        this.result = result;
        this.error = error;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }
}
