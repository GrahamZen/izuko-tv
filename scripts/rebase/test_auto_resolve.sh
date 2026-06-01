#!/usr/bin/env bash
# fork-rebase.sh 自动规则的集成测试: 在临时仓库里造出四种冲突, 真的跑一次 rebase, 让 auto_resolve 处理, 检查结果.
#   ./scripts/rebase/test_auto_resolve.sh      (在仓库根运行; 不碰本仓库)
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
cd "$T"
git init -q -b main . && git config user.email t@t && git config user.name t && git config commit.gpgsign false
# 换行配置照搬本仓库: 冲突文件在工作区里是 LF 还是 CRLF 取决于它
for k in core.autocrlf core.eol; do v="$(git -C "$ROOT" config "$k" || true)"; [ -z "$v" ] || git config "$k" "$v"; done
mkdir -p scripts/rebase && cp "$ROOT"/scripts/rebase/*.py "$ROOT"/scripts/rebase/take-fork.txt scripts/rebase/

STR=app/shared/app-lang/src/androidMain/res/values/strings.xml
TAKE=app/shared/ui-settings/src/commonMain/kotlin/ui/settings/account/ProfileGroup.kt
mkdir -p "$(dirname "$STR")" "$(dirname "$TAKE")" src
printf '<resources>\n    <string name="a">A</string>\n    <string name="b">B</string>\n</resources>\n' > "$STR"
printf 'class C {\n    fun base() {}\n}\n' > src/C.kt
printf 'val gone = 1\n' > src/Gone.kt
printf 'val profile = "base"\n' > "$TAKE"
git add -A && git commit -qm base

git checkout -qb upstream
sed -i 's/>A</>A-up</' "$STR"
printf 'class C {\n    fun base() {}\n    fun up() {\n        a()\n    }\n}\n' > src/C.kt
printf 'val gone = 2\n' > src/Gone.kt
printf 'val profile = "upstream"\n' > "$TAKE"
git commit -qam upstream

git checkout -q main && git checkout -qb fork
sed -i 's/>B</>B-fork</' "$STR"
printf 'class C {\n    fun base() {}\n    fun fork() {\n        b()\n    }\n}\n' > src/C.kt
git rm -q src/Gone.kt
printf 'val profile = "fork"\n' > "$TAKE"
git commit -qam fork

# 取出 fork-rebase.sh 里的 install_rules / auto_resolve, 装规则、重放、自动处理
source <(sed -n '/^install_rules() {/,/^}/p; /^auto_resolve() {/,/^}/p' "$ROOT/scripts/fork-rebase.sh")
install_rules
git -c merge.conflictStyle=diff3 -c merge.directoryRenames=true rebase upstream >/dev/null 2>&1 || true
auto_resolve
left=$(git diff --name-only --diff-filter=U)
[ -z "$left" ] || { echo "FAIL 还有没解开的: $left"; exit 1; }
GIT_EDITOR=true git -c merge.conflictStyle=diff3 rebase --continue >/dev/null

fail=0
expect() { if eval "$2"; then echo "ok $1"; else echo "FAIL $1"; fail=1; fi; }
expect "fork 删掉的保持删除" '[ ! -e src/Gone.kt ]'
expect "清单里的文件取 fork 版" 'grep -q "\"fork\"" "$TAKE"'
expect "两侧各加的函数都在" 'grep -q "fun up()" src/C.kt && grep -q "fun fork()" src/C.kt'
expect "括号完整 (3 个收尾)" '[ "$(grep -c "^    }\$" src/C.kt)" = 2 ] && [ "$(grep -c "^}\$" src/C.kt)" = 1 ]'
expect "文案两边的修改都在" 'grep -q ">A-up<" "$STR" && grep -q ">B-fork<" "$STR"'
expect "没有冲突标记" '! grep -rq "^<<<<<<<" src "$STR" "$TAKE"'
exit $fail
