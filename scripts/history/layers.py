"""把一条还没发布的功能分支按层重排: 每个文件整个归一层, 取最终的样子, 一层一条提交. 流程见同目录 README.md.

regroup.py 挪的是提交, 成员按原序重放; 这里不看中间的提交, 只看「留着的那条提交 → 最终的树」变了哪些文件.
适合反复重写过、提交之间互相改写的分支: 没有冲突可言, 代价是一个文件只能整个归一层
(前面的层要用到它的一部分才编得过时, 用 EARLY 让那一层先放它的一个历史版本).

最终的树 = 分支顶 + 工作区里没提交的改动, EXCLUDE 匹配的路径除外 (它们留在工作区). 在检出这条分支的工作区里跑;
计划是一个 python 文件 (格式见 layers-plan-template.py), 结果写在计划旁边.

  uv run --no-project python scripts/history/layers.py dry   <plan.py>                        # 算最终的树、分层、造链; 只写对象
  uv run --no-project python scripts/history/layers.py each  <plan.py> <worktree> <命令> [层,…]  # 在另一个 worktree 里逐条检出来跑命令 (编译)
  uv run --no-project python scripts/history/layers.py check <plan.py>                        # 落地前的核对, 什么都不改
  uv run --no-project python scripts/history/layers.py land  <plan.py>                        # 核对 + 备份 tag + CAS 挪分支 + 索引对到新的分支顶

核对的是: 分支还在 dry 时的位置; 索引里没有暂存的内容; 工作区里要提交的内容与 dry 时相同; 链一条接一条, 链尾的树 = 最终的树.
land 不动工作区的文件, 之后 `git status` 只剩 EXCLUDE 的那些.
"""
import datetime
import importlib.util
import json
import os
import re
import subprocess
import sys
import time

sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding="utf-8")

if len(sys.argv) < 3 or sys.argv[1] not in ("dry", "each", "check", "land"):
    raise SystemExit(__doc__)
MODE, PLAN_PATH = sys.argv[1], os.path.abspath(sys.argv[2])
OUT = os.path.dirname(PLAN_PATH)
RESULT = os.path.join(OUT, "layers-result.json")
REPO = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, check=True).stdout.decode().strip()
TMP_INDEX = os.path.join(OUT, f"layers-{os.getpid()}.index")
ZERO = "0" * 40

spec = importlib.util.spec_from_file_location("plan", PLAN_PATH)
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)
BRANCH = "refs/heads/" + plan.BRANCH
EXCLUDE = [re.compile(p) for p in getattr(plan, "EXCLUDE", [])]
RULES = [(layer, re.compile(p)) for layer, p in plan.RULES]
MOVES = getattr(plan, "MOVES", {})
EARLY = getattr(plan, "EARLY", [])


def run(args, cwd=REPO, env=None, input=None, check=True):
    r = subprocess.run(args, cwd=cwd, capture_output=True, env=env, input=input)
    if check and r.returncode != 0:
        raise SystemExit(f"{' '.join(args[:4])} failed ({r.returncode}): {r.stderr.decode('utf-8', 'replace')}")
    return r


def git(*args, **kw):
    return run(["git", *args], **kw).stdout.decode("utf-8", "replace")


def rev(x):
    return git("rev-parse", "--verify", x).strip()


def tree_of(c):
    return rev(c + "^{tree}")


def temp_index():
    return dict(os.environ, GIT_INDEX_FILE=TMP_INDEX)


def status():
    """工作区相对分支顶的改动 [(XY, 路径)]. 最终的树按工作区的文件算, 索引里另有暂存的版本时不往下做."""
    out = git("status", "--porcelain", "--untracked-files=all", "-z")
    items = [(entry[:2], entry[3:]) for entry in out.split("\0") if entry]
    staged = [path for xy, path in items if xy[0] not in " ?"]
    if staged:
        raise SystemExit(f"索引里有暂存的内容 ({len(staged)} 项, 如 {staged[0]}): 先 git reset -q")
    return items


def crlf_introduced(tip, path):
    """工作区的文本文件带回车换行, 而分支顶上的那一版没有 (新文件: 带就算). 工具整文件翻了换行符的会这样."""
    full = os.path.join(REPO, path)
    if not os.path.isfile(full):
        return False
    with open(full, "rb") as f:
        data = f.read()
    if b"\r\n" not in data or b"\0" in data:
        return False
    old = run(["git", "cat-file", "blob", f"{tip}:{path}"], check=False)
    return old.returncode != 0 or b"\r\n" not in old.stdout


def final_tree(tip):
    """分支顶 + 工作区里要提交的改动 → (树, 不提交的路径). 只写对象."""
    included, excluded = [], []
    for _, path in status():
        (excluded if any(p.search(path) for p in EXCLUDE) else included).append(path)
    flipped = [p for p in included if crlf_introduced(tip, p)]
    if flipped:
        raise SystemExit("这些文件的换行符变成了回车换行, 先改回去:\n  " + "\n  ".join(flipped))
    git("read-tree", tip, env=temp_index())
    if included:
        git("update-index", "--add", "--remove", "-z", "--stdin", env=temp_index(),
            input=("\0".join(included) + "\0").encode("utf-8"))
    return git("write-tree", env=temp_index()).strip(), sorted(excluded)


def layer_of(path):
    if path in MOVES:
        return MOVES[path]
    for layer, pattern in RULES:
        if pattern.search(path):
            return layer
    return None


def partition(keep, final):
    """「留着的那条 → 最终的树」变了的每个路径归到一层."""
    layers = {name: [] for name in plan.LAYERS}
    unmatched = []
    for path in filter(None, git("diff", "--name-only", "--no-renames", "-z", keep, final).split("\0")):
        layer = layer_of(path)
        if layer is None:
            unmatched.append(path)
        elif layer not in layers:
            raise SystemExit(f"{path} 归到了没列在 LAYERS 里的层 {layer}")
        else:
            layers[layer].append(path)
    if unmatched:
        raise SystemExit("没有规则管的路径:\n  " + "\n  ".join(unmatched))
    return layers


def entries(treeish, path=None):
    """树里每个路径的 (mode, blob); 给了 path 只看那一个."""
    args = ["ls-tree", "-r", "-z", treeish] + (["--", path] if path else [])
    result = {}
    for item in filter(None, git(*args).split("\0")):
        meta, name = item.split("\t", 1)
        mode, _, sha = meta.split(" ")
        result[name] = (mode, sha)
    return result


def message(name):
    text = plan.MESSAGES.get(name)
    if not text or not text.strip():
        raise SystemExit(f"层 {name} 没有说明")
    return text.strip() + "\n"


def build(keep, final, layers):
    """从留着的那条起一层一条造提交: 这一层的文件换成最终的样子 (最终没有的删掉), 别的不动."""
    for name in plan.LAYERS:
        message(name)
    target = entries(final)
    git("read-tree", keep, env=temp_index())
    prev, prev_tree, chain = keep, tree_of(keep), []
    for name in plan.LAYERS:
        info = ["%s %s\t%s" % (*target.get(path, ("0", ZERO)), path) for path in layers[name]]
        for layer, source, path in EARLY:
            if layer == name:
                found = entries(source, path)
                if path not in found:
                    raise SystemExit(f"{source} 里没有 {path}")
                info.append("%s %s\t%s" % (*found[path], path))
        if info:
            git("update-index", "--add", "-z", "--index-info", env=temp_index(),
                input=("\0".join(info) + "\0").encode("utf-8"))
        tree = git("write-tree", env=temp_index()).strip()
        if tree == prev_tree:
            print(f"{name}: 没有改动, 跳过")
            continue
        commit = git("commit-tree", tree, "-p", prev, "-F", "-", input=message(name).encode("utf-8")).strip()
        chain.append([name, commit, tree])
        prev, prev_tree = commit, tree
    if prev_tree != final:
        raise SystemExit(f"链尾的树 {prev_tree[:10]} 与最终的树 {final[:10]} 不同: EARLY 里的文件最终归的层要排在后面")
    return chain


def dry():
    tip, keep = rev(BRANCH), rev(plan.KEEP)
    if run(["git", "merge-base", "--is-ancestor", keep, tip], check=False).returncode != 0:
        raise SystemExit(f"留着的那条 {keep[:10]} 不在 {plan.BRANCH} 上")
    final, excluded = final_tree(tip)
    layers = partition(keep, final)
    chain = build(keep, final, layers)
    with open(RESULT, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"branch": BRANCH, "tip": tip, "keep": keep, "final_tree": final, "chain": chain, "excluded": excluded},
                  f, ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "layers.txt"), "w", encoding="utf-8", newline="\n") as f:
        for name, commit, _ in chain:
            f.write(f"== {name} {commit[:10]} ({len(layers[name])})\n" + "".join(f"  {p}\n" for p in layers[name]))
    print(f"{plan.BRANCH} {tip[:10]}  留着的 {keep[:10]}  最终的树 {final[:10]}  不提交的 {len(excluded)} 项")
    for name, commit, _ in chain:
        print(f"{commit[:10]} {name:<12} {len(layers[name]):>4} 个文件  {message(name).splitlines()[0][:80]}")
    old, new = git("rev-list", "--count", f"{keep}..{tip}").strip(), len(chain)
    print(f"{old} 条 -> {new} 条; 分支顶到新链尾差 {len(git('diff', '--name-only', '--no-renames', tip, final).split())} 个文件 (工作区里要提交的)")


def check():
    """落地前的核对. 返回 (dry 的结果, 新链尾)."""
    with open(RESULT, encoding="utf-8") as f:
        r = json.load(f)
    tip = rev(BRANCH)
    if tip != r["tip"] or BRANCH != r["branch"]:
        raise SystemExit(f"{plan.BRANCH} 现在在 {tip[:10]}, dry 时在 {r['tip'][:10]}: 重新 dry")
    if git("symbolic-ref", "HEAD").strip() != BRANCH:
        raise SystemExit(f"这个工作区检出的不是 {plan.BRANCH}")
    final, excluded = final_tree(tip)
    if final != r["final_tree"] or excluded != r["excluded"]:
        raise SystemExit("工作区里要提交的内容在 dry 之后变了: 重新 dry")
    prev = r["keep"]
    for name, commit, tree in r["chain"]:
        if git("rev-list", "--parents", "-1", commit).split() != [commit, prev]:
            raise SystemExit(f"{name}: 没接在上一条后面")
        if tree_of(commit) != tree:
            raise SystemExit(f"{name}: 树与 dry 的记录不同")
        prev = commit
    if tree_of(prev) != final:
        raise SystemExit("链尾的树与最终的树不同")
    print(f"核对过: {plan.BRANCH} 在 {tip[:10]}, 索引里没有暂存的, 最终的树 {final[:10]} 没变, "
          f"{len(r['chain'])} 条接在 {r['keep'][:10]} 后面, 链尾 {prev[:10]}")
    return r, prev


def land():
    r, new = check()
    old = r["tip"]
    tag = getattr(plan, "BACKUP_TAG", None) or "backup-regroup-%s-%s" % (
        datetime.datetime.now().strftime("%Y%m%d"), plan.BRANCH.rsplit("/", 1)[-1])
    existing = run(["git", "rev-parse", "-q", "--verify", f"refs/tags/{tag}^{{commit}}"], check=False)
    if existing.returncode != 0:
        git("tag", tag, old)
    elif existing.stdout.decode().strip() != old:
        raise SystemExit(f"tag {tag} 已经指着别的提交: 在计划里用 BACKUP_TAG 另起一个名字")
    git("update-ref", "-m", f"layers {os.path.basename(PLAN_PATH)}", BRANCH, new, old)
    print(f"{plan.BRANCH} {old[:10]} -> {new[:10]}  备份 tag {tag}")
    # 索引对到新的分支顶 (工作区的文件不动), 不然已经提交的改动在 git status 里显示成反向改动
    git("reset", "-q")
    left = sorted(path for _, path in status())
    if tree_of("HEAD") != r["final_tree"] or left != r["excluded"]:
        raise SystemExit(f"落地后的状态不对 (HEAD 的树 {tree_of('HEAD')[:10]}, 工作区还剩 {len(left)} 项): 对一遍 git status; 旧分支顶在 tag {tag}")
    print(f"HEAD 的树 = 最终的树; 工作区只剩不提交的 {len(left)} 项")


def bash():
    """Git 自带的 bash. Windows 上 PATH 里先找到的可能是 WSL 的 (System32), 它不认 /c/… 这样的路径."""
    if os.name == "nt":
        root = os.path.normpath(os.path.join(git("--exec-path").strip(), "..", "..", ".."))
        for candidate in (os.path.join(root, "usr", "bin", "bash.exe"), os.path.join(root, "bin", "bash.exe")):
            if os.path.exists(candidate):
                return candidate
    return "bash"


def each():
    """在另一个 worktree 里逐条检出链上的提交跑命令, 输出写到计划旁边的 each-<层>.log."""
    if len(sys.argv) < 5:
        raise SystemExit(__doc__)
    worktree, command = os.path.abspath(sys.argv[3]), sys.argv[4]
    shell = bash()
    only = set(sys.argv[5].split(",")) if len(sys.argv) > 5 else None
    with open(RESULT, encoding="utf-8") as f:
        chain = json.load(f)["chain"]
    for name, commit, _ in chain:
        if only and name not in only:
            continue
        run(["git", "checkout", "-q", "--detach", commit], cwd=worktree)
        log = os.path.join(OUT, f"each-{name}.log")
        start = time.time()
        with open(log, "wb") as f:
            code = subprocess.run([shell, "-c", command], cwd=worktree, stdout=f, stderr=subprocess.STDOUT).returncode
        print(f"{name} {commit[:10]} exit={code} {int(time.time() - start)}s  {log}", flush=True)


try:
    {"dry": dry, "each": each, "check": check, "land": land}[MODE]()
finally:
    if os.path.exists(TMP_INDEX):
        os.remove(TMP_INDEX)
