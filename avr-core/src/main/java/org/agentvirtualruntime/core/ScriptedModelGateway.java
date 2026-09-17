// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.core;

import org.agentvirtualruntime.api.AgentMessage;
import org.agentvirtualruntime.api.CapabilityDescriptor;
import org.agentvirtualruntime.api.ModelGateway;
import org.agentvirtualruntime.api.ModelResponse;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public final class ScriptedModelGateway implements ModelGateway {
    private final Deque<ModelResponse> responses;

    public ScriptedModelGateway(List<ModelResponse> responses) {
        this.responses = new ArrayDeque<ModelResponse>(responses);
    }

    public static ScriptedModelGateway of(ModelResponse... responses) {
        return new ScriptedModelGateway(Arrays.asList(responses));
    }

    @Override
    public ModelResponse generate(
            List<AgentMessage> messages,
            List<CapabilityDescriptor> capabilities) {
        ModelResponse response = responses.pollFirst();
        if (response == null) {
            throw new IllegalStateException("scripted model has no response left");
        }
        return response;
    }
}
