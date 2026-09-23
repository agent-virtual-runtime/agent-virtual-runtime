package com.avr.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskWorkspaceTest {
    @TempDir
    Path directory;

    @Test
    void persistsFilesAndCommitsWebArtifact() {
        DiskWorkspace workspace = new DiskWorkspace("tenant-defined", directory);
        workspace.writeText("/site/A.html", "<link rel=stylesheet href=a.css>");
        workspace.writeText("/site/a.css", "body{}");
        assertEquals("body{}", workspace.readText("/site/a.css"));
        assertEquals("/site/A.html", workspace.commitArtifact("/site", "A.html").getEntrypoint());
        assertEquals(2, workspace.artifacts().get(0).getFiles().size());

        workspace.writeText("/site/A.html", "changed");

        DiskWorkspace restored = new DiskWorkspace("tenant-defined", directory);
        assertEquals(Arrays.asList("/site/A.html", "/site/a.css"), restored.list("/"));
        assertEquals(1, restored.artifacts().size());
        assertEquals("<link rel=stylesheet href=a.css>",
                restored.artifacts().get(0).readText("/site/A.html"));
    }

    @Test
    void rejectsTraversal() {
        DiskWorkspace workspace = new DiskWorkspace("w", directory);
        assertThrows(IllegalArgumentException.class, () -> workspace.writeText("/../secret", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.writeText("/.avr/metadata", "x"));
    }

    @Test
    void editsPersistAcrossWorkspaceInstances() {
        DiskWorkspace workspace = new DiskWorkspace("w", directory);
        workspace.appendText("/draft.txt", "a");
        workspace.appendText("/draft.txt", "b");
        workspace.replaceText("/draft.txt", "ab", "done", false);
        assertEquals("done", new DiskWorkspace("w", directory).readText("/draft.txt"));
    }

    @Test
    void streamsRangesAndManagesDirectoryLifecycle() {
        DiskWorkspace workspace = new DiskWorkspace("w", directory);
        workspace.createDirectory("/workspace/source/empty");
        workspace.writeText("/workspace/source/report.md", "alpha\nbeta\ngamma\ndelta");

        assertEquals("   2| beta\n   3| gamma\n",
                workspace.readLines("/workspace/source/report.md", 2, 3, 100).getContent());
        assertEquals(2, workspace.searchText(
                "/workspace/source/report.md", "ta", false, 10).size());

        workspace.copyDirectory("/workspace/source", "/workspace/copy", false);
        assertTrue(workspace.directoryExists("/workspace/copy/empty"));
        workspace.moveDirectory("/workspace/copy", "/workspace/moved", false);
        assertTrue(workspace.exists("/workspace/moved/report.md"));
        assertFalse(workspace.directoryExists("/workspace/copy"));
        workspace.deleteDirectory("/workspace/moved", true);
        assertFalse(workspace.directoryExists("/workspace/moved"));
    }
}
