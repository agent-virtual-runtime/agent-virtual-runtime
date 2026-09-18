package com.avr.api;

import java.time.Instant;

/** 智能体运行过程中按序产生的生命周期事件。 */
public final class RunEvent {
    private final String runId;
    private final long sequence;
    private final Instant time;
    private final RunState state;
    private final String type;
    private final String detail;

    public RunEvent(String runId, long sequence, RunState state, String type, String detail) {
        this.runId = runId;
        this.sequence = sequence;
        this.time = Instant.now();
        this.state = state;
        this.type = type;
        this.detail = detail;
    }

    public String getRunId() {
        return runId;
    }

    public long getSequence() {
        return sequence;
    }

    public Instant getTime() {
        return time;
    }

    public RunState getState() {
        return state;
    }

    public String getType() {
        return type;
    }

    public String getDetail() {
        return detail;
    }
}
