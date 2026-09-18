package com.avr.core;

import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.ToolCall;
import com.avr.api.LlmResponse;
import com.avr.api.Workspace;
import com.avr.core.tool.CommitArtifactTool;
import com.avr.core.tool.ReadFileTool;
import com.avr.core.tool.WriteFileTool;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopTest {
    @Test
    void executesToolLoopAndReturnsAnswer() {
        TestWorkspace workspace = new TestWorkspace();
        workspace.writeText("/inputs/data.txt", "revenue=120");

        DefaultToolRegistry registry = DefaultToolRegistry.builder()
                .register(new ReadFileTool())
                .register(new WriteFileTool())
                .register(new CommitArtifactTool())
                .build();

        ScriptedLlm model = ScriptedLlm.of(
                LlmResponse.calls(Collections.singletonList(call(
                        "1", "file.read", map("path", "/inputs/data.txt")))),
                LlmResponse.calls(Collections.singletonList(call(
                        "2", "file.write", map("path", "/outputs/index.html",
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
}
