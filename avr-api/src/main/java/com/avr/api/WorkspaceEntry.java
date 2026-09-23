package com.avr.api;

import lombok.Getter;

import java.util.Objects;

/** Workspace 中可浏览的文件或目录条目。 */
@Getter
public final class WorkspaceEntry {
    public enum Type {
        FILE,
        DIRECTORY
    }

    private final String path;
    private final Type type;
    private final long size;

    public WorkspaceEntry(String path, Type type, long size) {
        this.path = Objects.requireNonNull(path, "path");
        this.type = Objects.requireNonNull(type, "type");
        this.size = size;
    }
}
