"""逐个文件判断一组提交能不能一起挪到更早的位置 (紧跟在第 T 条之后), 给 regroup 计划的 SPLITS 用.

对每个文件 p 按 regroup 的重放顺序模拟: 从第 T 条的 p 出发, 组里改过 p 的成员按原序三方合并上去 (冲突的成员这个文件留在原处);
再按原序重放留在原处的提交 (不在组里的、以及组里这个文件没挪的), 都干净且最后与原历史同一位置的 p 相同才算能挪;
重放冲突时这个文件整个不挪 (保守). 整条提交的文件都能挪才可以整条并进组里, 否则只挪能挪的文件.

  uv run --no-project python scripts/history/movable.py <上游基点> --to 36 60 51 58 [--tip main] [--only-shared] [--json out.json]

--only-shared: 只挪第 T 条也改过的文件 (挪过去才省得掉「同一文件被两条提交改」); 其余文件按留在原处算.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

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
    return r.stdout, r.returncode == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("members", nargs="+", type=int)
    ap.add_argument("--to", type=int, required=True)
    ap.add_argument("--tip", default="refs/heads/main")
    ap.add_argument("--only-shared", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args()
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
        cur = st.get(C[T], p)
        moved = []
        why = {}
        for x in cand:
            res, clean = merge3(st.get(C[x - 1], p), cur, st.get(C[x], p))
            if clean:
                cur = res
                moved.append(x)
            else:
                why[x] = T
        stay = [k for k in touch if k not in moved]
        ok = True
        for k in stay:
            cur, clean = merge3(st.get(C[k - 1], p), cur, st.get(C[k], p))
            if not clean:
                ok, culprit = False, k
                break
        if ok and cur != st.get(C[touch[-1]], p):
            ok, culprit = False, touch[-1]
        for x in cand:
            if ok and x in moved:
                movable[x].append(p)
            else:
                blocked[x][p] = why.get(x, culprit if not ok else T)
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
