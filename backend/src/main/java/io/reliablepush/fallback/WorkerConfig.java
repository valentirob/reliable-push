package io.reliablepush.fallback;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.shared.metrics.RpMetrics;
import io.reliablepush.shared.time.TimeScale;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
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

    /** The production webhook client; present wherever the fallback worker runs. Closed with the context. */
    @Bean
    HttpWebhookClient webhookClient(
            Clock clock, JsonMapper json, RpMetrics metrics, @Value("${info.app.version:dev}") String version) {
        return new HttpWebhookClient(clock, json, metrics, version);
    }

    @Bean
    FallbackScheduler fallbackScheduler(
            NotificationRepository notifications,
            AppRegistry apps,
            TimeScale timeScale,
            Clock clock,
            ReliablePushProperties properties,
            WebhookClient webhookClient,
            JsonMapper json,
            RpMetrics metrics) {
        // the lease is a product duration (2 "minutes"): scaled by the fast clock like backoff and trigger window
        Duration lease = timeScale.scale(properties.worker().lease());
        boolean fastClock = !timeScale.minuteDuration().equals(Duration.ofMinutes(1));
        checkLease(lease, fastClock, apps.all()).ifPresent(LOG::warn);

        FallbackDispatcher dispatcher =
                new FallbackDispatcher(notifications, apps, timeScale, clock, webhookClient, json, metrics, lease);
        return new FallbackScheduler(notifications, dispatcher, clock, lease, properties.worker().batchSize(),
                properties.worker().maxConcurrency());
    }

    /**
     * Stops the worker before the web server and the connection pool go (SIGTERM): claims nothing more and waits for
     * the calls in flight, at most {@code shutdown-timeout}.
     */
    @Bean
    FallbackWorkerLifecycle fallbackWorkerLifecycle(
            FallbackScheduler scheduler, AppRegistry apps, ReliablePushProperties properties, Environment env) {
        Duration timeout = resolveShutdownTimeout(properties.worker().shutdownTimeout(), apps.all());
        Duration phase = env.getProperty(SHUTDOWN_PHASE_KEY, Duration.class, DEFAULT_SHUTDOWN_PHASE);
        checkShutdownTimeout(timeout, phase);
        return new FallbackWorkerLifecycle(scheduler, timeout);
    }

    private static final String SHUTDOWN_PHASE_KEY = "spring.lifecycle.timeout-per-shutdown-phase";
    private static final Duration DEFAULT_SHUTDOWN_PHASE = Duration.ofSeconds(30);

    /** The configured {@code worker.shutdown-timeout}, else the largest app {@code http-timeout} plus 5 s. */
    static Duration resolveShutdownTimeout(Duration configured, Collection<AppConfig> apps) {
        if (configured != null) {
            return configured;
        }
        Duration longest = apps.stream()
                .map(app -> app.fallback().httpTimeout())
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
        return longest.plusSeconds(5);
    }

    /**
     * Spring cuts a lifecycle phase after {@code timeout-per-shutdown-phase}; a longer worker stop would be abandoned
     * silently and its rows re-sent after the lease.
     *
     * @throws IllegalStateException when {@code shutdownTimeout} exceeds the phase timeout
     */
    static void checkShutdownTimeout(Duration shutdownTimeout, Duration phaseTimeout) {
        if (shutdownTimeout.compareTo(phaseTimeout) > 0) {
            throw new IllegalStateException("reliable-push.worker.shutdown-timeout (" + shutdownTimeout
                    + ") must not exceed " + SHUTDOWN_PHASE_KEY + " (" + phaseTimeout
                    + "); raise the latter or lower the former, otherwise Spring cuts the worker stop and rows are re-sent");
        }
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
