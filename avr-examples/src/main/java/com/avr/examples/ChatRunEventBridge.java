package com.avr.examples;

import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 将 Runtime 的全局监听事件按 runId 分发给当前 HTTP 请求。 */
@Component
public class ChatRunEventBridge implements RuntimeEventListener {
    private final ConcurrentHashMap<String, Consumer<RuntimeEvent>> subscribers =
            new ConcurrentHashMap<String, Consumer<RuntimeEvent>>();

    public void subscribe(String runId, Consumer<RuntimeEvent> subscriber) {
        if (subscribers.putIfAbsent(
                Objects.requireNonNull(runId, "runId"),
                Objects.requireNonNull(subscriber, "subscriber")) != null) {
            throw new IllegalStateException("run already has an event subscriber");
        }
    }

    public void unsubscribe(String runId) {
        subscribers.remove(runId);
    }

    @Override
    public void onEvent(RuntimeEvent event) {
        Consumer<RuntimeEvent> subscriber = subscribers.get(event.getRunId());
        if (subscriber != null) {
            subscriber.accept(event);
        }
    }
}
