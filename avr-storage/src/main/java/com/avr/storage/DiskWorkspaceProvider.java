package com.avr.storage;

import com.avr.api.ExecutionContext;
import com.avr.api.Workspace;
import com.avr.api.WorkspaceProvider;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

/** 将不透明工作空间引用解析为应用指定的磁盘根目录。 */
public final class DiskWorkspaceProvider implements WorkspaceProvider {
    private final BiFunction<String, ExecutionContext, Path> rootResolver;
    private final ConcurrentMap<String, Workspace> workspaces =
            new ConcurrentHashMap<String, Workspace>();

    public DiskWorkspaceProvider(BiFunction<String, ExecutionContext, Path> rootResolver) {
        this.rootResolver = Objects.requireNonNull(rootResolver, "rootResolver");
    }

    @Override
    public Workspace resolve(String reference, ExecutionContext context) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("workspace reference must not be blank");
        }
        return workspaces.computeIfAbsent(reference,
                key -> new DiskWorkspace(key, rootResolver.apply(key, context)));
    }
}
