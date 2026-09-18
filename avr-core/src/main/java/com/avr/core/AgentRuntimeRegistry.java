package com.avr.core;

import com.avr.api.AgentRuntime;

import java.util.LinkedHashMap;
import java.util.Map;

/** 用于子智能体委派的具名运行时注册表。 */
public final class AgentRuntimeRegistry {
    private final Map<String, AgentRuntime> agents = new LinkedHashMap<String, AgentRuntime>();

    public AgentRuntimeRegistry register(String name, AgentRuntime runtime) {
        if (agents.put(name, runtime) != null) {
            throw new IllegalArgumentException("duplicate agent: " + name);
        }
        return this;
    }

    public AgentRuntime require(String name) {
        AgentRuntime runtime = agents.get(name);
        if (runtime == null) {
            throw new IllegalArgumentException("unknown agent: " + name);
        }
        return runtime;
    }
}
