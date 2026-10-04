"""按内容判断每条 fork 提交测了多久: 它新增的行有多少已经在某个已发布的 tag 里.

提交在栈里的位置与提交时间都不可靠 (整理时新改动常被并进很早的提交, 改写会重置提交时间), 所以拿内容对 tag:
取每条提交新增的非空行 (去首尾空白, 不短于 12 个字符), 在 tag 的同名文件里找得到的比例. 接近 1 = 那个 tag 已经带着它.

默认列出发布满 --days 天的最新一个 release 及其后的 release (发布时间从 gh 取), 也可以用 --tags 直接指定.

  uv run --no-project python scripts/history/tested.py <上游基点> [--tip main] [--days 7] [--tags v1.0.5,v1.0.6-alpha01]
"""
import argparse
import datetime
import json
import subprocess
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")


def git(*a):
    return subprocess.run(["git", *a], capture_output=True, check=True).stdout


class Cat:
    def __init__(self):
        self.p = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        self.cache = {}

    def lines(self, spec):
        if spec not in self.cache:
            self.p.stdin.write(spec.encode("utf-8") + b"\n")
            self.p.stdin.flush()
            hdr = self.p.stdout.readline().split()
            if len(hdr) < 3 or hdr[1] != b"blob":
                self.cache[spec] = None
            else:
                data = self.p.stdout.read(int(hdr[2]))
                self.p.stdout.read(1)
                self.cache[spec] = set(l.strip() for l in data.decode("utf-8", "replace").splitlines())
        return self.cache[spec]


def releases(days):
    """[(tag, 发布时间)] 按时间升序: 发布满 days 天的最新一个及其后的."""
    out = subprocess.run(["gh", "release", "list", "-L", "50", "--json", "tagName,publishedAt,isDraft"],
                         capture_output=True, check=True).stdout
    rs = sorted((r["publishedAt"], r["tagName"]) for r in json.loads(out) if not r["isDraft"] and r["publishedAt"]
                and r["tagName"].startswith("v"))
    now = datetime.datetime.now(datetime.timezone.utc)
    old = [i for i, (t, _) in enumerate(rs) if now - datetime.datetime.fromisoformat(t.replace("Z", "+00:00")) >= datetime.timedelta(days=days)]
    first = old[-1] if old else 0
    return [(tag, t) for t, tag in rs[first:]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("--tip", default="refs/heads/main")
    ap.add_argument("--days", type=float, default=7)
    ap.add_argument("--tags")
    a = ap.parse_args()
    if a.tags:
        tags = [(t, "") for t in a.tags.split(",")]
    else:
        tags = releases(a.days)
        print(f"满 {a.days:g} 天的最新 release: {tags[0][0]} ({tags[0][1]}); 之后的: " + ", ".join(f"{t} ({p})" for t, p in tags[1:]))
    names = [t for t, _ in tags]
    cat = Cat()
    commits = git("rev-list", "--reverse", f"{a.base}..{a.tip}").decode().split()
    print("idx\tsha\tadded\t" + "\t".join(names) + "\tsubject")
    for i, c in enumerate(commits, 1):
        diff = git("diff-tree", "-p", "--no-renames", "-U0", "--no-color", f"{c}^", c).decode("utf-8", "replace")
        added, path = defaultdict(list), None
        for line in diff.splitlines():
            if line.startswith("+++ "):
                path = None if line[4:] == "/dev/null" else line[6:]
            elif line.startswith("+") and path:
                t = line[1:].strip()
                if len(t) >= 12:
                    added[path].append(t)
        total = sum(len(v) for v in added.values())
        fr = []
        for tag in names:
            hit = sum(sum(1 for l in ls if l in s) for p, ls in added.items() if (s := cat.lines(f"{tag}:{p}")))
            fr.append(f"{hit / total:.2f}" if total else "-")
        subj = git("log", "-1", "--format=%s", c).decode("utf-8").strip()
        print(f"{i}\t{c[:10]}\t{total}\t" + "\t".join(fr) + f"\t{subj[:60]}", flush=True)


main()
