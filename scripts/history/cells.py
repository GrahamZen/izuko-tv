"""量「同一个上游文件被几条 fork 提交改」: 追上游时每条改到上游文件的提交都要对这个文件解一遍冲突.

格子 = (提交, 上游文件) 对, 上游文件 = 上游基点的树里有的路径. 「多余格子」= 每个文件被改的次数减一之和, 整理能省的上限.

  uv run --no-project python scripts/history/cells.py <上游基点> [--tip main] [--upto N]        # 汇总 + 改得最多的文件
  ... --overlap 36,60                                                                     # 每条提交与这几条共改的上游文件数
  ... --compare <另一个链尾>                                                               # 整理前后对比
"""
import argparse
import collections
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")


def git(*a):
    return subprocess.run(["git", *a], capture_output=True, check=True).stdout


def per_commit(base, tip):
    upstream = set(p for p in git("ls-tree", "-r", "-z", "--name-only", base).decode("utf-8").split("\0") if p)
    res = []
    for c in git("rev-list", "--reverse", f"{base}..{tip}").decode().split():
        ps = [p for p in git("diff-tree", "--no-commit-id", "-r", "--name-only", "--no-renames", "-z", c).decode("utf-8").split("\0") if p]
        subj = git("log", "-1", "--format=%s", c).decode("utf-8").strip()
        res.append((c, subj, set(p for p in ps if p in upstream), set(p for p in ps if p not in upstream)))
    return res


def summary(rows, label):
    cnt = collections.Counter(p for _, _, up, _ in rows for p in up)
    print(f"{label}: {len(rows)} 条提交, 格子 {sum(cnt.values())}, 改到的上游文件 {len(cnt)}, 多余格子 {sum(n - 1 for n in cnt.values())}")
    return cnt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("--tip", default="refs/heads/main")
    ap.add_argument("--upto", type=int, help="只看前 N 条")
    ap.add_argument("--overlap", help="逗号分隔的序号")
    ap.add_argument("--compare")
    ap.add_argument("--top", type=int, default=20)
    a = ap.parse_args()
    rows = per_commit(a.base, a.tip)
    if a.upto:
        rows = rows[:a.upto]
    cnt = summary(rows, a.tip)
    if a.compare:
        summary(per_commit(a.base, a.compare), a.compare)
        return
    for p, n in cnt.most_common(a.top):
        print(f"  {n:3}  {p}")
    if a.overlap:
        refs = [int(x) for x in a.overlap.split(",")]
        print("\nidx  up  fork  多处改  " + "  ".join(f"与#{r}" for r in refs))
        for i, (c, subj, up, fork) in enumerate(rows, 1):
            shared = sum(1 for p in up if cnt[p] > 1)
            ov = "  ".join(f"{len(up & rows[r - 1][2]):5}" for r in refs)
            print(f"{i:3} {len(up):4} {len(fork):5} {shared:6}  {ov}  {subj[:56]}")


main()
