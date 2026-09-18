package com.avr.core.tool;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolResult;

/** 创建或覆盖一个 UTF-8 虚拟文本文件。 */
public final class WriteFileTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "file.write", "Write a UTF-8 text file to the virtual workspace. Arguments: path, content");

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String path = call.requireString("path");
        String content = call.requireString("content");
        context.getWorkspace().writeText(path, content);
        return ToolResult.success("wrote " + path + " (" + content.length() + " chars)");
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }
}
