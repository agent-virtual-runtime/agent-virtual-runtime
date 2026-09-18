package com.avr.api;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 模型上下文中的一条系统、用户、助手或工具消息。 */
@Getter
public final class Message {
    /** 消息在模型协议中的角色。 */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    private final Role role;
    private final String content;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;

    private Message(
            Role role,
            String content,
            String toolCallId,
            List<ToolCall> toolCalls) {
        this.role = Objects.requireNonNull(role, "role");
        this.content = Objects.requireNonNull(content, "content");
        this.toolCallId = toolCallId;
        this.toolCalls = Collections.unmodifiableList(
                new ArrayList<ToolCall>(toolCalls));
    }

    public static Message system(String content) {
        return new Message(
                Role.SYSTEM, content, null, Collections.<ToolCall>emptyList());
    }

    public static Message user(String content) {
        return new Message(
                Role.USER, content, null, Collections.<ToolCall>emptyList());
    }

    public static Message assistant(String content) {
        return assistant(content, Collections.<ToolCall>emptyList());
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, null, toolCalls);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message(
                Role.TOOL,
                content,
                Objects.requireNonNull(toolCallId, "toolCallId"),
                Collections.<ToolCall>emptyList());
    }

}
