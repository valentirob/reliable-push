package io.reliablepush.harness.apns;

import io.reliablepush.harness.devices.RealDeviceRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Real APNs mode: on when {@code AuthKey.p8} and {@code apns.properties} load (default directory {@code /run/apns},
 * mounted by compose from {@code harness/build/apns/}). Sends a push to every real device of the user that reported
 * an APNs token.
 */
@Component
public class ApnsPusher {

    private static final Logger LOG = LogManager.getLogger(ApnsPusher.class);

    /** The outcome for one device; carries no token. */
    public record DevicePush(UUID deviceId, ApnsResult result) {}

    private final Optional<ApnsSender> sender;
    private final RealDeviceRegistry registry;

    @Autowired
    public ApnsPusher(@Value("${harness.apns.credentials-dir:/run/apns}") String credentialsDir,
                      RealDeviceRegistry registry) {
        this(load(credentialsDir), registry);
    }

    ApnsPusher(Optional<ApnsSender> sender, RealDeviceRegistry registry) {
        this.sender = sender;
        this.registry = registry;
    }

    private static Optional<ApnsSender> load(String dir) {
        try {
            Optional<ApnsSender> s = ApnsCredentials.load(Path.of(dir)).map(ApnsSender::new);
            s.ifPresentOrElse(x -> LOG.info("apns.real_mode enabled {}", x),
                    () -> LOG.info("apns.real_mode disabled (no AuthKey.p8 / apns.properties)"));
            return s;
        } catch (IllegalArgumentException e) {
            LOG.warn("apns.real_mode disabled: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean enabled() {
        return sender.isPresent();
    }

    /** One APNs push per real device of the user with a token; empty when real mode is off. */
    public List<DevicePush> push(String appId, String userId, UUID trackingId, String title, String body) {
        if (sender.isEmpty()) {
            return List.of();
        }
        return registry.forUser(appId, userId).stream().filter(d -> d.apnsToken() != null).map(d -> {
            ApnsResult r = sender.get().send(d.apnsToken(), trackingId, title, body);
            if (r.outcome() == ApnsResult.Outcome.UNREGISTERED) {
                registry.clearApnsToken(d.deviceId(), d.apnsToken()); // not if the app reported a new one meanwhile
            }
            return new DevicePush(d.deviceId(), r);
        }).toList();
    }
}
