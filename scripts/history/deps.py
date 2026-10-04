"""往前挪提交之前查语义上的依赖: movable.py 只看文本合不合得上, 挪过去的代码可能用到中间某条才加的类或函数.

挪的提交新增行里的名字, 在它原位置的父提交树里有、在目标位置的树里没有 = 依赖目标与原位置之间的某条提交.
一次挪几组时按给的顺序算, 先挪过去的提交新增的名字算已有. 只看 .kt / .kts / .java;
注释里的词、平台或依赖库的 API (树里没人用过) 也会报出来, 逐个看新增行再定.

  uv run --no-project python scripts/history/deps.py <上游基点> <目标序号>:<序号,…> [<目标序号>:<序号,…> …] [--tip main]

例: deps.py 4b9a7a91da 35:66,103,146 59:161,248  (挪到第 35 条之后的三条、第 59 条之后的两条)
"""
import argparse
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")
TOK = re.compile(rb"[A-Za-z_][A-Za-z0-9_]{3,}")
SOURCES = (b".kt", b".kts", b".java")


class Tokens:
    def __init__(self):
        self.p = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        self.blobs = {}
        self.trees = {}

    def blob(self, sha):
        if sha not in self.blobs:
            self.p.stdin.write(sha + b"\n")
            self.p.stdin.flush()
            hdr = self.p.stdout.readline().split()
            data = self.p.stdout.read(int(hdr[2]))
            self.p.stdout.read(1)
            self.blobs[sha] = set(TOK.findall(data))
        return self.blobs[sha]

    def tree(self, commit):
        if commit not in self.trees:
            s = set()
            for e in subprocess.run(["git", "ls-tree", "-r", "-z", commit], capture_output=True).stdout.split(b"\0"):
                if not e:
                    continue
                meta, path = e.split(b"\t", 1)
                if path.endswith(SOURCES):
                    s |= self.blob(meta.split()[2])
            self.trees[commit] = s
        return self.trees[commit]


def added_tokens(commit):
    d = subprocess.run(["git", "show", "--format=", "-U0", commit, "--", "*.kt", "*.kts", "*.java"], capture_output=True).stdout
    return set(t for ln in d.split(b"\n") if ln.startswith(b"+") and not ln.startswith(b"+++") for t in TOK.findall(ln[1:]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("moves", nargs="+")
    ap.add_argument("--tip", default="refs/heads/main")
    a = ap.parse_args()
    order = subprocess.run(["git", "rev-list", "--reverse", f"{a.base}..{a.tip}"], capture_output=True, text=True).stdout.split()
    tk = Tokens()
    carried = set()
    for spec in a.moves:
        t, ids = spec.split(":")
        target = tk.tree(order[int(t) - 1])
        for i in map(int, ids.split(",")):
            c = order[i - 1]
            added = added_tokens(c)
            missing = sorted(x.decode() for x in (added & tk.tree(c + "^")) - target - carried)
            carried |= added
            shown = ", ".join(missing[:15]) + (" …" if len(missing) > 15 else "")
            print(f"#{i} -> 第 {t} 条之后: " + (shown or "OK"))


if __name__ == "__main__":
    main()
