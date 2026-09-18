package com.avr.core;

import com.avr.api.RunSnapshot;
import com.avr.api.RuntimeEvent;
import com.avr.api.RuntimeEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 将 Runtime 事件投影为可查询状态的有界内存实现。
 *
 * <p>该实现不会自动启用，适合调试和轻量服务。需要持久化时，应用可以自行实现
 * {@link RuntimeEventListener} 并写入数据库、消息队列或日志系统。</p>
 */
public final class InMemoryRunTracker implements RuntimeEventListener {
    private static final int DEFAULT_MAX_RUNS = 1_000;
    private static final int DEFAULT_MAX_EVENTS_PER_RUN = 500;

    private final int maxRuns;
    private final int maxEventsPerRun;
    private final Map<String, TrackedRun> runs =
            new LinkedHashMap<String, TrackedRun>();

    public InMemoryRunTracker() {
        this(DEFAULT_MAX_RUNS, DEFAULT_MAX_EVENTS_PER_RUN);
    }

    public InMemoryRunTracker(int maxRuns, int maxEventsPerRun) {
        if (maxRuns < 1) {
            throw new IllegalArgumentException("maxRuns must be positive");
        }
        if (maxEventsPerRun < 1) {
            throw new IllegalArgumentException(
                    "maxEventsPerRun must be positive");
        }
        this.maxRuns = maxRuns;
        this.maxEventsPerRun = maxEventsPerRun;
    }

    @Override
    public synchronized void onEvent(RuntimeEvent event) {
        TrackedRun run = runs.get(event.getRunId());
        if (run == null) {
            evictOldestRunIfNecessary();
            run = new TrackedRun(event);
            runs.put(event.getRunId(), run);
        }
        run.accept(event, maxEventsPerRun);
    }

    /** 按 runId 查询最新运行状态。 */
    public synchronized Optional<RunSnapshot> find(String runId) {
        TrackedRun run = runs.get(runId);
        return run == null
                ? Optional.empty()
                : Optional.of(run.snapshot());
    }

    /** 返回当前保留的全部运行状态。 */
    public synchronized List<RunSnapshot> snapshots() {
        List<RunSnapshot> snapshots = new ArrayList<RunSnapshot>();
        for (TrackedRun run : runs.values()) {
            snapshots.add(run.snapshot());
        }
        return Collections.unmodifiableList(snapshots);
    }

    /** 返回尚未进入终态的运行。 */
    public synchronized List<RunSnapshot> running() {
        List<RunSnapshot> snapshots = new ArrayList<RunSnapshot>();
        for (TrackedRun run : runs.values()) {
            if (!run.latest.getState().isTerminal()) {
                snapshots.add(run.snapshot());
            }
        }
        return Collections.unmodifiableList(snapshots);
    }

    /** 返回某次运行仍保留在内存中的有序事件。 */
    public synchronized List<RuntimeEvent> events(String runId) {
        TrackedRun run = runs.get(runId);
        if (run == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(
                new ArrayList<RuntimeEvent>(run.events));
    }

    private void evictOldestRunIfNecessary() {
        if (runs.size() < maxRuns) {
            return;
        }
        Iterator<String> iterator = runs.keySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static final class TrackedRun {
        private final Instant startedAt;
        private final List<RuntimeEvent> events =
                new ArrayList<RuntimeEvent>();
        private RuntimeEvent latest;

        private TrackedRun(RuntimeEvent first) {
            this.startedAt = first.getOccurredAt();
            this.latest = first;
        }

        private void accept(RuntimeEvent event, int maxEvents) {
            latest = event;
            events.add(event);
            if (events.size() > maxEvents) {
                events.remove(0);
            }
        }

        private RunSnapshot snapshot() {
            Instant completedAt = latest.getState().isTerminal()
                    ? latest.getOccurredAt()
                    : null;
            return new RunSnapshot(
                    latest.getRunId(),
                    latest.getAgent(),
                    latest.getWorkspaceId(),
                    latest.getState(),
                    latest.getSequence(),
                    startedAt,
                    latest.getOccurredAt(),
                    completedAt,
                    latest.getType(),
                    latest.getDetail());
        }
    }
}
