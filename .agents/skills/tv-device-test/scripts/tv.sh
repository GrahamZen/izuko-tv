#!/usr/bin/env bash
# Helpers for on-device tests. Source it: `ANDROID_SERIAL=10.0.0.250:5555 PKG=<package> source tv.sh`
#   tv_alive                 is PKG the resumed (foreground) activity?
#   tv_shot <name>           screenshot to $TV_OUT/<name>.png (retries: the Sony sometimes returns an empty file)
#   tv_cold_start            fresh process in front without the Sony's TV center stealing the foreground afterwards
#   tv_user_keys [seconds]   key presses from a physical remote in the last N seconds (someone is using the device: do not inject)
#   tv_measure <name> <cmd> [settle]   reset gfxinfo, run <cmd>, wait, save framestats to $TV_OUT/<name>.framestats.txt,
#                                      report whether the process changed or left the foreground
# Output goes to TV_OUT (default a temp dir per device), never into the repo.
export MSYS_NO_PATHCONV=1
: "${ANDROID_SERIAL:?set ANDROID_SERIAL (e.g. 10.0.0.250:5555 for the Sony, 10.0.0.203:5555 for the Shield)}"
: "${PKG:?set PKG to the package under test}"
TV_ACTIVITY="${TV_ACTIVITY:-$PKG/me.him188.ani.android.activity.MainActivity}"
TV_OUT="${TV_OUT:-${TMPDIR:-/tmp}/izuko-tv-${ANDROID_SERIAL//[:.]/_}}"
mkdir -p "$TV_OUT"

tv_alive() { adb shell dumpsys activity activities | grep -q "mResumedActivity.*$PKG/"; }

tv_shot() {
  local i
  for i in 1 2 3; do
    adb exec-out screencap -p > "$TV_OUT/$1.png"
    [ -s "$TV_OUT/$1.png" ] && { echo "$TV_OUT/$1.png"; return 0; }
    sleep 0.5
  done
  echo "screenshot $1 came back empty" >&2
  return 1
}

# force-stop while the app is in front makes the Sony fall back to its HDMI/TV center page, which then takes the foreground again
# a few seconds (up to ~30 s) later. Going home first and killing the background process avoids it.
tv_cold_start() {
  adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
  sleep 1
  adb shell am kill "$PKG"
  adb shell am start -n "$TV_ACTIVITY" >/dev/null
  local i
  for i in $(seq 1 30); do tv_alive && return 0; sleep 1; done
  echo "$PKG did not come to the foreground" >&2
  return 1
}

# Injected keys (`input keyevent`) carry deviceId=-1; the hid.sh remote and the physical remote have their own ids.
# Sony: `asd: KeyEvent {... deviceId=N}`; Shield: `handleComboKeys keyCode: N, keyAction: 0` without a matching `injectKeyEvent`.
tv_user_keys() {
  local since="${1:-60}"
  adb logcat -d -v epoch -t "$(($(date +%s) - since)).0" 2>/dev/null |
    grep -E "asd: KeyEvent|handleComboKeys.*keyAction: 0|injectKeyEvent" | grep -v "deviceId=-1" | tail -20
}

tv_measure() {
  local name=$1 cmd=$2 settle=${3:-2.5} pid0 pid1 state=ok
  adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
  pid0=$(adb shell pidof "$PKG" | tr -d '\r')
  eval "$cmd"
  sleep "$settle"
  adb shell dumpsys gfxinfo "$PKG" framestats > "$TV_OUT/$name.framestats.txt"
  pid1=$(adb shell pidof "$PKG" | tr -d '\r')
  [ "$pid0" != "$pid1" ] && state="PROCESS CHANGED ($pid0 -> $pid1)"
  tv_alive || state="$state; NOT FOREGROUND"
  echo "## $name: $state  ($TV_OUT/$name.framestats.txt)"
}
