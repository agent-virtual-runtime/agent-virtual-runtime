package com.avr.core;

import com.avr.api.Message;
import com.avr.api.ToolDefinition;
import com.avr.api.Llm;
import com.avr.api.LlmResponse;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** 按预设顺序返回响应的确定性模型实现，主要用于测试和示例。 */
public final class ScriptedLlm implements Llm {
    private final Deque<LlmResponse> responses;

    public ScriptedLlm(List<LlmResponse> responses) {
        this.responses = new ArrayDeque<LlmResponse>(responses);
    }

    public static ScriptedLlm of(LlmResponse... responses) {
        return new ScriptedLlm(Arrays.asList(responses));
    }

    @Override
    public LlmResponse chat(
            List<Message> messages,
            List<ToolDefinition> tools) {
        LlmResponse response = responses.pollFirst();
        if (response == null) {
            throw new IllegalStateException("scripted model has no response left");
        }
        return response;
    }
}
