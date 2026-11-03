package io.reliablepush.fallback;

import io.micrometer.core.instrument.MeterRegistry;
import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.shared.time.TimeScale;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/** The {@code worker} role: scheduling and the fallback worker (spec 1). */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class WorkerConfig {

    private static final Logger LOG = LogManager.getLogger(WorkerConfig.class);

    @Bean
    FallbackScheduler fallbackScheduler(
            NotificationRepository notifications,
            AppRegistry apps,
            TimeScale timeScale,
            Clock clock,
            ReliablePushProperties properties,
            ObjectProvider<WebhookClient> webhookClient,
            JsonMapper json,
            MeterRegistry meters) {
        // the lease is a product duration (2 "minutes"): scaled by the fast clock like backoff and trigger window
        Duration lease = timeScale.scale(properties.worker().lease());
        boolean fastClock = !timeScale.minuteDuration().equals(Duration.ofMinutes(1));
        checkLease(lease, fastClock, apps.all()).ifPresent(LOG::warn);

        WebhookClient client = webhookClient.getIfAvailable();
        FallbackDispatcher dispatcher = null;
        if (client == null) {
            // until the HTTP client lands (plan task 13), a worker without one must not claim rows it cannot send
            LOG.warn("No WebhookClient configured: the fallback worker does not claim notifications");
        } else {
            dispatcher = new FallbackDispatcher(notifications, apps, timeScale, clock, client, json, meters, lease);
        }
        return new FallbackScheduler(notifications, dispatcher, clock, lease, properties.worker().batchSize());
    }

    /**
     * The lease must outlast one webhook call ({@code http-timeout}, a literal duration that is never scaled),
     * otherwise every slow call is re-claimed and re-sent by another worker. In production this fails the startup;
     * with the fast clock (e2e/demo) the scaled lease is usually shorter, which only warrants a warning.
     *
     * @return a warning for the fast clock, empty when the lease is long enough
     * @throws IllegalStateException when the lease is too short outside the fast clock
     */
    static Optional<String> checkLease(Duration lease, boolean fastClock, Collection<AppConfig> apps) {
        List<AppConfig> tooSlow = apps.stream()
                .filter(app -> lease.compareTo(app.fallback().httpTimeout()) <= 0)
                .toList();
        if (tooSlow.isEmpty()) {
            return Optional.empty();
        }
        String offenders = tooSlow.stream()
                .map(app -> app.appId() + " (" + app.fallback().httpTimeout() + ")")
                .collect(Collectors.joining(", "));
        String problem = "reliable-push.worker.lease (" + lease + (fastClock ? " with the fast clock" : "")
                + ") must be longer than fallback.http-timeout of apps " + offenders
                + "; a slower webhook call is re-sent by another worker";
        if (!fastClock) {
            throw new IllegalStateException(problem);
        }
        return Optional.of(problem);
    }
}
