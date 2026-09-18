package com.avr.core.tool;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolResult;

/** 从虚拟工作空间删除一个文件。 */
public final class DeleteFileTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "file.delete", "Delete a virtual file. Argument: path",
            "{\"type\":\"object\",\"required\":[\"path\"]}");

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String path = call.requireString("path");
        context.getWorkspace().delete(path);
        return ToolResult.success("deleted " + path);
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }
}
