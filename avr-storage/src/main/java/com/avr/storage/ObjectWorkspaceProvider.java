package com.avr.storage;

import com.avr.api.ExecutionContext;
import com.avr.api.Workspace;
import com.avr.api.WorkspaceProvider;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

/** 使用应用定义的 Key 前缀创建对象存储工作空间。 */
public final class ObjectWorkspaceProvider implements WorkspaceProvider {
    private final ObjectStore store;
    private final BiFunction<String, ExecutionContext, String> prefixResolver;
    private final ConcurrentMap<String, Workspace> workspaces =
            new ConcurrentHashMap<String, Workspace>();

    public ObjectWorkspaceProvider(
            ObjectStore store,
            BiFunction<String, ExecutionContext, String> prefixResolver) {
        this.store = Objects.requireNonNull(store, "store");
        this.prefixResolver = Objects.requireNonNull(prefixResolver, "prefixResolver");
    }

    @Override
    public Workspace resolve(String reference, ExecutionContext context) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("workspace reference must not be blank");
        }
        return workspaces.computeIfAbsent(reference,
                key -> new ObjectWorkspace(key, store, prefixResolver.apply(key, context)));
    }
}
