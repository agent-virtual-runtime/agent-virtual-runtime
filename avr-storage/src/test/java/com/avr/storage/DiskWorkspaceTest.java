package com.avr.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
