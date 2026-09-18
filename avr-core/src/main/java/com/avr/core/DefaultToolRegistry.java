package com.avr.core;

import com.avr.api.Tool;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 向运行时暴露工具定义和实例的不可变注册表。 */
public final class DefaultToolRegistry implements ToolRegistry {
    private final Map<String, Tool> tools;

    private DefaultToolRegistry(Map<String, Tool> tools) {
        this.tools = Collections.unmodifiableMap(
                new LinkedHashMap<String, Tool>(tools));
    }

    /** 创建工具注册表构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<ToolDefinition> definitions() {
        List<ToolDefinition> result = new ArrayList<ToolDefinition>();
        for (Tool tool : tools.values()) {
            result.add(tool.definition());
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Tool require(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        return tool;
    }

    /** 按名称注册工具并构建不可变注册表。 */
    public static final class Builder {
        private final Map<String, Tool> tools =
                new LinkedHashMap<String, Tool>();

        public Builder register(Tool tool) {
            String name = tool.definition().getName();
            if (tools.put(name, tool) != null) {
                throw new IllegalArgumentException("duplicate tool: " + name);
            }
            return this;
        }

        public DefaultToolRegistry build() {
            return new DefaultToolRegistry(tools);
        }
    }
}
