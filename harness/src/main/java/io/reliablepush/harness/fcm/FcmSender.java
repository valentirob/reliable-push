package io.reliablepush.harness.fcm;

import io.reliablepush.harness.sender.PayloadPreview;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Sends a tracked push (Android mode v1: data-only, high priority) through the FCM HTTP v1 API. The registration
 * token, the access token and the key never reach a log line or an exception message.
 */
public final class FcmSender {

    private static final Logger LOG = LogManager.getLogger(FcmSender.class);
    static final URI FCM_BASE = URI.create("https://fcm.googleapis.com");

    private final FcmCredentials credentials;
    private final GoogleAccessTokens tokens;
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public FcmSender(FcmCredentials credentials) {
        this(credentials, new GoogleAccessTokens(credentials), FCM_BASE);
    }

    /** Test seam: another FCM base URL and token source. */
    FcmSender(FcmCredentials credentials, GoogleAccessTokens tokens, URI base) {
        this.credentials = credentials;
        this.tokens = tokens;
        this.base = base;
    }

    public String projectId() {
        return credentials.projectId();
    }

    /** Never throws: any failure is {@code FAILED(0)}, so the caller keeps its tracking id. */
    public FcmResult send(String fcmToken, UUID trackingId, String title, String body) {
        try {
            return doSend(fcmToken, trackingId, title, body);
        } catch (RuntimeException e) {
            LOG.warn("fcm.send failed trackingId={} reason=error type={}", trackingId, e.getClass().getSimpleName());
            return FcmResult.failed(0);
        }
    }

    private FcmResult doSend(String fcmToken, UUID trackingId, String title, String body) {
        String accessToken;
        try {
            accessToken = tokens.accessToken();
        } catch (IOException e) {
            LOG.warn("fcm.send failed trackingId={} reason=no_access_token", trackingId);
            return FcmResult.failed(0);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/v1/projects/" + credentials.projectId()
                        + "/messages:send"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(PayloadPreview.fcmV1(trackingId, title, body, fcmToken)))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOG.warn("fcm.send failed trackingId={} reason=unreachable", trackingId);
            return FcmResult.failed(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FcmResult.failed(0);
        }
        int status = res.statusCode();
        FcmResult result;
        if (status == 200) {
            result = FcmResult.sent();
        } else if (status == 404 || (res.body() != null && res.body().contains("\"UNREGISTERED\""))) {
            result = FcmResult.unregistered();
        } else {
            if (status == 401) {
                tokens.invalidate();
            }
            result = FcmResult.failed(status);
        }
        LOG.info("fcm.send trackingId={} outcome={} status={}", trackingId, result.outcome(), status);
        return result;
    }

    @Override
    public String toString() {
        return "FcmSender[project=" + credentials.projectId() + "]";
    }
}
