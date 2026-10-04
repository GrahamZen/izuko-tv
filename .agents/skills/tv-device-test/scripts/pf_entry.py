"""分析进页 Perfetto 轨迹: 主线程长片段; 进页窗口里主线程状态 (Running / Runnable / Sleeping / D) 与阻塞点;
主线程 Runnable 时谁在占 CPU; 窗口内各线程 CPU; 主线程最长的子片段.
窗口: 从第一个 >100ms 的主线程片段起, 到 WINDOW 秒 (默认 3).
用法: pf_entry.py <trace> [window_s]"""
import collections, sys
sys.stdout.reconfigure(encoding="utf-8")
from perfetto.trace_processor import TraceProcessor
path = sys.argv[1]
WINDOW = float(sys.argv[2]) if len(sys.argv) > 2 else 3.0
tp = TraceProcessor(trace=path)
q = lambda s: list(tp.query(s))
t0 = q("select start_ts from trace_bounds")[0].start_ts
pid = [x.pid for x in q("select pid, name from process where name = 'io.github.grahamzen.anime.tv'")][0]
main = q(f"select utid from thread join process using(upid) where pid={pid} and tid={pid}")[0].utid
upid = q(f"select upid from process where pid={pid}")[0].upid
rel = lambda ts: (ts - t0) / 1e9

print("== 主线程上 >50ms 的顶层片段")
longs = q(f"""select s.ts, s.dur, s.name from slice s join thread_track tt on s.track_id = tt.id
              where tt.utid={main} and s.depth=0 and s.dur > 50e6 order by s.ts""")
for r in longs: print(f"  {rel(r.ts):7.3f}s {r.dur/1e6:7.1f}ms {r.name}")
first_long = next((r.ts for r in longs if r.dur > 100e6), longs[0].ts if longs else t0)
w0, w1 = first_long - int(0.2e9), first_long + int(WINDOW * 1e9)
print(f"\n== 窗口 {rel(w0):.3f}s ~ {rel(w1):.3f}s")

states = q(f"""select ts, dur, state, io_wait, blocked_function, waker_utid from thread_state
               where utid={main} and ts < {w1} and ts + dur > {w0}""")
acc = collections.Counter(); blocked = collections.Counter(); wakers = collections.Counter()
def clip(ts, dur): return max(0, min(ts + dur, w1) - max(ts, w0))
for s in states:
    d = clip(s.ts, s.dur); acc[s.state] += d
    if s.state in ("S", "D"):
        blocked[s.blocked_function or "?"] += d
        if s.waker_utid is not None: wakers[s.waker_utid] += d
print("  主线程状态:", ", ".join(f"{k}={v/1e6:.0f}ms" for k, v in acc.most_common()))
names = {r.utid: f"{r.name}({r.tid})" for r in q(f"select utid, tid, thread.name as name from thread")}
print("  睡着时被谁叫醒 (前 8):", ", ".join(f"{names.get(u, u)}={d/1e6:.0f}ms" for u, d in wakers.most_common(8)))
print("  D 状态阻塞点:", ", ".join(f"{k}={v/1e6:.0f}ms" for k, v in blocked.most_common(5)))

# 主线程 Runnable 期间, 各 CPU 上在跑谁
runnable = [(max(s.ts, w0), min(s.ts + s.dur, w1)) for s in states if s.state in ("R", "R+") and clip(s.ts, s.dur) > 0]
comp = collections.Counter()
if runnable:
    sched = q(f"""select ts, dur, utid from sched where ts < {w1} and ts + dur > {w0} and utid != 0""")
    for sc in sched:
        for a, b in runnable:
            o = min(sc.ts + sc.dur, b) - max(sc.ts, a)
            if o > 0: comp[sc.utid] += o
print("  主线程能跑却在等 CPU 时, 占着 CPU 的线程 (前 12):")
for u, d in comp.most_common(12): print(f"     {d/1e6:7.0f}ms  {names.get(u, u)}")

print("\n== 窗口内各线程 CPU (前 15)")
cpu = q(f"""select utid, sum(min(ts + dur, {w1}) - max(ts, {w0})) as t from sched
            where ts < {w1} and ts + dur > {w0} and utid != 0 group by utid order by t desc limit 15""")
for r in cpu: print(f"  {r.t/1e6:7.0f}ms  {names.get(r.utid, r.utid)}")

print("\n== 主线程窗口内最长的片段 (各层, 前 25)")
sl = q(f"""select s.ts, s.dur, s.depth, s.name from slice s join thread_track tt on s.track_id = tt.id
           where tt.utid={main} and s.ts >= {w0} and s.ts < {w1} order by s.dur desc limit 25""")
for r in sl: print(f"  {rel(r.ts):7.3f}s d{r.depth} {r.dur/1e6:7.1f}ms {r.name[:110]}")

print("\n== 主线程窗口内按名字合计 (前 25)")
agg = q(f"""select s.name, count(*) as n, sum(s.dur) as t from slice s join thread_track tt on s.track_id = tt.id
            where tt.utid={main} and s.ts >= {w0} and s.ts < {w1} group by s.name order by t desc limit 25""")
for r in agg: print(f"  {r.t/1e6:7.1f}ms x{r.n:<4} {r.name[:110]}")

print("\n== Izuko 区段 (主线程, 窗口内; 首次出现 / 次数 / 合计 / 最长)")
eab = q(f"""select s.name, min(s.ts) as first, count(*) as n, sum(s.dur) as t, max(s.dur) as mx from slice s join thread_track tt on s.track_id = tt.id
            where tt.utid={main} and s.name like 'Izuko:%' and s.ts >= {w0} and s.ts < {w1} group by s.name order by t desc""")
for r in eab: print(f"  {rel(r.first):7.3f}s  x{r.n:<3} total {r.t/1e6:7.1f}ms  max {r.mx/1e6:7.1f}ms  {r.name}")
print("\n== Izuko 区段里主线程睡着 (S/D) 的时间 (等锁 / runBlocking)")
for r in eab:
    spans = q(f"""select s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id where tt.utid={main} and s.name = '{r.name}' and s.ts >= {w0} and s.ts < {w1}""")
    slept = 0; runnable = 0
    for sp in spans:
        for st in q(f"select ts, dur, state from thread_state where utid={main} and ts < {sp.ts + sp.dur} and ts + dur > {sp.ts}"):
            o = min(st.ts + st.dur, sp.ts + sp.dur) - max(st.ts, sp.ts)
            if st.state in ("S", "D"): slept += o
            elif st.state in ("R", "R+"): runnable += o
    if slept > 5e6 or runnable > 5e6: print(f"  {r.name}: 睡着 {slept/1e6:.0f}ms, 等 CPU {runnable/1e6:.0f}ms")
