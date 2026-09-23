package com.avr.storage;

import com.avr.api.Artifact;
import com.avr.api.WorkspaceEntry;
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

    @Test
    void appendsAndReplacesWithoutAmbiguousEdits() {
        MemoryWorkspace workspace = new MemoryWorkspace("test");
        workspace.appendText("/draft.md", "hello");
        workspace.appendText("/draft.md", " world");
        assertEquals(1, workspace.replaceText("/draft.md", "world", "AVR", false));
        assertEquals("hello AVR", workspace.readText("/draft.md"));
        workspace.writeText("/draft.md", "x x");
        assertThrows(IllegalArgumentException.class,
                () -> workspace.replaceText("/draft.md", "x", "y", false));
        assertEquals("x x", workspace.readText("/draft.md"));
        assertEquals(2, workspace.replaceText("/draft.md", "x", "y", true));
        assertEquals("y y", workspace.readText("/draft.md"));
    }

    @Test
    void managesDirectoriesAndLineBasedContent() {
        MemoryWorkspace workspace = new MemoryWorkspace("test");
        workspace.createDirectory("/workspace/reports/drafts");
        workspace.writeText("/workspace/reports/drafts/report.md", "one\ntwo\nthree");

        assertTrue(workspace.directoryExists("/workspace/reports/drafts"));
        assertEquals(1, workspace.entries("/workspace/reports/drafts").size());
        assertEquals(WorkspaceEntry.Type.FILE,
                workspace.entries("/workspace/reports/drafts").get(0).getType());
        assertEquals("   2| two\n   3| three\n",
                workspace.readLines("/workspace/reports/drafts/report.md", 2, 3, 100).getContent());
        assertEquals(2, workspace.searchText(
                "/workspace/reports/drafts/report.md", "t", false, 10).size());

        workspace.insertLines("/workspace/reports/drafts/report.md", 2, "inserted");
        workspace.replaceLines("/workspace/reports/drafts/report.md", 3, 3, "replaced");
        workspace.deleteLines("/workspace/reports/drafts/report.md", 1, 1);
        assertEquals("inserted\nreplaced\nthree",
                workspace.readText("/workspace/reports/drafts/report.md"));

        workspace.copyDirectory("/workspace/reports", "/workspace/archive", false);
        assertThrows(IllegalArgumentException.class, () -> workspace.copyDirectory(
                "/workspace/reports", "/workspace/reports/nested", false));
        workspace.moveDirectory("/workspace/archive", "/workspace/final", false);
        assertTrue(workspace.exists("/workspace/final/drafts/report.md"));
        workspace.deleteDirectory("/workspace/final", true);
        assertFalse(workspace.directoryExists("/workspace/final"));
    }
}
