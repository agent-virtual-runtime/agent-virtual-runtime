package com.avr.api;

import lombok.Getter;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Agent Runtime 在运行过程中发布的不可变事件。 */
@Getter
public final class RuntimeEvent {
    private final String runId;
    private final String agent;
    private final String workspaceId;
    private final long sequence;
    private final Instant occurredAt;
    private final RunState state;
    private final String type;
    private final String detail;
    private final Map<String, Object> attributes;

    public RuntimeEvent(
            String runId,
            String agent,
            String workspaceId,
            long sequence,
            RunState state,
            String type,
            String detail,
            Map<String, Object> attributes) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.sequence = sequence;
        this.occurredAt = Instant.now();
        this.state = Objects.requireNonNull(state, "state");
        this.type = Objects.requireNonNull(type, "type");
        this.detail = detail == null ? "" : detail;
        this.attributes = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(attributes));
    }

}
