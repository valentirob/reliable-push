#!/usr/bin/env bash
# Starts the local reliable-push stack for demos and manual testing and opens the web console. Local only.
#
#   ./scripts/demo.sh              # fast clock: 1 product "minute" = 1 second (timeouts and windows pass quickly)
#   ./scripts/demo.sh --realtime   # real clock: 1 minute = 60 seconds
#
# Same stack as scripts/e2e.sh (PostgreSQL, api, worker-a, worker-b, harness) in the compose project reliable-push-demo,
# with the Spring profile `demo` instead of `e2e` (harness/compose/demo.override.yml). It keeps running: stop it with
#   docker compose -p reliable-push-demo -f harness/compose/docker-compose.yml -f harness/compose/demo.override.yml down -v
# Console: http://127.0.0.1:8090, backend API: http://127.0.0.1:8080. Generated files: harness/build/demo/.
# Environment: DEMO_READY_TIMEOUT (seconds to wait for readiness, default 120), DEMO_NO_OPEN=1 (do not open a browser).
set -euo pipefail

RP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RP_PROJECT="reliable-push-demo"
RP_OUT="$RP_ROOT/harness/build/demo"
RP_TAG="demo"
RP_COMPOSE_OVERRIDES=("$RP_ROOT/harness/compose/demo.override.yml")
# shellcheck source=lib/common.sh
. "$RP_ROOT/scripts/lib/common.sh"

usage() {
    sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

REALTIME=0
while [ $# -gt 0 ]; do
    case "$1" in
        --realtime) REALTIME=1 ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            rp_die "unknown argument: $1"
            ;;
    esac
    shift
done
[ "$REALTIME" = 1 ] && RP_COMPOSE_OVERRIDES+=("$RP_ROOT/harness/compose/demo.realtime.override.yml")

READY_TIMEOUT="${DEMO_READY_TIMEOUT:-120}"
case "$READY_TIMEOUT" in
    '' | *[!0-9]*) rp_die "DEMO_READY_TIMEOUT must be a whole number of seconds, got '$READY_TIMEOUT'" ;;
esac

STOP_CMD="docker compose -p $RP_PROJECT -f harness/compose/docker-compose.yml -f harness/compose/demo.override.yml down -v"

rp_require_tools
if [ -n "$(rp_compose ps -a -q 2>/dev/null)" ]; then
    rp_log "removing the stack left by a previous run (project $RP_PROJECT)"
    rp_compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
rp_check_ports 8080 8090 8091

mkdir -p "$RP_OUT/logs"
failed() {
    rp_compose logs --no-color --tail 40 >&2 2>/dev/null || true
    rp_compose down -v --remove-orphans >/dev/null 2>&1 || true
    rp_die "$1 (the stack was removed again; logs of the failed start are above)"
}

rp_log "building images (backend, harness); output in harness/build/demo/logs/build.log"
if ! rp_compose build >"$RP_OUT/logs/build.log" 2>&1; then
    tail -n 40 "$RP_OUT/logs/build.log" >&2
    rp_die "image build failed (harness/build/demo/logs/build.log)"
fi
rp_log "generating per-run secrets"
rp_compose run --rm -q secrets >"$RP_OUT/logs/secrets.log" 2>&1 \
    || { cat "$RP_OUT/logs/secrets.log" >&2; rp_die "secret generation failed"; }
if [ "$REALTIME" = 1 ]; then
    # real clock: the backend must not run the fast clock
    grep -v '^RELIABLEPUSH_TESTING_MINUTEDURATION=' "$RP_OUT/secrets.env" >"$RP_OUT/secrets.env.tmp"
    mv "$RP_OUT/secrets.env.tmp" "$RP_OUT/secrets.env"
fi

rp_log "starting the stack ($([ "$REALTIME" = 1 ] && echo 'real clock: 1 minute = 60 s' || echo 'fast clock: 1 minute = 1 s'))"
rp_compose up -d >"$RP_OUT/logs/up.log" 2>&1 || { cat "$RP_OUT/logs/up.log" >&2; failed "docker compose up failed"; }

deadline=$(($(date +%s) + READY_TIMEOUT))
pending="api worker-a worker-b harness"
while :; do
    still=""
    for svc in $pending; do
        state="$(rp_service_state "$svc")"
        [ "$state" = "running" ] || failed "container $svc is not running (state: ${state:-none})"
        case "$svc" in
            api) rp_http_ok "$RP_API_URL/actuator/health/readiness" || still="$still $svc" ;;
            harness) rp_http_ok "$RP_HARNESS_URL/actuator/health/readiness" || still="$still $svc" ;;
            *) rp_container_ready "$svc" || still="$still $svc" ;;
        esac
    done
    pending="${still# }"
    [ -z "$pending" ] && break
    [ "$(date +%s)" -lt "$deadline" ] || failed "container(s) not ready after ${READY_TIMEOUT}s: $pending"
    sleep 1
done

rp_log "stack ready: console $RP_HARNESS_URL, API $RP_API_URL"
rp_log "stop it with: $STOP_CMD"
if [ "${DEMO_NO_OPEN:-0}" != 1 ]; then
    if command -v open >/dev/null 2>&1; then
        open "$RP_HARNESS_URL" >/dev/null 2>&1 || true
    elif command -v xdg-open >/dev/null 2>&1; then
        xdg-open "$RP_HARNESS_URL" >/dev/null 2>&1 || true
    else
        rp_log "open $RP_HARNESS_URL in a browser"
    fi
fi
