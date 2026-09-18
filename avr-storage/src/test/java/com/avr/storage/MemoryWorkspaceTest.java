package com.avr.storage;

import com.avr.api.Artifact;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertThrows(IllegalArgumentException.class,
                () -> workspace.writeText("/.avr/metadata", "bad"));
    }

    @Test
    void artifactIsAnImmutableSnapshot() {
        MemoryWorkspace workspace = new MemoryWorkspace("test");
        workspace.writeText("/site/index.html", "version-one");

        Artifact artifact = workspace.commitArtifact("/site", "index.html");
        workspace.writeText("/site/index.html", "version-two");

        assertEquals("version-one", artifact.readText("/site/index.html"));
        assertEquals("version-two", workspace.readText("/site/index.html"));
    }

    @Test
    void copiesMovesAndDeletesFiles() {
        MemoryWorkspace workspace = new MemoryWorkspace("test");
        workspace.writeText("/a.txt", "content");
        workspace.copy("/a.txt", "/b.txt");
        workspace.move("/b.txt", "/c.txt");
        workspace.delete("/a.txt");

        assertFalse(workspace.exists("/a.txt"));
        assertFalse(workspace.exists("/b.txt"));
        assertEquals("content", workspace.readText("/c.txt"));
    }
}
