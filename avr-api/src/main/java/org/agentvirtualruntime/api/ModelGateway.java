// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.List;

public interface ModelGateway {
    ModelResponse generate(List<AgentMessage> messages, List<CapabilityDescriptor> capabilities);
}

