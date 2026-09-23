package com.avr.command;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolDisplayType;
import com.avr.api.ToolResult;
import com.avr.api.WebSearchProvider;
import com.avr.api.WebSearchRequest;
import com.avr.api.WebSearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/** 将应用提供的搜索组件封装为标准 Function Tool。 */
public final class WebSearchTool implements Tool {
    public static final String NAME = "web.search";
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Search the public web for current information. Use this tool when the task "
                    + "requires facts that may have changed or sources from the internet. "
                    + "Arguments: query (specific search query), maxResults (optional, 1-10).",
            "{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"required\":[\"query\"],\"properties\":{"
                    + "\"query\":{\"type\":\"string\",\"minLength\":1},"
                    + "\"maxResults\":{\"type\":\"integer\",\"minimum\":1,"
                    + "\"maximum\":10}}}",
            "联网搜索",
            ToolDisplayType.TABLE);

    private final WebSearchProvider provider;
    private final ObjectMapper objectMapper;

    public WebSearchTool(WebSearchProvider provider) {
        this(provider, new ObjectMapper());
    }

    WebSearchTool(WebSearchProvider provider, ObjectMapper objectMapper) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        try {
            WebSearchRequest request = new WebSearchRequest(
                    call.requireString("query"), maxResults(call));
            WebSearchResult result = provider.search(
                    request, context.getExecutionContext());
            if (result == null) {
                return ToolResult.failure("ERROR: web search provider returned no result");
            }
            return ToolResult.success(
                    objectMapper.writeValueAsString(result),
                    result.getItems().size(),
                    ToolDisplayType.TABLE);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure("ERROR: " + exception.getMessage());
        } catch (JsonProcessingException exception) {
            return ToolResult.failure("ERROR: cannot serialize web search result");
        } catch (RuntimeException exception) {
            return ToolResult.failure("ERROR: web search failed: " + safeMessage(exception));
        }
    }

    private static int maxResults(ToolCall call) {
        Object value = call.getArguments().get("maxResults");
        if (value == null) {
            return DEFAULT_MAX_RESULTS;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("argument 'maxResults' must be an integer");
        }
        Number number = (Number) value;
        int result = number.intValue();
        if (number.doubleValue() != result) {
            throw new IllegalArgumentException("argument 'maxResults' must be an integer");
        }
        return result;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }
}
