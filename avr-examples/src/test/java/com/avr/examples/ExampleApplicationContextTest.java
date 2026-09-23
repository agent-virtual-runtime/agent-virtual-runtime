package com.avr.examples;

import com.avr.api.AgentRuntime;
import com.avr.api.ToolRegistry;
import com.avr.core.AgentTaskManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证示例的 Starter、聊天服务和多 Agent Tool 可以完整装配。 */
class ExampleApplicationContextTest {
    @Test
    void startsCompleteApplicationContext() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                StockReportSpringApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--avr.llm.openai.api-key=test-only",
                        "--avr.llm.openai.model=test-model",
                        "--avr.chat.workspace.type=memory",
                        "--avr.workspace.type=memory")) {
            assertNotNull(context.getBean(AgentRuntime.class));
            assertNotNull(context.getBean(ChatAgentService.class));
            assertNotNull(context.getBean(ToolRegistry.class)
                    .require("agent.manage"));
            assertTrue(context.getBean(AgentTaskManager.class)
                    .agents().containsKey("research-agent"));
        }
    }
}
