"""review.py 的单测: 在临时仓库里造上游 / fork 两条线, 模拟改写历史、rebase、整取 fork 版、丢改动、改名等情况.
运行: uv run --no-project python scripts/review/test_review.py"""
import contextlib
import importlib.util
import io
import os
import subprocess
import sys
import tempfile
import unittest

sys.stdout.reconfigure(encoding="utf-8")
spec = importlib.util.spec_from_file_location(
    "review", os.path.join(os.path.dirname(os.path.abspath(__file__)), "review.py"),
)
rv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rv)

RULES = """base-commit ^revert: keep old module$
skip gen
keep-deleted oldtv
"""


def text(n=30, **edits):
    """n 行的文件, L5="x" 把第 5 行换成 x。"""
    lines = [f"line {i}" for i in range(1, n + 1)]
    for k, v in edits.items():
        lines[int(k[1:]) - 1] = v
    return "\n".join(lines) + "\n"


LIB = """package lib

fun loadThing(): Int {
    return 1
}

fun other(): Int = 2
"""
LIB_CHANGED_LOAD = LIB.replace("return 1", "return 2")
API = """package lib

fun fetch(): Int {
    return 1
}
"""
API_FORK = API.replace("return 1", "return 10")
USER = """package app

import lib.loadThing

fun useIt() = loadThing() + 1
"""


class Repo:
    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.d = self.tmp.name
        self.git("init", "-q", "-b", "main")
        for k, v in (("user.name", "t"), ("user.email", "t@t"), ("core.autocrlf", "false"), ("commit.gpgsign", "false")):
            self.git("config", k, v)
        self.rules = os.path.join(self.d, ".git", "review-rules.txt")
        with open(self.rules, "w", encoding="utf-8", newline="") as f:
            f.write(RULES)

    def git(self, *a):
        return subprocess.run(["git", "-C", self.d, *a], capture_output=True, check=True, text=True).stdout.strip()

    def commit(self, branch, parent, files, msg):
        """在 parent 上改 files (路径 → 内容, None 是删掉) 提交到 branch; parent 为 None 时从空树起。"""
        if parent is None:
            self.git("symbolic-ref", "HEAD", f"refs/heads/{branch}")
        else:
            self.git("checkout", "-q", "-f", "-B", branch, parent)
        for p, c in files.items():
            full = os.path.join(self.d, p)
            if c is None:
                os.remove(full)
            else:
                os.makedirs(os.path.dirname(full), exist_ok=True)
                with open(full, "w", encoding="utf-8", newline="") as f:
                    f.write(c)
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", msg)
        return self.git("rev-parse", "HEAD")

    def run(self, *argv, upstream="up"):
        buf, cwd = io.StringIO(), os.getcwd()
        os.chdir(self.d)
        try:
            with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
                code = rv.main(["--upstream", upstream, "--rules", self.rules, *argv])
        finally:
            os.chdir(cwd)
        return code, buf.getvalue()

    def ok(self, *argv):
        code, s = self.run(*argv)
        assert code == 0, s
        return s

    def queue(self, rev):
        rows = [l.split("\t") for l in self.ok("queue", "--rev", rev, "--tsv").splitlines() if l]
        return {r[1]: r[0] for r in rows}

    def ledger(self):
        return {l.split("\t")[0] for l in self.git("show", f"{rv.LEDGER}:ledger.tsv").splitlines() if not l.startswith("#")}


class ReviewTest(unittest.TestCase):
    def setUp(self):
        r = self.r = Repo()
        base = {p: text() for p in ("a.kt", "b.kt", "c.kt", "d.kt", "e.kt", "del.kt", "oldtv/x.kt", "gen/g.kt")}
        base.update({"lib/Lib.kt": LIB, "lib/Api.kt": API})
        self.u0 = r.commit("up", None, base, "upstream base")
        self.r0 = r.commit("main", self.u0, {"mod/m.kt": text(10)}, "revert: keep old module")
        self.fork_files = {
            "a.kt": text(L5="fork a"), "b.kt": text(L10="fork b"), "c.kt": text(L15="fork c"),
            "d.kt": text(L20="fork d"), "e.kt": text(L25="fork e"), "del.kt": None, "oldtv/x.kt": None,
            "f.kt": USER, "mod/m.kt": text(10, L3="fork m"), "gen/g.kt": text(L1="generated"), "lib/Api.kt": API_FORK,
        }
        self.f1 = r.commit("main", self.r0, self.fork_files, "fork work")

    def tearDown(self):
        self.r.tmp.cleanup()

    def approve_all(self, rev):
        self.r.ok("approve", "--rev", rev, "-m", "all", ".")

    def test_first_review_scope(self):
        q = self.r.queue(self.f1)
        # 栈底 revert 带进来的 mod/m.kt 算基准, 只审 fork 在它上面的改动; skip 与 keep-deleted 不进队列
        self.assertEqual(q, {"a.kt": "modified", "b.kt": "modified", "c.kt": "modified", "d.kt": "modified",
                             "e.kt": "modified", "del.kt": "deleted", "f.kt": "added", "mod/m.kt": "modified",
                             "lib/Api.kt": "modified"})
        show = self.r.ok("show", "--rev", self.f1, "mod/m.kt")
        self.assertIn("+fork m", show)
        self.assertNotIn("+line 1\n", show)

    def test_history_rewrite_needs_no_review(self):
        self.approve_all(self.f1)
        self.assertEqual(self.r.queue(self.f1), {})
        # 拆成两条、改标题: 树一样, SHA 全换
        half = {k: v for k, v in self.fork_files.items() if k in ("a.kt", "b.kt")}
        rest = {k: v for k, v in self.fork_files.items() if k not in half}
        x = self.r.commit("rewritten", self.r0, half, "part 1")
        y = self.r.commit("rewritten", x, rest, "part 2")
        self.assertNotEqual(y, self.f1)
        self.assertEqual(self.r.queue(y), {})

    def test_approve_needs_rev(self):
        code, s = self.r.run("approve", ".")
        self.assertEqual(code, 1)
        self.assertIn("--rev", s)

    def test_rebase_kinds(self):
        self.approve_all(self.f1)
        u1 = self.r.commit("up", self.u0, {
            "a.kt": text(L28="up a"),            # 离 fork 的块远: 原样重放
            "b.kt": text(L10="up b"),            # 撞上: fork 补丁跟着变
            "c.kt": text(L16="up c"),            # fork 整取自己的版本: 上游这处被丢
            "d.kt": text(L1="up d"),             # fork 的改动在 rebase 里丢了
            "e.kt": text(L25="fork e"),          # 上游收了 fork 一模一样的改动
            "del.kt": text(L1="up del"),         # fork 删掉的文件上游又改
            "oldtv/x.kt": text(L1="up tv"),      # keep-deleted
            "gen/g.kt": text(L2="up gen"),       # skip
        }, "upstream moves")
        r1 = self.r.commit("main2", u1, {"mod/m.kt": text(10)}, "revert: keep old module")
        f2 = self.r.commit("main2", r1, {
            "a.kt": text(L5="fork a", L28="up a"), "b.kt": text(L10="fork b merged"), "c.kt": text(L15="fork c"),
            "del.kt": None, "oldtv/x.kt": None, "f.kt": USER, "mod/m.kt": text(10, L3="fork m"),
            "gen/g.kt": text(L1="generated"), "lib/Api.kt": API_FORK,
        }, "fork work rebased")
        q = self.r.queue(f2)
        self.assertEqual(q, {"a.kt": "replayed", "b.kt": "remerged", "c.kt": "dropped-upstream",
                             "d.kt": "vanished", "del.kt": "dropped-upstream"})
        s = self.r.ok("show", "--rev", f2, "a.kt")
        self.assertIn("+up a", s)
        self.assertIn("(0/1", s)          # fork 的块离上游改动 23 行, 不用看
        s = self.r.ok("show", "--rev", f2, "b.kt")
        # 补丁行的差: 上次审过的 +fork b 换成了现在的 +fork b merged
        self.assertIn("-+fork b\n", s)
        self.assertIn("++fork b merged", s)
        s = self.r.ok("show", "--rev", f2, "c.kt")
        self.assertIn("+up c", s)
        self.approve_all(f2)
        self.assertEqual(self.r.queue(f2), {})
        led = self.r.ledger()
        self.assertNotIn("e.kt", led)     # 上游已吸收, 不再记
        self.assertNotIn("d.kt", led)     # 确认去掉的改动, 不再记

    def test_upstream_branch_with_revert_on_top(self):
        # fork-rebase.sh 会把 UPSTREAM 指到「上游 + 栈底 revert」的分支上
        self.r.git("branch", "base", self.r0)
        cwd = os.getcwd()
        os.chdir(self.r.d)
        try:
            ctx = rv.Ctx(self.f1, "base", rv.Rules(self.r.rules))
        finally:
            os.chdir(cwd)
        self.assertEqual(ctx.U, self.u0)
        self.assertEqual(ctx.B, self.r0)

    def test_fork_change_reviewed_against_reviewed_version(self):
        self.approve_all(self.f1)
        f2 = self.r.commit("main", self.f1, {"f.kt": USER + "\nfun second() = 2\n"}, "f second")
        f3 = self.r.commit("main", f2, {"f.kt": USER + "\nfun second() = 2\n\nfun third() = 3\n"}, "f third")
        self.assertEqual(self.r.queue(f3), {"f.kt": "fork-changed"})
        # 审的是 f2, 期间又提交了 f3: 只记 f2 的内容, f3 的改动照样回到队列
        self.r.ok("approve", "--rev", f2, "f.kt")
        self.assertEqual(self.r.queue(f2), {})
        self.assertEqual(self.r.queue(f3), {"f.kt": "fork-changed"})
        s = self.r.ok("show", "--rev", f3, "f.kt")
        self.assertIn("+fun third() = 3", s)
        self.assertNotIn("+fun second", s)

    def test_issue_lifecycle(self):
        self.r.ok("approve", "--rev", self.f1, "-m", "all but f", "a.kt", "b.kt", "c.kt", "d.kt", "e.kt", "del.kt",
                  "mod", "lib")
        self.r.ok("issue", "--rev", self.f1, "-m", "useIt overflows", "f.kt")
        self.assertEqual(self.r.queue(self.f1), {})
        self.assertIn("useIt overflows", self.r.ok("status", "--rev", self.f1))
        # 整目录 approve 不顺带关掉内容没变的文件上的问题
        self.r.ok("approve", "--rev", self.f1, ".")
        self.assertIn("useIt overflows", self.r.ok("status", "--rev", self.f1))
        f2 = self.r.commit("main", self.f1, {"f.kt": USER.replace("+ 1", "+ 2")}, "fix")
        self.assertEqual(self.r.queue(f2), {"f.kt": "fork-changed"})
        self.r.ok("approve", "--rev", f2, ".")
        self.assertNotIn("useIt overflows", self.r.ok("status", "--rev", f2))
        code, s = self.r.run("issue", "--rev", f2, "-m", "x", "lib")
        self.assertEqual(code, 1)

    def test_rename_carries_review(self):
        self.approve_all(self.f1)
        f2 = self.r.commit("main", self.f1, {"f.kt": None, "sub/f2.kt": USER.replace("+ 1", "+ 3")}, "move")
        self.assertEqual(self.r.queue(f2), {"sub/f2.kt": "fork-changed"})
        s = self.r.ok("show", "--rev", f2, "sub/f2.kt")
        self.assertIn("+fun useIt() = loadThing() + 3", s)
        self.assertIn("f.kt", s)
        self.approve_all(f2)
        self.assertEqual(self.r.queue(f2), {})
        self.assertNotIn("f.kt", self.r.ledger())
        # 原样改名: 什么都不用审, 下次写台账时换成新路径
        f3 = self.r.commit("main", f2, {"sub/f2.kt": None, "g.kt": USER.replace("+ 1", "+ 3")}, "move again")
        self.assertEqual(self.r.queue(f3), {})
        f4 = self.r.commit("main", f3, {"a.kt": text(L5="fork a2")}, "touch a")
        self.approve_all(f4)
        self.assertIn("g.kt", self.r.ledger())
        self.assertNotIn("sub/f2.kt", self.r.ledger())

    def test_upstream_interaction(self):
        self.approve_all(self.f1)
        u1 = self.r.commit("up", self.u0, {"lib/Lib.kt": LIB_CHANGED_LOAD}, "change loadThing")
        u2 = self.r.commit("up", u1, {"lib/Lib.kt": LIB_CHANGED_LOAD.replace("= 2", "= 3")}, "change other")
        u3 = self.r.commit("up", u2, {"lib/New.kt": "package lib\n\nfun caller() = fetch() + 1\n"}, "new caller")
        r1 = self.r.commit("main2", u3, {"mod/m.kt": text(10)}, "revert: keep old module")
        f2 = self.r.commit("main2", r1, {k: v for k, v in self.fork_files.items()}, "fork work rebased")
        self.assertEqual(self.r.queue(f2), {})
        s = self.r.ok("upstream", "--rev", f2)
        self.assertIn("3 条", s)
        parts = s.split("\n\n")
        load = next(p for p in parts if "change loadThing" in p)
        self.assertIn("loadThing ← f.kt", load)
        other = next(p for p in parts if "change other" in p)
        self.assertIn("fork 代码没连着", other)
        caller = next(p for p in parts if "new caller" in p)
        self.assertIn("fork 改过的 Api.kt: fetch", caller)
        self.r.ok("upstream", "--approve", "--rev", f2, "-m", "looked")
        self.assertIn("0 条", self.r.ok("upstream", "--rev", f2))


class GlobTest(unittest.TestCase):
    def test_patterns(self):
        m = lambda pat, p: rv.glob_re(pat).fullmatch(p) is not None
        self.assertTrue(m("app/x", "app/x/y.kt"))
        self.assertTrue(m("app/x/", "app/x"))
        self.assertFalse(m("app/x", "app/xy/z.kt"))
        self.assertTrue(m("**/*.png", "a.png"))
        self.assertTrue(m("**/*.png", "a/b/c.png"))
        self.assertTrue(m("app/shared/*/tv", "app/shared/ui-subject/tv/build.gradle.kts"))
        self.assertFalse(m("app/shared/*/tv", "app/shared/a/b/tv/x"))
        self.assertTrue(m("app/shared/*/src/androidTv*", "app/shared/ui/src/androidTvTest/kotlin/A.kt"))
        self.assertTrue(m("**/src/*Test/resources", "app/x/src/desktopTest/resources/a.json"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
