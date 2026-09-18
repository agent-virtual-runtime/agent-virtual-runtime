package com.avr.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单次模型调用返回的文本、工具调用和结束原因。 */
public final class LlmResponse {
    private final String text;
    private final List<ToolCall> toolCalls;
    private final String finishReason;

    private LlmResponse(
            String text,
            List<ToolCall> toolCalls,
            String finishReason) {
        this.text = text == null ? "" : text;
        this.toolCalls = Collections.unmodifiableList(
                new ArrayList<ToolCall>(toolCalls));
        this.finishReason = finishReason;
    }

    public static LlmResponse answer(String text) {
        return new LlmResponse(text, Collections.<ToolCall>emptyList(), null);
    }

    public static LlmResponse calls(List<ToolCall> calls) {
        return new LlmResponse("", calls, null);
    }

    public static LlmResponse of(String text, List<ToolCall> calls) {
        return new LlmResponse(text, calls, null);
    }

    public static LlmResponse of(
            String text,
            List<ToolCall> calls,
            String finishReason) {
        return new LlmResponse(text, calls, finishReason);
    }

    public String getText() {
        return text;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public boolean isTruncated() {
        return "length".equals(finishReason);
    }
}
