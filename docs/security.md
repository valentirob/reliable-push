# Security

Summary of how the backend protects data and authenticates callers. The binding rules for contributors are in
[`.agents/rules/security.md`](../.agents/rules/security.md); the design is in spec section 5. To report a vulnerability see
[`SECURITY.md`](../SECURITY.md).

## Threat model summary

| Actor / asset | Threat | Mitigation |
|---|---|---|
| Anyone on the network | Calling the API without credentials, flooding it | Every `/v1` endpoint except device registration needs a credential; identical `401` for missing and wrong credentials; body limit 64 KB; per-sender and per-device rate limits; per-IP limits at the ingress (see [operations.md](operations.md)) |
| Stolen or guessed sender API key | Registering or cancelling notifications | Keys are compared as SHA-256 hashes in constant time; one key per app; the key gives access only to that app's data |
| A device or user | Reading or reporting events for another user's notifications | `appId`/`userId` come from the verified credential or token, never from a body; an event for another user's `trackingId` is `FORBIDDEN`, for another app's `NOT_FOUND` |
| Forged or replayed device registration | Obtaining a device token for another user | OIDC signature verification against the issuer's JWKS, strict claim checks; signed grants are short-lived and capped |
| Stolen device token | Reporting events as that device | Short revocation cache, revoke endpoint, `token_version` bump on re-registration, expiry (default 90 days) |
| Attacker in front of the fallback receiver | Forging fallbacks, replaying old ones | HMAC-SHA256 signature over timestamp and body, timestamp for replay rejection, `Idempotency-Key` for de-duplication |
| Stolen or leaked admin key | Reading notifications, user ids and fallback payloads through the dashboard | Read-only API; keys scoped to apps; the payload only for keys with `payload`; keys stored as hashes; rate limit per key; every search and view audit-logged; page headers; optionally restricted to an internal network at the ingress. See [Admin keys](#admin-keys) |
| Operator mistakes | Secrets in config files or logs | Secrets and keys only as `env:NAME` references; inline values are rejected at startup; log deny-list |
| Malicious identity provider metadata | SSRF through discovery or JWKS URLs | https required (localhost issuers excepted), redirects never followed, 5 s deadline, 64 KB limit, discovery `issuer` must match |

## Sender API keys

- Configured per app as `sender-api-key-sha256` (64 lowercase hex characters). Create a key and its hash:

  ```sh
  KEY=$(openssl rand -hex 32)                       # give this to the sender, keep it out of the repo and the logs
  printf '%s' "$KEY" | openssl dgst -sha256 -hex    # configure the hash as sender-api-key-sha256
  ```

- The raw key is never stored or logged. Lookup hashes the presented key and compares against **every** app with
  `MessageDigest.isEqual` (no early exit), so timing does not reveal which app matched. Two apps cannot share a key hash
  (startup fails).
- Each app has exactly one key hash, so rotating a key is a switch-over: deploy the new `sender-api-key-sha256` and update
  the sender at the same time (or accept a short window of `401`s on the sender, which should retry registration).

## Admin keys

The [ops dashboard](admin.md) and its API (`/v1/admin/**`, `/admin/**`) are protected by admin keys. They are configured
under `reliable-push.admin.keys[]` as `{id, key-sha256, apps, permissions}`; with no key neither the page nor the API exists
(both answer `404` like an unknown path).

- Create a key and its hash (the hash is not a secret, the key is):

  ```sh
  KEY=$(openssl rand -base64 32)                    # give this to the person, keep it out of the repo and the logs
  printf %s "$KEY" | shasum -a 256                  # configure the hash (first column) as key-sha256 (Linux: sha256sum)
  ```

- `id` (`[a-z0-9-]{1,40}`) names the key in audit logs instead of the key. `apps` is `["*"]` (every app) or a list of app ids;
  a key cannot see an app outside its list (`404`). `permissions` can hold `payload`, which allows reading the stored
  fallback payload; without it the payload route answers `404`.
- Lookup is by SHA-256 in constant time, as for sender keys. Startup fails on a duplicate id or hash, a malformed hash, an
  unknown app or permission, and on a hash equal to a sender key hash.
- **Rotation:** add the new key (new `id` and hash), deploy, hand the new key out, remove the old entry, deploy. Both keys work
  during the overlap, so nobody is locked out. To revoke a key at once, remove its entry and deploy.
- **Audit:** every search and view logs `admin.search`, `admin.notification_viewed`, `admin.payload_viewed` or
  `admin.devices_viewed` with the key `id`, the app, for a notification or payload view the tracking id, and for every view
  `rp.user_hash`; never the key, a user id, a filter value or a payload.
  Failed sign-ins log `auth.failed` (`missing_admin_key`, `unknown_admin_key`); a key over its rate limit logs
  `rate_limit.exceeded` with scope `admin`.
- **Page headers** on `/admin/**`: `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'; base-uri 'none';
  form-action 'none'`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, and
  `Cache-Control: no-cache` (files revalidated on every load). Server
  values are inserted as text, never as HTML. API answers carry `Cache-Control: no-store`. The page keeps the key in
  `sessionStorage` only and sends it in a header, never in a URL.
- The `userId` is visible to every admin key; treat an admin key like access to personal data.

## Device authentication

### Device tokens

- ES256 JWT signed by the backend (`iss = reliable-push`, `aud = appId`, `sub = userId`, `did = deviceId`, `ver = token_version`,
  `iat`, `exp`). Validity is per app (`device-token-validity`, default 90 days).
- Verification is strict: the algorithm must be exactly ES256; the verification key is looked up by `kid` among the
  configured keys only, and tokens carrying their own key (`jwk`, `jku`, `x5u`, `x5c`) or `crit` headers are rejected; `iss`
  must match; `aud` must be exactly one value; `sub`, `did`, `ver`, `iat`, `exp` are required and strictly typed; 60 s clock
  skew; tokens above 8 KB are rejected before parsing.
- After the token checks, the device row must exist for that app and user, not be revoked, and have the same `token_version`
  (positive answers are cached for `revocation-cache-ttl`, default 60 s, keyed by device and version, never by token).
  Revocation and re-registration take effect immediately on the instance that handles them and on other instances after the
  cache TTL. Refresh, revoke and event reporting are conditional on the presented token version, so a superseded token cannot
  refresh, revoke or report.
- Every failure returns the same `401`; only the logged reason code (`missing_token`, `malformed`, `unknown_kid`,
  `bad_signature`, `wrong_issuer`, `expired`, `unknown_app` for a token of an app no longer configured, `revoked`) differs.

### Signing keys and rotation

Keys are P-256 pairs, configured as environment variables holding PEM text, identified by `kid`:

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve -out k1.pem   # private key, PKCS#8
openssl pkey -in k1.pem -pubout -out k1.pub.pem                                                                # public key, SPKI
# RP_DEVICE_KEY_K1="$(cat k1.pem)"   RP_DEVICE_PUB_K1="$(cat k1.pub.pem)"      (then delete the files)
```

```yaml
reliable-push:
  device-token:
    signing-keys:
      - { kid: k1, private-key-pem-ref: "env:RP_DEVICE_KEY_K1", public-key-pem-ref: "env:RP_DEVICE_PUB_K1" }
    current-kid: k1
```

`-pkeyopt ec_param_enc:named_curve` is required with the LibreSSL that ships with macOS (3.3.x), which otherwise writes explicit
EC parameters that the backend rejects at startup; it is harmless with OpenSSL 3 and later. The private key must be PKCS#8 (`BEGIN PRIVATE KEY`) and the public key SPKI (`BEGIN PUBLIC KEY`); both must be P-256 and
match each other, otherwise startup fails. **Rotation procedure:**

1. Generate a new pair (`k2`) and add it to `signing-keys` next to `k1`; deploy. All instances now verify both kids.
2. Set `current-kid: k2`; deploy. New and refreshed tokens are signed with `k2`; tokens signed with `k1` still verify.
3. Keep `k1` configured until the longest `device-token-validity` of any app has elapsed since step 2 (default 90 days),
   because tokens issued with `k1` are valid until their own expiry.
4. Remove `k1` and its variables; deploy. Anything still signed with `k1` now gets `401` and the SDK re-registers.

Do step 1 on every instance before step 2 reaches any of them, otherwise an instance that does not know `k2` rejects tokens
issued by one that does (a rolling deploy of step 1 completes before step 2 starts). If a key leaks, switch `current-kid` and
remove the leaked key immediately; devices re-register (`401` leads to registration with a fresh ID token).

### Identity verifiers (device registration)

`identity.type` per app:

- **`oidc`:** the ID token is verified with RS256 or ES256 only (anything else is rejected), against keys from the issuer's
  JWKS only. Checks: exact `iss`, `aud` contains the configured audience, `exp` required, `iat`/`nbf` validated when present
  (60 s skew), the configured user claim (default `sub`) must be a non-blank string of at most 256 characters. `azp` is not
  checked by design. ID tokens above 8 KB are rejected. RSA keys under 2048 bits and EC keys other than P-256 are ignored.
  - JWKS location: `jwks-uri` if set, else the `jwks_uri` of `<issuer>/.well-known/openid-configuration` (whose `issuer` must
    equal the configured one). https is required; plain http only when the issuer host is `localhost` or `127.0.0.1`;
    redirects are never followed; each fetch has a 5 s deadline and a 64 KB limit.
  - Cache: 15 minutes. An unknown `kid` may trigger a refresh at most once per 30 s, so random-`kid` floods cost at most one
    fetch per 30 s. During an identity provider outage, cached (stale) keys keep being served and a failing provider is not
    retried more than every 30 s. With nothing cached, `POST /v1/devices` answers `503` with `Retry-After: 30`.
- **`signed-grant`:** a short-lived HS256 JWT minted by your backend with a shared secret (`grant-secret-ref`, at least 32
  bytes; shorter secrets fail startup). Required: `aud` = the app id (exactly), `sub` = user id, `iat`, `exp` with
  `exp - iat` at most 5 minutes. There is no `jti` store: a stolen grant can be replayed by its holder until it expires,
  which together with the clock skew means a replay window of up to about 7 minutes. Keep the lifetime short and mint grants
  per login.

Rejections of credentials return one identical `401` body; the reason (`malformed`, `bad_signature`, `expired`,
`not_yet_valid`, `wrong_issuer`, `wrong_audience`, `missing_user_claim`, `lifetime_too_long`, `unknown_kid`) goes to the log
only, as `auth.failed`.

## Webhook authenticity

Each call carries `X-RP-Timestamp`, `X-RP-Signature: v1=hex(HMAC-SHA256(secret, timestamp + "." + body))` and
`Idempotency-Key: <trackingId>`. The secret is per app (`hmac-secret-ref`). Receivers verify the signature in constant time
over the raw body, reject timestamps older than a few minutes and de-duplicate on the idempotency key; example code is in
the [integration guide](integration-guide.md). The user agent never follows redirects, so a receiver cannot bounce the
signed request elsewhere; the response body is discarded. Plain `http://` receiver URLs are accepted by the configuration
validator (for local testing); use https in production.

## Request hygiene and limits

- Bean Validation on every input; rejected values are never echoed in error bodies or logs.
- Request bodies are limited to 64 KB on `/v1/**` (413); multipart handling is disabled; the `fallback` payload is limited to
  4 KB; Tomcat's form-post and swallow limits are bounded to 64 KB as well.
- Rate limits are Bucket4j token buckets in memory: per sender app (default 6,000/min), per device (default 60/min) and, for
  registration, per user (`register:<appId>:<userHash>`, the device limit), counted only after the credential was verified.
- All SQL uses bound parameters (`JdbcClient`); there is no ORM and no dynamic SQL from user input except fixed fragments.

## Logging rules

Logs are structured ECS JSON ([operations.md](operations.md)). Never logged, anywhere (also enforced by tests and by
`EventLog`'s deny-list of field names):

- sender API keys, ID tokens, signed grants, device tokens, signing keys, HMAC secrets, webhook signatures;
- the fallback message content (the `fallback` object) and any request or response body;
- raw user ids: logs carry `rp.user_hash`, the first 16 hex characters of SHA-256 of the user id (stable, not reversible by
  guessing short ids, but a hash of a low-entropy id can still be brute-forced: treat logs as sensitive);
- error messages that quote a rejected value (credential errors use fixed reason codes; parser exceptions are not chained).

Operators must keep the ability to read configuration (environment) restricted, since signing keys and HMAC secrets live in
environment variables.

## Known limits

- **Signed-grant replay window** of up to about 7 minutes (no `jti` store), see above.
- **Rate limits are per instance:** with N replicas a caller can use up to N times the configured rate. Unauthenticated
  floods (for example `POST /v1/devices` with garbage credentials) are limited only by the ingress: configure per-IP limits
  there.
- **Revocation latency:** another instance may honour a revoked token for up to `revocation-cache-ttl` (default 60 s).
- **User hash:** pseudonymisation, not anonymisation.
- **`azp` is ignored** when verifying OIDC ID tokens; scope tokens by `aud`.
- **No encryption of `fallback` content at rest** beyond what PostgreSQL storage encryption provides; it lives in
  `notification.fallback_payload` until retention deletes the row (default 30 days after it closed).
- **Admin keys are shared secrets:** there are no per-person accounts or SSO; restrict who holds a key and where `/admin/` is reachable.
- **TLS** is terminated at the ingress; the backend speaks plain HTTP inside the cluster.
