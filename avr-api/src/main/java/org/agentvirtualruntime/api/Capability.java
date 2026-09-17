// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

public interface Capability {
    CapabilityDescriptor descriptor();
    CapabilityResult invoke(CapabilityCall call, CapabilityContext context);
}

