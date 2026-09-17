// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CapabilityCall {
    private final String id;
    private final String name;
    private final Map<String, Object> arguments;

    public CapabilityCall(String id, String name, Map<String, Object> arguments) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public String requireString(String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("argument '" + key + "' must be a string");
        }
        return (String) value;
    }
}

