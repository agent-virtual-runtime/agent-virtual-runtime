package com.avr.storage;

import com.avr.api.ExecutionContext;
import com.avr.api.Workspace;
import com.avr.api.WorkspaceProvider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 按不透明引用延迟创建相互隔离的内存工作空间。 */
public final class MemoryWorkspaceProvider implements WorkspaceProvider {
    private final ConcurrentMap<String, Workspace> workspaces =
            new ConcurrentHashMap<String, Workspace>();

    @Override
    public Workspace resolve(String reference, ExecutionContext context) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("workspace reference must not be blank");
        }
        return workspaces.computeIfAbsent(reference, MemoryWorkspace::new);
    }
}
