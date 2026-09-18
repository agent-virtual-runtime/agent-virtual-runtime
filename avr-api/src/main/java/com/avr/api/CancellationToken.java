package com.avr.api;

/** 由智能体循环检查的只读取消信号。 */
public interface CancellationToken {
    boolean isCancelled();

    static CancellationToken none() {
        return () -> false;
    }
}
