"""merge-android-strings.py 的单测. 运行: uv run --no-project python scripts/rebase/test_merge_android_strings.py"""
import importlib.util
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")

spec = importlib.util.spec_from_file_location(
    "mas", os.path.join(os.path.dirname(os.path.abspath(__file__)), "merge-android-strings.py"),
)
mas = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mas)


def xml(*lines):
    return "\n".join(['<?xml version="1.0" encoding="utf-8"?>', "<resources>", *lines, "</resources>", ""])


def s(k, v):
    return f'    <string name="{k}">{v}</string>'


def keys(text):
    return [mas.key_of(l) for l in text.split("\n") if mas.key_of(l)]


def check(name, cond):
    assert cond, name
    print("ok", name)


base = xml(s("a", "A"), s("b", "B"), s("c", "C"))

# 1. 两边改相邻的 key: 按行必冲突, 按 key 互不相干
merged, both = mas.merge(base, xml(s("a", "A2"), s("b", "B"), s("c", "C")), xml(s("a", "A"), s("b", "B2"), s("c", "C")))
check("相邻修改都保留", s("a", "A2") in merged and s("b", "B2") in merged and not both)

# 2. 两边各自新增, 位置挨着
merged, both = mas.merge(
    base,
    xml(s("a", "A"), s("b", "B"), s("c", "C"), s("up_new", "U")),
    xml(s("a", "A"), s("b", "B"), s("c", "C"), s("fork_new", "F")),
)
check("两边新增都在且只出现一次", keys(merged).count("up_new") == 1 and keys(merged).count("fork_new") == 1)

# 3. 同一个 key 两边都改了: 取合进来那一侧 (fork), 并报告
merged, both = mas.merge(base, xml(s("a", "上游改"), s("b", "B"), s("c", "C")), xml(s("a", "fork改"), s("b", "B"), s("c", "C")))
check("两边都改取 fork", s("a", "fork改") in merged and both == ["a"])

# 4. 删除: 一边删、另一边没动 → 删掉; 一边删、另一边改了 → 保留改的
merged, _ = mas.merge(base, xml(s("a", "A"), s("c", "C")), xml(s("a", "A"), s("b", "B"), s("c", "C")))
check("上游删了、fork 没动 → 删", "b" not in keys(merged))
merged, _ = mas.merge(base, xml(s("a", "A"), s("b", "B"), s("c", "C")), xml(s("a", "A"), s("c", "C")))
check("fork 删了、上游没动 → 删", "b" not in keys(merged))
merged, _ = mas.merge(base, xml(s("a", "A"), s("b", "B改"), s("c", "C")), xml(s("a", "A"), s("c", "C")))
check("fork 删了、上游改了 → 保留上游改的", s("b", "B改") in merged)

# 5. fork 新增带注释的 key: 注释跟在它前一个 key 后面、紧挨着新 key
merged, _ = mas.merge(base, base, xml(s("a", "A"), "    <!-- 说明 -->", s("x", "X"), s("b", "B"), s("c", "C")))
lines = merged.split("\n")
i = lines.index("    <!-- 说明 -->")
check("注释与新 key 相邻且在 a 之后", lines[i + 1] == s("x", "X") and lines[i - 1] == s("a", "A"))

# 6. 结果的 key 顺序以上游为骨架, 行数守恒
merged, _ = mas.merge(base, xml(s("c", "C"), s("a", "A"), s("b", "B")), base)
check("以上游行序为骨架", keys(merged) == ["c", "a", "b"])
check("收尾保留换行", merged.endswith("</resources>\n"))

# 7. 跨行的 <string>: 按行认 key 会拆坏, 不合并 (驱动退回按行合并)
multi = xml('    <string name="m">第一行', "第二行</string>", s("a", "A"))
try:
    mas.merge(multi, multi, multi)
    check("跨行文案不硬合", False)
except ValueError:
    check("跨行文案不硬合", True)
check("自闭合的 <string/> 照常", keys(mas.merge(base, xml(s("a", "A"), '    <string name="e"/>'), base)[0]) == ["a", "e"])

print("全部通过")
