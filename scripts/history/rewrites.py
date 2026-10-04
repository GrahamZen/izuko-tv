"""找「前面加、后面改掉」: 哪些提交删改了指定几条提交写的行 (在各自的父提交上 blame).
加了又删的并到一起能抵消 (例: 一种登录方式先加进来、后来整套换掉), 整理时用来找能并的提交.

  uv run --no-project python scripts/history/rewrites.py <上游基点> <目标序号,…> [--from N] [--path 关键词] [--tip main]

从第 --from 条 (默认最小的目标序号之后) 看到链尾. 输出: 序号  删改了哪条目标的几行  标题. 文案与发版说明模板不算.
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
    ap.add_argument("targets")
    ap.add_argument("--from", dest="start", type=int)
    ap.add_argument("--path")
    ap.add_argument("--tip", default="refs/heads/main")
    a = ap.parse_args()
    order = git("rev-list", "--reverse", f"{a.base}..{a.tip}").split()
    targets = {order[int(x) - 1]: int(x) for x in a.targets.split(",")}
    start = a.start or min(targets.values()) + 1
    for i in range(start, len(order) + 1):
        sha = order[i - 1]
        cnt = Counter()
        f = None
        for ln in git("show", "-U0", "--no-renames", "--format=", sha).splitlines():
            if ln.startswith("--- a/"):
                f = ln[6:]
            elif ln.startswith("--- /dev/null"):
                f = None
            m = re.match(r"^@@ -(\d+)(?:,(\d+))?", ln)
            if m and f and (a.path is None or a.path in f) and not SKIP.search(f):
                s, n = int(m.group(1)), int(m.group(2) or 1)
                if n == 0:
                    continue
                for b in git("blame", "-s", "-l", "-L", f"{s},+{n}", f"{sha}^", "--", f).splitlines():
                    c = b.split()[0].lstrip("^")
                    if c in targets:
                        cnt[targets[c]] += 1
        if cnt:
            subj = git("log", "-1", "--format=%s", sha).strip()
            print(f"#{i}\t" + ", ".join(f"删改 #{k} 的 {v} 行" for k, v in sorted(cnt.items())) + f"\t{subj[:70]}")


if __name__ == "__main__":
    main()
