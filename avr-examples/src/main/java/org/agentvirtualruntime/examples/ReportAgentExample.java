// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.examples;

import org.agentvirtualruntime.api.AgentRequest;
import org.agentvirtualruntime.api.AgentResult;
import org.agentvirtualruntime.api.CapabilityCall;
import org.agentvirtualruntime.api.ModelResponse;
import org.agentvirtualruntime.core.DefaultAgentRuntime;
import org.agentvirtualruntime.core.DefaultCapabilityRegistry;
import org.agentvirtualruntime.core.ScriptedModelGateway;
import org.agentvirtualruntime.core.capability.CommitArtifactCapability;
import org.agentvirtualruntime.core.capability.ReadFileCapability;
import org.agentvirtualruntime.core.capability.WriteFileCapability;
import org.agentvirtualruntime.storage.memory.MemoryWorkspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReportAgentExample {
    private ReportAgentExample() {}

    public static void main(String[] args) {
        MemoryWorkspace workspace = new MemoryWorkspace("report-demo");
        workspace.writeText("/inputs/data.txt", "revenue=120; growth=23%");

        ScriptedModelGateway model = ScriptedModelGateway.of(
                call("1", "file.read", args("path", "/inputs/data.txt")),
                call("2", "file.write", args(
                        "path", "/outputs/index.html",
                        "content", "<html><body><h1>Revenue: 120</h1><p>Growth: 23%</p></body></html>")),
                call("3", "artifact.commit", args(
                        "root", "/outputs", "entrypoint", "/index.html")),
                ModelResponse.answer("Report generated successfully."));

        DefaultCapabilityRegistry capabilities = DefaultCapabilityRegistry.builder()
                .register(new ReadFileCapability())
                .register(new WriteFileCapability())
                .register(new CommitArtifactCapability())
                .build();

        AgentResult result = new DefaultAgentRuntime(model, capabilities).run(
                AgentRequest.builder()
                        .workspace(workspace)
                        .prompt("Read the input data and create an HTML report")
                        .build());

        System.out.println(result.getText());
        System.out.println("Artifacts: " + result.getArtifacts().size());
        System.out.println(workspace.readText("/outputs/index.html"));
    }

    private static ModelResponse call(String id, String name, Map<String, Object> arguments) {
        return ModelResponse.calls(Collections.singletonList(
                new CapabilityCall(id, name, arguments)));
    }

    private static Map<String, Object> args(String... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }
}
