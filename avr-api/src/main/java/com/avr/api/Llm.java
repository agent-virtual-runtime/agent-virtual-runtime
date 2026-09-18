package com.avr.api;

import java.util.List;
import java.util.function.Consumer;

/** 描述一次大模型调用能力，不负责智能体循环。 */
public interface Llm {
    /** 生成最终回答或结构化工具调用。 */
    LlmResponse chat(List<Message> messages, List<ToolDefinition> tools);

    /** 生成响应，并在模型支持流式输出时回调文本增量。 */
    default LlmResponse chat(
            List<Message> messages,
            List<ToolDefinition> tools,
            Consumer<String> onTextDelta) {
        return chat(messages, tools);
    }
}
