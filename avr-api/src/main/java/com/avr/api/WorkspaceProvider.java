package com.avr.api;

/** 将不透明引用解析为工作空间，不限定任何租户或业务模型。 */
public interface WorkspaceProvider {
    Workspace resolve(String reference, ExecutionContext context);
}
