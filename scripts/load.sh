#!/usr/bin/env bash
# Local load test (NFR-2 and the fallback burst). Local only: never run in CI.
#
#   ./scripts/load.sh [--simulation ingest|burst|all] [--api-pool N] [--worker-concurrency N] [--worker-pool N]
#                     [--workers 1|2] [--keep]
#
# Defaults: all, 10, 100, 10, 1. Starts the harness compose stack with loadtest/compose/load.override.yml (project
# reliable-push-load, real clock, API limited to 2 CPUs / 1 GiB) plus a Gatling container on the compose network, runs
# the Gatling simulations (EventIngestSimulation, FallbackBurstSimulation + BurstDrainProbe) and writes, in
# harness/build/load/: gatling/<simulation>/ (HTML report), burst.json (probe), summary.json (one entry per simulation:
# status PASSED|FAILED|INVALID, metrics, config, generatorCpuAvgPercent). Exit 0 only if every simulation PASSED.
# A run whose Gatling container averages more than 90 % CPU is INVALID (the generator was saturated).
# Simulation settings come from the environment: LOAD_DEVICES, LOAD_NOTIFICATIONS_PER_SECOND, LOAD_RAMP, LOAD_STEADY,
# LOAD_BURST_SIZE (multiple of LOAD_BURST_PER_SECOND), LOAD_BURST_PER_SECOND, LOAD_READY_TIMEOUT (seconds, default 120).
# Without --keep the stack is removed at the end (down -v); with --keep it keeps running.
set -euo pipefail

RP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RP_PROJECT="reliable-push-load"
RP_OUT="$RP_ROOT/harness/build/load"
RP_TAG="load"
RP_COMPOSE_OVERRIDES=("$RP_ROOT/loadtest/compose/load.override.yml")
# shellcheck source=lib/common.sh
. "$RP_ROOT/scripts/lib/common.sh"
export COMPOSE_PROFILES=load

usage() {
    sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

SIMULATION=all API_POOL=10 WORKER_CONCURRENCY=100 WORKER_POOL=10 WORKERS=1 KEEP=0
need_arg() { [ $# -ge 2 ] || { usage >&2; rp_die "$1 needs a value"; }; }
need_int() { case "$2" in '' | *[!0-9]* | 0) rp_die "$1 must be a positive whole number, got '$2'" ;; esac; }
while [ $# -gt 0 ]; do
    case "$1" in
        --simulation) need_arg "$@"; SIMULATION="$2"; shift ;;
        --api-pool) need_arg "$@"; API_POOL="$2"; shift ;;
        --worker-concurrency) need_arg "$@"; WORKER_CONCURRENCY="$2"; shift ;;
        --worker-pool) need_arg "$@"; WORKER_POOL="$2"; shift ;;
        --workers) need_arg "$@"; WORKERS="$2"; shift ;;
        --keep) KEEP=1 ;;
        -h | --help) usage; exit 0 ;;
        *) usage >&2; rp_die "unknown argument: $1" ;;
    esac
    shift
done
case "$SIMULATION" in ingest | burst | all) ;; *) rp_die "--simulation must be ingest, burst or all, got '$SIMULATION'" ;; esac
case "$WORKERS" in 1 | 2) ;; *) rp_die "--workers must be 1 or 2, got '$WORKERS'" ;; esac
need_int --api-pool "$API_POOL"
need_int --worker-concurrency "$WORKER_CONCURRENCY"
need_int --worker-pool "$WORKER_POOL"
READY_TIMEOUT="${LOAD_READY_TIMEOUT:-120}"
need_int LOAD_READY_TIMEOUT "$READY_TIMEOUT"
export LOAD_API_POOL="$API_POOL" LOAD_WORKER_CONCURRENCY="$WORKER_CONCURRENCY" LOAD_WORKER_POOL="$WORKER_POOL"

BURST_SIZE="${LOAD_BURST_SIZE:-30000}"
BURST_PER_SECOND="${LOAD_BURST_PER_SECOND:-1500}"
case "$BURST_SIZE$BURST_PER_SECOND" in '' | *[!0-9]*) rp_die "LOAD_BURST_SIZE and LOAD_BURST_PER_SECOND must be whole numbers" ;; esac
[ $((BURST_SIZE % BURST_PER_SECOND)) -eq 0 ] || rp_die "LOAD_BURST_SIZE must be a multiple of LOAD_BURST_PER_SECOND"

rp_require_tools
if [ -n "$(rp_compose ps -a -q 2>/dev/null)" ]; then
    rp_log "removing the stack left by a previous run (project $RP_PROJECT)"
    rp_compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
rp_check_ports 8080 8090

SERVICES="postgres api worker-a harness gatling"
[ "$WORKERS" = 2 ] && SERVICES="postgres api worker-a worker-b harness gatling"
WORKER_SERVICES="worker-a"
[ "$WORKERS" = 2 ] && WORKER_SERVICES="worker-a worker-b"

rm -rf "$RP_OUT"
mkdir -p "$RP_OUT/logs" "$RP_OUT/gatling"
SAMPLER_PID=""
cleanup() {
    [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true
    if [ "$KEEP" = 1 ]; then
        rp_log "stack kept running; stop it with: docker compose -p $RP_PROJECT -f harness/compose/docker-compose.yml -f loadtest/compose/load.override.yml --profile load down -v"
    else
        rp_compose down -v --remove-orphans >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
failed() {
    rp_compose logs --no-color --tail 40 >&2 2>/dev/null || true
    rp_die "$1"
}

rp_log "building images (backend, harness, gatling runner); output in harness/build/load/logs/build.log"
if ! rp_compose build >"$RP_OUT/logs/build.log" 2>&1; then
    tail -n 40 "$RP_OUT/logs/build.log" >&2
    rp_die "image build failed (harness/build/load/logs/build.log)"
fi
rp_log "generating per-run secrets"
rp_compose run --rm -q secrets >"$RP_OUT/logs/secrets.log" 2>&1 \
    || { cat "$RP_OUT/logs/secrets.log" >&2; rp_die "secret generation failed"; }
# real clock: the backend must not run the fast clock
grep -v '^RELIABLEPUSH_TESTING_MINUTEDURATION=' "$RP_OUT/secrets.env" >"$RP_OUT/secrets.env.tmp"
mv "$RP_OUT/secrets.env.tmp" "$RP_OUT/secrets.env"

rp_log "starting the stack: $SERVICES (api pool $API_POOL, $WORKERS worker(s), concurrency $WORKER_CONCURRENCY, worker pool $WORKER_POOL)"
# shellcheck disable=SC2086
rp_compose up -d $SERVICES >"$RP_OUT/logs/up.log" 2>&1 || { cat "$RP_OUT/logs/up.log" >&2; failed "docker compose up failed"; }

deadline=$(($(date +%s) + READY_TIMEOUT))
pending="api $WORKER_SERVICES harness gatling"
while :; do
    still=""
    for svc in $pending; do
        state="$(rp_service_state "$svc")"
        [ "$state" = "running" ] || failed "container $svc is not running (state: ${state:-none})"
        case "$svc" in
            api) rp_http_ok "$RP_API_URL/actuator/health/readiness" || still="$still $svc" ;;
            harness) rp_http_ok "$RP_HARNESS_URL/actuator/health/readiness" || still="$still $svc" ;;
            gatling) ;;
            *) rp_container_ready "$svc" 8081 || still="$still $svc" ;; # workers: management port 8081
        esac
    done
    pending="${still# }"
    [ -z "$pending" ] && break
    [ "$(date +%s)" -lt "$deadline" ] || failed "container(s) not ready after ${READY_TIMEOUT}s: $pending"
    sleep 1
done
rp_log "stack ready"

# --- Gatling container helpers ---------------------------------------------------------------------------------
GATLING_ENV=()
for v in LOAD_DEVICES LOAD_NOTIFICATIONS_PER_SECOND LOAD_RAMP LOAD_STEADY LOAD_BURST_SIZE LOAD_BURST_PER_SECOND LOAD_APP_ID; do
    [ -n "${!v:-}" ] && GATLING_ENV+=(-e "$v=${!v}")
done
gexec() { rp_compose exec -T ${GATLING_ENV[@]+"${GATLING_ENV[@]}"} gatling "$@"; }
GATLING_CID="$(rp_compose ps -q gatling)"

rp_log "compiling loadtest and downloading dependencies (first run only); output in harness/build/load/logs/prepare.log"
gexec ./mvnw -B -pl loadtest test-compile >"$RP_OUT/logs/prepare.log" 2>&1 \
    || { tail -n 40 "$RP_OUT/logs/prepare.log" >&2; failed "loadtest compile failed"; }

# CPU of the Gatling container every 5 s into $1 (docker stats percent: 100 % = one core = the container's limit)
start_sampler() {
    : >"$1"
    (while :; do
        docker stats --no-stream --format '{{.CPUPerc}}' "$GATLING_CID" >>"$1" 2>/dev/null || true
        sleep 5
    done) &
    SAMPLER_PID=$!
}
stop_sampler() {
    kill "$SAMPLER_PID" >/dev/null 2>&1 || true
    wait "$SAMPLER_PID" 2>/dev/null || true
    SAMPLER_PID=""
}
avg_cpu() { # file -> average percent with one decimal ("0.0" without samples)
    tr -d '%' <"$1" | awk 'NF { s += $1; n++ } END { printf "%.1f", n ? s / n : 0 }'
}

ENTRIES=()
ALL_PASSED=1
CONFIG_JSON="{\"apiCpus\":2,\"apiMemory\":\"1g\",\"apiPool\":$API_POOL,\"workers\":$WORKERS,\"workerConcurrency\":$WORKER_CONCURRENCY,\"workerPool\":$WORKER_POOL}"

# run_simulation <name> <simulation class> <metrics json file or ''> <extra pass condition: 0|1>
# Sets SIM_STATUS and SIM_CPU; appends to ENTRIES.
run_gatling() {
    local name="$1" class="$2" samples="$RP_OUT/logs/cpu-$1.txt" rc=0 report
    rm -rf "$RP_ROOT/loadtest/target/gatling"
    rp_log "running $class (log: harness/build/load/logs/gatling-$name.log)"
    start_sampler "$samples"
    gexec ./mvnw -B -pl loadtest gatling:test "-Dgatling.simulationClass=io.reliablepush.loadtest.$class" \
        >"$RP_OUT/logs/gatling-$name.log" 2>&1 || rc=$?
    stop_sampler
    SIM_CPU="$(avg_cpu "$samples")"
    report="$(ls -dt "$RP_ROOT"/loadtest/target/gatling/*/ 2>/dev/null | head -n 1 || true)"
    # Gatling 3.16 writes no assertions file: the result lines of the console summary are the source
    SIM_ASSERTIONS="$(grep -E ' : (true|false) \(actual : ' "$RP_OUT/logs/gatling-$name.log" \
        | awk 'BEGIN { printf "{\"assertions\":[" }
            { line = $0; sub(/ : (true|false) \(actual : .*$/, "", line); gsub(/["\\]/, "", line)
              ok = ($0 ~ / : true \(actual/) ? "true" : "false"; act = $0; sub(/^.*actual : /, "", act); sub(/\).*$/, "", act)
              printf "%s{\"name\":\"%s\",\"passed\":%s,\"actual\":%s}", (n++ ? "," : ""), line, ok, act }
            END { printf "]}" }')"
    if [ -n "$report" ]; then
        rm -rf "$RP_OUT/gatling/$name"
        cp -R "$report" "$RP_OUT/gatling/$name"
    fi
    tail -n 25 "$RP_OUT/logs/gatling-$name.log"
    return "$rc"
}

finish() { # <name> <ok 0|1> <metrics json>
    local status
    if awk -v c="$SIM_CPU" 'BEGIN { exit !(c > 90) }'; then
        status=INVALID
    elif [ "$2" = 1 ]; then status=PASSED
    else status=FAILED; fi
    [ "$status" = PASSED ] || ALL_PASSED=0
    rp_log "$1: $status (generator CPU average $SIM_CPU %)"
    ENTRIES+=("{\"simulation\":\"$1\",\"status\":\"$status\",\"metrics\":$3,\"config\":$CONFIG_JSON,\"generatorCpuAvgPercent\":$SIM_CPU}")
}

if [ "$SIMULATION" = ingest ] || [ "$SIMULATION" = all ]; then
    ok=1
    run_gatling ingest EventIngestSimulation || ok=0
    finish ingest "$ok" "$SIM_ASSERTIONS"
fi

if [ "$SIMULATION" = burst ] || [ "$SIMULATION" = all ]; then
    ok=1
    METRICS_URLS="http://worker-a:8081/actuator/prometheus"
    [ "$WORKERS" = 2 ] && METRICS_URLS="$METRICS_URLS http://worker-b:8081/actuator/prometheus"
    rm -f "$RP_OUT/burst.json"
    rp_log "starting BurstDrainProbe (expected $BURST_SIZE rows, timeout 20m) before the burst"
    # shellcheck disable=SC2086
    rp_compose exec -T -d gatling java -cp loadtest/target/classes io.reliablepush.loadtest.BurstDrainProbe \
        --metrics $METRICS_URLS --expected "$BURST_SIZE" --timeout 20m --out /work/harness/build/load/burst.json
    sleep 3 # baseline sample before the first notification
    run_gatling burst FallbackBurstSimulation || ok=0
    rp_log "waiting for the backlog to drain (probe timeout 20m)"
    probe_deadline=$(($(date +%s) + 20 * 60 + 90))
    while [ ! -s "$RP_OUT/burst.json" ] && [ "$(date +%s)" -lt "$probe_deadline" ]; do sleep 2; done
    if [ -s "$RP_OUT/burst.json" ]; then
        PROBE_JSON="$(tr -d '\n' <"$RP_OUT/burst.json")"
        case "$PROBE_JSON" in *'"status":"PASSED"'*) ;; *) ok=0 ;; esac
    else
        PROBE_JSON="null"
        ok=0
    fi
    finish burst "$ok" "$PROBE_JSON"
fi

SUMMARY="["
for i in "${!ENTRIES[@]}"; do
    [ "$i" -gt 0 ] && SUMMARY="$SUMMARY,"
    SUMMARY="$SUMMARY${ENTRIES[$i]}"
done
printf '%s]\n' "$SUMMARY" >"$RP_OUT/summary.json"
rp_log "summary: harness/build/load/summary.json"
rp_collect_logs || true
[ "$ALL_PASSED" = 1 ]
