package com.avr.command;

import com.avr.api.Artifact;
import com.avr.api.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VirtualCommandToolTest {
    @Test
    void runsWithoutHostProcess() {
        Workspace workspace = new TinyWorkspace();
        VirtualCommandTool commands = new VirtualCommandTool();
        assertEquals("hello world", commands.execute("cat /note.txt", workspace));
        assertEquals("hello world", commands.execute("grep world /note.txt", workspace));
        assertThrows(IllegalArgumentException.class,
                () -> commands.execute("curl https://example.com", workspace));
    }

    @Test
    void delegatesCurlToApplicationControlledClient() {
        Workspace workspace = new TinyWorkspace();
        VirtualCommandTool commands = new VirtualCommandTool(
                (url, context) -> "response from " + url);

        assertEquals("response from https://example.com",
                commands.execute("curl https://example.com", workspace));
    }

    private static final class TinyWorkspace implements Workspace {
        @Override
        public String id() {
            return "test";
        }

        @Override
        public java.util.List<String> list(String directory) {
            return Collections.singletonList("/note.txt");
        }

        @Override
        public String readText(String path) {
            return "hello world";
        }

        @Override
        public void writeText(String path, String content) {
        }

        @Override
        public void delete(String path) {
        }

        @Override
        public boolean exists(String path) {
            return true;
        }

        @Override
        public Artifact commitArtifact(String root, String entrypoint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<Artifact> artifacts() {
            return Collections.emptyList();
        }
    }
}
