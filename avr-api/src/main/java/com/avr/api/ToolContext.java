package com.avr.api;

import lombok.Getter;

/** 工具执行时可访问的工作空间和业务上下文。 */
@Getter
public final class ToolContext {
    private final Workspace workspace;
    private final ExecutionContext executionContext;
    private final String runId;

    public ToolContext(Workspace workspace) {
        this(workspace, ExecutionContext.empty());
    }

    public ToolContext(Workspace workspace, ExecutionContext executionContext) {
        this(workspace, executionContext, "");
    }

    public ToolContext(Workspace workspace, ExecutionContext executionContext, String runId) {
        this.workspace = workspace;
        this.executionContext = executionContext;
        this.runId = runId;
    }

}
