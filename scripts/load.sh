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
# config records the effective settings (rates, ramp, steady, devices, burst, API and worker limits read from the
# containers; 0 or unset limits as "unlimited") and nfr2Criterion: true only when the run used the NFR-2 settings
# (>= 1000 events/s, >= 500 notifications/s, 60 s ramp, >= 300 s steady, API limited to 2 CPUs / 1g). Only an ingest
# entry with status PASSED and nfr2Criterion true validates NFR-2.
# A run whose Gatling container averages more than 90 % CPU during injection is INVALID (the generator was saturated):
# CPU is sampled (docker stats, every 5 s) from the start of the injection to the end of the simulation; the ingest ramp
# and the burst warm-up are excluded. A simulation is PASSED only if Gatling exited 0 and every expected assertion
# result line was parsed and true; otherwise FAILED with a "reason" in summary.json.
# Simulation settings come from the environment: LOAD_DEVICES, LOAD_NOTIFICATIONS_PER_SECOND, LOAD_RAMP, LOAD_STEADY,
# LOAD_BURST_SIZE (multiple of LOAD_BURST_PER_SECOND), LOAD_BURST_PER_SECOND, LOAD_BURST_WARMUP (default 30s: requests
# ramping up to the burst rate before the measured burst, not asserted), LOAD_READY_TIMEOUT (seconds, default 120).
# Worker container limits (default none): LOAD_WORKER_CPUS (e.g. 1), LOAD_WORKER_MEMORY (e.g. 768m); recorded in config.
# Without --keep the stack is removed at the end (down -v); with --keep it keeps running. The Maven cache of the Gatling
# runner is the external volume reliable-push-gatling-m2 and survives `down -v`; purge it with
#   docker volume rm reliable-push-gatling-m2
set -euo pipefail

RP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RP_PROJECT="reliable-push-load"
RP_OUT="$RP_ROOT/harness/build/load"
RP_TAG="load"
RP_COMPOSE_OVERRIDES=("$RP_ROOT/loadtest/compose/load.override.yml")
# shellcheck source=lib/common.sh
. "$RP_ROOT/scripts/lib/common.sh"
export COMPOSE_PROFILES=load

# --- pure helpers (unit-checkable: LOAD_SH_SOURCE_ONLY=1 . scripts/load.sh) ---------------------------------------
# stdin: Gatling console output; stdout: {"assertions":[{"name","passed","actual"}]}. A non-numeric actual is passed:false.
parse_assertions() {
    { grep -E ' : (true|false) \(actual : ' || true; } | awk 'BEGIN { printf "{\"assertions\":[" }
        { line = $0; sub(/ : (true|false) \(actual : .*$/, "", line); gsub(/["\\]/, "", line)
          ok = ($0 ~ / : true \(actual/) ? "true" : "false"
          act = $0; sub(/^.*actual : /, "", act); sub(/\).*$/, "", act)
          if (act !~ /^-?[0-9]+(\.[0-9]+)?$/) { act = "null"; ok = "false" }
          printf "%s{\"name\":\"%s\",\"passed\":%s,\"actual\":%s}", (n++ ? "," : ""), line, ok, act }
        END { printf "]}" }'
}
# gatling_reason <exit code> <expected assertion count> <assertions json> -> empty when fine, else the failure reason
gatling_reason() {
    local n f
    n="$(printf '%s' "$3" | grep -o '"passed":' | wc -l | tr -d ' ')"
    f="$(printf '%s' "$3" | grep -o '"passed":false' | wc -l | tr -d ' ')"
    if [ "$1" != 0 ]; then printf 'gatling exited with code %s' "$1"
    elif [ "$n" -lt "$2" ]; then printf 'expected %s assertion results, parsed %s' "$2" "$n"
    elif [ "$f" -gt 0 ]; then printf '%s assertion(s) failed' "$f"
    fi
}
# top-level "status" of the probe JSON (the last field of the flat object)
probe_status() { printf '%s' "$1" | sed -n 's/.*"status":"\([A-Z]*\)"}[[:space:]]*$/\1/p'; }
# whole seconds of 90s | 5m | 1h | 500ms | 90 | PT90S | PT5M (empty when unsupported)
duration_seconds() {
    case "$1" in
        *ms) [ -n "${1%ms}" ] && printf '%s' $(((${1%ms} + 999) / 1000)) ;;
        *s) printf '%s' "${1%s}" ;;
        *m) printf '%s' $((${1%m} * 60)) ;;
        *h) printf '%s' $((${1%h} * 3600)) ;;
        PT*S) printf '%s' "${1#PT}" | tr -d 'S' ;;
        PT*M) printf '%s' $(($(printf '%s' "${1#PT}" | tr -d 'M') * 60)) ;;
        '' | *[!0-9]*) ;;
        *) printf '%s' "$1" ;;
    esac
}
# average of the second column of a "<epoch> <percent>" sample file for lines with epoch >= $2 (empty without samples)
avg_cpu() {
    awk -v from="$2" '$1 ~ /^[0-9]+$/ && $1 >= from && $2 != "" { gsub(/%/, "", $2); s += $2; n++ } END { if (n) printf "%.1f", s / n }' "$1"
}
# "unlimited" for an unset or 0 limit, else the value
limit_label() { case "$1" in '' | 0) printf unlimited ;; *) printf '%s' "$1" ;; esac; }
# docker HostConfig.NanoCpus -> CPUs (2000000000 -> 2), 0 -> unlimited
cpus_label() { awk -v n="$1" 'BEGIN { if (n + 0 == 0) printf "unlimited"; else printf "%g", n / 1e9 }'; }
# docker HostConfig.Memory (bytes) -> 1g / 768m / bytes, 0 -> unlimited
mem_label() {
    awk -v b="$1" 'BEGIN { g = 1073741824; m = 1048576
        if (b + 0 == 0) printf "unlimited"; else if (b % g == 0) printf "%dg", b / g
        else if (b % m == 0) printf "%dm", b / m; else printf "%d", b }'
}
# nfr2_criterion <notifications/s> <ramp s> <steady s> <api cpus> <api memory> -> true|false: the run used the NFR-2
# settings (each notification sends two events: >= 1000 events/s, >= 500 notifications/s, ramp 60 s, steady >= 300 s,
# API limited to 2 CPUs / 1g)
nfr2_criterion() {
    if [ $(($1 * 2)) -ge 1000 ] && [ "$1" -ge 500 ] && [ "$2" -eq 60 ] && [ "$3" -ge 300 ]         && [ "$4" = 2 ] && [ "$5" = 1g ]; then
        printf true
    else
        printf false
    fi
}
[ "${LOAD_SH_SOURCE_ONLY:-0}" = 1 ] && return 0

usage() {
    awk 'NR > 1 && /^set -euo pipefail/ { exit } NR > 1' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
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
[[ "${LOAD_WORKER_CPUS:-1}" =~ ^[0-9]+(\.[0-9]+)?$ ]] || rp_die "LOAD_WORKER_CPUS must be a number like 1 or 0.5, got '${LOAD_WORKER_CPUS}'"
[[ "${LOAD_WORKER_MEMORY:-1g}" =~ ^[0-9]+[kmg]?$ ]] || rp_die "LOAD_WORKER_MEMORY must be like 768m or 1g, got '${LOAD_WORKER_MEMORY}'"
need_int LOAD_READY_TIMEOUT "$READY_TIMEOUT"
export LOAD_API_POOL="$API_POOL" LOAD_WORKER_CONCURRENCY="$WORKER_CONCURRENCY" LOAD_WORKER_POOL="$WORKER_POOL"

BURST_SIZE="${LOAD_BURST_SIZE:-30000}"
BURST_PER_SECOND="${LOAD_BURST_PER_SECOND:-1500}"
case "$BURST_SIZE$BURST_PER_SECOND" in '' | *[!0-9]*) rp_die "LOAD_BURST_SIZE and LOAD_BURST_PER_SECOND must be whole numbers" ;; esac
[ "$BURST_PER_SECOND" -gt 0 ] && [ "$BURST_SIZE" -gt 0 ] || rp_die "LOAD_BURST_SIZE and LOAD_BURST_PER_SECOND must be greater than 0"
[ $((BURST_SIZE % BURST_PER_SECOND)) -eq 0 ] || rp_die "LOAD_BURST_SIZE must be a multiple of LOAD_BURST_PER_SECOND"

RAMP_SECONDS="$(duration_seconds "${LOAD_RAMP:-60s}")"
[ -n "$RAMP_SECONDS" ] || rp_die "LOAD_RAMP must be like 90s, 5m, 1h, 90 or PT90S (needed to exclude the ramp from the CPU average)"
STEADY_SECONDS="$(duration_seconds "${LOAD_STEADY:-300s}")"
[ -n "$STEADY_SECONDS" ] || rp_die "LOAD_STEADY must be like 90s, 5m, 1h, 90 or PT90S (recorded in summary.json)"
WARMUP_SECONDS="$(duration_seconds "${LOAD_BURST_WARMUP:-30s}")"
[ -n "$WARMUP_SECONDS" ] || rp_die "LOAD_BURST_WARMUP must be like 30s, 1m, 30 or PT30S"
# effective simulation settings (the defaults of LoadConfig), recorded in summary.json
NOTIFICATIONS_PER_SECOND="${LOAD_NOTIFICATIONS_PER_SECOND:-500}"
DEVICES="${LOAD_DEVICES:-2000}"
need_int LOAD_NOTIFICATIONS_PER_SECOND "$NOTIFICATIONS_PER_SECOND"
need_int LOAD_DEVICES "$DEVICES"

rp_require_tools
if [ -n "$(rp_compose ps -a -q 2>/dev/null)" ]; then
    rp_log "removing the stack left by a previous run (project $RP_PROJECT)"
    rp_compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
rp_check_ports 8080 8090 8091

SERVICES="postgres api worker-a harness gatling"
[ "$WORKERS" = 2 ] && SERVICES="postgres api worker-a worker-b harness gatling"
WORKER_SERVICES="worker-a"
[ "$WORKERS" = 2 ] && WORKER_SERVICES="worker-a worker-b"

rm -rf "$RP_OUT"
mkdir -p "$RP_OUT/logs" "$RP_OUT/gatling"
SAMPLER_PID=""
cleanup() {
    if [ -n "$SAMPLER_PID" ]; then
        pkill -P "$SAMPLER_PID" >/dev/null 2>&1 || true
        kill "$SAMPLER_PID" >/dev/null 2>&1 || true
    fi
    if [ "$KEEP" = 1 ]; then
        rp_log "stack kept running; stop it with: docker compose -p $RP_PROJECT -f harness/compose/docker-compose.yml -f loadtest/compose/load.override.yml --profile load down -v"
    else
        rp_compose down -v --remove-orphans >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
failed() {
    rp_compose logs --no-color --tail 40 >&2 2>/dev/null || true
    rp_die "$1"
}

# Maven cache of the Gatling runner: external volume, kept across runs (down -v does not remove it)
docker volume inspect reliable-push-gatling-m2 >/dev/null 2>&1 || docker volume create reliable-push-gatling-m2 >/dev/null
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
# effective container limits (what the containers really got, not what was asked for)
limits_of() { docker inspect -f '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' "$(rp_compose ps -q "$1")"; }
read -r api_nano api_mem <<<"$(limits_of api)"
read -r worker_nano worker_mem <<<"$(limits_of worker-a)"
[ -n "${api_nano:-}" ] && [ -n "${worker_nano:-}" ] || failed "cannot read the API and worker container limits"
API_CPUS="$(cpus_label "$api_nano")" API_MEMORY="$(mem_label "$api_mem")"
WORKER_CPUS="$(cpus_label "$worker_nano")" WORKER_MEMORY="$(mem_label "$worker_mem")"
NFR2_CRITERION="$(nfr2_criterion "$NOTIFICATIONS_PER_SECOND" "$RAMP_SECONDS" "$STEADY_SECONDS" "$API_CPUS" "$API_MEMORY")"
rp_log "API limits $API_CPUS CPUs / $API_MEMORY, worker limits $WORKER_CPUS CPUs / $WORKER_MEMORY; NFR-2 settings: $NFR2_CRITERION"

# --- Gatling container helpers ---------------------------------------------------------------------------------
# only the three keys the simulations need are passed (as names: the values stay out of argv)
GATLING_ENV=()
for k in HARNESS_APPS_SHOP_SENDERAPIKEY HARNESS_APPS_SHOP_GRANTSECRET HARNESS_APPS_SHOP_GRANTISSUER; do
    val="$(sed -n "s/^$k=//p" "$RP_OUT/secrets.env" | head -n 1)"
    [ -n "$val" ] || rp_die "$k missing in harness/build/load/secrets.env"
    export "$k=$val"
    GATLING_ENV+=(-e "$k")
done
for v in LOAD_DEVICES LOAD_NOTIFICATIONS_PER_SECOND LOAD_RAMP LOAD_STEADY LOAD_BURST_SIZE LOAD_BURST_PER_SECOND \
    LOAD_BURST_WARMUP LOAD_APP_ID; do
    [ -n "${!v:-}" ] && GATLING_ENV+=(-e "$v=${!v}")
done
gexec() { rp_compose exec -T ${GATLING_ENV[@]+"${GATLING_ENV[@]}"} gatling "$@"; }
GATLING_CID="$(rp_compose ps -q gatling)"

rp_log "compiling loadtest and resolving the Gatling plugin (downloads on the first run only); output in harness/build/load/logs/prepare.log"
gexec ./mvnw -B -pl loadtest test-compile gatling:help >"$RP_OUT/logs/prepare.log" 2>&1 \
    || { tail -n 40 "$RP_OUT/logs/prepare.log" >&2; failed "loadtest compile failed"; }

# CPU of the Gatling container: from the start of the injection (simulation.log exists and the devices are
# provisioned) until Gatling starts generating the report, a sample at once and then every 5 s.
# Lines: "S <start epoch>", then "<epoch> <percent>" (docker stats: 100 % = one core = the container's limit).
# $1 samples file, $2 gatling log, $3 1 when the log prints "Provisioned" before the injection, $4 done-flag file
cpu_sampler() {
    local out="$1" log="$2" prov="$3" doneflag="$4" i t
    sample() { printf '%s %s\n' "$(date +%s)" "$(docker stats --no-stream --format '{{.CPUPerc}}' "$GATLING_CID" 2>/dev/null | head -n 1)" >>"$out"; }
    until { ls "$RP_ROOT"/loadtest/target/gatling/*/simulation.log >/dev/null 2>&1 \
        && { [ "$prov" = 0 ] || grep -q '^Provisioned ' "$log" 2>/dev/null; }; }; do
        [ -f "$doneflag" ] && return 0
        sleep 1
    done
    printf 'S %s\n' "$(date +%s)" >"$out"
    while :; do
        sample
        for t in 1 2 3 4 5; do
            if [ -f "$doneflag" ] || grep -q '^Generating reports' "$log" 2>/dev/null; then return 0; fi
            sleep 1
        done
    done
}

ENTRIES=()
ALL_PASSED=1
CONFIG_JSON="{\"notificationsPerSecond\":$NOTIFICATIONS_PER_SECOND,\"eventsPerSecond\":$((NOTIFICATIONS_PER_SECOND * 2))"
CONFIG_JSON="$CONFIG_JSON,\"rampSeconds\":$RAMP_SECONDS,\"steadySeconds\":$STEADY_SECONDS,\"devices\":$DEVICES"
CONFIG_JSON="$CONFIG_JSON,\"burstSize\":$BURST_SIZE,\"burstPerSecond\":$BURST_PER_SECOND,\"burstWarmUpSeconds\":$WARMUP_SECONDS"
CONFIG_JSON="$CONFIG_JSON,\"apiCpus\":\"$API_CPUS\",\"apiMemory\":\"$API_MEMORY\",\"apiPool\":$API_POOL,\"workers\":$WORKERS"
CONFIG_JSON="$CONFIG_JSON,\"workerConcurrency\":$WORKER_CONCURRENCY,\"workerPool\":$WORKER_POOL"
CONFIG_JSON="$CONFIG_JSON,\"workerCpus\":\"$WORKER_CPUS\",\"workerMemory\":\"$WORKER_MEMORY\",\"nfr2Criterion\":$NFR2_CRITERION}"

# run_gatling <name> <simulation class> <expected assertion count> <skip ramp seconds>
# Sets SIM_RC, SIM_REASON, SIM_ASSERTIONS, SIM_CPU (used for INVALID), SIM_CPU_INJECTION, SIM_CPU_STEADY
run_gatling() {
    local name="$1" class="$2" expected="$3" skip="$4" samples="$RP_OUT/logs/cpu-$1.txt" glog="$RP_OUT/logs/gatling-$1.log"
    # both simulations provision their devices first (the burst's users need one: NO_DEVICE_REGISTERED never falls back)
    local doneflag="$RP_OUT/logs/cpu-$1.done" prov=1 report start
    rm -rf "$RP_ROOT/loadtest/target/gatling" "$doneflag"
    : >"$samples"
    : >"$glog"
    rp_log "running $class (log: harness/build/load/logs/gatling-$name.log)"
    cpu_sampler "$samples" "$glog" "$prov" "$doneflag" &
    SAMPLER_PID=$!
    SIM_RC=0
    gexec ./mvnw -B -pl loadtest gatling:test "-Dgatling.simulationClass=io.reliablepush.loadtest.$class" \
        >"$glog" 2>&1 || SIM_RC=$?
    touch "$doneflag"
    wait "$SAMPLER_PID" 2>/dev/null || true
    SAMPLER_PID=""
    start="$(sed -n 's/^S //p' "$samples" | head -n 1)"
    SIM_CPU_INJECTION=""
    SIM_CPU_STEADY=""
    if [ -n "$start" ]; then
        SIM_CPU_INJECTION="$(avg_cpu "$samples" "$start")"
        SIM_CPU_STEADY="$(avg_cpu "$samples" $((start + skip)))"
    fi
    if [ "$skip" -gt 0 ]; then SIM_CPU="$SIM_CPU_STEADY"; else SIM_CPU="$SIM_CPU_INJECTION"; fi
    report="$(ls -dt "$RP_ROOT"/loadtest/target/gatling/*/ 2>/dev/null | head -n 1 || true)"
    if [ -n "$report" ]; then
        rm -rf "$RP_OUT/gatling/$name"
        cp -R "$report" "$RP_OUT/gatling/$name"
    fi
    # Gatling 3.16 writes no assertions file: the result lines of the console summary are the source
    SIM_ASSERTIONS="$(parse_assertions <"$glog")"
    SIM_REASON="$(gatling_reason "$SIM_RC" "$expected" "$SIM_ASSERTIONS")"
    tail -n 25 "$glog"
}

jnum() { if [ -n "$1" ]; then printf '%s' "$1"; else printf 'null'; fi; }

# finish <name> <reason or ''> <metrics json>   (uses SIM_CPU*)
finish() {
    local status=PASSED reason="$2"
    [ -z "$reason" ] || status=FAILED
    if [ -z "$SIM_CPU" ]; then
        status=INVALID
        reason="${reason:+$reason; }no CPU samples during the injection"
    elif awk -v c="$SIM_CPU" 'BEGIN { exit !(c > 90) }'; then
        status=INVALID
        reason="${reason:+$reason; }generator CPU average above 90 %"
    fi
    [ "$status" = PASSED ] || ALL_PASSED=0
    rp_log "$1: $status (generator CPU average ${SIM_CPU:-n/a} %${reason:+; $reason})"
    ENTRIES+=("{\"simulation\":\"$1\",\"status\":\"$status\",\"reason\":$([ -n "$reason" ] && printf '"%s"' "$reason" || printf null),\"metrics\":$3,\"config\":$CONFIG_JSON,\"generatorCpuAvgPercent\":$(jnum "$SIM_CPU"),\"generatorCpuInjectionAvgPercent\":$(jnum "$SIM_CPU_INJECTION"),\"generatorCpuSteadyAvgPercent\":$(jnum "$SIM_CPU_STEADY")}")
}

if [ "$SIMULATION" = ingest ] || [ "$SIMULATION" = all ]; then
    run_gatling ingest EventIngestSimulation 7 "$RAMP_SECONDS"
    finish ingest "$SIM_REASON" "$SIM_ASSERTIONS"
fi

if [ "$SIMULATION" = burst ] || [ "$SIMULATION" = all ]; then
    METRICS_URLS="http://worker-a:8081/actuator/prometheus"
    [ "$WORKERS" = 2 ] && METRICS_URLS="$METRICS_URLS http://worker-b:8081/actuator/prometheus"
    rm -f "$RP_OUT/burst.json" "$RP_OUT/probe.pid"
    probe_alive() { gexec sh -c 'kill -0 "$(cat /work/harness/build/load/probe.pid)"' >/dev/null 2>&1; }
    rp_log "starting BurstDrainProbe (expected $BURST_SIZE rows, timeout 20m) before the burst"
    # shellcheck disable=SC2086
    rp_compose exec -T -d gatling sh -c 'echo $$ >/work/harness/build/load/probe.pid; exec java -cp loadtest/target/classes io.reliablepush.loadtest.BurstDrainProbe "$@"' \
        probe --metrics $METRICS_URLS --expected "$BURST_SIZE" --timeout 20m --out /work/harness/build/load/burst.json
    sleep 3 # baseline sample before the first notification
    probe_alive || rp_warn "the drain probe is not running"
    run_gatling burst FallbackBurstSimulation 2 "$WARMUP_SECONDS"
    reason="$SIM_REASON"
    PROBE_JSON="null"
    if [ -n "$reason" ]; then
        gexec sh -c 'kill "$(cat /work/harness/build/load/probe.pid)"' >/dev/null 2>&1 || true
    else
        rp_log "waiting for the backlog to drain (probe timeout 20m)"
        probe_deadline=$(($(date +%s) + 20 * 60 + 90))
        n=0
        while [ ! -s "$RP_OUT/burst.json" ] && [ "$(date +%s)" -lt "$probe_deadline" ]; do
            sleep 2
            n=$((n + 1))
            if [ $((n % 5)) -eq 0 ] && ! probe_alive; then
                sleep 1
                break
            fi
        done
        if [ -s "$RP_OUT/burst.json" ]; then
            PROBE_JSON="$(tr -d '\n' <"$RP_OUT/burst.json")"
            [ "$(probe_status "$PROBE_JSON")" = PASSED ] || reason="${reason:+$reason; }drain probe did not report PASSED"
        else
            reason="${reason:+$reason; }no drain result (probe not running or timed out)"
        fi
    fi
    finish burst "$reason" "$PROBE_JSON"
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
