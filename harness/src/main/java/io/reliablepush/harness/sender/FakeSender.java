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

    public FakeSender(HarnessProperties properties, PushReceiver receiver) {
        this.properties = properties;
        this.receiver = receiver;
    }

    public Notification send(SendPushCommand c) {
        Objects.requireNonNull(c, "command");
        Notification n = client(c.appId()).register(RegisterRequest.builder()
                .userId(c.userId())
                .policy(c.policy())
                .timeoutMinutes(c.timeoutMinutes())
                .fallback(c.fallback() != null ? c.fallback() : defaultFallback(c.body()))
                .build());
        receiver.receivePush(n.trackingId(), c.appId(), c.userId(), c.perDevice());
        return n;
    }

    private static Map<String, Object> defaultFallback(String body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", "sms");
        m.put("text", body == null ? "" : body);
        return m;
    }

    private SenderClient client(String appId) {
        SenderClient existing = clients.get(appId);
        if (existing != null) {
            return existing;
        }
        String key = properties.app(appId).senderApiKey(); // throws for an unknown app, nothing cached
        return clients.computeIfAbsent(appId, id -> SenderClient.builder()
                .baseUrl(properties.backendUrl()).apiKey(key).timeout(TIMEOUT).build());
    }
}
