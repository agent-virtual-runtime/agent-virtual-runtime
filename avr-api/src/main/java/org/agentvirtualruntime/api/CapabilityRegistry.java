// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.List;

public interface CapabilityRegistry {
    List<CapabilityDescriptor> descriptors();
    Capability require(String name);
}

