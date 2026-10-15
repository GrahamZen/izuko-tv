"""
把工作区里 agents 文件 (AGENTS.md / CLAUDE.md / .agents/** / .claude/**) 的改动并进历史上唯一那条
`chore(agents): 智能体规则与测试技能`. 那条之后的提交都不碰 agents 文件, 所以只把它们树里的这几个路径换成新版本、
作者 / 时间 / 说明原样重放, 不会冲突. 不碰工作区; 最后打备份 tag、带旧值 CAS 挪 main.

用法 (仓库根目录, 检出 main):
  uv run python -I scripts/history/fold-agents.py <路径>... [--message <说明文件>] [--apply]

- 路径只列自己改的: 共享工作区里别的会话可能也改着 agents 文件. 工作区里删掉的路径按删除处理.
- --message: 顺带换掉 agents 提交的说明 (文件里是完整说明). 不给就沿用原说明.
- 不加 --apply 只写对象、报新链尾; 加了才挪 main.
- 挪完: 主索引里这些路径 `git reset -q -- <路径>` 对齐新 HEAD; SHA 全换了, 通知在线会话
  (基于旧 main 的分支 `git rebase --onto <新 main> <旧 main> <分支>`).
"""
import os
import subprocess
import sys
import tempfile
import time

SUBJECT = "chore(agents): 智能体规则与测试技能"
PREFIXES = ("AGENTS.md", "CLAUDE.md", ".agents/", ".claude/")


def run(args, env=None, input=None, check=True):
    r = subprocess.run(args, capture_output=True, env=env, input=input)
    if check and r.returncode != 0:
        sys.exit(f"{' '.join(args)} failed:\n{r.stdout.decode(errors='replace')}{r.stderr.decode(errors='replace')}")
    return r


def git(*args, env=None, input=None):
    return run(["git", *args], env=env, input=input).stdout.decode("utf-8", errors="replace").strip()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = sys.argv[1:]
    apply = "--apply" in args
    args = [a for a in args if a != "--apply"]
    message = None
    if "--message" in args:
        i = args.index("--message")
        with open(args[i + 1], "rb") as f:
            message = f.read().replace(b"\r\n", b"\n").rstrip() + b"\n"
        del args[i:i + 2]
    paths = [p.replace("\\", "/") for p in args]
    if not paths:
        sys.exit(__doc__)
    for p in paths:
        if not p.startswith(PREFIXES):
            sys.exit(f"不是 agents 文件: {p}")
    if git("symbolic-ref", "HEAD") != "refs/heads/main":
        sys.exit("要在检出 main 的工作区里跑 (新版本取自工作区)")

    old_head = git("rev-parse", "refs/heads/main")
    candidates = git("log", "--format=%H%x09%s", "--fixed-strings", f"--grep={SUBJECT}", old_head).splitlines()
    agents = [line.split("\t", 1)[0] for line in candidates if line.split("\t", 1)[1] == SUBJECT]
    if len(agents) != 1:
        sys.exit(f"main 上「{SUBJECT}」应恰好一条, 实际 {len(agents)} 条")
    agents = agents[0]
    later = git("rev-list", "--reverse", f"{agents}..{old_head}").split()
    if git("rev-list", "--merges", f"{agents}..{old_head}"):
        sys.exit("agents 提交之后有合并提交, 不能按线性重放")
    touching = git("log", "--format=%h %s", f"{agents}..{old_head}", "--", *[p.rstrip("/") for p in PREFIXES])
    if touching:
        sys.exit(f"agents 提交之后还有提交改 agents 文件, 先把它们并进去:\n{touching}")

    # 新版本: 工作区里的文件 (经 git 的换行过滤); 不存在 = 删除
    updates = {}
    for p in paths:
        if os.path.exists(p):
            blob = git("hash-object", "-w", "--path", p, p)
            old = git("ls-tree", old_head, "--", p)
            mode = old.split()[0] if old else ("100755" if p.endswith(".sh") else "100644")
            updates[p] = (mode, blob)
        else:
            updates[p] = None
        print(("update " if updates[p] else "delete ") + p)

    index = os.path.join(tempfile.mkdtemp(), "index")
    env = dict(os.environ, GIT_INDEX_FILE=index)

    def replaced(tree):
        git("read-tree", tree, env=env)
        for p, entry in updates.items():
            if entry:
                git("update-index", "--add", "--cacheinfo", f"{entry[0]},{entry[1]},{p}", env=env)
            else:
                git("update-index", "--force-remove", p, env=env)
        return git("write-tree", env=env)

    def commit_like(orig, tree, parent, new_message=None):
        raw = run(["git", "cat-file", "commit", orig]).stdout
        header, _, body = raw.partition(b"\n\n")
        author = next(l for l in header.decode("utf-8").splitlines() if l.startswith("author "))
        name_email, _, date = author[len("author "):].rpartition("> ")
        name, _, email = name_email.partition(" <")
        cenv = dict(os.environ, GIT_AUTHOR_NAME=name, GIT_AUTHOR_EMAIL=email, GIT_AUTHOR_DATE=date)
        return git("commit-tree", tree, "-p", parent, env=cenv, input=new_message or body)

    new_agents = commit_like(agents, replaced(f"{agents}^{{tree}}"), git("rev-parse", f"{agents}^"), message)
    parent = new_agents
    mapping = [(agents, new_agents)]
    for c in later:
        new = commit_like(c, replaced(f"{c}^{{tree}}"), parent)
        if git("diff-tree", "-r", f"{c}^", c) != git("diff-tree", "-r", f"{parent}", new):
            sys.exit(f"重放 {c[:10]} 后改动与原提交不同")
        mapping.append((c, new))
        parent = new
    new_head = parent
    if git("rev-parse", f"{new_head}^{{tree}}") != replaced(f"{old_head}^{{tree}}"):
        sys.exit("新链尾的树不等于 旧 main + 这几个路径")
    print(f"agents {agents[:10]} -> {new_agents[:10]}; 重放 {len(later)} 条; 新链尾 {new_head[:10]} (旧 {old_head[:10]})")
    map_file = os.path.join(os.path.dirname(index), "fold-agents-map.txt")
    with open(map_file, "w", encoding="utf-8") as f:
        for o, n in mapping:
            f.write(f"{o[:10]} -> {n[:10]} {git('log', '-1', '--format=%s', o)}\n")
    print("对照表:", map_file)

    if apply:
        tag = f"backup-fold-agents-{time.strftime('%Y%m%d-%H%M')}-main"
        git("tag", tag, old_head)
        git("update-ref", "refs/heads/main", new_head, old_head)
        print(f"main {old_head[:10]} -> {new_head[:10]}, 备份 tag {tag}")
        print("接着: git reset -q -- " + " ".join(paths))
        others = [b for b in git("branch", "--format=%(refname:short)", "--contains", agents).splitlines() if b != "main"]
        if others:
            print("还挂在旧历史上的分支 (要 rebase --onto):", ", ".join(others))


main()
