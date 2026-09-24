"""Android strings.xml 的合并驱动: 按 key 做三方合并, 不按行.

跟进上游时 strings.xml 的冲突几乎都是「两边在相邻的行上各改各的 key」, 按行合并必然冲突, 按 key 看其实互不相干.
规则 (b = 共同祖先, o = 当前分支, t = 合进来的那一侧; rebase 时 o 是上游, t 是正在重放的 fork 提交):

- o 与 t 一样 → 取它; 只有一侧相对 b 改了 (含新增、删除) → 取改了的那一侧;
- 两侧都改了同一个 key 且不一样 → 取 t (fork 的措辞); 一侧删了、另一侧改了 → 留改过的那条. 这两种都在 stderr 打出来,
  事后在 range-diff 里核对.

输出以 o 的行序为骨架; t 新增的 key 与注释插在它在 t 里的前一个 key 后面. 解析失败时退回 git merge-file (留冲突标记).

git 以 `merge.android-strings.driver = <本脚本> %O %A %B %P` 调用, 结果写回 %A; 由 scripts/fork-rebase.sh 装进本地配置.
"""
import re
import subprocess
import sys

KEY = re.compile(r'^\s*<string\s+name="([^"]+)"')


def key_of(line):
    m = KEY.match(line)
    return m.group(1) if m else None


def keyed(lines):
    out = {}
    for line in lines:
        k = key_of(line)
        if k is not None:
            if k in out:
                raise ValueError(f"重复的 key: {k}")
            # 按行认 key, 跨行的 <string> 会被拆开; 交给按行合并
            if "</string>" not in line and not line.rstrip().endswith("/>"):
                raise ValueError(f"跨行的 <string>: {k}")
            out[k] = line
    return out


def merge(base_text, ours_text, theirs_text):
    """返回 (合并结果, 两侧都改了而取了 theirs 的 key 列表)."""
    base, ours, theirs = (t.split("\n") for t in (base_text, ours_text, theirs_text))
    b, o, t = keyed(base), keyed(ours), keyed(theirs)
    resolved, both_changed = {}, []
    for k in set(b) | set(o) | set(t):
        bk, ok, tk = b.get(k), o.get(k), t.get(k)
        if ok == tk:
            resolved[k] = ok
        elif ok == bk:
            resolved[k] = tk
        elif tk == bk:
            resolved[k] = ok
        else:
            # 一侧删了、另一侧改了: 留改过的那条 (多一条用不到的文案无害, 少一条还在用的就编译不过);
            # 两侧改成不一样的内容: 取 t
            resolved[k] = ok if tk is None else tk
            both_changed.append(k)

    # 骨架: o 的行序; key 行换成裁决结果 (None = 删掉)
    out, present = [], set()
    for line in ours:
        k = key_of(line)
        if k is None:
            out.append(line)
        elif resolved[k] is not None:
            out.append(resolved[k])
            present.add(k)

    # t 带来的新行 (新增的 key、新增的注释): 挂在它在 t 里最近的前一个「已在输出里」的 key 后面
    base_set, ours_set = set(base), set(ours)
    after = {}  # 锚点 key (None = 开头) -> 依次插入的行
    anchor = None
    for line in theirs:
        k = key_of(line)
        if k is not None:
            if k in present:
                anchor = k
            elif resolved.get(k) is not None:
                after.setdefault(anchor, []).append(resolved[k])
                present.add(k)
                anchor = k
        elif line not in ours_set and line not in base_set and line.strip():
            after.setdefault(anchor, []).append(line)

    result = []
    head_inserts = after.pop(None, [])
    for line in out:
        result.append(line)
        if head_inserts and line.strip().startswith("<resources"):
            result.extend(head_inserts)
            head_inserts = []
        k = key_of(line)
        if k is not None and k in after:
            result.extend(after.pop(k))
    if head_inserts or after:
        # 找不到落点的 (骨架里没有 <resources>, 或锚点 key 被删了): 放到 </resources> 前
        rest = head_inserts + [l for ls in after.values() for l in ls]
        idx = next((i for i in range(len(result) - 1, -1, -1) if result[i].strip() == "</resources>"), len(result))
        result[idx:idx] = rest
    return "\n".join(result), sorted(both_changed)


def main():
    # git 在 Windows 上调驱动时控制台是 cp1252, 打中文提示会抛异常 (进而误退回按行合并)
    sys.stderr.reconfigure(encoding="utf-8")
    o_path, a_path, b_path = sys.argv[1:4]
    label = sys.argv[4] if len(sys.argv) > 4 else a_path
    read = lambda p: open(p, encoding="utf-8", newline="").read()
    try:
        texts = [read(p) for p in (o_path, a_path, b_path)]
        crlf = "\r\n" in texts[1]
        merged, both = merge(*(t.replace("\r\n", "\n") for t in texts))
        if crlf:
            merged = merged.replace("\n", "\r\n")
        open(a_path, "w", encoding="utf-8", newline="").write(merged)
        for k in both:
            print(f"merge-android-strings: {label}: 两侧都改了 {k}, 按规则裁决 (见文件头), 请在 range-diff 里核对", file=sys.stderr)
        return 0
    except Exception as e:  # noqa: BLE001 —— 任何意外都退回按行合并, 让人来看
        print(f"merge-android-strings: {label}: 按 key 合并失败 ({e}), 退回按行合并", file=sys.stderr)
        subprocess.run(["git", "merge-file", a_path, o_path, b_path])
        return 1


if __name__ == "__main__":
    sys.exit(main())
