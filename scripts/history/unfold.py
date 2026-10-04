"""把早先按文件拆进某条提交 (一般是基线) 的一条修复剥出来, 放回成单独一条.

regroup.py 只能整份文件地分: 一条修复改上游文件的那几行拆进基线后, 和基线自己对同一文件的改动混在一起, 按文件拆不回来.
这里拿那条修复在改写前历史里的原提交做反向三方合并: 第 --carrier 条到第 --at 条的树里, 这几个文件各减去原提交的改动;
第 --at 条之后插一条新提交把改动加回来 (树 = 原来第 --at 条的树, 说明与作者取原提交的); 再往后的提交树不变, 只换父提交.
终树与原链尾相同. 只写对象, 不碰工作区、索引与分支.

  uv run --no-project python scripts/history/unfold.py <上游基点> --carrier 71 --at 72 --source <原提交> --files <路径,…> [--tip main]
  uv run --no-project python scripts/history/unfold.py <上游基点> --land <新链尾> --expect <旧 main>   # 打备份 tag, CAS 挪 main

几条依次剥时把上一次打印的新链尾当 --tip 接着跑 (序号按那条链算), 最后 --land 一次.
剥完用 regroup.py 把原提交剩在后面的部分 (新文件、测试) 并进新插的这条, 再看能不能往下挪.
"""
import argparse
import datetime
import os
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
TMP = tempfile.mkdtemp(prefix="unfold-")
TMP_INDEX = os.path.join(TMP, "index")


def git(*a, env=None, input=None):
    return subprocess.run(["git", *a], capture_output=True, check=True, env=env, input=input).stdout


def rev(x):
    return git("rev-parse", x).decode().strip()


def tree_of(c):
    return rev(c + "^{tree}")


def entry(commit, path):
    out = git("ls-tree", "-z", commit, "--", path).decode("utf-8")
    if not out:
        return None
    mode, _, sha = out.split("\t", 1)[0].split()
    return mode, sha


def blob(sha):
    return git("cat-file", "blob", sha)


def merge3(ours, base, theirs):
    files = []
    for name, data in (("ours", ours), ("base", base), ("theirs", theirs)):
        f = os.path.join(TMP, name)
        open(f, "wb").write(data)
        files.append(f)
    r = subprocess.run(["git", "merge-file", "-p", *files], capture_output=True)
    return r.stdout, r.returncode == 0


def tree_with(tree, changes):
    env = dict(os.environ, GIT_INDEX_FILE=TMP_INDEX)
    git("read-tree", tree, env=env)
    data = b""
    for path, ent in changes.items():
        if ent is None:
            data += b"0 0000000000000000000000000000000000000000\t" + path.encode("utf-8") + b"\0"
        else:
            data += f"{ent[0]} {ent[1]}\t".encode() + path.encode("utf-8") + b"\0"
    git("update-index", "-z", "--index-info", env=env, input=data)
    return git("write-tree", env=env).decode().strip()


def raw_message(c):
    data = git("cat-file", "commit", c)
    return data.split(b"\n\n", 1)[1]


def author_env(c):
    an, ae, ad = git("log", "-1", "--format=%an%x00%ae%x00%ad", "--date=raw", c).decode("utf-8").split("\0")
    return dict(os.environ, GIT_AUTHOR_NAME=an, GIT_AUTHOR_EMAIL=ae, GIT_AUTHOR_DATE=ad.strip())


def commit_tree(tree, parent, msg_from):
    f = os.path.join(TMP, "msg")
    open(f, "wb").write(raw_message(msg_from))
    return git("commit-tree", tree, "-p", parent, "-F", f, env=author_env(msg_from)).decode().strip()


def changed_lines(a, b):
    """a -> b 删掉的行与加上的行 (各排序), 不看位置."""
    fa, fb = os.path.join(TMP, "a"), os.path.join(TMP, "b")
    open(fa, "wb").write(a)
    open(fb, "wb").write(b)
    out = subprocess.run(["git", "diff", "--no-index", "-U0", fa, fb], capture_output=True).stdout.decode("utf-8", "replace")
    minus = sorted(ln[1:] for ln in out.splitlines() if ln.startswith("-") and not ln.startswith("---"))
    plus = sorted(ln[1:] for ln in out.splitlines() if ln.startswith("+") and not ln.startswith("+++"))
    return minus, plus


def reverse_patch(content, source, path):
    """content 上反向打 source 对 path 的补丁 (允许上下文模糊); 结果的增删与原提交恰好相反才返回.
    上下文从 3 行缩到 1 行、0 行重试: 原提交的一段改动中间后来插进了别的行时, 上下文小一些才拆得开."""
    src_removed, src_added = changed_lines(blob(entry(source + "^", path)[1]), blob(entry(source, path)[1]))
    fin, fout, fpatch = (os.path.join(TMP, n) for n in ("in", "out", "patch"))
    open(fin, "wb").write(content)
    for ctx in (3, 1, 0):
        open(fpatch, "wb").write(git("diff", f"-U{ctx}", source + "^", source, "--", path))
        if os.path.exists(fout):
            os.remove(fout)
        r = subprocess.run(["patch", "-R", "-F3", "-s", "-o", fout, fin, fpatch], capture_output=True)
        if r.returncode != 0 or not os.path.exists(fout):
            continue
        res = open(fout, "rb").read()
        removed, added = changed_lines(content, res)
        if (removed, added) == (src_added, src_removed):
            return res
    return None


def without_source(commit, source, files, k):
    """commit 的树里, files 各减去 source 的改动."""
    changes = {}
    for p in files:
        cur, before, after = entry(commit, p), entry(source + "^", p), entry(source, p)
        if cur is None or after is None:
            sys.exit(f"第 {k} 条的 {p}: 文件不在 (或原提交删了它), 不支持")
        if before is None:
            if blob(cur[1]) != blob(after[1]):
                sys.exit(f"第 {k} 条的 {p}: 原提交新建的文件已被改过, 不能整份删")
            changes[p] = None
            continue
        res, clean = merge3(blob(cur[1]), blob(after[1]), blob(before[1]))
        if not clean:
            # 三方合并把相邻行的改动也算冲突 (常见: 后来在旁边又加了 import). 退一步用带模糊的 patch -R,
            # 只在删掉 / 加回的行与原提交的改动逐行对得上时才收
            res = reverse_patch(blob(cur[1]), source, p)
            if res is None:
                sys.exit(f"第 {k} 条的 {p}: 反向合并冲突, 这条提交也改了原提交那几行; 把 --at 放到它之前")
        if res == blob(cur[1]):
            print(f"  注意: 第 {k} 条的 {p} 减完没变 (原提交的改动不在这里)")
        sha = git("hash-object", "-w", "--stdin", input=res).decode().strip()
        changes[p] = (cur[0], sha)
    return tree_with(tree_of(commit), changes)


def land(new, expect):
    main = rev("refs/heads/main")
    if main != rev(expect):
        sys.exit(f"main 已经不是 {expect[:10]} (现在 {main[:10]}), 在新的 main 上重跑")
    if tree_of(new) != tree_of(main):
        sys.exit("终树与 main 不同, 不挪")
    tag = "backup-unfold-" + datetime.datetime.now().strftime("%Y%m%d-%H%M") + "-main"
    git("tag", tag, main)
    git("update-ref", "-m", "unfold", "refs/heads/main", new, main)
    print(f"main {main[:10]} -> {new[:10]}  备份 tag {tag}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("--tip", default="refs/heads/main")
    ap.add_argument("--carrier", type=int)
    ap.add_argument("--at", type=int)
    ap.add_argument("--source")
    ap.add_argument("--files")
    ap.add_argument("--land")
    ap.add_argument("--expect")
    a = ap.parse_args()
    if a.land:
        land(rev(a.land), a.expect or "refs/heads/main")
        return
    order = git("rev-list", "--reverse", f"{a.base}..{a.tip}").decode().split()
    source, files = rev(a.source), a.files.split(",")
    if not 1 <= a.carrier <= a.at <= len(order):
        sys.exit("--carrier <= --at <= 链长")
    parent = order[a.carrier - 2] if a.carrier > 1 else rev(a.base)
    for k in range(a.carrier, len(order) + 1):
        c = order[k - 1]
        t = without_source(c, source, files, k) if k <= a.at else tree_of(c)
        parent = commit_tree(t, parent, c)
        if k == a.at:
            parent = commit_tree(tree_of(c), parent, source)
    if tree_of(parent) != tree_of(order[-1]):
        sys.exit("终树对不上 (不该发生)")
    print(parent)


if __name__ == "__main__":
    main()
