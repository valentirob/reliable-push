package io.reliablepush.harness.scenarios;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationState;
import io.reliablepush.client.ReliablePushException;
import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.AndroidMode;
import io.reliablepush.harness.devices.DeviceBehaviour;
import io.reliablepush.harness.devices.DeviceSimulator;
import io.reliablepush.harness.devices.DeviceSimulator.DeviceEventRecord;
import io.reliablepush.harness.devices.GrantMinter;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.logs.BackendLogTail;
import io.reliablepush.harness.logs.LogQuery;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.receiver.WebhookInbox;
import io.reliablepush.harness.sender.FakeSender;
import io.reliablepush.harness.sender.SendPushCommand;
import io.reliablepush.harness.tracking.Timeline;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Everything a {@link Scenario} may use: the harness components, unique user ids, bounded waits and assertions that
 * end the scenario with an expected / actual / hint {@link ScenarioResult.Failure}. One context per scenario
 * execution; user ids and the trackingId → app map are shared by the whole run.
 *
 * <p>All waits are bounded and poll; none asserts exact wall-clock timings. Log lines ({@link #log}) end up in
 * {@code summary.json}: never put secrets, tokens or user ids into them.
 */
public final class ScenarioContext {

    /** The harness components scenarios drive. */
    public record Components(FakeSender sender, DeviceSimulator devices, GrantMinter grants, WebhookInbox inbox,
                             Timeline timeline, BackendLogTail backendLog, HarnessProperties properties) {}

    /** State shared by all scenarios of one run. */
    static final class Run {
        final String prefix;
        final Map<UUID, String> appOf = new ConcurrentHashMap<>();
        final AtomicInteger texts = new AtomicInteger();

        Run(String prefix) {
            this.prefix = prefix;
        }
    }

    /**
     * Values scenarios generated during the harness lifetime that must never reach the backend log: raw user ids
     * and fallback texts (insertion-ordered, last {@value #CAPACITY}). Never logged or returned.
     */
    static final class History {
        static final int CAPACITY = 10_000;
        private final LinkedHashSet<String> values = new LinkedHashSet<>();

        synchronized void add(String value) {
            values.add(value);
            while (values.size() > CAPACITY) {
                values.remove(values.iterator().next());
            }
        }

        synchronized List<String> snapshot() {
            return List.copyOf(values);
        }

        @Override
        public String toString() {
            return "History[size=" + values.size() + "]";
        }
    }

    private static final Set<NotificationState> FINAL = EnumSet.of(NotificationState.READ,
            NotificationState.FALLBACK_TRIGGERED, NotificationState.FALLBACK_FAILED, NotificationState.FALLBACK_EXPIRED,
            NotificationState.CANCELLED);

    private final Components c;
    private final Run run;
    private final History history;
    private final String scenario;
    private final Duration poll;
    private final Map<UUID, List<DeviceEventRecord>> deviceEvents;
    private final long startNanos;
    private final List<String> messages;

    ScenarioContext(Components components, Run run, History history, String scenario, Duration poll,
                    Map<UUID, List<DeviceEventRecord>> deviceEvents, long startNanos, List<String> priorMessages) {
        this.c = components;
        this.run = run;
        this.history = history;
        this.scenario = scenario;
        this.poll = poll;
        this.deviceEvents = deviceEvents;
        this.startNanos = startNanos;
        this.messages = Collections.synchronizedList(new ArrayList<>(priorMessages));
    }

    // ---- components -------------------------------------------------------------------------------------------

    public FakeSender sender() {
        return c.sender();
    }

    public DeviceSimulator devices() {
        return c.devices();
    }

    public WebhookInbox inbox() {
        return c.inbox();
    }

    public Timeline timeline() {
        return c.timeline();
    }

    public BackendLogTail backendLog() {
        return c.backendLog();
    }

    public HarnessProperties properties() {
        return c.properties();
    }

    public String name() {
        return scenario;
    }

    // ---- ids, logging, failing -------------------------------------------------------------------------------

    /** Unique for this run (lowercase letters and digits). */
    public String userIdPrefix() {
        return run.prefix;
    }

    /** {@code <run-prefix>-<scenario>-<n>}; remembered (harness lifetime, bounded) for the log-hygiene scan. */
    public String userId(int n) {
        String id = run.prefix + "-" + scenario + "-" + n;
        history.add(id);
        return id;
    }

    /** Appends a progress line ({@code [+<ms since start> ms] message}) to the scenario result. No secrets. */
    public void log(String message) {
        messages.add("[+" + elapsedMs() + " ms] " + message);
    }

    /** Ends the scenario as FAILED. Never returns normally; the return type allows {@code throw ctx.fail(...)}. */
    public ScenarioFailure fail(String expected, String actual, String hint) {
        throw new ScenarioFailure(new ScenarioResult.Failure(expected, actual, hint));
    }

    public void check(boolean ok, String expected, Supplier<String> actual, String hint) {
        if (!ok) {
            fail(expected, actual.get(), hint);
        }
    }

    /**
     * Every value the backend must never log, for the log-hygiene scan: each app's sender API key, grant secret and
     * HMAC secret; every device token, signed grant and webhook signature the harness issued or saw; and the raw
     * user ids and fallback texts scenarios used (harness lifetime, bounded). Never log or return it.
     */
    public List<String> secrets() {
        List<String> out = new ArrayList<>();
        for (HarnessApp app : c.properties().apps().values()) {
            out.add(app.senderApiKey());
            out.add(app.grantSecret());
            out.add(app.hmacSecret());
        }
        addAll(out, c.devices().issuedTokensForLogHygieneScan());
        addAll(out, c.grants().issuedGrantsForLogHygieneScan());
        addAll(out, c.inbox().signaturesForLogHygieneScan());
        out.addAll(history.snapshot());
        return out;
    }

    private static void addAll(List<String> out, List<String> values) {
        if (values != null) {
            out.addAll(values);
        }
    }

    // ---- devices and sending ---------------------------------------------------------------------------------

    /** Registers a simulated device (Android devices use payload mode V1). */
    public SimulatedDevice register(String appId, String userId, Platform platform) {
        SimulatedDevice d = c.devices().register(appId, userId, platform,
                platform == Platform.ANDROID ? AndroidMode.V1 : null);
        log("registered " + platform + " device " + d.deviceId() + " on " + appId);
        return d;
    }

    /**
     * Registers a notification through the sender API and simulates the push; {@code behaviours} maps device ids to
     * behaviour strings such as {@code deliver:1s}. The fallback is {@code {channel: sms, text: <unique text>}}; the
     * text is message content the backend must never log, so the hygiene scan looks for it. Returns the trackingId
     * (remembered for {@link #awaitState}).
     */
    public UUID send(String appId, String userId, FallbackPolicy policy, Integer timeoutMinutes,
                     Map<UUID, String> behaviours) {
        Map<UUID, DeviceBehaviour> parsed = new LinkedHashMap<>();
        behaviours.forEach((device, text) -> parsed.put(device, DeviceBehaviour.parse(text)));
        String text = "Fallback text " + run.prefix + " " + scenario + " #" + run.texts.incrementAndGet();
        history.add(text);
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("channel", "sms");
        fallback.put("text", text);
        Notification n = c.sender().send(new SendPushCommand(appId, userId, "Scenario " + scenario, text, policy,
                timeoutMinutes, fallback, parsed));
        track(n.trackingId(), appId);
        log("sent " + n.trackingId() + " app=" + appId + " policy=" + policy + " timeoutMinutes=" + timeoutMinutes
                + " behaviours=" + behaviours.values());
        return n.trackingId();
    }

    /** Remembers the app of a trackingId created outside {@link #send} (e.g. in an external scenario's prepare). */
    public void track(UUID trackingId, String appId) {
        run.appOf.put(trackingId, appId);
    }

    public void cancel(UUID trackingId) {
        c.sender().senderClient(appOf(trackingId)).cancel(trackingId);
        log("cancelled " + trackingId);
    }

    public Optional<Notification> notification(UUID trackingId) {
        return c.sender().senderClient(appOf(trackingId)).get(trackingId);
    }

    // ---- waits -----------------------------------------------------------------------------------------------

    /**
     * Polls the sender API every 200 ms until the notification is in {@code state}. Fails at {@code timeout}, or as
     * soon as it is in a different final state. Transient API errors are retried until the timeout.
     */
    public Notification awaitState(UUID trackingId, NotificationState state, Duration timeout)
            throws InterruptedException {
        String appId = appOf(trackingId);
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "not found";
        while (true) {
            try {
                Optional<Notification> n = c.sender().senderClient(appId).get(trackingId);
                if (n.isPresent()) {
                    NotificationState now = n.get().state();
                    if (now == state) {
                        log(trackingId + " is " + state);
                        return n.get();
                    }
                    last = "state " + now;
                    if (FINAL.contains(now)) {
                        fail("state " + state + " for " + trackingId, last + " (final, it cannot change any more)",
                                hint(trackingId));
                    }
                } else {
                    last = "not found";
                }
            } catch (ReliablePushException e) {
                last = "sender API error (HTTP " + e.status() + ")";
            }
            if (System.nanoTime() - deadline >= 0) {
                fail("state " + state + " for " + trackingId + " within " + format(timeout),
                        last + " when the wait ended", hint(trackingId));
            }
            Thread.sleep(poll);
        }
    }

    public void pause(Duration d) throws InterruptedException {
        Thread.sleep(d);
    }

    public void sleepUntil(Instant until) throws InterruptedException {
        long ms = Duration.between(Instant.now(), until).toMillis();
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    // ---- webhooks --------------------------------------------------------------------------------------------

    /** Every receiver call for the trackingId, in arrival order, including pending (in-flight) ones. */
    public List<ReceivedWebhook> webhooks(UUID trackingId) {
        return c.inbox().forTracking(trackingId).stream()
                .sorted(Comparator.comparing(ReceivedWebhook::receivedAt))
                .toList();
    }

    /** Answered receiver calls for the trackingId, in arrival order. */
    public List<ReceivedWebhook> finishedWebhooks(UUID trackingId) {
        return webhooks(trackingId).stream().filter(w -> !w.isPending()).toList();
    }

    /** Waits until {@code until}, then asserts that no webhook (answered or in flight) arrived for the trackingId. */
    public void expectNoWebhook(UUID trackingId, Instant until) throws InterruptedException {
        sleepUntil(until);
        List<ReceivedWebhook> calls = webhooks(trackingId);
        check(calls.isEmpty(), "no fallback webhook for " + trackingId, () -> calls.size() + " webhook call(s): "
                + describe(calls), "the policy was satisfied (or cancelled) in time; " + hint(trackingId));
        log("no webhook for " + trackingId);
    }

    /** Asserts exactly {@code count} webhook calls for the trackingId, none of them still in flight. */
    public List<ReceivedWebhook> expectWebhookCount(UUID trackingId, int count) {
        List<ReceivedWebhook> calls = webhooks(trackingId);
        check(calls.size() == count && calls.stream().noneMatch(ReceivedWebhook::isPending),
                "exactly " + count + " webhook call(s) for " + trackingId + ", all answered",
                () -> calls.size() + " call(s): " + describe(calls), hint(trackingId));
        log(count + " webhook call(s) for " + trackingId + ": " + describe(calls));
        return calls;
    }

    /** Short, secret-free description of receiver calls: attempt, status, signature validity. */
    public static String describe(List<ReceivedWebhook> calls) {
        if (calls.isEmpty()) {
            return "[]";
        }
        return calls.stream()
                .map(w -> "{attempt=" + w.attempt() + ", status=" + (w.isPending() ? "in-flight" : w.respondedStatus())
                        + ", signature=" + (w.signatureValid() ? "valid" : "INVALID") + "}")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    // ---- device events ---------------------------------------------------------------------------------------

    /** Events the simulated devices reported for the trackingId during this run, in report order. */
    public List<DeviceEventRecord> deviceEvents(UUID trackingId) {
        List<DeviceEventRecord> list = deviceEvents.get(trackingId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    public List<DeviceEventRecord> awaitDeviceEvents(UUID trackingId, int count, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (deviceEvents(trackingId).size() < count) {
            if (System.nanoTime() - deadline >= 0) {
                List<DeviceEventRecord> seen = deviceEvents(trackingId);
                fail(count + " device event report(s) for " + trackingId + " within " + format(timeout),
                        seen.size() + " report(s): " + seen.stream().map(e -> e.type() + "/" + e.outcome()).toList(),
                        "the device simulator posts to /v1/events; check the api container log");
            }
            Thread.sleep(poll);
        }
        return deviceEvents(trackingId);
    }

    // ---- backend log -----------------------------------------------------------------------------------------

    /**
     * Waits until the backend log has an entry of each action with {@code rp.tracking_id} = trackingId; returns the
     * first (newest) entry per action.
     */
    public Map<String, Map<String, Object>> awaitLogActions(UUID trackingId, Collection<String> actions,
                                                             Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Map<String, Map<String, Object>> found = new LinkedHashMap<>();
            for (Map<String, Object> e : c.backendLog().query(new LogQuery(null, trackingId.toString(), null, 0))) {
                Object action = e.get("event.action");
                if (action instanceof String a && actions.contains(a)) {
                    found.putIfAbsent(a, e);
                }
            }
            if (found.keySet().containsAll(actions)) {
                log("backend log has " + actions + " for " + trackingId);
                return found;
            }
            if (System.nanoTime() - deadline >= 0) {
                List<String> missing = actions.stream().filter(a -> !found.containsKey(a)).toList();
                fail("backend log events " + actions + " with rp.tracking_id=" + trackingId,
                        "missing " + missing + "; present " + found.keySet(),
                        "the harness reads backend-*.json from HARNESS_BACKENDLOGDIR; see spec 9.1 and "
                                + "harness/build/e2e/logs/");
            }
            Thread.sleep(poll);
        }
    }

    /** Waits until the backend log has an entry of {@code action} whose {@code field} equals {@code value}. */
    public Map<String, Object> awaitLogEntry(String action, String field, String value, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (Map<String, Object> e : c.backendLog().query(new LogQuery(null, null, action, 0))) {
                if (value.equals(e.get(field))) {
                    log("backend log has " + action + " with " + field + "=" + value);
                    return e;
                }
            }
            if (System.nanoTime() - deadline >= 0) {
                fail("backend log event " + action + " with " + field + "=" + value + " within " + format(timeout),
                        "none", "see spec 9.1 and harness/build/e2e/logs/");
            }
            Thread.sleep(poll);
        }
    }

    // ---- internals -------------------------------------------------------------------------------------------

    List<String> messages() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    private String appOf(UUID trackingId) {
        String appId = run.appOf.get(Objects.requireNonNull(trackingId, "trackingId"));
        if (appId == null) {
            throw new IllegalArgumentException("unknown trackingId " + trackingId + ": use send() or track() first");
        }
        return appId;
    }

    private long elapsedMs() {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    static String hint(UUID trackingId) {
        return "follow rp.tracking_id=" + trackingId + " in harness/build/e2e/logs/ (api and worker logs) and in the "
                + "console timeline";
    }

    static String format(Duration d) {
        long ms = d.toMillis();
        return ms >= 1000 && ms % 1000 == 0 ? ms / 1000 + " s" : ms + " ms";
    }
}
