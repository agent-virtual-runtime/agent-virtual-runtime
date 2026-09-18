package com.avr.core.tool;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolResult;

/** 递归列出虚拟目录中的文件。 */
public final class ListFilesTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "file.list", "List files recursively under a virtual directory. Argument: path",
            "{\"type\":\"object\",\"required\":[\"path\"]}");

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        return ToolResult.success(
                String.join("\n", context.getWorkspace().list(call.requireString("path"))));
    }
}
