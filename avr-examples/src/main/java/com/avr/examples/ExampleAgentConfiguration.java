package com.avr.examples;

import com.avr.api.Llm;
import com.avr.api.RuntimeEventListener;
import com.avr.api.Tool;
import com.avr.api.ToolPolicy;
import com.avr.api.ToolRegistry;
import com.avr.core.AgentLoop;
import com.avr.core.AgentLoopOptions;
import com.avr.core.AgentTaskManager;
import com.avr.core.DefaultToolRegistry;
import com.avr.core.tool.AgentManageTool;
import com.avr.core.tool.WorkspaceTools;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Collectors;

/** 为工作台示例注册可被主 Agent 调度的专业子 Agent。 */
@Configuration(proxyBeanMethods = false)
public class ExampleAgentConfiguration {
    @Bean
    public AgentTaskManager agentTaskManager(
            Llm llm,
            ToolPolicy policy,
            AgentLoopOptions options,
            ObjectProvider<RuntimeEventListener> eventListeners) {
        DefaultToolRegistry.Builder tools = DefaultToolRegistry.builder();
        WorkspaceTools.defaults().forEach(tools::register);
        ToolRegistry childTools = tools.build();
        List<RuntimeEventListener> listeners = eventListeners.orderedStream()
                .collect(Collectors.toList());
        AgentLoop childRuntime = new AgentLoop(llm, childTools, policy, options, listeners);

        return new AgentTaskManager()
                .register("research-agent", "负责资料检索、事实梳理与结构化摘要", childRuntime)
                .register("writer-agent", "负责长报告撰写、增量编辑与产物整理", childRuntime);
    }

    @Bean(name = "avrAgentManageTool")
    public Tool avrAgentManageTool(AgentTaskManager manager) {
        return new AgentManageTool(manager);
    }
}
