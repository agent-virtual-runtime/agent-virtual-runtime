package com.avr.core;

import com.avr.api.ExecutionContext;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolResult;
import com.avr.core.tool.DirectoryTool;
import com.avr.core.tool.FileOpTool;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceToolsTest {
    @Test
    void editsLargeFilesIncrementallyThroughStandardTools() {
        MemoryWorkspace workspace = new MemoryWorkspace("test");
        workspace.writeText("/workspace/report.md", "title\nsection-a\nsection-b");
        FileOpTool tool = new FileOpTool();
        ToolContext context = new ToolContext(workspace, ExecutionContext.empty());

        ToolResult read = tool.execute(call("read", Map.of(
                "path", "/workspace/report.md", "startLine", 2, "endLine", 3)), context);
        assertTrue(read.getContent().contains("section-a"));

        tool.execute(call("insert", Map.of(
                "path", "/workspace/report.md", "beforeLine", 2,
                "content", "summary")), context);
        tool.execute(call("replace_lines", Map.of(
                "path", "/workspace/report.md", "startLine", 3, "endLine", 3,
                "content", "updated")), context);
        assertEquals("title\nsummary\nupdated\nsection-b",
                workspace.readText("/workspace/report.md"));
    }

    @Test
    void managesDirectoryLifecycleThroughOneTool() {
        MemoryWorkspace workspace = new MemoryWorkspace("test");
        DirectoryTool tool = new DirectoryTool();
        ToolContext context = new ToolContext(workspace);

        tool.execute(call("create", Map.of("path", "/workspace/source")), context);
        workspace.writeText("/workspace/source/a.txt", "a");
        tool.execute(call("copy", Map.of(
                "source", "/workspace/source", "target", "/workspace/copy")), context);
        tool.execute(call("move", Map.of(
                "source", "/workspace/copy", "target", "/workspace/moved")), context);
        assertTrue(workspace.exists("/workspace/moved/a.txt"));
    }

    private static ToolCall call(String op, Map<String, Object> arguments) {
        Map<String, Object> values = new LinkedHashMap<String, Object>(arguments);
        values.put("op", op);
        return new ToolCall("call-1", "workspace", values);
    }
}
