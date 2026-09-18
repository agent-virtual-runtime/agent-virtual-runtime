package com.avr.api;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 智能体运行产生的最终文本、状态和 Artifact。 */
@Getter
public final class AgentResult {
    private final String text;
    private final int steps;
    private final List<Artifact> artifacts;
    private final String runId;
    private final RunState state;

    public AgentResult(String text, int steps, List<Artifact> artifacts) {
        this(null, RunState.COMPLETED, text, steps, artifacts);
    }

    public AgentResult(String runId, RunState state, String text, int steps, List<Artifact> artifacts) {
        this.runId = runId;
        this.state = state;
        this.text = text;
        this.steps = steps;
        this.artifacts = Collections.unmodifiableList(new ArrayList<Artifact>(artifacts));
    }

}
