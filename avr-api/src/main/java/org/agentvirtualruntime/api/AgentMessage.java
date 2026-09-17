// SPDX-License-Identifier: Apache-2.0
package org.agentvirtualruntime.api;

import java.util.Objects;

public final class AgentMessage {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    private final Role role;
    private final String content;
    private final String toolCallId;

    private AgentMessage(Role role, String content, String toolCallId) {
        this.role = Objects.requireNonNull(role, "role");
        this.content = Objects.requireNonNull(content, "content");
        this.toolCallId = toolCallId;
    }

    public static AgentMessage system(String content) {
        return new AgentMessage(Role.SYSTEM, content, null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(Role.USER, content, null);
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage(Role.ASSISTANT, content, null);
    }

    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage(Role.TOOL, content, Objects.requireNonNull(toolCallId, "toolCallId"));
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getToolCallId() {
        return toolCallId;
    }
}

