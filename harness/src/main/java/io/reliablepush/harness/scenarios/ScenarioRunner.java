package io.reliablepush.harness.scenarios;

import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.DeviceSimulator;
import io.reliablepush.harness.devices.DeviceSimulator.DeviceEventRecord;
import io.reliablepush.harness.devices.GrantMinter;
import io.reliablepush.harness.logs.BackendLogTail;
import io.reliablepush.harness.receiver.ReceiverMode;
import io.reliablepush.harness.receiver.WebhookInbox;
import io.reliablepush.harness.sender.FakeSender;
import io.reliablepush.harness.tracking.Timeline;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs scenarios sequentially, one run at a time (a concurrent call gets {@link RunInProgressException}), each with
 * a 90 s timeout. Before each scenario the webhook inbox is cleared; after it every app's receiver is reset to
 * {@link ReceiverMode#OK}. Keeps the results of the last run (external scenarios' verify results are merged in).
 * Logs one INFO line per scenario (name, status, duration) and nothing else about it.
 */
@Component
public class ScenarioRunner implements AutoCloseable {

    static final Duration SCENARIO_TIMEOUT = Duration.ofSeconds(90);
    static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    /** How long a prepared external scenario blocks other runs while the script kills and restarts workers. */
    static final Duration RESERVATION_TTL = Duration.ofSeconds(120);
    static final int MAX_TRACKED_EVENTS = 10_000;
    static final String SKIPPED_EXTERNAL = "external control: driven by scripts/e2e.sh through prepare/verify";
    private static final int MAX_MESSAGE = 500;
    private static final Logger LOG = LogManager.getLogger(ScenarioRunner.class);
    private static final AtomicLong RUNS = new AtomicLong();

    /** A second run (or prepare/verify) while one is in progress. */
    public static final class RunInProgressException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        RunInProgressException() {
            this("A scenario run is already in progress; wait for it to finish and retry.");
        }

        RunInProgressException(String message) {
            super(message);
        }
    }

    public static final class UnknownScenarioException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        UnknownScenarioException(String name) {
            super("unknown scenario: " + name);
        }
    }

    /** Result of a prepare: the trackingId to watch, or the FAILED result (then trackingId is null). */
    public record Prepared(UUID trackingId, ScenarioResult result) {}

    private record PreparedState(UUID trackingId, long startNanos, List<String> messages, ScenarioContext.Run run) {}

    /** A prepared external scenario holds the runner until it is verified or the reservation expires. */
    private record Reservation(String name, long expiresAtNanos) {}

    private final Map<String, Scenario> scenarios = new LinkedHashMap<>();
    private final ScenarioContext.Components components;
    private final Duration timeout;
    private final Duration poll;
    private final Duration reservationTtl;
    private final ScenarioContext.History history = new ScenarioContext.History();
    private volatile Reservation reservation;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<UUID, List<DeviceEventRecord>> deviceEvents = new ConcurrentHashMap<>();
    private final Map<String, PreparedState> prepared = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("scenario-", 0).factory());
    private volatile List<ScenarioResult> lastResults = List.of();

    @Autowired
    public ScenarioRunner(List<Scenario> scenarios, FakeSender sender, DeviceSimulator devices, GrantMinter grants,
                          WebhookInbox inbox, Timeline timeline, BackendLogTail backendLog,
                          HarnessProperties properties) {
        this(scenarios, new ScenarioContext.Components(sender, devices, grants, inbox, timeline, backendLog,
                properties), SCENARIO_TIMEOUT, POLL_INTERVAL, RESERVATION_TTL);
    }

    ScenarioRunner(List<Scenario> scenarios, ScenarioContext.Components components, Duration timeout, Duration poll) {
        this(scenarios, components, timeout, poll, RESERVATION_TTL);
    }

    ScenarioRunner(List<Scenario> scenarios, ScenarioContext.Components components, Duration timeout, Duration poll,
                   Duration reservationTtl) {
        for (Scenario s : scenarios) {
            if (this.scenarios.putIfAbsent(s.name(), s) != null) {
                throw new IllegalStateException("duplicate scenario name: " + s.name());
            }
        }
        this.components = components;
        this.timeout = timeout;
        this.poll = poll;
        this.reservationTtl = reservationTtl;
        components.devices().onEvent(this::recordDeviceEvent);
    }

    /** All scenarios in catalogue order. */
    public List<Scenario> scenarios() {
        return List.copyOf(scenarios.values());
    }

    public boolean running() {
        return running.get();
    }

    public List<ScenarioResult> lastResults() {
        return lastResults;
    }

    /** Runs every scenario that is not external-control, in catalogue order. */
    public List<ScenarioResult> runAll() {
        return run(scenarios.values().stream().filter(s -> !s.externalControl()).map(Scenario::name).toList());
    }

    public ScenarioResult run(String name) {
        return run(List.of(name)).get(0);
    }

    /** Runs the named scenarios in the given order; external-control ones are reported as SKIPPED. */
    public List<ScenarioResult> run(List<String> names) {
        names.forEach(this::scenario);
        acquire(null);
        try {
            ScenarioContext.Run run = newRun();
            List<ScenarioResult> results = new ArrayList<>();
            for (String name : names) {
                Scenario s = scenario(name);
                if (s.externalControl()) {
                    ScenarioResult skipped = new ScenarioResult(name, ScenarioResult.SKIPPED, 0, null,
                            List.of(SKIPPED_EXTERNAL));
                    LOG.info("scenario {} {} durationMs={}", name, skipped.status(), 0);
                    results.add(skipped);
                } else {
                    results.add(runOne(s, run));
                }
            }
            lastResults = List.copyOf(results);
            return lastResults;
        } finally {
            running.set(false);
        }
    }

    /**
     * Sets up an external-control scenario. On success the runner stays reserved for it (every other run or prepare
     * gets {@link RunInProgressException}) until {@link #verify} or its reservation (120 s) expires; receiver
     * overrides it set stay until then.
     */
    public Prepared prepare(String name) {
        Scenario.External ext = external(name);
        acquire(name);
        try {
            reservation = null;
            ScenarioContext.Run run = newRun();
            long start = System.nanoTime();
            ScenarioContext ctx = context(run, name, start, List.of());
            components.inbox().clear();
            prepared.remove(name);
            ScenarioResult.Failure failure;
            UUID id = null;
            try {
                Attempt<UUID> a = attempt(() -> ext.prepare(ctx));
                id = a.value();
                failure = a.failure() != null || id != null ? a.failure()
                        : new ScenarioResult.Failure("a trackingId from prepare", "none", "scenario bug");
            } catch (RuntimeException e) {
                failure = unexpected(e);
            }
            if (failure == null) {
                prepared.put(name, new PreparedState(id, start, ctx.messages(), run));
                reservation = new Reservation(name, System.nanoTime() + reservationTtl.toNanos());
                LOG.info("scenario {} PREPARED durationMs={}", name, elapsedMs(start));
                return new Prepared(id, null);
            }
            resetReceivers();
            ScenarioResult result = new ScenarioResult(name, ScenarioResult.FAILED, elapsedMs(start), failure,
                    ctx.messages());
            LOG.info("scenario {} {} durationMs={}", name, result.status(), result.durationMs());
            upsert(result);
            return new Prepared(null, result);
        } finally {
            running.set(false);
        }
    }

    /**
     * Asserts the end state of an external-control scenario ({@code trackingId} null = the prepared one), merges the
     * result into the last run's results and resets the receivers. The duration counts from prepare when the ids match.
     */
    public ScenarioResult verify(String name, UUID trackingId) {
        Scenario.External ext = external(name);
        PreparedState p = prepared.get(name);
        UUID id = trackingId != null ? trackingId : p == null ? null : p.trackingId();
        if (id == null) {
            throw new IllegalArgumentException("no trackingId given and " + name + " was not prepared: call prepare first");
        }
        acquire(name);
        try {
            boolean same = p != null && p.trackingId().equals(id);
            long start = same ? p.startNanos() : System.nanoTime();
            ScenarioContext ctx = context(same ? p.run() : newRun(), name, start, same ? p.messages() : List.of());
            ScenarioResult.Failure failure;
            try {
                failure = attempt(() -> {
                    ext.verify(ctx, id);
                    return null;
                }).failure();
            } finally {
                resetReceivers();
                prepared.remove(name);
                reservation = null;
            }
            ScenarioResult result = new ScenarioResult(name, failure == null ? ScenarioResult.PASSED
                    : ScenarioResult.FAILED, elapsedMs(start), failure, ctx.messages());
            LOG.info("scenario {} {} durationMs={}", name, result.status(), result.durationMs());
            upsert(result);
            return result;
        } finally {
            running.set(false);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    ScenarioContext.Components components() {
        return components;
    }

    // ---------------------------------------------------------------------------------------------------------

    private record Attempt<T>(T value, ScenarioResult.Failure failure) {}

    private ScenarioResult runOne(Scenario s, ScenarioContext.Run run) {
        long start = System.nanoTime();
        ScenarioContext ctx = context(run, s.name(), start, List.of());
        ScenarioResult.Failure failure;
        try {
            components.inbox().clear();
            failure = attempt(() -> {
                s.run(ctx);
                return null;
            }).failure();
        } catch (RuntimeException e) {
            failure = unexpected(e);
        } finally {
            resetReceivers();
        }
        ScenarioResult result = new ScenarioResult(s.name(), failure == null ? ScenarioResult.PASSED
                : ScenarioResult.FAILED, elapsedMs(start), failure, ctx.messages());
        LOG.info("scenario {} {} durationMs={}", s.name(), result.status(), result.durationMs());
        return result;
    }

    /** Runs the body on its own thread with the scenario timeout; interrupts it on timeout. */
    private <T> Attempt<T> attempt(Callable<T> body) {
        Future<T> f = executor.submit(body);
        try {
            return new Attempt<>(f.get(timeout.toMillis(), TimeUnit.MILLISECONDS), null);
        } catch (TimeoutException e) {
            f.cancel(true);
            return new Attempt<>(null, new ScenarioResult.Failure(
                    "scenario finishes within " + ScenarioContext.format(timeout),
                    "timed out after " + ScenarioContext.format(timeout) + " (scenario thread interrupted)",
                    "a wait inside the scenario never ended; read its logs and harness/build/e2e/logs/"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ScenarioFailure sf) {
                return new Attempt<>(null, sf.failure());
            }
            return new Attempt<>(null, unexpected(cause));
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            return new Attempt<>(null, new ScenarioResult.Failure("scenario finishes", "runner interrupted",
                    "the harness is shutting down"));
        }
    }

    private static ScenarioResult.Failure unexpected(Throwable t) {
        String message = t.getMessage() == null ? "" : ": " + t.getMessage();
        String actual = t.getClass().getSimpleName() + message;
        if (actual.length() > MAX_MESSAGE) {
            actual = actual.substring(0, MAX_MESSAGE) + "…";
        }
        return new ScenarioResult.Failure("no unexpected error", actual,
                "an exception escaped the scenario; is the backend up (docker compose ps) and the app configured?");
    }

    private ScenarioContext context(ScenarioContext.Run run, String name, long start, List<String> prior) {
        return new ScenarioContext(components, run, history, name, poll, deviceEvents, start, prior);
    }

    private ScenarioContext.Run newRun() {
        deviceEvents.clear();
        String prefix = "rp" + Long.toString(System.currentTimeMillis(), 36) + Long.toString(RUNS.incrementAndGet(), 36);
        return new ScenarioContext.Run(prefix);
    }

    /**
     * Takes the one-run guard. While an external scenario is prepared, only a prepare or verify of that scenario
     * ({@code reservedName}) may run; an expired reservation is dropped and the receivers reset.
     */
    private void acquire(String reservedName) {
        if (!running.compareAndSet(false, true)) {
            throw new RunInProgressException();
        }
        Reservation r = reservation;
        if (r == null) {
            return;
        }
        if (System.nanoTime() - r.expiresAtNanos() >= 0) {
            reservation = null;
            prepared.remove(r.name());
            resetReceivers();
            LOG.info("scenario {} reservation expired without verify", r.name());
        } else if (!r.name().equals(reservedName)) {
            running.set(false);
            throw new RunInProgressException("external scenario " + r.name() + " is prepared; verify it first");
        }
    }

    private Scenario scenario(String name) {
        Scenario s = scenarios.get(Objects.requireNonNull(name, "name"));
        if (s == null) {
            throw new UnknownScenarioException(name);
        }
        return s;
    }

    private Scenario.External external(String name) {
        if (scenario(name) instanceof Scenario.External ext) {
            return ext;
        }
        throw new IllegalArgumentException(name + " is not an external-control scenario");
    }

    private void resetReceivers() {
        Set<String> apps = new HashSet<>(components.properties().apps().keySet());
        for (String app : apps) {
            components.inbox().setMode(app, ReceiverMode.OK, Duration.ZERO);
        }
    }

    private synchronized void upsert(ScenarioResult result) {
        List<ScenarioResult> next = new ArrayList<>(lastResults);
        next.removeIf(r -> r.scenario().equals(result.scenario()));
        next.add(result);
        lastResults = List.copyOf(next);
    }

    /** Device events are recorded only while a run is active (bounded); scenarios read them by trackingId. */
    private void recordDeviceEvent(DeviceEventRecord e) {
        if (!running.get() || e.trackingId() == null
                || deviceEvents.size() >= MAX_TRACKED_EVENTS && !deviceEvents.containsKey(e.trackingId())) {
            return;
        }
        List<DeviceEventRecord> list = deviceEvents.computeIfAbsent(e.trackingId(), k -> new ArrayList<>());
        synchronized (list) {
            list.add(e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
