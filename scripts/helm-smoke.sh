#!/usr/bin/env bash
# Local smoke install of the backend Helm chart on a throwaway kind cluster (LOCAL ONLY, never in CI).
#
#   ./scripts/helm-smoke.sh [--keep]
#
# Creates the kind cluster "rp-smoke" (node image KIND_NODE_IMAGE from deploy/helm/tools.env), builds and loads
# reliable-push-backend:smoke, installs Postgres, a slow dummy fallback receiver and the chart (mode split, 1 API + 2
# workers, OpenShift-like UID 1000870000 / GID 0), then runs the checks (each prints PASS or FAIL; the first FAIL stops
# the script):
#   rollouts, helm test, POST/GET /v1/notifications, /actuator/health on the API Service = 404, and Review Focus 3: 200
#   notifications (timeoutMinutes 1) fall due, the worker Deployment is rolled while their webhook calls are in flight,
#   afterwards all 200 are FALLBACK_TRIGGERED, the dummy saw exactly 200 calls (no re-send), and no pod log (terminated
#   workers included) has a "log.level":"ERROR" line or a "fallback.reclaimed" event.
# Exit 0 = passed. The cluster is deleted on exit unless --keep was given; a later run then reuses it (the namespace is
# recreated). Without --keep an existing rp-smoke cluster makes the script fail fast.
#
# Isolation: the kubeconfig is harness/build/helm-smoke/kubeconfig (--kubeconfig on every kind/kubectl call); the user's
# ~/.kube/config is never read or written. Only the cluster "rp-smoke" is ever created or deleted.
#
# Helm is not installed on the host: it runs from HELM_IMAGE (tools.env). On Colima the kind API server is published on
# the VM's loopback, which a "docker run --network host" container does not share with the macOS host, so the helm
# container joins the docker network "kind" and uses `kind get kubeconfig --internal` (https://rp-smoke-control-plane:6443).
#
# Needs: docker (Colima: set DOCKER_HOST), kind, kubectl, curl, a JDK 21 (JAVA_HOME or /opt/homebrew/opt/openjdk@21).
# Bash 3.2 compatible. Output and logs: harness/build/helm-smoke/ (git-ignored, contains generated secrets: never share).
set -u

KEEP=0
case "${1:-}" in
    "") ;;
    --keep) KEEP=1 ;;
    *) echo "usage: $0 [--keep]" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/harness/build/helm-smoke"
CLUSTER=rp-smoke
NS=smoke
RELEASE=rp
FULL="$RELEASE-reliable-push" # chart fullname: release name + chart name
KUBECONFIG_FILE="$OUT/kubeconfig"
KUBECONFIG_INTERNAL="$OUT/kubeconfig.internal"
KEPT_MARKER="$OUT/.kept"
CHART="$ROOT/deploy/helm/reliable-push"
SMOKE_DIR="$ROOT/scripts/helm-smoke"
NOTIFICATIONS=200

# shellcheck source=../deploy/helm/tools.env
. "$ROOT/deploy/helm/tools.env"

say() { printf '[helm-smoke] %s\n' "$*"; }
pass() { printf '[helm-smoke] PASS: %s\n' "$*"; }
fail() {
    printf '[helm-smoke] FAIL: %s\n' "$*" >&2
    exit 1
}

k() { kubectl --kubeconfig "$KUBECONFIG_FILE" -n "$NS" "$@"; }

# helm in a container, on the kind docker network (see header); chart and values mounted read-only
helm() {
    docker run --rm --network kind -e HOME=/tmp -e KUBECONFIG=/kube/config \
        -v "$KUBECONFIG_INTERNAL:/kube/config:ro" -v "$CHART:/chart:ro" -v "$SMOKE_DIR:/smoke:ro" \
        "$HELM_IMAGE" "$@"
}

cleanup_done=0
OWNED=0 # 1 only for a cluster this run created (or a marker-validated --keep reuse): the only one it may delete
OLD_PODS=""
PF_PID=""
SAMPLER_PID=""
cleanup() {
    status=$?
    [ "$cleanup_done" = 1 ] && return
    cleanup_done=1
    [ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null
    [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" 2>/dev/null
    if [ "$OWNED" = 1 ] && [ -f "$KUBECONFIG_FILE" ] && kind get clusters --quiet 2>/dev/null | grep -qx "$CLUSTER"; then
        collect_logs || true
        if [ "$KEEP" = 1 ]; then
            say "cluster $CLUSTER kept (--keep); delete it with: kind delete cluster --name $CLUSTER --kubeconfig $KUBECONFIG_FILE"
        else
            say "deleting cluster $CLUSTER"
            kind delete cluster --name "$CLUSTER" --kubeconfig "$KUBECONFIG_FILE" >/dev/null 2>&1
            rm -f "$KEPT_MARKER"
        fi
    fi
    if [ "$status" = 0 ]; then say "OK: all checks passed"; else say "FAILED (exit $status); see $OUT"; fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Pod logs (current ones; the terminated worker pods' logs were saved by the rollout step) and helm status
collect_logs() {
    mkdir -p "$OUT/logs"
    for pod in $(k get pods -l app.kubernetes.io/instance="$RELEASE" -o name 2>/dev/null); do
        n="${pod#pod/}"
        case " $OLD_PODS " in *" $n "*) continue ;; esac # streamed (complete, shutdown tail included) during the rollout
        k logs "$n" --all-containers >"$OUT/logs/$n.log" 2>&1 || true
    done
    k logs deploy/fallback-dummy >"$OUT/logs/fallback-dummy.log" 2>&1 || true
    helm status "$RELEASE" -n "$NS" >"$OUT/helm-status.txt" 2>&1 || true
}

# ---------------------------------------------------------------- prerequisites
for tool in docker kind kubectl curl; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool not found"
done
docker info >/dev/null 2>&1 || fail "cannot reach the docker daemon (DOCKER_HOST=${DOCKER_HOST:-unset})"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
[ -x "$JAVA_HOME/bin/java" ] || fail "JDK 21 not found (set JAVA_HOME)"

mkdir -p "$OUT"
chmod 700 "$OUT"

# ---------------------------------------------------------------- cluster
# The --keep marker holds the ID of the cluster's control-plane container: a cluster of the same name created by anyone
# else (or recreated since) has another ID and is never reused or deleted.
control_plane_id() { docker inspect -f '{{.Id}}' "$CLUSTER-control-plane" 2>/dev/null; }
rm -rf "$OUT/logs" "$OUT/helm-status.txt" # stale logs of a previous run would be counted and grepped below
if kind get clusters --quiet 2>/dev/null | grep -qx "$CLUSTER"; then
    cp_id="$(control_plane_id)"
    if [ -f "$KEPT_MARKER" ] && [ -f "$KUBECONFIG_FILE" ] && [ -n "$cp_id" ] && [ "$(cat "$KEPT_MARKER")" = "$cp_id" ]; then
        say "reusing the kept cluster $CLUSTER"
        OWNED=1
        kubectl --kubeconfig "$KUBECONFIG_FILE" delete namespace "$NS" --ignore-not-found --wait=true --timeout=120s >/dev/null
    else
        fail "kind cluster $CLUSTER already exists and was not kept by this script (no marker, or its control-plane container ID differs); delete it (kind delete cluster --name $CLUSTER) and rerun"
    fi
else
    say "creating kind cluster $CLUSTER ($KIND_NODE_IMAGE)"
    kind create cluster --name "$CLUSTER" --image "$KIND_NODE_IMAGE" --kubeconfig "$KUBECONFIG_FILE" --wait 180s >"$OUT/kind-create.log" 2>&1 \
        || { cat "$OUT/kind-create.log" >&2; OWNED=1; fail "kind create cluster failed"; } # a half-created cluster is ours
    OWNED=1
fi
chmod 600 "$KUBECONFIG_FILE"
if [ "$KEEP" = 1 ]; then
    cp_id="$(control_plane_id)"
    [ -n "$cp_id" ] || fail "cannot read the ID of container $CLUSTER-control-plane"
    printf '%s\n' "$cp_id" >"$KEPT_MARKER" || fail "cannot write $KEPT_MARKER"
fi
kind get kubeconfig --name "$CLUSTER" --internal >"$KUBECONFIG_INTERNAL" || fail "kind get kubeconfig failed"
chmod 600 "$KUBECONFIG_INTERNAL"
kubectl --kubeconfig "$KUBECONFIG_FILE" create namespace "$NS" >/dev/null || fail "create namespace"

# ---------------------------------------------------------------- image
say "building reliable-push-backend:smoke"
docker build -q -t reliable-push-backend:smoke "$ROOT/backend" >"$OUT/docker-build.log" 2>&1 \
    || { tail -30 "$OUT/docker-build.log" >&2; fail "docker build failed"; }
say "loading the image into the cluster"
kind load docker-image reliable-push-backend:smoke --name "$CLUSTER" >"$OUT/kind-load.log" 2>&1 \
    || { say "kind load docker-image failed, trying an image archive"
         docker save reliable-push-backend:smoke -o "$OUT/image.tar" \
             && kind load image-archive "$OUT/image.tar" --name "$CLUSTER" >>"$OUT/kind-load.log" 2>&1 \
             || { cat "$OUT/kind-load.log" >&2; fail "kind load failed"; }
         rm -f "$OUT/image.tar"; }

# ---------------------------------------------------------------- secrets
say "generating secrets (GenSecrets) into $OUT"
: >"$OUT/empty.template" # the smoke config goes through the chart's `config`, not through the env template
"$JAVA_HOME/bin/java" "$ROOT/harness/compose/GenSecrets.java" "$OUT" "$OUT/empty.template" >/dev/null \
    || fail "GenSecrets failed"
# Only the secret keys reach the cluster. The fast-clock key (RELIABLEPUSH_TESTING_MINUTEDURATION) is not among them.
# The PEM values are single-quoted in the env file: strip the quotes (the literal \n separators are kept, the backend's
# PEM reader turns them into line breaks).
grep -E '^(SPRING_DATASOURCE_PASSWORD|RELIABLEPUSH_APPS_SHOP_SENDERAPIKEYSHA256|RP_SHOP_GRANT_SECRET|RP_SHOP_HOOK_SECRET|RP_DEVICE_KEY_E2E|RP_DEVICE_PUB_E2E)=' "$OUT/secrets.env" \
    | sed "s/='\(.*\)'\$/=\1/" >"$OUT/cluster-secrets.env"
chmod 600 "$OUT/cluster-secrets.env"
[ "$(wc -l <"$OUT/cluster-secrets.env" | tr -d ' ')" = 6 ] || fail "cluster-secrets.env does not have the 6 expected keys"
grep -q 'TESTING' "$OUT/cluster-secrets.env" && fail "fast clock key leaked into the cluster secret"
# plaintext sender key for the client side only (curl header file, mode 600); never sent to the cluster
printf 'X-Api-Key: %s\n' "$(grep '^HARNESS_APPS_SHOP_SENDERAPIKEY=' "$OUT/secrets.env" | cut -d= -f2-)" >"$OUT/sender-key.header"
chmod 600 "$OUT/sender-key.header"
k create secret generic rp-secrets --from-env-file="$OUT/cluster-secrets.env" >/dev/null || fail "create secret rp-secrets"
k create secret generic rp-postgres --from-env-file="$OUT/db.env" >/dev/null || fail "create secret rp-postgres"

# ---------------------------------------------------------------- postgres, dummy receiver
k apply -f "$SMOKE_DIR/postgres.yaml" -f "$SMOKE_DIR/fallback-dummy.yaml" >/dev/null || fail "apply postgres/dummy"
k rollout status deploy/postgres --timeout=180s >/dev/null || fail "postgres did not become ready"
k rollout status deploy/fallback-dummy --timeout=180s >/dev/null || fail "fallback dummy did not become ready"

# R3: verify (not assume) that the dummy answers 2xx to POST
code="$(k run dummy-probe --rm -i --restart=Never --quiet --image="busybox:1.38.0@sha256:a46257e06c82ad85242d0d38077d0a52d9896389ca60a1a59d3463db586e5fa4" \
    --command -- sh -c 'wget -q -S -O /dev/null --post-data=x http://fallback-dummy:8080/hook 2>&1 | head -1' 2>/dev/null | tr -d '\r')"
case "$code" in *" 200 "*) pass "dummy fallback receiver answers 200 to POST ($code)" ;; *) fail "dummy receiver POST answer: '$code'" ;; esac
k logs deploy/fallback-dummy | grep -o 'HOOK /hook' | wc -l | tr -d ' ' >"$OUT/probe-hooks.txt" # the probe's own call, subtracted later
PROBE_HOOKS="$(cat "$OUT/probe-hooks.txt")"

# ---------------------------------------------------------------- helm install
say "helm install $RELEASE (mode split, 1 API + 2 workers)"
helm install "$RELEASE" /chart -n "$NS" -f /smoke/smoke-values.yaml >"$OUT/helm-install.txt" 2>&1 \
    || { cat "$OUT/helm-install.txt" >&2; fail "helm install failed"; }

# ---------------------------------------------------------------- checks
k rollout status "deploy/$FULL-api" --timeout=180s >/dev/null || fail "API rollout not complete within 180 s"
k rollout status "deploy/$FULL-worker" --timeout=180s >/dev/null || fail "worker rollout not complete within 180 s"
pass "rollouts complete within 180 s (1 API, 2 workers)"

uid="$(k get pod -l app.kubernetes.io/component=worker -o jsonpath='{.items[0].spec.securityContext.runAsUser}')"
[ "$uid" = 1000870000 ] || fail "worker pod runs with UID '$uid', expected 1000870000"
fsg="$(k get pod -l app.kubernetes.io/component=worker -o jsonpath='{.items[0].spec.securityContext.fsGroup}')"
[ -z "$fsg" ] || fail "worker pod has fsGroup '$fsg'"
pass "pods run as UID 1000870000, GID 0, no fsGroup"

helm test "$RELEASE" -n "$NS" >"$OUT/helm-test.txt" 2>&1 || { cat "$OUT/helm-test.txt" >&2; fail "helm test failed"; }
pass "helm test"

# port-forward to the API Service (kubectl picks a free local port)
k port-forward "svc/$FULL-api" :80 >"$OUT/port-forward.log" 2>&1 &
PF_PID=$!
PORT=""
i=0
while [ $i -lt 30 ] && [ -z "$PORT" ]; do
    PORT="$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) -> .*/\1/p' "$OUT/port-forward.log" | head -1)"
    [ -z "$PORT" ] && sleep 1
    i=$((i + 1))
done
[ -n "$PORT" ] || fail "port-forward to $FULL-api did not start"
API="http://127.0.0.1:$PORT"

CURL=(curl -sS --max-time 20 -H @"$OUT/sender-key.header" -H 'Content-Type: application/json')

body='{"userId":"smoke-user","externalId":"smoke-single","policy":"NOT_DELIVERED","timeoutMinutes":10,"fallback":{"sms":"smoke"}}'
code="$("${CURL[@]}" -o "$OUT/single.json" -w '%{http_code}' -X POST "$API/v1/notifications" -d "$body")"
[ "$code" = 201 ] || fail "POST /v1/notifications -> $code, expected 201"
tid="$(sed -n 's/.*"trackingId":"\([^"]*\)".*/\1/p' "$OUT/single.json")"
[ -n "$tid" ] || fail "no trackingId in the 201 answer"
code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$API/v1/notifications/$tid")"
[ "$code" = 200 ] || fail "GET /v1/notifications/{id} -> $code, expected 200"
pass "POST /v1/notifications -> 201, GET -> 200 (through the API Service)"

code="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "$API/actuator/health")"
[ "$code" = 404 ] || fail "/actuator/health on the API port -> $code, expected 404"
pass "/actuator/health on the API Service port -> 404 (management port only)"

# ---- Review Focus 3: worker rolling restart while fallback webhooks are in flight
say "registering $NOTIFICATIONS notifications (timeoutMinutes 1)"
i=1
bad=0
while [ $i -le $NOTIFICATIONS ]; do
    b="{\"userId\":\"smoke-user-$i\",\"externalId\":\"smoke-$i\",\"policy\":\"NOT_DELIVERED\",\"timeoutMinutes\":1,\"fallback\":{\"sms\":\"smoke $i\"}}"
    c="$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$API/v1/notifications" -d "$b")"
    [ "$c" = 201 ] || bad=$((bad + 1))
    i=$((i + 1))
done
[ "$bad" = 0 ] || fail "$bad of $NOTIFICATIONS registrations did not answer 201"
pass "$NOTIFICATIONS notifications registered (201)"

psql() { k exec deploy/postgres -- psql -U reliablepush -d reliablepush -Atc "$1"; }
states() { psql "select coalesce(string_agg(state || '=' || n, ' ' order by state), '-') from (select state, count(*) n from notification where external_id ~ '^smoke-[0-9]+\$' group by state) s"; }

say "waiting for the notifications to fall due (1 real minute) and the worker to start dispatching"
deadline=$(($(date +%s) + 240))
while :; do
    s="$(states)"
    case "$s" in *DISPATCHING=*|*FALLBACK_TRIGGERED=*) break ;; esac
    [ "$(date +%s)" -lt "$deadline" ] || fail "no notification fell due within 240 s (states: $s)"
    sleep 2
done
say "dispatching started ($s)"

mkdir -p "$OUT/logs"
: >"$OUT/rollout-samples.txt"
: >"$OUT/rollout-terminating.txt"
(
    while :; do
        # "<pod> <deletionTimestamp> <graceSeconds>" of the terminating workers (deletionTimestamp = delete request + grace)
        tl="$(k get pods -l app.kubernetes.io/component=worker -o jsonpath='{range .items[*]}{.metadata.name} {.metadata.deletionTimestamp} {.metadata.deletionGracePeriodSeconds}{"\n"}{end}' 2>/dev/null | grep 'Z ')"
        [ -n "$tl" ] && printf '%s\n' "$tl" >>"$OUT/rollout-terminating.txt"
        term="$(printf '%s' "$tl" | grep -c .)"
        printf '%s terminating_worker_pods=%s %s\n' "$(date +%H:%M:%S)" "$term" "$(states 2>/dev/null)" >>"$OUT/rollout-samples.txt"
        sleep 1
    done
) &
SAMPLER_PID=$!

# remember the old worker pods and stream their logs: the pods (and their logs) disappear after the rollout
OLD_PODS="$(k get pods -l app.kubernetes.io/component=worker -o name | sed 's#^pod/##' | tr '\n' ' ')"
LOG_PIDS=""
for p in $OLD_PODS; do
    k logs -f "$p" >"$OUT/logs/$p.log" 2>&1 &
    LOG_PIDS="$LOG_PIDS $!"
done

say "kubectl rollout restart deploy/$FULL-worker"
k rollout restart "deploy/$FULL-worker" >/dev/null || fail "rollout restart failed"
k rollout status "deploy/$FULL-worker" --timeout=300s >/dev/null || fail "worker rollout after restart not complete within 300 s"
# the old pods may still be Terminating (grace 60 s) when the rollout is "complete": wait until they are gone, so the
# streamed logs contain the whole shutdown tail, then stop the streams
for p in $OLD_PODS; do
    k wait --for=delete "pod/$p" --timeout=90s >/dev/null 2>&1 || fail "old worker pod $p still exists 90 s after the rollout"
done
sleep 2
kill "$SAMPLER_PID" 2>/dev/null
SAMPLER_PID=""
# shellcheck disable=SC2086
kill $LOG_PIDS 2>/dev/null

# The rollout must really have hit calls in flight IN THE POD BEING STOPPED. SIGTERM moment of an old pod =
# deletionTimestamp - deletionGracePeriodSeconds (second resolution). Evidence from that pod's own saved log: a row it
# claimed (fallback.claimed) before SIGTERM and whose outcome it logged (fallback.dispatched) at or after SIGTERM.
inflight_total=0
for p in $OLD_PODS; do
    line="$(grep "^$p " "$OUT/rollout-terminating.txt" | head -1)"
    if [ -z "$line" ]; then # an idle worker can exit between two samples; the assertion below is over all old pods
        say "old worker $p: no deletionTimestamp sampled (terminated within one sampling interval)"
        continue
    fi
    dts="$(echo "$line" | cut -d' ' -f2)"
    grace="$(echo "$line" | cut -d' ' -f3)"
    # BSD date (macOS) first, then GNU date (Linux, WSL)
    epoch="$(date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$dts" +%s 2>/dev/null || date -u -d "$dts" +%s)" \
        || fail "cannot parse deletionTimestamp '$dts'"
    sigterm="$(date -u -r $((epoch - grace)) +%Y-%m-%dT%H:%M:%S.000Z 2>/dev/null \
        || date -u -d "@$((epoch - grace))" +%Y-%m-%dT%H:%M:%S.000Z)"
    n="$(awk -v s="$sigterm" '
        { if (match($0, /"@timestamp":"[^"]*"/)) ts = substr($0, RSTART + 14, RLENGTH - 15); else next
          if (match($0, /"rp.tracking_id":"[^"]*"/)) id = substr($0, RSTART + 17, RLENGTH - 18); else next
          if ($0 ~ /"event.action":"fallback.claimed"/) c[id] = ts
          else if ($0 ~ /"event.action":"fallback.dispatched"/ && (id in c) && c[id] < s && ts >= s) n++ }
        END { print n + 0 }' "$OUT/logs/$p.log")"
    say "old worker $p: SIGTERM at $sigterm, $n rows in flight (claimed before, dispatched after SIGTERM, same pod)"
    inflight_total=$((inflight_total + n))
done
if [ "$inflight_total" -ge 1 ]; then
    pass "SIGTERM hit worker pods with webhook calls in flight: $inflight_total rows claimed before and completed after SIGTERM by the same pod"
else
    cat "$OUT/rollout-samples.txt" >&2
    fail "no old worker pod had a call in flight at SIGTERM: the restart did not hit calls in flight (check is vacuous)"
fi

say "waiting for all $NOTIFICATIONS to be FALLBACK_TRIGGERED"
deadline=$(($(date +%s) + 300))
while :; do
    s="$(states)"
    [ "$s" = "FALLBACK_TRIGGERED=$NOTIFICATIONS" ] && break
    [ "$(date +%s)" -lt "$deadline" ] || fail "not all notifications FALLBACK_TRIGGERED within 300 s (states: $s)"
    sleep 3
done

# via the list API (paged, newest first)
count=0
cursor=""
while :; do
    if [ -n "$cursor" ]; then
        page="$("${CURL[@]}" -G "$API/v1/notifications" --data-urlencode "state=FALLBACK_TRIGGERED" --data-urlencode "limit=100" --data-urlencode "cursor=$cursor")"
    else
        page="$("${CURL[@]}" -G "$API/v1/notifications" --data-urlencode "state=FALLBACK_TRIGGERED" --data-urlencode "limit=100")"
    fi
    n="$(printf '%s' "$page" | grep -o '"trackingId"' | wc -l | tr -d ' ')"
    count=$((count + n))
    cursor="$(printf '%s' "$page" | sed -n 's/.*"nextCursor":"\([^"]*\)".*/\1/p')"
    [ -n "$cursor" ] || break
done
[ "$count" = "$NOTIFICATIONS" ] || fail "list API: $count notifications FALLBACK_TRIGGERED, expected $NOTIFICATIONS"
pass "list API: all $NOTIFICATIONS notifications are FALLBACK_TRIGGERED after the worker rollout"

hooks="$(k logs deploy/fallback-dummy | grep -o 'HOOK /hook' | wc -l | tr -d ' ')"
expected=$((NOTIFICATIONS + PROBE_HOOKS))
[ "$hooks" = "$expected" ] || fail "dummy receiver saw $hooks calls, expected $expected ($NOTIFICATIONS + $PROBE_HOOKS probe): a webhook was re-sent or lost"
pass "dummy receiver saw exactly $NOTIFICATIONS fallback calls (no re-send)"

# ---- logs: no ERROR, no fallback.reclaimed (terminated workers included: saved while they ran)
collect_logs
nlogs="$(ls "$OUT/logs" | grep -c 'reliable-push')"
[ "$nlogs" -ge 5 ] || fail "expected logs of at least 5 backend pods (1 API, 2 terminated and 2 new workers), found $nlogs"
if grep -l '"log.level":"ERROR"' "$OUT"/logs/"$FULL"-*.log >"$OUT/error-files.txt" 2>/dev/null && [ -s "$OUT/error-files.txt" ]; then
    grep -h '"log.level":"ERROR"' "$OUT"/logs/"$FULL"-*.log | head -5 >&2
    fail "ERROR log lines in: $(tr '\n' ' ' <"$OUT/error-files.txt")"
fi
pass "no \"log.level\":\"ERROR\" line in $nlogs backend pod logs (terminated workers included)"
if grep -q '"event.action":"fallback.reclaimed"' "$OUT"/logs/"$FULL"-*.log; then
    fail "fallback.reclaimed event found in the pod logs"
fi
pass "no fallback.reclaimed event in the pod logs"
grep -h '"event.action":"fallback' "$OUT"/logs/"$FULL"-worker-*.log | sed 's/.*"event.action":"\([^"]*\)".*/\1/' | sort | uniq -c >"$OUT/fallback-events.txt"
say "fallback events: $(tr -s ' ' <"$OUT/fallback-events.txt" | tr '\n' ';')"

exit 0
