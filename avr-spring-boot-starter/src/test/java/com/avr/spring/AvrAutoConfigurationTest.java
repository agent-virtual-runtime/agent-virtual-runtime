package com.avr.spring;

import com.avr.api.Agent;
import com.avr.api.AgentRuntime;
import com.avr.api.Llm;
import com.avr.api.ToolRegistry;
import com.avr.api.Workspace;
import com.avr.model.openai.OpenAiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AvrAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            AvrAutoConfiguration.class))
                    .withPropertyValues(
                            "avr.llm.openai.api-key=test-key",
                            "avr.llm.openai.model=test-model",
                            "avr.workspace.type=memory",
                            "avr.workspace.id=stock-report",
                            "avr.agent.name=stock-agent",
                            "avr.agent.instructions=Use supplied financial data only");

    @Test
    void createsModelRuntimeWorkspaceAndAgentBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OpenAiConfig.class);
            assertThat(context).hasSingleBean(Llm.class);
            assertThat(context).hasSingleBean(ToolRegistry.class);
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context).hasSingleBean(Workspace.class);
            assertThat(context).hasSingleBean(AgentFactory.class);
            assertThat(context).hasSingleBean(Agent.class);
        });
    }
}
