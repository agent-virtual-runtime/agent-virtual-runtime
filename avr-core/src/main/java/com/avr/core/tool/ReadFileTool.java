package com.avr.core.tool;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolResult;

/** 读取一个 UTF-8 虚拟文本文件。 */
public final class ReadFileTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "file.read", "Read a UTF-8 text file from the virtual workspace. Argument: path");

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String path = call.requireString("path");
        return ToolResult.success(context.getWorkspace().readText(path));
    }
}
