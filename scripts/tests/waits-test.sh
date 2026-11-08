#!/usr/bin/env bash
# Tests for scripts/wait-until.sh and scripts/check-waits.sh (rule .agents/rules/long-running.md).
# Plain bash, no test framework; runs on macOS (bash 3.2) and Linux. CI: .github/workflows/agent-context.yml.
#
#   scripts/tests/waits-test.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WAIT="$ROOT/scripts/wait-until.sh"
CHECK="$ROOT/scripts/check-waits.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/waits-test.XXXXXX")"
export RP_WAITS_DIR="$WORK/registry"
passed=0
failed=0
children=()

cleanup() {
  for pid in ${children[@]+"${children[@]}"}; do kill "$pid" 2>/dev/null; done
  rm -rf "$WORK"
}
trap cleanup EXIT

ok() { passed=$((passed + 1)); echo "ok   $1"; }
ko() { failed=$((failed + 1)); echo "FAIL $1: $2"; }

expect_exit() { # name expected actual
  if [ "$3" -eq "$2" ]; then ok "$1"; else ko "$1" "exit $3, expected $2"; fi
}

expect_contains() { # name haystack needle
  case "$2" in *"$3"*) ok "$1" ;; *) ko "$1" "output does not contain '$3': $2" ;; esac
}

expect_empty() { # name text
  if [ -z "$2" ]; then ok "$1"; else ko "$1" "expected no output, got: $2"; fi
}

# --- wait-until.sh -----------------------------------------------------------------------------------------------

"$WAIT" --timeout 5 --interval 1 -- true
expect_exit "condition already true exits 0" 0 $?

touch "$WORK/later"
( sleep 2; echo done > "$WORK/later" ) &
children+=($!)
"$WAIT" --timeout 10 --interval 1 -- grep -q done "$WORK/later"
expect_exit "condition that becomes true exits 0" 0 $?

start=$(date +%s)
out="$("$WAIT" --timeout 2 --interval 1 --label "never true" -- false 2>&1)"
code=$?
elapsed=$(( $(date +%s) - start ))
expect_exit "timeout exits 124" 124 "$code"
expect_contains "timeout names the label" "$out" "never true"
if [ "$elapsed" -le 4 ]; then ok "timeout stops on time"; else ko "timeout stops on time" "took ${elapsed}s for a 2s timeout"; fi

mkdir -p "$WORK/workspace"
( sleep 2; rm -rf "$WORK/workspace" ) &
children+=($!)
start=$(date +%s)
out="$("$WAIT" --timeout 60 --interval 1 --needs "$WORK/workspace" -- test -f "$WORK/workspace/never.log" 2>&1)"
code=$?
elapsed=$(( $(date +%s) - start ))
expect_exit "a vanished --needs path exits 125" 125 "$code"
expect_contains "a vanished --needs path is named" "$out" "$WORK/workspace"
if [ "$elapsed" -le 6 ]; then ok "a vanished --needs path stops long before the timeout"; else ko "a vanished --needs path stops long before the timeout" "took ${elapsed}s"; fi

"$WAIT" --timeout 60 --interval 1 --needs "$WORK/missing" -- false 2>/dev/null
expect_exit "a --needs path missing from the start exits 125" 125 $?

sleep 2 &
producer=$!
children+=("$producer")
start=$(date +%s)
"$WAIT" --timeout 60 --interval 1 --needs-pid "$producer" -- false 2>/dev/null
code=$?
elapsed=$(( $(date +%s) - start ))
expect_exit "an ended --needs-pid process exits 125" 125 "$code"
if [ "$elapsed" -le 6 ]; then ok "an ended --needs-pid process stops long before the timeout"; else ko "an ended --needs-pid process stops long before the timeout" "took ${elapsed}s"; fi

( sleep 1; echo done > "$WORK/written" ) &
writer=$!
children+=("$writer")
"$WAIT" --timeout 30 --interval 2 --needs-pid "$writer" -- test -f "$WORK/written" 2>/dev/null
expect_exit "a result written just before the producer ends still counts" 0 $?

"$WAIT" --interval 1 -- true 2>/dev/null
expect_exit "missing --timeout is a usage error" 2 $?

"$WAIT" --timeout soon -- true 2>/dev/null
expect_exit "an unreadable --timeout is a usage error" 2 $?

"$WAIT" --timeout 5 2>/dev/null
expect_exit "a missing condition is a usage error" 2 $?

"$WAIT" --timeout 1m --interval 1 --label "registered wait" -- false 2>/dev/null &
waiter=$!
children+=("$waiter")
sleep 1
if grep -q "label=registered wait" "$RP_WAITS_DIR/$waiter.wait" 2>/dev/null; then ok "a running wait is registered"; else ko "a running wait is registered" "no record $RP_WAITS_DIR/$waiter.wait"; fi
out="$("$CHECK")"
expect_exit "check-waits with only healthy waits exits 0" 0 $?
expect_contains "check-waits lists a healthy wait" "$out" "registered wait"
kill "$waiter" 2>/dev/null
wait "$waiter" 2>/dev/null
if [ ! -e "$RP_WAITS_DIR/$waiter.wait" ]; then ok "a stopped wait removes its record"; else ko "a stopped wait removes its record" "record still there"; fi

# --- check-waits.sh ----------------------------------------------------------------------------------------------

rm -rf "$RP_WAITS_DIR"
out="$("$CHECK" --max-age 1h)"
code=$?
expect_exit "check-waits with nothing to report exits 0" 0 "$code"
expect_empty "check-waits --hook claude is silent when clean" "$("$CHECK" --max-age 1h --hook claude)"
expect_contains "check-waits --hook antigravity prints an empty object when clean" "$("$CHECK" --max-age 1h --hook antigravity)" "{}"

mkdir -p "$RP_WAITS_DIR"
printf 'pid=%s\nstarted=1\ndeadline=1\nlabel=dead wait\ncwd=/\n' 999999 > "$RP_WAITS_DIR/999999.wait"
"$CHECK" --max-age 1h >/dev/null
expect_exit "a record of a dead process is not a finding" 0 $?
if [ ! -e "$RP_WAITS_DIR/999999.wait" ]; then ok "a record of a dead process is removed"; else ko "a record of a dead process is removed" "still there"; fi

sleep 30 &
hung=$!
children+=("$hung")
printf 'pid=%s\nstarted=1\ndeadline=2\nlabel=hung condition\ncwd=/\n' "$hung" > "$RP_WAITS_DIR/$hung.wait"
out="$("$CHECK" --max-age 1h)"
expect_exit "an overdue wait is a finding" 1 $?
expect_contains "an overdue wait is named" "$out" "hung condition"
out="$("$CHECK" --max-age 1h --hook claude)"
expect_exit "--hook never fails the hook" 0 $?
expect_contains "--hook claude reports the overdue wait" "$out" "hung condition"
out="$("$CHECK" --max-age 1h --hook antigravity)"
expect_contains "--hook antigravity reports through injectSteps" "$out" '"injectSteps"'
expect_contains "--hook antigravity keeps the finding" "$out" "hung condition"
kill "$hung" 2>/dev/null
wait "$hung" 2>/dev/null
rm -f "$RP_WAITS_DIR/$hung.wait"

bash -c 'until [ -f /nonexistent/reliable-push-waits-test ]; do sleep 1; done' &
loop=$!
children+=("$loop")
sleep 2
out="$("$CHECK" --max-age 1s)"
expect_exit "an open-ended shell loop is a finding" 1 $?
expect_contains "an open-ended shell loop is reported with its pid" "$out" "pid $loop"
out="$("$CHECK" --max-age 1h)"
expect_exit "a young shell loop is not a finding" 0 $?
kill "$loop" 2>/dev/null
wait "$loop" 2>/dev/null

bash -c 'while true; do curl -s -H "Authorization: Bearer s3cr3tvalue" "http://x/?token=abc123" >/dev/null 2>&1; sleep 1; done' &
loop=$!
children+=("$loop")
sleep 2
out="$("$CHECK" --max-age 1s)"
case "$out" in *s3cr3tvalue* | *abc123*) ko "a reported loop hides secrets in its command line" "$out" ;; *) ok "a reported loop hides secrets in its command line" ;; esac
expect_contains "a reported loop with a secret is still reported" "$out" "pid $loop"
kill "$loop" 2>/dev/null
wait "$loop" 2>/dev/null

echo
echo "waits tests: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
