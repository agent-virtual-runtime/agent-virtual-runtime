package com.avr.core.tool;

import com.avr.api.Artifact;
import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolDisplayType;
import com.avr.api.ToolResult;

/** 将指定虚拟目录提交为带入口文件的 Artifact。 */
public final class CommitArtifactTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "artifact.commit", "Commit an immutable artifact. Arguments: root, entrypoint",
            "{\"type\":\"object\",\"required\":[\"root\",\"entrypoint\"],"
                    + "\"additionalProperties\":false,\"properties\":{"
                    + "\"root\":{\"type\":\"string\"},"
                    + "\"entrypoint\":{\"type\":\"string\"}}}",
            "提交产物", ToolDisplayType.TEXT);

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        Artifact artifact = context.getWorkspace().commitArtifact(
                call.requireString("root"), call.requireString("entrypoint"));
        return ToolResult.success(
                "committed artifact " + artifact.getId() + " entrypoint=" + artifact.getEntrypoint());
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }
}
