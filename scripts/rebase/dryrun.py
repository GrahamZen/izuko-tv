"""在内存里模拟「fork 全部提交 rebase 到上游」, 不碰任何分支、工作区与索引 (git merge-tree).

逐条重放, 统计每条提交的冲突, 并按 fork-rebase.sh 的自动规则分成「会被自动处理」与「要人手」两类:

- 目录改名: 重放时带 merge.directoryRenames=true, 直接跟着上游挪;
- strings.xml: 由 merge-android-strings 驱动按 key 合并 (需先装好, 见 fork-rebase.sh install_rules);
- fork 删掉的文件被上游改了: 保持删除;
- take-fork.txt 里的文件: 整份取 fork 版;
- .kt 里只有「两侧各自新增 / 只动了 import」的冲突: 取并集 (union_resolve.py).

「要人手」的冲突再按 rerere 的算法算指纹, 看 .git/rr-cache 里有没有现成解法. 冲突处用 -X theirs 继续往后重放.

用法 (在仓库根): uv run --no-project python scripts/rebase/dryrun.py [--onto upstream/main] [--base <merge-base>] [--tip HEAD]
"""
import argparse
import hashlib
import os
import re
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from union_resolve import resolve_text  # noqa: E402

# 重放写出的树与提交都进临时对象库 (真对象库挂成只读的 alternates), 跑完整个删掉, 不在 .git 里留下游离对象
OBJECTS = {}


def git(*args, env=None, inp=None, ok_codes=(0,)):
    e = {**os.environ, **OBJECTS, **(env or {})}
    r = subprocess.run(["git", *args], capture_output=True, env=e, input=inp)
    if r.returncode not in ok_codes:
        raise SystemExit(f"git {' '.join(args)}: {r.stderr.decode('utf-8', 'replace')}")
    return r


def out(*args, **kw):
    return git(*args, **kw).stdout.decode("utf-8", "replace").strip()


def exists(rev, path):
    return subprocess.run(["git", "cat-file", "-e", f"{rev}:{path}"], capture_output=True).returncode == 0


def merge_tree(onto, commit, *extra):
    r = git("-c", "merge.directoryRenames=true", "-c", "merge.conflictStyle=diff3",
            "merge-tree", "--write-tree", "-z", "--name-only", *extra,
            f"--merge-base={commit}^", onto, commit, ok_codes=(0, 1))
    parts = r.stdout.decode("utf-8", "replace").split("\0")
    tree, conflicted, i = parts[0], [], 1
    while i < len(parts) and parts[i]:
        conflicted.append(parts[i])
        i += 1
    kinds, rest, j = {}, parts[i + 1:], 0
    while j < len(rest):
        if not rest[j]:
            j += 1
            continue
        try:
            n = int(rest[j])
        except ValueError:
            break
        for p in rest[j + 1:j + 1 + n]:
            if rest[j + 1 + n].startswith("CONFLICT"):
                kinds.setdefault(p, rest[j + 1 + n])
        j += 3 + n
    return r.returncode == 0, tree, sorted(set(conflicted)), kinds


MARK = re.compile(r"^(<{7}|={7}|>{7}|\|{7})( |$)")


def rerere_id(text):
    """与 rerere.c 的 handle_path 一致: 每个冲突块两侧排序后各连同结尾 NUL 进 SHA1, 公共祖先段丢掉."""
    h, state, one, two, hunks = hashlib.sha1(), None, [], [], 0
    for line in text.splitlines(keepends=True):
        if MARK.match(line):
            if line.startswith("<<<<<<<") and state is None:
                state, one, two = "one", [], []
                continue
            if line.startswith("|||||||") and state == "one":
                state = "base"
                continue
            if line.startswith("=======") and state in ("one", "base"):
                state = "two"
                continue
            if line.startswith(">>>>>>>") and state == "two":
                a, b = "".join(one).encode("utf-8"), "".join(two).encode("utf-8")
                a, b = min(a, b), max(a, b)
                h.update(a + b"\0")
                h.update(b + b"\0")
                hunks += 1
                state = None
                continue
        if state == "one":
            one.append(line)
        elif state == "two":
            two.append(line)
    return h.hexdigest() if hunks else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onto", default="upstream/main")
    ap.add_argument("--tip", default="HEAD")
    ap.add_argument("--base", default=None, help="fork 的起点, 默认 merge-base(tip, onto)")
    args = ap.parse_args()
    base = args.base or out("merge-base", args.tip, args.onto)
    take_fork = {l.strip() for l in open(os.path.join(HERE, "take-fork.txt"), encoding="utf-8")
                 if l.strip() and not l.lstrip().startswith("#")}
    driver = out("config", "--get", "merge.android-strings.driver", ok_codes=(0, 1))
    if not driver:
        print("⚠ 没装 strings.xml 合并驱动, 文案冲突会按行算 (先跑 ./scripts/fork-rebase.sh install)")
    rr_cache = os.path.join(out("rev-parse", "--git-common-dir"), "rr-cache")
    tmp = tempfile.TemporaryDirectory(prefix="fork-rebase-dryrun-", ignore_cleanup_errors=True)
    os.mkdir(os.path.join(tmp.name, "objects"))
    OBJECTS.update(GIT_OBJECT_DIRECTORY=os.path.join(tmp.name, "objects"),
                   GIT_ALTERNATE_OBJECT_DIRECTORIES=os.path.abspath(out("rev-parse", "--git-path", "objects")))
    index = os.path.join(tmp.name, "index")

    commits = out("rev-list", "--reverse", f"{base}..{args.tip}").split()
    cur = out("rev-parse", args.onto)
    stats = {"raw": 0, "fork_deleted": 0, "take_fork": 0, "union": 0, "manual": 0, "rerere": 0}
    manual_report = []
    for c in commits:
        clean, tree, conflicted, kinds = merge_tree(cur, c)
        if not clean:
            removes, forks, unions, manual = [], [], [], []
            for p in conflicted:
                stats["raw"] += 1
                kind = kinds.get(p, "")
                if "modify/delete" in kind and not exists(c, p):
                    removes.append(p)
                    stats["fork_deleted"] += 1
                elif p in take_fork and exists(c, p):
                    forks.append(p)
                    stats["take_fork"] += 1
                elif "contents" in kind and "add/add" not in kind and p.endswith(".kt") and (
                        merged := resolve_text(out("show", f"{tree}:{p}", ok_codes=(0, 128)) + "\n")) is not None:
                    unions.append((p, merged))
                    stats["union"] += 1
                else:
                    hit = False
                    if "contents" in kind:
                        rid = rerere_id(out("show", f"{tree}:{p}", ok_codes=(0, 128)))
                        hit = bool(rid) and os.path.exists(os.path.join(rr_cache, rid, "postimage"))
                    stats["manual"] += 1
                    stats["rerere"] += hit
                    manual.append((p, kind.split(":")[0].replace("CONFLICT ", ""), hit))
            if manual:
                manual_report.append((c, out("log", "-1", "--format=%s", c), manual))
            # 往后重放: -X theirs 的结果, 再套上自动规则
            _, tree, _, _ = merge_tree(cur, c, "-X", "theirs")
            env = {"GIT_INDEX_FILE": index}
            git("read-tree", tree, env=env)
            for p in removes:
                git("update-index", "--force-remove", "--", p, env=env)
            for p in forks:
                mode_sha = out("ls-tree", c, "--", p).split("\t")[0].split()
                git("update-index", "--add", "--cacheinfo", f"{mode_sha[0]},{mode_sha[2]},{p}", env=env)
            for p, merged in unions:
                sha = git("hash-object", "-w", "--stdin", inp=merged.encode("utf-8")).stdout.decode().strip()
                git("update-index", "--add", "--cacheinfo", f"100644,{sha},{p}", env=env)
            tree = out("write-tree", env=env)
        cur = out("commit-tree", tree, "-p", cur, "-m", "dryrun")
    tmp.cleanup()

    print(f"{len(commits)} 条提交重放到 {args.onto}")
    print(f"冲突 {stats['raw']} 处: 自动 —— fork 删掉的保持删除 {stats['fork_deleted']}, 整份取 fork 版 {stats['take_fork']},"
          f" 两侧各自新增取并集 {stats['union']};"
          f" 要人手 {stats['manual']} 处 (其中 rerere 有现成解法 {stats['rerere']})")
    print("(目录改名与 strings.xml 由 git 在合并时直接处理, 不计入冲突)")
    for c, subject, manual in manual_report:
        print(f"\n{c[:9]} {subject[:70]}")
        for p, kind, hit in manual:
            print(f"   {'R' if hit else ' '} {kind:22} {p}")


if __name__ == "__main__":
    main()
