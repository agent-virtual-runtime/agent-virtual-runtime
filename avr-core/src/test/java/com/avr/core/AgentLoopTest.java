package com.avr.core;

import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventTypes;
import com.avr.api.ToolCall;
import com.avr.api.Tool;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolResult;
import com.avr.api.LlmResponse;
import com.avr.api.Llm;
import com.avr.api.Message;
import com.avr.api.Workspace;
import com.avr.core.tool.CommitArtifactTool;
import com.avr.core.tool.FileOpTool;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    @Test
    void executesToolLoopAndReturnsAnswer() {
        TestWorkspace workspace = new TestWorkspace();
        workspace.writeText("/inputs/data.txt", "revenue=120");

        DefaultToolRegistry registry = DefaultToolRegistry.builder()
                .register(new FileOpTool())
                .register(new CommitArtifactTool())
                .build();

        ScriptedLlm model = ScriptedLlm.of(
                LlmResponse.calls(Collections.singletonList(call(
                        "1", "file.op", map("op", "read", "path", "/inputs/data.txt")))),
                LlmResponse.calls(Collections.singletonList(call(
                        "2", "file.op", map("op", "write", "path", "/outputs/index.html",
                                "content", "<h1>Revenue 120</h1>")))),
                LlmResponse.calls(Collections.singletonList(call(
                        "3", "artifact.commit", map("root", "/outputs",
                                "entrypoint", "/index.html")))),
                LlmResponse.answer("Report completed"));

        AgentResult result = new AgentLoop(model, registry).run(
                AgentRequest.builder()
                        .prompt("create report")
                        .workspace(workspace)
                        .build());

        assertEquals("Report completed", result.getText());
        assertEquals("<h1>Revenue 120</h1>", workspace.readText("/outputs/index.html"));
        assertEquals(1, result.getArtifacts().size());
    }

    @Test
    void extendsSoftStepLimitWhileAgentMakesProgress() {
        TestWorkspace workspace = new TestWorkspace();
        DefaultToolRegistry registry = DefaultToolRegistry.builder()
                .register(new FileOpTool())
                .build();
        List<RuntimeEvent> events = new ArrayList<RuntimeEvent>();
        ScriptedLlm model = ScriptedLlm.of(
                LlmResponse.calls(Collections.singletonList(call(
                        "1", "file.op", map("op", "write", "path", "/report/one.txt", "content", "one")))),
                LlmResponse.calls(Collections.singletonList(call(
                        "2", "file.op", map("op", "write", "path", "/report/two.txt", "content", "two")))),
                LlmResponse.calls(Collections.singletonList(call(
                        "3", "file.op", map("op", "write", "path", "/report/three.txt", "content", "three")))),
                LlmResponse.answer("finished after extension"));

        AgentResult result = new AgentLoop(
                model,
                registry,
                com.avr.api.ToolPolicy.allowAll(),
                AgentLoopOptions.builder().maxStepMultiplier(3).build(),
                Collections.singletonList(events::add))
                .run(AgentRequest.builder()
                        .prompt("write a multi-part report")
                        .workspace(workspace)
                        .maxSteps(2)
                        .build());

        assertEquals("finished after extension", result.getText());
        assertEquals(4, result.getSteps());
        assertTrue(events.stream().anyMatch(event ->
                RuntimeEventTypes.RUN_EXTENDED.equals(event.getType())));
    }

    @Test
    void stopsRepeatedSuccessfulToolRoundsAsNoProgress() {
        TestWorkspace workspace = new TestWorkspace();
        workspace.writeText("/inputs/data.txt", "same result");
        DefaultToolRegistry registry = DefaultToolRegistry.builder()
                .register(new FileOpTool())
                .build();
        ToolCall repeated = call(
                "read", "file.op", map("op", "read", "path", "/inputs/data.txt"));
        ScriptedLlm model = ScriptedLlm.of(
                LlmResponse.calls(Collections.singletonList(repeated)),
                LlmResponse.calls(Collections.singletonList(repeated)),
                LlmResponse.calls(Collections.singletonList(repeated)));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new AgentLoop(
                        model,
                        registry,
                        com.avr.api.ToolPolicy.allowAll(),
                        AgentLoopOptions.builder()
                                .maxNoProgressRounds(2)
                                .maxStepMultiplier(5)
                                .build())
                        .run(AgentRequest.builder()
                                .prompt("keep reading")
                                .workspace(workspace)
                                .maxSteps(2)
                                .build()));

        assertTrue(error.getMessage().contains("made no progress"));
    }

    @Test
    void exposesRuntimeSearchOnlyForRequestsThatEnableIt() {
        CapturingLlm enabledModel = new CapturingLlm();
        DefaultToolRegistry registry = DefaultToolRegistry.builder()
                .register(new SearchTool())
                .build();

        new AgentLoop(enabledModel, registry).run(AgentRequest.builder()
                .prompt("latest news")
                .workspace(new TestWorkspace())
                .webSearch(true)
                .build());

        assertTrue(enabledModel.toolNames.contains("web.search"));

        CapturingLlm disabledModel = new CapturingLlm();
        new AgentLoop(disabledModel, registry).run(AgentRequest.builder()
                .prompt("offline task")
                .workspace(new TestWorkspace())
                .build());

        assertTrue(!disabledModel.toolNames.contains("web.search"));
    }

    @Test
    void failsBeforeModelCallWhenRuntimeSearchProviderIsMissing() {
        CapturingLlm model = new CapturingLlm();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new AgentLoop(model, DefaultToolRegistry.builder().build())
                        .run(AgentRequest.builder()
                                .prompt("latest news")
                                .workspace(new TestWorkspace())
                                .webSearch(true)
                                .build()));

        assertTrue(error.getMessage().contains("no web.search tool"));
        assertTrue(model.toolNames.isEmpty());
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, name, args);
    }

    private static Map<String, Object> map(String... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private static final class TestWorkspace implements Workspace {
        private final Map<String, String> files = new LinkedHashMap<String, String>();
        private final java.util.List<com.avr.api.Artifact> artifacts =
                new java.util.ArrayList<com.avr.api.Artifact>();

        @Override
        public String id() {
            return "test";
        }

        @Override
        public java.util.List<String> list(String directory) {
            java.util.List<String> result = new java.util.ArrayList<String>();
            for (String path : files.keySet()) {
                if (path.startsWith(directory)) {
                    result.add(path);
                }
            }
            return result;
        }

        @Override
        public String readText(String path) {
            return files.get(path);
        }

        @Override
        public void writeText(String path, String content) {
            files.put(path, content);
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }

        @Override
        public boolean exists(String path) {
            return files.containsKey(path);
        }

        @Override
        public com.avr.api.Artifact commitArtifact(String root, String entrypoint) {
            com.avr.api.Artifact artifact = new com.avr.api.Artifact(
                    "art-test", root, root + entrypoint, list(root));
            artifacts.add(artifact);
            return artifact;
        }

        @Override
        public java.util.List<com.avr.api.Artifact> artifacts() {
            return artifacts;
        }
    }

    private static final class CapturingLlm implements Llm {
        private final List<String> toolNames = new ArrayList<String>();

        @Override
        public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            for (ToolDefinition tool : tools) {
                toolNames.add(tool.getName());
            }
            return LlmResponse.answer("done");
        }
    }

    private static final class SearchTool implements Tool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("web.search", "Search the web");
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            return ToolResult.success("[]");
        }
    }
}
