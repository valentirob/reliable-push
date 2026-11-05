package io.reliablepush.harness.fcm;

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
 * Real FCM mode: on when the service-account file loads (default {@code /run/fcm/service-account.json}, mounted by
 * compose from {@code harness/build/fcm/}). Sends a push to every real device of the user that reported an FCM token.
 */
@Component
public class FcmPusher {

    private static final Logger LOG = LogManager.getLogger(FcmPusher.class);

    /** The outcome for one device; carries no token. */
    public record DevicePush(UUID deviceId, FcmResult result) {}

    private final Optional<FcmSender> sender;
    private final RealDeviceRegistry registry;

    @Autowired
    public FcmPusher(@Value("${harness.fcm.credentials-file:/run/fcm/service-account.json}") String credentialsFile,
                     RealDeviceRegistry registry) {
        this(load(credentialsFile), registry);
    }

    FcmPusher(Optional<FcmSender> sender, RealDeviceRegistry registry) {
        this.sender = sender;
        this.registry = registry;
    }

    private static Optional<FcmSender> load(String file) {
        try {
            Optional<FcmSender> s = FcmCredentials.load(Path.of(file)).map(FcmSender::new);
            s.ifPresentOrElse(x -> LOG.info("fcm.real_mode enabled project={}", x.projectId()),
                    () -> LOG.info("fcm.real_mode disabled (no service-account file)"));
            return s;
        } catch (IllegalArgumentException e) {
            LOG.warn("fcm.real_mode disabled: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean enabled() {
        return sender.isPresent();
    }

    /** One FCM message per real device of the user with a token; empty when real mode is off. */
    public List<DevicePush> push(String appId, String userId, UUID trackingId, String title, String body) {
        if (sender.isEmpty()) {
            return List.of();
        }
        return registry.forUser(appId, userId).stream().filter(d -> d.fcmToken() != null).map(d -> {
            FcmResult r = sender.get().send(d.fcmToken(), trackingId, title, body);
            if (r.outcome() == FcmResult.Outcome.UNREGISTERED) {
                registry.setFcmToken(d.deviceId(), null);
            }
            return new DevicePush(d.deviceId(), r);
        }).toList();
    }
}
