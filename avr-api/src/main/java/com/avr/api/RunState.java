package com.avr.api;

/** 智能体运行的生命周期状态。 */
public enum RunState {
    CREATED,
    PREPARING,
    CALLING_MODEL,
    EXECUTING_TOOLS,
    WAITING_APPROVAL,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    /** 判断当前状态是否为不可继续执行的终态。 */
    public boolean isTerminal() {
        return this == COMPLETED
                || this == FAILED
                || this == CANCELLED
                || this == TIMED_OUT;
    }
}
