#!/usr/bin/env bash
# fork 全栈 rebase 到上游的助手.
#
# 这个 fork 是两层叠的: upstream/main → main (fork 自有提交) → feat/* (在 main 之上).
# 而 feat/* 是 main 的直系后代, 所以**一趟就能重放整栈** —— 用 --update-refs 让 git 自己
# 把 main 挪到正确位置, 不必"先 rebase main, 再记住旧 main 的 SHA 去 --onto" (那一步记错
# 一次就得从备份重来).
#
#   ./scripts/fork-rebase.sh preflight          # 探这次会在哪打架, 不动任何东西
#   ./scripts/fork-rebase.sh run                # 打备份 tag + 一趟重放
#   ./scripts/fork-rebase.sh verify             # 逐条对照重放前后, 看哪条被上游改了
#
# 环境变量: UPSTREAM (默认 upstream/main), TIP (默认当前分支)
set -euo pipefail

UPSTREAM="${UPSTREAM:-upstream/main}"
TIP="${TIP:-$(git rev-parse --abbrev-ref HEAD)}"
STAMP="$(date +%Y%m%d)"
# 前缀不能用 backup/: **上游自己有一个叫 `backup` 的 tag** (f61190506), 每次 fetch upstream
# 都会把它拉回来, 而一个名叫 backup 的 ref 占死了 backup/* 整个命名空间 —— 表现是
# "fatal: 'refs/tags/backup' exists; cannot create ...", 删掉也会被下次 fetch 带回来.
TAG_TIP="forkbak/rebase-$STAMP/$TIP"
TAG_MAIN="forkbak/rebase-$STAMP/main"
NOTE_FILE=".git/fork-rebase-$STAMP.env"

hr() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

require_clean() {
    [ -z "$(git status --porcelain --untracked-files=no)" ] || die "工作区不干净, 先提交或 stash"
}

# --update-refs 会挪动**范围内的所有分支 ref**, 备份分支首当其冲 (踩过). tag 不受影响,
# 所以备份一律用 tag; 这里先揪出范围内的分支, 免得默默把某个 backup/* 冲掉.
branches_in_range() {
    local mb="$1"
    git for-each-ref --format='%(refname:short) %(objectname)' refs/heads |
        while read -r name sha; do
            [ "$name" = "main" ] && continue
            [ "$name" = "$TIP" ] && continue
            if git merge-base --is-ancestor "$sha" "$TIP" 2>/dev/null &&
               git merge-base --is-ancestor "$mb" "$sha" 2>/dev/null; then
                echo "$name"
            fi
        done
}

cmd_preflight() {
    git fetch upstream --quiet
    local mb; mb=$(git merge-base main "$UPSTREAM")

    hr "规模"
    echo "  merge-base      $(git log --oneline -1 "$mb")"
    echo "  fork main 自有  $(git rev-list --count "$mb"..main) 条"
    echo "  $TIP 之上        $(git rev-list --count main.."$TIP") 条"
    echo "  上游新提交      $(git rev-list --count "$mb".."$UPSTREAM") 条"

    hr "上游新提交"
    git log --oneline "$mb".."$UPSTREAM" | cat

    hr "两边都改的文件 (每轮的固定冲突税)"
    comm -12 <(git diff --name-only "$mb" "$TIP" | sort) \
             <(git diff --name-only "$mb" "$UPSTREAM" | sort)

    # 上游删掉整个包是这个 fork 踩过的形态: 连带孤儿 import 与别处的路由引用
    hr "上游删掉的文件里, 有没有 fork 动过的"
    comm -12 <(git diff --name-only --diff-filter=D "$mb" "$UPSTREAM" | sort) \
             <(git diff --name-only "$mb" "$TIP" | sort) || true

    # 直连分支的专属税: 上游往回加 Ani 服务器依赖
    hr "上游新代码里新增的 Ani 服务器依赖 (直连分支要重新拆掉)"
    git diff "$mb".."$UPSTREAM" -- '*.kt' |
        grep -E '^\+' | grep -E 'me\.him188\.ani\.client|myani\.org|api\.animeko\.org' |
        sort -u | head -20 || echo "  (无)"

    # 数据库版本撞车会让 app 开不了机
    hr "Room 数据库版本"
    for ref in "$mb" main "$UPSTREAM"; do
        printf '  %-16s ' "$ref"
        git show "$ref:app/shared/app-data/src/commonMain/kotlin/data/persistent/database/AniDatabase.kt" 2>/dev/null |
            grep -oE 'version = [0-9]+' | head -1 || echo "?"
    done

    hr "范围内的分支 ref (rebase 会把它们一起挪走)"
    branches_in_range "$mb" | sed 's/^/  /' || true
    echo "  ↑ 有的话先转成 tag: git tag <名字> <分支> && git branch -D <分支>"
}

cmd_run() {
    require_clean
    git fetch upstream --quiet
    local mb; mb=$(git merge-base main "$UPSTREAM")

    local stray; stray=$(branches_in_range "$mb" || true)
    if [ -n "$stray" ]; then
        die "这些分支落在重放范围内, --update-refs 会把它们挪走:
$stray
先转成 tag 再来 (tag 不受 --update-refs 影响)."
    fi

    git tag -f "$TAG_TIP" "$TIP" >/dev/null
    git tag -f "$TAG_MAIN" main >/dev/null
    { echo "OLD_MB=$mb"; echo "OLD_TIP=$TAG_TIP"; echo "OLD_MAIN=$TAG_MAIN"; } > "$NOTE_FILE"
    echo "备份: $TAG_TIP / $TAG_MAIN  (记在 $NOTE_FILE)"

    git config rerere.enabled true
    git config rerere.autoUpdate true   # 认得出的冲突自动解并入暂存区

    hr "一趟重放 $(git rev-list --count "$mb".."$TIP") 条到 $UPSTREAM"
    echo "冲突时: 解完 git add, 然后 git rebase --continue; 放弃用 git rebase --abort"
    git rebase --update-refs --onto "$UPSTREAM" "$mb" "$TIP"

    hr "完成; 接着跑 verify"
}

cmd_verify() {
    local env_file; env_file=$(ls -t .git/fork-rebase-*.env 2>/dev/null | head -1) \
        || die "找不到备份记录, 先跑 run"
    # shellcheck disable=SC1090
    source "$env_file"
    local new_mb; new_mb=$(git merge-base "$OLD_MAIN" "$UPSTREAM")

    hr "逐条对照 (只应有被上游真正改到的那几条变化)"
    git range-diff "$OLD_MB..$OLD_TIP" "$UPSTREAM..$TIP" || true

    hr "行尾自查 (Edit 工具翻过 CRLF)"
    local bad; bad=$(git diff --numstat "$OLD_TIP" "$TIP" | wc -l)
    local bad2; bad2=$(git diff --numstat --ignore-cr-at-eol "$OLD_TIP" "$TIP" | wc -l)
    [ "$bad" = "$bad2" ] && echo "  行尾干净" || echo "  ⚠ 有文件只差行尾, 查 git diff --numstat 与 --ignore-cr-at-eol 的差集"

    hr "还要人工过的卡口"
    cat <<'EOF'
  1. Room 版本: 上游若升过, fork 的迁移要往后推一版, 否则装上去开不了机
  2. 上游把 fork 的功能自己实现了一遍 → 删掉 fork 那份, 别留两套
  3. Nav3 per-entry lifecycle 语义: 靠页面 ON_STOP/ON_START 的地方全要重看
  4. 编译两个变体 + 跑锚点测试: ANI_TMDB_E2E=fresh
EOF
}

case "${1:-}" in
    preflight) cmd_preflight ;;
    run)       cmd_run ;;
    verify)    cmd_verify ;;
    *) die "用法: $0 {preflight|run|verify}" ;;
esac
