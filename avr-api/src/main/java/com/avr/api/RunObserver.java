package com.avr.api;

/** 接收智能体运行过程中有序产生的生命周期事件。 */
public interface RunObserver {
    void onEvent(RunEvent event);

    /** 返回忽略所有事件的观察者。 */
    static RunObserver noop() {
        return event -> {
        };
    }
}
