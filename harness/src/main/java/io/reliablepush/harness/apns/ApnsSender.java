package io.reliablepush.harness.apns;

import io.reliablepush.harness.sender.PayloadPreview;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Sends a tracked alert push (spec 6: {@code mutable-content}, {@code rp_tid}) to the APNs HTTP/2 provider API. The
 * device token, the provider token and the key never reach a log line or an exception message.
 */
public final class ApnsSender {

    private static final Logger LOG = LogManager.getLogger(ApnsSender.class);
    private static final Pattern DEVICE_TOKEN = Pattern.compile("^[0-9a-fA-F]{8,200}$");
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([A-Za-z]{1,64})\"");

    private final ApnsCredentials credentials;
    private final ApnsProviderToken tokens;
    private final URI host;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public ApnsSender(ApnsCredentials credentials) {
        this(credentials, new ApnsProviderToken(credentials), credentials.host());
    }

    /** Test seam: another host (a local stub; never Apple) and token source. */
    ApnsSender(ApnsCredentials credentials, ApnsProviderToken tokens, URI host) {
        this.credentials = credentials;
        this.tokens = tokens;
        this.host = host;
    }

    public String environment() {
        return credentials.environment();
    }

    /** Never throws: any failure is {@code FAILED(0)}, so the caller keeps its tracking id. */
    public ApnsResult send(String deviceToken, UUID trackingId, String title, String body) {
        try {
            return doSend(deviceToken, trackingId, title, body);
        } catch (RuntimeException e) {
            LOG.warn("apns.send failed trackingId={} reason=error type={}", trackingId, e.getClass().getSimpleName());
            return ApnsResult.failed(0, null);
        }
    }

    private ApnsResult doSend(String deviceToken, UUID trackingId, String title, String body) {
        if (deviceToken == null || !DEVICE_TOKEN.matcher(deviceToken).matches()) {
            LOG.warn("apns.send failed trackingId={} reason=bad_device_token_format", trackingId);
            return ApnsResult.failed(0, null);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/3/device/" + deviceToken))
                .timeout(Duration.ofSeconds(10))
                .header("authorization", "bearer " + tokens.token())
                .header("apns-topic", credentials.topic())
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("apns-expiration", "0")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(PayloadPreview.apns(trackingId, title, body)))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOG.warn("apns.send failed trackingId={} reason=unreachable", trackingId);
            return ApnsResult.failed(0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApnsResult.failed(0, null);
        }
        int status = res.statusCode();
        String reason = reason(res.body());
        ApnsResult result;
        if (status == 200) {
            result = ApnsResult.sent();
        } else if (status == 410 || (status == 400 && "BadDeviceToken".equals(reason))) {
            result = ApnsResult.unregistered(status);
        } else {
            if (status == 403) {
                tokens.invalidate();
            }
            result = ApnsResult.failed(status, reason);
        }
        LOG.info("apns.send trackingId={} outcome={} status={} reason={}", trackingId, result.outcome(), status, result.reason());
        return result;
    }

    private static String reason(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = REASON.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public String toString() {
        return "ApnsSender[topic=" + credentials.topic() + ", environment=" + credentials.environment() + "]";
    }
}
