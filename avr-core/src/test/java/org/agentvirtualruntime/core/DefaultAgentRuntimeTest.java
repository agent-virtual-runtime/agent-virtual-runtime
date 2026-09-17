// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.core;

import org.agentvirtualruntime.api.AgentRequest;
import org.agentvirtualruntime.api.AgentResult;
import org.agentvirtualruntime.api.CapabilityCall;
import org.agentvirtualruntime.api.ModelResponse;
import org.agentvirtualruntime.api.Workspace;
import org.agentvirtualruntime.core.capability.CommitArtifactCapability;
import org.agentvirtualruntime.core.capability.ReadFileCapability;
import org.agentvirtualruntime.core.capability.WriteFileCapability;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultAgentRuntimeTest {
    @Test
    void executesToolLoopAndReturnsAnswer() {
        TestWorkspace workspace = new TestWorkspace();
        workspace.writeText("/inputs/data.txt", "revenue=120");

        DefaultCapabilityRegistry registry = DefaultCapabilityRegistry.builder()
                .register(new ReadFileCapability())
                .register(new WriteFileCapability())
                .register(new CommitArtifactCapability())
                .build();

        ScriptedModelGateway model = ScriptedModelGateway.of(
                ModelResponse.calls(Collections.singletonList(call(
                        "1", "file.read", map("path", "/inputs/data.txt")))),
                ModelResponse.calls(Collections.singletonList(call(
                        "2", "file.write", map("path", "/outputs/index.html",
                                "content", "<h1>Revenue 120</h1>")))),
                ModelResponse.calls(Collections.singletonList(call(
                        "3", "artifact.commit", map("root", "/outputs",
                                "entrypoint", "/index.html")))),
                ModelResponse.answer("Report completed"));

        AgentResult result = new DefaultAgentRuntime(model, registry).run(
                AgentRequest.builder()
                        .prompt("create report")
                        .workspace(workspace)
                        .build());

        assertEquals("Report completed", result.getText());
        assertEquals("<h1>Revenue 120</h1>", workspace.readText("/outputs/index.html"));
        assertEquals(1, result.getArtifacts().size());
    }

    private static CapabilityCall call(String id, String name, Map<String, Object> args) {
        return new CapabilityCall(id, name, args);
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
        private final java.util.List<org.agentvirtualruntime.api.Artifact> artifacts =
                new java.util.ArrayList<org.agentvirtualruntime.api.Artifact>();

        @Override public String id() { return "test"; }
        @Override public java.util.List<String> list(String directory) {
            java.util.List<String> result = new java.util.ArrayList<String>();
            for (String path : files.keySet()) if (path.startsWith(directory)) result.add(path);
            return result;
        }
        @Override public String readText(String path) { return files.get(path); }
        @Override public void writeText(String path, String content) { files.put(path, content); }
        @Override public boolean exists(String path) { return files.containsKey(path); }
        @Override public org.agentvirtualruntime.api.Artifact commitArtifact(String root, String entrypoint) {
            org.agentvirtualruntime.api.Artifact artifact = new org.agentvirtualruntime.api.Artifact(
                    "art-test", root, root + entrypoint, list(root));
            artifacts.add(artifact);
            return artifact;
        }
        @Override public java.util.List<org.agentvirtualruntime.api.Artifact> artifacts() { return artifacts; }
    }
}

