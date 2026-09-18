package com.avr.api;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 模型请求的一次结构化工具调用。 */
@Getter
public final class ToolCall {
    private final String id;
    private final String name;
    private final Map<String, Object> arguments;

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
    }

    /** 读取必需的字符串参数。 */
    public String requireString(String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("argument '" + key + "' must be a string");
        }
        return (String) value;
    }
}
