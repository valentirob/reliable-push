# Shared helpers of scripts/wait-until.sh and scripts/check-waits.sh (rule .agents/rules/long-running.md).
# Sourced, not run. Bash 3.2 compatible (macOS) and GNU/Linux.

# Where running waits register themselves; one <pid>.wait file per wait, removed when it ends. Per user, because /tmp
# is shared on Linux.
waits_dir() {
  local tmp="${TMPDIR:-/tmp}"
  printf '%s\n' "${RP_WAITS_DIR:-${tmp%/}/reliable-push-waits-$(id -u)}"
}

# Hides values that look like secrets in a command line before it is printed (rule security.md: no secrets in logs).
redact() {
  printf '%s\n' "$1" | sed -E \
    -e 's/([Bb]earer|[Bb]asic)[[:space:]]+[^[:space:]"'"'"']+/\1 ***/g' \
    -e 's/(([Tt][Oo][Kk][Ee][Nn]|[Pp][Aa][Ss][Ss][Ww]([Oo][Rr])?[Dd]|[Ss][Ee][Cc][Rr][Ee][Tt]|[Aa][Pp][Ii][_-]?[Kk][Ee][Yy]|[Aa][Uu][Tt][Hh][Oo][Rr][Ii][Zz][Aa][Tt][Ii][Oo][Nn])[^=:[:space:]]*[=:][[:space:]]*)[^[:space:]"'"'"'&]+/\1***/g'
}

# "90", "90s", "15m", "2h" -> seconds on stdout; returns 1 for anything else.
duration_seconds() {
  local value="$1" number unit
  case "$value" in
    '' | *[!0-9smh]*) return 1 ;;
  esac
  number="${value%[smh]}"
  unit="${value#"$number"}"
  case "$number" in '' | *[!0-9]*) return 1 ;; esac
  case "$unit" in
    '' | s) echo $((10#$number)) ;;
    m) echo $((10#$number * 60)) ;;
    h) echo $((10#$number * 3600)) ;;
    *) return 1 ;;
  esac
}

# ps elapsed time "[[dd-]hh:]mm:ss" -> seconds on stdout.
etime_seconds() {
  local value="$1" days=0 a b c hours=0 minutes=0 seconds=0
  case "$value" in *-*) days="${value%%-*}"; value="${value#*-}" ;; esac
  IFS=: read -r a b c <<EOF
$value
EOF
  if [ -n "$c" ]; then hours="$a"; minutes="$b"; seconds="$c"; else minutes="$a"; seconds="$b"; fi
  echo $((10#$days * 86400 + 10#$hours * 3600 + 10#$minutes * 60 + 10#$seconds))
}

# seconds -> "3d 6h", "2h 5m", "4m 10s", "20s".
human_duration() {
  local s="$1"
  if [ "$s" -ge 86400 ]; then echo "$((s / 86400))d $((s % 86400 / 3600))h"
  elif [ "$s" -ge 3600 ]; then echo "$((s / 3600))h $((s % 3600 / 60))m"
  elif [ "$s" -ge 60 ]; then echo "$((s / 60))m $((s % 60))s"
  else echo "${s}s"
  fi
}
