package io.reliablepush.harness.config;

import jakarta.validation.constraints.NotBlank;

/** Per-app credentials the harness plays the role of: the sender backend, the app and the device. */
public record HarnessApp(
        @NotBlank String senderApiKey,
        @NotBlank String grantSecret,
        @NotBlank String hmacSecret,
        @NotBlank String grantIssuer) {

    @Override
    public String toString() {
        return "HarnessApp[senderApiKey=***, grantSecret=***, hmacSecret=***, grantIssuer=" + grantIssuer + "]";
    }
}
