package com.avr.command;

import com.avr.api.Tool;
import com.avr.api.ToolCall;
import com.avr.api.ToolContext;
import com.avr.api.ToolDefinition;
import com.avr.api.ToolDisplayType;
import com.avr.api.ToolResult;

import java.net.URI;
import java.util.Objects;

/** 通过应用提供的受控 HTTP 客户端读取网页，不执行宿主机命令。 */
public final class HttpGetTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "http.get",
            "Fetch a URL through the application-controlled HTTP client. Argument: url",
            "{\"type\":\"object\",\"required\":[\"url\"],\"properties\":{\"url\":{\"type\":\"string\"}}}",
            "读取网页", ToolDisplayType.TEXT);

    private final VirtualHttpClient httpClient;

    public HttpGetTool(VirtualHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        try {
            String url = call.requireString("url");
            URI uri = URI.create(url);
            if ((!"https".equalsIgnoreCase(uri.getScheme())
                    && !"http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                return ToolResult.failure("ERROR: url must be an HTTP(S) URL without credentials");
            }
            String result = httpClient.get(url, context.getExecutionContext());
            return ToolResult.success(result, Math.toIntExact(result.lines().count()));
        } catch (RuntimeException exception) {
            return ToolResult.failure("ERROR: " + exception.getMessage());
        }
    }
}
