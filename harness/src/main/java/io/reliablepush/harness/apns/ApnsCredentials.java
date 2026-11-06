package io.reliablepush.harness.apns;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * An APNs token-based auth key placed by the maintainer: {@code AuthKey.p8} (PKCS#8 EC P-256 PEM) and
 * {@code apns.properties} ({@code keyId}, {@code teamId}, {@code topic} = the sample's bundle id, {@code environment}
 * = {@code sandbox} or {@code production}). {@link #toString()} shows only key id, topic and environment; error
 * messages never carry file content.
 */
public final class ApnsCredentials {

    private static final Pattern APPLE_ID = Pattern.compile("^[A-Z0-9]{10}$");
    private static final Pattern TOPIC = Pattern.compile("^[A-Za-z0-9.-]{1,155}$");

    private final String keyId;
    private final String teamId;
    private final String topic;
    private final String environment;
    private final PrivateKey privateKey;

    private ApnsCredentials(String keyId, String teamId, String topic, String environment, PrivateKey privateKey) {
        this.keyId = keyId;
        this.teamId = teamId;
        this.topic = topic;
        this.environment = environment;
        this.privateKey = privateKey;
    }

    /**
     * Reads the directory. A missing or incomplete one (no key file, no properties file, a missing property) is
     * {@code Optional.empty()}: real APNs mode stays off.
     *
     * @throws IllegalArgumentException when the files exist but hold an unusable value (the message names the
     *                                  problem, never the content)
     */
    public static Optional<ApnsCredentials> load(Path dir) {
        if (dir == null) {
            return Optional.empty();
        }
        Path p8 = dir.resolve("AuthKey.p8");
        Path props = dir.resolve("apns.properties");
        if (!Files.isRegularFile(p8) || !Files.isRegularFile(props)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(props)) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("apns.properties is not readable");
        }
        String keyId = p.getProperty("keyId", "").trim();
        String teamId = p.getProperty("teamId", "").trim();
        String topic = p.getProperty("topic", "").trim();
        String environment = p.getProperty("environment", "").trim();
        if (keyId.isEmpty() || teamId.isEmpty() || topic.isEmpty() || environment.isEmpty()) {
            return Optional.empty();
        }
        if (!APPLE_ID.matcher(keyId).matches() || !APPLE_ID.matcher(teamId).matches()) {
            throw new IllegalArgumentException("apns.properties keyId and teamId must be 10 characters (A-Z, 0-9)");
        }
        if (!TOPIC.matcher(topic).matches()) {
            throw new IllegalArgumentException("apns.properties topic is not a bundle id");
        }
        if (!environment.equals("sandbox") && !environment.equals("production")) {
            throw new IllegalArgumentException("apns.properties environment must be sandbox or production");
        }
        String pem;
        try {
            pem = Files.readString(p8);
        } catch (IOException e) {
            throw new IllegalArgumentException("AuthKey.p8 is not readable");
        }
        return Optional.of(new ApnsCredentials(keyId, teamId, topic, environment, parseKey(pem)));
    }

    private static PrivateKey parseKey(String pem) {
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
            if (!(key instanceof ECPrivateKey ec) || ec.getParams().getOrder().bitLength() != 256) {
                throw new IllegalArgumentException("AuthKey.p8 is not a PKCS#8 EC P-256 key");
            }
            return key;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("AuthKey.p8 is not a PKCS#8 EC P-256 key");
        }
    }

    String keyId() {
        return keyId;
    }

    String teamId() {
        return teamId;
    }

    String topic() {
        return topic;
    }

    String environment() {
        return environment;
    }

    PrivateKey privateKey() {
        return privateKey;
    }

    URI host() {
        return URI.create(environment.equals("production") ? "https://api.push.apple.com" : "https://api.sandbox.push.apple.com");
    }

    @Override
    public String toString() {
        return "ApnsCredentials[keyId=" + keyId + ", topic=" + topic + ", environment=" + environment + "]";
    }
}
