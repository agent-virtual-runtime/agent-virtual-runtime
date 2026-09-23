package com.avr.core.tool;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolDisplayType;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolResult;
import com.avr.api.WorkspaceEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 创建、浏览、复制、移动、合并和删除虚拟目录。 */
public final class DirectoryTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "directory.manage",
            "Manage virtual directories. Operations: list, create, copy, move, merge, delete. "
                    + "Arguments: op, path; source/target for copy/move/merge; recursive for delete.",
            "{\"type\":\"object\",\"required\":[\"op\"],\"additionalProperties\":false,\"properties\":{"
                    + "\"op\":{\"type\":\"string\",\"enum\":[\"list\",\"create\",\"copy\",\"move\",\"merge\",\"delete\"]},"
                    + "\"path\":{\"type\":\"string\"},\"source\":{\"type\":\"string\"},"
                    + "\"target\":{\"type\":\"string\"},\"recursive\":{\"type\":\"boolean\"}}}",
            "管理目录",
            ToolDisplayType.TREE);

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String op = call.requireString("op");
        switch (op) {
            case "list":
                List<WorkspaceEntry> directoryEntries = context.getWorkspace()
                        .entries(call.requireString("path"));
                return ToolResult.success(
                        entries(directoryEntries), directoryEntries.size(),
                        ToolDisplayType.TABLE);
            case "create":
                context.getWorkspace().createDirectory(call.requireString("path"));
                return ok("created", call.requireString("path"));
            case "copy":
                context.getWorkspace().copyDirectory(
                        call.requireString("source"), call.requireString("target"), false);
                return ok("copied", call.requireString("target"));
            case "move":
                context.getWorkspace().moveDirectory(
                        call.requireString("source"), call.requireString("target"), false);
                return ok("moved", call.requireString("target"));
            case "merge":
                context.getWorkspace().copyDirectory(
                        call.requireString("source"), call.requireString("target"), true);
                return ok("merged", call.requireString("target"));
            case "delete":
                context.getWorkspace().deleteDirectory(
                        call.requireString("path"), booleanArgument(call, "recursive", false));
                return ok("deleted", call.requireString("path"));
            default:
                return ToolResult.failure("unsupported directory operation: " + op);
        }
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    private static String entries(List<WorkspaceEntry> entries) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (WorkspaceEntry entry : entries) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("path", entry.getPath());
            value.put("type", entry.getType().name().toLowerCase());
            value.put("size", entry.getSize());
            values.add(value);
        }
        return WorkspaceJson.encode(Map.of("entries", values, "count", values.size()));
    }

    private static ToolResult ok(String action, String path) {
        return ToolResult.success(WorkspaceJson.encode(
                Map.of("ok", true, "action", action, "path", path)),
                ToolDisplayType.JSON);
    }

    static boolean booleanArgument(ToolCall call, String name, boolean fallback) {
        Object value = call.getArguments().get(name);
        return value == null ? fallback : Boolean.TRUE.equals(value);
    }
}
