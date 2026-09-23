package com.avr.command;

import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpGetToolTest {
    @Test
    void delegatesToApplicationControlledClient() {
        AtomicInteger requests = new AtomicInteger();
        HttpGetTool tool = new HttpGetTool((url, context) -> {
            requests.incrementAndGet();
            return "response from " + url;
        });

        assertEquals("http.get", tool.definition().getName());
        assertEquals("response from https://example.com",
                tool.execute(call("https://example.com"), context()).getContent());
        assertEquals(1, requests.get());
    }

    @Test
    void rejectsNonHttpAndEmbeddedCredentials() {
        AtomicInteger requests = new AtomicInteger();
        HttpGetTool tool = new HttpGetTool((url, context) -> {
            requests.incrementAndGet();
            return "should not run";
        });

        assertFalse(tool.execute(call("file:///etc/passwd"), context()).isSuccess());
        assertFalse(tool.execute(call("https://user:pass@example.com"), context()).isSuccess());
        assertEquals(0, requests.get());
    }

    private static ToolCall call(String url) {
        return new ToolCall("test-call", "http.get", Map.of("url", url));
    }

    private static ToolContext context() {
        return new ToolContext(new MemoryWorkspace("test"));
    }
}
