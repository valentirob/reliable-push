#!/usr/bin/env bash
# iOS Simulator E2E of the iOS SDK against the local harness stack (never in CI). Same command for humans and agents.
#
#   ./scripts/ios-e2e.sh                  # phase "blocked", then phase "main" (hosted storage tests + IOSE2ETests)
#   ./scripts/ios-e2e.sh --test <name>    # one entry: its scenario name (delivered-is-reported) or method name
#   ./scripts/ios-e2e.sh --keep           # leave the stack running afterwards (console: http://127.0.0.1:8090)
#
# Needs Xcode (xcodebuild, xcrun simctl, xcresulttool; see ios/README.md for the pinned versions), JDK 21, Docker with
# compose v2, and ports 8080, 8090 and 8091 free on 127.0.0.1. Compose project reliable-push-ios-e2e, fast clock, the
# same files and secrets generation as scripts/e2e.sh. The Simulator shares the Mac's network: the app reaches the
# backend through the harness's device proxy (http://127.0.0.1:8091, switched offline by the offline test) and the
# harness at http://127.0.0.1:8090.
# Simulator: IOS_SIMULATOR (default "iPhone 17"); it is booted unless already booted, and shut down at the end only if
# this run booted it. Its language is set to en-US (the tests tap the notification prompt's "Allow" / "Don't Allow").
# Builds are signed to run locally (no Apple Developer team); the App Group entitlement applies on the Simulator.
# Phases (the app is uninstalled before each, so the notification prompt shows again):
#   blocked: NotificationsBlockedUITests, answers "Don't Allow"  -> notifications-blocked-means-fallback
#   main:    scheme ReliablePushSampleE2E (SharedStorageTests hosted in the app, then IOSE2ETests), answers "Allow"
# Outputs in harness/build/ios-e2e/: summary.json (same shape as e2e.sh), junit/ios-e2e.xml, <phase>.xcresult,
# logs/ (xcodebuild, the Simulator's io.reliablepush log, containers). DerivedData/ is kept between runs.
# Prints "iOS E2E: 8/8 passed"; exit code 0 only if no entry FAILED and at least one PASSED.
# Environment: IOS_SIMULATOR, E2E_READY_TIMEOUT (stack readiness, default 120 s), RP_BUNDLE_ID (sample bundle id,
# default io.reliablepush.sample).
set -euo pipefail

RP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RP_PROJECT="reliable-push-ios-e2e"
RP_OUT="$RP_ROOT/harness/build/ios-e2e"
RP_TAG="ios-e2e"
# shellcheck source=lib/common.sh
. "$RP_ROOT/scripts/lib/common.sh"

usage() {
    # the header: every comment line after the shebang, up to the first line that is not one
    awk 'NR > 1 { if ($0 ~ /^#/) print; else exit }' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

XCODEPROJ="$RP_ROOT/ios/Sample/ReliablePushSample.xcodeproj"
BUNDLE_ID="${RP_BUNDLE_ID:-io.reliablepush.sample}"
SIM_NAME="${IOS_SIMULATOR:-iPhone 17}"
# scenario, phase, test identifier (xcodebuild -only-testing)
CATALOGUE="keychain-access-group-and-app-group-are-usable main ReliablePushSampleTests/SharedStorageTests/testKeychainAccessGroupAndAppGroupAreUsable
keychain-round-trip-without-access-group main ReliablePushSampleTests/SharedStorageTests/testKeychainRoundTripWithoutAccessGroup
delivered-is-reported main ReliablePushSampleUITests/IOSE2ETests/testDeliveredIsReported
read-is-reported-on-tap main ReliablePushSampleUITests/IOSE2ETests/testReadIsReportedOnTap
offline-delivery-is-sent-on-next-launch main ReliablePushSampleUITests/IOSE2ETests/testOfflineDeliveryIsSentOnNextLaunch
token-refresh-near-expiry main ReliablePushSampleUITests/IOSE2ETests/testTokenRefreshNearExpiry
logout-revokes-device main ReliablePushSampleUITests/IOSE2ETests/testLogoutRevokesDevice
notifications-blocked-means-fallback blocked ReliablePushSampleUITests/NotificationsBlockedUITests/testNotificationsBlockedMeansFallback"

TEST=""
KEEP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --test)
            [ $# -ge 2 ] && [ -n "$2" ] || rp_die "--test needs a scenario or method name"
            TEST="$2"
            shift 2
            ;;
        --test=*)
            TEST="${1#--test=}"
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

# --test: a scenario name or a test method name (testDeliveredIsReported) -> one catalogue line
SELECTED="$CATALOGUE"
if [ -n "$TEST" ]; then
    SELECTED="$(printf '%s\n' "$CATALOGUE" | awk -v t="$TEST" '{ n = split($3, p, "/"); if ($1 == t || p[n] == t) print }')"
    [ -n "$SELECTED" ] || rp_die "unknown test '$TEST'; known: $(printf '%s\n' "$CATALOGUE" | awk '{ printf "%s ", $1 }')"
fi
PHASES=""
printf '%s\n' "$SELECTED" | grep -q ' blocked ' && PHASES="blocked"
printf '%s\n' "$SELECTED" | grep -q ' main ' && PHASES="${PHASES:+$PHASES }main"

READY_TIMEOUT="${E2E_READY_TIMEOUT:-120}"
case "$READY_TIMEOUT" in '' | *[!0-9]*) rp_die "E2E_READY_TIMEOUT must be whole seconds, got '$READY_TIMEOUT'" ;; esac

# ---- preflight -------------------------------------------------------------------------------------------------
command -v xcodebuild >/dev/null 2>&1 || rp_die "xcodebuild not found: install Xcode and select it (xcode-select -s)"
xcodebuild -version >/dev/null 2>&1 || rp_die "xcodebuild does not run (Command Line Tools only? select Xcode with xcode-select -s)"
xcrun simctl help >/dev/null 2>&1 || rp_die "xcrun simctl not available (Xcode's Simulator tools)"
[ "$(rp_java_major)" = 21 ] || rp_die "JDK 21 required (found '$(rp_java_major)'); set JAVA_HOME to a JDK 21"
rp_require_tools

UDID="$(xcrun simctl list devices available | grep -F "    $SIM_NAME (" | head -n 1 \
    | sed -E 's/.*\(([0-9A-Fa-f-]{36})\).*/\1/' || true)"
[ -n "$UDID" ] || rp_die "no available Simulator named '$SIM_NAME' (set IOS_SIMULATOR; xcrun simctl list devices available)"

if [ -n "$(rp_compose ps -a -q 2>/dev/null)" ]; then
    rp_log "removing the stack left by a previous run (project $RP_PROJECT)"
    rp_compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
rp_check_ports 8080 8090 8091

rm -rf "$RP_OUT/logs" "$RP_OUT/junit" "$RP_OUT/summary.json" "$RP_OUT"/*.xcresult
mkdir -p "$RP_OUT/logs" "$RP_OUT/junit"

SIM_STARTED=0
XCB_PID=""
LOG_SINCE="$(date '+%Y-%m-%d %H:%M:%S')"

finish() {
    local rc=$?
    trap - EXIT INT TERM
    set +e
    if [ -n "$XCB_PID" ]; then
        kill -TERM "$XCB_PID" 2>/dev/null
        wait "$XCB_PID" 2>/dev/null
    fi
    if xcrun simctl list devices | grep -F "($UDID)" | grep -q '(Booted)'; then
        # the SDK's and the sample's log lines (os_log subsystem io.reliablepush) of this run
        xcrun simctl spawn "$UDID" log show --style compact --info --debug --start "$LOG_SINCE" \
            --predicate 'subsystem BEGINSWITH "io.reliablepush"' >"$RP_OUT/logs/simulator-io.reliablepush.log" 2>&1
    fi
    if [ "$RP_STACK_STARTED" = 1 ]; then
        rp_collect_logs
        if [ "$KEEP" = 1 ]; then
            rp_log "stack kept running (--keep): console $RP_HARNESS_URL, API $RP_API_URL, device proxy http://127.0.0.1:8091"
            rp_log "stop it with: docker compose -p $RP_PROJECT -f harness/compose/docker-compose.yml down -v"
        else
            rp_compose down -v --remove-orphans >/dev/null 2>&1
        fi
    fi
    if [ "$SIM_STARTED" = 1 ]; then
        rp_log "shutting down the Simulator this run booted ($SIM_NAME)"
        xcrun simctl shutdown "$UDID" >/dev/null 2>&1
    fi
    rp_log "outputs: harness/build/ios-e2e (summary.json, junit/, *.xcresult, logs/)"
    if [ "$rc" = 0 ]; then rp_log "RESULT: no entry failed"; else rp_log "RESULT: FAILED (exit $rc)"; fi
    exit "$rc"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---- stack -----------------------------------------------------------------------------------------------------
rp_start_stack "$READY_TIMEOUT"

# ---- Simulator: the booted one, or boot it ---------------------------------------------------------------------
sim_booted() { xcrun simctl list devices | grep -F "($UDID)" | grep -q '(Booted)'; }
boot_sim() {
    xcrun simctl bootstatus "$UDID" -b >"$RP_OUT/logs/simulator-boot.log" 2>&1 \
        || { tail -n 20 "$RP_OUT/logs/simulator-boot.log" >&2; rp_die "the Simulator $SIM_NAME ($UDID) did not boot"; }
}
if sim_booted; then
    rp_log "using the booted Simulator $SIM_NAME ($UDID), left running afterwards"
else
    rp_log "booting the Simulator $SIM_NAME ($UDID)"
    SIM_STARTED=1
    boot_sim
fi
# en-US for the system alert's button labels
if ! xcrun simctl spawn "$UDID" defaults read -g AppleLanguages 2>/dev/null | tr -d ' \n"()' | grep -q '^en-US'; then
    xcrun simctl spawn "$UDID" defaults write -g AppleLanguages -array en-US
    xcrun simctl spawn "$UDID" defaults write -g AppleLocale -string en_US
    if [ "$SIM_STARTED" = 1 ]; then
        rp_log "language set to en-US; rebooting the Simulator so it applies"
        xcrun simctl shutdown "$UDID" >/dev/null 2>&1 || true
        boot_sim
    else
        rp_warn "the booted Simulator's language was not en-US: set now, applies after its next restart (the tests fall back to the prompt's button order)"
    fi
fi

# ---- phases ----------------------------------------------------------------------------------------------------
XCODE_ARGS=(-project "$XCODEPROJ" -destination "platform=iOS Simulator,id=$UDID"
    -derivedDataPath "$RP_OUT/DerivedData" -parallel-testing-enabled NO
    -test-timeouts-enabled YES -maximum-test-execution-time-allowance 300)
[ -n "${RP_BUNDLE_ID:-}" ] && XCODE_ARGS+=("RP_BUNDLE_ID=$RP_BUNDLE_ID")
PHASE_ARGS=()
for phase in $PHASES; do
    if [ "$phase" = blocked ]; then
        scheme=ReliablePushSample
        only=(-only-testing:ReliablePushSampleUITests/NotificationsBlockedUITests)
    else
        scheme=ReliablePushSampleE2E
        only=()
    fi
    if [ -n "$TEST" ]; then
        only=("-only-testing:$(printf '%s\n' "$SELECTED" | awk '{ print $3 }')")
    fi
    rp_log "phase $phase: uninstalling $BUNDLE_ID (fresh notification prompt)"
    xcrun simctl terminate "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl uninstall "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
    rp_log "phase $phase: xcodebuild test -scheme $scheme ${only[*]:-}; output in harness/build/ios-e2e/logs/xcodebuild-$phase.log"
    rc=0
    # in the background, so INT/TERM reach the exit trap at once (it stops xcodebuild, then tears down)
    xcodebuild test "${XCODE_ARGS[@]}" -scheme "$scheme" ${only[@]+"${only[@]}"} \
        -resultBundlePath "$RP_OUT/$phase.xcresult" >"$RP_OUT/logs/xcodebuild-$phase.log" 2>&1 &
    XCB_PID=$!
    wait "$XCB_PID" || rc=$?
    XCB_PID=""
    if [ "$rc" != 0 ]; then
        rp_warn "phase $phase: xcodebuild exited $rc (failed tests are in the summary; tail of the log below)"
        grep -E 'error:|\*\* (TEST|BUILD) ' "$RP_OUT/logs/xcodebuild-$phase.log" | tail -n 15 >&2 || true
    fi
    json="$RP_OUT/logs/$phase-tests.json"
    if [ -d "$RP_OUT/$phase.xcresult" ]; then
        xcrun xcresulttool get test-results tests --path "$RP_OUT/$phase.xcresult" >"$json" 2>"$RP_OUT/logs/$phase-xcresulttool.log" \
            || { rp_warn "xcresulttool could not read $phase.xcresult"; : >"$json"; }
    else
        : >"$json"
    fi
    PHASE_ARGS+=(--phase "$phase=$json")
done

# ---- summary and verdict ---------------------------------------------------------------------------------------
EXPECT=()
for name in $(printf '%s\n' "$SELECTED" | awk '{ print $1 }'); do EXPECT+=(--expect "$name"); done
result="$("${JAVA_HOME:+$JAVA_HOME/bin/}java" "$RP_ROOT/scripts/lib/IosE2eSummary.java" "$RP_OUT/summary.json" \
    "$RP_OUT/junit/ios-e2e.xml" ${PHASE_ARGS[@]+"${PHASE_ARGS[@]}"} "${EXPECT[@]}")" \
    || rp_die "could not build summary.json"
printf '%s\n' "$result" | sed '$d'
if ! rp_summary_verdict "iOS E2E" "$(printf '%s\n' "$result" | tail -n 1)"; then
    rp_log "failed or missing entries: read failure.expected / actual / hint in harness/build/ios-e2e/summary.json"
    exit 1
fi
exit 0
