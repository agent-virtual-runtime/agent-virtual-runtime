package com.avr.api;

import lombok.Getter;

import java.util.Objects;

/** 用于在 {@link ExecutionContext} 中安全存取值的类型化键。 */
@Getter
public final class ContextKey<T> {
    private final String name;
    private final Class<T> type;

    private ContextKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    public static <T> ContextKey<T> of(String name, Class<T> type) {
        return new ContextKey<T>(name, type);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContextKey && name.equals(((ContextKey<?>) other).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
