package com.avr.core.tool;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolResult;

/** 移动一个虚拟文件。 */
public final class MoveFileTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "file.move", "Move a virtual file. Arguments: source, target",
            "{\"type\":\"object\",\"required\":[\"source\",\"target\"]}");

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String source = call.requireString("source");
        String target = call.requireString("target");
        context.getWorkspace().move(source, target);
        return ToolResult.success("moved " + source + " to " + target);
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }
}
