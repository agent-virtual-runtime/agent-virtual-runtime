package com.avr.api;

/** 可由模型请求、由运行时执行的结构化工具。 */
public interface Tool {
    /** 返回提供给模型的工具定义。 */
    ToolDefinition definition();
    /** 执行一次已经完成授权检查的工具调用。 */
    ToolResult execute(ToolCall call, ToolContext context);

    /** 返回模型在同一轮请求多个工具时采用的执行模式。 */
    default ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }
}
