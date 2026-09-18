package com.avr.api;

import java.util.concurrent.atomic.AtomicBoolean;

/** 线程安全的取消信号源，持有并控制运行令牌。 */
public final class CancellationSource {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellationToken token = cancelled::get;

    public CancellationToken token() {
        return token;
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }
}
