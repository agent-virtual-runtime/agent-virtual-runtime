package com.avr.api;

import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/** 由运行事件投影得到的只读运行状态。 */
@Getter
public final class RunSnapshot {
    private final String runId;
    private final String agent;
    private final String workspaceId;
    private final RunState state;
    private final long lastSequence;
    private final Instant startedAt;
    private final Instant updatedAt;
    private final Instant completedAt;
    private final String lastEventType;
    private final String lastDetail;

    public RunSnapshot(
            String runId,
            String agent,
            String workspaceId,
            RunState state,
            long lastSequence,
            Instant startedAt,
            Instant updatedAt,
            Instant completedAt,
            String lastEventType,
            String lastDetail) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.state = Objects.requireNonNull(state, "state");
        this.lastSequence = lastSequence;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.completedAt = completedAt;
        this.lastEventType = Objects.requireNonNull(lastEventType, "lastEventType");
        this.lastDetail = lastDetail == null ? "" : lastDetail;
    }

}
