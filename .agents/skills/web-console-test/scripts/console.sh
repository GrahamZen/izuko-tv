#!/usr/bin/env bash
# Drive the Izuko TV web console (the LAN HTTP control page the TV app serves) from the dev machine.
# See ../SKILL.md. Needs adb, curl and `uv run python` (Git Bash on Windows is fine).
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-10.0.0.203:5555}"
STATE_DIR="${CONSOLE_STATE_DIR:-${TMPDIR:-/tmp}/izuko-console}"
mkdir -p "$STATE_DIR"
# Git Bash: /tmp 之类的路径 Windows 上的 python 打不开, 换成 C:/... 形式 (bash 与 curl 也认)
command -v cygpath >/dev/null 2>&1 && STATE_DIR=$(cygpath -m "$STATE_DIR")
URL_FILE="$STATE_DIR/url"
export MSYS_NO_PATHCONV=1 PYTHONIOENCODING=utf-8

die() { echo "console.sh: $*" >&2; exit 1; }
mask() { sed -E 's#(http://[^/]+/)[^/ ]+/#\1<token>/#g'; }
base() { [ -s "$URL_FILE" ] || die "no console URL yet, run: console.sh url <package>"; cat "$URL_FILE"; }
py() { uv run python - "$@"; }

cmd_url() {
  local pkg="${1:?package name, e.g. io.github.grahamzen.anime.tv.debug2}" pid line
  pid=$(adb -s "$SERIAL" shell pidof "$pkg" | tr -d '\r')
  [ -n "$pid" ] || die "$pkg is not running on $SERIAL"
  # Every Izuko package runs its own console on its own port: take the line logged by this pid.
  line=$(adb -s "$SERIAL" logcat -d -v brief 2>/dev/null | grep -E "\( *$pid\).*Remote control reachable at" | tail -1 || true)
  if [ -z "$line" ]; then
    # logcat rotated: the package's own app.log (debuggable builds only), whose last line belongs to the running process
    line=$(adb -s "$SERIAL" exec-out run-as "$pkg" cat files/logs/app.log 2>/dev/null | grep "Remote control reachable at" | tail -1 || true)
  fi
  [ -n "$line" ] || die "pid $pid has not logged its console address (restart the app, or open the console page once)"
  echo "$line" | grep -oE "http://[^ ]+/" > "$URL_FILE"
  echo "pid $pid: $(mask < "$URL_FILE")"
}

cmd_get() {
  # The console answers one connection at a time; an empty reply now and then (another client polling) is retried
  local out="" attempt
  for attempt in 1 2 3; do
    out=$(curl -s --max-time 15 "$(base)${1:?path}" || true)
    [ -n "$out" ] && break
    sleep 1
  done
  [ -n "$out" ] || die "no reply from $(mask <<< "$(base)")${1} (app not running, or not in front?)"
  echo "$out"
}

# post <path> [curl --data-urlencode args...]
cmd_post() {
  local path="${1:?path}"; shift
  local args=()
  for kv in "$@"; do args+=(--data-urlencode "$kv"); done
  curl -s --max-time 15 -X POST "${args[@]}" "$(base)$path"; echo
}

cmd_state() {
  cmd_get api/player > "$STATE_DIR/player.json"
  py "$STATE_DIR/player.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
if not d.get("available"):
    print("player not available:", d.get("reason"), d.get("title", "")); sys.exit()
p = d.get("playback") or {}
print(f"{d.get('title')} | episode {d.get('episode')} | background={d.get('background')}")
print(f"source: {d.get('selectedSource')} | {d.get('selectedTitle')}")
print(f"position {p.get('position')} / {p.get('duration')} ms, playing={p.get('playing')}, speed={p.get('speed')}")
for g in d.get("groups") or []:
    print(f"  [{g['name']}] {len(g['items'])} item(s)" + (" (+more)" if g.get("more") else ""))
EOF
}

cmd_candidates() {
  local group="${1:?source name as shown in state, e.g. PanSou}"
  cmd_get api/player > "$STATE_DIR/player.json"
  py "$STATE_DIR/player.json" "$group" <<'EOF'
import json, sys
group = sys.argv[2]
d = json.load(open(sys.argv[1], encoding="utf-8"))
for g in d.get("groups") or []:
    if g["name"] == group:
        for i, it in enumerate(g["items"]):
            print(i, it.get("size"), it.get("resolution"), it["title"][:100])
EOF
}

cmd_select() {
  local group="${1:?source name}" index="${2:-0}" idfile="$STATE_DIR/select-id.txt"
  cmd_get api/player > "$STATE_DIR/player.json"
  # Web-source ids contain Chinese; on Windows a command-line argument mangles them, so the id goes to curl through a file
  py "$STATE_DIR/player.json" "$group" "$index" "$idfile" <<'EOF'
import json, sys
group, index, out = sys.argv[2], int(sys.argv[3]), sys.argv[4]
d = json.load(open(sys.argv[1], encoding="utf-8"))
items = [it for g in d.get("groups") or [] if g["name"] == group for it in g["items"]]
open(out, "w", encoding="utf-8", newline="").write(items[index]["id"] if index < len(items) else "")
EOF
  [ -s "$idfile" ] || die "no candidate $index under source '$group' (see: console.sh state)"
  cmd_post api/player/select "id@$idfile"
}

# control play|pause|toggle|back|forward|mute | control seek <ms> | control speed <x> | control volume <0..1>
cmd_control() {
  local action="${1:?action}"
  case "$action" in
    seek) cmd_post api/player/control action=seek "ms=${2:?ms}" ;;
    speed|volume) cmd_post api/player/control "action=$action" "v=${2:?value}" ;;
    *) cmd_post api/player/control "action=$action" ;;
  esac
}

# seek-measure back <seconds> | seek-measure abs <seconds>: seek, then report when playback moves again
cmd_seek_measure() {
  local mode="${1:?back|abs}" value="${2:?seconds}"
  py "$(base)" "$mode" "$value" <<'EOF'
import json, sys, time, urllib.parse, urllib.request
base, mode, value = sys.argv[1], sys.argv[2], int(sys.argv[3])
def state():
    with urllib.request.urlopen(base + "api/player", timeout=10) as r:
        return json.load(r)["playback"]
def seek(ms):
    data = urllib.parse.urlencode({"action": "seek", "ms": str(ms)}).encode()
    with urllib.request.urlopen(base + "api/player/control", data=data, timeout=10) as r:
        return json.load(r)
pos = state()["position"]
target = pos - value * 1000 if mode == "back" else value * 1000
t0 = time.time()
print("from", pos, "seek to", target, "->", seek(target))
while time.time() - t0 < 60:
    p = state()
    # The reported position updates about once a second: "moving again" = past the target by a second
    if target + 1000 <= p["position"] <= target + 20000 and p["playing"]:
        print(f"playing again at {p['position']} after {time.time() - t0:.1f}s"); break
    time.sleep(0.3)
else:
    print("did not resume within 60s, last position", p["position"])
EOF
}

case "${1:-help}" in
  url) shift; cmd_url "$@" ;;
  get) shift; cmd_get "$@" ;;
  post) shift; cmd_post "$@" ;;
  state) cmd_state ;;
  candidates) shift; cmd_candidates "$@" ;;
  select) shift; cmd_select "$@" ;;
  control) shift; cmd_control "$@" ;;
  seek-measure) shift; cmd_seek_measure "$@" ;;
  history-play) shift; cmd_post api/history/play "id=${1:?subject id}" ;;
  open-player) cmd_post api/player/open ;;
  *) sed -n '2,3p' "$0"; echo "commands: url <pkg> | state | candidates <source> | select <source> [i] | control <action> [v] | seek-measure back|abs <s> | history-play <subjectId> | open-player | get <path> | post <path> [k=v...]" ;;
esac
