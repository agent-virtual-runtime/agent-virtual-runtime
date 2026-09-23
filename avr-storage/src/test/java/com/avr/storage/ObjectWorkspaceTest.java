package com.avr.storage;

import com.avr.api.Artifact;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectWorkspaceTest {
    @Test
    void storesFilesUnderAnApplicationDefinedPrefix() {
        MemoryObjectStore store = new MemoryObjectStore();
        ObjectWorkspace workspace = new ObjectWorkspace("opaque", store, "business/work-1");
        workspace.writeText("/site/index.html", "first");
        workspace.writeText("/site/app.js", "run()");
        workspace.appendText("/site/app.js", ";ready()");
        assertEquals(1, workspace.replaceText(
                "/site/app.js", "ready()", "start()", false));
        assertEquals("run();start()", workspace.readText("/site/app.js"));

        Artifact artifact = workspace.commitArtifact("/site", "index.html");
        workspace.writeText("/site/index.html", "second");
        workspace.move("/site/app.js", "/site/main.js");

        assertEquals("first", artifact.readText("/site/index.html"));
        assertEquals("second", workspace.readText("/site/index.html"));
        assertFalse(workspace.exists("/site/app.js"));
        assertEquals(Arrays.asList("/site/index.html", "/site/main.js"), workspace.list("/"));

        ObjectWorkspace restored = new ObjectWorkspace("opaque", store, "business/work-1");
        assertEquals(1, restored.artifacts().size());
        assertEquals("first", restored.artifacts().get(0).readText("/site/index.html"));
        assertThrows(IllegalArgumentException.class,
                () -> restored.writeText("/.avr/metadata", "x"));
    }

    @Test
    void keepsEmptyDirectoriesAndSupportsMerge() {
        MemoryObjectStore store = new MemoryObjectStore();
        ObjectWorkspace workspace = new ObjectWorkspace("opaque", store, "tenant/session");
        workspace.createDirectory("/workspace/first/empty");
        workspace.writeText("/workspace/first/a.txt", "a");
        workspace.createDirectory("/workspace/second");
        workspace.writeText("/workspace/second/b.txt", "b");

        workspace.copyDirectory("/workspace/first", "/workspace/second", true);
        assertTrue(workspace.exists("/workspace/second/a.txt"));
        assertTrue(workspace.directoryExists("/workspace/second/empty"));
        assertThrows(IllegalStateException.class, () -> workspace.deleteDirectory(
                "/workspace/second", false));
        workspace.deleteDirectory("/workspace/first", true);
        assertFalse(workspace.directoryExists("/workspace/first"));
    }

    private static final class MemoryObjectStore implements ObjectStore {
        private final Map<String, byte[]> objects = new LinkedHashMap<String, byte[]>();

        @Override
        public Optional<byte[]> get(String key) {
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public void put(String key, byte[] value) {
            objects.put(key, value);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public List<String> list(String prefix) {
            List<String> result = new ArrayList<String>();
            for (String key : objects.keySet()) {
                if (key.startsWith(prefix)) {
                    result.add(key);
                }
            }
            Collections.sort(result);
            return result;
        }
    }
}
