"""加密的应用配置 (交流群、弹弹 play 凭据、TMDB token 这类可能要换的东西): 生成钥匙、改配置、看现在是什么.

明文不进仓库, 也不进 CI:
- 配置以密文放在本仓库 `lists` 分支的 app-config.txt; 应用每天去取, 用包里的钥匙解开, 某一项有就用它、没有用包里的 (换掉不用发版);
- 钥匙: 本机 local.properties 的 `ani.config.key`, CI 的 secret `APP_CONFIG_KEY`; 编包时解开密文作为包里内置的那份 (build-logic/appConfig.kt).
- 名字是 local.properties 属性去掉 `ani.` 前缀: `dandanplay.app.id` ↔ `ani.dandanplay.app.id`; 应用里经 RemoteAppConfig 读.

用法 (仓库根目录, 前缀 `uv run --no-project --with cryptography python scripts/app-config.py`):
    keygen                         # 新钥匙写进 local.properties (已有就不动: 换钥匙会让已装的应用解不开新配置)
    push-secret                    # 把 local.properties 里的钥匙设成 CI secret APP_CONFIG_KEY (经 gh, 不打印)
    import-legacy                  # 把 local.properties 里已有的明文属性 (ani.<名字>) 收进配置, 配置里已有的不动
    set chat.qq.id=123 chat.qq.link=https://...   # 改几项
    unset chat.telegram.link       # 去掉几项 (应用就用包里内置的)
    show [--ref lists|origin/lists] [--reveal]    # 看配置里有哪些项 (默认打码; 不给 --ref 看 local.properties 里的)
改配置的命令都会: 写 local.properties 的 `ani.config.sealed` + 在本地 lists 分支提交一次 (不碰工作区); 之后 `git push origin lists` 才生效.

明文逐行 `名字=值`; 密文格式与加解密在 scripts/sealed_text.py (与应用、build-logic 同一个格式).
"""
import argparse
import os
import subprocess
import sys

from sealed_text import PURPOSE_APP_CONFIG, ROOT, b64e, open_token as open_sealed, read_props, seal as seal_text, write_prop

BRANCH = "lists"
FILE = "app-config.txt"
REPO = "GrahamZen/izuko-tv"
SECRET = "APP_CONFIG_KEY"
# 应用会读的项 (RemoteAppConfig); 缺了的在 show / 改完时提醒
KNOWN = ["chat.qq.id", "chat.qq.link", "chat.telegram.link", "dandanplay.app.id", "dandanplay.app.secret", "tmdb.api.token"]


def seal(config: dict, key: str) -> str:
    return seal_text("".join(f"{k}={v}\n" for k, v in sorted(config.items())), key, PURPOSE_APP_CONFIG)


def open_token(token: str, key: str):
    plain = open_sealed(token, key, PURPOSE_APP_CONFIG)
    return None if plain is None else dict(line.split("=", 1) for line in plain.splitlines() if "=" in line)


def git(*args, input: bytes = None) -> str:
    r = subprocess.run(["git", *args], cwd=ROOT, input=input, capture_output=True)
    if r.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} 失败: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout.decode().strip()


def ref_exists(ref: str) -> bool:
    return subprocess.run(["git", "rev-parse", "--verify", "-q", ref], cwd=ROOT, capture_output=True).returncode == 0


def commit_to_branch(token: str, message: str) -> str:
    """把 app-config.txt 写进本地 lists 分支 (没有就建一条只放清单的孤立分支), 不碰工作区."""
    blob = git("hash-object", "-w", "--stdin", input=(token + "\n").encode())
    parent = git("rev-parse", f"refs/heads/{BRANCH}") if ref_exists(f"refs/heads/{BRANCH}") else None
    entries = [l for l in (git("ls-tree", parent).splitlines() if parent else []) if not l.endswith(f"\t{FILE}")]
    entries.append(f"100644 blob {blob}\t{FILE}")
    tree = git("mktree", input=("\n".join(entries) + "\n").encode())
    if parent and git("rev-parse", f"{parent}^{{tree}}") == tree:
        return parent
    commit = git("commit-tree", tree, *(["-p", parent] if parent else []), input=message.encode())
    git("update-ref", f"refs/heads/{BRANCH}", commit, *([parent] if parent else []))
    return commit


def mask(value: str) -> str:
    return value if len(value) <= 4 else value[:3] + "…" + f"({len(value)} 字)"


def print_config(config: dict, reveal: bool) -> None:
    for k in sorted(config):
        print(f"  {k} = {config[k] if reveal else mask(config[k])}")
    missing = [k for k in KNOWN if not config.get(k)]
    if missing:
        print("  没有的项 (应用用包里内置的):", ", ".join(missing))


def current_config(props: dict, key: str) -> dict:
    sealed = props.get("ani.config.sealed")
    if not sealed:
        return {}
    config = open_token(sealed, key)
    if config is None:
        raise SystemExit("local.properties 里的 ani.config.sealed 用现在的钥匙解不开 (钥匙换过?)")
    return config


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("keygen")
    sub.add_parser("push-secret")
    for name in ("import-legacy", "set", "unset"):
        p = sub.add_parser(name)
        if name == "set":
            p.add_argument("items", nargs="+", metavar="名字=值")
        if name == "unset":
            p.add_argument("names", nargs="+", metavar="名字")
        p.add_argument("--message", default="app-config: 更新应用配置")
    p = sub.add_parser("show")
    p.add_argument("--ref", help="看哪个分支上的 app-config.txt (如 lists、origin/lists); 不给就看 local.properties 的 ani.config.sealed")
    p.add_argument("--reveal", action="store_true", help="显示明文 (默认打码)")
    a = ap.parse_args()

    props = read_props()
    key = props.get("ani.config.key", "")
    if a.cmd == "keygen":
        if key:
            raise SystemExit("local.properties 里已经有 ani.config.key; 换钥匙会让已装的应用解不开新配置, 要换先手动删掉那行")
        write_prop("ani.config.key", b64e(os.urandom(32)))
        print("新钥匙已写进 local.properties (ani.config.key); 接着 push-secret 设成 CI secret")
        return
    if not key:
        raise SystemExit("local.properties 里没有 ani.config.key, 先 keygen (或从备份抄回来)")
    if a.cmd == "push-secret":
        subprocess.run(["gh", "secret", "set", SECRET, "-R", REPO], input=key.encode(), check=True)
        print(f"已设 {REPO} 的 secret {SECRET}")
        return
    if a.cmd == "show":
        token = git("show", f"{a.ref}:{FILE}") if a.ref else props.get("ani.config.sealed", "")
        if not token:
            raise SystemExit("没有密文 (local.properties 里没有 ani.config.sealed; 或指定 --ref)")
        config = open_token(token, key)
        if config is None:
            raise SystemExit("用现在的钥匙解不开")
        print_config(config, a.reveal)
        return

    config = current_config(props, key)
    if a.cmd == "import-legacy":
        for name in KNOWN:
            if not config.get(name) and props.get(f"ani.{name}"):
                config[name] = props[f"ani.{name}"]
    elif a.cmd == "set":
        for item in a.items:
            if "=" not in item:
                raise SystemExit(f"要写成 名字=值: {item}")
            name, value = item.split("=", 1)
            config[name.strip()] = value.strip()
    elif a.cmd == "unset":
        for name in a.names:
            config.pop(name, None)
    token = seal(config, key)
    assert open_token(token, key) == config
    write_prop("ani.config.sealed", token)
    commit = commit_to_branch(token, a.message)
    print("已写 local.properties 的 ani.config.sealed, 本地 lists 分支:", commit[:10])
    print_config(config, reveal=False)
    print("推上去才生效: git push origin lists")


if __name__ == "__main__":
    main()
