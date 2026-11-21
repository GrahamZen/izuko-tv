"""本项目仓库的清单分支 (`lists`): 应用从这里取清单类文件 (镜像、加密直连地址、TMDB 图片入口、一起看的代理、BT 屏蔽规则、加密配置…),
与代码的 main 分开 —— 改清单只推这个分支, 不用连带推 main 上还没发布的提交. 见 docs/contributing/code/remote-files.md.

用法 (仓库根目录):
    uv run --no-project python scripts/lists_branch.py show                       # 分支上有哪些文件
    uv run --no-project python scripts/lists_branch.py put 文件... [--from-ref HEAD] [--message …]
        # 把工作区里的这些文件 (给 --from-ref 时取那个提交里的) 写进本地 lists 分支提交一次, 不碰工作区; 之后 `git push origin lists`
main 上同名的那几份给读 main 的旧版本 (1.0.9-alpha04 及以前) 与已发布的安装助手 (读 github-download-mirrors.json) 用, 改清单时也要跟着改、推 main.
"""
import argparse
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BRANCH = "lists"


def git(*args, input: bytes = None) -> str:
    r = subprocess.run(["git", *args], cwd=ROOT, input=input, capture_output=True)
    if r.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} 失败: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout.decode().strip()


def ref_exists(ref: str) -> bool:
    return subprocess.run(["git", "rev-parse", "--verify", "-q", ref], cwd=ROOT, capture_output=True).returncode == 0


def commit_files(files: dict, message: str) -> str:
    """把 {文件名: 内容 (bytes)} 写进本地 lists 分支 (没有就建一条孤立分支) 提交一次, 别的文件原样留着, 不碰工作区. 没变化就不提交."""
    parent = git("rev-parse", f"refs/heads/{BRANCH}") if ref_exists(f"refs/heads/{BRANCH}") else None
    entries = {}
    for line in (git("ls-tree", parent).splitlines() if parent else []):
        meta, name = line.split("\t", 1)
        entries[name] = meta
    for name, data in files.items():
        if "/" in name:
            raise SystemExit(f"清单分支只放根目录的文件: {name}")
        blob = git("hash-object", "-w", "--stdin", input=data)
        entries[name] = f"100644 blob {blob}"
    tree = git("mktree", input=("".join(f"{meta}\t{name}\n" for name, meta in sorted(entries.items()))).encode())
    if parent and git("rev-parse", f"{parent}^{{tree}}") == tree:
        return parent
    commit = git("commit-tree", tree, *(["-p", parent] if parent else []), input=message.encode())
    git("update-ref", f"refs/heads/{BRANCH}", commit, *([parent] if parent else []))
    return commit


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("show")
    p = sub.add_parser("put")
    p.add_argument("files", nargs="+")
    p.add_argument("--from-ref", help="从这个提交里取文件 (默认取工作区里的)")
    p.add_argument("--message", default="lists: 更新清单")
    a = ap.parse_args()
    if a.cmd == "show":
        print(git("ls-tree", f"refs/heads/{BRANCH}") if ref_exists(f"refs/heads/{BRANCH}") else "还没有本地 lists 分支")
        return
    files = {}
    for f in a.files:
        name = pathlib.PurePosixPath(f).name
        if a.from_ref:
            files[name] = subprocess.run(["git", "show", f"{a.from_ref}:{f}"], cwd=ROOT, capture_output=True, check=True).stdout
        else:
            files[name] = (ROOT / f).read_bytes()
    commit = commit_files(files, a.message)
    print(f"本地 lists 分支: {commit[:10]} ({', '.join(files)}); 推上去才生效: git push origin lists")


if __name__ == "__main__":
    main()
