package com.avr.core.tool;

import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolExecutionMode;
import com.avr.api.ToolResult;
import com.avr.core.AgentRuntimeRegistry;

/** 将任务委派给注册表中的具名子智能体。 */
public final class DelegateAgentTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "agent.delegate", "Delegate a task to a named agent in the same workspace. Arguments: agent, prompt",
            "{\"type\":\"object\",\"required\":[\"agent\",\"prompt\"]}");
    private final AgentRuntimeRegistry agents;

    public DelegateAgentTool(AgentRuntimeRegistry agents) {
        this.agents = agents;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String name = call.requireString("agent");
        AgentResult result = agents.require(name).run(AgentRequest.builder()
                .agent(name).prompt(call.requireString("prompt")).workspace(context.getWorkspace())
                .context(context.getExecutionContext()).build());
        return ToolResult.success(result.getText());
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }
}
