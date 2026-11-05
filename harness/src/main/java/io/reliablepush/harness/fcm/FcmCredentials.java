package io.reliablepush.harness.fcm;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Google service-account key file ({@code project_id}, {@code client_email}, {@code private_key} PKCS#8 PEM,
 * {@code token_uri}), placed by the maintainer. Nothing but the project id is ever printed: {@link #toString()}
 * redacts the rest, and error messages never carry file content.
 */
public final class FcmCredentials {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A Google Cloud project id: it goes into the FCM URL path, so nothing else is accepted. */
    private static final java.util.regex.Pattern PROJECT_ID = java.util.regex.Pattern.compile("^[a-z][a-z0-9-]{4,28}[a-z0-9]$");

    private final String projectId;
    private final String clientEmail;
    private final PrivateKey privateKey;
    private final URI tokenUri;

    private FcmCredentials(String projectId, String clientEmail, PrivateKey privateKey, URI tokenUri) {
        this.projectId = projectId;
        this.clientEmail = clientEmail;
        this.privateKey = privateKey;
        this.tokenUri = tokenUri;
    }

    /**
     * Reads the key file. An absent file is {@code Optional.empty()} (real FCM mode stays off).
     *
     * @throws IllegalArgumentException when the file exists but is not a usable service-account key (the message names
     *                                  the problem, never the content)
     */
    public static Optional<FcmCredentials> load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        JsonNode json;
        try {
            json = JSON.readTree(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("the service-account file is not readable JSON");
        }
        String project = text(json, "project_id");
        if (!PROJECT_ID.matcher(project).matches()) {
            throw new IllegalArgumentException("the service-account project_id is not a valid Google project id");
        }
        String email = text(json, "client_email");
        String pem = text(json, "private_key");
        String tokenUri = text(json, "token_uri");
        return Optional.of(new FcmCredentials(project, email, parseKey(pem), URI.create(tokenUri)));
    }

    private static String text(JsonNode json, String field) {
        JsonNode n = json == null ? null : json.get(field);
        if (n == null || !n.isString() || n.asString().isBlank()) {
            throw new IllegalArgumentException("the service-account file has no " + field);
        }
        return n.asString();
    }

    private static PrivateKey parseKey(String pem) {
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("the service-account private_key is not a PKCS#8 RSA key");
        }
    }

    public String projectId() {
        return projectId;
    }

    String clientEmail() {
        return clientEmail;
    }

    PrivateKey privateKey() {
        return privateKey;
    }

    URI tokenUri() {
        return tokenUri;
    }

    @Override
    public String toString() {
        return "FcmCredentials[projectId=" + projectId + ", clientEmail=<redacted>, privateKey=<redacted>]";
    }
}
