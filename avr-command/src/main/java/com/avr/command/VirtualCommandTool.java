package com.avr.command;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolResult;
import com.avr.api.ExecutionContext;
import com.avr.api.Workspace;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 在 Workspace 上执行有限且确定的虚拟命令，不启动操作系统进程。 */
public final class VirtualCommandTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "command.execute", "Execute a virtual command: pwd, ls, cat, grep, wc or curl. Argument: command",
            "{\"type\":\"object\",\"required\":[\"command\"],\"properties\":{\"command\":{\"type\":\"string\"}}}");
    private final VirtualHttpClient httpClient;

    public VirtualCommandTool() {
        this(null);
    }

    public VirtualCommandTool(VirtualHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        try {
            return ToolResult.success(
                    execute(call.requireString("command"), context.getWorkspace(),
                            context.getExecutionContext()));
        } catch (RuntimeException exception) {
            return ToolResult.failure("ERROR: " + exception.getMessage());
        }
    }

    /** 在指定工作空间执行一条虚拟命令。 */
    public String execute(String command, Workspace workspace) {
        return execute(command, workspace, ExecutionContext.empty());
    }

    public String execute(String command, Workspace workspace, ExecutionContext context) {
        String value = command == null ? "" : command.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (value.contains(";")
                || value.contains("&&")
                || value.contains("||")
                || value.contains("`")
                || value.contains("$(")) {
            throw new IllegalArgumentException("shell operators are not supported");
        }
        String[] words = value.split("\\s+");
        if ("pwd".equals(words[0]) && words.length == 1) {
            return "/";
        }
        if ("ls".equals(words[0]) && words.length <= 2) {
            String directory = words.length == 1 ? "/" : words[1];
            return String.join("\n", workspace.list(directory));
        }
        if ("cat".equals(words[0]) && words.length == 2) {
            return workspace.readText(words[1]);
        }
        if ("wc".equals(words[0]) && words.length == 2) {
            String text = workspace.readText(words[1]);
            int lines = text.isEmpty() ? 0 : text.split("\\R", -1).length;
            int wordCount = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
            return lines + " " + wordCount + " " + text.length() + " " + words[1];
        }
        if ("grep".equals(words[0]) && words.length == 3) {
            Pattern pattern = Pattern.compile(words[1]);
            List<String> matches = Pattern.compile("\\R").splitAsStream(workspace.readText(words[2]))
                    .filter(line -> pattern.matcher(line).find()).collect(Collectors.toList());
            return String.join("\n", matches);
        }
        if ("curl".equals(words[0]) && words.length == 2) {
            if (httpClient == null) {
                throw new IllegalArgumentException("virtual HTTP client is not configured");
            }
            return httpClient.get(words[1], context);
        }
        throw new IllegalArgumentException("unsupported virtual command: " + words[0]);
    }
}
