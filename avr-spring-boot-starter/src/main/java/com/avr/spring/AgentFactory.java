package com.avr.spring;

import com.avr.api.Agent;
import com.avr.api.AgentRuntime;
import com.avr.api.ExecutionContext;
import com.avr.api.RunObserver;
import com.avr.api.Skill;
import com.avr.api.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 使用应用默认配置创建请求级 Agent 实例。 */
public final class AgentFactory {
    private final AgentRuntime runtime;
    private final AvrAgentProperties properties;
    private final List<Skill> skills;

    public AgentFactory(
            AgentRuntime runtime,
            AvrAgentProperties properties,
            List<Skill> skills) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.skills = new ArrayList<Skill>(skills);
    }

    /** 使用指定工作空间创建 Agent。 */
    public Agent create(Workspace workspace) {
        return create(workspace, ExecutionContext.empty(), RunObserver.noop());
    }

    /** 使用指定工作空间、执行上下文和观察者创建 Agent。 */
    public Agent create(
            Workspace workspace,
            ExecutionContext context,
            RunObserver observer) {
        Agent.Builder builder = Agent.builder()
                .name(properties.getName())
                .runtime(runtime)
                .workspace(workspace)
                .context(context)
                .observer(observer)
                .maxSteps(properties.getMaxSteps());
        for (Skill skill : skills) {
            builder.skill(skill);
        }
        return builder.build();
    }
}
