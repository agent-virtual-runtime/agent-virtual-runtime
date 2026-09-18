package com.avr.model.openai;

import com.avr.api.Llm;
import com.avr.api.LlmResponse;
import com.avr.api.Message;
import com.avr.api.ToolCall;
import com.avr.api.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** 调用 OpenAI 兼容 {@code POST /chat/completions} 接口的模型客户端。 */
public final class OpenAiLlm implements Llm {
    private static final int MAX_ERROR_BODY_CHARS = 4_000;
    private static final TypeReference<LinkedHashMap<String, Object>> ARGUMENT_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>() { };

    private final OpenAiConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiLlm(OpenAiConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(config.getTimeout())
                .build(), new ObjectMapper());
    }

    OpenAiLlm(
            OpenAiConfig config,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public LlmResponse chat(
            List<Message> messages,
            List<ToolDefinition> tools) {
        return chat(messages, tools, delta -> {
        });
    }

    @Override
    public LlmResponse chat(
            List<Message> messages,
            List<ToolDefinition> tools,
            Consumer<String> onTextDelta) {
        Objects.requireNonNull(onTextDelta, "onTextDelta");
        try {
            String requestBody = objectMapper.writeValueAsString(
                    requestBody(messages, tools));
            HttpRequest request = request(requestBody);
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpFailure(response);
            }
            try (InputStream responseBody = response.body()) {
                // 兼容同一端点按配置返回 SSE 或普通 JSON 的两种响应形式。
                if (isEventStream(response)) {
                    return new OpenAiSseResponseParser(objectMapper)
                            .parse(responseBody, onTextDelta);
                }
                return parseResponse(readBody(responseBody));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException("model request was interrupted", exception);
        } catch (IOException exception) {
            throw new LlmException("model request failed: " + exception.getMessage(), exception);
        }
    }

    private ObjectNode requestBody(
            List<Message> messages,
            List<ToolDefinition> tools) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getModel());
        body.set("messages", messages(messages));
        if (!tools.isEmpty()) {
            body.set("tools", tools(tools));
            body.put("tool_choice", "auto");
        }
        if (config.getTemperature() != null) {
            body.put("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            body.put("max_tokens", config.getMaxTokens());
        }
        body.put("stream", config.isStream());
        return body;
    }

    private ArrayNode messages(List<Message> messages) {
        ArrayNode result = objectMapper.createArrayNode();
        for (Message message : messages) {
            ObjectNode value = result.addObject();
            value.put("role", role(message.getRole()));
            if (message.getRole() == Message.Role.TOOL) {
                value.put("content", message.getContent());
                value.put("tool_call_id", message.getToolCallId());
            } else if (message.getRole() == Message.Role.ASSISTANT
                    && !message.getToolCalls().isEmpty()) {
                if (message.getContent().isEmpty()) {
                    value.putNull("content");
                } else {
                    value.put("content", message.getContent());
                }
                value.set("tool_calls", toolCalls(message.getToolCalls()));
            } else {
                value.put("content", message.getContent());
            }
        }
        return result;
    }

    private ArrayNode toolCalls(List<ToolCall> calls) {
        ArrayNode result = objectMapper.createArrayNode();
        for (ToolCall call : calls) {
            ObjectNode value = result.addObject();
            value.put("id", call.getId());
            value.put("type", "function");
            ObjectNode function = value.putObject("function");
            function.put("name", call.getName());
            try {
                function.put("arguments", objectMapper.writeValueAsString(call.getArguments()));
            } catch (JsonProcessingException exception) {
                throw new LlmException(
                        "cannot serialize arguments for tool " + call.getName(), exception);
            }
        }
        return result;
    }

    private ArrayNode tools(List<ToolDefinition> tools) {
        ArrayNode result = objectMapper.createArrayNode();
        for (ToolDefinition definition : tools) {
            ObjectNode tool = result.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", definition.getName());
            function.put("description", definition.getDescription());
            try {
                function.set("parameters", objectMapper.readTree(definition.getInputSchema()));
            } catch (JsonProcessingException exception) {
                throw new LlmException(
                        "invalid input schema for tool " + definition.getName(), exception);
            }
        }
        return result;
    }

    private HttpRequest request(String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(config.getEndpoint())
                .timeout(config.getTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", config.isStream()
                        ? "text/event-stream"
                        : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (config.getApiKey() != null && !config.getApiKey().trim().isEmpty()) {
            request.header("Authorization", "Bearer " + config.getApiKey());
        }
        for (Map.Entry<String, String> header : config.getHeaders().entrySet()) {
            request.setHeader(header.getKey(), header.getValue());
        }
        return request.build();
    }

    private LlmResponse parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmException("model response does not contain choices");
            }
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").isTextual()
                    ? message.path("content").asText()
                    : "";
            List<ToolCall> calls = parseToolCalls(message.path("tool_calls"));
            String finishReason = choices.get(0).path("finish_reason").isTextual()
                    ? choices.get(0).path("finish_reason").asText()
                    : null;
            return LlmResponse.of(content, calls, finishReason);
        } catch (JsonProcessingException exception) {
            throw new LlmException("invalid model response JSON", exception);
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode values)
            throws JsonProcessingException {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        if (!values.isArray()) {
            return calls;
        }
        for (JsonNode value : values) {
            if (!"function".equals(value.path("type").asText())) {
                continue;
            }
            JsonNode function = value.path("function");
            String id = requiredText(value, "id");
            String name = requiredText(function, "name");
            String arguments = requiredText(function, "arguments");
            Map<String, Object> parsedArguments = objectMapper.readValue(
                    arguments, ARGUMENT_TYPE);
            calls.add(new ToolCall(id, name, parsedArguments));
        }
        return calls;
    }

    private LlmException httpFailure(HttpResponse<InputStream> response) {
        String body;
        try (InputStream responseBody = response.body()) {
            body = responseBody == null ? "" : readBody(responseBody);
        } catch (IOException exception) {
            body = "<failed to read error response>";
        }
        if (body.length() > MAX_ERROR_BODY_CHARS) {
            body = body.substring(0, MAX_ERROR_BODY_CHARS);
        }
        String requestId = response.headers().firstValue("x-request-id").orElse("unknown");
        return new LlmException(
                "model endpoint returned HTTP " + response.statusCode()
                        + ", requestId=" + requestId + ", body=" + body);
    }

    private static boolean isEventStream(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Content-Type")
                .map(value -> value.toLowerCase().contains("text/event-stream"))
                .orElse(false);
    }

    private static String readBody(InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode child = value.path(field);
        if (!child.isTextual() || child.asText().trim().isEmpty()) {
            throw new LlmException("model response is missing field: " + field);
        }
        return child.asText();
    }

    private static String role(Message.Role role) {
        return role.name().toLowerCase();
    }
}
