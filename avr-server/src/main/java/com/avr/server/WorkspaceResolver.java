package com.avr.server;

import com.avr.api.ExecutionContext;
import com.avr.api.Workspace;
import com.avr.api.WorkspaceProvider;

/** 将应用定义的标识解析为虚拟工作空间。 */
public interface WorkspaceResolver extends WorkspaceProvider {
    Workspace resolve(String workspaceId);

    @Override
    default Workspace resolve(String reference, ExecutionContext context) {
        return resolve(reference);
    }
}
