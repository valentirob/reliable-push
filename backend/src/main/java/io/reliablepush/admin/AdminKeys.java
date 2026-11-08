package io.reliablepush.admin;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The configured admin keys, validated once at startup (an {@link IllegalStateException} naming the key id fails the
 * start). Lookup hashes the raw key with SHA-256 and compares it with {@link MessageDigest#isEqual} against every
 * key, without early exit. Raw keys are never stored or logged.
 */
@Component
public class AdminKeys {

    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,40}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final String RESERVED_ID = "false";
    private static final String ALL_APPS = "*";
    private static final String PERMISSION_PAYLOAD = "payload";
    private static final String PERMISSION_FALLBACK_CONTROL = "fallback-control";

    private record Entry(byte[] hash, AdminPrincipal principal) {}

    private final List<Entry> entries;
    private final int ratePerMinute;

    public AdminKeys(AdminProperties properties, AppRegistry apps) {
        Set<String> appIds = new HashSet<>();
        for (AppConfig app : apps.all()) {
            appIds.add(app.appId());
        }
        Set<String> ids = new HashSet<>();
        List<Entry> list = new ArrayList<>();
        for (AdminProperties.Key key : properties.keys()) {
            String id = key.id();
            String where = "reliable-push.admin.keys[" + id + "]";
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalStateException("reliable-push.admin.keys: id '" + id + "' must match " + ID.pattern());
            }
            if (RESERVED_ID.equalsIgnoreCase(id)) {
                // the admin beans are gated by @ConditionalOnProperty on keys[0].id, which reads "false" as off
                throw new IllegalStateException(
                        "reliable-push.admin.keys: id 'false' is reserved (it would switch the admin beans off)");
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("reliable-push.admin.keys: duplicate id " + id);
            }
            if (key.keySha256() == null || !HASH.matcher(key.keySha256()).matches()) {
                throw new IllegalStateException(where + ".key-sha256 must be 64 lowercase hex characters (SHA-256)");
            }
            byte[] hash = HexFormat.of().parseHex(key.keySha256());
            for (Entry other : list) {
                if (MessageDigest.isEqual(other.hash(), hash)) {
                    throw new IllegalStateException(where + ".key-sha256 equals the one of key "
                            + other.principal().keyId() + "; every key needs its own hash");
                }
            }
            if (apps.isSenderKeyHash(hash)) {
                throw new IllegalStateException(where + ".key-sha256 equals a sender key hash; admin keys must differ");
            }
            Set<String> visible = resolveApps(where, key.apps(), appIds);
            for (String permission : key.permissions()) {
                if (!PERMISSION_PAYLOAD.equals(permission) && !PERMISSION_FALLBACK_CONTROL.equals(permission)) {
                    throw new IllegalStateException(where + ".permissions: unknown permission '" + permission + "'");
                }
            }
            boolean payload = key.permissions().contains(PERMISSION_PAYLOAD);
            boolean fallbackControl = key.permissions().contains(PERMISSION_FALLBACK_CONTROL);
            list.add(new Entry(hash, new AdminPrincipal(id, visible, payload, fallbackControl)));
        }
        this.entries = List.copyOf(list);
        this.ratePerMinute = properties.rateLimitPerMinute();
    }

    private static Set<String> resolveApps(String where, List<String> configured, Set<String> appIds) {
        if (configured.isEmpty()) {
            throw new IllegalStateException(where + ".apps must not be empty");
        }
        if (configured.contains(ALL_APPS)) {
            if (configured.size() > 1) {
                throw new IllegalStateException(where + ".apps: \"*\" cannot be mixed with app ids");
            }
            return Set.copyOf(appIds);
        }
        for (String appId : configured) {
            if (!appIds.contains(appId)) {
                throw new IllegalStateException(where + ".apps: unknown app '" + appId + "'");
            }
        }
        return Set.copyOf(configured);
    }

    public boolean enabled() {
        return !entries.isEmpty();
    }

    public int ratePerMinute() {
        return ratePerMinute;
    }

    public Optional<AdminPrincipal> byRawKey(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return Optional.empty();
        }
        byte[] hash = sha256(rawKey);
        AdminPrincipal found = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(hash, entry.hash())) {
                found = entry.principal();
            }
        }
        return Optional.ofNullable(found);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
