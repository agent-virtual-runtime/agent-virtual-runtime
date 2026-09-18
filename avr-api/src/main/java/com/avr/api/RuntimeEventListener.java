package com.avr.api;

/** 监听 Runtime 生命周期事件，不参与或改变 Agent 的执行过程。 */
@FunctionalInterface
public interface RuntimeEventListener {

    /** 消费一条不可变运行事件。 */
    void onEvent(RuntimeEvent event);
}
