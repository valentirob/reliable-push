package io.reliablepush.harness.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Per-app credentials the harness plays the role of: the sender backend, the app and the device. */
public record HarnessApp(
        @NotBlank String senderApiKey,
        @NotBlank String grantSecret,
        @NotBlank String hmacSecret,
        @NotBlank String grantIssuer,
        @Positive Integer triggerWindowMinutes) {

    /** The backend's default {@code fallback.trigger-window-minutes}. */
    public static final int DEFAULT_WINDOW_MINUTES = 20;

    @ConstructorBinding
    public HarnessApp {
    }

    public HarnessApp(String senderApiKey, String grantSecret, String hmacSecret, String grantIssuer) {
        this(senderApiKey, grantSecret, hmacSecret, grantIssuer, null);
    }

    /** The app's fallback trigger window in product minutes (console countdowns); optional, default 20. */
    public int windowMinutes() {
        return triggerWindowMinutes == null ? DEFAULT_WINDOW_MINUTES : triggerWindowMinutes;
    }

    @Override
    public String toString() {
        return "HarnessApp[senderApiKey=***, grantSecret=***, hmacSecret=***, grantIssuer=" + grantIssuer + ", triggerWindowMinutes="
                + windowMinutes() + "]";
    }
}
