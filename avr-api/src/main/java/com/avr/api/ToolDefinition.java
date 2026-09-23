package com.avr.api;

import lombok.Getter;

import java.util.Objects;

/** 提供给模型的工具名称、说明和输入 Schema。 */
@Getter
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final String inputSchema;
    private final String displayName;
    private final ToolDisplayType displayType;

    public ToolDefinition(String name, String description) {
        this(name, description, "{\"type\":\"object\"}");
    }

    public ToolDefinition(String name, String description, String inputSchema) {
        this(name, description, inputSchema, name, ToolDisplayType.TEXT);
    }

    public ToolDefinition(
            String name,
            String description,
            String inputSchema,
            String displayName,
            ToolDisplayType displayType) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.displayType = Objects.requireNonNull(displayType, "displayType");
    }

}
