"""一条提交删改的行是谁写的: 在父提交上 blame, 按「上游」/ 第几条 fork 提交计行数; 纯新增看插入点前后各一行.

两个用处:
- 判「修上游代码」: 删改的行多为上游写的, 追上游时上游可能也修了同一处, 整条单独留着 (能挪到上游合并点上的放 A 层, 见 movable.py).
- 判一条修复该并回哪条: 它改的行多数是哪条提交写的.

  uv run --no-project python scripts/history/owners.py <上游基点> [序号…] [--from N] [--tip main]

不给序号时从第 --from 条看到链尾. 输出: 序号  上游文件数  上游写的行  fork 写的行  前三名  标题.
文案 (strings*.xml) 与发版说明模板不算; 改动超过 300 个文件的 (删上游代码、整体迁移) 不 blame.
"""
import argparse
import re
import subprocess
import sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")
SKIP = re.compile(r"/strings[^/]*\.xml$|ci-helper/release-template\.md$")


def git(*a):
    return subprocess.run(["git", *a], capture_output=True).stdout.decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("idx", nargs="*", type=int)
    ap.add_argument("--from", dest="start", type=int, default=1)
    ap.add_argument("--tip", default="refs/heads/main")
    a = ap.parse_args()
    order = git("rev-list", "--reverse", f"{a.base}..{a.tip}").split()
    pos = {c: i for i, c in enumerate(order, 1)}
    upstream_commits = set(git("rev-list", a.base).split())
    upstream_files = set(p for p in git("ls-tree", "-r", "-z", "--name-only", a.base).split("\0") if p)
    for i in a.idx or range(a.start, len(order) + 1):
        sha = order[i - 1]
        subj = git("log", "-1", "--format=%s", sha).strip()
        files = [p for p in git("diff-tree", "--no-commit-id", "-r", "--name-only", "--no-renames", "-z", sha).split("\0") if p]
        n_up = sum(p in upstream_files for p in files)
        if len(files) > 300:
            print(f"{i}\t{n_up}\t-\t-\t({len(files)} 个文件, 不 blame)\t{subj[:70]}")
            continue
        cnt = Counter()
        f = None
        for ln in git("show", "-U0", "--no-renames", "--format=", sha).splitlines():
            if ln.startswith("--- a/"):
                f = ln[6:]
            elif ln.startswith("--- /dev/null"):
                f = None
            m = re.match(r"^@@ -(\d+)(?:,(\d+))?", ln)
            if m and f and not SKIP.search(f):
                s, n = int(m.group(1)), int(m.group(2) or 1)
                lo, k = (max(s, 1), 2) if n == 0 else (s, n)
                for b in git("blame", "-s", "-l", "-L", f"{lo},+{k}", f"{sha}^", "--", f).splitlines():
                    c = b.split()[0].lstrip("^")
                    if c in upstream_commits:
                        cnt["上游"] += 1
                    else:
                        cnt[f"#{pos.get(c, '?')}"] += 1
        up = cnt["上游"]
        fork = sum(v for k, v in cnt.items() if k != "上游")
        top = ", ".join(f"{k}×{v}" for k, v in cnt.most_common(3))
        print(f"{i}\t{n_up}\t{up}\t{fork}\t{top}\t{subj[:70]}")


if __name__ == "__main__":
    main()
