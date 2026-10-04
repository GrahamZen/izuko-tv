#!/usr/bin/env bash
# Virtual remote over uhid (a Consumer Control device registered with the device's own `hid` command).
# Use it where `adb shell input keyevent` cannot do the job: holding a key (the system generates the auto-repeat, exactly like a
# real remote), fast repeated presses, precise gaps. On the Sony `sendevent` is blocked by SELinux; `hid` works through /dev/uhid.
#
#   hid.sh pipe          keep running (in the background): registers the device, then feeds every event appended to the queue file
#   hid.sh keys <seq>    queue a key sequence and wait until it has played, e.g.
#                        hid.sh keys "D:600 D:600 U:600 OK:800 BACK:800 W:4000 HOLD_D:1200"
#                        X:ms = press X once, then wait ms; HOLD_X:ms = hold X for ms, then release; W:ms = only wait
#                        keys: U D L R OK BACK
#
# SERIAL picks the device (default the Sony). The queue lives in HID_STATE (default a temp dir per device), not in the repo.
# Delays run on the device, so timing is exact; `keys` sleeps for the total length of the sequence before it returns.
# Key events from this device show up in logcat with their own deviceId (`input keyevent` is -1, the Sony's remote has another id).
set -u
SERIAL="${SERIAL:-10.0.0.250:5555}"
STATE="${HID_STATE:-${TMPDIR:-/tmp}/izuko-hid-${SERIAL//[:.]/_}}"
IN="$STATE/hid_in.jsonl"
export MSYS_NO_PATHCONV=1
mkdir -p "$STATE"

code() {
  case "$1" in
    OK) echo "65,0" ;; U) echo "66,0" ;; D) echo "67,0" ;; L) echo "68,0" ;; R) echo "69,0" ;; BACK) echo "36,2" ;;
    *) echo "bad key $1" >&2; exit 2 ;;
  esac
}

case "${1:-}" in
  pipe)
    : > "$IN"
    # Consumer Control descriptor: report id 1, one 16-bit usage (0x41 select, 0x42-0x45 up/down/left/right, 0x224 back)
    echo '{"id":1,"command":"register","name":"izuko-test-remote","vid":6353,"pid":11328,"bus":"usb","descriptor":[5,12,9,1,161,1,133,1,25,0,42,60,2,21,0,38,60,2,149,1,117,16,129,0,192]}' >> "$IN"
    exec bash -c "tail -f -s 0.02 '$IN' | adb -s $SERIAL shell hid -"
    ;;
  keys)
    shift
    [ -s "$IN" ] || { echo "start 'hid.sh pipe' first (SERIAL=$SERIAL)" >&2; exit 2; }
    total=0
    for tok in $*; do
      k="${tok%%:*}"; ms="${tok##*:}"
      if [ "$k" = "W" ]; then
        echo "{\"id\":1,\"command\":\"delay\",\"duration\":$ms}" >> "$IN"
      elif [ "${k#HOLD_}" != "$k" ]; then
        c=$(code "${k#HOLD_}")
        echo "{\"id\":1,\"command\":\"report\",\"report\":[1,$c]}" >> "$IN"
        echo "{\"id\":1,\"command\":\"delay\",\"duration\":$ms}" >> "$IN"
        echo '{"id":1,"command":"report","report":[1,0,0]}' >> "$IN"
      else
        c=$(code "$k")
        echo "{\"id\":1,\"command\":\"report\",\"report\":[1,$c]}" >> "$IN"
        echo '{"id":1,"command":"delay","duration":60}' >> "$IN"
        echo '{"id":1,"command":"report","report":[1,0,0]}' >> "$IN"
        echo "{\"id\":1,\"command\":\"delay\",\"duration\":$ms}" >> "$IN"
        ms=$((ms + 60))
      fi
      total=$((total + ms))
    done
    # leave some room for the pipe and adb
    sleep "$(awk "BEGIN{print ($total + 400) / 1000}")"
    ;;
  *)
    echo "usage: [SERIAL=ip:port] hid.sh pipe | keys <seq>" >&2; exit 2 ;;
esac
