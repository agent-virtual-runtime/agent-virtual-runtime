package com.avr.api;

/** AVR 内置运行事件类型，应用仍可使用自定义类型。 */
public final class RuntimeEventTypes {
    public static final String RUN_CREATED = "run.created";
    public static final String RUN_PREPARING = "run.preparing";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String RUN_FAILED = "run.failed";
    public static final String RUN_CANCELLED = "run.cancelled";
    public static final String RUN_EXTENDED = "run.extended";
    public static final String RUN_TIMED_OUT = "run.timed_out";
    public static final String MODEL_CALL = "model.call";
    public static final String MODEL_COMPLETED = "model.completed";
    public static final String MODEL_DELTA = "model.delta";
    public static final String MODEL_REASONING_DELTA = "model.reasoning_delta";
    public static final String MODEL_EMPTY_RETRY = "model.empty_retry";
    public static final String MODEL_COMPLETION_REJECTED = "model.completion_rejected";
    public static final String TOOL_STARTED = "tool.started";
    public static final String TOOL_COMPLETED = "tool.completed";
    public static final String TOOL_FAILED = "tool.failed";

    private RuntimeEventTypes() {
    }
}
