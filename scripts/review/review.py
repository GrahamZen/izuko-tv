"""fork 代码审查台账: 每个文件记下审过的那一对内容 (比较基准里的版本, fork 的版本), rebase 之后只审变了的.

结论绑定内容, 不绑定提交: 提交 SHA 只用来找到要审的那一版。一个文件的 fork 改动 = 基准版 → fork 版, 这两个 blob
哈希都没变, 改动就逐字节没变, 上次的结论接着有效。重排 / 合并 / 改写历史不产生待审项; 追上游之后只有上游也动过的
文件、fork 自己改过的文件进队列, 而且只给要看的那段 diff。只有上游改、fork 没碰的文件不逐个审, 由 `upstream`
按上游提交反查碰到了 fork 的哪些代码。台账在 refs/review/ledger (不在任何分支上)。流程见同目录 README.md。

  uv run --no-project python scripts/review/review.py status [--rev R]             # 概况 (默认)
  ... queue [--rev R] [--kind K,…] [--group | --tsv] [路径…]                     # 待审清单
  ... show [--rev R] [--kind K,…] [--full] 路径…                                 # 要看的 diff
  ... approve --rev R [--kind K,…] [-m 说明] 路径…                               # 记「审过, 没问题」
  ... issue --rev R -m 问题 路径…                                                # 记「审过, 有没解决的问题」
  ... upstream [--rev R] [--since U] [--approve --rev R -m 说明]                 # 上游提交碰到 fork 的哪里
  ... compact                                                                    # 去掉台账的历史 (清敏感信息时用)

路径参数: 目录或文件前缀, 或者带 * ? 的通配 (* 不跨 /, ** 跨目录); `.` 表示全部。
环境变量 UPSTREAM 或 --upstream 指定上游分支 (默认 upstream/main), 与 scripts/fork-rebase.sh 一致。
"""
import argparse
import collections
import difflib
import os
import re
import subprocess
import sys
import tempfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

LEDGER = "refs/review/ledger"
ZERO = "0" * 40
HERE = os.path.dirname(os.path.abspath(__file__))
# replayed: 离上游改动这么多行以内的 fork 块要一起看
NEAR = 20

# 待审的种类, 也是处理顺序 (风险从高到低排在 status 里)
KINDS = {
    "added": "fork 新增的文件: 审完整实现",
    "modified": "fork 改过的上游文件: 审改动和它连着的调用",
    "deleted": "fork 删掉的上游文件: 审该不该删、有没有残留引用",
    "remerged": "上游改了这个文件, fork 补丁也变了 (解过冲突 / 合并改写): 审补丁的变化和上游的改动",
    "dropped-upstream": "上游改了这个文件, fork 版没跟 (整取 fork 版 / 保持删除): 审丢掉的上游改动要不要搬过来",
    "vanished": "fork 的改动没了 (文件和上游一样了): 审是有意去掉、上游换了做法, 还是丢了",
    "replayed": "上游改了这个文件, fork 补丁原样重放: 审上游的改动和挨着的 fork 块合不合得来",
    "fork-changed": "上游版没变, fork 版变了: 审两版 fork 之间的差",
}
AUTO = {
    "clean": "审过、没变",
    "absorbed": "上游已吸收",
    "moved": "改名, 结论跟着新路径",
    "skip": "skip 规则",
    "keep-deleted": "keep-deleted 规则",
}
KOTLIN = (".kt", ".kts")
PACKAGE = re.compile(r"^package[ \t]+([\w.]+)", re.M)
IMPORT = re.compile(r"^import[ \t]+([\w.`]+)", re.M)
DECL = re.compile(r"^([ \t]*)(?:[a-z]+[ \t]+)*(?:fun|class|interface|object|val|var|typealias)[ \t]+(?:<[^>]*>[ \t]*)?([A-Za-z_][\w.]*)")
# 引用: 成员访问 (.x / ::x)、调用 (x( / x{ / x<)、类型名 (大写开头); 光秃秃的小写名字多半是局部变量, 不算
REF = re.compile(r"(?:(?<=\.)|(?<=::))([A-Za-z_]\w{3,})|\b([A-Z]\w{3,})|\b([a-z_]\w{3,})(?=[ \t]*[({<])")
# 测试 rebase 之后照样要跑, 跑挂了自己会报, 不靠这里提示
TEST = re.compile(r"/src/\w*[Tt]est/")
HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
STOP = {"Modifier", "Composable", "Preview", "String", "Boolean", "Unit", "List", "Flow", "State", "MutableState",
        "value", "state", "content", "modifier", "onClick", "enabled", "remember", "Text", "Column", "Box",
        "true", "false", "null", "this", "return", "override", "private", "internal", "import", "package", "suspend"}


class Fail(Exception):
    pass


def git(*args, input=None, env=None, ok=(0,)):
    r = subprocess.run(["git", "-c", "core.quotepath=false", *args], input=input, env=env, capture_output=True)
    if r.returncode not in ok:
        raise Fail(f"git {' '.join(args[:2])}: {r.stderr.decode('utf-8', 'replace').strip()}")
    return r.stdout


def out(*args, **kw):
    return git(*args, **kw).decode("utf-8").strip()


def rev_or_none(rev):
    s = out("rev-parse", "-q", "--verify", rev, ok=(0, 1))
    return s or None


def subject(c):
    return out("log", "-1", "--format=%s", c)


def dash(x):
    return "-" if x is None else x


def undash(s):
    return None if s == "-" else s


def empty_blob():
    # 没有这个文件的那一侧拿空 blob 比; 新仓库里不一定有这个对象, 每次写一下 (已有就是空操作)
    return out("hash-object", "-w", "--stdin", input=b"")


# ---------------------------------------------------------------------------
# 规则与路径匹配
# ---------------------------------------------------------------------------

def glob_re(pat):
    """目录或文件前缀; 带 * ? 时按通配 (* 不跨 /, ** 跨目录)。匹配到目录就包括目录下的全部文件。"""
    pat = pat.replace("\\", "/").strip().rstrip("/")
    if pat.startswith("./"):
        pat = pat[2:]
    s, i = "", 0
    while i < len(pat):
        if pat.startswith("**/", i):
            s, i = s + r"(?:.*/)?", i + 3
        elif pat.startswith("**", i):
            s, i = s + ".*", i + 2
        elif pat[i] == "*":
            s, i = s + "[^/]*", i + 1
        elif pat[i] == "?":
            s, i = s + "[^/]", i + 1
        else:
            s, i = s + re.escape(pat[i]), i + 1
    return re.compile(s + r"(?:/.*)?")


def path_filter(specs):
    if not specs or "." in specs:
        return lambda p: True
    rs = [glob_re(s) for s in specs]
    return lambda p: any(r.fullmatch(p) for r in rs)


class Rules:
    def __init__(self, path):
        self.base, self.skip, self.keep_deleted = [], [], []
        with open(path, encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                kind, _, pat = line.partition(" ")
                pat = pat.strip()
                if kind == "base-commit":
                    self.base.append(re.compile(pat))
                elif kind == "skip":
                    self.skip.append(glob_re(pat))
                elif kind == "keep-deleted":
                    self.keep_deleted.append(glob_re(pat))
                else:
                    raise Fail(f"{path}:{n}: 不认识的规则「{kind}」")

    def is_base(self, subj):
        return any(r.search(subj) for r in self.base)

    def skipped(self, p):
        return any(r.fullmatch(p) for r in self.skip)

    def kept_deleted(self, p):
        return any(r.fullmatch(p) for r in self.keep_deleted)


# ---------------------------------------------------------------------------
# 树与补丁
# ---------------------------------------------------------------------------

def ls_tree(rev):
    res = {}
    for rec in git("ls-tree", "-r", "-z", "--full-tree", rev).split(b"\0"):
        if not rec:
            continue
        meta, path = rec.split(b"\t", 1)
        _mode, typ, sha = meta.split(b" ")
        if typ == b"blob":
            res[path.decode("utf-8")] = sha.decode()
    return res


def make_tree(entries):
    """{路径: blob} → 树对象, 用临时索引, 不碰工作区和真索引。"""
    with tempfile.TemporaryDirectory() as d:
        env = dict(os.environ, GIT_INDEX_FILE=os.path.join(d, "index"))
        info = "".join(f"100644 {sha}\t{p}\n" for p, sha in entries.items())
        git("update-index", "--replace", "--index-info", input=info.encode("utf-8"), env=env)
        return out("write-tree", env=env)


def pair_trees(pairs):
    """{路径: (旧 blob, 新 blob)} → 两棵树, 一次 diff-tree 就能拿到所有文件这两版之间的差。"""
    return (make_tree({p: o for p, (o, _) in pairs.items() if o}),
            make_tree({p: n for p, (_, n) in pairs.items() if n}))


def split_patch(text):
    """diff-tree -p 的输出 → {路径: [从第一个 @@ 起的行]}; 二进制文件只有一行 Binary files …。"""
    res, cur, body = {}, None, False
    for line in text.split("\n"):
        if line.startswith("diff --git "):
            rest, cur, body = line[len("diff --git "):], None, False
            n = (len(rest) - 5) // 2
            if rest.startswith("a/") and rest == f"a/{rest[2:2 + n]} b/{rest[2:2 + n]}":
                cur = rest[2:2 + n]
                res[cur] = []
            continue
        if cur is None or not line:
            continue
        if line.startswith("@@"):
            body = True
        if body or line.startswith("Binary files"):
            res[cur].append(line)
    return res


def pair_patches(pairs, context=0):
    if not pairs:
        return {}
    old, new = pair_trees(pairs)
    return split_patch(git("diff-tree", "-r", "-p", f"-U{context}", "--no-renames", "--no-color", old, new)
                       .decode("utf-8", "replace"))


def norm(lines):
    """只比改动行: 去掉行号, 二进制文件没法比 (返回 None)。"""
    if lines is None or any(l.startswith("Binary files") for l in lines):
        return None
    return ["@@" if l.startswith("@@") else l for l in lines]


def ranges(lines, side):
    res = []
    for l in lines:
        m = HUNK.match(l)
        if m:
            s, n = (int(m[1]), int(m[2] or 1)) if side == "old" else (int(m[3]), int(m[4] or 1))
            res.append((s, s + max(n, 1) - 1))
    return res


def gap(a, b):
    return max(0, b[0] - a[1], a[0] - b[1])


def blob_diff(o, n, context=3):
    text = git("diff", "--no-color", "--no-ext-diff", f"-U{context}", o or empty_blob(), n or empty_blob())
    lines = text.decode("utf-8", "replace").split("\n")
    for k, l in enumerate(lines):
        if l.startswith("@@") or l.startswith("Binary files"):
            return [x for x in lines[k:] if x]
    return []


def hunks_of(lines):
    res = []
    for l in lines:
        if l.startswith("@@") or not res:
            res.append([])
        res[-1].append(l)
    return res


# ---------------------------------------------------------------------------
# 要审的那一版, 台账
# ---------------------------------------------------------------------------

class Ctx:
    """目标提交 T, 它所在的上游提交 U, 比较基准 B (U 加上栈底按规则算上游的几条, 如撤销上游删功能的 revert)。"""

    def __init__(self, rev, upstream_ref, rules):
        self.rules = rules
        self.T = out("rev-parse", "--verify", rev + "^{commit}")
        u = out("merge-base", self.T, upstream_ref)
        # 上游分支本身带着栈底 revert 时 (fork-rebase.sh 的 UPSTREAM=rebase/base-<日期>), 往下找到真正的上游提交
        while rules.is_base(subject(u)):
            u = out("rev-parse", u + "^")
        self.U = self.B = u
        for line in out("log", "--first-parent", "--reverse", "--format=%H %s", f"{u}..{self.T}").splitlines():
            sha, _, subj = line.partition(" ")
            if not rules.is_base(subj):
                break
            self.B = sha
        self.base = ls_tree(self.B)
        self.fork = ls_tree(self.T)
        self.delta = {p for p in self.base.keys() | self.fork.keys() if self.base.get(p) != self.fork.get(p)}


class Ledger:
    """ledger.tsv: 路径 → 审过的 (基准 blob, fork blob); issues.tsv: 审过但没解决的问题; upstream: 上游交互审到哪。
    树里另有 blobs/ 存着这些 blob, 让旧版内容一直取得到 (rebase 之后旧提交会被回收)。"""

    def __init__(self):
        self.commit = rev_or_none(LEDGER)
        self.pairs, self.issues, self.upstream = {}, collections.defaultdict(list), None
        if not self.commit:
            return
        for line in self._file("ledger.tsv").splitlines():
            if line and not line.startswith("#"):
                p, b, f = line.split("\t")
                self.pairs[p] = (undash(b), undash(f))
        for line in self._file("issues.tsv").splitlines():
            if line:
                p, f, text = line.split("\t", 2)
                self.issues[p].append((undash(f), text))
        self.upstream = self._file("upstream").strip() or None

    def _file(self, name):
        return git("show", f"{self.commit}:{name}", ok=(0, 128)).decode("utf-8")


def write_ledger(old, pairs, issues, upstream, message):
    def blob(text):
        return out("hash-object", "-w", "--stdin", input=text.encode("utf-8"))

    tsv = "# 路径\t基准版 blob\tfork 版 blob (- 表示没有这个文件); 由 scripts/review/review.py 维护\n"
    tsv += "".join(f"{p}\t{dash(b)}\t{dash(f)}\n" for p, (b, f) in sorted(pairs.items()))
    iss = "".join(f"{p}\t{dash(f)}\t{t}\n" for p in sorted(issues) for f, t in issues[p])
    entries = {"ledger.tsv": blob(tsv), "issues.tsv": blob(iss), "upstream": blob((upstream or "") + "\n")}
    for pair in pairs.values():
        for sha in pair:
            if sha:
                entries[f"blobs/{sha[:2]}/{sha[2:]}"] = sha
    tree = make_tree(entries)
    new = out("commit-tree", tree, *(["-p", old.commit] if old.commit else []), "-F", "-", input=message.encode("utf-8"))
    r = subprocess.run(["git", "update-ref", "-m", "review", LEDGER, new, old.commit or ZERO], capture_output=True)
    return new if r.returncode == 0 else None


# ---------------------------------------------------------------------------
# 分类
# ---------------------------------------------------------------------------

Item = collections.namedtuple("Item", "path kind b f ab af src note", defaults=(None, None, None, None))


def classify(ctx, led):
    """→ (待审项, 免审计数, 改名跟过去的 [(旧路径, 新路径, 基准 blob, fork blob)])。
    b / f 是现在的基准版 / fork 版, ab / af 是台账里审过的那一对; None 表示没有这个文件。"""
    items, auto, moves = [], collections.Counter(), []
    for p in sorted(ctx.delta | led.pairs.keys()):
        b, f = ctx.base.get(p), ctx.fork.get(p)
        if ctx.rules.skipped(p):
            auto["skip"] += 1
            continue
        if f is None and b is not None and ctx.rules.kept_deleted(p):
            auto["keep-deleted"] += 1
            continue
        if p not in led.pairs:
            items.append(Item(p, "added" if b is None else "deleted" if f is None else "modified", b, f))
            continue
        ab, af = led.pairs[p]
        if (b, f) == (ab, af):
            auto["clean"] += 1
        elif b == f:
            if f == af:
                auto["absorbed"] += 1
            else:
                items.append(Item(p, "vanished", b, f, ab, af))
        elif b == ab:
            items.append(Item(p, "fork-changed", b, f, ab, af))
        elif f == af:
            items.append(Item(p, "dropped-upstream", b, f, ab, af))
        else:
            items.append(Item(p, "rebased", b, f, ab, af))

    # 改名: 审过的路径没了 (vanished) + 一个没审过的新路径, 内容相近 (git 的改名检测) 就把结论接过去, 只审两版之间的差
    gone = {i.path: i for i in items if i.kind == "vanished" and i.af}
    fresh = {i.path: i for i in items if i.kind in ("added", "modified")}
    if gone and fresh:
        x, y = make_tree({p: i.af for p, i in gone.items()}), make_tree({p: i.f for p, i in fresh.items()})
        recs = git("diff-tree", "-r", "-z", "-M50%", "-l0", "--name-status", x, y).decode("utf-8").split("\0")
        k, moved = 0, {}
        while k < len(recs) and recs[k]:
            if recs[k].startswith("R"):
                moved[recs[k + 2]] = recs[k + 1]
                k += 3
            else:
                k += 2
        drop = set()
        for i, it in enumerate(items):
            src = moved.get(it.path) if it.path in fresh else None
            if src is None:
                continue
            old = gone[src]
            drop.add(src)
            if (it.b, it.f) == (old.ab, old.af):
                moves.append((src, it.path, it.b, it.f))
                auto["moved"] += 1
                items[i] = None
            else:
                kind = "fork-changed" if it.b == old.ab else "dropped-upstream" if it.f == old.af else "rebased"
                items[i] = it._replace(kind=kind, ab=old.ab, af=old.af, src=src)
                auto["moved"] += 1
        items = [i for i in items if i is not None and not (i.kind == "vanished" and i.path in drop)]

    # 两边都动了: fork 补丁 (只看改动行) 和上次审过的一样就是原样重放, 否则是合并时变了
    both = [i for i in items if i.kind == "rebased"]
    if both:
        was = pair_patches({i.path: (i.ab, i.af) for i in both})
        now = pair_patches({i.path: (i.b, i.f) for i in both})
        up = pair_patches({i.path: (i.ab, i.b) for i in both})
        for k, it in enumerate(items):
            if it.kind != "rebased":
                continue
            a, n = norm(was.get(it.path)), norm(now.get(it.path))
            if a is None or a != n:
                items[k] = it._replace(kind="remerged")
                continue
            d = min((gap(x, y) for x in ranges(now[it.path], "old") for y in ranges(up.get(it.path, []), "new")),
                    default=None)
            note = None if d is None else "挨着上游改动" if d == 0 else f"离上游改动 {d} 行"
            items[k] = it._replace(kind="replayed", note=note)
    return items, auto, moves


def material(it):
    """这一项要看的那段差: 审它的工作量。"""
    if it.kind in ("added", "modified", "deleted"):
        return it.b, it.f
    if it.kind == "fork-changed":
        return it.af, it.f
    if it.kind == "vanished":
        return it.ab, it.af
    return it.ab, it.b


def sizes(items):
    pairs = {i.path: material(i) for i in items}
    if not pairs:
        return {}
    old, new = pair_trees(pairs)
    res = {}
    for rec in git("diff-tree", "-r", "-z", "--numstat", "--no-renames", old, new).decode("utf-8").split("\0"):
        if rec:
            a, d, p = rec.split("\t", 2)
            res[p] = (int(a) if a != "-" else 0, int(d) if d != "-" else 0)
    return res


def select(items, kinds, paths):
    want = set(kinds.split(",")) if kinds else None
    if want and not want <= KINDS.keys():
        raise Fail(f"不认识的种类 {', '.join(sorted(want - KINDS.keys()))}; 可选 {', '.join(KINDS)}")
    match = path_filter(paths)
    return [i for i in items if match(i.path) and (want is None or i.kind in want)]


# ---------------------------------------------------------------------------
# 命令
# ---------------------------------------------------------------------------

def print_header(ctx, led):
    print(f"目标  {ctx.T[:12]}  {subject(ctx.T)}")
    print(f"上游  {ctx.U[:12]}  {subject(ctx.U)}")
    if ctx.B != ctx.U:
        print(f"基准  {ctx.B[:12]}  {subject(ctx.B)}  (栈底这几条按 rules.txt 算上游, 不审)")
    if led.commit:
        up = led.upstream or ""
        behind = int(out("rev-list", "--count", "--first-parent", f"{up}..{ctx.U}")) if up else 0
        print(f"台账  {led.commit[:12]}  记着 {len(led.pairs)} 个文件; 上游交互审到 {up[:12]}"
              + (f" (之后上游还有 {behind} 条没对过, 跑 upstream)" if behind else ""))
    else:
        print("台账  还没有 (第一次 approve 时建, 记下当时的上游)")


def cmd_status(ctx, led, args):
    print_header(ctx, led)
    items, auto, _ = classify(ctx, led)
    size = sizes(items)
    if items:
        a = sum(size.get(i.path, (0, 0))[0] for i in items)
        d = sum(size.get(i.path, (0, 0))[1] for i in items)
        print(f"\n待审 {len(items)} 个文件, 要看的差 +{a} -{d} 行:")
        by = collections.defaultdict(list)
        for i in items:
            by[i.kind].append(i)
        for k, desc in KINDS.items():
            if by[k]:
                a = sum(size.get(i.path, (0, 0))[0] for i in by[k])
                d = sum(size.get(i.path, (0, 0))[1] for i in by[k])
                print(f"  {k:<17}{len(by[k]):>5}  +{a:<7} -{d:<7} {desc}")
    else:
        print("\n没有待审的文件")
    print("免审: " + (" · ".join(f"{AUTO[k]} {auto[k]}" for k in AUTO if auto[k]) or "无"))
    n = sum(len(v) for v in led.issues.values())
    if n:
        print(f"\n审过、还没解决的问题 {n} 条:")
        for p in sorted(led.issues):
            for _, t in led.issues[p]:
                print(f"  {p}: {t}")
    if items:
        print(f"\n下一步: queue --rev {ctx.T[:12]} 列文件, show 看内容, approve / issue 记结论")


def module_of(p):
    parts = p.split("/")
    depth = 3 if parts[0] == "app" and len(parts) > 1 and parts[1] == "shared" else 2
    return "/".join(parts[:depth]) if len(parts) > depth else (os.path.dirname(p) or ".")


def cmd_queue(ctx, led, args):
    items = select(classify(ctx, led)[0], args.kind, args.paths)
    size = sizes(items)
    if args.tsv:
        for i in items:
            a, d = size.get(i.path, (0, 0))
            print(f"{i.kind}\t{i.path}\t{a}\t{d}\t{i.src or ''}")
        return
    print(f"目标 {ctx.T[:12]}  待审 {len(items)} 个文件")
    if args.group:
        groups = collections.defaultdict(list)
        for i in items:
            groups[module_of(i.path)].append(i)
        print(f"\n{'模块':<44}{'文件':>6}{'+行':>9}{'-行':>8}  种类")
        for m in sorted(groups, key=lambda m: -sum(size.get(i.path, (0, 0))[0] for i in groups[m])):
            g = groups[m]
            kinds = collections.Counter(i.kind for i in g)
            print(f"{m:<46}{len(g):>6}{sum(size.get(i.path, (0, 0))[0] for i in g):>9}"
                  f"{sum(size.get(i.path, (0, 0))[1] for i in g):>8}  "
                  + " ".join(f"{k} {kinds[k]}" for k in KINDS if kinds[k]))
        return
    for k in KINDS:
        g = [i for i in items if i.kind == k]
        if not g:
            continue
        print(f"\n{k} ({len(g)}): {KINDS[k]}")
        for i in g:
            a, d = size.get(i.path, (0, 0))
            extra = (f"  ← {i.src}" if i.src else "") + (f"  [{i.note}]" if i.note else "")
            print(f"  +{a:<6} -{d:<6} {i.path}{extra}")


def emit(title, lines):
    print(f"--- {title}")
    print("\n".join(lines) if lines else "(没有差异)")


def upstream_log(ctx, led, it):
    if not led.upstream or led.upstream == ctx.U:
        return
    paths = [it.path] + ([it.src] if it.src else [])
    log = out("log", "--first-parent", "--format=  %h %s", f"{led.upstream}..{ctx.U}", "--", *paths)
    if log:
        print("上游动它的提交:\n" + log)


def show_item(ctx, led, it, full):
    print(f"\n=== {it.kind}  {it.path}" + (f"  (由 {it.src} 改名来)" if it.src else "")
          + (f"  [{it.note}]" if it.note else ""))
    print(KINDS[it.kind])
    for _, t in led.issues.get(it.path, []):
        print(f"审过时记的问题: {t}")
    k = it.kind
    if k == "deleted" and not full:
        n = git("cat-file", "-p", it.b).count(b"\n")
        stem = os.path.basename(it.path).split(".")[0]
        print(f"上游版 {n} 行, 原文加 --full 看; 残留引用: git grep -n -w {stem} {ctx.T[:12]}")
    elif k in ("added", "modified", "deleted"):
        emit(f"基准 {ctx.B[:10]} → fork {ctx.T[:10]}", blob_diff(it.b, it.f))
    elif k == "fork-changed":
        emit("上次审过的 fork 版 → 现在的 fork 版", blob_diff(it.af, it.f))
    elif k == "dropped-upstream":
        upstream_log(ctx, led, it)
        emit("上游这次的改动 (fork 版没跟)", blob_diff(it.ab, it.b))
    elif k == "replayed":
        upstream_log(ctx, led, it)
        emit("上游这次的改动", blob_diff(it.ab, it.b))
        up = ranges(blob_diff(it.ab, it.b, 0), "new")
        # 远近按改动行算 (-U0, 与 queue 里的距离一致), 打出来的块带上下文
        close = [r for r in ranges(blob_diff(it.b, it.f, 0), "old") if any(gap(r, u) <= NEAR for u in up)]
        hs = hunks_of(blob_diff(it.b, it.f))
        near = [h for h in hs if any(gap(r, c) == 0 for r in ranges(h[:1], "old") for c in close)]
        emit(f"离上游改动 {NEAR} 行以内的 fork 块 ({len(near)}/{len(hs)}, 其余没变也离得远)",
             [l for h in near for l in h])
    elif k == "remerged":
        upstream_log(ctx, led, it)
        was, now = norm(blob_diff(it.ab, it.af, 0)) or [], norm(blob_diff(it.b, it.f, 0)) or []
        emit("fork 补丁的变化 (上次审过 → 现在, 只比改动行; 完整补丁加 --full)",
             list(difflib.unified_diff(was, now, lineterm="", n=2))[2:])
        emit("上游这次的改动", blob_diff(it.ab, it.b))
        if full:
            emit("现在的 fork 补丁", blob_diff(it.b, it.f))
    elif k == "vanished":
        emit("没了的 fork 改动 (上次审过的基准 → fork)", blob_diff(it.ab, it.af))
        emit("上游这次的改动", blob_diff(it.ab, it.b))


def cmd_show(ctx, led, args):
    if not args.paths:
        raise Fail("要给路径 (`.` 表示全部)")
    items = select(classify(ctx, led)[0], args.kind, args.paths)
    if not items:
        print("没有匹配的待审文件")
    for it in items:
        show_item(ctx, led, it, args.full)


def update_ledger(ctx, verb, note, mutate):
    """读台账 → 现算分类 → mutate 改 → CAS 写回; 被别的会话抢先就按新台账重来。
    mutate(led, items, pairs, issues) 返回 (摘要, 新的上游基点或 None), 没东西可改返回 None。"""
    for _ in range(5):
        led = Ledger()
        items, _, moves = classify(ctx, led)
        pairs = dict(led.pairs)
        issues = collections.defaultdict(list, {p: list(v) for p, v in led.issues.items()})
        res = mutate(led, items, pairs, issues)
        if res is None:
            print("没有匹配的待审文件, 台账没动")
            return
        summary, upstream = res
        # 顺手收拾: 上游已吸收的不再记; 改名跟过去的换成新路径
        for p in list(pairs):
            if p not in ctx.delta and ctx.fork.get(p) == pairs[p][1]:
                del pairs[p]
        for old, new, b, f in moves:
            pairs.pop(old, None)
            pairs[new] = (b, f)
        msg = (f"{verb}: {note or '(没写说明)'}\n\nrev {ctx.T} {subject(ctx.T)}\nupstream {ctx.U}\nbase {ctx.B}\n\n{summary}\n")
        new = write_ledger(led, pairs, issues, upstream or led.upstream or ctx.U, msg)
        if new:
            print(f"{summary}\n台账 → {new[:12]}")
            return
    raise Fail("台账一直被别的会话同时改, 重试 5 次都没写进去")


def need_rev(args):
    if not args.rev:
        raise Fail("要 --rev <审的那个提交>: 用 status / queue 打出来的「目标」。别省成 HEAD —— 审的过程中 HEAD 可能已经变了, "
                   "台账记的是 --rev 那一版的内容, 之后改过的部分会照样回到队列")
    if not args.paths:
        raise Fail("要给路径 (`.` 表示全部)")


def cmd_approve(ctx, args):
    need_rev(args)
    exact ={s.replace("\\", "/").strip("/") for s in args.paths}

    def mutate(led, items, pairs, issues):
        sel = select(items, args.kind, args.paths)
        # 记过问题的文件内容变了 (修过) 才会回到队列, 审过就关掉; 内容没变的只有点名到这个文件才关, 整目录 approve 不顺带关
        cleared = [p for p in issues if p in {i.path for i in sel} or p in exact]
        if not sel and not cleared:
            return None
        for i in sel:
            if i.f == i.b:
                pairs.pop(i.path, None)
            else:
                pairs[i.path] = (i.b, i.f)
            if i.src:
                pairs.pop(i.src, None)
        for p in cleared:
            del issues[p]
        c = collections.Counter(i.kind for i in sel)
        s = f"审过 {len(sel)} 个文件 (" + ", ".join(f"{k} {c[k]}" for k in KINDS if c[k]) + ")" if sel else "审过 0 个文件"
        return s + (f"; 关掉 {len(cleared)} 个文件的问题" if cleared else ""), None

    update_ledger(ctx, "approve", args.message, mutate)


def cmd_issue(ctx, args):
    need_rev(args)
    if not args.message:
        raise Fail("issue 要 -m <问题>")
    text = " ".join(args.message.split())
    wanted = sorted({s.replace("\\", "/").strip("/") for s in args.paths})

    def mutate(led, items, pairs, issues):
        pending = {i.path: i for i in items}
        # 问题记在具体文件上, 不接受目录和通配
        targets = [p for p in wanted if p in ctx.delta or p in pending]
        unknown = [p for p in wanted if p not in targets]
        if unknown:
            raise Fail(f"这些路径不在 fork 改动里 (要写完整的文件路径): {', '.join(unknown)}")
        for p in targets:
            b, f = ctx.base.get(p), ctx.fork.get(p)
            if b == f:
                pairs.pop(p, None)
            else:
                pairs[p] = (b, f)
            if p in pending and pending[p].src:
                pairs.pop(pending[p].src, None)
            issues[p].append((f, text))
        return f"{len(targets)} 个文件记了问题: {text}", None

    update_ledger(ctx, "issue", text, mutate)


def decl_names(lines):
    for l in lines:
        m = DECL.match(l)
        if m and len(m[1].expandtabs(4)) <= 4:
            name = m[2].rsplit(".", 1)[-1]
            if len(name) >= 4 and name not in STOP:
                yield name


def refs(lines):
    return {next(g for g in m if g) for l in lines for m in REF.findall(l)} - STOP


def cat_many(specs):
    """一次 cat-file --batch 取很多 blob (sha 或 rev:path) → {spec: 文本}; 取不到的不在结果里。"""
    specs = list(dict.fromkeys(specs))
    if not specs:
        return {}
    data = git("cat-file", "--batch", input=("\n".join(specs) + "\n").encode("utf-8"))
    res, pos = {}, 0
    for spec in specs:
        nl = data.index(b"\n", pos)
        header, pos = data[pos:nl].decode("utf-8", "replace"), nl + 1
        if header.endswith(" missing") or header.endswith(" ambiguous"):
            continue
        size = int(header.rsplit(" ", 1)[1])
        res[spec] = data[pos:pos + size].decode("utf-8", "replace")
        pos += size + 1
    return res


def package_of(text):
    m = PACKAGE.search(text)
    return m[1] if m else ""


def links_of(text):
    """这个文件直接看得到哪些包里的声明: 自己的包 + import 进来的包。"""
    res = {package_of(text)} | {i.replace("`", "").rsplit(".", 1)[0] for i in IMPORT.findall(text)}
    res.discard("")
    return res


def touched_names(text, lines, side):
    """补丁 lines 的改动落在 text (补丁 old / new 那一侧的文件) 的哪些成员级 / 顶层声明里, 加上被改的声明行本身。"""
    table, cur = [None], None
    for l in text.split("\n"):
        m = DECL.match(l)
        if m and len(m[1].expandtabs(4)) <= 4:
            cur = m[2].rsplit(".", 1)[-1]
        table.append(cur)
    names = set()
    for s, e in ranges(lines, side):
        names.update(table[n] for n in range(max(s, 1), min(e, len(table) - 1) + 1) if table[n])
    names.update(decl_names(l[1:] for l in lines if l.startswith("-" if side == "old" else "+")))
    return {n for n in names if len(n) >= 4 and n not in STOP}


class ForkIndex:
    """fork 的 Kotlin 代码: 每个文件看得到哪些包、新增行里用了哪些名字; fork 改过的上游文件改到了哪些声明。"""

    def __init__(self, ctx):
        files = [p for p in ctx.delta if p.endswith(KOTLIN) and ctx.fork.get(p)
                 and not ctx.rules.skipped(p) and not TEST.search(p)]
        texts = cat_many(ctx.fork[p] for p in files)
        patch = split_patch(git("diff-tree", "-r", "-p", "-U0", "--no-renames", "--no-color", ctx.B, ctx.T)
                            .decode("utf-8", "replace"))
        self.sees = collections.defaultdict(set)
        self.words = {}
        self.touched = collections.defaultdict(dict)
        for p in files:
            text, lines = texts.get(ctx.fork[p], ""), patch.get(p, [])
            for pkg in links_of(text):
                self.sees[pkg].add(p)
            self.words[p] = refs(l[1:] for l in lines if l.startswith("+"))
            if ctx.base.get(p):
                names = touched_names(text, lines, "new")
                if names and package_of(text):
                    self.touched[package_of(text)][p] = names


def brief(paths, limit=4):
    names = sorted(os.path.basename(p) for p in paths)
    return ", ".join(names[:limit]) + (f" 等 {len(names)} 个" if len(names) > limit else "")


def cmd_upstream(ctx, led, args):
    if args.approve:
        if not args.rev:
            raise Fail("upstream --approve 要 --rev <目标>: 记的是这个提交所在的上游")

        def mutate(led, items, pairs, issues):
            if led.upstream == ctx.U:
                return None
            return f"上游交互审到 {ctx.U[:12]} {subject(ctx.U)}", ctx.U

        update_ledger(ctx, "upstream", args.message, mutate)
        return
    since = args.since or led.upstream
    if not since:
        raise Fail("台账里还没记上游基点 (第一次 approve 时记下当时的上游); 看某一段用 --since <上游提交>")
    since = out("rev-parse", "--verify", since + "^{commit}")
    commits = out("log", "--first-parent", "--reverse", "--format=%H%x09%h%x09%s", f"{since}..{ctx.U}").splitlines()
    print(f"上游 {since[:12]}..{ctx.U[:12]}: {len(commits)} 条。只有上游改、fork 没碰的文件, 按包和名字查 fork 有没有连着它")
    if not commits:
        return
    covered = ctx.delta | led.pairs.keys()
    idx = ForkIndex(ctx)
    hit, cov_only, none = [], [], []
    for line in commits:
        full, short, subj = line.split("\t", 2)
        patch = split_patch(git("diff-tree", "-r", "-p", "-U0", "--no-renames", "--no-color", f"{full}^", full)
                            .decode("utf-8", "replace"))
        files = [p for p in patch if not ctx.rules.skipped(p)
                 and not (ctx.fork.get(p) is None and ctx.rules.kept_deleted(p))]
        cov = [p for p in files if p in covered]
        mine = [p for p in files if p not in covered and p.endswith(KOTLIN) and not TEST.search(p)]
        olds = cat_many(f"{full}^:{p}" for p in mine)
        news = cat_many(f"{full}:{p}" for p in mine)
        uses, calls = [], collections.defaultdict(lambda: (set(), set()))
        for p in mine:
            old, new = olds.get(f"{full}^:{p}"), news.get(f"{full}:{p}")
            if old is not None and package_of(old):
                names = touched_names(old, patch[p], "old")
                users = {fp: names & idx.words[fp] for fp in idx.sees.get(package_of(old), ()) if names & idx.words[fp]}
                if users:
                    uses.append((p, users))
            if new is not None:
                # 它自己声明的名字指的是它自己
                plus = refs(l[1:] for l in patch[p] if l.startswith("+")) - set(decl_names(new.split("\n")))
                for pkg in links_of(new):
                    for g, names in idx.touched.get(pkg, {}).items():
                        if names & plus:
                            calls[g][0].update(names & plus)
                            calls[g][1].add(p)
        print(f"\n{short} {subj}")
        if not files:
            print("   只改了 skip / keep-deleted 规则里的文件")
            none.append(short)
            continue
        print(f"   改了 {len(files)} 个文件" + (f", 其中 {len(cov)} 个 fork 也改过 (在逐文件的待审项里看)" if cov else ""))
        if not uses and not calls:
            print("   其余只有上游改的文件, fork 代码没连着")
            (cov_only if cov else none).append(short)
            continue
        for p, users in uses:
            names = sorted(set().union(*users.values()))
            print(f"   fork 用到上游改过的 {os.path.basename(p)}: {', '.join(names[:6])}"
                  + (f" 等 {len(names)} 个" if len(names) > 6 else "") + f" ← {brief(users)}")
        for g, (names, callers) in sorted(calls.items()):
            print(f"   上游新代码调到 fork 改过的 {os.path.basename(g)}: {', '.join(sorted(names)[:6])} ← 上游 {brief(callers)}")
        hit.append(short)
    print(f"\n小结: {len(hit)} 条连着 fork 的代码要看" + (f" ({' '.join(hit)})" if hit else "")
          + (f"; {len(cov_only)} 条只碰到 fork 改过的文件 (逐文件项里看)" if cov_only else "") + f"; {len(none)} 条无交集")
    print(f"对完: upstream --approve --rev {ctx.T[:12]} -m <说明>")


def cmd_compact(args):
    led = Ledger()
    if not led.commit:
        raise Fail("还没有台账")
    new = out("commit-tree", led.commit + "^{tree}", "-F", "-",
              input=f"compact: 去掉台账历史, 内容同 {led.commit}\n".encode("utf-8"))
    r = subprocess.run(["git", "update-ref", "-m", "review compact", LEDGER, new, led.commit], capture_output=True)
    if r.returncode:
        raise Fail("台账刚被别的会话改过, 再跑一次")
    print(f"台账 {led.commit[:12]} → {new[:12]} (没有父提交了; 旧对象等 git gc 回收)")


def main(argv=None):
    global LEDGER
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--upstream", default=os.environ.get("UPSTREAM", "upstream/main"))
    ap.add_argument("--rules", default=os.path.join(HERE, "rules.txt"))
    ap.add_argument("--ledger", default=LEDGER, help="台账的 ref; 试验用另起一个, 别动正式的")
    sub = ap.add_subparsers(dest="cmd")

    def kind_arg(p):
        p.add_argument("--kind", help="只看这些种类, 逗号隔开: " + ", ".join(KINDS))

    p = sub.add_parser("status")
    p.add_argument("--rev", default="HEAD")
    p = sub.add_parser("queue")
    p.add_argument("--rev", default="HEAD")
    kind_arg(p)
    p.add_argument("--group", action="store_true", help="按模块汇总 (首次全量时切批用)")
    p.add_argument("--tsv", action="store_true", help="种类 路径 +行 -行 改名来源, 给脚本读")
    p.add_argument("paths", nargs="*")
    p = sub.add_parser("show")
    p.add_argument("--rev", default="HEAD")
    kind_arg(p)
    p.add_argument("--full", action="store_true", help="deleted 打出原文, remerged 打出完整补丁")
    p.add_argument("paths", nargs="*")
    p = sub.add_parser("approve")
    p.add_argument("--rev")
    kind_arg(p)
    p.add_argument("-m", "--message")
    p.add_argument("paths", nargs="*")
    p = sub.add_parser("issue")
    p.add_argument("--rev")
    p.add_argument("-m", "--message")
    p.add_argument("paths", nargs="*")
    p = sub.add_parser("upstream")
    p.add_argument("--rev")
    p.add_argument("--since", help="从这个上游提交起看 (默认台账记的上游基点)")
    p.add_argument("--approve", action="store_true")
    p.add_argument("-m", "--message")
    sub.add_parser("compact")
    args = ap.parse_args(argv)
    LEDGER = args.ledger

    try:
        os.chdir(out("rev-parse", "--show-toplevel"))
        if args.cmd == "compact":
            return cmd_compact(args) or 0
        rules = Rules(args.rules)
        ctx = Ctx(getattr(args, "rev", None) or "HEAD", args.upstream, rules)
        cmd = args.cmd or "status"
        if cmd == "approve":
            cmd_approve(ctx, args)
        elif cmd == "issue":
            cmd_issue(ctx, args)
        else:
            {"status": cmd_status, "queue": cmd_queue, "show": cmd_show, "upstream": cmd_upstream}[cmd](ctx, Ledger(), args)
        return 0
    except Fail as e:
        print(f"review: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
