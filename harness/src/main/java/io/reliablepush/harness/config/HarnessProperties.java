package io.reliablepush.harness.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
        @NotEmpty Map<String, @NotNull @Valid HarnessApp> apps) {

    public HarnessApp app(String appId) {
        HarnessApp app = apps.get(appId);
        if (app == null) {
            throw new IllegalArgumentException("unknown app: " + appId);
        }
        return app;
    }
}
