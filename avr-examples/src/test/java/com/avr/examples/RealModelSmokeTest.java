package com.avr.examples;

import com.avr.api.Agent;
import com.avr.api.AgentResult;
import com.avr.api.Artifact;
import com.avr.api.ExecutionContext;
import com.avr.api.RunState;
import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventTypes;
import com.avr.api.ToolPolicy;
import com.avr.core.AgentLoop;
import com.avr.core.AgentLoopOptions;
import com.avr.core.DefaultToolRegistry;
import com.avr.core.WorkspaceSkillRegistry;
import com.avr.core.tool.CommitArtifactTool;
import com.avr.core.tool.FileOpTool;
import com.avr.model.openai.OpenAiConfig;
import com.avr.model.openai.OpenAiLlm;
import com.avr.storage.DiskWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 显式启用时才调用真实的 OpenAI 兼容接口，避免普通构建产生费用。 */
class RealModelSmokeTest {
    @Test
    void streamsToolCallsAndCommitsAWebArtifact() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("AVR_RUN_LIVE_TESTS")),
                "Set AVR_RUN_LIVE_TESTS=true to enable live model requests");
        assertTrue(hasText(System.getenv("OPENAI_API_KEY")),
                "Set OPENAI_API_KEY to a provider API key");

        Path baseDirectory = Paths.get(System.getenv().getOrDefault(
                "AVR_SMOKE_WORKSPACE_DIR", "target/real-model-smoke"));
        Path workspaceDirectory = baseDirectory.resolve(UUID.randomUUID().toString())
                .toAbsolutePath().normalize();
        DiskWorkspace workspace = new DiskWorkspace("real-model-smoke", workspaceDirectory);
        workspace.writeText("/inputs/brief.txt", "Project: AVR. Test marker: BLUE-42.");
        workspace.writeText("/skills/web-report/SKILL.md", String.join("\n",
                "Read /inputs/brief.txt before writing the report.",
                "Use file.op with op=write to create /site/index.html, /site/style.css and /site/app.js.",
                "index.html must use relative links to style.css and app.js.",
                "Include the exact test marker from the input in index.html.",
                "Call artifact.commit with root=/site and entrypoint=index.html.",
                "Only claim completion after the artifact is committed."));

        OpenAiConfig config = OpenAiConfig.fromYaml();
        assertTrue(config.isStream(), "The smoke test must exercise SSE");
        List<RuntimeEvent> events = new CopyOnWriteArrayList<RuntimeEvent>();
        AgentLoop loop = new AgentLoop(new OpenAiLlm(config),
                DefaultToolRegistry.builder()
                        .register(new FileOpTool())
                        .register(new CommitArtifactTool())
                        .build(),
                ToolPolicy.allowAll(), AgentLoopOptions.defaults(),
                Collections.singletonList(events::add));
        Agent agent = Agent.builder()
                .name("real-model-smoke")
                .runtime(loop)
                .workspace(workspace)
                .skill(new WorkspaceSkillRegistry(workspace)
                        .find("web-report", ExecutionContext.empty())
                        .orElseThrow(AssertionError::new))
                .maxSteps(30)
                .build();

        System.out.println("Live model: " + config.getModel());
        System.out.println("Live endpoint: " + config.getEndpoint());
        System.out.println("Test workspace: " + workspaceDirectory);
        AgentResult result = agent.input("Create the small web report from the virtual input file.");

        assertEquals(RunState.COMPLETED, result.getState());
        assertTrue(workspace.exists("/site/index.html"));
        assertTrue(workspace.readText("/site/index.html").contains("BLUE-42"));
        assertTrue(workspace.readText("/site/index.html").contains("style.css"));
        assertTrue(workspace.readText("/site/index.html").contains("app.js"));
        assertTrue(workspace.exists("/site/style.css"));
        assertTrue(workspace.exists("/site/app.js"));
        assertFalse(result.getArtifacts().isEmpty());
        Artifact artifact = result.getArtifacts().get(0);
        assertEquals("/site/index.html", artifact.getEntrypoint());
        assertEquals(3, artifact.getFiles().size());
        assertTrue(hasEvent(events, RuntimeEventTypes.MODEL_DELTA));
        assertTrue(hasEvent(events, RuntimeEventTypes.TOOL_COMPLETED));
        assertTrue(hasEvent(events, RuntimeEventTypes.RUN_COMPLETED));
        System.out.println("Artifact ID: " + artifact.getId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean hasEvent(List<RuntimeEvent> events, String type) {
        return events.stream().anyMatch(event -> type.equals(event.getType()));
    }
}
