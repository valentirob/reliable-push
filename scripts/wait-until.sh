#!/usr/bin/env bash
# Waits until a condition command succeeds, but never forever (rule .agents/rules/long-running.md).
#
#   scripts/wait-until.sh --timeout <duration> [--interval <seconds>] [--label <text>]
#                         [--needs <path>]... [--needs-pid <pid>]... -- <condition command...>
#
#   --timeout    required: 90, 90s, 15m or 2h. Exit 124 when it passes with the condition still false.
#   --interval   seconds between checks (default 10).
#   --label      what is awaited, printed on failure and by scripts/check-waits.sh.
#   --needs      a file or directory that must keep existing (the workspace, the log's folder). Exit 125 as soon as
#                it is gone: a condition on a file in a deleted folder would otherwise never become true.
#   --needs-pid  the process that produces the result (the build, the test run). Exit 125 when it has ended and the
#                condition, checked once more, is still false.
#
# Exit codes: 0 condition true, 124 timed out, 125 what it waits on is gone, 130 interrupted, 2 usage error.
# While it runs, the wait is registered in $RP_WAITS_DIR (default $TMPDIR/reliable-push-waits-<uid>) so that
# scripts/check-waits.sh can list it and flag it when it is still alive after its deadline (a hung condition command).
#
# Examples:
#   scripts/wait-until.sh --timeout 20m --label "CI on develop" -- \
#     sh -c 'gh run list --commit "$(git rev-parse HEAD)" --json status -q ".[].status" | grep -qv -e queued -e in_progress'
#   scripts/wait-until.sh --timeout 30m --needs "$LOG_DIR" --needs-pid "$E2E_PID" -- grep -q "E2E: " "$LOG_DIR/e2e.log"
set -uo pipefail

# shellcheck source=lib/waits.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/waits.sh"

usage() {
  sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

timeout_text=""
interval=10
label=""
needs=()
needs_pids=()
while [ $# -gt 0 ]; do
  case "$1" in
    --timeout) timeout_text="${2:-}"; shift 2 || usage ;;
    --interval) interval="${2:-}"; shift 2 || usage ;;
    --label) label="${2:-}"; shift 2 || usage ;;
    --needs) needs+=("${2:-}"); shift 2 || usage ;;
    --needs-pid) needs_pids+=("${2:-}"); shift 2 || usage ;;
    --) shift; break ;;
    -h | --help) usage ;;
    *) echo "wait-until: unknown option $1" >&2; usage ;;
  esac
done
[ -n "$timeout_text" ] || { echo "wait-until: --timeout is required" >&2; usage; }
timeout="$(duration_seconds "$timeout_text")" || { echo "wait-until: cannot read --timeout $timeout_text" >&2; usage; }
case "$interval" in '' | *[!0-9]* | 0) echo "wait-until: --interval must be a positive number of seconds" >&2; usage ;; esac
[ $# -gt 0 ] || { echo "wait-until: no condition command after --" >&2; usage; }
[ -n "$label" ] || label="$*"

dir="$(waits_dir)"
mkdir -p "$dir" && chmod 700 "$dir" 2>/dev/null
record="$dir/$$.wait"
started="$(date +%s)"
deadline=$((started + timeout))
printf 'pid=%s\nstarted=%s\ndeadline=%s\nlabel=%s\ncwd=%s\n' "$$" "$started" "$deadline" "$label" "$PWD" > "$record"
sleeper=""
trap 'rm -f "$record"; [ -n "$sleeper" ] && kill "$sleeper" 2>/dev/null' EXIT
trap 'exit 130' INT TERM HUP

gone() { # prints why the wait can never succeed, or returns 1
  local path pid
  for path in ${needs[@]+"${needs[@]}"}; do
    [ -e "$path" ] || { echo "$path is gone"; return 0; }
  done
  for pid in ${needs_pids[@]+"${needs_pids[@]}"}; do
    kill -0 "$pid" 2>/dev/null || { echo "process $pid has ended"; return 0; }
  done
  return 1
}

while true; do
  "$@" && exit 0
  if reason="$(gone)"; then
    # the producer may have written the result just before it ended
    [ ${#needs_pids[@]} -gt 0 ] && "$@" && exit 0
    echo "wait-until: $reason, stopping: $label" >&2
    exit 125
  fi
  now="$(date +%s)"
  if [ "$now" -ge "$deadline" ]; then
    echo "wait-until: timed out after $timeout_text: $label" >&2
    exit 124
  fi
  pause="$interval"
  [ $((deadline - now)) -lt "$pause" ] && pause=$((deadline - now))
  sleep "$pause" &
  sleeper=$!
  wait "$sleeper" 2>/dev/null
  sleeper=""
done
