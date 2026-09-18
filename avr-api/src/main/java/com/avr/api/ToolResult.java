package com.avr.api;

import java.util.Objects;

/** 一次工具调用的成功或失败结果。 */
public final class ToolResult {
    private final boolean success;
    private final String content;

    private ToolResult(boolean success, String content) {
        this.success = success;
        this.content = Objects.requireNonNull(content, "content");
    }

    public static ToolResult success(String content) {
        return new ToolResult(true, content);
    }

    public static ToolResult failure(String content) {
        return new ToolResult(false, content);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    }
}
