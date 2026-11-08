package io.reliablepush.devices;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.devices.identity.IdentityRejectedException;
import io.reliablepush.devices.identity.IdentityVerifiers;
import io.reliablepush.devices.identity.VerifiedIdentity;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.shared.ratelimit.RateLimiter;
import io.reliablepush.shared.security.DevicePrincipal;
import io.reliablepush.shared.web.NotFoundException;
import io.reliablepush.shared.web.RetryLaterException;
import io.reliablepush.shared.web.UnauthorizedException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Device registration, token refresh and revocation (spec 4.2, 5). Registration trusts only what the app's identity
 * verifier proves; the client chooses nothing but the platform and, to re-register, a device id that must already
 * belong to the same app and user. Whether a presented token is still honoured ({@link #isActive}) is decided here
 * too, with a short memory ({@code revocation-cache-ttl}) that revocation and re-registration clear on this instance.
 */
@Service
public class DeviceService {

    /** Same text for every rejected credential: the reason goes to the log only. */
    static final String CREDENTIAL_REJECTED = "The credential could not be verified.";
    static final String TOKEN_REJECTED = "A valid device token is required.";
    static final String DEVICE_CHALLENGE = "Bearer realm=\"reliable-push\"";
    static final long IDENTITY_PROVIDER_RETRY_SECONDS = 30;
    private final DeviceRepository repository;
    private final DeviceTokenService tokens;
    private final IdentityVerifiers identities;
    private final AppRegistry apps;
    private final RateLimiter limiter;
    private final Clock clock;
    private final ActiveDeviceCache activeCache;

    DeviceService(
            DeviceRepository repository,
            DeviceTokenService tokens,
            IdentityVerifiers identities,
            AppRegistry apps,
            RateLimiter limiter,
            Clock clock,
            ReliablePushProperties properties) {
        this.repository = repository;
        this.tokens = tokens;
        this.identities = identities;
        this.apps = apps;
        this.limiter = limiter;
        this.clock = clock;
        this.activeCache = new ActiveDeviceCache(clock, properties.deviceToken().revocationCacheTtl());
    }

    public DeviceTokenResponse register(RegisterDeviceRequest request) {
        AppConfig app = apps.byId(request.appId()).orElseThrow(() -> new NotFoundException("Unknown appId."));
        // verify first: a request without a valid credential consumes no bucket that a legitimate user depends on
        String userId = verifyCredential(app, request.idToken()).userId();
        enforceRegisterLimit(app, userId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = tokenExpiry(app, now);

        UUID deviceId = null;
        int version = 0;
        if (request.deviceId() != null) {
            // only a device of this very app and user is reused; any other id (unknown, foreign) is never adopted
            Optional<Integer> bumped = repository.reRegister(
                    request.deviceId(), app.appId(), userId, request.platform(), now, expiresAt);
            if (bumped.isPresent()) {
                deviceId = request.deviceId();
                version = bumped.get();
                activeCache.evict(deviceId);
            }
        }
        if (deviceId == null) {
            deviceId = UUID.randomUUID();
            repository.insert(new Device(deviceId, app.appId(), userId, request.platform(), 0, now, now, null, expiresAt));
        }

        DeviceTokenService.IssuedToken token = issue(app, userId, deviceId, version, now, expiresAt);
        logDevice("device.registered", app.appId(), deviceId, userId);
        return new DeviceTokenResponse(deviceId, token.token(), token.expiresAt());
    }

    /** Issues a new token with the same version; the old one stays valid until its own expiry. */
    public DeviceTokenResponse refresh(DevicePrincipal principal) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // DeviceAuthFilter only admits tokens of configured apps; the registry is immutable, so this never fires
        AppConfig app = apps.byId(principal.appId())
                .orElseThrow(() -> new UnauthorizedException(TOKEN_REJECTED, DEVICE_CHALLENGE));
        Instant expiresAt = tokenExpiry(app, now);
        // conditional on the version of the presented token: a token that lost against a re-registration within the
        // cache TTL cannot be exchanged for a current one
        if (!repository.touchForRefresh(
                principal.deviceId(), principal.appId(), principal.userId(), principal.tokenVersion(), now, expiresAt)) {
            activeCache.evict(principal.deviceId());
            throw new UnauthorizedException(TOKEN_REJECTED, DEVICE_CHALLENGE);
        }
        DeviceTokenService.IssuedToken token =
                issue(app, principal.userId(), principal.deviceId(), principal.tokenVersion(), now, expiresAt);
        logDevice("device.token_refreshed", principal.appId(), principal.deviceId(), principal.userId());
        return new DeviceTokenResponse(null, token.token(), token.expiresAt());
    }

    /**
     * Records that the device of an authenticated request was seen. Conditional on the presented token version, like
     * {@link #refresh}: a token that lost against a re-registration or revocation within the cache TTL is rejected.
     */
    public void markSeen(DevicePrincipal principal) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!repository.touch(principal.deviceId(), principal.appId(), principal.userId(), principal.tokenVersion(), now)) {
            activeCache.evict(principal.deviceId());
            throw new UnauthorizedException(TOKEN_REJECTED, DEVICE_CHALLENGE);
        }
    }

    /** Revokes the device: every token it ever had stops working (on other instances after the cache TTL). */
    public void revoke(DevicePrincipal principal) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // conditional on the presented version, like refresh: a superseded token cannot revoke the current registration
        boolean revoked = repository.revoke(
                principal.deviceId(), principal.appId(), principal.userId(), principal.tokenVersion(), now);
        activeCache.evict(principal.deviceId());
        if (!revoked) {
            throw new UnauthorizedException(TOKEN_REJECTED, DEVICE_CHALLENGE);
        }
        logDevice("device.revoked", principal.appId(), principal.deviceId(), principal.userId());
    }

    /**
     * True if the verified token claims still describe an active device: the row exists for this app and user, is not
     * revoked and has exactly this token version. Positive answers are remembered for {@code revocation-cache-ttl}.
     */
    public boolean isActive(DeviceClaims claims) {
        if (activeCache.isKnownActive(claims.deviceId(), claims.tokenVersion())) {
            return true;
        }
        boolean active = repository.isActive(claims.deviceId(), claims.appId(), claims.userId(), claims.tokenVersion());
        if (active) {
            activeCache.markActive(claims.deviceId(), claims.tokenVersion());
        }
        return active;
    }

    /**
     * Whether the user has at least one active device for the app (sidecar revision spec 2.3): not revoked, and its
     * newest token unexpired or expired for less than the app's {@code device-token-events-grace}. Time comes from the
     * application clock. An unknown app has no devices.
     */
    public boolean hasActiveDevice(String appId, String userId) {
        return apps.byId(appId)
                .map(app -> repository.hasActiveDevice(appId, userId, clock.instant(), app.deviceTokenEventsGrace()))
                .orElse(false);
    }

    /**
     * Registration has no device token yet, so after the credential was verified it is limited per user:
     * {@code register:<appId>:<userHash>} gets the app's {@code device-per-minute} requests per minute. Nothing is
     * consumed before verification, so unauthenticated traffic cannot exhaust a bucket of a legitimate user.
     */
    private void enforceRegisterLimit(AppConfig app, String userId) {
        String userHash = UserHash.of(userId);
        String bucket = "register:" + app.appId() + ":" + userHash;
        if (limiter.tryConsume(bucket, app.rateLimits().devicePerMinute())) {
            return;
        }
        long retryAfter = limiter.retryAfterSeconds(bucket);
        EventLog.warn("rate_limit.exceeded", Map.of(
                "event.outcome", "failure", "rp.app_id", app.appId(), "rp.user_hash", userHash,
                "rate_limit.scope", "register"));
        throw RetryLaterException.tooManyRequests(retryAfter);
    }

    private VerifiedIdentity verifyCredential(AppConfig app, String credential) {
        try {
            return identities.forApp(app).verify(credential);
        } catch (IdentityRejectedException e) {
            EventLog.warn("auth.failed", Map.of(
                    "event.outcome", "failure", "reason", e.reason(), "rp.app_id", app.appId()));
            if (IdentityRejectedException.JWKS_UNAVAILABLE.equals(e.reason())) {
                throw RetryLaterException.serviceUnavailable(
                        "The identity provider is temporarily unavailable.", IDENTITY_PROVIDER_RETRY_SECONDS);
            }
            throw new UnauthorizedException(CREDENTIAL_REJECTED, null);
        }
    }

    /** The {@code exp} of a token issued at {@code now}: whole seconds, exactly what {@link DeviceTokenService} signs. */
    private static Instant tokenExpiry(AppConfig app, Instant now) {
        return now.plus(app.deviceTokenValidity()).truncatedTo(ChronoUnit.SECONDS);
    }

    private DeviceTokenService.IssuedToken issue(
            AppConfig app, String userId, UUID deviceId, int version, Instant now, Instant expiresAt) {
        return tokens.issue(new DeviceClaims(app.appId(), userId, deviceId, version, now, expiresAt));
    }

    private static void logDevice(String action, String appId, UUID deviceId, String userId) {
        EventLog.info(action, Map.of(
                "event.outcome", "success",
                "rp.app_id", appId,
                "rp.device_id", deviceId.toString(),
                "rp.user_hash", UserHash.of(userId)));
    }
}
