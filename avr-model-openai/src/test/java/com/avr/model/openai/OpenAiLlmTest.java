package com.avr.model.openai;

import com.avr.api.LlmResponse;
import com.avr.api.Message;
import com.avr.api.ToolCall;
import com.avr.api.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OpenAiLlmTest {
    @Test
    void resolvesBaseAndFullEndpointUrls() {
        assertEquals("https://example.com/v1/chat/completions",
                config("https://example.com/v1").getEndpoint().toString());
        assertEquals("https://example.com/chat/completions",
                config("https://example.com/chat/completions").getEndpoint().toString());
    }

    @Test
    void performsACompleteToolCallingRoundTrip() throws Exception {
        AtomicReference<String> latestRequest = new AtomicReference<String>();
        AtomicReference<String> authorization = new AtomicReference<String>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = createServer();
        if (server == null) {
            return;
        }
        server.createContext("/v1/chat/completions", exchange -> {
            latestRequest.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = requests.getAndIncrement() == 0
                    ? toolCallResponse()
                    : finalResponse();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            OpenAiLlm llm = new OpenAiLlm(
                    OpenAiConfig.builder()
                            .apiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .apiKey("secret")
                            .model("test-model")
                            .temperature(0.1)
                            .maxTokens(512)
                            .stream(false)
                            .timeout(Duration.ofSeconds(2))
                            .build());
            List<Message> messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("read file"));
            List<ToolDefinition> tools = Collections.singletonList(
                    new ToolDefinition("file.read", "Read a file",
                            "{\"type\":\"object\",\"required\":[\"path\"]}"));

            LlmResponse first = llm.chat(messages, tools);
            assertEquals(1, first.getToolCalls().size());
            ToolCall call = first.getToolCalls().get(0);
            assertEquals("file.read", call.getName());
            assertEquals("/input.txt", call.requireString("path"));

            messages.add(Message.assistant(first.getText(), first.getToolCalls()));
            messages.add(Message.tool(call.getId(), "hello"));
            LlmResponse second = llm.chat(messages, tools);

            assertEquals("finished", second.getText());
            assertEquals("Bearer secret", authorization.get());
            JsonNode request = new ObjectMapper().readTree(latestRequest.get());
            assertEquals("test-model", request.path("model").asText());
            assertTrue(request.path("messages").get(2).path("tool_calls").isArray());
            assertEquals("call-1",
                    request.path("messages").get(3).path("tool_call_id").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aggregatesStreamedTextAndToolCallDeltas() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = createServer();
        if (server == null) {
            return;
        }
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = streamedResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            OpenAiLlm llm = new OpenAiLlm(
                    OpenAiConfig.builder()
                            .apiUrl("http://127.0.0.1:"
                                    + server.getAddress().getPort() + "/v1")
                            .model("test-model")
                            .stream(true)
                            .timeout(Duration.ofSeconds(2))
                            .build());
            StringBuilder deltas = new StringBuilder();

            LlmResponse response = llm.chat(
                    Collections.singletonList(Message.user("read file")),
                    Collections.singletonList(new ToolDefinition(
                            "file.read",
                            "Read a file",
                            "{\"type\":\"object\"}")),
                    deltas::append);

            assertEquals("I will read it.", response.getText());
            assertEquals(response.getText(), deltas.toString());
            assertEquals("tool_calls", response.getFinishReason());
            assertEquals(1, response.getToolCalls().size());
            assertEquals("call-1", response.getToolCalls().get(0).getId());
            assertEquals("file.read", response.getToolCalls().get(0).getName());
            assertEquals("/input.txt",
                    response.getToolCalls().get(0).requireString("path"));
            assertTrue(new ObjectMapper().readTree(requestBody.get())
                    .path("stream").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer createServer() throws IOException {
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (SocketException exception) {
            assumeTrue(false, "environment does not permit binding a loopback socket");
            return null;
        }
    }

    private static OpenAiConfig config(String apiUrl) {
        return OpenAiConfig.builder()
                .apiUrl(apiUrl)
                .model("test")
                .build();
    }

    private static String toolCallResponse() {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"file.read\","
                + "\"arguments\":\"{\\\"path\\\":\\\"/input.txt\\\"}\"}}]}}]}";
    }

    private static String finalResponse() {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"finished\"}}]}";
    }

    private static String streamedResponse() {
        return ": keep-alive\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"I will \"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"read it.\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"file.\","
                + "\"arguments\":\"{\\\"path\\\":\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"name\":\"read\","
                + "\"arguments\":\"\\\"/input.txt\\\"}\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},"
                + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
    }
}
