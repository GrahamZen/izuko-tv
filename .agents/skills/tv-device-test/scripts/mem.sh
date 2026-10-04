#!/usr/bin/env bash
# Memory check before a test, and cleanup when the device is short on memory.
#   mem.sh check            MemAvailable / MemTotal, the biggest processes, and whether it is below MIN_AVAIL_MB (exit 1 when low)
#   mem.sh clean [pkg…]     force-stop the given packages (your own, not in use), then `am kill-all` (cached background processes only),
#                           wait for the memory to settle and report again
#   mem.sh ensure [pkg…]    check; clean only when low; exit 1 if it is still low afterwards
# ANDROID_SERIAL picks the device. Clean only a device the board shows nobody else is using: kill-all also ends other sessions' idle
# apps. MIN_AVAIL_MB (default 600) is the threshold, MEM_TOP (default 8) how many processes to list.
set -u
export MSYS_NO_PATHCONV=1
: "${ANDROID_SERIAL:?set ANDROID_SERIAL (e.g. 10.0.0.250:5555 for the Sony, 10.0.0.203:5555 for the Shield)}"
MIN="${MIN_AVAIL_MB:-600}"
TOP="${MEM_TOP:-8}"

meminfo_mb() { adb shell cat /proc/meminfo | tr -d '\r' | awk -v k="$1" '$1 == k":" {print int($2 / 1024)}'; }

report() {
  local avail total
  avail=$(meminfo_mb MemAvailable)
  total=$(meminfo_mb MemTotal)
  echo "$ANDROID_SERIAL: MemAvailable ${avail} MB / MemTotal ${total} MB (threshold ${MIN} MB)"
  echo "biggest processes (PSS):"
  adb shell dumpsys meminfo | tr -d '\r' | awk '/Total PSS by process:/ {f = 1; next} f && /^$/ {exit} f' | head -n "$TOP"
  [ "${avail:-0}" -ge "$MIN" ]
}

clean() {
  local p
  for p in "$@"; do adb shell am force-stop "$p"; done
  adb shell am kill-all
  sleep "${MEM_SETTLE:-8}"
}

case "${1:-}" in
  check)
    report
    ;;
  clean)
    shift
    clean "$@"
    report
    ;;
  ensure)
    shift
    if report; then
      echo "enough memory"
    else
      echo "low on memory: cleaning"
      clean "$@"
      report
    fi
    ;;
  *)
    echo "usage: ANDROID_SERIAL=ip:port mem.sh check | clean [pkg…] | ensure [pkg…]" >&2
    exit 2
    ;;
esac
