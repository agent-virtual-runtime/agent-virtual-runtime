// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AgentResult {
    private final String text;
    private final int steps;
    private final List<Artifact> artifacts;

    public AgentResult(String text, int steps, List<Artifact> artifacts) {
        this.text = text;
        this.steps = steps;
        this.artifacts = Collections.unmodifiableList(new ArrayList<Artifact>(artifacts));
    }

    public String getText() {
        return text;
    }

    public int getSteps() {
        return steps;
    }

    public List<Artifact> getArtifacts() {
        return artifacts;
    }
}

