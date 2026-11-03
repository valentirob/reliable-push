package io.reliablepush.shared.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Pseudonymises user ids for logs (spec 9.1): raw user ids are never logged. */
public final class UserHash {

    private static final int HEX_CHARS = 16;

    private UserHash() {}

    /** First 16 lowercase hex characters of SHA-256 over the UTF-8 bytes of {@code userId}. */
    public static String of(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
