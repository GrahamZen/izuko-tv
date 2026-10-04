"""每条提交的改动行落在哪: 电视界面 / Web 控制台 / 数据层与其他. 判「界面相关的并进基线」时用.
文案 (strings*.xml) 与发版说明模板每个功能都改, 不计入.

  uv run --no-project python scripts/history/classify.py <上游基点> [序号…] [--from N] [--tip main]

不给序号时从第 --from 条看到链尾. 输出: 序号  界面占比  总行数  控制台行数  数据层行数  标题.
"""
import argparse
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")
REMOTE = re.compile(r"/ui/remote/")
STRINGS = re.compile(r"/res/values[^/]*/strings[^/]*\.xml$|ci-helper/release-template\.md$")
UI = re.compile(
    r"^app/shared/(ui-[^/]+)/|^app/shared/src/[^/]+/kotlin/ui/|^app/android/src/[^/]+/res/|^app/android/src/izukoTv/kotlin/tv/"
    r"|^app/shared/video-player/src/[^/]+/kotlin/ui/"
)


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
    for i in a.idx or range(a.start, len(order) + 1):
        sha = order[i - 1]
        ui = remote = other = 0
        for ln in git("show", "--numstat", "--no-renames", "--format=", sha).strip().splitlines():
            p = ln.split("\t")
            if len(p) < 3 or p[0] == "-":
                continue
            n = int(p[0]) + int(p[1])
            if STRINGS.search(p[2]):
                continue
            if REMOTE.search(p[2]):
                remote += n
            elif UI.search(p[2]):
                ui += n
            else:
                other += n
        tot = ui + remote + other
        subj = git("log", "-1", "--format=%s", sha).strip()
        print(f"{i}\t{ui / tot if tot else 0:.2f}\t{tot}\t{remote}\t{other}\t{subj[:80]}")


if __name__ == "__main__":
    main()
