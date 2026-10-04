"""按分组计划重放 fork 栈: 只写对象, 不碰工作区与索引. 流程见同目录 README.md.

计划是一个 python 文件 (格式见 plan-template.py): 组落在 anchor 的位置, 成员按原序逐个 merge-tree 叠上去;
一条提交可以按文件拆给几个目标 (SPLITS). 发版说明模板从每一步拿掉, 标了 template_head 的那组写成原链尾的版本.
往前挪的成员冲突时自动留在原位单独成段 (同组后面挪不动的并进那一段); 其余冲突直接报错, 要改计划.

  uv run --no-project python scripts/history/regroup.py dry  <plan.py> [tip] [--stop]   # 造新链 + 校验, 结果写在计划旁边; --stop 第一处冲突就停
  uv run --no-project python scripts/history/regroup.py land <plan.py>         # 备份 tag + CAS 挪 main

dry 的校验: 新旧链尾的树相同; 每条新提交按文件的净增删行 = 成员之和; 只有 template_head 那组改模板.
land 时 main 若在 dry 之后又多了提交 (旧链尾是它的祖先), 用 git replay 把多出来的接到新链尾上再挪.
"""
import collections
import datetime
import importlib.util
import json
import os
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True  # 同目录的模块别在仓库里留 __pycache__
from linehistory import LineHistory

sys.stdout.reconfigure(encoding="utf-8")

if len(sys.argv) < 3 or sys.argv[1] not in ("dry", "land"):
    raise SystemExit(__doc__)
MODE, PLAN_PATH = sys.argv[1], os.path.abspath(sys.argv[2])
OUT = os.path.dirname(PLAN_PATH)
REPO = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, check=True).stdout.decode().strip()
TMP_INDEX = os.path.join(tempfile.gettempdir(), f"regroup-{os.getpid()}.index")


def run(args, env=None, input=None, check=True):
    r = subprocess.run(args, cwd=REPO, capture_output=True, env=env, input=input)
    if check and r.returncode != 0:
        raise SystemExit(f"{' '.join(args)} failed ({r.returncode}): {r.stderr.decode('utf-8', 'replace')}")
    return r


def git(*args, **kw):
    return run(["git", *args], **kw).stdout


def rev(x):
    return git("rev-parse", x).decode().strip()


def tree_of(c):
    return rev(c + "^{tree}")


def land():
    new, old = open(os.path.join(OUT, "new_tip.txt")).read().split()
    main = rev("refs/heads/main")
    if main != old:
        if run(["git", "merge-base", "--is-ancestor", old, main], check=False).returncode != 0:
            raise SystemExit(f"main ({main[:10]}) 不再包含 dry 时的链尾 {old[:10]}: 历史被别人改写过, 重新 dry")
        extra = git("rev-list", "--reverse", f"{old}..{main}").decode().split()
        print(f"main 在 dry 之后多了 {len(extra)} 条, 用 git replay 接到新链尾上")
        tmp = "refs/heads/tmp/regroup-land"
        git("update-ref", tmp, main)
        try:
            # 新版 git (2.5x) 的 replay 默认直接改引用、不打印; 要它照旧打印 update 命令得加 --ref-action=print, 老版本不认这个参数
            r = run(["git", "replay", "--ref-action=print", "--onto", new, f"{old}..{tmp}"], check=False)
            if r.returncode != 0:
                r = run(["git", "replay", "--onto", new, f"{old}..{tmp}"])
            out = r.stdout.decode().split()
        finally:
            git("update-ref", "-d", tmp)
        new = out[out.index(tmp) + 1]
        if tree_of(new) != tree_of(main):
            raise SystemExit("接上多出来的提交后树与 main 不同, 没挪")
    tag = "backup-regroup-" + datetime.datetime.now().strftime("%Y%m%d-%H%M") + "-main"
    git("tag", tag, main)
    git("update-ref", "-m", f"regroup {os.path.basename(PLAN_PATH)}", "refs/heads/main", new, main)
    print(f"main {main[:10]} -> {new[:10]}  备份 tag {tag}")
    print("检出 main 的工作区树没变, 索引与文件不用动; 挪完在那里跑一次 git status 核对, 再通知在线会话 (SHA 全换).")


if MODE == "land":
    land()
    sys.exit(0)

spec = importlib.util.spec_from_file_location("plan", PLAN_PATH)
_plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(_plan)
BASE = _plan.BASE
PLAN_UNITS, SPLITS, MESSAGES = _plan.PLAN_UNITS, getattr(_plan, "SPLITS", {}), getattr(_plan, "MESSAGES", {})
TEMPLATE = getattr(_plan, "TEMPLATE", "ci-helper/release-template.md")
SPLIT_NOTE = getattr(_plan, "SPLIT_NOTE", None)  # 例: "含追上游后 {files} 的接口适配."

STOP = "--stop" in sys.argv  # 第一处冲突就停 (后面的多半是它的连锁)
args = [a for a in sys.argv[3:] if a != "--stop"]
TIP = rev(args[0]) if args else rev("refs/heads/main")
commits = git("rev-list", "--reverse", f"{BASE}..{TIP}").decode().split()
N = len(commits)
C = {i: commits[i - 1] for i in range(1, N + 1)}


def parent_of(i):
    return C[i - 1] if i > 1 else rev(BASE)


_trees = {}


def entries(commit, paths):
    """{path: (mode, sha)} (缺的 path 不在结果里). 整棵树读一次再过滤: 路径多时命令行会超长."""
    if commit not in _trees:
        res = {}
        for rec in git("ls-tree", "-z", "-r", commit).decode("utf-8").split("\0"):
            if rec:
                meta, path = rec.split("\t", 1)
                mode, _, sha = meta.split()
                res[path] = (mode, sha)
        _trees[commit] = res
    tree = _trees[commit]
    return {p: tree[p] for p in paths if p in tree}


def tree_with(base_tree, changes):
    """base_tree 上把 changes 里的路径换成 (mode, sha) 或删掉 (None). 只用对象库里的 blob, 不读工作区."""
    if not changes:
        return base_tree
    env = dict(os.environ, GIT_INDEX_FILE=TMP_INDEX)
    git("read-tree", base_tree, env=env)
    data = b""
    for path, ent in changes.items():
        if ent is None:
            data += b"0 0000000000000000000000000000000000000000\t" + path.encode("utf-8") + b"\0"
        else:
            data += f"{ent[0]} {ent[1]}\t".encode() + path.encode("utf-8") + b"\0"
    git("update-index", "-z", "--index-info", env=env, input=data)
    return git("write-tree", env=env).decode().strip()


_changed = {}


def changed_paths(i):
    if i not in _changed:
        _changed[i] = [p for p in git("diff-tree", "--no-commit-id", "-r", "--name-only", "--no-renames", "-z", C[i]).decode("utf-8").split("\0") if p]
    return _changed[i]


def member_trees(i, paths):
    """(base, theirs): 原父与原提交的树, 拿掉模板; paths 非空时 theirs 只取这些路径的改动."""
    base_s = tree_with(tree_of(parent_of(i)), {TEMPLATE: None})
    if paths is None:
        theirs_s = tree_with(tree_of(C[i]), {TEMPLATE: None})
    else:
        new = entries(C[i], paths)
        theirs_s = tree_with(base_s, {p: new.get(p) for p in paths})
    return base_s, theirs_s


def merge(base, ours, theirs):
    global LAST_CONFLICT_TREE
    r = run(["git", "merge-tree", "--write-tree", "--name-only", f"--merge-base={base}", ours, theirs], check=False)
    lines = r.stdout.decode("utf-8").split("\n")
    if r.returncode == 0:
        return lines[0].strip(), []
    if r.returncode == 1:
        LAST_CONFLICT_TREE = lines[0].strip()
        conflicted = []
        for ln in lines[1:]:
            if not ln.strip():
                break
            conflicted.append(ln.strip())
        return None, conflicted
    raise SystemExit(r.stderr.decode("utf-8", "replace"))


# ---------------- 按行认身份重建: 三方合并冲突时的退路 (见 linehistory.py) ----------------
LAST_CONFLICT_TREE = None
applied_by = collections.defaultdict(set)  # 原提交序号 -> 新链里已放上去的它的路径
_histories = {}


def blob_bytes(sha):
    return git("cat-file", "blob", sha)


def line_history(path):
    """重放范围 (第 start 条起) 里 path 的行身份; 删了又建的文件返回 None."""
    if path not in _histories:
        def data(c):
            ent = entries(c, [path]).get(path)
            return None if ent is None else blob_bytes(ent[1])
        try:
            _histories[path] = LineHistory(data(parent_of(start)), [(k, data(C[k])) for k in range(start, N + 1) if path in changed_paths(k)])
        except ValueError:
            _histories[path] = None
    return _histories[path]


def merge_file(base, ours, theirs):
    """单个文件用 git merge-file (Myers 差异) 再合一次: merge-tree 用 histogram, 两种对齐方式合得上的不完全一样. 干净返回 bytes."""
    if base is None or ours is None or theirs is None:
        return None
    names = []
    for name, data in (("o", ours), ("b", base), ("t", theirs)):
        f = os.path.join(tempfile.gettempdir(), f"regroup-{os.getpid()}-{name}")
        open(f, "wb").write(data)
        names.append(f)
    r = run(["git", "merge-file", "-p", *names], check=False)
    return r.stdout if r.returncode == 0 else None


def rebuild(m, paths, conflicted, trees):
    """三方合并冲突后, 冲突的文件先用 merge-file 再合一次, 不行再按行身份重建; 成了返回新树, 不成返回 None."""
    mine = set(paths if paths is not None else changed_paths(m)) - {TEMPLATE}
    changes = {}
    for p in conflicted:
        if p not in mine:
            return None
        blobs = [entries(t, [p]).get(p) for t in trees]
        data = merge_file(*[None if b is None else blob_bytes(b[1]) for b in blobs])
        if data is not None:
            changes[p] = (blobs[2][0], git("hash-object", "-w", "--stdin", input=data).decode().strip())
            continue
        h = line_history(p)
        if h is None:
            return None
        done = {k for k, ps in applied_by.items() if p in ps} | {m}
        data = h.content(done)
        if data is False:
            return None
        if data is None:
            changes[p] = None
        else:
            mode = (entries(C[m], [p]).get(p) or ("100644", None))[0]
            changes[p] = (mode, git("hash-object", "-w", "--stdin", input=data).decode().strip())
    print(f"  rebuilt: #{m} {[os.path.basename(p) for p in conflicted][:4]}")
    return tree_with(LAST_CONFLICT_TREE, changes)


def merge_member(m, paths, current):
    """把第 m 条 (paths 非空时只取这些路径) 的改动合到 current 上; 冲突时退到按行重建. 成了记下已放上去的路径."""
    base, theirs = member_trees(m, paths)
    res, conflicted = merge(base, current, theirs)
    if res is None:
        res = rebuild(m, paths, conflicted, (base, current, theirs))
    if res is not None:
        applied_by[m].update(set(paths if paths is not None else changed_paths(m)) - {TEMPLATE})
    return res, conflicted


# ---------------- 计划展开 ----------------
units = {}
member_of = {}
partial = collections.defaultdict(list)  # anchor -> [(i, paths)]
for u in PLAN_UNITS:
    a = u["anchor"]
    units[a] = dict(u)
    for m in u["members"]:
        assert m not in member_of, f"{m} in two units"
        member_of[m] = a
split_assigned = {}
for i, mapping in SPLITS.items():
    by_target = collections.defaultdict(list)
    for p in changed_paths(i):
        if p == TEMPLATE:
            continue  # 模板不跟着拆: 拆过去的那几个路径会原样写进目标, 模板就绕过了 template_head
        t = mapping.get(p, mapping.get("*"))
        if t == "latest":
            touchers = [k for k in range(1, i) if k not in mapping.get("skip", ()) and p in changed_paths(k)]
            t = touchers[-1] if touchers else None
        if t is None:
            raise SystemExit(f"split {i}: no target for {p}")
        by_target[t].append(p)
    split_assigned[i] = by_target
    for t, ps in by_target.items():
        if t != i:
            partial[member_of.get(t, t)].append((i, ps))
for i in range(1, N + 1):
    if i not in member_of and i not in split_assigned:
        units.setdefault(i, {"anchor": i, "members": [i]})
        member_of[i] = i
for i, by_target in split_assigned.items():
    if i in by_target:
        units.setdefault(i, {"anchor": i, "members": []})
        partial[i].append((i, by_target[i]))
        member_of.setdefault(i, i)

involved = set()
for a, u in units.items():
    if u["members"] != [a] or partial.get(a):
        involved.update(u["members"])
        involved.add(a)
involved.update(SPLITS)
template_touchers = [i for i in range(1, N + 1) if TEMPLATE in changed_paths(i)]
start = int(min(involved | set(template_touchers)))
print(f"tip {TIP[:10]}  commits {N}  replay from #{start} ({C[start][:10]})")


def subject(i):
    return git("log", "-1", "--format=%s", C[i]).decode("utf-8").strip()


def author_env(i):
    an, ae, ad = git("log", "-1", "--format=%an%x00%ae%x00%ad", "--date=raw", C[i]).decode("utf-8").split("\0")
    return dict(os.environ, GIT_AUTHOR_NAME=an, GIT_AUTHOR_EMAIL=ae, GIT_AUTHOR_DATE=ad.strip())


TRAILER = "Co-Authored-By:"


def split_msg(i):
    """(subject, body 去掉 trailer, trailer 行)."""
    lines = git("log", "-1", "--format=%B", C[i]).decode("utf-8").rstrip("\n").split("\n")
    trailers = [ln for ln in lines if ln.startswith(TRAILER)]
    rest = [ln for ln in lines if not ln.startswith(TRAILER)]
    return rest[0].strip(), "\n".join(rest[1:]).strip(), trailers


def compose(paragraphs, trailers):
    out = "\n\n".join(p for p in paragraphs if p.strip())
    seen = []
    for t in trailers:
        if t not in seen:
            seen.append(t)
    if seen:
        out += "\n\n" + "\n".join(seen)
    return (out + "\n").encode("utf-8")


def message_of(u, items, notes):
    """说明: MESSAGES[msg] 有 text 就用它; keep = 用 anchor (或 msg_from) 那条的原说明; 都没有时用组里第一条的."""
    whole = sorted(set(m for m, p in items if p is None))
    if u.get("segment"):
        first = whole[0] if whole else items[0][0]
        s, b, t = split_msg(first)
        return compose([s, b] + notes, t)
    spec = MESSAGES.get(u.get("msg")) if u.get("msg") else None
    trailers = []
    for m in whole:
        trailers += split_msg(m)[2]
    if spec and "text" in spec:
        return compose([spec["subject"], spec["text"].strip("\n")] + notes, trailers)
    src = u.get("msg_from", u["anchor"] if u["anchor"] in whole else (whole or [items[0][0]])[0])
    s, b, t = split_msg(int(src))
    if spec and spec.get("subject"):
        s = spec["subject"]
    return compose([s, b] + notes, t + trailers)


# ---------------- 重放 ----------------
parent = parent_of(start)
current = tree_of(parent)
new_chain = []  # (sha, anchor, items)
failures = []
unit_at = dict(units)
assigned = collections.defaultdict(list)
for a, u in units.items():
    u.setdefault("group", a)
    assigned[a] += [(m, None) for m in u["members"]]
for a, lst in partial.items():
    assigned[a] += lst
pending_seg = {}
ORDER = sorted(set(range(start, N + 1)) | {a for a in units if isinstance(a, float)})
msgfile = os.path.join(OUT, "msg.txt")
for i in ORDER:
    if STOP and failures:
        break
    items = sorted(assigned.get(i, []), key=lambda x: x[0])
    if not items:
        continue
    u = unit_at[i]
    if u.get("separate"):
        # 只挪位置: 每个成员照旧单独一条
        for m, paths in items:
            res, conflicted = merge_member(m, paths, current)
            if res is None:
                failures.append((i, m, paths, conflicted))
                print(f"  CONFLICT moved {m} ({subject(m)[:40]}) @{i}: {conflicted[:6]}")
                if STOP:
                    break
                continue
            current = res
            if current == tree_of(parent):
                print(f"  empty moved {m}: {subject(m)[:50]}")
                continue
            open(msgfile, "wb").write(git("log", "-1", "--format=%B", C[m]))
            sha = git("commit-tree", current, "-p", parent, "-F", msgfile, env=author_env(m)).decode().strip()
            new_chain.append((sha, i, [(m, None)]))
            parent = sha
        continue
    applied = []
    for m, paths in items:
        res, conflicted = merge_member(m, paths, current)
        if res is None:
            if paths is None and m > i and not isinstance(i, float):
                g = u["group"]
                seg = pending_seg.get(g)
                if seg is None or seg <= i or seg > m:
                    seg = m
                    unit_at[m] = {"anchor": m, "members": [], "group": g, "segment": True}
                assigned[seg].append((m, None))
                pending_seg[g] = seg
                print(f"  defer member {m} of group@{g} -> @{seg} ({subject(m)[:36]}): {conflicted[:3]}")
                continue
            failures.append((i, m, paths, conflicted))
            print(f"  CONFLICT unit@{i} member {m} ({subject(m)[:40]}) paths={'all' if paths is None else len(paths)}: {conflicted[:6]}")
            if STOP:
                break
            continue
        current = res
        applied.append((m, paths))
    items = applied
    if not items:
        continue
    if u.get("template_head"):
        current = tree_with(current, {TEMPLATE: entries(TIP, [TEMPLATE]).get(TEMPLATE)})
    if current == tree_of(parent):
        print(f"  empty unit@{i}")
        continue
    notes = []
    split_parts = [(m, p) for m, p in partial.get(i, []) if m != i]
    if split_parts and SPLIT_NOTE:
        files = sorted(set(os.path.basename(x) for _, ps in split_parts for x in ps))
        notes.append(SPLIT_NOTE.format(files="、".join(files[:4]) + (" 等" if len(files) > 4 else "")))
    open(msgfile, "wb").write(message_of(u, items, notes))
    sha = git("commit-tree", current, "-p", parent, "-F", msgfile, env=author_env(u.get("author", items[0][0]))).decode().strip()
    new_chain.append((sha, i, items))
    parent = sha

if failures:
    print(f"\n{len(failures)} conflicts — plan needs changes")
    json.dump(failures, open(os.path.join(OUT, "failures.json"), "w"), ensure_ascii=False)
    sys.exit(1)

# ---------------- 校验 ----------------
ok = True
if tree_of(parent) != tree_of(TIP):
    ok = False
    print("FINAL TREE DIFFERS:", git("diff", "--stat", TIP, parent).decode("utf-8")[-2000:])


_diffs = {}


def net_lines(a, b, paths=None):
    """按 (文件, 行) 计的净增删; paths 非空时只算这些文件 (整份 diff 取一次再过滤, 路径多时命令行会超长)."""
    if (a, b) not in _diffs:
        _diffs[(a, b)] = git("-c", "core.quotePath=false", "diff", "-U0", "--no-renames", "--no-color", a, b).decode("utf-8", "replace")
    keep = None if paths is None else set(paths)
    cnt = collections.Counter()
    f = None
    for ln in _diffs[(a, b)].split("\n"):
        if ln.startswith("diff --git"):
            f = None
        elif ln.startswith("+++ "):
            f = ln[6:] if ln[4:6] == "b/" else f
        elif ln.startswith("--- "):
            f = ln[6:] if ln[4:6] == "a/" else None
        elif ln.startswith("Binary files"):
            cnt[("<bin>", ln)] += 1
        elif f and f != TEMPLATE and (keep is None or f in keep) and ln and ln[0] in "+-":
            cnt[(f, ln[1:])] += 1 if ln[0] == "+" else -1
    return {k: v for k, v in cnt.items() if v}


prev = parent_of(start)
for sha, a, items in new_chain:
    got = {k: v for k, v in net_lines(prev, sha).items() if k[0] != "<bin>"}
    want = collections.Counter()
    for m, paths in items:
        for k, v in net_lines(parent_of(m), C[m], paths).items():
            want[k] += v
    want = {k: v for k, v in want.items() if v and k[0] != "<bin>"}
    if got != want:
        ok = False
        diff = [(k, got.get(k, 0), want.get(k, 0)) for k in set(got) | set(want) if got.get(k, 0) != want.get(k, 0)]
        print(f"LINES DIFFER unit@{a}: {len(diff)} e.g. {diff[:3]}")
    touched = [p for p in git("diff-tree", "--no-commit-id", "-r", "--name-only", "-z", sha).decode("utf-8").split("\0") if p]
    if TEMPLATE in touched and not unit_at[a].get("template_head"):
        ok = False
        print(f"TEMPLATE touched by unit@{a}")
    prev = sha

print(f"\nnew commits from #{start}: {len(new_chain)} (old {N - start + 1}); total {start - 1 + len(new_chain)} (old {N})")
mapping = []
for sha, a, items in new_chain:
    ms = sorted(set(m for m, _ in items))
    tag = "" if ms == [a] else f"  <= {ms}"
    line = f"{sha[:10]} #{a:>5} {git('log', '-1', '--format=%s', sha).decode('utf-8').strip()[:70]}{tag}"
    print("  " + line)
    mapping.append(line + "  (old: " + ", ".join(f"{m}={C[m][:10]}" for m in ms) + ")")
open(os.path.join(OUT, "mapping.txt"), "w", encoding="utf-8").write("\n".join(mapping) + "\n")
open(os.path.join(OUT, "new_tip.txt"), "w").write(parent + "\n" + TIP + "\n")
print("OK" if ok else "CHECKS FAILED")
sys.exit(0 if ok else 1)
