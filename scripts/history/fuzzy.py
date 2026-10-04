"""三方合并冲突时的退路: 把 base -> theirs 的改动当补丁打到 ours 上, 上下文从 3 行缩到 1 行、0 行重试.

git merge-file / merge-tree 把挨着的改动也算冲突 (常见: 中间别的提交在旁边加了一行 import、在同一段末尾加了一条文案).
补丁只认上下文, 挨着没关系; 但打歪了不报错, 所以只在「结果相对 ours 的增删行」与「base -> theirs 的增删行」逐行相同
(不看位置) 时才收. 位置是否对, 由调用方最后比终树 (与原历史同一位置的文件逐字节相同) 兜底.
"""
import os
import subprocess
import tempfile

TMP = tempfile.mkdtemp(prefix="fuzzy-")


def _write(name, data):
    f = os.path.join(TMP, name)
    open(f, "wb").write(data)
    return f


def changed_lines(a, b):
    """a -> b 删掉的行与加上的行 (各排序), 不看位置."""
    out = subprocess.run(["git", "diff", "--no-index", "-U0", _write("ca", a), _write("cb", b)], capture_output=True).stdout
    lines = out.decode("utf-8", "replace").splitlines()
    minus = sorted(ln[1:] for ln in lines if ln.startswith("-") and not ln.startswith("---"))
    plus = sorted(ln[1:] for ln in lines if ln.startswith("+") and not ln.startswith("+++"))
    return minus, plus


def apply(base, ours, theirs):
    """把 base -> theirs 的改动打到 ours 上; 打不上或增删对不上返回 None. 三者都得是已有文件 (bytes)."""
    if base is None or ours is None or theirs is None:
        return None
    want = changed_lines(base, theirs)
    fin, fout = _write("in", ours), os.path.join(TMP, "out")
    fb, ft = _write("pb", base), _write("pt", theirs)
    for ctx in (3, 1, 0):
        diff = subprocess.run(["git", "diff", "--no-index", f"-U{ctx}", fb, ft], capture_output=True).stdout
        fpatch = _write("patch", diff)
        if os.path.exists(fout):
            os.remove(fout)
        r = subprocess.run(["patch", "-F3", "-s", "-o", fout, fin, fpatch], capture_output=True)
        if r.returncode != 0 or not os.path.exists(fout):
            continue
        res = open(fout, "rb").read()
        if changed_lines(ours, res) == want:
            return res
    return None
