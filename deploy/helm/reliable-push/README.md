# reliable-push Helm chart

Deploys the Reliable Push backend only (not the harness, console or load generator). Requires Kubernetes >= 1.30.

```
helm install rp deploy/helm/reliable-push -f my-values.yaml
```

Required values (no defaults): `image.repository`, `database.url`, `database.username`, `secrets.existingSecret`, and at
least one app under `config.apps`. A missing one fails `helm template` with a message naming the key.

- **Secrets:** never in values. One existing Secret (`secrets.existingSecret`) is loaded with `envFrom`; it holds
  `SPRING_DATASOURCE_PASSWORD` and every key referenced as `env:NAME` in `config`. The chart cannot check that the keys
  exist. Every key ending in `ref` anywhere under `config` (maps and lists; `-ref`, `_ref` or camelCase `Ref`, as Spring's
  relaxed binding accepts them all) must be `env:NAME`; anything else fails the render (the message names the key path,
  never the value).
- **Modes:** `mode: combined` (default) runs one Deployment with profiles `api,worker,json`; `mode: split` runs
  `<fullname>-api` and `<fullname>-worker`. Services: `<fullname>-api` (port 80, API pods only) and
  `<fullname>-management` (port 8081, all pods; probes and `/actuator/prometheus`, never expose it publicly).
- **Config:** `config` is rendered under `reliable-push:` in ConfigMap `<fullname>-config`, mounted at `/config`. A
  changed value changes the `checksum/config` pod annotation, so pods roll on `helm upgrade`.
- **Sizing:** `api.*` (also used in combined mode) and `worker.*` set replicas, resources, Hikari pool size and worker
  concurrency; see docs/operations.md for how they relate. They are env vars, which win over the same keys under
  `config`. PodDisruptionBudgets are rendered for a component with more than one replica (with the API HPA:
  `autoscaling.api.minReplicas`).
- **Security:** non-root, read-only root filesystem, no privilege escalation, all capabilities dropped, `RuntimeDefault`
  seccomp, `/tmp` as `emptyDir`. No `runAsUser`/`fsGroup` by default (platform values may add them).
- **Exposure:** `ingress` (Kubernetes Ingress, `values-gke.yaml` enables it with class `gce`) or `route` (OpenShift,
  `values-openshift.yaml`). Both route only the prefix `/v1` to `<fullname>-api:http`; `/actuator` and port 8081 are never
  exposed (an Ingress path outside `/v1` fails the render). `api.service.annotations` annotate `<fullname>-api`.
  `gke.backendConfig.enabled` (on in `values-gke.yaml`) adds a GKE `BackendConfig` whose health check is
  `/actuator/health/readiness` on port 8081 and references it from the API Service.
- **Autoscaling:** `autoscaling.api.enabled` adds a CPU HPA for the API only (worker scaling follows the backlog; set
  `worker.replicas`). In `combined` mode the HPA targets the combined Deployment, so the workers scale with the API; use
  `mode: split` to autoscale the API alone. While enabled, `api.replicas` is not rendered on the scaled Deployment.
- **Monitoring:** `metrics.serviceMonitor.enabled` (Prometheus Operator, OpenShift user workload monitoring) or
  `metrics.podMonitoring.enabled` (GKE Managed Prometheus) scrape `/actuator/prometheus` on the `management` port.
- **Platforms:** `-f values-gke.yaml` (UID 1001, GID 0, Ingress, BackendConfig health check, PodMonitoring, Cloud SQL
  Auth Proxy sidecar example) or `-f values-openshift.yaml` (Route, ServiceMonitor, no fixed UID/GID/fsGroup so
  restricted-v2 applies). Both are overlays: add your own values file for the required keys.
- **helm test:** `helm test <release>` runs a pod (busybox, pinned by digest in `test.image`) that GETs
  `/actuator/health/readiness` through `<fullname>-management`.

Checks (Docker only, pinned images from `deploy/helm/tools.env`): `deploy/helm/scripts/validate.sh` (lint, unit tests, kubeconform on four renders; the Route schema is vendored in `deploy/helm/schemas`, the other CRDs come from a pinned datreeio/CRDs-catalog commit).
