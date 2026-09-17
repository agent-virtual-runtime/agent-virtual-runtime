// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.Objects;

public final class CapabilityDescriptor {
    private final String name;
    private final String description;

    public CapabilityDescriptor(String name, String description) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}

