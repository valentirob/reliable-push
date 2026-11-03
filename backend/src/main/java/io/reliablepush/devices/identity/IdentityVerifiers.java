package io.reliablepush.devices.identity;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One {@link IdentityVerifier} per app, chosen by {@code identity.type}. Verifiers of every configured app are built
 * at startup so a bad identity configuration (insecure issuer URL, grant secret under 32 bytes) fails the start instead
 * of the first registration; nothing is fetched at startup. {@link #forApp} returns the cached instance, so JWKS
 * caches are shared across requests.
 */
@Component
public class IdentityVerifiers {

    private final Clock clock;
    private final Map<String, IdentityVerifier> byApp = new ConcurrentHashMap<>();

    public IdentityVerifiers(AppRegistry registry, Clock clock) {
        this.clock = clock;
        for (AppConfig app : registry.all()) {
            byApp.put(app.appId(), create(app));
        }
    }

    public IdentityVerifier forApp(AppConfig app) {
        return byApp.computeIfAbsent(app.appId(), id -> create(app));
    }

    private IdentityVerifier create(AppConfig app) {
        AppConfig.Identity identity = app.identity();
        try {
            return switch (identity.type()) {
                case OIDC -> new OidcIdTokenVerifier(identity, clock);
                case SIGNED_GRANT -> {
                    if (identity.grantSecret() == null) {
                        throw new IllegalStateException("identity.grant-secret-ref is required for signed-grant");
                    }
                    yield new SignedGrantVerifier(app.appId(), identity.grantSecret(), clock);
                }
            };
        } catch (IllegalStateException e) {
            throw new IllegalStateException("reliable-push.apps." + app.appId() + ": " + e.getMessage());
        }
    }
}
