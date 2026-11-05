package io.reliablepush.harness.console;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.Notification;
import io.reliablepush.client.ReliablePushException;
import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.AndroidMode;
import io.reliablepush.harness.apns.ApnsPusher;
import io.reliablepush.harness.fcm.FcmPusher;
import io.reliablepush.harness.devices.BackendCallException;
import io.reliablepush.harness.devices.DeviceBehaviour;
import io.reliablepush.harness.devices.DeviceProxy;
import io.reliablepush.harness.devices.DeviceSimulator;
import io.reliablepush.harness.devices.GrantMinter;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.RealDeviceRegistry;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.logs.BackendLogTail;
import io.reliablepush.harness.logs.LogQuery;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.receiver.ReceiverMode;
import io.reliablepush.harness.receiver.WebhookInbox;
import io.reliablepush.harness.sender.FakeSender;
import io.reliablepush.harness.sender.PayloadPreview;
import io.reliablepush.harness.sender.SendPushCommand;
import io.reliablepush.harness.tracking.Timeline;
import io.reliablepush.harness.tracking.TimelineEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * REST API of the web console (spec 10.2) besides the scenario endpoints ({@code ScenarioController}) and the SSE
 * stream ({@code ConsoleStream}). Local test/demo tool: no endpoint returns device tokens, sender API keys, grant or
 * HMAC secrets. Backend failures answer 502 problem+json with a generic detail and the backend's status in
 * {@code backendStatus} (0 or -1: no answer); a backend 429 is passed on as 429 with its {@code Retry-After}.
 */
@RestController
@RequestMapping("/console/api")
public class ConsoleApiController {

    private static final Logger LOG = LogManager.getLogger(ConsoleApiController.class);

    static final String DEFAULT_APP = "shop";
    static final int RECENT_LIMIT = 200;
    static final int INBOX_LIMIT = 500;
    static final int MAX_TEXT = 1_000;
    static final int MAX_LABEL = 100;
    static final int MAX_FCM_TOKEN = 4096;
    static final int MAX_APNS_TOKEN = 200;
    static final long MAX_HANG_MS = 120_000;

    public record DeviceRequest(String appId, String userId, Platform platform, AndroidMode androidMode) {}

    public record GrantRequest(String appId, String userId) {}

    public record RealDeviceRequest(String appId, String userId, String deviceId, String platform, String label,
                                    String fcmToken) {}

    public record FcmTokenRequest(String fcmToken) {}

    public record ApnsTokenRequest(String apnsToken) {}

    public record OnlineRequest(Boolean online) {}

    /** {@code perDevice}: deviceId to behaviour string such as {@code deliver:3s}. */
    public record PushRequest(String appId, String userId, String title, String body, FallbackPolicy policy,
                              Integer timeoutMinutes, Map<String, Object> fallback, Map<String, String> perDevice) {}

    public record ModeRequest(String appId, String mode, Long hangMs) {}

    private static final class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotFoundException(String message) {
            super(message);
        }
    }

    private static final class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConflictException(String message) {
            super(message);
        }
    }

    private final HarnessProperties properties;
    private final DeviceSimulator simulator;
    private final FakeSender sender;
    private final Timeline timeline;
    private final WebhookInbox inbox;
    private final BackendLogTail logs;
    private final GrantMinter grants;
    private final RealDeviceRegistry realDevices;
    private final FcmPusher fcm;
    private final ApnsPusher apns;
    private final DeviceProxy deviceProxy;

    public ConsoleApiController(HarnessProperties properties, DeviceSimulator simulator, FakeSender sender,
                                Timeline timeline, WebhookInbox inbox, BackendLogTail logs, GrantMinter grants,
                                RealDeviceRegistry realDevices, FcmPusher fcm, DeviceProxy deviceProxy, ApnsPusher apns) {
        this.properties = properties;
        this.simulator = simulator;
        this.sender = sender;
        this.timeline = timeline;
        this.inbox = inbox;
        this.logs = logs;
        this.grants = grants;
        this.realDevices = realDevices;
        this.fcm = fcm;
        this.apns = apns;
        this.deviceProxy = deviceProxy;
    }

    // ---- apps ------------------------------------------------------------------------------------------------

    /** Apps in {@link #displayOrder} order: the console's app selects default to the first entry ({@code shop}). */
    @GetMapping("/apps")
    public List<Map<String, Object>> apps() {
        return displayOrder(properties.apps().keySet()).stream().map(id -> {
            HarnessApp app = properties.apps().get(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("appId", id);
            m.put("windowMinutes", app.windowMinutes());
            m.put("timeScaleSeconds", properties.timeScaleSeconds());
            m.put("fcmEnabled", fcm.enabled());
            m.put("apnsEnabled", apns.enabled());
            return m;
        }).toList();
    }

    /** The default app {@value #DEFAULT_APP} (the scenarios' main app) first, then the others by id. */
    static List<String> displayOrder(Collection<String> appIds) {
        return appIds.stream()
                .sorted(Comparator.comparing((String id) -> !id.equals(DEFAULT_APP)).thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    // ---- devices ---------------------------------------------------------------------------------------------

    @GetMapping("/devices")
    public List<Map<String, Object>> devices(@RequestParam(required = false) String appId,
                                             @RequestParam(required = false) String userId) {
        List<Map<String, Object>> all = new java.util.ArrayList<>(simulator.allDevices(blankToNull(appId)).stream()
                .filter(d -> blankToNull(userId) == null || d.userId().equals(userId))
                .map(ConsoleApiController::deviceJson).toList());
        realDevices.all(blankToNull(appId)).stream()
                .filter(d -> blankToNull(userId) == null || d.userId().equals(userId))
                .map(ConsoleApiController::realDeviceJson).forEach(all::add);
        return all;
    }

    /**
     * A signed grant for the app's user, as the customer's backend would hand to its logged-in client: the sample app
     * on an emulator registers with it. This is the only place a grant leaves the harness; it is never logged.
     */
    @PostMapping("/grants")
    public ResponseEntity<Map<String, Object>> grant(@RequestBody GrantRequest r) {
        requireApp(r.appId());
        requireUser(r.userId());
        String grant = grants.mint(r.appId(), r.userId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grant", grant);
        m.put("expiresAt", iso(grants.expiresAt(grant)));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(m);
    }

    /** A device that registered by itself (emulator app): listed in the console, no token held by the harness. */
    @PostMapping("/real-devices")
    public ResponseEntity<Map<String, Object>> addRealDevice(@RequestBody RealDeviceRequest r) {
        requireApp(r.appId());
        requireUser(r.userId());
        UUID deviceId = uuid(r.deviceId(), "device id");
        Platform platform;
        try {
            platform = Platform.valueOf(String.valueOf(r.platform()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("platform must be ANDROID or IOS");
        }
        String label = r.label() == null ? "" : r.label();
        if (label.length() > MAX_LABEL) {
            throw new IllegalArgumentException("label is too long (at most " + MAX_LABEL + " characters)");
        }
        String fcmToken = r.fcmToken() == null ? null : validFcmToken(r.fcmToken());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(realDeviceJson(realDevices.add(r.appId(), r.userId(), deviceId, platform, label, fcmToken)));
    }

    /** The device's FCM token changed (onNewToken). The token is never echoed back. */
    @PostMapping("/real-devices/{id}/fcm-token")
    public Map<String, Object> setFcmToken(@PathVariable String id, @RequestBody FcmTokenRequest r) {
        UUID deviceId = uuid(id, "device id");
        String token = validFcmToken(r.fcmToken());
        if (!realDevices.setFcmToken(deviceId, token)) {
            throw new NotFoundException("unknown device");
        }
        return Map.of("deviceId", deviceId.toString(), "hasFcmToken", true);
    }

    /** The iOS sample reports its hex APNs device token (Debug, RP_APNS=1). The token is never echoed back. */
    @PostMapping("/real-devices/{id}/apns-token")
    public Map<String, Object> setApnsToken(@PathVariable String id, @RequestBody ApnsTokenRequest r) {
        UUID deviceId = uuid(id, "device id");
        String token = validApnsToken(r.apnsToken());
        if (!realDevices.setApnsToken(deviceId, token)) {
            throw new NotFoundException("unknown device");
        }
        return Map.of("deviceId", deviceId.toString(), "hasApnsToken", true);
    }

    @PostMapping("/devices")
    public ResponseEntity<Map<String, Object>> createDevice(@RequestBody DeviceRequest r) {
        requireApp(r.appId());
        requireUser(r.userId());
        if (r.platform() == null) {
            throw new IllegalArgumentException("platform is required (IOS or ANDROID)");
        }
        AndroidMode mode = r.platform() == Platform.ANDROID && r.androidMode() == null ? AndroidMode.V1 : r.androidMode();
        SimulatedDevice d = simulator.register(r.appId(), r.userId(), r.platform(), mode);
        return ResponseEntity.status(HttpStatus.CREATED).body(deviceJson(d));
    }

    /** Body: {@code {}} (required: every console POST carries a JSON body, see {@link ConsoleRequestGuard}). */
    @PostMapping("/devices/{id}/refresh")
    public Map<String, Object> refresh(@PathVariable String id, @RequestBody Map<String, Object> empty) {
        SimulatedDevice d = device(id);
        if (!d.online()) {
            throw new ConflictException("device is offline");
        }
        int status = simulator.forceRefresh(d.deviceId());
        if (status != 200) {
            throw new BackendCallException("token refresh failed", status, null);
        }
        return deviceJson(device(id));
    }

    @PostMapping("/devices/{id}/online")
    public Map<String, Object> online(@PathVariable String id, @RequestBody OnlineRequest r) {
        if (r.online() == null) {
            throw new IllegalArgumentException("online is required");
        }
        simulator.setOnline(device(id).deviceId(), r.online());
        return deviceJson(device(id));
    }

    // ---- device traffic proxy -----------------------------------------------------------------------------

    @GetMapping("/device-network")
    public Map<String, Object> deviceNetwork() {
        return Map.of("online", deviceProxy.online(), "enabled", deviceProxy.enabled());
    }

    @PostMapping("/device-network")
    public Map<String, Object> setDeviceNetwork(@RequestBody OnlineRequest r) {
        if (r.online() == null) {
            throw new IllegalArgumentException("online is required");
        }
        deviceProxy.setOnline(r.online());
        return deviceNetwork();
    }

    // ---- push and notifications ------------------------------------------------------------------------------

    @PostMapping("/push")
    public Map<String, Object> push(@RequestBody PushRequest r) {
        requireApp(r.appId());
        requireUser(r.userId());
        String title = limited(r.title(), "title");
        String body = limited(r.body(), "body");
        Map<UUID, DeviceBehaviour> perDevice = null;
        if (r.perDevice() != null) {
            perDevice = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : r.perDevice().entrySet()) {
                perDevice.put(uuid(e.getKey(), "device id"), DeviceBehaviour.parse(e.getValue()));
            }
        }
        Notification n = sender.send(new SendPushCommand(r.appId(), r.userId(), title, body, r.policy(),
                r.timeoutMinutes(), r.fallback(), perDevice));
        for (FcmPusher.DevicePush p : fcm.push(r.appId(), r.userId(), n.trackingId(), title, body)) {
            timeline.append(n.trackingId(), r.appId(), new TimelineEntry(Instant.now(), "fcm_push", Map.of(
                    "deviceId", p.deviceId().toString(), "result", p.result().outcome().name(),
                    "status", p.result().status())));
        }
        for (ApnsPusher.DevicePush p : apns.push(r.appId(), r.userId(), n.trackingId(), title, body)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("deviceId", p.deviceId().toString());
            details.put("result", p.result().outcome().name());
            details.put("status", p.result().status());
            if (p.result().reason() != null) {
                details.put("reason", p.result().reason());
            }
            timeline.append(n.trackingId(), r.appId(), new TimelineEntry(Instant.now(), "apns_push", details));
        }
        return Map.of("trackingId", n.trackingId().toString());
    }

    @GetMapping("/notifications")
    public List<Map<String, Object>> notifications(@RequestParam String appId) {
        requireApp(appId);
        return timeline.recent(appId, RECENT_LIMIT).stream().map(ConsoleApiController::notificationJson).toList();
    }

    @GetMapping("/notifications/{id}")
    public Map<String, Object> notification(@PathVariable String id) {
        UUID tid = uuid(id, "tracking id");
        List<TimelineEntry> entries = timeline.entries(tid);
        Notification n = timeline.current(tid).orElse(null);
        if (n == null && entries.isEmpty()) {
            throw new NotFoundException("unknown notification");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notification", n == null ? null : notificationJson(n));
        m.put("appId", timeline.appId(tid).orElse(null));
        m.put("timeline", entries.stream().map(ConsoleApiController::entryJson).toList());
        m.put("webhooks", inbox.forTracking(tid).stream().map(ConsoleApiController::webhookJson).toList());
        return m;
    }

    /** Body: {@code {}} (required: every console POST carries a JSON body, see {@link ConsoleRequestGuard}). */
    @PostMapping("/notifications/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody Map<String, Object> empty) {
        UUID tid = uuid(id, "tracking id");
        String appId = timeline.appId(tid).orElseThrow(() -> new NotFoundException("unknown notification"));
        sender.senderClient(appId).cancel(tid);
        return Map.of("trackingId", tid.toString(), "cancelled", true);
    }

    @GetMapping("/payload-preview")
    public Map<String, Object> payloadPreview(@RequestParam String trackingId,
                                              @RequestParam(defaultValue = "Title") String title,
                                              @RequestParam(defaultValue = "Body") String body) {
        UUID tid = uuid(trackingId, "tracking id");
        String t = limited(title, "title");
        String b = limited(body, "body");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("apns", PayloadPreview.apns(tid, t, b));
        m.put("fcmV1", PayloadPreview.fcmV1(tid, t, b));
        m.put("fcmV2", PayloadPreview.fcmV2(tid, t, b));
        m.put("androidDebugBroadcast", PayloadPreview.androidDebugBroadcast(tid, t, b));
        m.put("iosDebugOpenUrl", PayloadPreview.iosDebugOpenUrl(tid, t, b));
        return m;
    }

    // ---- fallback inbox --------------------------------------------------------------------------------------

    @GetMapping("/inbox")
    public Map<String, Object> inbox(@RequestParam String appId) {
        requireApp(appId);
        List<ReceivedWebhook> all = inbox.received(appId);
        List<Map<String, Object>> calls = new java.util.ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && calls.size() < INBOX_LIMIT; i--) {
            calls.add(webhookJson(all.get(i)));
        }
        WebhookInbox.Mode mode = inbox.mode(appId);
        Map<String, Object> modeJson = new LinkedHashMap<>();
        modeJson.put("mode", mode.mode().name());
        modeJson.put("hangMs", mode.hang().toMillis());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", appId);
        m.put("mode", modeJson);
        m.put("inFlight", inbox.inFlight(appId));
        m.put("calls", calls);
        return m;
    }

    @PostMapping("/inbox/mode")
    public Map<String, Object> setMode(@RequestBody ModeRequest r) {
        requireApp(r.appId());
        ReceiverMode mode;
        try {
            mode = ReceiverMode.valueOf(String.valueOf(r.mode()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be one of OK, FAIL_500, FAIL_400, HANG");
        }
        long hangMs = r.hangMs() == null ? 0 : r.hangMs();
        if (hangMs < 0 || hangMs > MAX_HANG_MS) {
            throw new IllegalArgumentException("hangMs must be between 0 and " + MAX_HANG_MS);
        }
        inbox.setMode(r.appId(), mode, Duration.ofMillis(hangMs));
        return Map.of("appId", r.appId(), "mode", mode.name(), "hangMs", hangMs);
    }

    // ---- logs ------------------------------------------------------------------------------------------------

    @GetMapping("/logs")
    public List<Map<String, Object>> logs(@RequestParam(required = false) String traceId,
                                          @RequestParam(required = false) String trackingId,
                                          @RequestParam(required = false) String eventAction,
                                          @RequestParam(defaultValue = "100") int limit) {
        return logs.query(new LogQuery(blankToNull(traceId), blankToNull(trackingId), blankToNull(eventAction), limit));
    }

    // ---- errors ----------------------------------------------------------------------------------------------

    @ExceptionHandler(ReliablePushException.class)
    ResponseEntity<Object> backendFailure(ReliablePushException e) {
        return backendProblem(e.status(), e.retryAfter().map(d -> d.toSeconds()).orElse(null));
    }

    @ExceptionHandler(BackendCallException.class)
    ResponseEntity<Object> deviceApiFailure(BackendCallException e) {
        return backendProblem(e.status(), e.retryAfterSeconds() == null ? null : e.retryAfterSeconds().longValue());
    }

    /** Anything else that is not a backend answer: a generic 502 (the cause is logged here, never returned). */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Object> unexpected(IllegalStateException e) {
        LOG.warn("console request failed: {}", e.getClass().getName());
        LOG.debug("console request failure", e);
        return problem(HttpStatus.BAD_GATEWAY, "Request failed", "The harness could not complete the request", null);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), null);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Object> unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "malformed request", null);
    }

    /**
     * A backend error answer: 429 is passed on as 429 (with {@code Retry-After} when the backend gave one) so callers
     * can back off; every other status, and no answer at all ({@code <= 0}), is a 502. {@code backendStatus} carries
     * the backend's status.
     */
    private static ResponseEntity<Object> backendProblem(int backendStatus, Long retryAfterSeconds) {
        if (backendStatus == 429) {
            ResponseEntity<Object> r = problem(HttpStatus.TOO_MANY_REQUESTS, "Backend rate limit",
                    "The backend rate-limited the call (backend status 429)", 429);
            if (retryAfterSeconds == null || retryAfterSeconds < 0) {
                return r;
            }
            return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                    .header("Retry-After", Long.toString(retryAfterSeconds)).body(r.getBody());
        }
        String detail = backendStatus > 0
                ? "The backend call failed (backend status " + backendStatus + ")"
                : "The backend call failed (backend unreachable or timed out)";
        return problem(HttpStatus.BAD_GATEWAY, "Backend call failed", detail, backendStatus);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String title, String detail, Integer backend) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        if (backend != null) {
            p.setProperty("backendStatus", backend);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void requireApp(String appId) {
        if (appId == null || !properties.apps().containsKey(appId)) {
            throw new NotFoundException("unknown app");
        }
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 200) {
            throw new IllegalArgumentException("userId is required (at most 200 characters)");
        }
    }

    private SimulatedDevice device(String id) {
        try {
            return simulator.device(uuid(id, "device id"));
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("unknown device")) {
                throw new NotFoundException("unknown device");
            }
            throw e;
        }
    }

    private static UUID uuid(String s, String what) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(what + " is not a UUID");
        }
    }

    /** Never echoes the token in the error. */
    private static String validFcmToken(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_FCM_TOKEN) {
            throw new IllegalArgumentException("fcmToken must not be blank and at most " + MAX_FCM_TOKEN + " characters");
        }
        return token;
    }

    /** A hex token (it goes into the APNs URL path); never echoes the token in the error. */
    private static String validApnsToken(String token) {
        if (token == null || !token.matches("^[0-9a-fA-F]{8," + MAX_APNS_TOKEN + "}$")) {
            throw new IllegalArgumentException("apnsToken must be 8 to " + MAX_APNS_TOKEN + " hex characters");
        }
        return token;
    }

    private static String limited(String s, String what) {
        String v = s == null ? "" : s;
        if (v.length() > MAX_TEXT) {
            throw new IllegalArgumentException(what + " is too long (at most " + MAX_TEXT + " characters)");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String iso(Instant i) {
        return i == null ? null : i.toString();
    }

    static Map<String, Object> deviceJson(SimulatedDevice d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "SIMULATED");
        m.put("deviceId", d.deviceId().toString());
        m.put("appId", d.appId());
        m.put("userId", d.userId());
        m.put("platform", d.platform().name());
        m.put("androidMode", d.androidMode() == null ? null : d.androidMode().name());
        m.put("tokenExpiresAt", iso(d.tokenExpiresAt()));
        m.put("online", d.online());
        return m;
    }

    static Map<String, Object> realDeviceJson(RealDeviceRegistry.RealDevice d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "REAL");
        m.put("deviceId", d.deviceId().toString());
        m.put("appId", d.appId());
        m.put("userId", d.userId());
        m.put("platform", d.platform().name());
        m.put("androidMode", null);
        m.put("tokenExpiresAt", null);
        m.put("online", true);
        m.put("label", d.label());
        m.put("registeredAt", iso(d.registeredAt()));
        m.put("hasFcmToken", d.fcmToken() != null);
        m.put("hasApnsToken", d.apnsToken() != null);
        return m;
    }

    static Map<String, Object> notificationJson(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trackingId", n.trackingId().toString());
        m.put("state", n.state().name());
        m.put("userId", n.userId());
        m.put("policy", n.policy() == null ? null : n.policy().name());
        m.put("timeoutMinutes", n.timeoutMinutes());
        m.put("createdAt", iso(n.createdAt()));
        m.put("dueAt", iso(n.dueAt()));
        m.put("deliveredAt", iso(n.deliveredAt()));
        m.put("readAt", iso(n.readAt()));
        m.put("closedAt", iso(n.closedAt()));
        return m;
    }

    static Map<String, Object> entryJson(TimelineEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", iso(e.at()));
        m.put("kind", e.kind());
        m.put("details", e.details());
        return m;
    }

    /** A receiver call; {@code pending} = still in flight (respondedStatus 0). The body is not exposed. */
    static Map<String, Object> webhookJson(ReceivedWebhook w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callId", w.callId() == null ? null : w.callId().toString());
        m.put("appId", w.appId());
        m.put("trackingId", w.trackingId() == null ? null : w.trackingId().toString());
        m.put("idempotencyKey", w.idempotencyKey());
        m.put("attempt", w.attempt());
        m.put("reason", w.reason());
        m.put("signatureValid", w.signatureValid());
        m.put("receivedAt", iso(w.receivedAt()));
        m.put("respondedAt", iso(w.respondedAt()));
        m.put("respondedStatus", w.respondedStatus());
        m.put("pending", w.isPending());
        return m;
    }
}
