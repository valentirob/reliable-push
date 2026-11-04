package io.reliablepush.harness.sender;

import io.reliablepush.client.Notification;
import io.reliablepush.client.RegisterRequest;
import io.reliablepush.client.ReliablePushException;
import io.reliablepush.client.SenderClient;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.PushReceiver;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Plays the customer backend: registers the notification through the real sender API (client-java) and only then
 * simulates the push reaching the user's devices. A registration failure propagates as
 * {@link ReliablePushException} and no push is simulated.
 */
@Component
public class FakeSender {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HarnessProperties properties;
    private final PushReceiver receiver;
    private final Map<String, SenderClient> clients = new ConcurrentHashMap<>();
    private final List<Consumer<Sent>> listeners = new CopyOnWriteArrayList<>();

    /** A notification the sender registered; {@code deviceCount} = devices given an explicit behaviour. */
    public record Sent(String appId, Notification notification, int deviceCount) {}

    /** Listener called synchronously after registration and before the push is simulated; it must not block. */
    public void onSent(Consumer<Sent> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public FakeSender(HarnessProperties properties, PushReceiver receiver) {
        this.properties = properties;
        this.receiver = receiver;
    }

    public Notification send(SendPushCommand c) {
        Objects.requireNonNull(c, "command");
        Notification n = senderClient(c.appId()).register(RegisterRequest.builder()
                .userId(c.userId())
                .policy(c.policy())
                .timeoutMinutes(c.timeoutMinutes())
                .fallback(c.fallback() != null ? c.fallback() : defaultFallback(c.body()))
                .build());
        for (Consumer<Sent> l : listeners) {
            try {
                l.accept(new Sent(c.appId(), n, c.perDevice() == null ? 0 : c.perDevice().size()));
            } catch (RuntimeException ignored) {
                // a listener never breaks sending
            }
        }
        receiver.receivePush(n.trackingId(), c.appId(), c.userId(), c.perDevice());
        return n;
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
