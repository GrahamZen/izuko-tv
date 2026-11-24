#!/usr/bin/env python3
"""
iPad (iOS) 编译前, 删掉上游页面里「没有电视版时」的那一支. 只改 CI 检出的这份源码, 不提交.

iPad 的页面都由电视版接管: InstallTvTouchPageVariants 在最外层提供各个 Local*Variant, 上游页面函数里
「没有电视版时走上游布局」那一支在 iPad 上永远不执行. 但这一支在源码里有调用, Kotlin/Native 的 Release 链接
(去虚拟化分析、逃逸分析等全程序分析) 会把它连同只被它用到的代码都算进去; 编译期常量开关不起作用 (常量代进去了,
死分支要到最后 LLVM 才删). 所以这里直接把调用从源码里删掉, 让编译器在分析之前就当它们没人用.

只删 iPad 上确定走不到的分支:
- 「电视版画完就 return」后面的上游布局 (kind = after)
- 「if (电视版) … else 上游」里的 else (kind = else)
详情页窄窗口 (iPad 分屏) 用的单栏布局、设置页故意回到上游页面的那几项 (SettingsScreenVariant 的 legacy) 都在用, 不删.

每条规则要求: 文件在、函数名唯一、定位文字在该函数里恰好一处、形状对 (after 的块里有 return, else 紧跟在块后面).
对不上就报错退出 (CI 失败), 不悄悄编出不一样的东西; 上游改了这些文件时照报错改规则.

用法: ios_trim_upstream_pages.py [仓库根目录] [--check]   (--check 只检查规则, 不改文件)
"""
import re
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Rule:
    path: str
    fn: str
    anchor: str
    kind: str  # 'after' | 'else'
    what: str


RULES = [
    Rule('app/shared/src/commonMain/kotlin/ui/main/MainScreen.kt', 'MainScreenNavigationLayout',
         'if (shellVariant != null) {', 'after', '主框架: 手机 / 桌面的导航外壳'),
    Rule('app/shared/ui-exploration/src/commonMain/kotlin/ui/exploration/ExplorationScreen.kt', 'ExplorationScreen',
         'if (pageVariant != null) {', 'after', '探索页: 手机 / 桌面布局'),
    Rule('app/shared/ui-exploration/src/commonMain/kotlin/ui/exploration/schedule/ScheduleScreen.kt', 'ScheduleScreen',
         'LocalSchedulePageVariant.current', 'after', '时间表: 手机 / 桌面布局'),
    Rule('app/shared/ui-exploration/src/commonMain/kotlin/ui/exploration/search/SearchPage.kt', 'SearchPage',
         'if (pageVariant != null) {', 'after', '搜索页: 手机 / 桌面布局'),
    Rule('app/shared/ui-subject/src/commonMain/kotlin/ui/subject/collection/CollectionPage.kt', 'CollectionPage',
         'if (pageVariant != null) {', 'after', '收藏页: 手机 / 桌面布局'),
    Rule('app/shared/ui-subject/src/commonMain/kotlin/ui/subject/details/SubjectDetailsPage.kt', 'SubjectDetailsPage',
         'if (useTvImmersive && pageVariant != null) {', 'else', '详情页: 桌面多栏布局 (窄窗口的单栏布局保留)'),
    Rule('app/shared/ui-subject/src/commonMain/kotlin/ui/subject/details/SubjectDetailsPage.kt',
         'PlaceholderSubjectDetailsPage',
         'if (variant != null) {', 'after', '详情页加载占位: 多栏 / 单栏骨架'),
    Rule('app/shared/src/commonMain/kotlin/ui/subject/episode/EpisodePage.kt', 'EpisodeScreenContent',
         'if (screenVariant != null) {', 'else', '播放页: 上游播放器'),
]


class TrimError(Exception):
    pass


class Scan:
    """Kotlin 源码的括号结构: 跳过注释、字符串 (含 ${} 模板)、字符字面量与反引号标识符."""

    def __init__(self, text: str):
        self.text = text
        self.pairs: dict[int, int] = {}     # 开括号 ( 或 { 的位置 -> 对应闭括号的位置
        self.parent: dict[int, int] = {}    # { 的位置 -> 外面一层 { 的位置 (最外层为 -1)
        self.code = bytearray(len(text))    # 1 = 这个字符在代码里 (不在注释 / 字符串里)
        self._scan()

    def _scan(self):
        t, n = self.text, len(self.text)
        i = 0
        stack: list[tuple[str, int]] = []   # ('{' | '(' | 'tpl', 位置)
        modes = ['code']
        while i < n:
            mode = modes[-1]
            c = t[i]
            if mode == 'code':
                if t.startswith('//', i):
                    j = t.find('\n', i)
                    i = n if j < 0 else j
                    continue
                if t.startswith('/*', i):
                    depth, i = 1, i + 2
                    while depth and i < n:
                        if t.startswith('/*', i):
                            depth, i = depth + 1, i + 2
                        elif t.startswith('*/', i):
                            depth, i = depth - 1, i + 2
                        else:
                            i += 1
                    continue
                if t.startswith('"""', i):
                    modes.append('raw')
                    i += 3
                    continue
                if c == '"':
                    modes.append('str')
                    i += 1
                    continue
                if c == "'":
                    j = i + 1
                    if j < n and t[j] == '\\':
                        j += 2
                    else:
                        j += 1
                    while j < n and t[j] != "'":
                        j += 1
                    i = j + 1
                    continue
                if c == '`':
                    j = t.find('`', i + 1)
                    i = n if j < 0 else j + 1
                    continue
                self.code[i] = 1
                if c == '{' or c == '(':
                    if c == '{':
                        outer = next((p for k, p in reversed(stack) if k == '{'), -1)
                        self.parent[i] = outer
                    stack.append((c, i))
                elif c == '}' or c == ')':
                    if not stack:
                        raise TrimError(f'多出来的 {c!r} (第 {t.count(chr(10), 0, i) + 1} 行)')
                    kind, pos = stack.pop()
                    if kind == 'tpl':
                        if c != '}':
                            raise TrimError(f'字符串模板没闭合 (第 {t.count(chr(10), 0, i) + 1} 行)')
                        self.code[i] = 0
                        modes.pop()
                    else:
                        if (kind, c) not in (('{', '}'), ('(', ')')):
                            raise TrimError(f'括号不配对 (第 {t.count(chr(10), 0, i) + 1} 行)')
                        self.pairs[pos] = i
                i += 1
            elif mode == 'str':
                if c == '\\':
                    i += 2
                elif c == '"':
                    modes.pop()
                    i += 1
                elif t.startswith('${', i):
                    stack.append(('tpl', i))
                    modes.append('code')
                    i += 2
                else:
                    i += 1
            else:  # raw
                if t.startswith('"""', i):
                    while i < n and t[i] == '"':
                        i += 1
                    modes.pop()
                elif t.startswith('${', i):
                    stack.append(('tpl', i))
                    modes.append('code')
                    i += 2
                else:
                    i += 1
        if stack or modes != ['code']:
            raise TrimError('文件结尾时还有括号或字符串没闭合')

    def next_code(self, start: int, char: str) -> int:
        """start 起第一个在代码里的 char 的位置."""
        i = start
        while True:
            i = self.text.find(char, i)
            if i < 0:
                return -1
            if self.code[i]:
                return i
            i += 1

    def code_occurrences(self, needle: str, lo: int, hi: int) -> list[int]:
        out, i = [], lo
        while True:
            i = self.text.find(needle, i, hi)
            if i < 0:
                return out
            if self.code[i]:
                out.append(i)
            i += 1

    def line(self, pos: int) -> int:
        return self.text.count('\n', 0, pos) + 1


def function_body(scan: Scan, fn: str) -> tuple[int, int]:
    """函数 fn 的函数体 { 与 } 的位置."""
    hits = [m.start() for m in re.finditer(r'\bfun\s+' + re.escape(fn) + r'\s*\(', scan.text) if scan.code[m.start()]]
    if len(hits) != 1:
        raise TrimError(f'函数 {fn} 应恰好一处, 找到 {len(hits)} 处')
    params_open = scan.next_code(hits[0], '(')
    params_close = scan.pairs[params_open]
    body_open = scan.next_code(params_close, '{')
    between = scan.text[params_close + 1:body_open]
    if '=' in between or '\n\n' in between:
        raise TrimError(f'函数 {fn} 的参数表后面不是函数体')
    return body_open, scan.pairs[body_open]


def plan(rule: Rule, scan: Scan) -> tuple[int, int, str]:
    """要删的区间 [start, end) 与替换成的文字."""
    body_open, body_close = function_body(scan, rule.fn)
    hits = scan.code_occurrences(rule.anchor, body_open, body_close)
    if len(hits) != 1:
        raise TrimError(f'{rule.fn} 里的「{rule.anchor}」应恰好一处, 找到 {len(hits)} 处')
    block_open = scan.next_code(hits[0], '{')
    block_close = scan.pairs[block_open]
    if rule.kind == 'after':
        if scan.parent.get(block_open) != body_open:
            raise TrimError(f'「{rule.anchor}」的块不在 {rule.fn} 的函数体最外层')
        if not scan.code_occurrences('return', block_open, block_close):
            raise TrimError(f'「{rule.anchor}」的块里没有 return, 后面的上游布局不一定走不到')
        start, end = block_close + 1, body_close
        if not scan.text[start:end].strip():
            raise TrimError(f'「{rule.anchor}」后面已经没有代码')
        indent = ' ' * 4
        return start, end, f'\n{indent}// [iOS 编译前删掉] {rule.what} (见 ios_trim_upstream_pages.py)\n'
    if rule.kind == 'else':
        m = re.compile(r'\s*else\s*\{').match(scan.text, block_close + 1)
        if not m or not scan.code[m.end() - 1]:
            raise TrimError(f'「{rule.anchor}」的块后面不是 else {{')
        else_open = m.end() - 1
        else_close = scan.pairs[else_open]
        return block_close + 1, else_close + 1, f' // [iOS 编译前删掉] else: {rule.what} (见 ios_trim_upstream_pages.py)'
    raise TrimError(f'未知的 kind {rule.kind}')


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding='utf-8')
    check_only = '--check' in argv
    args = [a for a in argv if not a.startswith('--')]
    root = Path(args[0]) if args else Path('.')
    by_file: dict[str, list[Rule]] = {}
    for r in RULES:
        by_file.setdefault(r.path, []).append(r)
    failures = 0
    total_removed = 0
    for path, rules in by_file.items():
        f = root / path
        if not f.is_file():
            print(f'::error::{path}: 文件不在')
            failures += 1
            continue
        raw = f.read_bytes()
        text = raw.decode('utf-8')
        try:
            scan = Scan(text)
            cuts = []
            for r in rules:
                try:
                    start, end, repl = plan(r, scan)
                except TrimError as e:
                    print(f'::error file={path}::{r.what}: {e}')
                    failures += 1
                    continue
                cuts.append((start, end, repl, r))
        except TrimError as e:
            print(f'::error file={path}::扫描失败: {e}')
            failures += 1
            continue
        cuts.sort(key=lambda c: c[0])
        for a, b in zip(cuts, cuts[1:]):
            if a[1] > b[0]:
                print(f'::error file={path}::两条规则删的范围重叠')
                failures += 1
                cuts = []
        new = text
        for start, end, repl, r in sorted(cuts, key=lambda c: -c[0]):
            removed = new.count('\n', start, end)
            total_removed += removed
            print(f'{path}:{scan.line(start)}  {r.fn}  删 {removed} 行  ({r.what})')
            new = new[:start] + repl + new[end:]
        try:
            Scan(new)
        except TrimError as e:
            print(f'::error file={path}::删完后括号不配: {e}')
            failures += 1
            continue
        if not check_only and cuts:
            f.write_bytes(new.encode('utf-8'))
    if failures:
        print(f'::error::{failures} 条规则对不上, 没有编译; 上游改了这些文件时照上面的提示改 ios_trim_upstream_pages.py')
        return 1
    print(f'共删 {total_removed} 行 ({len(RULES)} 条规则){" (只检查, 没改文件)" if check_only else ""}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
