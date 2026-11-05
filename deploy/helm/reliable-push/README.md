# reliable-push Helm chart

Deploys the Reliable Push backend only (not the harness, console or load generator). Requires Kubernetes >= 1.30.

```
helm install rp deploy/helm/reliable-push -f my-values.yaml
```

Required values (no defaults): `image.repository`, `database.url`, `database.username`, `secrets.existingSecret`, and at
least one app under `config.apps`. A missing one fails `helm template` with a message naming the key.

- **Secrets:** never in values. One existing Secret (`secrets.existingSecret`) is loaded with `envFrom`; it holds
  `SPRING_DATASOURCE_PASSWORD` and every key referenced as `env:NAME` in `config`. The chart cannot check that the keys
  exist. Every key ending in `-ref` anywhere under `config` (maps and lists) must be `env:NAME`; anything else fails the
  render (the message names the key path, never the value).
- **Modes:** `mode: combined` (default) runs one Deployment with profiles `api,worker,json`; `mode: split` runs
  `<fullname>-api` and `<fullname>-worker`. Services: `<fullname>-api` (port 80, API pods only) and
  `<fullname>-management` (port 8081, all pods; probes and `/actuator/prometheus`, never expose it publicly).
- **Config:** `config` is rendered under `reliable-push:` in ConfigMap `<fullname>-config`, mounted at `/config`. A
  changed value changes the `checksum/config` pod annotation, so pods roll on `helm upgrade`.
- **Sizing:** `api.*` (also used in combined mode) and `worker.*` set replicas, resources, Hikari pool size and worker
  concurrency; see docs/operations.md for how they relate.
- **Security:** non-root, read-only root filesystem, no privilege escalation, all capabilities dropped, `RuntimeDefault`
  seccomp, `/tmp` as `emptyDir`. No `runAsUser`/`fsGroup` by default (platform values may add them).

Checks (Docker only, pinned images from `deploy/helm/tools.env`): `deploy/helm/scripts/validate.sh`.
