package io.reliablepush.harness.sender;

import io.reliablepush.client.Notification;
import io.reliablepush.client.RegisterRequest;
import io.reliablepush.client.ReliablePushException;
import io.reliablepush.client.SenderClient;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.PushReceiver;
import io.reliablepush.payload.TrackingIds;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays the customer backend, following the sender flow of the sidecar integration (spec 1): generate the tracking id
 * with the payload jar ({@code TrackingIds.newId()}, unless the command brings its own), build the tracked APNs and
 * FCM payloads with the jar, register the notification through the real sender API (client-java) with that
 * {@code trackingId}, and then <b>always</b> deliver the push to the user's devices, whatever the registration
 * outcome: a failed registration only means no tracking and no fallback. Only an unknown app or a payload the jar
 * rejects (for example no title and no body) stops the send before anything happens.
 *
 * <p>The registration uses the client's 5 s timeout, not the 1 s a production sender should use: the local stack runs
 * on a laptop, often under load, and a scenario must not fail on a slow registration.
 */
@Component
public class FakeSender {

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HarnessProperties properties;
    private final PushReceiver receiver;
    private final Map<String, SenderClient> clients = new ConcurrentHashMap<>();
    private final List<Consumer<Sent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * What a send did. {@code notification}: the registered row, null when the registration failed or was skipped.
     * {@code failureStatus}: the sender API's HTTP status of a failed registration ({@code -1} = no answer: timeout or
     * I/O error), null otherwise.
     */
    public record SendResult(UUID trackingId, Notification notification, Integer failureStatus, boolean skipped) {

        public boolean registered() {
            return notification != null;
        }
    }

    /** A push the sender sent; {@code deviceCount} = devices given an explicit behaviour. */
    public record Sent(String appId, SendResult result, int deviceCount) {

        public UUID trackingId() {
            return result.trackingId();
        }

        /** The registered row, or null (registration failed or skipped). */
        public Notification notification() {
            return result.notification();
        }
    }

    /** Listener called synchronously after the registration step and before the push is simulated; must not block. */
    public void onSent(Consumer<Sent> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public FakeSender(HarnessProperties properties, PushReceiver receiver) {
        this.properties = properties;
        this.receiver = receiver;
    }

    /**
     * Runs the sender flow once.
     *
     * @throws IllegalArgumentException unknown app, or a payload the jar rejects ({@code PayloadException}); nothing
     *     was registered or pushed
     */
    public SendResult send(SendPushCommand c) {
        Objects.requireNonNull(c, "command");
        SenderClient client = senderClient(c.appId()); // unknown app: throws before anything happens
        UUID trackingId = c.trackingId() != null ? c.trackingId() : UUID.fromString(TrackingIds.newId());
        // 3. the payloads are built (and validated) first, so an unsendable push is never registered (spec 4.3)
        String apns = PayloadPreview.apnsCompact(trackingId, c.title(), c.body());
        PayloadPreview.fcmV1Compact(trackingId, c.title(), c.body(), PayloadPreview.DEVICE_TOKEN);
        // 2. register; the outcome is informative only
        SendResult result;
        if (c.skipRegistration()) {
            result = new SendResult(trackingId, null, null, true);
        } else {
            result = register(client, c, trackingId);
        }
        for (Consumer<Sent> l : listeners) {
            try {
                l.accept(new Sent(c.appId(), result, c.perDevice() == null ? 0 : c.perDevice().size()));
            } catch (RuntimeException ignored) {
                // a listener never breaks sending
            }
        }
        // 4. always push: the devices see the rp_tid of the payload the jar built
        receiver.receivePush(UUID.fromString(JSON.readTree(apns).get("rp_tid").asString()), c.appId(), c.userId(),
                c.perDevice());
        return result;
    }

    private SendResult register(SenderClient client, SendPushCommand c, UUID trackingId) {
        try {
            Notification n = client.register(RegisterRequest.builder()
                    .trackingId(trackingId.toString())
                    .userId(c.userId())
                    .externalId(c.externalId())
                    .policy(c.policy())
                    .timeoutMinutes(c.timeoutMinutes())
                    .fallback(c.fallback() != null ? c.fallback() : defaultFallback(c.body()))
                    .build());
            return new SendResult(trackingId, n, null, false);
        } catch (ReliablePushException e) {
            return new SendResult(trackingId, null, e.status(), false);
        }
    }

    private static Map<String, Object> defaultFallback(String body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", "sms");
        m.put("text", body == null ? "" : body);
        return m;
    }

    /** The cached sender client of an app (shared with the tracking poller). Throws for an unknown app. */
    public SenderClient senderClient(String appId) {
        SenderClient existing = clients.get(appId);
        if (existing != null) {
            return existing;
        }
        String key = properties.app(appId).senderApiKey(); // throws for an unknown app, nothing cached
        return clients.computeIfAbsent(appId, id -> SenderClient.builder()
                .baseUrl(properties.backendUrl()).apiKey(key).timeout(TIMEOUT).build());
    }
}
