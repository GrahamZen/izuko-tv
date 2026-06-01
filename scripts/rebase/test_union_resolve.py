"""union_resolve.py 的单测. 运行: uv run --no-project python scripts/rebase/test_union_resolve.py"""
import importlib.util
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
spec = importlib.util.spec_from_file_location(
    "ur", os.path.join(os.path.dirname(os.path.abspath(__file__)), "union_resolve.py"),
)
ur = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ur)


def conflict(ours, base, theirs):
    return f"<<<<<<< HEAD\n{ours}||||||| base\n{base}=======\n{theirs}>>>>>>> fork\n"


def check(name, cond):
    assert cond, name
    print("ok", name)


# 1. 两侧各加了 import: 都留
text = "package a\n\n" + conflict("import a.B\nimport a.Up\n", "import a.B\n", "import a.B\nimport a.Fork\n") + "\nfun f() {}\n"
r = ur.resolve_text(text)
check("import 并集", r is not None and "import a.Up\n" in r and "import a.Fork\n" in r and r.count("import a.B\n") == 1)

# 2. fork 删掉的 import (类被删了) 不能被上游那侧加回来
text = conflict("import a.Old\nimport a.Up\n", "import a.Old\n", "import a.Fork\n")
r = ur.resolve_text(text)
check("一侧删掉的 import 不留", r is not None and "import a.Old" not in r and "import a.Up" in r and "import a.Fork" in r)

# 3. 两侧在同一处各加一个函数: 整块拼接, 两个收尾 } 都在
up = "fun up() {\n    x()\n}\n"
fork = "fun fork() {\n    y()\n}\n"
r = ur.resolve_text("class C {\n" + conflict(up, "", fork) + "}\n")
check("纯新增整块拼接", r == "class C {\n" + up + fork + "}\n")
check("收尾括号没被去重", r.count("}\n") == 3)

# 4. 两侧加的一模一样: 只留一份
r = ur.resolve_text(conflict("val a = 1\n", "", "val a = 1\n"))
check("相同新增只留一份", r == "val a = 1\n")

# 4b. 两边加的是同一个东西 (同一行实质代码): 不拼
enum_up = "    MAIN_STORY,\n    COMPILATION,\n"
enum_fork = "    MAIN_STORY,\n    SUMMARY,\n"
check("两边加了同一个枚举值不拼", ur.resolve_text(conflict(enum_up, "", enum_fork)) is None)
check("只有括号相同不算同一个东西", ur.resolve_text(conflict("fun a() {\n}\n", "", "fun b() {\n}\n")) is not None)

# 5. 真改到了同一段: 不动
check("修改冲突不处理", ur.resolve_text(conflict("val a = 2\n", "val a = 1\n", "val a = 3\n")) is None)

# 6. 一个文件里只要有一个块不能处理, 整个文件不动
text = conflict("import a.X\n", "", "import a.Y\n") + "\n" + conflict("val a = 2\n", "val a = 1\n", "val a = 3\n")
check("有一个块不行就整体放弃", ur.resolve_text(text) is None)

# 7. 不是 diff3 风格 (没有祖先段): 不处理
check("非 diff3 不处理", ur.resolve_text("<<<<<<< HEAD\na\n=======\nb\n>>>>>>> fork\n") is None)

# 8. 工作区开了 core.autocrlf, 冲突文件是 CRLF: 照样解开, 写回仍是 CRLF
r = ur.resolve_raw(("class C {\n" + conflict(up, "", fork) + "}\n").replace("\n", "\r\n"))
check("CRLF 冲突文件", r == ("class C {\n" + up + fork + "}\n").replace("\n", "\r\n"))

print("全部通过")
