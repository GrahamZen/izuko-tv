"""逐个文件判断一组提交能不能一起挪到更早的位置 (紧跟在第 T 条之后), 给 regroup 计划的 SPLITS 用.

对每个文件 p 按 regroup 的重放顺序模拟: 从第 T 条的 p 出发, 组里改过 p 的成员按原序三方合并上去 (冲突的成员这个文件留在原处);
再按原序重放留在原处的提交 (不在组里的、以及组里这个文件没挪的), 都干净且最后与原历史同一位置的 p 相同才算能挪.
成员逐个试: 加上它整条链还对得上才留下, 一个成员挪不动不连累同文件的其他成员. 三方合并冲突时与 regroup 一样退到按行重建
(linehistory.py). 整条提交的文件都能挪才可以整条并进组里, 否则只挪能挪的文件.

  uv run --no-project python scripts/history/movable.py <上游基点> --to 36 60 51 58 [--tip main] [--only-shared] [--json out.json]

--only-shared: 只挪第 T 条也改过的文件 (挪过去才省得掉「同一文件被两条提交改」); 其余文件按留在原处算.
--no-lines: 不用按行重建 (只看三方合并); --fuzzy: 再加补丁式模糊合并.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True  # 同目录的模块别在仓库里留 __pycache__
import fuzzy
from linehistory import LineHistory

sys.stdout.reconfigure(encoding="utf-8")


def git(*a):
    return subprocess.run(["git", *a], capture_output=True, check=True).stdout


class Store:
    def __init__(self):
        self.p = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        self.cache = {}

    def get(self, commit, path):
        key = f"{commit}:{path}"
        if key not in self.cache:
            self.p.stdin.write(key.encode("utf-8") + b"\n")
            self.p.stdin.flush()
            hdr = self.p.stdout.readline().split()
            if len(hdr) < 3 or hdr[1] != b"blob":
                self.cache[key] = None
            else:
                data = self.p.stdout.read(int(hdr[2]))
                self.p.stdout.read(1)
                self.cache[key] = data
        return self.cache[key]


TMP = tempfile.mkdtemp(prefix="movable-")
FUZZY = False
LINES = True


def merge3(base, ours, theirs):
    """三方合并 (None = 文件不存在). 返回 (结果, 是否干净)."""
    if ours == theirs:
        return ours, True
    if base == ours:
        return theirs, True
    if base == theirs:
        return ours, True
    if base is None or ours is None or theirs is None:
        return None, False
    files = []
    for name, data in (("o", ours), ("b", base), ("t", theirs)):
        f = os.path.join(TMP, name)
        open(f, "wb").write(data)
        files.append(f)
    r = subprocess.run(["git", "merge-file", "-p", *files], capture_output=True)
    if r.returncode != 0 and FUZZY:
        res = fuzzy.apply(base, ours, theirs)
        if res is not None:
            return res, True
    return r.stdout, r.returncode == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("members", nargs="+", type=int)
    ap.add_argument("--to", type=int, required=True)
    ap.add_argument("--tip", default="refs/heads/main")
    ap.add_argument("--only-shared", action="store_true")
    ap.add_argument("--json")
    ap.add_argument("--fuzzy", action="store_true")
    ap.add_argument("--no-lines", action="store_true")
    a = ap.parse_args()
    global FUZZY, LINES
    FUZZY, LINES = a.fuzzy, not a.no_lines
    cs = git("rev-list", "--reverse", f"{a.base}..{a.tip}").decode().split()
    C = {i: c for i, c in enumerate(cs, 1)}
    changed = {}

    def paths_of(i):
        if i not in changed:
            changed[i] = set(p for p in git("diff-tree", "--no-commit-id", "-r", "--name-only", "--no-renames", "-z", C[i]).decode("utf-8").split("\0") if p)
        return changed[i]

    st = Store()
    T = a.to
    members = sorted(set(a.members) - {T})
    last = max(members)
    allpaths = set().union(*(paths_of(x) for x in members))
    movable = {x: [] for x in members}
    blocked = {x: {} for x in members}
    for p in sorted(allpaths):
        touch = [k for k in range(T + 1, last + 1) if p in paths_of(k)]
        cand = [x for x in members if p in paths_of(x) and (not a.only_shared or p in paths_of(T))]
        final = st.get(C[touch[-1]], p)
        history = []

        def step(k, cur, done):
            """第 k 条对 p 的改动放到 cur 上; 三方合并冲突时与 regroup 一样退到按行重建."""
            res, clean = merge3(st.get(C[k - 1], p), cur, st.get(C[k], p))
            if clean or not LINES:
                return res, clean
            if not history:
                try:
                    history.append(LineHistory(st.get(C[T], p), [(j, st.get(C[j], p)) for j in touch]))
                except ValueError:
                    history.append(None)
            data = history[0].content(done | {k}) if history[0] else False
            return (data, True) if data is not False else (None, False)

        def simulate(sel):
            """sel 里的成员挪到第 T 条之后, 其余照原序重放; 返回 (成不成, 卡在哪条)."""
            cur, done = st.get(C[T], p), set()
            for x in sel:
                cur, clean = step(x, cur, done)
                if not clean:
                    return False, T
                done.add(x)
            for k in touch:
                if k in sel:
                    continue
                cur, clean = step(k, cur, done)
                if not clean:
                    return False, k
                done.add(k)
            return (True, None) if cur == final else (False, touch[-1])

        # 逐个试: 加上它整条链还对得上才留下, 一个成员挪不动不连累同文件的其他成员
        moved = []
        for x in cand:
            ok, culprit = simulate(moved + [x])
            if ok:
                moved.append(x)
                movable[x].append(p)
            else:
                blocked[x][p] = culprit
        for x in members:
            if p in paths_of(x) and x not in cand:
                blocked[x][p] = "not-shared"
    out = {}
    for x in members:
        b = {p: k for p, k in blocked[x].items() if k != "not-shared"}
        out[x] = {"movable": movable[x], "blocked": b}
        by = {}
        for p, k in b.items():
            by.setdefault(k, []).append(p)
        whole = "整条能挪" if not blocked[x] else f"能挪 {len(movable[x])}, 挪不动 {len(b)}"
        print(f"#{x}: {whole}" + "".join(f"\n    卡在 #{k}: " + ", ".join(q.rsplit('/', 1)[-1] for q in ps[:8]) + (" …" if len(ps) > 8 else "") for k, ps in sorted(by.items(), key=lambda kv: str(kv[0]))))
    if a.json:
        json.dump(out, open(a.json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


main()
