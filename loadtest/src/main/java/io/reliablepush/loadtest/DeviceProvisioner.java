package io.reliablepush.loadtest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Registers the load devices through {@code POST /v1/devices} and returns feeder rows. JDK only. */
public final class DeviceProvisioner {

    static final int MAX_IN_FLIGHT = 50;
    private static final Pattern TOKEN = Pattern.compile("\"deviceToken\"\\s*:\\s*\"([^\"]+)\"");

    /** @return one row {@code {userId, deviceToken}} per device, in index order */
    public List<Map<String, Object>> provision(LoadConfig c) {
        GrantSigner signer = GrantSigner.of(c);
        URI uri = c.apiUrl().resolve("/v1/devices");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < c.devices(); i++) {
                int index = i;
                futures.add(vt.submit(() -> {
                    inFlight.acquire();
                    try {
                        return register(client, uri, c, signer, index);
                    } finally {
                        inFlight.release();
                    }
                }));
            }
            List<Map<String, Object>> rows = new ArrayList<>(c.devices());
            for (Future<Map<String, Object>> f : futures) {
                try {
                    rows.add(f.get());
                } catch (ExecutionException e) {
                    futures.forEach(x -> x.cancel(true));
                    if (e.getCause() instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("Device registration failed: " + e.getCause().getClass().getSimpleName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while provisioning devices");
                }
            }
            return rows;
        }
    }

    private static Map<String, Object> register(HttpClient client, URI uri, LoadConfig c, GrantSigner signer, int index)
            throws IOException, InterruptedException {
        String userId = "load-user-" + index;
        String platform = index % 2 == 0 ? "ANDROID" : "IOS";
        String body = "{\"appId\":" + GrantSigner.quote(c.appId()) + ",\"idToken\":"
                + GrantSigner.quote(signer.sign(userId, Instant.now())) + ",\"platform\":\"" + platform + "\"}";
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 201) {
            // never echo the response body: it could carry tokens
            throw new IllegalStateException("POST /v1/devices returned " + res.statusCode() + " for device index " + index);
        }
        Matcher m = TOKEN.matcher(res.body());
        if (!m.find()) {
            throw new IllegalStateException("POST /v1/devices response without deviceToken for device index " + index);
        }
        return Map.of("userId", userId, "deviceToken", m.group(1));
    }
}
