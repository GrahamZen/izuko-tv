#!/usr/bin/env bash
# 用 .github/workflows/src.main.kts 重新生成 build.yml 与 release.yml.
# 这两个文件只改 src.main.kts 再生成, 不手改: CI 的 consistency-check 会重新生成一遍对拍,
# 跟进上游时它们的冲突也靠重新生成解 (scripts/fork-rebase.sh).
#
#   ./scripts/gen-workflows.sh           # 重新生成
#   ./scripts/gen-workflows.sh --check   # 生成后与 HEAD 对拍, 不一致时退出码 1 (生成的结果留在工作区)
#
# 本机没有 kotlin 时下载与 gradle/libs.versions.toml 同版本的 Kotlin 编译器到 ~/.cache/kotlinc (只下一次).
# 第一次运行要从 Maven Central 与 bindings.krzeminski.it 解析脚本依赖, 之后走 ~/.m2 的缓存.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

if command -v kotlin >/dev/null 2>&1; then
    KOTLIN=(kotlin)
else
    ver=$(sed -n 's/^kotlin = "\([^"]*\)".*/\1/p' gradle/libs.versions.toml)
    [ -n "$ver" ] || { echo "gradle/libs.versions.toml 里找不到 kotlin 版本" >&2; exit 1; }
    dir="${XDG_CACHE_HOME:-$HOME/.cache}/kotlinc/$ver"
    if [ ! -f "$dir/kotlinc/bin/kotlin" ]; then
        echo "下载 Kotlin $ver 编译器到 $dir" >&2
        mkdir -p "$dir"
        curl -fL --retry 3 -o "$dir/kotlin-compiler.zip" \
            "https://github.com/JetBrains/kotlin/releases/download/v$ver/kotlin-compiler-$ver.zip"
        unzip -q -o "$dir/kotlin-compiler.zip" -d "$dir"
        rm -f "$dir/kotlin-compiler.zip"
    fi
    KOTLIN=(bash "$dir/kotlinc/bin/kotlin")
fi

# 生成器从脚本所在目录往上找 .git 目录当仓库根, 输出写到根下的 .github/workflows/;
# linked worktree 里 .git 是文件, 找不到. 所以拷到一个只有空 .git 目录的临时目录里生成, 再把结果拷回来.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/.git" "$WORK/.github/workflows"
cp .github/workflows/src.main.kts "$WORK/.github/workflows/"
(
    cd "$WORK"
    # 脚本依赖偶尔解析失败, 失败会在本地仓库留下 "absent" 标记, 重试前要清掉 (同 consistency-check)
    for attempt in 1 2 3; do
        if "${KOTLIN[@]}" .github/workflows/src.main.kts; then exit 0; fi
        [ "$attempt" = 3 ] && exit 1
        echo "生成失败 (第 $attempt 次), 清掉依赖缓存重试" >&2
        rm -rf "$HOME/.m2/repository/io/github/typesafegithub"
    done
)
cp "$WORK/.github/workflows/build.yml" "$WORK/.github/workflows/release.yml" .github/workflows/

if [ "${1:-}" = "--check" ]; then
    if ! git diff --quiet HEAD -- .github/workflows/build.yml .github/workflows/release.yml; then
        git diff --stat HEAD -- .github/workflows/build.yml .github/workflows/release.yml
        echo "build.yml / release.yml 与 src.main.kts 生成的不一致" >&2
        exit 1
    fi
    echo "build.yml / release.yml 与 src.main.kts 一致"
fi
