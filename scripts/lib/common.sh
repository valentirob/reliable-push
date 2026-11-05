# Shared helpers of scripts/e2e.sh (and scripts/demo.sh, android-e2e.sh, ios-e2e.sh). Sourced, not executed.
# Bash 3.2 compatible (macOS).
# Needs: docker with the compose v2 plugin, curl. No jq: the JSON answers of the harness are matched with grep -E.

RP_ROOT="${RP_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
RP_COMPOSE_FILE="$RP_ROOT/harness/compose/docker-compose.yml"
# scripts/demo.sh sets these before sourcing: another compose project, output directory and override files
RP_PROJECT="${RP_PROJECT:-reliable-push-e2e}"
RP_OUT="${RP_OUT:-$RP_ROOT/harness/build/e2e}"
RP_API_URL="http://127.0.0.1:8080"
RP_HARNESS_URL="http://127.0.0.1:8090"

RP_TAG="${RP_TAG:-e2e}"
rp_log() { printf '[%s] %s\n' "$RP_TAG" "$*"; }
rp_warn() { printf '[%s] WARNING: %s\n' "$RP_TAG" "$*" >&2; }
rp_die() {
    printf '[%s] ERROR: %s\n' "$RP_TAG" "$*" >&2
    exit 1
}

# docker compose on the E2E project; generated files belong to the invoking user
rp_compose() {
    local files=(-f "$RP_COMPOSE_FILE") f
    for f in ${RP_COMPOSE_OVERRIDES[@]+"${RP_COMPOSE_OVERRIDES[@]}"}; do files+=(-f "$f"); done
    # bind source of the harness's optional real-FCM key (harness/build/fcm/service-account.json, placed by the maintainer)
    mkdir -p "$RP_ROOT/harness/build/fcm"
    E2E_UID="$(id -u)" E2E_GID="$(id -g)" docker compose -p "$RP_PROJECT" "${files[@]}" "$@"
}

rp_require_tools() {
    command -v docker >/dev/null 2>&1 || rp_die "docker not found: install Docker Desktop, Colima or Podman with compose"
    command -v curl >/dev/null 2>&1 || rp_die "curl not found"
    docker compose version >/dev/null 2>&1 \
        || rp_die "'docker compose version' failed: the compose v2 plugin is required and the docker daemon must run"
    docker info >/dev/null 2>&1 || rp_die "cannot reach the docker daemon (is it running? DOCKER_HOST=${DOCKER_HOST:-unset})"
}

# true when something accepts TCP connections on 127.0.0.1:<port>
rp_port_in_use() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1
}

# Review Focus 2: fail fast, before compose up, with a clear message
rp_check_ports() {
    local port
    for port in "$@"; do
        if rp_port_in_use "$port"; then
            rp_die "port $port is in use on 127.0.0.1; stop the process using it (lsof -nP -iTCP:$port -sTCP:LISTEN) and retry"
        fi
    done
}

# HTTP helpers: rp_http <method> <url> <outfile> [json body] [max seconds] -> prints the status code ("000" = no answer)
rp_http() {
    local method="$1" url="$2" out="$3" body="${4:-}" max="${5:-30}"
    if [ -n "$body" ]; then
        curl -sS -o "$out" -w '%{http_code}' --max-time "$max" -X "$method" \
            -H 'Content-Type: application/json' --data "$body" "$url" 2>/dev/null || true
    else
        curl -sS -o "$out" -w '%{http_code}' --max-time "$max" -X "$method" "$url" 2>/dev/null || true
    fi
}

# Reads `"key":"value"` (string values) from compact or indented JSON on stdin; one value per line.
rp_json_strings() {
    grep -oE "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | sed -E 's/.*:[[:space:]]*"([^"]*)"$/\1/'
}

# Is <url> answering HTTP 200 within 2 s?
rp_http_ok() {
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$1" 2>/dev/null || true)" = "200" ]
}

# Is the readiness probe of a compose service without published ports UP (checked from inside the container)?
rp_container_ready() {
    rp_compose exec -T "$1" wget -q -T 2 -O /dev/null "http://127.0.0.1:${2:-8080}/actuator/health/readiness" \
        >/dev/null 2>&1
}

# State of a compose service's container (running, exited, ...; empty when it has none)
rp_service_state() {
    rp_compose ps -a --format '{{.Service}} {{.State}}' 2>/dev/null | awk -v s="$1" '$1 == s { print $2; exit }'
}

# Copies `docker compose logs` of every service, and the backend JSON log files, to $RP_OUT/logs/
rp_collect_logs() {
    local svc
    mkdir -p "$RP_OUT/logs"
    for svc in $(rp_compose config --services 2>/dev/null); do
        rp_compose logs --no-color --timestamps "$svc" >"$RP_OUT/logs/$svc.log" 2>&1 || true
    done
    if [ "$(rp_service_state harness)" = "running" ]; then
        rp_compose cp harness:/tmp/rp-logs/. "$RP_OUT/logs/" >/dev/null 2>&1 || true
    fi
}

# Builds the images, generates the per-run secrets, starts the stack and waits up to <seconds> for api, worker-a,
# worker-b and harness (scripts/android-e2e.sh and ios-e2e.sh; e2e.sh and demo.sh keep their own copy of these
# steps). Sets RP_STACK_STARTED=1 right before `up`, so the caller's exit trap knows to collect logs and tear down.
RP_STACK_STARTED=0
# Bounded wait until the harness's device-traffic proxy (127.0.0.1:8091 -> api:8080) forwards: the backend's actuator
# answers 200 through it. Returns 1 on timeout (callers fail the start with their own die function).
rp_wait_device_proxy() {
    local deadline=$(($(date +%s) + ${1:-60}))
    until rp_http_ok "http://127.0.0.1:8091/actuator/health"; do
        [ "$(date +%s)" -lt "$deadline" ] || return 1
        sleep 1
    done
}

rp_start_stack() {
    local timeout="$1" deadline pending still svc state ready rel
    rel="${RP_OUT#"$RP_ROOT"/}"
    rp_log "building images (backend, harness); output in $rel/logs/build.log"
    if ! rp_compose build >"$RP_OUT/logs/build.log" 2>&1; then
        tail -n 40 "$RP_OUT/logs/build.log" >&2
        rp_die "image build failed ($rel/logs/build.log)"
    fi
    rp_log "generating per-run secrets"
    rp_compose run --rm -q secrets >"$RP_OUT/logs/secrets.log" 2>&1 \
        || { cat "$RP_OUT/logs/secrets.log" >&2; rp_die "secret generation failed"; }
    rp_log "starting the stack"
    RP_STACK_STARTED=1
    rp_compose up -d >"$RP_OUT/logs/up.log" 2>&1 || { cat "$RP_OUT/logs/up.log" >&2; rp_die "docker compose up failed"; }

    rp_log "waiting up to ${timeout}s for api, worker-a, worker-b and harness"
    deadline=$(($(date +%s) + timeout))
    pending="api worker-a worker-b harness"
    while :; do
        still=""
        for svc in $pending; do
            state="$(rp_service_state "$svc")"
            if [ "$state" != "running" ]; then
                rp_compose logs --no-color --tail 60 "$svc" >&2 || true
                rp_die "container $svc is not running (state: ${state:-none}); logs in $rel/logs/$svc.log"
            fi
            case "$svc" in
                api) rp_http_ok "$RP_API_URL/actuator/health/readiness" && ready=1 || ready=0 ;;
                harness) rp_http_ok "$RP_HARNESS_URL/actuator/health/readiness" && ready=1 || ready=0 ;;
                *) rp_container_ready "$svc" && ready=1 || ready=0 ;;
            esac
            [ "$ready" = 1 ] || still="$still $svc"
        done
        pending="${still# }"
        [ -z "$pending" ] && break
        if [ "$(date +%s)" -ge "$deadline" ]; then
            for svc in $pending; do
                printf '\n----- last log lines of %s -----\n' "$svc" >&2
                rp_compose logs --no-color --tail 60 "$svc" >&2 || true
            done
            rp_die "container(s) not ready after ${timeout}s: $pending; logs in $rel/logs/"
        fi
        sleep 1
    done
    rp_wait_device_proxy 30 \
        || rp_die "the device proxy on 127.0.0.1:8091 does not forward to the backend (GET /actuator/health did not answer 200)"
    rp_log "stack ready"
}

# Major version of the JDK that runs `java` (JAVA_HOME first), e.g. 21; empty when there is none
rp_java_major() {
    local java="java"
    [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && java="$JAVA_HOME/bin/java"
    "$java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ { print $2; exit }'
}

# Prints "<label>: <passed>/<total> passed[, <skipped> skipped]" from the last line of a summary tool
# ("<passed>/<total> <skipped>", scripts/lib/IosE2eSummary.java) and returns 1 unless every counted entry passed and
# at least one entry ran (scripts/ios-e2e.sh).
rp_summary_verdict() {
    local label="$1" line="$2" passed total skipped
    passed="${line%%/*}"
    total="${line#*/}"
    total="${total%% *}"
    skipped="${line##* }"
    if [ "$skipped" -gt 0 ]; then
        rp_log "$label: $passed/$total passed, $skipped skipped"
    else
        rp_log "$label: $passed/$total passed"
    fi
    [ "$passed" = "$total" ] && { [ "$total" != 0 ] || [ "$skipped" != 0 ]; }
}
