package com.avr.core.tool;

import com.avr.api.TextFileSlice;
import com.avr.api.TextSearchMatch;
import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolDisplayType;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一个文件领域工具完成文件生命周期和内容编辑；目录操作由 DirectoryTool 负责。 */
public final class FileOpTool implements Tool {
    private static final int MAX_READ_CHARS = 12_000;
    private static final int MAX_SEARCH_RESULTS = 20;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "file.op",
            "Operate on virtual files. op=list lists files under path; read reads up to 200 numbered "
                    + "lines; search locates text; write creates or overwrites; append grows a file; "
                    + "insert inserts before a line; replace_text replaces exact text; replace_lines "
                    + "and delete_lines edit 1-based inclusive ranges; copy, move, delete manage files. "
                    + "For directories use directory.manage. For long reports write in chunks, then "
                    + "read/search to verify. Required parameters depend on op; missing parameters fail.",
            "{\"type\":\"object\",\"required\":[\"op\"],\"additionalProperties\":false,\"properties\":{"
                    + "\"op\":{\"type\":\"string\",\"enum\":[\"list\",\"read\",\"search\",\"write\",\"append\",\"insert\",\"replace_text\",\"replace_lines\",\"delete_lines\",\"copy\",\"move\",\"delete\"]},"
                    + "\"path\":{\"type\":\"string\",\"description\":\"Virtual file path; directory path for list\"},"
                    + "\"source\":{\"type\":\"string\"},\"target\":{\"type\":\"string\"},"
                    + "\"content\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"},"
                    + "\"oldText\":{\"type\":\"string\"},\"newText\":{\"type\":\"string\"},"
                    + "\"replaceAll\":{\"type\":\"boolean\"},\"caseSensitive\":{\"type\":\"boolean\"},"
                    + "\"startLine\":{\"type\":\"integer\"},\"endLine\":{\"type\":\"integer\"},"
                    + "\"beforeLine\":{\"type\":\"integer\"},\"maxChars\":{\"type\":\"integer\"},"
                    + "\"maxResults\":{\"type\":\"integer\"}}}",
            "操作文件",
            ToolDisplayType.JSON);

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String op = call.requireString("op");
        switch (op) {
            case "list":
                String directory = call.requireString("path");
                List<String> files = context.getWorkspace().list(directory);
                return ToolResult.success(WorkspaceJson.encode(
                        Map.of("path", directory, "files", files, "count", files.size())),
                        files.size(), ToolDisplayType.TREE);
            case "read":
                return read(call, context);
            case "search":
                return search(call, context);
            case "write":
                context.getWorkspace().writeText(
                        call.requireString("path"), call.requireString("content"));
                return changed("write", call.requireString("path"));
            case "append":
                context.getWorkspace().appendText(
                        call.requireString("path"), call.requireString("content"));
                return changed("append", call.requireString("path"));
            case "insert":
                context.getWorkspace().insertLines(call.requireString("path"),
                        integer(call, "beforeLine", -1), call.requireString("content"));
                return changed("insert", call.requireString("path"));
            case "replace_text":
                context.getWorkspace().replaceText(call.requireString("path"),
                        call.requireString("oldText"), call.requireString("newText"),
                        Boolean.TRUE.equals(call.getArguments().get("replaceAll")));
                return changed("replace_text", call.requireString("path"));
            case "replace_lines":
                context.getWorkspace().replaceLines(call.requireString("path"),
                        integer(call, "startLine", -1), integer(call, "endLine", -1),
                        call.requireString("content"));
                return changed("replace_lines", call.requireString("path"));
            case "delete_lines":
                context.getWorkspace().deleteLines(call.requireString("path"),
                        integer(call, "startLine", -1), integer(call, "endLine", -1));
                return changed("delete_lines", call.requireString("path"));
            case "copy":
                context.getWorkspace().copy(call.requireString("source"), call.requireString("target"));
                return changed("copy", call.requireString("target"));
            case "move":
                context.getWorkspace().move(call.requireString("source"), call.requireString("target"));
                return changed("move", call.requireString("target"));
            case "delete":
                context.getWorkspace().delete(call.requireString("path"));
                return changed("delete", call.requireString("path"));
            default:
                return ToolResult.failure("unsupported file operation: " + op);
        }
    }

    private static ToolResult read(ToolCall call, ToolContext context) {
        String path = call.requireString("path");
        int start = integer(call, "startLine", 1);
        int end = integer(call, "endLine", start + 199);
        if (end - start >= 200) {
            throw new IllegalArgumentException("read supports at most 200 lines");
        }
        TextFileSlice slice = context.getWorkspace().readLines(path, start, end,
                Math.min(integer(call, "maxChars", MAX_READ_CHARS), MAX_READ_CHARS));
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("path", path);
        value.put("content", slice.getContent());
        value.put("startLine", slice.getStartLine());
        value.put("endLine", slice.getEndLine());
        value.put("totalLines", slice.getTotalLines());
        value.put("truncated", slice.isTruncated());
        return ToolResult.success(WorkspaceJson.encode(value), ToolDisplayType.JSON);
    }

    private static ToolResult search(ToolCall call, ToolContext context) {
        String path = call.requireString("path");
        List<TextSearchMatch> matches = context.getWorkspace().searchText(path,
                call.requireString("query"),
                Boolean.TRUE.equals(call.getArguments().get("caseSensitive")),
                Math.min(integer(call, "maxResults", MAX_SEARCH_RESULTS), MAX_SEARCH_RESULTS));
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (TextSearchMatch match : matches) {
            values.add(Map.of("line", match.getLine(), "text", match.getText()));
        }
        return ToolResult.success(WorkspaceJson.encode(
                Map.of("path", path, "matches", values, "count", values.size())),
                values.size(), ToolDisplayType.TABLE);
    }

    private static ToolResult changed(String op, String path) {
        return ToolResult.success(WorkspaceJson.encode(
                Map.of("ok", true, "op", op, "path", path)), ToolDisplayType.JSON);
    }

    private static int integer(ToolCall call, String name, int fallback) {
        Object value = call.getArguments().get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return ((Number) value).intValue();
    }
}
