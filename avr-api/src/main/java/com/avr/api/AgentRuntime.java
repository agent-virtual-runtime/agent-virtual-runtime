package com.avr.api;

/** 通过模型与工具循环执行智能体请求。 */
public interface AgentRuntime {
    /** 同步执行请求并返回最终结果。 */
    AgentResult run(AgentRequest request);

    /** 使用默认执行器异步执行请求。 */
    default java.util.concurrent.CompletionStage<AgentResult> runAsync(AgentRequest request) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> run(request));
    }
}
