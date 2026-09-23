package com.avr.core;

/** 子 Agent 任务生命周期状态。 */
public enum AgentTaskStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
