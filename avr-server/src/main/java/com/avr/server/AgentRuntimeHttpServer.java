package com.avr.server;

import com.avr.api.AgentRequest;
import com.avr.api.AgentResult;
import com.avr.api.AgentRuntime;
import com.avr.api.Artifact;
import com.avr.api.RunEvent;
import com.avr.api.Workspace;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 可嵌入的轻量 HTTP 服务，认证和租户隔离由接入应用负责。 */
public final class AgentRuntimeHttpServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private final AgentRuntime runtime;
    private final WorkspaceResolver workspaces;
    private final HttpServer server;
    private final RunEventHub events = new RunEventHub();

    public AgentRuntimeHttpServer(InetSocketAddress address, AgentRuntime runtime,
                                  WorkspaceResolver workspaces) throws IOException {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.server = HttpServer.create(address, 0);
        this.server.createContext("/health", this::health);
        this.server.createContext("/v1/runs", this::run);
        this.server.createContext("/v1/artifacts", this::artifact);
        this.server.setExecutor(Executors.newCachedThreadPool());
    }

    /** 启动 HTTP 服务。 */
    public void start() {
        server.start();
    }

    /** 返回服务实际监听的端口。 */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        sendJson(exchange, 200, "{\"status\":\"UP\"}");
    }

    private void run(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/events")) {
            try {
                streamEvents(exchange, runId(path));
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 404, error(exception));
            }
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        try {
            RunPayload payload = RunPayload.parse(
                    read(exchange.getRequestBody()),
                    exchange.getRequestHeaders().getFirst("Content-Type"));
            String workspaceId = payload.workspace != null
                    ? payload.workspace
                    : header(exchange, "X-AVR-Workspace", "default");
            AgentRequest.Builder request = AgentRequest.builder()
                    .agent(payload.agent)
                    .prompt(payload.prompt)
                    .maxSteps(payload.maxSteps)
                    .workspace(workspaces.resolve(workspaceId));
            if (path.endsWith("/async")) {
                startAsync(exchange, request);
                return;
            }
            AgentResult result = runtime.run(request.build());
            String response = "{\"runId\":\"" + json(result.getRunId())
                    + "\",\"state\":\"" + result.getState()
                    + "\",\"text\":\"" + json(result.getText())
                    + "\",\"artifacts\":" + result.getArtifacts().size() + "}";
            sendJson(exchange, 200, response);
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, error(exception));
        } catch (RuntimeException exception) {
            sendJson(exchange, 500, error(exception));
        }
    }

    private void startAsync(HttpExchange exchange, AgentRequest.Builder request)
            throws IOException {
        String runId = java.util.UUID.randomUUID().toString();
        events.open(runId);
        AgentRequest agentRequest = request
                .runId(runId)
                .observer(events::publish)
                .build();
        runtime.runAsync(agentRequest);
        sendJson(exchange, 202, "{\"runId\":\"" + runId
                + "\",\"state\":\"ACCEPTED\",\"events\":\"/v1/runs/"
                + runId + "/events\"}");
    }

    private void streamEvents(HttpExchange exchange, String runId) throws IOException {
        RunEventHub.Subscription subscription;
        try {
            subscription = events.subscribe(runId);
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 404, error(exception));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        try (RunEventHub.Subscription ignored = subscription;
             OutputStream output = exchange.getResponseBody()) {
            while (!subscription.isComplete()) {
                RunEvent event = subscription.poll(15, TimeUnit.SECONDS);
                if (event == null) {
                    writeSse(output, ": heartbeat\n\n");
                } else {
                    writeSse(output, sse(event));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端断开连接后，智能体任务仍在后台继续运行。
        }
    }

    private static String runId(String path) {
        String prefix = "/v1/runs/";
        String suffix = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
        int separator = suffix.indexOf('/');
        if (separator < 1) {
            throw new IllegalArgumentException("invalid run event path");
        }
        return suffix.substring(0, separator);
    }

    private static String sse(RunEvent event) {
        String data = "{\"runId\":\"" + json(event.getRunId())
                + "\",\"sequence\":" + event.getSequence()
                + ",\"state\":\"" + event.getState()
                + "\",\"type\":\"" + json(event.getType())
                + "\",\"detail\":\"" + json(event.getDetail()) + "\"}";
        return "id: " + event.getSequence() + "\nevent: " + event.getType()
                + "\ndata: " + data + "\n\n";
    }

    private static void writeSse(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void artifact(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        try {
            String suffix = exchange.getRequestURI().getRawPath()
                    .substring("/v1/artifacts/".length());
            int separator = suffix.indexOf('/');
            if (separator < 1 || separator == suffix.length() - 1) {
                throw new IllegalArgumentException("artifact path must contain an id and file path");
            }
            String artifactId = suffix.substring(0, separator);
            String relativePath = decodePath(suffix.substring(separator + 1));
            Workspace workspace = workspaces.resolve(
                    header(exchange, "X-AVR-Workspace", "default"));
            Artifact artifact = findArtifact(workspace, artifactId);
            send(exchange, 200, contentType(relativePath),
                    artifact.readText(artifact.getRoot() + relativePath));
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 404, error(exception));
        }
    }

    private static Artifact findArtifact(Workspace workspace, String id) {
        for (Artifact artifact : workspace.artifacts()) {
            if (artifact.getId().equals(id)) {
                return artifact;
            }
        }
        throw new IllegalArgumentException("artifact does not exist: " + id);
    }

    private static String decodePath(String rawPath) {
        String value = URI.create("http://localhost/" + rawPath).getPath().substring(1);
        if (value.isEmpty() || value.contains("..") || value.contains("\\")) {
            throw new IllegalArgumentException("invalid artifact path");
        }
        return value;
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "text/plain; charset=utf-8";
    }

    private static String header(HttpExchange exchange, String name, String fallback) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? fallback : value;
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("request body exceeds 1 MiB");
            }
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, String body)
            throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String error(Exception exception) {
        return "{\"error\":\"" + json(exception.getMessage()) + "\"}";
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static final class RunPayload {
        private final String prompt;
        private final String workspace;
        private final String agent;
        private final int maxSteps;

        private RunPayload(String prompt, String workspace, String agent, int maxSteps) {
            if (prompt == null || prompt.trim().isEmpty()) {
                throw new IllegalArgumentException("prompt must not be blank");
            }
            this.prompt = prompt;
            this.workspace = workspace;
            this.agent = agent == null ? "default" : agent;
            this.maxSteps = maxSteps;
        }

        private static RunPayload parse(String body, String contentType) {
            if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
                return new RunPayload(body, null, "default", 20);
            }
            return new RunPayload(
                    JsonFields.string(body, "prompt", true),
                    JsonFields.string(body, "workspace", false),
                    JsonFields.string(body, "agent", false),
                    JsonFields.integer(body, "maxSteps", 20));
        }
    }
}
