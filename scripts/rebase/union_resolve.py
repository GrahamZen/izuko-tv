"""冲突只是「两侧各自新增」或「两侧只动了 import」时, 自动取两边的并集.

要求 diff3 风格的冲突标记 (带公共祖先段 |||||||, fork-rebase.sh 重放时用 merge.conflictStyle=diff3). 逐个冲突块判断:

- 两侧与祖先段都只有 import 行: 按行三方合并 —— 两侧的 import 都留, 但任何一侧相对祖先删掉的行不留
  (fork 删掉某个类时顺带删了它的 import, 另一侧还留着的话并集会把它加回来, 编译就会找不到);
- 祖先段为空 (两侧都是在同一处新增): 整块拼接, 当前分支那侧在前 (rebase 时是上游), 合进来的在后 (fork);
  两块完全一样只留一份. **不按行去重**: 两侧各加一个函数时, 第二个函数的收尾 `}` 会被误当成重复删掉.
  但两侧有同一行实质代码 (不算空行、括号、注释) 就不拼: 那说明两边加的是同一个东西 (实测: 两边都加了枚举值
  MAIN_STORY、同名测试函数), 拼起来只会重复定义.

有任何一个冲突块不属于这两种, 文件原样不动, 返回 1. 只处理 .kt: 构建脚本里两侧各加的配置块往往互相矛盾
(实测上游与 fork 各加一个 create("tv")), JSON 等格式拼起来不一定合法, 都留给人.

两侧都是新建的同名文件 (add/add) 由调用方排除 (fork-rebase.sh 只处理 UU, dryrun 跳过 add/add).

用法: union_resolve.py <冲突文件>...   返回 0 = 全部解开并写回
"""
import re
import sys

HUNK = re.compile(
    r"^<{7}[^\n]*\n(.*?)^\|{7}[^\n]*\n(.*?)^={7}\n(.*?)^>{7}[^\n]*\n",
    re.S | re.M,
)


def _lines(block):
    return block.splitlines(keepends=True)


def _is_imports(block):
    return all(not l.strip() or l.startswith("import ") for l in block.splitlines())


def _significant(block):
    """块里的实质代码行: 去掉空行、只有括号标点的行、注释行."""
    out = set()
    for l in block.splitlines():
        s = l.strip()
        if len(s) < 4 or s.startswith(("*", "//", "/*")) or not any(ch.isalnum() for ch in s):
            continue
        out.add(s)
    return out


def resolve_hunk(ours, base, theirs):
    """返回合并后的文本; 不属于可自动处理的形态时返回 None."""
    if _is_imports(ours) and _is_imports(base) and _is_imports(theirs):
        o, b, t = _lines(ours), _lines(base), _lines(theirs)
        dropped = {l for l in b if l not in o or l not in t}
        merged = []
        for l in o + t:
            if l.strip() and l not in dropped and l not in merged:
                merged.append(l)
        return "".join(merged)
    if not base.strip():
        if ours == theirs:
            return ours
        if _significant(ours) & _significant(theirs):
            return None
        sep = "" if not ours or ours.endswith("\n") else "\n"
        return ours + sep + theirs
    return None


def resolve_text(text):
    out, pos = [], 0
    found = False
    for m in HUNK.finditer(text):
        found = True
        merged = resolve_hunk(m.group(1), m.group(2), m.group(3))
        if merged is None:
            return None
        out.append(text[pos:m.start()])
        out.append(merged)
        pos = m.end()
    if not found:
        return None
    out.append(text[pos:])
    result = "".join(out)
    # 还剩冲突标记 (比如不是 diff3 风格) 就不算解开
    if re.search(r"^(<{7}|>{7})( |$)", result, re.M):
        return None
    return result


def resolve_raw(raw):
    """工作区里的冲突文件: 开了 core.autocrlf 时是 CRLF, 按 LF 解, 结果还原成 CRLF."""
    if "\r\n" not in raw:
        return resolve_text(raw)
    result = resolve_text(raw.replace("\r\n", "\n"))
    return None if result is None else result.replace("\n", "\r\n")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ok = True
    for path in sys.argv[1:]:
        if not path.endswith(".kt"):
            ok = False
            continue
        text = open(path, encoding="utf-8", newline="").read()
        result = resolve_raw(text)
        if result is None:
            ok = False
            continue
        open(path, "w", encoding="utf-8", newline="").write(result)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
