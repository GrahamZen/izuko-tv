#!/usr/bin/env bash
#
# 多个 session 共用同一个工作区时的 Gradle 串行闸.
#
# 为什么需要它: **Gradle 不支持同一个项目目录的并发构建** —— 它的 project lock 保护不到 Kotlin
# 编译的输出目录. 两边同时跑的典型症状 (都实际踩过):
#   - Could not delete 'app/shared/<模块>/build/classes/kotlin/android/main/me'
#   - Gradle build daemon disappeared unexpectedly
#   - e: Daemon compilation failed
# 这类失败跟代码无关, 重跑就好, 但一次全量构建十几分钟, 白等两轮很亏.
#
# 做法是一把目录锁把构建串起来: 抢到锁才跑, 跑完释放. 等锁那段时间对方编的是**同一份源码**,
# 轮到自己时多半直接 up-to-date 秒过 —— 所以**不必自己判断"我那部分是不是已经被编过了"**,
# 交给 Gradle 的 up-to-date 检查 (它按文件哈希判, 比比较时间戳准; 不同 session 跑的任务覆盖
# 范围不一样, 拿产物时间戳判会误以为自己的模块也编过了).
#
# 锁管不了的一件事: 别的 session 正在**编辑**源文件时, 你的构建会读到半成品 (踩过一次,
# 报的是自己根本没碰过的文件里 "Unresolved reference"). 遇到这种先别怀疑自己的改动, 过一会儿重试.
#
# 用法:
#   ./scripts/gradle-lock.sh :app:android:assembleDefaultTvDebug
#   ./scripts/gradle-lock.sh :app:shared:app-data:testAndroidHostTest --tests "*FooTest*"
#
# 可调 (环境变量):
#   GRADLE_LOCK_WAIT=1800   最多等多久 (秒); 默认 30 分钟, 一次全量构建的量级
#   GRADLE_LOCK_STALE=3600  锁多久没动静就当上次构建崩了并接管 (秒); 默认 1 小时
#
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/.build.lock"
WAIT_SECONDS=${GRADLE_LOCK_WAIT:-1800}
STALE_SECONDS=${GRADLE_LOCK_STALE:-3600}

now() { date +%s; }

acquire_lock() {
    local deadline=$(( $(now) + WAIT_SECONDS ))
    local announced=0
    while :; do
        # mkdir 存在即失败, 是原子的 (Windows 上同样), 所以拿它当锁
        if mkdir "$LOCK" 2>/dev/null; then
            echo "$$" > "$LOCK/pid"
            now > "$LOCK/started"
            return 0
        fi

        local started age
        started=$(cat "$LOCK/started" 2>/dev/null || echo 0)
        age=$(( $(now) - started ))

        # 上一次构建崩了 / 被 kill 掉, 锁没人清: 超过阈值就接管, 否则所有 session 一起卡死
        if [ "$age" -gt "$STALE_SECONDS" ]; then
            echo "[gradle-lock] 锁已 ${age}s 没动静, 视为上次构建异常退出, 接管" >&2
            rm -rf "$LOCK"
            continue
        fi

        if [ "$(now)" -ge "$deadline" ]; then
            echo "[gradle-lock] 等锁超时 (${WAIT_SECONDS}s), 持有者 pid=$(cat "$LOCK/pid" 2>/dev/null)." >&2
            echo "[gradle-lock] 确认没有构建在跑的话手动清: rm -rf '$LOCK'" >&2
            return 1
        fi

        if [ "$announced" -eq 0 ]; then
            echo "[gradle-lock] 另一个构建正在跑 (pid=$(cat "$LOCK/pid" 2>/dev/null)), 排队中…" >&2
            announced=1
        fi
        sleep 3
    done
}

run_gradle() { (cd "$ROOT" && ./gradlew "$@"); }

acquire_lock || exit 1
# 拿到锁之后才装 trap: 装早了会在抢锁失败那条路上删掉**别人**的锁
trap 'rm -rf "$LOCK"' EXIT INT TERM

OUT="$LOCK/output.log"
run_gradle "$@" 2>&1 | tee "$OUT"
status=${PIPESTATUS[0]}

# 并发留下的瞬态故障重试一次: 这类失败与代码无关, 而重跑通常一次就过
if [ "$status" -ne 0 ] && grep -qE "Could not delete|daemon disappeared|Daemon compilation failed|Timeout waiting to lock" "$OUT"; then
    echo "[gradle-lock] 疑似并发留下的瞬态故障, 重试一次" >&2
    run_gradle "$@" 2>&1 | tee "$OUT"
    status=${PIPESTATUS[0]}
fi

# 锁只管构建不管编辑: 报错文件不是自己改的时候, 多半是别人正改到一半
if [ "$status" -ne 0 ] && grep -q "^e: .*Unresolved reference" "$OUT"; then
    echo "[gradle-lock] 提示: 若报错的文件不是你改的, 可能是别的 session 正在编辑它, 过一会儿再试" >&2
fi

exit "$status"
