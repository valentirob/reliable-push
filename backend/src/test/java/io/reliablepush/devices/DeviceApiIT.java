package io.reliablepush.devices;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.shared.security.DevicePrincipal;
import io.reliablepush.shared.web.UnauthorizedException;
import io.reliablepush.support.FakeOidc;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Device API and device authentication end to end: a real PostgreSQL and a WireMock identity provider ({@link FakeOidc})
 * for the apps {@code deviceit}, {@code deviceother}, {@code deviceregister}, {@code devicelimited} and {@code devicedown} of application-test.yml. The database is
 * shared with other ITs: every test uses fresh user ids. {@code revocation-cache-ttl} is {@code 0s} in the test profile,
 * so a revocation is visible to the next request.
 */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class DeviceApiIT extends PostgresTestBase {

    private static final String APP = "deviceit";
    private static final String AUDIENCE = "shop-mobile-it";
    private static final String LIMITED_APP = "devicelimited";
    private static final String LIMITED_AUDIENCE = "shop-mobile-limited";
    private static final String REGISTER_APP = "deviceregister";
    private static final String REGISTER_AUDIENCE = "shop-mobile-register";
    private static final String OTHER_APP = "deviceother";
    private static final String OTHER_AUDIENCE = "shop-mobile-other";
    private static final String DOWN_APP = "devicedown";
    private static final String DOWN_AUDIENCE = "shop-mobile-down";

    private static final FakeOidc IDP = new FakeOidc();
    private static final FakeOidc DOWN_IDP = new FakeOidc();

    static {
        DOWN_IDP.respondToJwksWith(aResponse().withStatus(500));
    }

    @DynamicPropertySource
    static void identityProviders(DynamicPropertyRegistry registry) {
        registry.add("reliable-push.apps." + APP + ".identity.issuer", IDP::issuer);
        registry.add("reliable-push.apps." + LIMITED_APP + ".identity.issuer", IDP::issuer);
        registry.add("reliable-push.apps." + REGISTER_APP + ".identity.issuer", IDP::issuer);
        registry.add("reliable-push.apps." + OTHER_APP + ".identity.issuer", IDP::issuer);
        registry.add("reliable-push.apps." + DOWN_APP + ".identity.issuer", DOWN_IDP::issuer);
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeviceTokenService tokens;

    @Autowired
    DeviceService devices;

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static String uniqueUser() {
        return "user-" + UUID.randomUUID();
    }

    private static String idToken(String userId) {
        return IDP.idToken(userId, AUDIENCE, Duration.ofMinutes(5));
    }

    private ResponseEntity<String> register(String appId, String idToken, String platform, String deviceId) {
        String body = "{\"appId\":\"" + appId + "\",\"idToken\":\"" + idToken + "\",\"platform\":\"" + platform + "\""
                + (deviceId == null ? "" : ",\"deviceId\":\"" + deviceId + "\"") + "}";
        return post("/v1/devices", body);
    }

    private ResponseEntity<String> registerUser(String userId, String deviceId) {
        return register(APP, idToken(userId), "ANDROID", deviceId);
    }

    private ResponseEntity<String> post(String path, String body) {
        return http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private ResponseEntity<String> refresh(String bearer) {
        return http.post()
                .uri("/v1/devices/token:refresh")
                .headers(h -> {
                    if (bearer != null) {
                        h.set(HttpHeaders.AUTHORIZATION, bearer);
                    }
                })
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private ResponseEntity<String> revoke(String bearer) {
        return http.delete()
                .uri("/v1/devices/me")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private static String bearer(ResponseEntity<String> registered) {
        return "Bearer " + JsonPath.read(registered.getBody(), "$.deviceToken");
    }

    private static String deviceId(ResponseEntity<String> registered) {
        return JsonPath.read(registered.getBody(), "$.deviceId");
    }

    private static void assertProblem(ResponseEntity<String> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.status")).isEqualTo(status);
    }

    private static List<String> lines(CapturedOutput output, String action) {
        return output.getOut().lines().filter(l -> l.contains("\"event.action\":\"" + action + "\"")).toList();
    }

    private static String reasonOfLastAuthFailure(CapturedOutput output) {
        List<String> failures = lines(output, "auth.failed");
        return JsonPath.read(failures.get(failures.size() - 1), "$.reason");
    }

    // ---- register ----------------------------------------------------------------------------------------------

    @Test
    void registerWithValidIdTokenReturns201(CapturedOutput output) {
        String userId = uniqueUser();
        String idToken = idToken(userId);

        ResponseEntity<String> response = register(APP, idToken, "IOS", null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String deviceId = deviceId(response);
        String deviceToken = JsonPath.read(response.getBody(), "$.deviceToken");
        Instant expiresAt = Instant.parse(JsonPath.read(response.getBody(), "$.expiresAt"));
        assertThat(expiresAt).isBetween(Instant.now().plus(Duration.ofDays(90)).minusSeconds(60),
                Instant.now().plus(Duration.ofDays(90)).plusSeconds(60));
        DeviceClaims claims = tokens.verify(deviceToken);
        assertThat(claims.appId()).isEqualTo(APP);
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.deviceId()).isEqualTo(UUID.fromString(deviceId));
        assertThat(claims.tokenVersion()).isZero();
        assertThat(claims.expiresAt()).isEqualTo(expiresAt);
        assertThat(jdbc.sql("select platform from device where id = :id").param("id", UUID.fromString(deviceId))
                        .query(String.class).single())
                .isEqualTo("IOS");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lines(output, "device.registered"))
                .anySatisfy(line -> {
                    assertThat(JsonPath.<String>read(line, "$['rp.device_id']")).isEqualTo(deviceId);
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo(APP);
                    assertThat(JsonPath.<String>read(line, "$['rp.user_hash']")).isEqualTo(UserHash.of(userId));
                    assertThat(JsonPath.<String>read(line, "$['event.outcome']")).isEqualTo("success");
                }));
        assertThat(output.getAll()).doesNotContain(userId).doesNotContain(idToken).doesNotContain(deviceToken);
    }

    @Test
    void registerWithBadIdToken401(CapturedOutput output) {
        String userId = uniqueUser();
        String expired = IDP.idToken(userId, AUDIENCE, Duration.ofMinutes(-10));
        String wrongAudience = IDP.idToken(userId, "some-other-app", Duration.ofMinutes(5));
        String signedByA = idToken(userId);
        String signedByB = idToken(uniqueUser());
        String tampered = signedByA.substring(0, signedByA.lastIndexOf('.') + 1) + signedByB.substring(signedByB.lastIndexOf('.') + 1);
        String foreign = FakeOidc.sign(IDP.newRsaKey(), com.nimbusds.jose.JWSAlgorithm.RS256,
                IDP.claims(userId, AUDIENCE, Duration.ofMinutes(5)).build());

        ResponseEntity<String> badSignature = register(APP, tampered, "IOS", null);
        assertProblem(register(APP, foreign, "IOS", null), 401);
        assertProblem(badSignature, 401);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(lines(output, "auth.failed")).isNotEmpty());
        ResponseEntity<String> expiredResponse = register(APP, expired, "IOS", null);
        ResponseEntity<String> audienceResponse = register(APP, wrongAudience, "IOS", null);
        ResponseEntity<String> garbage = register(APP, "not-a-jwt", "IOS", null);

        for (ResponseEntity<String> response : List.of(expiredResponse, audienceResponse, garbage)) {
            assertProblem(response, 401);
        }
        // the body is the same whatever the reason, so it does not tell an attacker what was wrong
        assertThat(JsonPath.<String>read(expiredResponse.getBody(), "$.detail"))
                .isEqualTo(JsonPath.read(badSignature.getBody(), "$.detail"))
                .isEqualTo(JsonPath.read(audienceResponse.getBody(), "$.detail"))
                .isEqualTo(JsonPath.read(garbage.getBody(), "$.detail"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> reasons = lines(output, "auth.failed").stream()
                    .map(line -> JsonPath.<String>read(line, "$.reason")).toList();
            assertThat(reasons).contains("unknown_kid", "bad_signature", "expired", "wrong_audience", "malformed");
        });
        assertThat(output.getAll()).doesNotContain(userId).doesNotContain(expired).doesNotContain(foreign)
                .doesNotContain(tampered);
        assertThat(jdbc.sql("select count(*) from device where user_id = :u").param("u", userId).query(Integer.class).single())
                .isZero();
    }

    @Test
    void unknownApp404() {
        ResponseEntity<String> response = register("no-such-app", idToken(uniqueUser()), "IOS", null);

        assertProblem(response, 404);
        OpenApiAssert.assertMatchesContract("POST", "/v1/devices", response);
    }

    @Test
    void invalidBodiesAreClientErrors() {
        assertProblem(post("/v1/devices", "{\"appId\":\"" + APP + "\",\"idToken\":\"x\"}"), 422); // platform missing
        assertProblem(post("/v1/devices", "{\"appId\":\"" + APP + "\",\"idToken\":\"\",\"platform\":\"IOS\"}"), 422);
        assertProblem(post("/v1/devices", "{\"appId\":\"" + APP + "\",\"idToken\":\"x\",\"platform\":\"WINDOWS\"}"), 400);
        assertProblem(post("/v1/devices", "{\"appId\":\"" + APP + "\",\"idToken\":\"x\",\"platform\":\"IOS\",\"deviceId\":\"nope\"}"), 400);
        assertProblem(post("/v1/devices", "{broken"), 400);
    }

    @Test
    void identityProviderOutage503WithRetryAfter(CapturedOutput output) {
        String idToken = IDP.idToken(uniqueUser(), DOWN_AUDIENCE, Duration.ofMinutes(5));

        ResponseEntity<String> response = register(DOWN_APP, IDP.sign(
                IDP.claims(uniqueUser(), DOWN_AUDIENCE, Duration.ofMinutes(5)).issuer(DOWN_IDP.issuer()).build()), "IOS", null);

        assertProblem(response, 503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        OpenApiAssert.assertMatchesContract("POST", "/v1/devices", response);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lines(output, "auth.failed"))
                .anySatisfy(line -> assertThat(JsonPath.<String>read(line, "$.reason")).isEqualTo("jwks_unavailable")));
        assertThat(output.getAll()).doesNotContain(idToken);
    }

    @Test
    void registerRateLimitedPerUser(CapturedOutput output) {
        // the register bucket is the app's device-per-minute (1 for this app) per user, counted after verification
        String userId = uniqueUser();
        String firstToken = IDP.idToken(userId, REGISTER_AUDIENCE, Duration.ofMinutes(5));
        assertThat(register(REGISTER_APP, firstToken, "IOS", null).getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> limited = register(REGISTER_APP, IDP.idToken(userId, REGISTER_AUDIENCE, Duration.ofMinutes(5)), "IOS", null);

        assertProblem(limited, 429);
        assertThat(Long.parseLong(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
        OpenApiAssert.assertMatchesContract("POST", "/v1/devices", limited);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lines(output, "rate_limit.exceeded"))
                .anySatisfy(line -> {
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo(REGISTER_APP);
                    assertThat(JsonPath.<String>read(line, "$['rate_limit.scope']")).isEqualTo("register");
                    assertThat(JsonPath.<String>read(line, "$['rp.user_hash']")).isEqualTo(UserHash.of(userId));
                }));
        assertThat(output.getAll()).doesNotContain(userId);
        // other users of the same app are not affected
        assertThat(register(REGISTER_APP, IDP.idToken(uniqueUser(), REGISTER_AUDIENCE, Duration.ofMinutes(5)), "IOS", null)
                        .getStatusCode().value())
                .isEqualTo(201);
    }

    @Test
    void invalidCredentialsConsumeNoRegistrationBudgetOfOtherUsers() {
        // far more bogus registrations than any bucket of this app allows (1 per minute per user)
        for (int i = 0; i < 15; i++) {
            String bogus = i % 2 == 0 ? "not-a-jwt" : IDP.idToken(uniqueUser(), "some-other-app", Duration.ofMinutes(5));
            assertProblem(register(REGISTER_APP, bogus, "IOS", null), 401);
        }

        ResponseEntity<String> legitimate = register(REGISTER_APP, IDP.idToken(uniqueUser(), REGISTER_AUDIENCE, Duration.ofMinutes(5)), "IOS", null);

        assertThat(legitimate.getStatusCode().value()).isEqualTo(201);
    }

    // ---- re-registration ---------------------------------------------------------------------------------------

    @Test
    void reRegisterSameDeviceInvalidatesOldToken() {
        String userId = uniqueUser();
        ResponseEntity<String> first = registerUser(userId, null);
        String deviceId = deviceId(first);
        assertThat(refresh(bearer(first)).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> second = register(APP, idToken(userId), "IOS", deviceId);

        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(deviceId(second)).isEqualTo(deviceId);
        ResponseEntity<String> oldToken = refresh(bearer(first));
        assertProblem(oldToken, 401);
        assertThat(refresh(bearer(second)).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.sql("select token_version from device where id = :id").param("id", UUID.fromString(deviceId))
                        .query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select platform from device where id = :id").param("id", UUID.fromString(deviceId))
                        .query(String.class).single())
                .isEqualTo("IOS");
    }

    @Test
    void reRegisterRestoresARevokedDevice() {
        String userId = uniqueUser();
        ResponseEntity<String> first = registerUser(userId, null);
        assertThat(revoke(bearer(first)).getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> again = registerUser(userId, deviceId(first));

        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(deviceId(again)).isEqualTo(deviceId(first));
        assertThat(refresh(bearer(again)).getStatusCode().value()).isEqualTo(200);
        assertProblem(refresh(bearer(first)), 401);
    }

    @Test
    void deviceIdOfOtherUserNotHijacked() {
        ResponseEntity<String> alice = registerUser(uniqueUser(), null);
        String aliceDevice = deviceId(alice);

        ResponseEntity<String> mallory = registerUser(uniqueUser(), aliceDevice);

        assertThat(mallory.getStatusCode().value()).isEqualTo(201);
        assertThat(deviceId(mallory)).isNotEqualTo(aliceDevice);
        // alice's device is untouched: its token still works and its version did not change
        assertThat(refresh(bearer(alice)).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.sql("select token_version from device where id = :id").param("id", UUID.fromString(aliceDevice))
                        .query(Integer.class).single())
                .isZero();
        // nor can the id of another app be taken over; an unknown id is never adopted either
        String unknownId = UUID.randomUUID().toString();
        ResponseEntity<String> unknown = registerUser(uniqueUser(), unknownId);
        assertThat(deviceId(unknown)).isNotEqualTo(unknownId);
        ResponseEntity<String> otherApp = register(OTHER_APP, IDP.idToken(JsonPath.<String>read(
                        new String(java.util.Base64.getUrlDecoder().decode(
                                JsonPath.<String>read(alice.getBody(), "$.deviceToken").split("\\.")[1])), "$.sub"),
                OTHER_AUDIENCE, Duration.ofMinutes(5)), "IOS", aliceDevice);
        assertThat(otherApp.getStatusCode().value()).isEqualTo(201);
        assertThat(deviceId(otherApp)).isNotEqualTo(aliceDevice);
    }

    // ---- refresh and revoke ------------------------------------------------------------------------------------

    @Test
    void refreshReturnsNewTokenAndOldStillValidUntilExpiry() {
        ResponseEntity<String> registered = registerUser(uniqueUser(), null);
        UUID deviceId = UUID.fromString(deviceId(registered));
        Instant seenBefore = jdbc.sql("select last_seen_at from device where id = :id").param("id", deviceId)
                .query(java.sql.Timestamp.class).single().toInstant();

        ResponseEntity<String> refreshed = refresh(bearer(registered));

        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
        String newToken = JsonPath.read(refreshed.getBody(), "$.deviceToken");
        assertThat(newToken).isNotEqualTo(JsonPath.<String>read(registered.getBody(), "$.deviceToken"));
        assertThat(refreshed.getBody()).as("refresh answers {deviceToken, expiresAt}").doesNotContain("deviceId");
        DeviceClaims claims = tokens.verify(newToken);
        assertThat(claims.deviceId()).isEqualTo(deviceId);
        assertThat(claims.tokenVersion()).isZero();
        assertThat(Instant.parse(JsonPath.read(refreshed.getBody(), "$.expiresAt")))
                .isBetween(Instant.now().plus(Duration.ofDays(90)).minusSeconds(60), Instant.now().plus(Duration.ofDays(90)).plusSeconds(60));
        // both tokens work
        assertThat(refresh("Bearer " + newToken).getStatusCode().value()).isEqualTo(200);
        assertThat(refresh(bearer(registered)).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.sql("select last_seen_at from device where id = :id").param("id", deviceId)
                        .query(java.sql.Timestamp.class).single().toInstant())
                .isAfterOrEqualTo(seenBefore);
    }

    @Test
    void refreshAndRevokeLogEventsWithoutIdentifiersOfTheUser(CapturedOutput output) {
        String userId = uniqueUser();
        ResponseEntity<String> registered = registerUser(userId, null);
        String deviceId = deviceId(registered);

        ResponseEntity<String> refreshed = refresh(bearer(registered));
        assertThat(revoke(bearer(registered)).getStatusCode().value()).isEqualTo(204);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            for (String action : List.of("device.token_refreshed", "device.revoked")) {
                assertThat(lines(output, action)).anySatisfy(line -> {
                    assertThat(JsonPath.<String>read(line, "$['rp.device_id']")).isEqualTo(deviceId);
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo(APP);
                    assertThat(JsonPath.<String>read(line, "$['rp.user_hash']")).isEqualTo(UserHash.of(userId));
                });
            }
            // the access log carries the device of an authenticated request
            assertThat(output.getOut().lines()).anySatisfy(line -> {
                assertThat(line).contains("\"api.request\"").contains("token:refresh");
                assertThat(JsonPath.<String>read(line, "$['rp.device_id']")).isEqualTo(deviceId);
                assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo(APP);
            });
        });
        assertThat(output.getAll()).doesNotContain(userId)
                .doesNotContain(JsonPath.<String>read(registered.getBody(), "$.deviceToken"))
                .doesNotContain(JsonPath.<String>read(refreshed.getBody(), "$.deviceToken"));
    }

    @Test
    void revokeThenRefresh401() {
        ResponseEntity<String> registered = registerUser(uniqueUser(), null);
        UUID deviceId = UUID.fromString(deviceId(registered));
        String other = bearer(registerUser(uniqueUser(), null));

        ResponseEntity<String> revoked = revoke(bearer(registered));

        assertThat(revoked.getStatusCode().value()).isEqualTo(204);
        assertProblem(refresh(bearer(registered)), 401);
        assertProblem(revoke(bearer(registered)), 401);
        assertThat(jdbc.sql("select revoked_at is not null from device where id = :id").param("id", deviceId)
                        .query(Boolean.class).single())
                .isTrue();
        assertThat(jdbc.sql("select token_version from device where id = :id").param("id", deviceId)
                        .query(Integer.class).single())
                .isEqualTo(1);
        // other devices are not affected
        assertThat(refresh(other).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void supersededTokenCannotRevokeTheCurrentRegistration() {
        String userId = uniqueUser();
        ResponseEntity<String> first = registerUser(userId, null);
        UUID deviceId = UUID.fromString(deviceId(first));
        ResponseEntity<String> second = register(APP, idToken(userId), "IOS", deviceId.toString());
        assertThat(second.getStatusCode().value()).isEqualTo(201);

        // what the filter would hand over if the old token (version 0) were still accepted from the cache
        assertThatThrownBy(() -> devices.revoke(new DevicePrincipal(APP, userId, deviceId, 0)))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(jdbc.sql("select revoked_at is null and token_version = 1 from device where id = :id").param("id", deviceId)
                        .query(Boolean.class).single())
                .as("the current registration is untouched")
                .isTrue();
        assertThat(refresh(bearer(second)).getStatusCode().value()).isEqualTo(200);
        // the current version can revoke
        devices.revoke(new DevicePrincipal(APP, userId, deviceId, 1));
        assertProblem(refresh(bearer(second)), 401);
    }

    @Test
    void deviceRateLimited429(CapturedOutput output) {
        ResponseEntity<String> registered = register(LIMITED_APP, IDP.idToken(uniqueUser(), LIMITED_AUDIENCE, Duration.ofMinutes(5)), "ANDROID", null);
        assertThat(registered.getStatusCode().value()).isEqualTo(201);
        String bearer = bearer(registered);
        String deviceId = deviceId(registered);

        assertThat(refresh(bearer).getStatusCode().value()).isEqualTo(200);
        assertThat(refresh(bearer).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> third = refresh(bearer);

        assertProblem(third, 429);
        assertThat(Long.parseLong(third.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
        OpenApiAssert.assertMatchesContract("POST", "/v1/devices/token:refresh", third);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lines(output, "rate_limit.exceeded"))
                .anySatisfy(line -> {
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo(LIMITED_APP);
                    assertThat(JsonPath.<String>read(line, "$['rate_limit.scope']")).isEqualTo("device");
                    assertThat(JsonPath.<String>read(line, "$['rp.device_id']")).isEqualTo(deviceId);
                }));
        // another device of the same app has its own bucket
        ResponseEntity<String> other = register(LIMITED_APP, IDP.idToken(uniqueUser(), LIMITED_AUDIENCE, Duration.ofMinutes(5)), "ANDROID", null);
        if (other.getStatusCode().value() == 201) {
            assertThat(refresh(bearer(other)).getStatusCode().value()).isEqualTo(200);
        }
    }

    // ---- device authentication ---------------------------------------------------------------------------------

    @Test
    void tokenOfAnAppNoLongerConfiguredIsTheGeneric401(CapturedOutput output) {
        // a device of app "removed-app" registered while the app was configured; the app was then removed from the
        // configuration, but the token is still validly signed and the device row still active
        String userId = uniqueUser();
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("""
                insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at)
                values (?, 'removed-app', ?, 'ANDROID', 0, now(), now())
                """).params(deviceId, userId).update();
        Instant now = Instant.now();
        String token = tokens.issue(new DeviceClaims("removed-app", userId, deviceId, 0, now, now.plus(Duration.ofDays(1))))
                .token();

        for (ResponseEntity<String> response : List.of(refresh("Bearer " + token), revoke("Bearer " + token))) {
            assertProblem(response, 401);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
            assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).isEqualTo("A valid device token is required.");
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(reasonOfLastAuthFailure(output)).isEqualTo("unknown_app"));
        assertThat(jdbc.sql("select revoked_at is null from device where id = ?").param(deviceId).query(Boolean.class).single())
                .isTrue();
        assertThat(output.getAll()).doesNotContain(token).doesNotContain("\"log.level\":\"ERROR\"");
    }

    @Test
    void unauthenticatedRequestsAreRejectedWithTheSameProblem(CapturedOutput output) {
        ResponseEntity<String> registered = registerUser(uniqueUser(), null);
        String valid = bearer(registered);
        String garbage = "Bearer not.a.jwt";
        String signedByStranger = "Bearer " + strangerToken(UUID.fromString(deviceId(registered)));

        List<ResponseEntity<String>> responses = List.of(
                refresh(null),
                refresh(""),
                refresh("Bearer"),
                refresh("Basic dXNlcjpwYXNz"),
                refresh(garbage),
                refresh(signedByStranger),
                refresh(valid.replace("Bearer ", "Bearer x")));

        for (ResponseEntity<String> response : responses) {
            assertProblem(response, 401);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
            assertThat(response.getBody()).as("identical body for every reason").isEqualTo(responses.get(0).getBody());
        }
        OpenApiAssert.assertMatchesContract("POST", "/v1/devices/token:refresh", responses.get(0));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> reasons = lines(output, "auth.failed").stream()
                    .map(line -> JsonPath.<String>read(line, "$.reason")).toList();
            assertThat(reasons).contains("missing_token", "malformed");
        });
        assertThat(output.getAll()).doesNotContain(valid.substring("Bearer ".length()));
    }

    private String strangerToken(UUID deviceId) {
        // an ES256 token with the right claims but another key and an unknown kid
        try {
            com.nimbusds.jose.jwk.ECKey key = IDP.newEcKey();
            com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .issuer("reliable-push").audience(APP).subject("someone")
                    .claim("did", deviceId.toString()).claim("ver", 0)
                    .issueTime(new java.util.Date()).expirationTime(java.util.Date.from(Instant.now().plusSeconds(600)))
                    .build();
            return FakeOidc.sign(key, com.nimbusds.jose.JWSAlgorithm.ES256, claims);
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void validSignatureIsNotEnoughTheDeviceMustBeActiveAndMatch(CapturedOutput output) {
        String userId = uniqueUser();
        ResponseEntity<String> registered = registerUser(userId, null);
        UUID deviceId = UUID.fromString(deviceId(registered));
        Instant now = Instant.now();
        Instant later = now.plus(Duration.ofDays(1));

        assertThat(refresh("Bearer " + tokens.issue(new DeviceClaims(APP, userId, deviceId, 0, now, later)).token())
                        .getStatusCode().value())
                .as("control: the same claims as the registered device")
                .isEqualTo(200);
        List<DeviceClaims> forged = List.of(
                new DeviceClaims(APP, userId, UUID.randomUUID(), 0, now, later), // unknown device
                new DeviceClaims(APP, userId, deviceId, 1, now, later), // version does not match
                new DeviceClaims(APP, uniqueUser(), deviceId, 0, now, later), // another user
                new DeviceClaims(LIMITED_APP, userId, deviceId, 0, now, later), // another app
                new DeviceClaims(APP, userId, deviceId, 0, now.minus(Duration.ofDays(100)), now.minus(Duration.ofDays(10)))); // expired
        for (DeviceClaims claims : forged) {
            ResponseEntity<String> response = refresh("Bearer " + tokens.issue(claims).token());
            assertProblem(response, 401);
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> reasons = lines(output, "auth.failed").stream()
                    .map(line -> JsonPath.<String>read(line, "$.reason")).toList();
            assertThat(reasons).contains("revoked", "expired");
        });
    }

    @Test
    void everyDeviceEndpointRequiresAToken() {
        assertProblem(post("/v1/events", "{\"events\":[]}"), 401);
        assertProblem(http.delete().uri("/v1/devices/me").retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(String.class), 401);
        assertProblem(http.delete().uri("/v1/devices/me;x=1").retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(String.class), 401);
        // percent-encoded spelling: the container decodes it for the servlet path, so the filter must still apply
        assertProblem(http.post().uri(java.net.URI.create("http://localhost:" + port + "/v1/devices/token%3Arefresh"))
                .retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(String.class), 401);
    }

    @Test
    void responsesMatchContract() {
        String userId = uniqueUser();
        String body = "{\"appId\":\"" + APP + "\",\"idToken\":\"" + idToken(userId) + "\",\"platform\":\"IOS\"}";
        OpenApiAssert.assertRequestMatchesContract("POST", "/v1/devices", body, null, null);

        ResponseEntity<String> registered = post("/v1/devices", body);
        OpenApiAssert.assertMatchesContract("POST", "/v1/devices", registered);
        ResponseEntity<String> refreshed = refresh(bearer(registered));
        OpenApiAssert.assertMatchesContract("POST", "/v1/devices/token:refresh", refreshed);
        OpenApiAssert.assertMatchesContract("POST", "/v1/devices", post("/v1/devices", "{\"appId\":\"" + APP + "\"}"));
        OpenApiAssert.assertMatchesContract("POST", "/v1/devices",
                register(APP, "bad.token.value", "IOS", null));
        ResponseEntity<String> revoked = revoke(bearer(registered));
        OpenApiAssert.assertMatchesContract("DELETE", "/v1/devices/me", revoked);
        OpenApiAssert.assertMatchesContract("DELETE", "/v1/devices/me", revoke(bearer(registered)));
    }
}
