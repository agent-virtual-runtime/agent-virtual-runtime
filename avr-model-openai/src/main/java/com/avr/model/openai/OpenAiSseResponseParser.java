package com.avr.model.openai;

import com.avr.api.LlmResponse;
import com.avr.api.ToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** 解析 OpenAI 兼容的 Chat Completions SSE 响应。 */
final class OpenAiSseResponseParser {
    private static final TypeReference<LinkedHashMap<String, Object>> ARGUMENT_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>() {
            };

    private final ObjectMapper objectMapper;
    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, PartialToolCall> toolCalls =
            new TreeMap<Integer, PartialToolCall>();
    private String finishReason;

    OpenAiSseResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    LlmResponse parse(InputStream input, Consumer<String> onTextDelta) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder eventData = new StringBuilder();
            String line;
            boolean done = false;
            while (!done && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    done = processEvent(eventData, onTextDelta);
                    eventData.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (eventData.length() > 0) {
                        eventData.append('\n');
                    }
                    String value = line.substring(5);
                    eventData.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
            if (!done && eventData.length() > 0) {
                processEvent(eventData, onTextDelta);
            }
        }
        return LlmResponse.of(
                text.toString(), completedToolCalls(), finishReason);
    }

    private boolean processEvent(
            StringBuilder eventData,
            Consumer<String> onTextDelta) {
        if (eventData.length() == 0) {
            return false;
        }
        String data = eventData.toString();
        if ("[DONE]".equals(data)) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (!choices.isArray()) {
                return false;
            }
            for (JsonNode choice : choices) {
                if (choice.path("finish_reason").isTextual()) {
                    finishReason = choice.path("finish_reason").asText();
                }
                JsonNode delta = choice.path("delta");
                if (!delta.isObject()) {
                    continue;
                }
                appendText(delta.path("content"), onTextDelta);
                appendToolCalls(delta.path("tool_calls"));
            }
            return false;
        } catch (JsonProcessingException exception) {
            throw new LlmException("invalid model SSE event JSON", exception);
        }
    }

    private void appendText(JsonNode content, Consumer<String> onTextDelta) {
        if (!content.isTextual() || content.asText().isEmpty()) {
            return;
        }
        String delta = content.asText();
        text.append(delta);
        onTextDelta.accept(delta);
    }

    private void appendToolCalls(JsonNode values) {
        if (!values.isArray()) {
            return;
        }
        // OpenAI 兼容协议可能把同一个工具调用拆成多个 delta，按 index 聚合后再解析参数。
        for (JsonNode value : values) {
            JsonNode indexNode = value.path("index");
            if (!indexNode.canConvertToInt()) {
                throw new LlmException("model SSE tool call is missing index");
            }
            int index = indexNode.asInt();
            PartialToolCall call = toolCalls.computeIfAbsent(
                    index, ignored -> new PartialToolCall());
            call.append(value);
        }
    }

    private List<ToolCall> completedToolCalls() {
        List<ToolCall> result = new ArrayList<ToolCall>();
        for (Map.Entry<Integer, PartialToolCall> entry : toolCalls.entrySet()) {
            result.add(entry.getValue().complete(entry.getKey()));
        }
        return result;
    }

    private final class PartialToolCall {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        private void append(JsonNode value) {
            if (value.path("id").isTextual()) {
                id = value.path("id").asText();
            }
            JsonNode function = value.path("function");
            if (function.path("name").isTextual()) {
                name.append(function.path("name").asText());
            }
            if (function.path("arguments").isTextual()) {
                arguments.append(function.path("arguments").asText());
            }
        }

        private ToolCall complete(int index) {
            if (id == null || id.trim().isEmpty()) {
                throw new LlmException("model SSE tool call " + index + " is missing id");
            }
            if (name.length() == 0) {
                throw new LlmException("model SSE tool call " + index + " is missing name");
            }
            String json = arguments.length() == 0 ? "{}" : arguments.toString();
            try {
                Map<String, Object> parsed = objectMapper.readValue(json, ARGUMENT_TYPE);
                return new ToolCall(id, name.toString(), parsed);
            } catch (JsonProcessingException exception) {
                throw new LlmException(
                        "invalid arguments for streamed tool " + name, exception);
            }
        }
    }
}
