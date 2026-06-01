#!/usr/bin/env bash
# 删掉上游自带的那套 Android TV 客户端. 追上游之后跑一次, 结果提交成一条.
#
# 为什么删: 上游 64b111a62d 起自己做了一套 TV 界面 (src/tv flavor + shared-tv + 七个
# ui-*/tv 模块, 源码在各模块的 src/androidTv*/ 下, 共 225 文件 / 3.3 万行), 与 fork 自己的
# app/shared/ui-tv + src/izukoTv 是两套平行实现. fork 一行都用不上, 留着只是:
#   - 八个 Gradle 模块白配置、CI 白编;
#   - 两边都有一个叫 tv 的 product flavor (上游在 distribution 维度, fork 在 formFactor 维度),
#     AGP 要求 flavor 名全局唯一, 不删过不了配置阶段;
#   - 以后每轮上游改这些路径都要再处理一遍 (上游 83 条里 16 条碰过, 331 个文件-触碰).
# 删成一条 fork 提交之后, 以后上游改它们一律是 modify/delete, 由 fork-rebase.sh 的
# 「fork 删掉的保持删除」规则自动吃掉, 零人工.
#
# 用法 (在仓库根, 追完上游之后): ./scripts/rebase/drop-upstream-tv.sh
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

hr() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# 上游 TV 独占的路径. 各模块的 tv/ 子目录里只有 build.gradle.kts, 源码在 src/androidTv*/ 下.
PATHS=(
    'app/android/src/tv'
    'app/shared/shared-tv'
    'app/shared/*/tv'
    'app/shared/*/src/androidTv'
    'app/shared/*/src/androidTvTest'
    'app/shared/*/src/androidTvDeviceTest'
)

hr "删文件"
n=0
for p in "${PATHS[@]}"; do
    # shellcheck disable=SC2086  # 要让 shell 展开 */
    for d in $p; do
        [ -e "$d" ] || continue
        cnt=$(git ls-files -- "$d" | wc -l | tr -d ' ')
        [ "$cnt" = "0" ] && continue
        git rm -r -q --ignore-unmatch -- "$d"
        echo "  删 $cnt 个文件  $d"
        n=$((n + cnt))
    done
done
echo "  共 $n 个文件"
[ "$n" = "0" ] && echo "  (没有可删的 —— 上游 TV 这套不在树里, 可能已经删过了)"

hr "settings.gradle.kts 去掉这些模块"
if [ -f settings.gradle.kts ]; then
    before=$(grep -cE 'includeProject\(":app:shared:(tv|ui-[a-z-]+-tv)"' settings.gradle.kts || true)
    if [ "$before" != "0" ]; then
        grep -vE 'includeProject\(":app:shared:(tv|ui-[a-z-]+-tv)"' settings.gradle.kts > settings.gradle.kts.tmp
        mv settings.gradle.kts.tmp settings.gradle.kts
        git add settings.gradle.kts
        echo "  去掉 $before 行 includeProject"
    else
        echo "  已经没有了"
    fi
fi

hr "剩下要人手改的引用"
left=0
# app/android/build.gradle.kts: 解冲突时应当整取 fork 版 (fork 的 tv flavor 在 formFactor 维度,
# 且 sourceSets.named("tv") { setRoot("src/izukoTv") }). 这里只检查上游那一侧有没有残留.
if grep -qE '"tvImplementation"\(projects\.app\.shared\.tv\)' app/android/build.gradle.kts 2>/dev/null; then
    echo "  app/android/build.gradle.kts 还有 tvImplementation(projects.app.shared.tv) —— 删掉这一行"
    left=$((left + 1))
fi
if grep -qE 'dimension = "distribution"' -A2 -B4 app/android/build.gradle.kts 2>/dev/null &&
   [ "$(grep -cE '^\s*create\("tv"\)' app/android/build.gradle.kts || true)" -gt 1 ]; then
    echo "  app/android/build.gradle.kts 里有两个 create(\"tv\") —— 留 formFactor 维度那个 (fork 的), 删 distribution 维度那个 (上游的)"
    left=$((left + 1))
fi
for f in $(git grep -lE 'projects\.app\.shared\.(tv|ui[A-Za-z]*Tv)|":app:shared:(tv|ui-[a-z-]+-tv)"' -- '*.kts' 2>/dev/null || true); do
    echo "  还引用着被删模块: $f"
    git grep -nE 'projects\.app\.shared\.(tv|ui[A-Za-z]*Tv)|":app:shared:(tv|ui-[a-z-]+-tv)"' -- "$f" | sed 's/^/      /'
    left=$((left + 1))
done
[ "$left" = "0" ] && echo "  没有残留"

hr "下一步"
echo "  1. 按上面的提示改完 app/android/build.gradle.kts 与 CI 脚本里的模块清单"
echo "  2. ./gradlew :app:android:tasks --offline   # 确认配置阶段过得去 (flavor 不再重名)"
echo "  3. git commit -m 'chore: 删掉上游自带的 Android TV 客户端, fork 用自己那套'"
