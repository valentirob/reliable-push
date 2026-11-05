#!/usr/bin/env bash
# End-to-end scenarios of reliable-push on the local PC (never in CI). Same command for humans and AI agents.
#
#   ./scripts/e2e.sh                     # whole catalogue (internal scenarios, then the worker-crash ones)
#   ./scripts/e2e.sh --scenario <name>   # one scenario (console-smoke: only the console UI test, see below)
#   ./scripts/e2e.sh --keep              # leave the stack running afterwards (console: http://127.0.0.1:8090)
#
# Stack: harness/compose/docker-compose.yml (PostgreSQL, api, worker-a, worker-b, harness), per-run secrets from the
# one-shot `secrets` service. Outputs in harness/build/e2e/: summary.json, junit/e2e.xml, logs/<service>.log and the
# backend JSON log files. Exit code 0 only if every scenario PASSED.
# console-smoke: after the catalogue the web console is driven by a headless Chromium (Playwright for Java, Maven profile
# console-smoke of the harness module); its JUnit XML goes to junit/console-smoke.xml and it is the last entry of summary.json.
# The first run downloads Playwright's Chromium (about 150 MB, cached in the user's cache directory).
# Environment: E2E_READY_TIMEOUT (seconds to wait for readiness, default 120).
set -euo pipefail

RP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/common.sh
. "$RP_ROOT/scripts/lib/common.sh"

usage() {
    sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

SCENARIO=""
KEEP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --scenario)
            [ $# -ge 2 ] && [ -n "$2" ] || rp_die "--scenario needs a name"
            SCENARIO="$2"
            shift 2
            ;;
        --scenario=*)
            SCENARIO="${1#--scenario=}"
            shift
            ;;
        --keep)
            KEEP=1
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            rp_die "unknown argument: $1"
            ;;
    esac
done

READY_TIMEOUT="${E2E_READY_TIMEOUT:-120}"
case "$READY_TIMEOUT" in
    '' | *[!0-9]*) rp_die "E2E_READY_TIMEOUT must be a whole number of seconds, got '$READY_TIMEOUT'" ;;
esac

# Worker-crash protocol (Scenario.External in the harness): seconds the workers stay down after the kill.
crash_delay() {
    case "$1" in
        *-inside-window) echo 3 ;;
        *-outside-window) echo 25 ;;
        *) echo "" ;;
    esac
}

STACK_STARTED=0
finish() {
    local rc=$?
    trap - EXIT INT TERM
    set +e
    if [ "$STACK_STARTED" = 1 ]; then
        rp_collect_logs
        if [ "$KEEP" = 1 ]; then
            rp_log "stack kept running (--keep): console $RP_HARNESS_URL, API $RP_API_URL"
            rp_log "stop it with: docker compose -p $RP_PROJECT -f harness/compose/docker-compose.yml down -v"
        else
            rp_compose down -v --remove-orphans >/dev/null 2>&1
        fi
    fi
    rp_log "outputs: $RP_OUT (summary.json, junit/e2e.xml, logs/)"
    if [ "$rc" = 0 ]; then rp_log "RESULT: all scenarios PASSED"; else rp_log "RESULT: FAILED (exit $rc)"; fi
    exit "$rc"
}

# ---- preflight -------------------------------------------------------------------------------------------------
rp_require_tools
if [ -n "$(rp_compose ps -a -q 2>/dev/null)" ]; then
    rp_log "removing the stack left by a previous run (project $RP_PROJECT)"
    rp_compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
rp_check_ports 8080 8090 8091

rm -rf "$RP_OUT/logs" "$RP_OUT/junit" "$RP_OUT/summary.json" "$RP_OUT/responses"
mkdir -p "$RP_OUT/logs" "$RP_OUT/junit" "$RP_OUT/responses"
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---- build, secrets, start -------------------------------------------------------------------------------------
rp_log "building images (backend, harness); output in harness/build/e2e/logs/build.log"
if ! rp_compose build >"$RP_OUT/logs/build.log" 2>&1; then
    tail -n 40 "$RP_OUT/logs/build.log" >&2
    rp_die "image build failed (harness/build/e2e/logs/build.log)"
fi
rp_log "generating per-run secrets"
rp_compose run --rm -q secrets >"$RP_OUT/logs/secrets.log" 2>&1 \
    || { cat "$RP_OUT/logs/secrets.log" >&2; rp_die "secret generation failed"; }

rp_log "starting the stack"
STACK_STARTED=1
rp_compose up -d >"$RP_OUT/logs/up.log" 2>&1 || { cat "$RP_OUT/logs/up.log" >&2; rp_die "docker compose up failed"; }

# ---- readiness (Review Focus 1: bounded wait, names the container, never hangs) -------------------------------
is_ready() {
    case "$1" in
        api) rp_http_ok "$RP_API_URL/actuator/health/readiness" ;;
        harness) rp_http_ok "$RP_HARNESS_URL/actuator/health/readiness" ;;
        *) rp_container_ready "$1" ;;
    esac
}

rp_log "waiting up to ${READY_TIMEOUT}s for api, worker-a, worker-b and harness"
deadline=$(($(date +%s) + READY_TIMEOUT))
pending="api worker-a worker-b harness"
while :; do
    still=""
    for svc in $pending; do
        state="$(rp_service_state "$svc")"
        if [ "$state" != "running" ]; then
            rp_compose logs --no-color --tail 60 "$svc" >&2 || true
            rp_die "container $svc is not running (state: ${state:-none}); logs in harness/build/e2e/logs/$svc.log"
        fi
        is_ready "$svc" || still="$still $svc"
    done
    pending="${still# }"
    [ -z "$pending" ] && break
    if [ "$(date +%s)" -ge "$deadline" ]; then
        for svc in $pending; do
            printf '\n----- last log lines of %s -----\n' "$svc" >&2
            rp_compose logs --no-color --tail 60 "$svc" >&2 || true
        done
        rp_die "container(s) not ready after ${READY_TIMEOUT}s: $pending; logs in harness/build/e2e/logs/"
    fi
    sleep 1
done
rp_log "stack ready"

# ---- scenarios -------------------------------------------------------------------------------------------------
code="$(rp_http GET "$RP_HARNESS_URL/console/api/scenarios" "$RP_OUT/responses/scenarios.json")"
[ "$code" = 200 ] || rp_die "GET /console/api/scenarios answered $code"
ALL_NAMES="$(rp_json_strings name <"$RP_OUT/responses/scenarios.json")"
# entries look like {"name":"x","description":"...","externalControl":true}
EXTERNAL_NAMES="$(grep -oE '"name":"[^"]*","description":"([^"\\]|\\.)*","externalControl":true' \
    "$RP_OUT/responses/scenarios.json" | sed -E 's/^"name":"([^"]*)".*/\1/' || true)"

is_external() {
    printf '%s\n' "$EXTERNAL_NAMES" | grep -qx -- "$1"
}

print_results() {
    grep -oE '"scenario":"[^"]*","status":"[A-Z]+","durationMs":[0-9]+' "$1" \
        | sed -E 's/"scenario":"([^"]*)","status":"([A-Z]+)","durationMs":([0-9]+)/  \2  \1 (\3 ms)/' || true
}

run_internal() {
    local body="$1" out="$RP_OUT/responses/run.json" code
    code="$(rp_http POST "$RP_HARNESS_URL/console/api/scenarios/run" "$out" "$body" 600)"
    if [ "$code" != 200 ]; then
        cat "$out" >&2 2>/dev/null || true
        rp_die "POST /console/api/scenarios/run answered $code"
    fi
    print_results "$out"
}

run_external() {
    local name="$1" delay out tid code seen deadline
    delay="$(crash_delay "$name")"
    [ -n "$delay" ] || rp_die "external scenario $name has no crash delay in scripts/e2e.sh (expected *-inside-window or *-outside-window)"
    rp_log "$name: stopping worker-b (no second worker may claim the row)"
    rp_compose stop worker-b >/dev/null 2>&1

    out="$RP_OUT/responses/$name-prepare.json"
    code="$(rp_http POST "$RP_HARNESS_URL/console/api/scenarios/$name/prepare" "$out" "{}" 120)"
    tid="$(rp_json_strings trackingId <"$out" 2>/dev/null | head -n 1 || true)"
    if [ "$code" != 200 ] || [ -z "$tid" ]; then
        rp_warn "$name: prepare answered $code without a trackingId (its result is in the summary)"
        rp_compose start worker-b >/dev/null 2>&1
        return 0
    fi
    rp_log "$name: prepared trackingId $tid; waiting for its webhook call to be in flight"
    seen=0
    deadline=$(($(date +%s) + 40))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if [ "$(rp_http GET "$RP_HARNESS_URL/console/api/inbox/in-flight?appId=shop" "$RP_OUT/responses/in-flight.json")" = 200 ] \
            && grep -q "$tid" "$RP_OUT/responses/in-flight.json"; then
            seen=1
            break
        fi
        sleep 0.2
    done
    [ "$seen" = 1 ] || rp_warn "$name: the webhook call never showed up in flight within 40 s (verify will report it)"
    rp_log "$name: killing worker-a while the call hangs; workers stay down ${delay}s"
    rp_compose kill worker-a >/dev/null 2>&1
    sleep "$delay"
    rp_compose start worker-a worker-b >/dev/null 2>&1
    rp_log "$name: workers restarted; verifying"
    out="$RP_OUT/responses/$name-verify.json"
    code="$(rp_http POST "$RP_HARNESS_URL/console/api/scenarios/$name/verify" "$out" "{\"trackingId\":\"$tid\"}" 600)"
    if [ "$code" != 200 ]; then
        cat "$out" >&2 2>/dev/null || true
        rp_die "POST /console/api/scenarios/$name/verify answered $code"
    fi
    print_results "$out"
}

RUN_CATALOGUE=1
RUN_CONSOLE=1
if [ -n "$SCENARIO" ]; then
    if [ "$SCENARIO" = console-smoke ]; then
        RUN_CATALOGUE=0
    else
        printf '%s\n' "$ALL_NAMES" | grep -qx -- "$SCENARIO" \
            || rp_die "unknown scenario '$SCENARIO'; known: $(printf '%s ' $ALL_NAMES) console-smoke"
        RUN_CONSOLE=0
    fi
fi

if [ "$RUN_CATALOGUE" = 0 ]; then
    EXPECTED=0
elif [ -n "$SCENARIO" ]; then
    EXPECTED=1
    if is_external "$SCENARIO"; then
        run_external "$SCENARIO"
    else
        rp_log "running $SCENARIO"
        run_internal "{\"names\":[\"$SCENARIO\"]}"
    fi
else
    EXPECTED="$(printf '%s\n' "$ALL_NAMES" | grep -c . || true)"
    rp_log "running the catalogue ($EXPECTED scenarios; the internal ones take a few minutes)"
    run_internal '{}'
    for name in $EXTERNAL_NAMES; do
        run_external "$name"
    done
fi

# ---- reports and verdict ---------------------------------------------------------------------------------------
if [ "$RUN_CATALOGUE" = 1 ]; then
    code="$(rp_http GET "$RP_HARNESS_URL/console/api/scenarios/report/summary.json" "$RP_OUT/summary.json")"
    [ "$code" = 200 ] || rp_die "GET report/summary.json answered $code"
    code="$(rp_http GET "$RP_HARNESS_URL/console/api/scenarios/report/junit.xml" "$RP_OUT/junit/e2e.xml")"
    [ "$code" = 200 ] || rp_die "GET report/junit.xml answered $code"
else
    printf '[]\n' >"$RP_OUT/summary.json"
fi

# ---- console smoke test (Playwright, headless Chromium) against the running stack, before the teardown -------------
json_escape() {
    # backslash, double quote, then every control character (tab, newline, ...) to a space
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '[:cntrl:]' ' '
}

# appends one entry to the JSON array in summary.json (the harness writes it pretty-printed, ending in "]")
append_summary_entry() {
    local body
    body="$(cat "$RP_OUT/summary.json")"
    body="${body%]*}"
    body="$(printf '%s' "$body" | sed -e 's/[[:space:]]*$//')"
    if [ "$body" = "[" ]; then
        body="[
$1"
    else
        body="$body,
$1"
    fi
    printf '%s\n]\n' "$body" >"$RP_OUT/summary.json"
}

if [ "$RUN_CONSOLE" = 1 ]; then
    EXPECTED=$((EXPECTED + 1))
    rp_log "console-smoke: driving the console with headless Chromium (first run downloads the browser); output in harness/build/e2e/logs/console-smoke.log"
    report="$RP_ROOT/harness/target/failsafe-reports/TEST-io.reliablepush.harness.console.ConsoleSmokeE2E.xml"
    rm -f "$report"
    started=$(date +%s)
    mvn_rc=0
    (cd "$RP_ROOT" && ./mvnw -B -pl harness -am verify -Pconsole-smoke "-Dconsole.url=$RP_HARNESS_URL") \
        >"$RP_OUT/logs/console-smoke.log" 2>&1 || mvn_rc=$?
    duration_ms=$((($(date +%s) - started) * 1000))
    [ -f "$report" ] && cp "$report" "$RP_OUT/junit/console-smoke.xml"
    if [ "$mvn_rc" = 0 ] && [ -f "$report" ] && grep -qE '<testsuite [^>]*tests="[1-9]' "$report" \
        && grep -qE '<testsuite [^>]*failures="0"' "$report" && grep -qE '<testsuite [^>]*errors="0"' "$report" \
        && grep -qE '<testsuite [^>]*skipped="0"' "$report" && ! grep -qE '<(failure|error|skipped)[ >/]' "$report"; then
        rp_log "console-smoke: PASSED (${duration_ms} ms)"
        entry='{
  "scenario" : "console-smoke",
  "status" : "PASSED",
  "durationMs" : '"$duration_ms"',
  "failure" : null,
  "logs" : [ "ConsoleSmokeE2E.registerDeviceSendPushSeeDeliveredAndFallback" ]
}'
    else
        if [ -f "$report" ] && grep -qE '<(failure|error)[ >]' "$report"; then
            what="$(grep -oE '<(failure|error) message="[^"]*"' "$report" | head -n 1 | sed -e 's/^[^"]*"//' -e 's/"$//' \
                | sed -e 's/&#10;/ /g' -e 's/&quot;/'"'"'/g' -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g' | cut -c1-600)"
        elif [ -f "$report" ] && [ "$mvn_rc" = 0 ]; then
            what="the console test was skipped or did not run (failures, errors and skipped must all be 0)"
        else
            what="maven exited $mvn_rc without a test report (build, Playwright download or startup problem)"
        fi
        rp_warn "console-smoke FAILED: $what"
        entry='{
  "scenario" : "console-smoke",
  "status" : "FAILED",
  "durationMs" : '"$duration_ms"',
  "failure" : {
    "expected" : "the console registers a device, shows the push DELIVERED and the fallback webhook with a valid signature",
    "actual" : "'"$(json_escape "$what")"'",
    "hint" : "harness/build/e2e/logs/console-smoke.log and harness/build/e2e/junit/console-smoke.xml; Playwright call log shows the element that was awaited"
  },
  "logs" : [ ]
}'
    fi
    append_summary_entry "$entry"
fi

TOTAL="$(rp_json_strings scenario <"$RP_OUT/summary.json" | grep -c . || true)"
PASSED="$(rp_json_strings status <"$RP_OUT/summary.json" | grep -cx PASSED || true)"
rp_log "summary: $PASSED/$TOTAL passed (expected $EXPECTED scenarios)"
if [ "$TOTAL" != "$EXPECTED" ] || [ "$PASSED" != "$TOTAL" ] || [ "$TOTAL" = 0 ]; then
    rp_log "failed or missing scenarios: read failure.expected / actual / hint in harness/build/e2e/summary.json"
    exit 1
fi
exit 0
