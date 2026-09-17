// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.core;

import org.agentvirtualruntime.api.Capability;
import org.agentvirtualruntime.api.CapabilityDescriptor;
import org.agentvirtualruntime.api.CapabilityRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultCapabilityRegistry implements CapabilityRegistry {
    private final Map<String, Capability> capabilities;

    private DefaultCapabilityRegistry(Map<String, Capability> capabilities) {
        this.capabilities = Collections.unmodifiableMap(
                new LinkedHashMap<String, Capability>(capabilities));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<CapabilityDescriptor> descriptors() {
        List<CapabilityDescriptor> result = new ArrayList<CapabilityDescriptor>();
        for (Capability capability : capabilities.values()) {
            result.add(capability.descriptor());
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Capability require(String name) {
        Capability capability = capabilities.get(name);
        if (capability == null) {
            throw new IllegalArgumentException("unknown capability: " + name);
        }
        return capability;
    }

    public static final class Builder {
        private final Map<String, Capability> capabilities =
                new LinkedHashMap<String, Capability>();

        public Builder register(Capability capability) {
            String name = capability.descriptor().getName();
            if (capabilities.put(name, capability) != null) {
                throw new IllegalArgumentException("duplicate capability: " + name);
            }
            return this;
        }

        public DefaultCapabilityRegistry build() {
            return new DefaultCapabilityRegistry(capabilities);
        }
    }
}

