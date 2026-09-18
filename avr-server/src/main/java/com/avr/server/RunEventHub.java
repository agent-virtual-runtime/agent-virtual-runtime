package com.avr.server;

import com.avr.api.RunEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 供 SSE 使用的内存运行事件回放与订阅中心。 */
final class RunEventHub {
    private final Map<String, Channel> channels = new HashMap<String, Channel>();

    synchronized void open(String runId) {
        channels.computeIfAbsent(runId, ignored -> new Channel());
    }

    void publish(RunEvent event) {
        channel(event.getRunId()).publish(event);
    }

    Subscription subscribe(String runId) {
        return channel(runId).subscribe();
    }

    private synchronized Channel channel(String runId) {
        Channel channel = channels.get(runId);
        if (channel == null) {
            throw new IllegalArgumentException("run does not exist: " + runId);
        }
        return channel;
    }

    static final class Subscription implements AutoCloseable {
        private final Channel channel;
        private final BlockingQueue<RunEvent> events;
        private volatile boolean closed;

        private Subscription(Channel channel, BlockingQueue<RunEvent> events) {
            this.channel = channel;
            this.events = events;
        }

        RunEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
            return events.poll(timeout, unit);
        }

        boolean isComplete() {
            return channel.isComplete() && events.isEmpty();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                channel.unsubscribe(events);
            }
        }
    }

    private static final class Channel {
        private final List<RunEvent> history = new ArrayList<RunEvent>();
        private final List<BlockingQueue<RunEvent>> subscribers =
                new ArrayList<BlockingQueue<RunEvent>>();
        private boolean complete;

        synchronized void publish(RunEvent event) {
            history.add(event);
            for (BlockingQueue<RunEvent> subscriber : subscribers) {
                subscriber.offer(event);
            }
            if (event.getState().isTerminal()) {
                complete = true;
            }
        }

        synchronized Subscription subscribe() {
            BlockingQueue<RunEvent> queue = new LinkedBlockingQueue<RunEvent>();
            queue.addAll(history);
            subscribers.add(queue);
            return new Subscription(this, queue);
        }

        synchronized boolean isComplete() {
            return complete;
        }

        synchronized void unsubscribe(BlockingQueue<RunEvent> queue) {
            subscribers.remove(queue);
        }
    }
}
