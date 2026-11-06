# Deployment (Kubernetes, GKE, OpenShift)

The Helm chart `deploy/helm/reliable-push` deploys the backend only: the API and the fallback worker, never the harness,
the console or the load generator. It needs Kubernetes 1.30 or later (OpenShift 4.17 or later) and Helm 3 or 4 (the chart
checks run Helm 4.3). Chart reference (values, modes, checks):
[`deploy/helm/reliable-push/README.md`](../deploy/helm/reliable-push/README.md); configuration keys, metrics and logs:
[operations.md](operations.md).

The commands below use the namespace `reliable-push` and the release `rp` (objects are named `rp-reliable-push*`). The
generic steps were run as written against the local kind cluster of `./scripts/helm-smoke.sh --keep`, with an extra
values file for the locally loaded image and smaller CPU requests; the GKE and OpenShift parts were checked with
`helm template` and kubeconform (`deploy/helm/scripts/validate.sh`) only, not on a real GKE or OpenShift cluster.

## Prerequisites

- **Container image** in a registry your cluster can pull from. No image is published yet: build it from `backend/`
  (`docker build -t registry.example.com/reliable-push:0.1.0 backend`) and push it. Prefer `image.digest` over a tag.
- **PostgreSQL 16**, the only stateful dependency; the schema is created and migrated by Flyway at startup.
  - GKE: Cloud SQL for PostgreSQL 16, reached through the Cloud SQL Auth Proxy sidecar (see [GKE](#gke)).
  - OpenShift: an operator-managed cluster, for example CloudNativePG or Crunchy Postgres for Kubernetes, or a managed
    service outside the cluster. Operators put the generated password in their own Secret; copy it into the
    application Secret as `SPRING_DATASOURCE_PASSWORD` (step 1).
  - **`max_connections` vs. pools:** every pod opens up to its `hikariMaximumPoolSize` connections. Keep
    `api.replicas × api.hikariMaximumPoolSize + worker.replicas × worker.hikariMaximumPoolSize` (combined mode: only the
    `api.*` pair) below `max_connections`, with room for admin sessions, counting the HPA's `maxReplicas` instead of
    `api.replicas` when autoscaling and the extra pods of a rolling update (25 % surge by default). Chart defaults in
    split mode: 2 × 10 + 2 × 20 = 60, inside PostgreSQL's default of 100; small Cloud SQL tiers allow fewer, so check the
    instance's `max_connections` flag.
- **Fallback receiver** reachable from the worker pods (`config.apps.<id>.fallback.url`), and an identity provider for
  device registration (`identity.type: oidc`) or a grant secret (`signed-grant`), see [security.md](security.md).

## 1. Create the Secret

No secret ever goes into a values file: the chart loads one existing Secret (`secrets.existingSecret`) with `envFrom`.
It must contain `SPRING_DATASOURCE_PASSWORD` and one key for every `env:NAME` reference in `config`; the names below match
the values file of step 2. The chart cannot check the keys: a missing one fails the pod's startup with a message naming
the reference.

| Key | Content |
|---|---|
| `SPRING_DATASOURCE_PASSWORD` | password of `database.username` |
| `RP_SHOP_HOOK_SECRET` | HMAC secret of app `shop`'s fallback webhook (`fallback.hmac-secret-ref: env:RP_SHOP_HOOK_SECRET`), shared with the receiver |
| `RP_DEVICE_KEY_K1`, `RP_DEVICE_PUB_K1` | device-token signing key pair `k1` (PEM, PKCS#8 private / SPKI public, P-256) |
| `RP_SHOP_GRANT_SECRET` | only for `identity.type: signed-grant` (`grant-secret-ref`), at least 32 bytes |

Secret values are never typed inline on a command line (shell history, `ps`): they are generated into files, read
without echo, or copied from another Secret. Work in an empty directory that is never committed (values are read
verbatim, so strip the newline `openssl rand` prints), then create the Secret from the files:

```sh
umask 077 && mkdir rp-secrets && cd rp-secrets
openssl rand -hex 32 | tr -d '\n' >sender-key.txt         # give it to the sending backend; only its SHA-256 is configured
openssl rand -hex 32 | tr -d '\n' >shop-hook-secret.txt   # give it to the fallback receiver
printf 'database password: ' >&2; read -rs DBPW && printf '%s' "$DBPW" >db-password.txt; unset DBPW; echo >&2
# or, with an operator-managed database, copy its generated password (CloudNativePG: Secret <cluster>-app, key password):
# kubectl -n <db-namespace> get secret <cluster>-app -o jsonpath='{.data.password}' | base64 -d >db-password.txt
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve -out k1.pem
openssl pkey -in k1.pem -pubout -out k1.pub.pem
openssl dgst -sha256 -hex sender-key.txt | sed 's/^.* //'   # => sender-api-key-sha256 for the values file

kubectl create namespace reliable-push
kubectl -n reliable-push create secret generic reliable-push-secrets \
  --from-file=SPRING_DATASOURCE_PASSWORD=db-password.txt \
  --from-file=RP_SHOP_HOOK_SECRET=shop-hook-secret.txt \
  --from-file=RP_DEVICE_KEY_K1=k1.pem \
  --from-file=RP_DEVICE_PUB_K1=k1.pub.pem
```

Delete the files once the Secret exists and the sender key and hook secret are handed over (or keep them in your secret
manager). On GKE you can sync the Secret from Secret Manager, on OpenShift from Vault or the External Secrets Operator:
the chart only needs a Secret with these keys. Changing the Secret does not restart pods; see [Upgrades](#upgrades).

## 2. Values file

Required values (no defaults; a missing one fails `helm template`/`install` naming the key): `image.repository`,
`database.url`, `database.username`, `secrets.existingSecret` and at least one app under `config.apps`. `config` is the
`reliable-push.*` configuration in YAML ([operations.md](operations.md#configuration-reference-reliable-push)); every key
ending in `-ref` must be `env:NAME`, an inline value fails the render.

```yaml
# my-values.yaml
image:
  repository: registry.example.com/reliable-push
  tag: "0.1.0"
database:
  url: jdbc:postgresql://postgres:5432/reliablepush
  username: reliablepush
secrets:
  existingSecret: reliable-push-secrets
config:
  device-token:
    current-kid: k1
    signing-keys:
      - kid: k1
        private-key-pem-ref: env:RP_DEVICE_KEY_K1
        public-key-pem-ref: env:RP_DEVICE_PUB_K1
  apps:
    shop:
      sender-api-key-sha256: "<64 hex characters from step 1>"
      identity: { type: oidc, issuer: "https://idp.example.com", audience: shop-mobile }
      fallback:
        url: https://fallback.example.com/hook
        hmac-secret-ref: env:RP_SHOP_HOOK_SECRET
```

## 3. Install and check

From the repository root (or use the packaged chart):

```sh
helm install rp deploy/helm/reliable-push -n reliable-push -f my-values.yaml
kubectl -n reliable-push rollout status deploy/rp-reliable-push --timeout=180s   # split mode: deploy/rp-reliable-push-api and -worker
helm test rp -n reliable-push                                                    # GETs /actuator/health/readiness via rp-reliable-push-management
```

`rp-reliable-push-api` (port 80) serves `/v1` and is the only Service to expose. `rp-reliable-push-management` (port
8081, all pods) serves the probes and `/actuator/prometheus`; never route it publicly (the API port answers `404` for
`/actuator`). A quick check from your machine:

```sh
kubectl -n reliable-push port-forward svc/rp-reliable-push-management 8081:8081 &
curl -s http://127.0.0.1:8081/actuator/health/readiness   # => {"status":"UP"}
kill %1
```

## GKE

```sh
helm install rp deploy/helm/reliable-push -n reliable-push -f deploy/helm/reliable-push/values-gke.yaml -f my-values.yaml
```

`values-gke.yaml` runs the pods as UID 1001, GID 0 and enables:

- **Ingress** (class `gce`, external Application Load Balancer) routing only `/v1` to the API Service. Set your host
  under `ingress.hosts` in `my-values.yaml` (the overlay's `push.example.com` is a placeholder; lists are replaced, not
  merged). For TLS use a Google-managed certificate: create a `ManagedCertificate` (`networking.gke.io/v1`) for the host
  and a global static IP, then set the annotations shown (commented) in the overlay,
  `networking.gke.io/managed-certificates` and `kubernetes.io/ingress.global-static-ip-name`. On VPC-native clusters GKE
  uses container-native load balancing (NEGs) for the ClusterIP Service.
- **Load balancer health check:** the readiness probe is on port 8081, not on the serving port, so GKE cannot derive the
  health check from it and would fall back to `GET /` on port 8080, which answers `404`. The overlay sets
  `gke.backendConfig.enabled: true`: the chart renders a `BackendConfig` (`cloud.google.com/v1`) `rp-reliable-push-api`
  with `healthCheck: {type: HTTP, port: 8081, requestPath: /actuator/health/readiness}` and annotates the API Service
  with `cloud.google.com/backend-config: '{"default": "rp-reliable-push-api"}'`. Timings go in
  `gke.backendConfig.healthCheck`, other `BackendConfig` fields in `gke.backendConfig.extraSpec`, other Service
  annotations in `api.service.annotations`. Google's health-check probes come from `35.191.0.0/16` and
  `130.211.0.0/22` and must reach port 8081 as well: add a firewall rule if the cluster's rules allow only the serving
  port. Verify it on your cluster before sending traffic.
- **Managed Prometheus:** a `PodMonitoring` (`monitoring.googleapis.com/v1`) scrapes `/actuator/prometheus` on the
  `management` port every 30 s (`metrics.podMonitoring.interval`). Managed collection is on by default in recent GKE
  versions; the CRD must exist or the install fails.
- **Cloud SQL Auth Proxy:** add it as a native sidecar with `extraInitContainers` (the overlay has a commented example:
  pin the image by digest, `restartPolicy: Always`, the same hardened security context), set
  `database.url: jdbc:postgresql://127.0.0.1:5432/<db>`, and give the pods' service account `roles/cloudsql.client`
  through Workload Identity (`serviceAccount.annotations: {iam.gke.io/gcp-service-account: <gsa>@<project>.iam.gserviceaccount.com}`).
  Use IAM database authentication or keep the password in the Secret.

## OpenShift

```sh
helm install rp deploy/helm/reliable-push -n reliable-push -f deploy/helm/reliable-push/values-openshift.yaml -f my-values.yaml
```

- **SCC:** the overlay sets no `runAsUser`, `runAsGroup` or `fsGroup` anywhere (the `helm test` pod included), so the
  default `restricted-v2` SCC admits the pods and assigns an arbitrary UID in group 0; the image is built for that
  (group-writable files). Do not grant `anyuid`. The other security settings (non-root, read-only root filesystem, no
  privilege escalation, all capabilities dropped, `RuntimeDefault` seccomp) already satisfy `restricted-v2`.
- **Route** (`route.openshift.io/v1`) for `/v1` only, edge TLS with HTTP redirected to HTTPS. An empty `route.host`
  lets the router generate a host; set it in `my-values.yaml` for your own domain.
- **User workload monitoring:** the overlay enables a `ServiceMonitor` for the `management` port. It is scraped only
  when user workload monitoring is enabled (`enableUserWorkload: true` in the `cluster-monitoring-config` ConfigMap of
  `openshift-monitoring`, cluster admin); add `metrics.serviceMonitor.labels` if your Prometheus selects on labels.

## Combined vs split

- **`mode: combined`** (default): one Deployment `rp-reliable-push` runs both roles (profiles `api,worker,json`). It uses
  `api.replicas`, `api.resources`, `api.hikariMaximumPoolSize` and `api.podDisruptionBudget`, plus `worker.maxConcurrency`;
  it **ignores** `worker.replicas`, `worker.resources`, `worker.hikariMaximumPoolSize`, `worker.strategy` and
  `worker.podDisruptionBudget` (the Deployment always rolls with `RollingUpdate`). Size `api.resources` for both roles:
  add the worker's requests to the API's ([operations.md](operations.md#measured-capacity)). An HPA
  (`autoscaling.api.enabled`) then scales the workers with the API. Good for small installations.
- **`mode: split`**: `rp-reliable-push-api` (profiles `api,json`) and `rp-reliable-push-worker` (`worker,json`), each with
  its own `api.*` / `worker.*` replicas, resources, pool and PodDisruptionBudget, and `worker.strategy`. Use it to
  autoscale the API alone, to size the worker for the fallback backlog independently, or when you need `Recreate` for
  the workers. Worker pods declare no `http` port and are never behind the API Service.

Switching modes is a normal `helm upgrade`; the old Deployment is deleted and the new ones created, so expect a short
gap if `api.replicas` is 1.

## Upgrades

```sh
helm upgrade rp deploy/helm/reliable-push -n reliable-push -f my-values.yaml   # plus the platform overlay you installed with
kubectl -n reliable-push rollout status deploy/rp-reliable-push --timeout=180s
```

- **`config` changes roll the pods:** the pod template carries `checksum/config` (SHA-256 of the rendered ConfigMap),
  so a changed `config` value restarts the pods with the new configuration.
- **Secret changes do not:** the Secret is read at startup, so after rotating a value run
  `kubectl -n reliable-push rollout restart deploy/rp-reliable-push` (split mode: both Deployments). For signing keys
  follow the rotation order in [security.md](security.md#signing-keys-and-rotation).
- **Migrations** are forward-only and run at startup (Flyway serialises concurrent starts); there is no down migration,
  so roll back by restoring the database, not with `helm rollback` alone.
- **V3 boundary:** if you run a backend built before the `V3` migration (`notification.lease_until`), do not run old and
  new workers side by side ([operations.md](operations.md#scaling-and-capacity)): upgrade across that boundary with
  `mode: split` and `worker.strategy: Recreate` (stop all workers, then start the new ones). In combined mode, stop the
  pods first: `kubectl -n reliable-push scale deploy/rp-reliable-push --replicas=0`, wait until they are gone, then
  `helm upgrade` (it sets `api.replicas` again).
- Add a new app to `config.apps` on all replicas before sending its traffic (one `helm upgrade` does that).

## Graceful shutdown

On `SIGTERM` (rolling update, scale-down, node drain):

- **API pods** sleep 5 s in a `preStop` hook, so the endpoint is removed from the Service and the load balancer before
  the server stops accepting connections; then the web server finishes the requests in flight (`server.shutdown:
  graceful`, normally milliseconds).
- **Workers** claim nothing more and wait for the webhook calls in flight, writing their outcomes before the connection
  pool closes, for at most `reliable-push.worker.shutdown-timeout` (default: the largest app `fallback.http-timeout` + 5 s,
  15 s with the defaults). A call that outlives it is logged once at WARN and its row is re-sent after the lease.
- **The hard bound is the kubelet:** it sends `SIGKILL` `terminationGracePeriodSeconds` after the start of termination
  (chart default 60). Keep it above `worker.shutdown-timeout` plus the web server's drain (at most 30 s,
  `spring.lifecycle.timeout-per-shutdown-phase`) plus the 5 s `preStop` sleep. If you raise an app's `http-timeout` (for
  example to 40 s, so a 45 s shutdown timeout), raise `terminationGracePeriodSeconds` too (for example to 90). A `SIGKILL` during dispatch is the crash case: rows are re-sent
  once after the lease and receivers de-duplicate on `Idempotency-Key`.

The kind smoke install (`./scripts/helm-smoke.sh`) checks this: it rolls the workers while webhook calls are in flight and
expects every row triggered exactly once, with no ERROR log.

## Sizing

The chart defaults come from the local load runs: [operations.md, "Measured capacity"](operations.md#measured-capacity)
(NFR-2 result, worker drain rates, how the pools relate to `max-concurrency`) and the formulas under
[Scaling and capacity](operations.md#scaling-and-capacity). The numbers are relative to the measuring machine: confirm
them on your cluster, and watch `rp_due_backlog` for the workers.

## Ops dashboard

The [ops dashboard](admin.md) is off until you set `config.admin.keys` in `my-values.yaml`. The entries are the SHA-256
hashes of the keys, not secrets, so they go in the values file, not in the Secret ([security.md](security.md#admin-keys)).

```yaml
config:
  admin:
    keys:
      - id: support
        key-sha256: "<64 lowercase hex characters>"
        apps: ["shop"]            # or ["*"]
        permissions: [payload]    # optional: lets this key read the stored fallback payload
```

Nothing else changes: no new pod, port or Service. Every pod that serves the API also serves `/admin/` and `/v1/admin/`.
The chart's Ingress and Route route `/v1` only, so `/v1/admin/` is reachable through them while the `/admin/` page is not.
Choose how people reach the page:

- **Not through the public host (recommended start):** `kubectl -n reliable-push port-forward svc/rp-reliable-push-api 8080:80`,
  then open `http://127.0.0.1:8080/admin/`.
- **Through an internal Ingress or Route** that you add yourself for `/admin/` and `/v1/admin/` on an internal host or behind your
  SSO proxy, with an IP allow-list (for example `nginx.ingress.kubernetes.io/whitelist-source-range`, or the OpenShift Route annotation
  `haproxy.router.openshift.io/ip_whitelist`).
- **Block `/v1/admin/` on the public host** with a path rule or a WAF rule at the ingress, if you route the dashboard only
  internally. The admin key still protects it, and without any key it answers `404`.

## Per-IP limits at the ingress

The application's rate limits are per instance and only apply to authenticated callers; unauthenticated floods (garbage
credentials on `POST /v1/devices`, invalid API keys) must be limited in front of it, per client IP:

- **GKE:** a Cloud Armor security policy with a rate-based rule (`rate_based_ban` or `throttle`, keyed on `IP`),
  attached through the chart's `BackendConfig`: `gke.backendConfig.extraSpec: {securityPolicy: {name: <policy>}}`.
- **OpenShift:** HAProxy router annotations on the Route (`route.annotations`), for example
  `haproxy.router.openshift.io/rate-limit-connections: "true"` and
  `haproxy.router.openshift.io/rate-limit-connections.rate-http: "100"` (HTTP requests per source IP within the router's
  sampling period; see the OpenShift Route annotation reference).
- **ingress-nginx:** `nginx.ingress.kubernetes.io/limit-rps` / `limit-connections` in `ingress.annotations`.

Keep the per-IP limit above the legitimate peak of a sending backend (a NAT-ed backend sends from few IPs), or exempt its
addresses.

## Uninstall

```sh
helm uninstall rp -n reliable-push
kubectl -n reliable-push delete secret reliable-push-secrets
```

The database is not touched; drop it separately if you no longer need the data.
