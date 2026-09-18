package com.avr.api;

/** 在执行前校验工具调用权限，拒绝时直接抛出异常。 */
public interface ToolPolicy {
    void authorize(ExecutionContext context, Workspace workspace, ToolCall call);

    /** 返回允许所有工具调用的策略。 */
    static ToolPolicy allowAll() {
        return (context, workspace, call) -> {
        };
    }
}
