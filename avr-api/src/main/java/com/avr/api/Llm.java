package com.avr.api;

import java.util.List;
import java.util.function.Consumer;

/** 描述一次大模型调用能力，不负责智能体循环。 */
public interface Llm {
    /** 当前协议适配器是否真正支持模型服务原生托管搜索。 */
    default boolean supportsNativeWebSearch() {
        return false;
    }

    /** 生成最终回答或结构化工具调用。 */
    LlmResponse chat(List<Message> messages, List<ToolDefinition> tools);

    /** 生成响应，并在模型支持流式输出时回调文本增量。 */
    default LlmResponse chat(
            List<Message> messages,
            List<ToolDefinition> tools,
            Consumer<String> onTextDelta) {
        return chat(messages, tools);
    }

    /** 使用请求级模型覆盖；不支持动态模型的实现可忽略 model。 */
    default LlmResponse chat(
            String model,
            List<Message> messages,
            List<ToolDefinition> tools,
            Consumer<String> onTextDelta) {
        return chat(messages, tools, onTextDelta);
    }

    /** 可选的思考增量通道；不支持该通道的模型实现仍可只实现文本回调。 */
    default LlmResponse chat(
            String model,
            List<Message> messages,
            List<ToolDefinition> tools,
            Consumer<String> onTextDelta,
            Consumer<String> onReasoningDelta) {
        return chat(model, messages, tools, onTextDelta);
    }

    /** 可选启用模型服务原生托管搜索；不支持时应明确拒绝，不能伪造 Function Tool。 */
    default LlmResponse chat(
            String model,
            List<Message> messages,
            List<ToolDefinition> tools,
            boolean webSearch,
            Consumer<String> onTextDelta,
            Consumer<String> onReasoningDelta) {
        return chat(model, messages, tools, onTextDelta, onReasoningDelta);
    }
}
