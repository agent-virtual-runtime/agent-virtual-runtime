package com.avr.command;

import com.avr.api.ExecutionContext;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDisplayType;
import com.avr.api.ToolResult;
import com.avr.api.WebSearchItem;
import com.avr.api.WebSearchRequest;
import com.avr.api.WebSearchResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {
    @Test
    void exposesProviderResultsAsStructuredToolOutput() {
        WebSearchTool tool = new WebSearchTool((request, context) ->
                new WebSearchResult(request.getQuery(), Collections.singletonList(
                        new WebSearchItem("AVR", "https://example.com/avr", "runtime"))));
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("query", "agent virtual runtime");
        arguments.put("maxResults", 3);

        ToolResult result = tool.execute(
                new ToolCall("call-1", WebSearchTool.NAME, arguments),
                new ToolContext(new TestWorkspace(), ExecutionContext.empty(), "run-1"));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getRowCount());
        assertEquals(ToolDisplayType.TABLE, result.getDisplayType());
        assertTrue(result.getContent().contains("https://example.com/avr"));
    }

    @Test
    void rejectsInvalidResultLimitBeforeCallingProvider() {
        WebSearchTool tool = new WebSearchTool((request, context) -> {
            throw new AssertionError("provider must not be called");
        });
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("query", "avr");
        arguments.put("maxResults", 20);

        ToolResult result = tool.execute(
                new ToolCall("call-1", WebSearchTool.NAME, arguments),
                new ToolContext(new TestWorkspace(), ExecutionContext.empty(), "run-1"));

        assertTrue(!result.isSuccess());
        assertTrue(result.getContent().contains("between 1 and 10"));
    }

    private static final class TestWorkspace implements com.avr.api.Workspace {
        @Override public String id() { return "test"; }
        @Override public java.util.List<String> list(String directory) {
            return Collections.emptyList();
        }
        @Override public String readText(String path) { throw new UnsupportedOperationException(); }
        @Override public void writeText(String path, String content) { throw new UnsupportedOperationException(); }
        @Override public void delete(String path) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(String path) { return false; }
        @Override public com.avr.api.Artifact commitArtifact(String root, String entrypoint) {
            throw new UnsupportedOperationException();
        }
        @Override public java.util.List<com.avr.api.Artifact> artifacts() {
            return Collections.emptyList();
        }
    }
}
