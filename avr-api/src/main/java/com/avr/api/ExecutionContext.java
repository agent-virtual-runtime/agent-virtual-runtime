package com.avr.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 贯穿一次智能体运行的不可变类型安全上下文。 */
public final class ExecutionContext {
    private static final ExecutionContext EMPTY = new ExecutionContext(Collections.emptyMap());
    private final Map<ContextKey<?>, Object> values;

    private ExecutionContext(Map<ContextKey<?>, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<ContextKey<?>, Object>(values));
    }

    public static ExecutionContext empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 返回可能存在的上下文值。 */
    public <T> Optional<T> get(ContextKey<T> key) {
        Object value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(key.getType().cast(value));
    }

    /** 返回必需的上下文值，不存在时抛出异常。 */
    public <T> T require(ContextKey<T> key) {
        return get(key).orElseThrow(() -> new IllegalStateException("missing context: " + key.getName()));
    }

    /** 构建不可变执行上下文。 */
    public static final class Builder {
        private final Map<ContextKey<?>, Object> values = new LinkedHashMap<ContextKey<?>, Object>();

        public <T> Builder put(ContextKey<T> key, T value) {
            values.put(key, key.getType().cast(value));
            return this;
        }

        public ExecutionContext build() {
            return new ExecutionContext(values);
        }
    }
}
