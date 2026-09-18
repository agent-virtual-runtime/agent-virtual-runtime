package com.avr.api;

import java.util.List;

/** 保存运行时可暴露给模型并可执行的工具。 */
public interface ToolRegistry {
    List<ToolDefinition> definitions();
    Tool require(String name);
}
