#!/usr/bin/env bash
# 抓一份 Perfetto 系统轨迹 (调度 + 线程状态 + atrace + 本应用 Trace 区段), 拉回本机.
# 用法: SERIAL=10.0.0.250:5555 perfetto.sh <out.pftrace> [秒数=12] [应用包名=io.github.grahamzen.anime.tv]
# 配置用同目录的 perfetto_app.txt (经 stdin 传: Android 12 起 traced 读不到 /data/local/tmp 里的配置文件).
# 后台跑起来再去操作: perfetto.sh out.pftrace 12 & sleep 1.5; <开播 / 按键…>; wait
set -euo pipefail
OUT=${1:?out.pftrace}; SECS=${2:-12}; PKG=${3:-io.github.grahamzen.anime.tv}
ADB=${ADB:-adb}; SERIAL=${SERIAL:?SERIAL}
DIR=$(cd "$(dirname "$0")" && pwd)
REMOTE=/data/misc/perfetto-traces/cc_$$.pftrace
sed -e "s/duration_ms: [0-9]*/duration_ms: $((SECS * 1000))/" -e "s/io\.github\.grahamzen\.anime\.tv/$PKG/" "$DIR/perfetto_app.txt" |
  MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" shell "perfetto --txt -c - -o $REMOTE" >/dev/null
MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" pull "$REMOTE" "$OUT" >/dev/null
MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" shell "rm -f $REMOTE" </dev/null
echo "$OUT"
