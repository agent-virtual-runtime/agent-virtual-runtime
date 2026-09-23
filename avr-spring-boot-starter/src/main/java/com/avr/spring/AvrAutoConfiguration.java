package com.avr.spring;

import com.avr.api.Agent;
import com.avr.api.AgentRuntime;
import com.avr.api.Llm;
import com.avr.api.RuntimeEventListener;
import com.avr.api.Skill;
import com.avr.api.Tool;
import com.avr.api.ToolPolicy;
import com.avr.api.ToolRegistry;
import com.avr.api.Workspace;
import com.avr.api.WebSearchProvider;
import com.avr.command.HttpGetTool;
import com.avr.command.VirtualHttpClient;
import com.avr.command.WebSearchTool;
import com.avr.core.AgentLoop;
import com.avr.core.AgentLoopOptions;
import com.avr.core.DefaultToolRegistry;
import com.avr.core.tool.CommitArtifactTool;
import com.avr.core.tool.DirectoryTool;
import com.avr.core.tool.FileOpTool;
import com.avr.core.tool.PlanTool;
import com.avr.model.openai.OpenAiConfig;
import com.avr.model.openai.OpenAiLlm;
import com.avr.storage.DiskWorkspace;
import com.avr.storage.MemoryWorkspace;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Agent Virtual Runtime 的 Spring Boot 自动配置。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(AgentLoop.class)
@EnableConfigurationProperties({
        AvrLlmProperties.class,
        AvrAgentProperties.class,
        AvrWorkspaceProperties.class
})
public class AvrAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public OpenAiConfig openAiConfig(AvrLlmProperties properties) {
        OpenAiConfig.Builder builder = OpenAiConfig.builder()
                .apiUrl(properties.getApiUrl())
                .model(properties.getModel())
                .stream(properties.isStream())
                .maxRetries(properties.getMaxRetries())
                .timeout(properties.getTimeout());
        if (hasText(properties.getApiKey())) {
            builder.apiKey(properties.getApiKey());
        }
        if (properties.getTemperature() != null) {
            builder.temperature(properties.getTemperature());
        }
        if (properties.getMaxTokens() != null) {
            builder.maxTokens(properties.getMaxTokens());
        }
        for (Map.Entry<String, String> header : properties.getHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean(Llm.class)
    public Llm llm(OpenAiConfig config) {
        return new OpenAiLlm(config);
    }

    @Bean
    @ConditionalOnMissingBean(ToolPolicy.class)
    public ToolPolicy toolPolicy() {
        return ToolPolicy.allowAll();
    }

    @Bean
    @ConditionalOnMissingBean(name = "avrFileOpTool")
    public Tool avrFileOpTool() {
        return new FileOpTool();
    }

    @Bean
    @ConditionalOnMissingBean(name = "avrPlanTool")
    public Tool avrPlanTool() {
        return new PlanTool();
    }

    @Bean
    @ConditionalOnMissingBean(name = "avrDirectoryTool")
    public Tool avrDirectoryTool() {
        return new DirectoryTool();
    }

    @Bean
    @ConditionalOnMissingBean(name = "avrCommitArtifactTool")
    public Tool avrCommitArtifactTool() {
        return new CommitArtifactTool();
    }

    @Bean
    @ConditionalOnBean(VirtualHttpClient.class)
    @ConditionalOnMissingBean(name = "avrHttpGetTool")
    public Tool avrHttpGetTool(VirtualHttpClient httpClient) {
        return new HttpGetTool(httpClient);
    }

    /** 应用提供搜索实现后，自动将其注册为普通 Function Tool。 */
    @Bean
    @ConditionalOnBean(WebSearchProvider.class)
    @ConditionalOnMissingBean(name = "avrWebSearchTool")
    public Tool avrWebSearchTool(WebSearchProvider provider) {
        return new WebSearchTool(provider);
    }

    @Bean
    @ConditionalOnMissingBean(ToolRegistry.class)
    public ToolRegistry toolRegistry(ObjectProvider<Tool> tools) {
        DefaultToolRegistry.Builder registry = DefaultToolRegistry.builder();
        tools.orderedStream().forEach(registry::register);
        return registry.build();
    }

    @Bean
    @ConditionalOnMissingBean(AgentLoopOptions.class)
    public AgentLoopOptions agentLoopOptions(AvrAgentProperties properties) {
        return AgentLoopOptions.builder()
                .toolTimeout(properties.getToolTimeout())
                .loopTimeout(properties.getLoopTimeout())
                .maxStepMultiplier(properties.getMaxStepMultiplier())
                .maxEmptyResponses(properties.getMaxEmptyResponses())
                .maxNoProgressRounds(properties.getMaxNoProgressRounds())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntime.class)
    public AgentRuntime agentRuntime(
            Llm llm,
            ToolRegistry tools,
            ToolPolicy policy,
            AgentLoopOptions options,
            ObjectProvider<RuntimeEventListener> eventListeners) {
        return new AgentLoop(
                llm,
                tools,
                policy,
                options,
                eventListeners.orderedStream().collect(Collectors.toList()));
    }

    @Bean
    @ConditionalOnMissingBean(AgentFactory.class)
    public AgentFactory agentFactory(
            AgentRuntime runtime,
            AvrAgentProperties properties,
            ObjectProvider<Skill> skills) {
        List<Skill> configured = skills.orderedStream()
                .collect(Collectors.toList());
        if (hasText(properties.getInstructions())) {
            configured = new ArrayList<Skill>(configured);
            configured.add(new Skill(
                    properties.getSkillName(),
                    properties.getInstructions(),
                    Collections.<String>emptyList()));
        }
        return new AgentFactory(runtime, properties, configured);
    }

    @Bean
    @ConditionalOnMissingBean(Workspace.class)
    @ConditionalOnProperty(
            prefix = "avr.workspace",
            name = "type",
            havingValue = "memory")
    public Workspace memoryWorkspace(AvrWorkspaceProperties properties) {
        return new MemoryWorkspace(properties.getId());
    }

    @Bean
    @ConditionalOnMissingBean(Workspace.class)
    @ConditionalOnProperty(
            prefix = "avr.workspace",
            name = "type",
            havingValue = "disk")
    public Workspace diskWorkspace(AvrWorkspaceProperties properties) {
        return new DiskWorkspace(
                properties.getId(), Paths.get(properties.getPath()));
    }

    @Bean
    @ConditionalOnMissingBean(Agent.class)
    @ConditionalOnBean(AgentFactory.class)
    @ConditionalOnSingleCandidate(Workspace.class)
    public Agent agent(AgentFactory factory, Workspace workspace) {
        return factory.create(workspace);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
