// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.storage.memory;

import org.agentvirtualruntime.api.Artifact;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryWorkspaceTest {
    @Test
    void writesReadsAndCommitsArtifact() {
        MemoryWorkspace workspace = new MemoryWorkspace("test");
        workspace.writeText("/outputs/index.html", "<h1>Hello</h1>");
        workspace.writeText("/outputs/assets/app.css", "body{}");

        assertEquals("<h1>Hello</h1>", workspace.readText("/outputs/index.html"));

        Artifact artifact = workspace.commitArtifact("/outputs", "/index.html");
        assertEquals("/outputs/index.html", artifact.getEntrypoint());
        assertEquals(2, artifact.getFiles().size());
        assertTrue(artifact.getId().startsWith("art-"));
    }

    @Test
    void rejectsDirectoryTraversal() {
        MemoryWorkspace workspace = new MemoryWorkspace("test");
        assertThrows(IllegalArgumentException.class,
                () -> workspace.writeText("/outputs/../secret", "bad"));
    }
}

