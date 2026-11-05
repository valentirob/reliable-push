#!/usr/bin/env bash
# Emulator E2E of the Android SDK against the local harness stack (never in CI). Same command for humans and agents.
#
#   ./scripts/android-e2e.sh                  # SDK instrumented tests, sample E2E tests, then the cold-start check
#   ./scripts/android-e2e.sh --test <method>  # one AndroidE2ETest method, cold-start-delivery or fcm-real-delivery
#   ./scripts/android-e2e.sh --release-lib    # same, but the sample consumes the library's minified RELEASE variant (R8)
#   ./scripts/android-e2e.sh --keep           # leave the stack running afterwards (console: http://127.0.0.1:8090)
#
# Needs ANDROID_HOME (adb, emulator), JDK 21, Docker with compose v2. Uses the device adb sees (ANDROID_SERIAL picks
# one of several); with none it boots ANDROID_AVD (default Medium_Phone_API_37.0) headless and stops it at the end.
# The emulator reaches the stack at 10.0.2.2 (backend 8080, harness 8090). Compose project reliable-push-android-e2e,
# fast clock, the same files and secrets generation as scripts/e2e.sh.
# Outputs in harness/build/android-e2e/: summary.json (same shape as e2e.sh), junit/*.xml, logs/ (gradle, logcat,
# emulator, containers). Exit code 0 only if no entry FAILED and at least one PASSED (or only SKIPPED ones were asked for).
# Optional real FCM: fcm-real-delivery runs only when the maintainer placed android/sample/google-services.json and
# harness/build/fcm/service-account.json (both git-ignored, never created by a script); otherwise it is SKIPPED, which
# counts as neither passed nor failed ("Android E2E: 8/8 passed, 1 skipped"). See docs/testing.md, "Real FCM pushes". Each run first removes the Gradle connected-test results
# (android/{reliablepush,sample}/build/outputs/androidTest-results). On an attached device it sets the animation scales
# to 0 and restores them at the end; logcat is collected from the start of the run (the device's buffer is not cleared).
# Environment: ANDROID_AVD, ANDROID_SERIAL, E2E_READY_TIMEOUT (stack readiness, default 120 s), ANDROID_BOOT_TIMEOUT
# (emulator boot, default 300 s).
set -euo pipefail

RP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RP_PROJECT="reliable-push-android-e2e"
RP_OUT="$RP_ROOT/harness/build/android-e2e"
RP_TAG="android-e2e"
# shellcheck source=lib/common.sh
. "$RP_ROOT/scripts/lib/common.sh"

usage() {
    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

PKG="io.reliablepush.sample"
TEST_CLASS="$PKG.AndroidE2ETest"
SAMPLE_TESTS="deliveredIsReported readIsReportedOnTap offlineDeliveryIsSentWhenNetworkReturns
notificationsBlockedMeansFallback tokenRefreshNearExpiry logoutRevokesDevice"
COLD_START="cold-start-delivery"
FCM_REAL="fcm-real-delivery"
GOOGLE_SERVICES="$RP_ROOT/android/sample/google-services.json"
FCM_KEY="$RP_ROOT/harness/build/fcm/service-account.json"
EMU_HARNESS_URL="http://10.0.2.2:8090"
EMU_BASE_URL="http://10.0.2.2:8080"

TEST=""
KEEP=0
RELEASE_LIB=0
while [ $# -gt 0 ]; do
    case "$1" in
        --test)
            [ $# -ge 2 ] && [ -n "$2" ] || rp_die "--test needs a method name"
            TEST="$2"
            shift 2
            ;;
        --test=*)
            TEST="${1#--test=}"
            shift
            ;;
        --release-lib)
            RELEASE_LIB=1
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
if [ -n "$TEST" ] && [ "$TEST" != "$COLD_START" ] && [ "$TEST" != "$FCM_REAL" ]; then
    printf '%s\n' $SAMPLE_TESTS | grep -qx -- "$TEST" \
        || rp_die "unknown test '$TEST'; known: $(printf '%s ' $SAMPLE_TESTS)$COLD_START $FCM_REAL"
fi

READY_TIMEOUT="${E2E_READY_TIMEOUT:-120}"
BOOT_TIMEOUT="${ANDROID_BOOT_TIMEOUT:-300}"
for v in "$READY_TIMEOUT" "$BOOT_TIMEOUT"; do
    case "$v" in '' | *[!0-9]*) rp_die "E2E_READY_TIMEOUT and ANDROID_BOOT_TIMEOUT must be whole seconds, got '$v'" ;; esac
done
AVD="${ANDROID_AVD:-Medium_Phone_API_37.0}"

# ---- preflight -------------------------------------------------------------------------------------------------
[ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] || rp_die "ANDROID_HOME is not set or not a directory (the Android SDK)"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
[ -x "$ADB" ] || rp_die "adb not found at $ADB (Android SDK platform-tools)"
[ -x "$EMULATOR" ] || rp_die "emulator not found at $EMULATOR (Android SDK emulator package)"
[ "$(rp_java_major)" = 21 ] || rp_die "JDK 21 required (found '$(rp_java_major)'); set JAVA_HOME to a JDK 21"
rp_require_tools

if [ -n "$(rp_compose ps -a -q 2>/dev/null)" ]; then
    rp_log "removing the stack left by a previous run (project $RP_PROJECT)"
    rp_compose down -v --remove-orphans >/dev/null 2>&1 || true
fi
rp_check_ports 8080 8090

rm -rf "$RP_OUT/logs" "$RP_OUT/junit" "$RP_OUT/summary.json" "$RP_OUT/responses"
mkdir -p "$RP_OUT/logs" "$RP_OUT/junit" "$RP_OUT/responses"

SERIAL=""
EMU_STARTED=0
EMU_PID=""
ANIM_SAVED=""
adb_s() { "$ADB" -s "$SERIAL" "$@"; }

ANIM_KEYS="window_animation_scale transition_animation_scale animator_duration_scale"
LOGCAT_SINCE=""

# Stops the emulator this run started: `emu kill` when adb knows it, and in any case its process (TERM, then KILL),
# so an interrupt or a timeout while it is still booting leaves nothing running.
stop_started_emulator() {
    [ "$EMU_STARTED" = 1 ] || return 0
    rp_log "stopping the emulator this run started (${SERIAL:-not in adb yet})"
    [ -n "$SERIAL" ] && "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1
    [ -n "$EMU_PID" ] || return 0
    local i=0
    while kill -0 "$EMU_PID" 2>/dev/null && [ "$i" -lt 20 ]; do
        [ "$i" = 5 ] && { pkill -TERM -P "$EMU_PID" 2>/dev/null; kill -TERM "$EMU_PID" 2>/dev/null; }
        sleep 1
        i=$((i + 1))
    done
    if kill -0 "$EMU_PID" 2>/dev/null; then
        pkill -KILL -P "$EMU_PID" 2>/dev/null
        kill -KILL "$EMU_PID" 2>/dev/null
    fi
    wait "$EMU_PID" 2>/dev/null
    return 0
}

# A device that was already attached: put back what this run changed (animation scales, airplane mode)
restore_attached_device() {
    [ "$EMU_STARTED" = 0 ] && [ -n "$SERIAL" ] && [ -n "$ANIM_SAVED" ] || return 0
    local key value
    set -- $ANIM_SAVED
    for key in $ANIM_KEYS; do
        value="${1:-null}"
        shift || true
        if [ "$value" = null ]; then
            adb_s shell settings delete global "$key" >/dev/null 2>&1
        else
            adb_s shell settings put global "$key" "$value" >/dev/null 2>&1
        fi
    done
    adb_s shell cmd connectivity airplane-mode disable >/dev/null 2>&1
    return 0
}

finish() {
    local rc=$?
    trap - EXIT INT TERM
    set +e
    if [ -n "$SERIAL" ]; then
        if [ -n "$LOGCAT_SINCE" ]; then
            adb_s logcat -d -v threadtime -T "$LOGCAT_SINCE" >"$RP_OUT/logs/logcat.txt" 2>&1
        else
            adb_s logcat -d -v threadtime >"$RP_OUT/logs/logcat.txt" 2>&1
        fi
    fi
    if [ "$RP_STACK_STARTED" = 1 ]; then
        rp_collect_logs
        if [ "$KEEP" = 1 ]; then
            rp_log "stack kept running (--keep): console $RP_HARNESS_URL, API $RP_API_URL"
            rp_log "stop it with: docker compose -p $RP_PROJECT -f harness/compose/docker-compose.yml down -v"
        else
            rp_compose down -v --remove-orphans >/dev/null 2>&1
        fi
    fi
    restore_attached_device
    stop_started_emulator
    rp_log "outputs: harness/build/android-e2e (summary.json, junit/, logs/)"
    if [ "$rc" = 0 ]; then rp_log "RESULT: no entry failed"; else rp_log "RESULT: FAILED (exit $rc)"; fi
    exit "$rc"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---- device: the attached one, or boot the AVD headless --------------------------------------------------------
online_devices() { "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'; }

"$ADB" start-server >/dev/null 2>&1 || rp_die "adb start-server failed"
if [ -n "${ANDROID_SERIAL:-}" ]; then
    online_devices | grep -qx -- "$ANDROID_SERIAL" || rp_die "ANDROID_SERIAL=$ANDROID_SERIAL is not an online device"
    SERIAL="$ANDROID_SERIAL"
else
    count="$(online_devices | grep -c . || true)"
    if [ "$count" -gt 1 ]; then
        rp_die "several devices attached ($(online_devices | tr '\n' ' ')); pick one with ANDROID_SERIAL"
    elif [ "$count" = 1 ]; then
        SERIAL="$(online_devices)"
    fi
fi

if [ -n "$SERIAL" ]; then
    rp_log "using the attached device $SERIAL (left running afterwards)"
else
    "$EMULATOR" -list-avds 2>/dev/null | grep -qx -- "$AVD" \
        || rp_die "no device attached and no AVD '$AVD' (set ANDROID_AVD; known: $("$EMULATOR" -list-avds 2>/dev/null | tr '\n' ' '))"
    rp_log "no device attached: booting $AVD headless (log in harness/build/android-e2e/logs/emulator.log)"
    before="$("$ADB" devices | awk 'NR > 1 && $1 ~ /^emulator-/ { print $1 }')"
    "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-snapshot-save -no-boot-anim >"$RP_OUT/logs/emulator.log" 2>&1 &
    EMU_PID=$!
    EMU_STARTED=1
    deadline=$(($(date +%s) + BOOT_TIMEOUT))
    while [ -z "$SERIAL" ]; do
        kill -0 "$EMU_PID" 2>/dev/null || {
            tail -n 30 "$RP_OUT/logs/emulator.log" >&2
            rp_die "the emulator exited during start-up (harness/build/android-e2e/logs/emulator.log)"
        }
        SERIAL="$("$ADB" devices | awk 'NR > 1 && $1 ~ /^emulator-/ { print $1 }' | grep -vxF -- "$before" | head -n 1 || true)"
        [ -n "$SERIAL" ] || [ "$(date +%s)" -lt "$deadline" ] \
            || rp_die "the emulator did not show up in adb within ${BOOT_TIMEOUT}s"
        [ -n "$SERIAL" ] || sleep 2
    done
    rp_log "waiting up to ${BOOT_TIMEOUT}s for $SERIAL to finish booting"
    while [ "$(adb_s shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]; do
        kill -0 "$EMU_PID" 2>/dev/null || rp_die "the emulator exited while booting (harness/build/android-e2e/logs/emulator.log)"
        [ "$(date +%s)" -lt "$deadline" ] || rp_die "the emulator did not finish booting within ${BOOT_TIMEOUT}s"
        sleep 2
    done
fi
export ANDROID_SERIAL="$SERIAL"
[ "$(adb_s shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ] 2>/dev/null \
    || rp_warn "$SERIAL is below Android 13: the POST_NOTIFICATIONS steps do not apply"

# logcat from now on (device clock), without clearing the buffer of a device that was already attached
# (epoch seconds: `logcat -T` accepts 'sssss.mmm', which needs no quoting through adb shell)
LOGCAT_SINCE="$(adb_s shell date +%s 2>/dev/null | tr -dc '0-9' || true)"
[ -n "$LOGCAT_SINCE" ] && LOGCAT_SINCE="$LOGCAT_SINCE.000"
# animations off (UiAutomator), screen awake and unlocked (the notification shade test)
if [ "$EMU_STARTED" = 0 ]; then
    ANIM_SAVED="$(for s in $ANIM_KEYS; do
        v="$(adb_s shell settings get global "$s" | tr -d '\r')"
        printf '%s ' "${v:-null}"
    done)"
fi
for s in $ANIM_KEYS; do
    adb_s shell settings put global "$s" 0
done
adb_s shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb_s shell wm dismiss-keyguard >/dev/null 2>&1 || true

# ---- stack -----------------------------------------------------------------------------------------------------
rp_start_stack "$READY_TIMEOUT"

# ---- instrumented tests ----------------------------------------------------------------------------------------
GRADLE_TASKS=(:sample:assembleDebug)
GRADLE_ARGS=(
    "-Pandroid.testInstrumentationRunnerArguments.harnessUrl=$EMU_HARNESS_URL"
    "-Pandroid.testInstrumentationRunnerArguments.baseUrl=$EMU_BASE_URL"
)
if [ "$RELEASE_LIB" = 1 ]; then
    # the sample then consumes the minified release AAR from mavenLocal, so publish it first
    GRADLE_TASKS=(:reliablepush:publishToMavenLocal "${GRADLE_TASKS[@]}")
    GRADLE_ARGS+=("-Prp.sampleLibVariant=release")
fi
EXPECT=()
if [ -z "$TEST" ]; then
    GRADLE_TASKS+=(:reliablepush:connectedDebugAndroidTest :sample:connectedDebugAndroidTest)
    EXPECT+=(--expect keystore-token-cipher)
    for t in $SAMPLE_TESTS; do EXPECT+=(--expect "$t"); done
elif [ "$TEST" != "$COLD_START" ] && [ "$TEST" != "$FCM_REAL" ]; then
    GRADLE_TASKS+=(:sample:connectedDebugAndroidTest)
    GRADLE_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS#$TEST")
    EXPECT+=(--expect "$TEST")
fi
# the summary tool takes scenario names: method names in kebab case (see scripts/lib/AndroidE2eSummary.java)
kebab() { printf '%s' "$1" | sed -E 's/([a-z0-9])([A-Z])/\1-\2/g' | tr '[:upper:]' '[:lower:]'; }
for i in "${!EXPECT[@]}"; do
    [ "${EXPECT[$i]}" = --expect ] || EXPECT[$i]="$(kebab "${EXPECT[$i]}")"
done

RESULTS_DIRS=("$RP_ROOT/android/reliablepush/build/outputs/androidTest-results"
    "$RP_ROOT/android/sample/build/outputs/androidTest-results")
rm -rf "${RESULTS_DIRS[@]}"
rp_log "gradle ${GRADLE_TASKS[*]} on $SERIAL; output in harness/build/android-e2e/logs/gradle.log"
gradle_rc=0
(cd "$RP_ROOT/android" && ./gradlew --no-daemon --continue "${GRADLE_TASKS[@]}" "${GRADLE_ARGS[@]}") \
    >"$RP_OUT/logs/gradle.log" 2>&1 || gradle_rc=$?
[ "$gradle_rc" = 0 ] || rp_warn "gradle exited $gradle_rc (failed tests are in the summary; tail of gradle.log below)"
[ "$gradle_rc" = 0 ] || tail -n 25 "$RP_OUT/logs/gradle.log" >&2

XMLS=()
for d in "${RESULTS_DIRS[@]}"; do
    [ -d "$d" ] || continue
    module="$(basename "$(dirname "$(dirname "$(dirname "$d")")")")" # android/<module>/build/outputs/androidTest-results
    while IFS= read -r f; do
        # both modules name their file after the device: prefix the module
        cp "$f" "$RP_OUT/junit/$module-$(basename "$f")"
        XMLS+=("$RP_OUT/junit/$module-$(basename "$f")")
    done < <(find "$d" -name 'TEST-*.xml' -type f)
done

# ---- killed app: the process is not running when the push arrives ---------------------------------------------
# cold-start-delivery (FR-6): the push is handed to the stopped app with the debug broadcast (no FCM).
# fcm-real-delivery: the same, but the harness sends it through the real FCM HTTP v1 API and Google Play services wakes
# the app (needs the maintainer's Firebase files, otherwise SKIPPED).
ENTRIES=()
killed_app_delivery() {
    local name="$1" mode="$2" apk="$RP_ROOT/android/sample/build/outputs/apk/debug/sample-debug.apk" user tid code out device pid i attempt state
    local started fail="" has_token wait_steps
    started=$(date +%s)
    user="android-e2e-$name-$(date +%s)-$$"
    if [ "$mode" = fcm ]; then
        out="$RP_OUT/responses/$name-apps.json"
        rp_http GET "$RP_HARNESS_URL/console/api/apps" "$out" >/dev/null
        grep -q '"fcmEnabled":true' "$out" \
            || fail="the harness real FCM mode is off (it could not load harness/build/fcm/service-account.json; see the harness log)"
    fi
    if [ -n "$fail" ]; then
        :
    elif [ ! -f "$apk" ]; then
        fail="the debug APK was not built (harness/build/android-e2e/logs/gradle.log)"
    elif ! adb_s install -r -g "$apk" >"$RP_OUT/logs/$name-install.log" 2>&1; then
        fail="adb install failed (harness/build/android-e2e/logs/$name-install.log)"
    fi
    if [ -z "$fail" ]; then
        rp_log "$name: starting MainActivity, it registers $user"
        # a launch racing the end of the package update is restarted without its extras: retry until it registers
        device=""
        for attempt in 1 2 3; do
            adb_s shell am start -S -W -n "$PKG/.MainActivity" --es autoRegisterUser "$user" \
                >>"$RP_OUT/logs/$name-am.log" 2>&1 || true
            for i in $(seq 1 20); do
                out="$RP_OUT/responses/$name-devices.json"
                if [ "$(rp_http GET "$RP_HARNESS_URL/console/api/devices?appId=shop&userId=$user" "$out")" = 200 ] \
                    && grep -q '"kind":"REAL"' "$out"; then
                    device="$(rp_json_strings deviceId <"$out" | head -n 1)"
                    break 2
                fi
                sleep 0.5
            done
            rp_warn "$name: $user not registered 10 s after launch $attempt"
        done
        [ -n "$device" ] || fail="the app did not register $user after 3 launches (no real device in the harness)"
    fi
    if [ -z "$fail" ] && [ "$mode" = fcm ]; then
        # the app reports its FCM token with the registration, or when Firebase hands it a new one
        has_token=0
        for i in $(seq 1 60); do
            out="$RP_OUT/responses/$name-devices.json"
            if [ "$(rp_http GET "$RP_HARNESS_URL/console/api/devices?appId=shop&userId=$user" "$out")" = 200 ] \
                && grep -q '"hasFcmToken":true' "$out"; then
                has_token=1
                break
            fi
            sleep 0.5
        done
        [ "$has_token" = 1 ] || fail="the device never reported an FCM token within 30 s (Firebase configured? Google Play services and internet on the emulator?)"
    fi
    if [ -z "$fail" ]; then
        rp_log "$name: device $device registered; HOME, then killing the background process"
        adb_s shell input keyevent HOME >/dev/null 2>&1 || true
        sleep 2
        for i in $(seq 1 10); do
            adb_s shell am kill "$PKG" >/dev/null 2>&1 || true
            sleep 1
            pid="$(adb_s shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
            [ -z "$pid" ] && break
        done
        [ -z "$pid" ] || fail="the app process ($pid) survived am kill"
    fi
    if [ -z "$fail" ]; then
        out="$RP_OUT/responses/$name-push.json"
        code="$(rp_http POST "$RP_HARNESS_URL/console/api/push" "$out" \
            "{\"appId\":\"shop\",\"userId\":\"$user\",\"title\":\"Killed app\",\"body\":\"Delivered to a stopped app\",\"policy\":\"NOT_DELIVERED\",\"timeoutMinutes\":60}")"
        tid="$(rp_json_strings trackingId <"$out" 2>/dev/null | head -n 1 || true)"
        [ "$code" = 200 ] && [ -n "$tid" ] || fail="POST /console/api/push answered $code"
    fi
    if [ -z "$fail" ]; then
        if [ "$mode" = fcm ]; then
            rp_log "$name: push $tid sent through FCM; waiting up to 60 s for DELIVERED"
            wait_steps=120
        else
            rp_log "$name: push $tid; broadcasting it to the stopped app"
            adb_s shell am broadcast -a "$PKG.DEBUG_PUSH" -n "$PKG/.DebugPushReceiver" --es rp_tid "$tid" \
                --es title Cold-start --es body Delivered >"$RP_OUT/logs/$name-broadcast.log" 2>&1 || true
            wait_steps=60
        fi
        state=""
        for i in $(seq 1 "$wait_steps"); do
            out="$RP_OUT/responses/$name-notification.json"
            if [ "$(rp_http GET "$RP_HARNESS_URL/console/api/notifications/$tid" "$out")" = 200 ]; then
                state="$(rp_json_strings state <"$out" | head -n 1)"
                [ "$state" = DELIVERED ] && break
            fi
            sleep 0.5
        done
        [ "$state" = DELIVERED ] || fail="state ${state:-unknown} $((wait_steps / 2)) s after the push (expected DELIVERED)"
    fi
    local ms=$((($(date +%s) - started) * 1000))
    if [ -z "$fail" ]; then
        rp_log "$name: PASSED"
        ENTRIES+=(--entry "$name|PASSED|$ms||||$tid DELIVERED after a start from a killed app (device $device)")
    else
        rp_warn "$name FAILED: $fail"
        ENTRIES+=(--entry "$name|FAILED|$ms|the push starts the killed app process, SampleApp configures the SDK and DELIVERED is reported|${fail//|/ }|harness/build/android-e2e/logs/logcat.txt (ReliablePush tag), $name-*.log and the harness log|")
    fi
}
fcm_missing() {
    local m=""
    [ -f "$GOOGLE_SERVICES" ] || m="android/sample/google-services.json"
    [ -f "$FCM_KEY" ] || m="${m:+$m, }harness/build/fcm/service-account.json"
    printf '%s' "$m"
}
if [ -z "$TEST" ] || [ "$TEST" = "$COLD_START" ]; then
    killed_app_delivery "$COLD_START" broadcast
fi
if [ -z "$TEST" ] || [ "$TEST" = "$FCM_REAL" ]; then
    missing="$(fcm_missing)"
    if [ -z "$missing" ]; then
        killed_app_delivery "$FCM_REAL" fcm
    else
        rp_log "$FCM_REAL: SKIPPED (missing: $missing)"
        ENTRIES+=(--entry "$FCM_REAL|SKIPPED|0|||needs android/sample/google-services.json and harness/build/fcm/service-account.json (not found: $missing); see docs/testing.md, Real FCM pushes|")
    fi
fi

# ---- summary and verdict ---------------------------------------------------------------------------------------
result="$("${JAVA_HOME:+$JAVA_HOME/bin/}java" "$RP_ROOT/scripts/lib/AndroidE2eSummary.java" "$RP_OUT/summary.json" \
    ${EXPECT[@]+"${EXPECT[@]}"} ${ENTRIES[@]+"${ENTRIES[@]}"} ${XMLS[@]+"${XMLS[@]}"})" \
    || rp_die "could not build summary.json"
printf '%s\n' "$result" | sed '$d'
result="$(printf '%s\n' "$result" | tail -n 1)"
# last line of the summary tool: "<passed>/<total> <skipped>"; SKIPPED entries are in neither passed nor total
PASSED="${result%%/*}"
TOTAL="${result#*/}"
TOTAL="${TOTAL%% *}"
SKIPPED="${result##* }"
if [ "$SKIPPED" -gt 0 ]; then
    rp_log "Android E2E: $PASSED/$TOTAL passed, $SKIPPED skipped"
else
    rp_log "Android E2E: $PASSED/$TOTAL passed"
fi
if [ "$PASSED" != "$TOTAL" ] || { [ "$TOTAL" = 0 ] && [ "$SKIPPED" = 0 ]; }; then
    rp_log "failed or missing entries: read failure.expected / actual / hint in harness/build/android-e2e/summary.json"
    exit 1
fi
exit 0
