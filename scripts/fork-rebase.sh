#!/usr/bin/env bash
# fork 全栈 rebase 到上游的助手.
#
# 这个 fork 是两层叠的: upstream/main → main (fork 自有提交) → feat/* (在 main 之上).
# 而 feat/* 是 main 的直系后代, 所以**一趟就能重放整栈** —— 用 --update-refs 让 git 自己
# 把 main 挪到正确位置, 不必"先 rebase main, 再记住旧 main 的 SHA 去 --onto" (那一步记错
# 一次就得从备份重来).
#
#   ./scripts/fork-rebase.sh preflight          # 探这次会在哪打架, 不动任何东西
#   ./scripts/fork-rebase.sh dryrun             # 在内存里整栈重放一遍, 报告自动规则处理完还剩多少要人手 (不动任何东西)
#   ./scripts/fork-rebase.sh run                # 打备份 tag + 一趟重放; 停下来时先自动处理机械冲突
#   ./scripts/fork-rebase.sh continue           # 手工解完一处后: 再自动处理 + 继续, 直到下一处要人手的冲突
#   ./scripts/fork-rebase.sh verify             # 逐条对照重放前后, 看哪条被上游改了
#
# 自动规则 (只处理结果唯一的冲突, 其余照旧停下来给人):
#   - 目录改名 (如上游把 src/main 挪到 src/default): merge.directoryRenames=true, 跟着挪;
#   - strings.xml: 按 key 三方合并 (scripts/rebase/merge-android-strings.py), 行挨着改不再算冲突;
#   - fork 删掉的文件被上游改了: 保持删除;
#   - scripts/rebase/take-fork.txt 里 fork 整体重写过的文件: 整份取 fork 版;
#   - .kt 里每个冲突块都只是「两侧各自新增」或「两侧只动了 import」: 取并集 (scripts/rebase/union_resolve.py,
#     需要 diff3 风格的冲突标记, 重放时带 merge.conflictStyle=diff3).
# 驱动与属性装在本地 (.git/config 与 .git/info/attributes), 不改仓库文件; 用到它们的子命令都会先装一遍.
#
# fork 内部 (feat/* 追 main, 不涉及上游):
#
#   ./scripts/fork-rebase.sh stack-preflight    # 找落点、算规模、列会打架的文件, 不动东西
#   ./scripts/fork-rebase.sh stack              # 打备份 tag + 重放到 main 上
#   ./scripts/fork-rebase.sh stack-verify       # 对拍
#
# **最省事的路是根本不用 stack**: 改写 main (fixup/amend/reorder) 时**从 feat 的顶上发起**
# `git rebase -i --update-refs <要动的那条的父提交>`, main 的 ref 会被一起挪到正确位置,
# feat 侧零操作. 只有 main **追加**了新提交 (没改写) 时才需要动 feat, 而那时 `git rebase main`
# 直接就是对的 (落点仍是 main 的祖先). stack 只是给"忘了 --update-refs 就把 main 重写了"
# 这种情况兜底 —— 那时 feat 的落点已不是 main 的祖先, 直接 `git rebase main` 会把 feat 里那份
# **旧的 main 历史副本** (TV 基线 303 文件) 重新贴一遍, 是灾难.
#
# 别把 rebase.updateRefs 设成全局 true: 它会把范围内**所有**分支 ref 一起挪, 这个仓库里有
# 二十来个 backup/* 分支散在历史各处.
#
# 环境变量: UPSTREAM (默认 upstream/main), TIP (默认当前分支)
set -euo pipefail
# git status --porcelain / diff --name-only 给的是相对仓库根的路径, 脚本里的相对路径也都按仓库根写
cd "$(git rev-parse --show-toplevel)"

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

# 自动规则里需要本地配置的部分: strings.xml 的合并驱动 + 属性; rerere
install_rules() {
    git config merge.android-strings.name "Android strings.xml 按 key 三方合并"
    git config merge.android-strings.driver "uv run --no-project python scripts/rebase/merge-android-strings.py %O %A %B %P"
    local attrs; attrs="$(git rev-parse --git-common-dir)/info/attributes"
    local rule='**/res/values*/strings.xml merge=android-strings'
    mkdir -p "$(dirname "$attrs")"
    grep -qsxF -- "$rule" "$attrs" || echo "$rule" >> "$attrs"
    git config rerere.enabled true
    git config rerere.autoUpdate true   # 认得出的冲突自动解并入暂存区
}

rebase_in_progress() {
    [ -d "$(git rev-parse --git-path rebase-merge)" ] || [ -d "$(git rev-parse --git-path rebase-apply)" ]
}

# 按自动规则处理当前停下来的冲突; 处理不了的原样留着
auto_resolve() {
    local p
    # UD = deleted by them: rebase 里 them 是正在重放的 fork 提交 —— fork 删了、上游改了, 保持删除
    git status --porcelain=v1 | awk '$1 == "UD" { sub(/^UD /, ""); print }' | while IFS= read -r p; do
        git rm -q -- "$p" >/dev/null && echo "  自动: fork 删掉的保持删除  $p"
    done
    # fork 整体重写过的文件: 整份取 fork 版 (rebase 里 fork 那一侧是 --theirs)
    git diff --name-only --diff-filter=U | while IFS= read -r p; do
        if grep -v '^[[:space:]]*#' scripts/rebase/take-fork.txt | grep -qxF -- "$p"; then
            git checkout -q --theirs -- "$p" && git add -- "$p" && echo "  自动: 整份取 fork 版      $p"
        fi
    done
    # 两侧只是各自新增 / 只动了 import: 取并集 (解不开的原样留着)
    # 只处理 UU (两侧都改了); AA (两侧都新建了同名文件) 拼起来会重复定义, 留给人
    git status --porcelain=v1 -- '*.kt' | awk '$1 == "UU" { sub(/^UU /, ""); print }' | while IFS= read -r p; do
        if uv run --no-project python scripts/rebase/union_resolve.py "$p"; then
            git add -- "$p" && echo "  自动: 两侧各自新增, 取并集 $p"
        fi
    done
}

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

    install_rules

    hr "一趟重放 $(git rev-list --count "$mb".."$TIP") 条到 $UPSTREAM"
    echo "停在要人手的冲突时: 解完 git add, 然后 ./scripts/fork-rebase.sh continue; 放弃用 git rebase --abort"
    if git -c merge.directoryRenames=true -c merge.conflictStyle=diff3 rebase --update-refs --onto "$UPSTREAM" "$mb" "$TIP"; then
        hr "完成; 接着跑 verify"
    else
        rebase_in_progress || die "rebase 没有开始, 看上面 git 的报错"
        cmd_continue
    fi
}

# 自动处理 + 继续, 直到重放完或遇到要人手的冲突
cmd_continue() {
    rebase_in_progress || die "当前没有进行中的 rebase"
    install_rules
    local last=""
    while rebase_in_progress; do
        auto_resolve
        local left; left=$(git diff --name-only --diff-filter=U)
        if [ -n "$left" ]; then
            local stopped; stopped=$(cat "$(git rev-parse --git-path rebase-merge)/stopped-sha" 2>/dev/null || echo HEAD)
            hr "要人手的冲突 ($(git log -1 --format='%h %s' "$stopped"))"
            echo "$left" | sed 's/^/  /'
            echo "解完 git add, 然后再跑 ./scripts/fork-rebase.sh continue"
            return 1
        fi
        local here; here=$(git rev-parse HEAD)
        # 连着两次原地不动 (不是冲突、是别的原因停下) 就交给人, 免得死循环
        if [ "$here" = "$last" ]; then
            git status --short | head -20
            die "rebase 停在 $(git log -1 --format='%h %s' HEAD) 之后且没有可自动处理的冲突, 看上面的状态手工处理"
        fi
        last=$here
        GIT_EDITOR=true git -c merge.directoryRenames=true -c merge.conflictStyle=diff3 rebase --continue || true
    done
    hr "重放完成; 接着跑 verify"
}

cmd_dryrun() {
    install_rules
    git fetch upstream --quiet
    uv run --no-project python scripts/rebase/dryrun.py --onto "$UPSTREAM" --tip "$TIP"
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

# ---------------------------------------------------------------------------
# feat/* 追 main
# ---------------------------------------------------------------------------

# feat 上次落在 main 的哪一条上. 三条路, 按可靠程度排:
#   1. 上次 stack 记下的 tag forkbase/<TIP>
#   2. 分支自己的 reflog 里最近一条 "rebase (finish): ... onto <sha>"
#   3. git 自己按 main 的 reflog 猜 (--fork-point; reflog 会过期, 所以排最后)
stack_base() {
    local tag="forkbase/$TIP"
    if git rev-parse -q --verify "refs/tags/$tag" >/dev/null &&
       git merge-base --is-ancestor "$tag" "$TIP"; then
        echo "$(git rev-parse "$tag") tag:$tag"; return
    fi
    local sha
    sha=$(git reflog show "$TIP" 2>/dev/null |
          grep -oE "rebase \(finish\): refs/heads/$TIP onto [0-9a-f]+" |
          head -1 | awk '{print $NF}') || true
    if [ -n "$sha" ] && git merge-base --is-ancestor "$sha" "$TIP"; then
        echo "$sha reflog"; return
    fi
    sha=$(git merge-base --fork-point main "$TIP" 2>/dev/null) || true
    if [ -n "$sha" ] && [ "$sha" != "$(git merge-base main "$TIP")" ]; then
        echo "$sha fork-point"; return
    fi
    return 1
}

# 设置全局 STACK_BASE; 已经叠好时返回 1
stack_report() {
    [ "$TIP" != "main" ] || die "在 feat/* 分支上跑, 不是 main"

    if git merge-base --is-ancestor main "$TIP"; then
        echo "$TIP 已经叠在 main 上 (main 之上 $(git rev-list --count main.."$TIP") 条), 无事可做."
        echo "main 只是追加了新提交的话, 直接 git rebase main 就行."
        return 1
    fi

    local found how
    found=$(stack_base) || die "找不到 $TIP 上次的落点.
手动找: git reflog show $TIP | grep 'rebase (finish)'; 或者
       git tag forkbase/$TIP <落点 sha>  然后重跑."
    STACK_BASE=${found%% *}; how=${found#* }
    local base=$STACK_BASE

    hr "落点"
    echo "  $(git log --oneline -1 "$base")   (来源: $how)"
    hr "规模"
    echo "  要重放 (feat 自己的活儿)   $(git rev-list --count "$base".."$TIP") 条"
    echo "  feat 独有 (按 patch-id)     $(git rev-list --count --cherry-pick --right-only main..."$TIP") 条"
    echo "  main 独有 (按 patch-id)     $(git rev-list --count --cherry-pick --left-only main..."$TIP") 条"
    echo "  ↑ 第一行应等于第二行减去下面\"会被丢掉\"的条数; 对不上就别往下走"

    # 落点之前、但 main 上已没有的那些 = feat 里的旧副本. 它们会被 --onto 原地丢掉,
    # 所以每一条都要能在 main 上找到新版 —— 这里按提交说明首行粗查, 查不到的要人工确认
    hr "会被丢掉的旧副本 (每条都该能在 main 上找到新版)"
    git log --format='%h %s' --cherry-pick --right-only main..."$base" |
        while read -r h subject; do
            if git log --format=%h -1 --fixed-strings --grep="$subject" main | grep -q .; then
                echo "  ✓ $h $subject"
            else
                echo "  ✗ $h $subject   ← main 上按标题找不到, 先确认再继续"
            fi
        done

    hr "两边都改的文件 (这次会打架的地方)"
    comm -12 <(git diff --name-only "$base" "$TIP" | sort)              <(git diff --name-only "$base" main 2>/dev/null | sort) || true

    hr "Room 数据库版本 (撞车会开不了机)"
    for ref in main "$TIP"; do
        printf '  %-24s ' "$ref"
        git show "$ref:app/shared/app-data/src/commonMain/kotlin/data/persistent/database/AniDatabase.kt" 2>/dev/null |
            grep -oE 'version = [0-9]+' | head -1 || echo "?"
    done
}

cmd_stack_preflight() {
    stack_report || true
}

cmd_stack() {
    require_clean
    stack_report || return 0
    local base=$STACK_BASE

    local tag_tip="forkbak/stack-$STAMP/$TIP" tag_main="forkbak/stack-$STAMP/main"
    git tag -f "$tag_tip" "$TIP" >/dev/null
    git tag -f "$tag_main" main >/dev/null
    { echo "STACK_BASE=$base"; echo "OLD_TIP=$tag_tip"; echo "OLD_MAIN=$tag_main"; } > ".git/fork-stack-$STAMP.env"
    echo
    echo "备份: $tag_tip / $tag_main"

    # rerere 关掉: rr-cache 里有当初反向 pick 到 main 时记下的**相反**答案 (踩过)
    hr "重放 $(git rev-list --count "$base".."$TIP") 条到 main"
    echo "冲突时: 解完 git add, 然后 git rebase --continue; 放弃用 git rebase --abort"
    echo "modify/delete 的冲突先问\"main 把这个功能搬到哪去了\", 把改动搬过去, 别只保留删除"
    git -c rerere.enabled=false rebase --onto main "$base" "$TIP"

    git tag -f "forkbase/$TIP" main >/dev/null
    hr "完成; 落点已记到 forkbase/$TIP, 接着跑 stack-verify"
}

cmd_stack_verify() {
    local env_file; env_file=$(ls -t .git/fork-stack-*.env 2>/dev/null | head -1)         || die "找不到备份记录, 先跑 stack"
    # shellcheck disable=SC1090
    source "$env_file"

    hr "逐条对照 (只应有被 main 真正改到的那几条是 !)"
    git range-diff "$STACK_BASE..$OLD_TIP" "main..$TIP" || true

    # range-diff 抓不到静默错并 (自动合并成了 main 的版本, 毫无冲突提示); 文件集对拍能抓:
    # 旧 feat → 新 feat 变了的文件, 每一个都应能归因到 main 这次带来的改动
    hr "文件集对拍: 在左不在右的才可疑 (应该只剩你手动清的那几个)"
    comm -23 <(git diff --name-only "$OLD_TIP" "$TIP" | sort)              <(git diff --name-only "$STACK_BASE" main | sort) || true

    hr "行尾自查"
    local a b
    a=$(git diff --numstat "$OLD_TIP" "$TIP" | wc -l)
    b=$(git diff --numstat --ignore-cr-at-eol "$OLD_TIP" "$TIP" | wc -l)
    [ "$a" = "$b" ] && echo "  行尾干净" || echo "  ⚠ 有文件只差行尾"

    hr "还要人工过的"
    cat <<'EOF'
  1. 全树 grep 被删概念的名字 (WatchTogether / playbackAutomationSuppressed …), 清孤儿注释
  2. 编译 + 测试: ./gradlew :app:android:assembleDefaultTvDebug :app:shared:app-data:testAndroidHostTest
EOF
}

case "${1:-}" in
    install)      install_rules; echo "已装好自动规则 (strings.xml 合并驱动 / 属性 / rerere)" ;;
    preflight)    cmd_preflight ;;
    dryrun)       cmd_dryrun ;;
    run)          cmd_run ;;
    continue)     cmd_continue ;;
    verify)       cmd_verify ;;
    stack-preflight) cmd_stack_preflight ;;
    stack)           cmd_stack ;;
    stack-verify)    cmd_stack_verify ;;
    *) die "用法: $0 {install|preflight|dryrun|run|continue|verify|stack-preflight|stack|stack-verify}" ;;
esac
