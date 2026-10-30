"""数据源搜索录放 (DriveSeasonReplayTest / WebSearchReplayTest) 的结果对照库: 每次录放记下认出了哪些资源, 每条资源标一次对 / 错,
两次结果一比就知道有没有丢了对的、多了错的.

库是一个 SQLite 文件 (默认 <数据目录>/matchdb.sqlite), 只存资源的键与一行说明; 每次录放只记「这次有哪些资源」(两个整数一行), 不复制录放输出.

用例集 (set): season / first / extra (网盘, 读 <数据目录>/drivekw/replay-<set>/season-<标签>.json), web (读 <数据目录>/websearch/result-<标签>.json).
数据目录用 --data 或环境变量 REPLAY_DATA 给.

    uv run --no-project python scripts/replay/matchdb.py import season v7 --note "名字表"   # 录放跑完后记下这次结果
    uv run --no-project python scripts/replay/matchdb.py diff season dated v7               # 对照: 丢了标成对的、多了标成错的会列出来, 有就返回 1
    uv run --no-project python scripts/replay/matchdb.py label wrong season v7 --case 843 --match 惠灵顿 --note 真人剧
    uv run --no-project python scripts/replay/matchdb.py label-diff ok first dated v7 --added --case 51   # 把两次之间多出来的标成对
    uv run --no-project python scripts/replay/matchdb.py unlabeled season v7 --case 274     # 这次结果里还没标的
    uv run --no-project python scripts/replay/matchdb.py runs                                # 记过哪些

标注只跟着资源走 (用例集 + 条目 + 数据源 + 资源键), 与哪次录放无关: 标过一次, 以后每次对照都用得上.
网页资源的键是资源 id (见 web_key), 不带条目名: 同一条资源从别的页出来、挂了别的条目名, 仍是同一条.
"""
import argparse
import json
import os
import re
import sqlite3
import sys
import time

sys.stdout.reconfigure(encoding='utf-8')

DRIVE_SETS = ('season', 'first', 'extra')
SETS = DRIVE_SETS + ('web',)

SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
    id INTEGER PRIMARY KEY,
    set_name TEXT NOT NULL,
    label TEXT NOT NULL,
    imported_at INTEGER NOT NULL,
    note TEXT,
    UNIQUE (set_name, label)
);
CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY,
    set_name TEXT NOT NULL,
    case_id INTEGER NOT NULL,
    case_name TEXT NOT NULL,
    source TEXT NOT NULL,
    key TEXT NOT NULL,
    describe TEXT NOT NULL,
    verdict TEXT CHECK (verdict IN ('ok', 'wrong')),
    note TEXT,
    UNIQUE (set_name, case_id, source, key)
);
CREATE TABLE IF NOT EXISTS results (
    run_id INTEGER NOT NULL,
    item_id INTEGER NOT NULL,
    PRIMARY KEY (run_id, item_id)
) WITHOUT ROWID;
"""


def data_dir(args):
    d = args.data or os.environ.get('REPLAY_DATA')
    if not d:
        sys.exit('数据目录: 给 --data 或设 REPLAY_DATA (含 drivekw/ 与 websearch/ 的那个目录)')
    return d


def connect(args):
    path = args.db or os.path.join(data_dir(args), 'matchdb.sqlite')
    db = sqlite3.connect(path)
    db.executescript(SCHEMA)
    migrate_web_keys(db)
    return db


def read_run(args, set_name, label):
    """录放输出 → [(条目 id, 条目名, 数据源, 资源键, 说明)]"""
    base = data_dir(args)
    if set_name in DRIVE_SETS:
        rows = json.load(open(os.path.join(base, 'drivekw', f'replay-{set_name}', f'season-{label}.json'), encoding='utf-8'))
        for r in rows:
            for key, describe in r['matches'].items():
                yield r['id'], r['name'], r['site'], key, describe
    else:
        rows = json.load(open(os.path.join(base, 'websearch', f'result-{label}.json'), encoding='utf-8'))
        for r in rows:
            for media in r.get('included') or []:
                yield r['id'], r['name'], r['source'], web_key(media), media


def web_key(describe):
    """网页资源的键: 说明 (条目名 | 集名 | 集范围 | 资源 id) 里的资源 id. 同一条资源常从几个页出来 (站点把同系列的别的视频列在每一页),
    去重后留下哪一页的条目名看哪页先到, 键里带着条目名就会被当成两条."""
    return describe.rsplit(' | ', 1)[-1]


def migrate_web_keys(db):
    """旧库的网页资源以整段说明为键: 改成资源 id ([web_key]), 同一条资源的几行并成一行 (标注留着, 各次结果指向留下的那行).
    几行标得不一样 (一行对一行错) 时都留着原样不并, 列出来要人看."""
    rows = db.execute("SELECT id, case_id, source, key, verdict FROM items WHERE set_name = 'web' AND key LIKE '% | %'").fetchall()
    if not rows:
        return
    groups = {}
    for item_id, case_id, source, key, verdict in rows:
        groups.setdefault((case_id, source, web_key(key)), []).append((item_id, verdict))
    conflicts = 0
    with db:
        for (case_id, source, new_key), members in groups.items():
            existing = db.execute("SELECT id, verdict FROM items WHERE set_name = 'web' AND case_id = ? AND source = ? AND key = ?",
                                  (case_id, source, new_key)).fetchone()
            members = members + ([existing] if existing else [])
            verdicts = {v for _, v in members if v}
            if len(verdicts) > 1:
                conflicts += 1
                print(f'标注不一致, 没并: {case_id} [{source}] {new_key}')
                continue
            keep = next((i for i, v in members if v), members[0][0])
            for item_id, _ in members:
                if item_id == keep: continue
                db.execute('INSERT OR IGNORE INTO results (run_id, item_id) SELECT run_id, ? FROM results WHERE item_id = ?', (keep, item_id))
                db.execute('DELETE FROM results WHERE item_id = ?', (item_id,))
                db.execute('DELETE FROM items WHERE id = ?', (item_id,))
            db.execute('UPDATE items SET key = ?, verdict = ? WHERE id = ?', (new_key, next(iter(verdicts), None), keep))
    print(f'网页资源改以资源 id 为键: {len(rows)} 行并成 {len(groups) - conflicts} 行, 标注不一致 {conflicts} 处')


def run_id(db, set_name, label):
    row = db.execute('SELECT id FROM runs WHERE set_name = ? AND label = ?', (set_name, label)).fetchone()
    if row is None:
        sys.exit(f'没记过 {set_name} {label}, 先 import')
    return row[0]


def cmd_import(args):
    db = connect(args)
    with db:
        old = db.execute('SELECT id FROM runs WHERE set_name = ? AND label = ?', (args.set, args.label)).fetchone()
        if old:
            db.execute('DELETE FROM results WHERE run_id = ?', (old[0],))
            db.execute('DELETE FROM runs WHERE id = ?', (old[0],))
        rid = db.execute('INSERT INTO runs (set_name, label, imported_at, note) VALUES (?, ?, ?, ?)',
                         (args.set, args.label, int(time.time()), args.note)).lastrowid
        count = 0
        for case_id, case_name, source, key, describe in read_run(args, args.set, args.label):
            # 说明随最近一次 (网页资源的条目名可能换了一页的)
            db.execute('INSERT INTO items (set_name, case_id, case_name, source, key, describe) VALUES (?, ?, ?, ?, ?, ?) '
                       'ON CONFLICT (set_name, case_id, source, key) DO UPDATE SET describe = excluded.describe',
                       (args.set, case_id, case_name, source, key, describe))
            item = db.execute('SELECT id FROM items WHERE set_name = ? AND case_id = ? AND source = ? AND key = ?',
                              (args.set, case_id, source, key)).fetchone()[0]
            db.execute('INSERT OR IGNORE INTO results (run_id, item_id) VALUES (?, ?)', (rid, item))
            count += 1
    print(f'{args.set} {args.label}: {count} 条')


def items_of(db, rid):
    return {row[0]: row[1:] for row in db.execute(
        'SELECT i.id, i.case_id, i.case_name, i.source, i.describe, i.verdict, i.note FROM results r JOIN items i ON i.id = r.item_id WHERE r.run_id = ?',
        (rid,))}


def filtered(items, args):
    for item_id, (case_id, case_name, source, describe, verdict, note) in items.items():
        if args.case and case_id not in args.case: continue
        if getattr(args, 'source', None) and args.source not in source: continue
        if getattr(args, 'match', None) and args.match not in describe: continue
        yield item_id, (case_id, case_name, source, describe, verdict, note)


EPISODE_ARROW = re.compile(r'→ ([^\s(]+)')


def episode_of(describe):
    """资源对应的集: 网盘说明末尾的「→ 01」, 网页说明「标题 | 集名 | 集的范围 | id」里的范围 (「01..01」, 标题里也可能有「 | 」, 从右数); 认不出为 None"""
    if ' | ' in describe:
        parts = describe.rsplit(' | ', 2)
        if len(parts) < 3 or '..' not in parts[1]: return None
        start, _, end = parts[1].partition('..')
        return normalize_episode(start) if start == end or not end else f'{normalize_episode(start)}..{normalize_episode(end)}'
    found = EPISODE_ARROW.findall(describe)
    return normalize_episode(found[-1]) if found else None


def normalize_episode(text):
    stripped = text.lstrip('0')
    return stripped if stripped and not stripped.startswith('.') else ('0' + stripped if stripped else '0')


def coverage(items):
    """(条目, 集) → 没标成错的资源条数"""
    counts = {}
    for case_id, case_name, source, describe, verdict, note in items.values():
        episode = episode_of(describe)
        if episode is None or verdict == 'wrong': continue
        counts[(case_id, case_name, episode)] = counts.get((case_id, case_name, episode), 0) + 1
    return counts


def group_of(describe):
    """按所在的那一层分组: 网盘是文件之上的路径, 网页是条目名 (说明里「 | 」前的部分)"""
    if ' / ' in describe:
        folder, file = describe.rsplit(' / ', 1)
        return folder, file
    return describe.split(' | ')[0], ''


def print_items(title, rows, limit):
    """按条目、数据源、所在的那一层合并着列: 几条 + 一个例子"""
    if not rows: return
    print(f'\n{title}: {len(rows)}')
    groups = {}
    for _, (case_id, case_name, source, describe, verdict, note) in sorted(rows, key=lambda r: (r[1][0], r[1][2], r[1][3])):
        folder, file = group_of(describe)
        g = groups.setdefault((case_id, case_name, source, folder), [0, file, note])
        g[0] += 1
    for i, ((case_id, case_name, source, folder), (n, file, note)) in enumerate(groups.items()):
        if i == limit:
            print(f'  ... 另 {len(groups) - limit} 组')
            break
        where = folder if len(folder) <= 110 else folder[:40] + ' … ' + folder[-66:]
        print(f'  {case_id} {case_name} [{source}] {n} 条: {where}' + (f'  例 {file[:80]}' if file else '') + (f'  ({note})' if note else ''))


def cmd_diff(args):
    db = connect(args)
    a = items_of(db, run_id(db, args.set, args.old))
    b = items_of(db, run_id(db, args.set, args.new))
    lost = [(k, v) for k, v in filtered(a, args) if k not in b]
    added = [(k, v) for k, v in filtered(b, args) if k not in a]
    groups = {
        '丢了标成对的': [r for r in lost if r[1][4] == 'ok'],
        '多了标成错的': [r for r in added if r[1][4] == 'wrong'],
        '多了还没标的': [r for r in added if r[1][4] is None],
        '丢了还没标的': [r for r in lost if r[1][4] is None],
        '丢了标成错的 (改对了)': [r for r in lost if r[1][4] == 'wrong'],
        '多了标成对的 (改对了)': [r for r in added if r[1][4] == 'ok'],
    }
    print(f'{args.set} {args.old} -> {args.new}: 少了 {len(lost)}, 多了 {len(added)}')
    for title, rows in groups.items():
        print_items(title, rows, args.limit)
    # 逐集看: 上次有没标成错的资源、这次一条都没有的集, 比丢了几份副本要紧
    before = coverage(dict(filtered(a, args)))
    after = coverage(dict(filtered(b, args)))
    uncovered = sorted(k for k, n in before.items() if n > 0 and after.get(k, 0) == 0)
    newly = sorted(k for k, n in after.items() if n > 0 and before.get(k, 0) == 0)
    if uncovered:
        print(f'\n这次一条可用资源都没了的集: {len(uncovered)}')
        for case_id, case_name, episode in uncovered[:args.limit]:
            print(f'  {case_id} {case_name} 第 {episode} 集: 上次 {before[(case_id, case_name, episode)]} 条')
    if newly:
        print(f'\n这次才有资源的集: {len(newly)}')
        for case_id, case_name, episode in newly[:args.limit]:
            print(f'  {case_id} {case_name} 第 {episode} 集: {after[(case_id, case_name, episode)]} 条')
    bad = len(groups['丢了标成对的']) + len(groups['多了标成错的']) + len(uncovered)
    print(f'\n要看的: 丢了对的 {len(groups["丢了标成对的"])}, 多了错的 {len(groups["多了标成错的"])}, '
          f'没了资源的集 {len(uncovered)}, 没标的 {len(groups["多了还没标的"]) + len(groups["丢了还没标的"])}')
    sys.exit(1 if bad else 0)


def set_verdict(db, ids, verdict, note):
    with db:
        for item_id in ids:
            db.execute('UPDATE items SET verdict = ?, note = COALESCE(?, note) WHERE id = ?', (verdict, note, item_id))
    print(f'标成 {verdict}: {len(ids)} 条')


def cmd_label(args):
    db = connect(args)
    rows = list(filtered(items_of(db, run_id(db, args.set, args.label)), args))
    set_verdict(db, [k for k, _ in rows], args.verdict, args.note)


def cmd_label_diff(args):
    db = connect(args)
    a = items_of(db, run_id(db, args.set, args.old))
    b = items_of(db, run_id(db, args.set, args.new))
    if args.added:
        rows = [(k, v) for k, v in filtered(b, args) if k not in a]
    else:
        rows = [(k, v) for k, v in filtered(a, args) if k not in b]
    set_verdict(db, [k for k, _ in rows], args.verdict, args.note)


def cmd_unlabeled(args):
    db = connect(args)
    rows = [(k, v) for k, v in filtered(items_of(db, run_id(db, args.set, args.label)), args) if v[4] is None]
    print_items(f'{args.set} {args.label} 还没标的', rows, args.limit)


def cmd_runs(args):
    db = connect(args)
    q = 'SELECT r.set_name, r.label, r.imported_at, r.note, COUNT(x.item_id), SUM(i.verdict = \'ok\'), SUM(i.verdict = \'wrong\') ' \
        'FROM runs r LEFT JOIN results x ON x.run_id = r.id LEFT JOIN items i ON i.id = x.item_id '
    params = ()
    if args.set:
        q += 'WHERE r.set_name = ? '
        params = (args.set,)
    q += 'GROUP BY r.id ORDER BY r.set_name, r.imported_at'
    for set_name, label, at, note, n, ok, wrong in db.execute(q, params):
        print(f'{set_name:6} {label:10} {time.strftime("%m-%d %H:%M", time.localtime(at))}  {n:6} 条 (对 {ok or 0}, 错 {wrong or 0})  {note or ""}')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--db')
    ap.add_argument('--data')
    sub = ap.add_subparsers(dest='cmd', required=True)

    def filters(p):
        p.add_argument('--case', type=int, action='append', help='只看这几部 (条目 id, 可重复)')
        p.add_argument('--source', help='数据源名里含这几个字')
        p.add_argument('--match', help='资源说明里含这几个字')

    p = sub.add_parser('import'); p.add_argument('set', choices=SETS); p.add_argument('label'); p.add_argument('--note'); p.set_defaults(fn=cmd_import)
    p = sub.add_parser('diff'); p.add_argument('set', choices=SETS); p.add_argument('old'); p.add_argument('new')
    p.add_argument('--limit', type=int, default=30); filters(p); p.set_defaults(fn=cmd_diff)
    p = sub.add_parser('label'); p.add_argument('verdict', choices=('ok', 'wrong')); p.add_argument('set', choices=SETS); p.add_argument('label')
    p.add_argument('--note'); filters(p); p.set_defaults(fn=cmd_label)
    p = sub.add_parser('label-diff'); p.add_argument('verdict', choices=('ok', 'wrong')); p.add_argument('set', choices=SETS)
    p.add_argument('old'); p.add_argument('new'); g = p.add_mutually_exclusive_group(required=True)
    g.add_argument('--added', action='store_true'); g.add_argument('--lost', action='store_true')
    p.add_argument('--note'); filters(p); p.set_defaults(fn=cmd_label_diff)
    p = sub.add_parser('unlabeled'); p.add_argument('set', choices=SETS); p.add_argument('label')
    p.add_argument('--limit', type=int, default=50); filters(p); p.set_defaults(fn=cmd_unlabeled)
    p = sub.add_parser('runs'); p.add_argument('set', nargs='?', choices=SETS); p.set_defaults(fn=cmd_runs)
    args = ap.parse_args()
    args.fn(args)


if __name__ == '__main__':
    main()
