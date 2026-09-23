package com.avr.examples;

import com.avr.api.Artifact;
import com.avr.api.ExecutionContext;
import com.avr.core.WorkspaceSkillRegistry;
import com.avr.storage.DiskWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 示例中的隔离工作空间、Skill 加载和 Web Artifact 回归测试。 */
class ExampleWorkspaceTest {
    @TempDir
    Path testDirectory;

    @Test
    void keepsWorkspacesIsolatedAndPreservesArtifactAssets() {
        DiskWorkspace first = new DiskWorkspace("example-a", testDirectory.resolve("a"));
        DiskWorkspace second = new DiskWorkspace("example-b", testDirectory.resolve("b"));

        first.writeText("/skills/web-report/SKILL.md", "Create a small web report.");
        first.writeText("/site/index.html",
                "<link rel=\"stylesheet\" href=\"style.css\">"
                        + "<script src=\"app.js\"></script>");
        first.writeText("/site/style.css", "body { color: navy; }");
        first.writeText("/site/app.js", "document.title = 'AVR';");

        assertEquals("Create a small web report.",
                new WorkspaceSkillRegistry(first)
                        .find("web-report", ExecutionContext.empty())
                        .orElseThrow(AssertionError::new)
                        .getInstructions());
        assertFalse(second.exists("/site/index.html"));
        assertFalse(new WorkspaceSkillRegistry(second)
                .find("web-report", ExecutionContext.empty()).isPresent());

        Artifact artifact = first.commitArtifact("/site", "index.html");
        assertEquals("/site/index.html", artifact.getEntrypoint());
        assertEquals(3, artifact.getFiles().size());
        assertTrue(artifact.readText("/site/index.html").contains("style.css"));
        assertTrue(artifact.readText("/site/index.html").contains("app.js"));

        first.writeText("/site/style.css", "changed");
        DiskWorkspace reopened = new DiskWorkspace("example-a", testDirectory.resolve("a"));
        assertEquals("changed", reopened.readText("/site/style.css"));
        assertEquals("body { color: navy; }",
                reopened.artifacts().get(0).readText("/site/style.css"));
    }
}
