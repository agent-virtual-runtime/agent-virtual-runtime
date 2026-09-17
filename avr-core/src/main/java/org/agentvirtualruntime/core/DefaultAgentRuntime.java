// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.core;

import org.agentvirtualruntime.api.AgentMessage;
import org.agentvirtualruntime.api.AgentRequest;
import org.agentvirtualruntime.api.AgentResult;
import org.agentvirtualruntime.api.AgentRuntime;
import org.agentvirtualruntime.api.Capability;
import org.agentvirtualruntime.api.CapabilityCall;
import org.agentvirtualruntime.api.CapabilityContext;
import org.agentvirtualruntime.api.CapabilityRegistry;
import org.agentvirtualruntime.api.CapabilityResult;
import org.agentvirtualruntime.api.ModelGateway;
import org.agentvirtualruntime.api.ModelResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DefaultAgentRuntime implements AgentRuntime {
    private static final String SYSTEM_PROMPT =
            "You are running inside Agent Virtual Runtime. " +
            "Use only the capabilities provided by the runtime.";

    private final ModelGateway modelGateway;
    private final CapabilityRegistry capabilities;

    public DefaultAgentRuntime(ModelGateway modelGateway, CapabilityRegistry capabilities) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    @Override
    public AgentResult run(AgentRequest request) {
        List<AgentMessage> messages = new ArrayList<AgentMessage>();
        messages.add(AgentMessage.system(SYSTEM_PROMPT));
        messages.add(AgentMessage.user(request.getPrompt()));

        for (int step = 1; step <= request.getMaxSteps(); step++) {
            ModelResponse response = modelGateway.generate(messages, capabilities.descriptors());

            if (response.getCapabilityCalls().isEmpty()) {
                messages.add(AgentMessage.assistant(response.getText()));
                return new AgentResult(
                        response.getText(),
                        step,
                        request.getWorkspace().artifacts());
            }

            messages.add(AgentMessage.assistant(response.getText()));
            for (CapabilityCall call : response.getCapabilityCalls()) {
                CapabilityResult result = invoke(call, request);
                messages.add(AgentMessage.tool(call.getId(), result.getContent()));
            }
        }

        throw new IllegalStateException(
                "agent exceeded maxSteps=" + request.getMaxSteps());
    }

    private CapabilityResult invoke(CapabilityCall call, AgentRequest request) {
        try {
            Capability capability = capabilities.require(call.getName());
            return capability.invoke(call, new CapabilityContext(request.getWorkspace()));
        } catch (RuntimeException exception) {
            return CapabilityResult.failure(
                    "ERROR " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }
}

