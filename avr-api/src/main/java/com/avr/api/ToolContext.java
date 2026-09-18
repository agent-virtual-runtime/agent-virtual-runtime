package com.avr.api;

/** 工具执行时可访问的工作空间和业务上下文。 */
public final class ToolContext {
    private final Workspace workspace;
    private final ExecutionContext executionContext;

    public ToolContext(Workspace workspace) {
        this(workspace, ExecutionContext.empty());
    }

    public ToolContext(Workspace workspace, ExecutionContext executionContext) {
        this.workspace = workspace;
        this.executionContext = executionContext;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public ExecutionContext getExecutionContext() {
        return executionContext;
    }
}
