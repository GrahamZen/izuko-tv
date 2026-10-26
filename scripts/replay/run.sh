#!/usr/bin/env bash
# 跑数据源搜索录放并记进对照库 (matchdb.py). 用法: scripts/replay/run.sh <标签> [用例集...]
# 用例集: season first extra (网盘, DriveSeasonReplayTest) web (网页, WebSearchReplayTest); 不写时四个都跑.
# 环境变量: REPLAY_DATA (录放数据目录, 含 drivekw/ 与 websearch/), REPLAY_GRAPH (表, 默认 $REPLAY_DATA/drivekw/graph/bgm-anime.tsv),
#           REPLAY_CASES (只跑这几部, 逗号分隔的条目 id; 只跑几部时不记进对照库), REPLAY_NOTE (记进对照库的说明),
#           REPLAY_HOT=1 (网页每部每个源用同一份搜索缓存连查两次, 第二次记成 <标签>-hot),
#           REPLAY_NO_TABLE=1 (认部时当作名字表还没读进来; 建请求照旧用表, 同 app 里系列信息另取).
set -u
: "${REPLAY_DATA:?设 REPLAY_DATA}"
LABEL=$1; shift
SETS=${*:-season first extra web}
GRAPH=${REPLAY_GRAPH:-$REPLAY_DATA/drivekw/graph/bgm-anime.tsv}
TMDB=$REPLAY_DATA/drivekw/tmdb/bgm-tmdb.tsv
cd "$(dirname "$0")/../.."
for set in $SETS; do
  if [ "$set" = web ]; then
    log=$REPLAY_DATA/websearch/gradle-$LABEL.log
    env ${REPLAY_CASES:+WEB_REPLAY_CASES=$REPLAY_CASES} ${REPLAY_HOT:+WEB_REPLAY_HOT=$REPLAY_HOT} ${REPLAY_NO_TABLE:+WEB_REPLAY_NO_TABLE=$REPLAY_NO_TABLE} WEB_REPLAY_DIR=$REPLAY_DATA/websearch WEB_REPLAY_GRAPH=$GRAPH WEB_REPLAY_TMDB=$TMDB WEB_REPLAY_LABEL=$LABEL \
      ./scripts/gradle-lock.sh :app:shared:app-data:desktopTest --tests 'me.him188.ani.app.domain.mediasource.search.WebSearchReplayTest' --rerun -q > "$log" 2>&1
  else
    log=$REPLAY_DATA/drivekw/replay-$set/gradle-$LABEL.log
    env ${REPLAY_CASES:+DRIVE_REPLAY_CASES=$REPLAY_CASES} ${REPLAY_NO_TABLE:+DRIVE_REPLAY_NO_TABLE=$REPLAY_NO_TABLE} DRIVE_REPLAY_DIR=$REPLAY_DATA/drivekw/replay-$set DRIVE_SERIES_GRAPH=$GRAPH DRIVE_TMDB_MAP=$TMDB DRIVE_REPLAY_LABEL=$LABEL \
      ./scripts/gradle-lock.sh :app:shared:app-data:desktopTest --tests 'me.him188.ani.app.domain.mediasource.clouddrive.DriveSeasonReplayTest' --rerun -q > "$log" 2>&1
  fi
  status=$?
  echo "$set: exit $status (日志 $log)"
  if [ $status -eq 0 ] && [ -z "${REPLAY_CASES:-}" ]; then
    PYTHONUTF8=1 uv run --no-project python scripts/replay/matchdb.py import "$set" "$LABEL" ${REPLAY_NOTE:+--note "$REPLAY_NOTE"}
    if [ "$set" = web ] && [ "${REPLAY_HOT:-}" = 1 ]; then
      PYTHONUTF8=1 uv run --no-project python scripts/replay/matchdb.py import web "$LABEL-hot" --note "同一份搜索缓存的第二次查询"
    fi
  fi
done
