#!/system/bin/sh
# Memory sampler that runs ON the device (dumpsys over adb is too slow to poll from the host).
#   adb push memloop.sh /data/local/tmp/ && adb shell sh /data/local/tmp/memloop.sh <pid> /data/local/tmp/mem.txt /data/local/tmp/mem.stop &
#   ... run the scenario ...;  adb shell touch /data/local/tmp/mem.stop;  adb pull /data/local/tmp/mem.txt
# Each round: uptime seconds, the main process's App Summary rows (one dumpsys takes over a second) and the system MemAvailable.
PID=$1
OUT=$2
STOP=$3
rm -f "$STOP"
: > "$OUT"
while [ ! -f "$STOP" ]; do
  echo "T $(cut -d' ' -f1 /proc/uptime)" >> "$OUT"
  if [ -d "/proc/$PID" ]; then
    dumpsys meminfo "$PID" | grep -E "Java Heap:|Native Heap:|Code:|Graphics:|Private Other:|System:|TOTAL PSS:" >> "$OUT"
  else
    echo "GONE" >> "$OUT"
  fi
  grep -E "^MemAvailable" /proc/meminfo >> "$OUT"
done
