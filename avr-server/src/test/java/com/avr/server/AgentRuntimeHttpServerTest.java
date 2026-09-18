package com.avr.server;

import com.avr.api.LlmResponse;
import com.avr.core.AgentLoop;
import com.avr.core.DefaultToolRegistry;
import com.avr.core.ScriptedLlm;
import com.avr.storage.MemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentRuntimeHttpServerTest {
    @Test
    void acceptsJsonRunsAndPreviewsArtifactFiles() throws Exception {
        MemoryWorkspace workspace = new MemoryWorkspace("web");
        workspace.writeText("/site/index.html", "<link rel=stylesheet href=app.css>");
        workspace.writeText("/site/app.css", "body{}");
        String artifactId = workspace.commitArtifact("/site", "index.html").getId();

        AgentRuntimeHttpServer created;
        try {
            created = new AgentRuntimeHttpServer(
                    new InetSocketAddress("127.0.0.1", 0),
                    new AgentLoop(
                            ScriptedLlm.of(
                                    LlmResponse.answer("done"),
                                    LlmResponse.answer("async done")),
                            DefaultToolRegistry.builder().build()),
                    ignored -> workspace);
        } catch (SocketException exception) {
            assumeTrue(false, "environment does not permit binding a loopback socket");
            return;
        }

        try (AgentRuntimeHttpServer server = created) {
            server.start();

            HttpResponse run = request(server.port(), "POST", "/v1/runs",
                    "application/json", "{\"prompt\":\"build site\",\"workspace\":\"web\"}");
            assertEquals(200, run.status);
            assertTrue(run.body.contains("\"state\":\"COMPLETED\""));

            HttpResponse css = request(server.port(), "GET",
                    "/v1/artifacts/" + artifactId + "/app.css", null, null);
            assertEquals(200, css.status);
            assertEquals("body{}", css.body);
            assertTrue(css.contentType.startsWith("text/css"));

            HttpResponse accepted = request(server.port(), "POST", "/v1/runs/async",
                    "application/json", "{\"prompt\":\"async report\"}");
            assertEquals(202, accepted.status);
            String runId = jsonString(accepted.body, "runId");
            HttpResponse events = request(server.port(), "GET",
                    "/v1/runs/" + runId + "/events", null, null);
            assertEquals(200, events.status);
            assertTrue(events.contentType.startsWith("text/event-stream"));
            assertTrue(events.body.contains("event: run.completed"));
        }
    }

    private static HttpResponse request(int port, String method, String path,
                                        String contentType, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("X-AVR-Workspace", "web");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        try (InputStream input = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream()) {
            return new HttpResponse(status, connection.getContentType(), read(input));
        }
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String jsonString(String json, String field) {
        String prefix = "\"" + field + "\":\"";
        int start = json.indexOf(prefix);
        if (start < 0) {
            throw new IllegalArgumentException("missing JSON field: " + field);
        }
        int valueStart = start + prefix.length();
        int end = json.indexOf('"', valueStart);
        return json.substring(valueStart, end);
    }

    private static final class HttpResponse {
        private final int status;
        private final String contentType;
        private final String body;

        private HttpResponse(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
