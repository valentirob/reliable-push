package io.reliablepush.harness.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Binds {@code harness.*}: where the backend is, where its JSON log file is, and the simulated apps. */
@ConfigurationProperties("harness")
@Validated
public record HarnessProperties(
        @NotNull URI backendUrl,
        @NotNull Path backendLogDir,
        @NotEmpty Map<String, @NotNull @Valid HarnessApp> apps,
        @Positive Integer minuteSeconds,
        URI deviceProxyTarget,
        @Positive Integer deviceProxyPort,
        String deviceProxyBind) {

    /** Address the device-traffic proxy binds when none is set: loopback, so it is never reachable from the LAN (M12). */
    public static final String DEFAULT_DEVICE_PROXY_BIND = "127.0.0.1";

    /** Port the device-traffic proxy listens on inside the container. */
    public static final int DEFAULT_DEVICE_PROXY_PORT = 8091;

    /** One backend "minute" in seconds unless the fast clock is on ({@code harness.minute-seconds=1}). */
    public static final int DEFAULT_MINUTE_SECONDS = 60;

    @ConstructorBinding
    public HarnessProperties {
    }

    public HarnessProperties(URI backendUrl, Path backendLogDir, Map<String, HarnessApp> apps) {
        this(backendUrl, backendLogDir, apps, null, null, null, null);
    }

    public HarnessProperties(URI backendUrl, Path backendLogDir, Map<String, HarnessApp> apps, Integer minuteSeconds) {
        this(backendUrl, backendLogDir, apps, minuteSeconds, null, null, null);
    }

    /** Address the proxy listens on: {@code harness.device-proxy-bind}, loopback when unset or blank. Compose sets
     *  {@code 0.0.0.0} because Docker forwards the published port to the container's own interface. */
    public String proxyBindAddress() {
        return deviceProxyBind == null || deviceProxyBind.isBlank() ? DEFAULT_DEVICE_PROXY_BIND : deviceProxyBind.strip();
    }

    public int proxyListenPort() {
        return deviceProxyPort == null ? DEFAULT_DEVICE_PROXY_PORT : deviceProxyPort;
    }

    /** Seconds per backend minute, for the console countdowns (must match the backend's fast clock). */
    public int timeScaleSeconds() {
        return minuteSeconds == null ? DEFAULT_MINUTE_SECONDS : minuteSeconds;
    }

    public HarnessApp app(String appId) {
        HarnessApp app = apps.get(appId);
        if (app == null) {
            throw new IllegalArgumentException("unknown app: " + appId);
        }
        return app;
    }
}
