package com.avr.core.tool;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolDisplayType;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolResult;
import com.avr.core.AgentTaskManager;
import com.avr.core.AgentTaskSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将多 Agent 创建、执行、结果收集和取消能力暴露给主 Agent。 */
public final class AgentManageTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "agent.manage",
            "Manage sub-agent tasks. Operations: list_agents, list_tasks, create, execute, result, cancel. "
                    + "Create requires agent and prompt; execute/result/cancel require taskId.",
            "{\"type\":\"object\",\"required\":[\"op\"],\"additionalProperties\":false,\"properties\":{"
                    + "\"op\":{\"type\":\"string\",\"enum\":[\"list_agents\",\"list_tasks\",\"create\",\"execute\",\"result\",\"cancel\"]},"
                    + "\"agent\":{\"type\":\"string\"},\"prompt\":{\"type\":\"string\"},"
                    + "\"taskId\":{\"type\":\"string\"},\"maxSteps\":{\"type\":\"integer\"},"
                    + "\"waitMillis\":{\"type\":\"integer\"}}}",
            "协同 Agent",
            ToolDisplayType.JSON);

    private final AgentTaskManager manager;

    public AgentManageTool(AgentTaskManager manager) {
        this.manager = manager;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String op = call.requireString("op");
        switch (op) {
            case "list_agents":
                return ToolResult.success(WorkspaceJson.encode(manager.agents()));
            case "list_tasks":
                return ToolResult.success(WorkspaceJson.encode(snapshots(manager.tasks())));
            case "create":
                String taskId = manager.create(
                        call.requireString("agent"), call.requireString("prompt"),
                        context.getWorkspace(), context.getExecutionContext(),
                        integer(call, "maxSteps", 30));
                return ToolResult.success(WorkspaceJson.encode(Map.of("taskId", taskId)));
            case "execute":
                AgentTaskSnapshot started = manager.start(call.requireString("taskId"));
                int wait = Math.max(0, Math.min(integer(call, "waitMillis", 0), 60_000));
                if (wait > 0) {
                    started = manager.await(started.getTaskId(), Duration.ofMillis(wait));
                }
                return ToolResult.success(WorkspaceJson.encode(snapshot(started)));
            case "result":
                return ToolResult.success(WorkspaceJson.encode(
                        snapshot(manager.snapshot(call.requireString("taskId")))));
            case "cancel":
                return ToolResult.success(WorkspaceJson.encode(
                        snapshot(manager.cancel(call.requireString("taskId")))));
            default:
                return ToolResult.failure("unsupported agent operation: " + op);
        }
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    private static List<Map<String, Object>> snapshots(List<AgentTaskSnapshot> values) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AgentTaskSnapshot value : values) {
            result.add(snapshot(value));
        }
        return result;
    }

    private static Map<String, Object> snapshot(AgentTaskSnapshot value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("taskId", value.getTaskId());
        result.put("agent", value.getAgent());
        result.put("status", value.getStatus().name());
        result.put("runId", value.getRunId());
        result.put("result", value.getResult());
        result.put("error", value.getError());
        result.put("createdAt", value.getCreatedAt().toString());
        result.put("completedAt", value.getCompletedAt() == null
                ? null : value.getCompletedAt().toString());
        return result;
    }

    private static int integer(ToolCall call, String name, int fallback) {
        Object value = call.getArguments().get(name);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
}
