#!/usr/bin/env bash
# Checks that long-running waits are really still running (rule .agents/rules/long-running.md).
#
#   scripts/check-waits.sh [--max-age <duration>] [--hook claude|antigravity]
#
# Reports, for the current user:
#   - every wait registered by scripts/wait-until.sh, with the time it has left;
#   - OVERDUE: a registered wait still alive after its deadline (its condition command hangs);
#   - LOOP: a shell loop (`sh|bash|zsh -c` with until/while and sleep) older than --max-age (default 30m) that does not
#     go through wait-until.sh, so nothing bounds it. A record whose process is gone is removed silently.
# It never stops anything: the agent that started a flagged process stops it; for another agent's, ask.
#
# Exit code 1 when there is an OVERDUE or LOOP finding, else 0.
# --hook prints nothing when clean and always exits 0, for the tools' hooks:
#   claude       plain text (Claude Code adds a SessionStart / UserPromptSubmit hook's stdout to the context);
#   antigravity  {"injectSteps":[{"ephemeralMessage":"..."}]} for its SessionStart / PreInvocation hooks, {} when clean.
set -uo pipefail

# shellcheck source=lib/waits.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/waits.sh"

max_age_text="30m"
hook=""
while [ $# -gt 0 ]; do
  case "$1" in
    --max-age) max_age_text="${2:-}"; shift 2 || exit 2 ;;
    --hook) hook="${2:-}"; shift 2 || exit 2 ;;
    -h | --help) sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "check-waits: unknown option $1" >&2; exit 2 ;;
  esac
done
max_age="$(duration_seconds "$max_age_text")" || { echo "check-waits: cannot read --max-age $max_age_text" >&2; exit 2; }
case "$hook" in '' | claude | antigravity) ;; *) echo "check-waits: --hook claude or antigravity" >&2; exit 2 ;; esac

now="$(date +%s)"
findings=()
running=()
grace=60

dir="$(waits_dir)"
if [ -d "$dir" ]; then
  for record in "$dir"/*.wait; do
    [ -f "$record" ] || continue
    pid="" deadline="" label=""
    while IFS='=' read -r key value; do
      case "$key" in
        pid) pid="$value" ;;
        deadline) deadline="$value" ;;
        label) label="$value" ;;
      esac
    done < "$record"
    case "$pid$deadline" in '' | *[!0-9]*) rm -f "$record"; continue ;; esac
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$record"
      continue
    fi
    if [ "$now" -gt $((deadline + grace)) ]; then
      findings+=("OVERDUE wait pid $pid ($label): its deadline passed $(human_duration $((now - deadline))) ago, so its condition command hangs; stop it with kill $pid")
    else
      left=$((deadline - now))
      [ "$left" -lt 0 ] && left=0
      running+=("wait pid $pid ($label): $(human_duration "$left") left")
    fi
  done
fi

while read -r pid etime command; do
  [ -n "$pid" ] || continue
  [ "$pid" = "$$" ] && continue
  case "$command" in *wait-until.sh* | *check-waits.sh*) continue ;; esac
  first="${command%% *}"
  shell="${first##*/}"
  case "$shell" in sh | bash | zsh | dash | ksh) ;; *) continue ;; esac
  case " $command " in *" -c "*) ;; *) continue ;; esac
  case "$command" in *sleep*) ;; *) continue ;; esac
  case "$command" in *"until "* | *"while "*) ;; *) continue ;; esac
  age="$(etime_seconds "$etime")"
  [ "$age" -ge "$max_age" ] || continue
  findings+=("LOOP pid $pid has been running for $(human_duration "$age") with no timeout: $(redact "${command:0:160}")")
done < <(ps -U "$(id -u)" -ww -o pid=,etime=,command= 2>/dev/null)

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\t'/\\t}"
  s="${s//$'\r'/}"
  s="${s//$'\n'/\\n}"
  printf '%s' "$s"
}

if [ -n "$hook" ]; then
  if [ ${#findings[@]} -eq 0 ]; then
    [ "$hook" = antigravity ] && echo '{}'
    exit 0
  fi
  message="check-waits (rule long-running.md): long-running work that may be stuck. Check each item before saying it is still running; stop what you started, ask about the rest."
  for finding in "${findings[@]}"; do message="$message"$'\n'"- $finding"; done
  if [ "$hook" = claude ]; then
    printf '%s\n' "$message"
  else
    printf '{"injectSteps":[{"ephemeralMessage":"%s"}]}\n' "$(json_escape "$message")"
  fi
  exit 0
fi

for item in ${running[@]+"${running[@]}"}; do echo "running  $item"; done
for item in ${findings[@]+"${findings[@]}"}; do echo "$item"; done
if [ ${#findings[@]} -eq 0 ]; then
  [ ${#running[@]} -eq 0 ] && echo "check-waits: no waits running, no open-ended loops older than $max_age_text"
  exit 0
fi
exit 1
