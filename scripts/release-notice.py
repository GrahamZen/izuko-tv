#!/usr/bin/env python3
"""改已发布 release 正文里的「## 通知」一节: 发版后发现问题, 告诉已经装了这一版的人.

应用那边 (ui-tv `TvWhatsNew`, 解析在 ui-settings `ReleaseHighlights`): 装着这一版的电视每次冷启动去 GitHub 取这一版的正文,
「## 通知」一节的条目和上次显示过的不一样, 就盖一页只显示通知, 每台电视按内容只弹一次:
- 加一条 / 改一条 / 删掉其中一条: 内容变了, 看过旧通知的人也会再看到一次, 显示的是整节 (不只是改动的那条);
- 清空 (删掉整节): 什么都不弹;
- 只改正文的其他部分 (本次更新、下载表…): 不触发;
- 刚升级到这一版的人: 新功能介绍页最上面的卡片里就是当前的通知, 同样只出现一次;
- 连不上 GitHub 接口的电视收不到 (镜像读的是 tag 下的模板, 发版后改不了).

用法 (在仓库根目录):
  uv run --no-project python scripts/release-notice.py show   [--tag vX]
  uv run --no-project python scripts/release-notice.py add    [--tag vX] (文字... | -f 文件)   追加 (没有这一节就建在「## 本次更新」前面)
  uv run --no-project python scripts/release-notice.py set    [--tag vX] (文字... | -f 文件)   整节换成这些条目
  uv run --no-project python scripts/release-notice.py remove [--tag vX] 序号...              删掉第几条 (从 1 起, 序号见 show)
  uv run --no-project python scripts/release-notice.py clear  [--tag vX]                      删掉整节
  通用: --dry-run 只打印改动, 不提交; --repo owner/name (默认 gradle.properties 的 ani.repository).
不写 --tag = 最新的正式版 (releases/latest); 预发布版要写 --tag.

条目写成「标题：说明」: 应用按第一个全角冒号 (或「: 」) 拆, 冒号在前 30 字以内时前半句醒目显示、后半句压暗; 否则整条当标题.
Git Bash 里中文参数会变成问号: 中文写进 UTF-8 文件用 -f (一行一条, 开头的「- 」可写可不写), 或者在 PowerShell 里跑.
每次改之前把原正文备份到系统临时目录 (打印出路径), 改完再取回来核对. 需要已登录的 gh.
"""
import argparse
import datetime
import difflib
import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HEADING = "## 通知"
CHANGES_HEADING = "## 本次更新"
TITLE_MAX_LENGTH = 30

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")


def die(message):
    raise SystemExit(f"release-notice: {message}")


def gh(*args):
    r = subprocess.run(["gh", *args], capture_output=True)
    if r.returncode != 0:
        die(f"gh {' '.join(args)} 失败: {r.stderr.decode('utf-8', 'replace').strip()}")
    return r.stdout.decode("utf-8")


def default_repo():
    for line in open(os.path.join(ROOT, "gradle.properties"), encoding="utf-8"):
        if line.startswith("ani.repository="):
            return line.split("=", 1)[1].strip()
    die("gradle.properties 里没有 ani.repository, 用 --repo 指定")


def is_comment(text):
    """`[//]: # (…)` 与 `<!-- … -->`: 写给发版人的注释, 应用不显示. 模板那行用法注释里本身就有「## 通知」字样."""
    return text.startswith("[//]:") or text.startswith("<!--")


def is_thematic_break(text):
    compact = text.replace(" ", "")
    return len(compact) >= 3 and compact[0] in "-*_" and set(compact) == {compact[0]}


def find_section(lines):
    """「## 通知」那一行到下一个一级 / 二级标题之前 (左闭右开); 没有这一节返回 None. 按整行比."""
    for i, line in enumerate(lines):
        if line.strip() == HEADING:  # strip 连行尾的 \r 一起去掉
            j = i + 1
            while j < len(lines) and not (lines[j].startswith("## ") or lines[j].startswith("# ")):
                j += 1
            return i, j
    return None


def items_of(lines, section):
    """通知的条目, 与应用的解析一致: 列表项与一段一段的话都算, 小标题、注释、分隔线不算."""
    if section is None:
        return []
    items = []
    for line in lines[section[0] + 1:section[1]]:
        text = line.strip()
        if not text or text.startswith("#") or is_comment(text) or is_thematic_break(text):
            continue
        m = re.match(r"^[-*+] (.*)$", text)
        items.append((m.group(1) if m else text).strip())
    return items


def with_items(lines, items):
    """把通知换成 items: 空 = 删掉整节; 没有这一节就建在「## 本次更新」前面 (没有那个标题就放末尾)."""
    section = find_section(lines)
    if not items:
        return lines if section is None else lines[:section[0]] + lines[section[1]:]
    block = [HEADING, ""] + [f"- {x}" for x in items] + [""]
    if section is not None:
        return lines[:section[0]] + block + lines[section[1]:]
    at = next((i for i, line in enumerate(lines) if line.strip() == CHANGES_HEADING), len(lines))
    return lines[:at] + block + lines[at:]


def read_items(args):
    if args.file:
        raw = open(args.file, encoding="utf-8-sig").read().splitlines()
    else:
        raw = args.text
    items = [re.sub(r"^[-*+] ", "", x.strip()).strip() for x in raw]
    items = [x for x in items if x]
    if not items:
        die("没有条目: 给文字参数或 -f 文件")
    for x in items:
        if re.search(r"\?{2,}", x) or "�" in x:
            die(f"条目里有一串问号或乱码 ({x[:30]}…): Git Bash 下中文参数会变成问号, 改用 -f <UTF-8 文件>")
        colon = min([i for i in (x.find("："), x.find(": ")) if i >= 0], default=-1)
        if not 2 <= colon <= TITLE_MAX_LENGTH:
            print(f"注意: 「{x[:24]}…」没有在前 {TITLE_MAX_LENGTH} 字里的冒号, 应用里整条当标题显示 (想要标题 + 说明就写成「标题：说明」)",
                  file=sys.stderr)
    return items


def main():
    parser = argparse.ArgumentParser(description="改已发布 release 正文里的「## 通知」一节 (详见文件开头的说明)")
    parser.add_argument("command", choices=["show", "add", "set", "remove", "clear"])
    parser.add_argument("text", nargs="*", help="add / set 的条目; remove 的序号")
    parser.add_argument("--tag", help="release 的 tag (默认最新的正式版)")
    parser.add_argument("--repo", help="owner/name (默认 gradle.properties 的 ani.repository)")
    parser.add_argument("-f", "--file", help="add / set: 从 UTF-8 文件读条目, 一行一条")
    parser.add_argument("--dry-run", action="store_true", help="只打印改动, 不提交")
    parser.add_argument("--body-file", help="从本地文件读正文 (试脚本用: 只打印改动, 不碰 GitHub)")
    args = parser.parse_args()

    if args.body_file:
        args.dry_run = True
        repo, tag = "(本地文件)", args.tag or os.path.basename(args.body_file)
        release = {"body": open(args.body_file, encoding="utf-8", newline="").read()}
    else:
        repo = args.repo or default_repo()
        tag = args.tag or gh("api", f"repos/{repo}/releases/latest", "--jq", ".tag_name").strip()
        release = json.loads(gh("api", f"repos/{repo}/releases/tags/{tag}"))
    body = release.get("body") or ""
    # 按 \n 切, 行尾的 \r 留在行里 (比较时 strip 掉): GitHub 补的「Full Changelog」那几行是 \r\n, 其余是 \n, 写回时各行原样
    lines = body.split("\n")
    section = find_section(lines)
    current = items_of(lines, section)
    kind = "草稿" if release.get("draft") else ("预发布" if release.get("prerelease") else "正式版")
    print(f"{repo} {tag} ({kind})")

    if args.command == "show":
        if section is None:
            print("没有「## 通知」一节")
        elif not current:
            print("有「## 通知」标题, 但没有条目 (应用不会弹)")
        for n, x in enumerate(current, 1):
            print(f"  {n}. {x}")
        return

    if args.command == "add":
        wanted = current + read_items(args)
    elif args.command == "set":
        wanted = read_items(args)
    elif args.command == "remove":
        try:
            drop = {int(x) for x in args.text}
        except ValueError:
            die("remove 后面跟序号 (从 1 起, 见 show)")
        if not drop or not drop <= set(range(1, len(current) + 1)):
            die(f"序号要在 1~{len(current)} 之间 (现在 {len(current)} 条)")
        wanted = [x for n, x in enumerate(current, 1) if n not in drop]
    else:
        wanted = []

    new_body = "\n".join(with_items(lines, wanted))
    if new_body == body:
        print("没有变化, 不用改")
        return
    sys.stdout.writelines(difflib.unified_diff(
        [x.rstrip("\r") + "\n" for x in lines], [x.rstrip("\r") + "\n" for x in new_body.split("\n")], "改之前", "改之后", n=1))
    if args.dry_run:
        print("--dry-run: 没有提交")
        return

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = os.path.join(tempfile.gettempdir(), f"release-notice-{tag}-{stamp}.md")
    open(backup, "w", encoding="utf-8", newline="").write(body)
    notes = os.path.join(tempfile.gettempdir(), f"release-notice-{tag}-{stamp}-new.md")
    open(notes, "w", encoding="utf-8", newline="").write(new_body)
    gh("release", "edit", tag, "--repo", repo, "--notes-file", notes)
    check = json.loads(gh("api", f"repos/{repo}/releases/tags/{tag}")).get("body") or ""
    if check.replace("\r\n", "\n").rstrip("\n") != new_body.replace("\r\n", "\n").rstrip("\n"):
        die(f"取回来的正文和要写的不一样, 去 GitHub 上看一眼; 原正文备份在 {backup}")
    print(f"已更新; 原正文备份在 {backup}")
    if wanted:
        print(f"装着 {tag} 的电视下次冷启动弹一次 (整节 {len(wanted)} 条), 之后同样内容不再弹; 连不上 GitHub 接口的收不到")
    else:
        print("通知已清空: 不会再弹")
    if kind != "正式版":
        print(f"注意: 这是{kind}, 只有装着 {tag} 的人会看到")


if __name__ == "__main__":
    main()
