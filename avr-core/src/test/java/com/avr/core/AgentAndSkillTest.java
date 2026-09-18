package com.avr.core;

import com.avr.api.Agent;
import com.avr.api.AgentResult;
import com.avr.api.CancellationSource;
import com.avr.api.ExecutionContext;
import com.avr.api.LlmResponse;
import com.avr.api.Skill;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAndSkillTest {
    @Test
    void supportsOneLineAgentInput() {
        AgentLoop runtime = new AgentLoop(
                ScriptedLlm.of(LlmResponse.answer("done")),
                DefaultToolRegistry.builder().build());
        Agent agent = Agent.builder()
                .name("report-agent")
                .runtime(runtime)
                .workspace(new MemoryWorkspace("workspace"))
                .build();

        AgentResult result = agent.input("create report");

        assertEquals("done", result.getText());
    }

    @Test
    void loadsSkillFromVirtualWorkspace() {
        MemoryWorkspace workspace = new MemoryWorkspace("workspace");
        workspace.writeText("/skills/reporter/SKILL.md", "Write a concise report.");
        WorkspaceSkillRegistry registry = new WorkspaceSkillRegistry(workspace);

        Optional<Skill> skill = registry.find("reporter", ExecutionContext.empty());

        assertTrue(skill.isPresent());
        assertEquals("Write a concise report.", skill.get().getInstructions());
    }

    @Test
    void cancelsBeforeCallingTheModel() {
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel();
        AgentLoop runtime = new AgentLoop(
                ScriptedLlm.of(LlmResponse.answer("must not run")),
                DefaultToolRegistry.builder().build());
        Agent agent = Agent.builder()
                .runtime(runtime)
                .workspace(new MemoryWorkspace("workspace"))
                .cancellationToken(cancellation.token())
                .build();

        AgentResult result = agent.input("cancel this");

        assertEquals(com.avr.api.RunState.CANCELLED, result.getState());
        assertEquals(0, result.getSteps());
    }
}
