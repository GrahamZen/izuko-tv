"""主线程在 draw 里睡着 (等 RenderThread) 的长帧: 同一时段 RenderThread 上的片段与状态.
用法: pf_render.py <trace> [min_sleep_ms=80]"""
import collections, sys
sys.stdout.reconfigure(encoding="utf-8")
from perfetto.trace_processor import TraceProcessor
path = sys.argv[1]
MIN = float(sys.argv[2]) if len(sys.argv) > 2 else 80
tp = TraceProcessor(trace=path)
q = lambda s: list(tp.query(s))
t0 = q("select start_ts from trace_bounds")[0].start_ts
pid = [x.pid for x in q("select pid, name from process where name = 'io.github.grahamzen.anime.tv'")][0]
main = q(f"select utid from thread join process using(upid) where pid={pid} and tid={pid}")[0].utid
rt = q(f"select utid from thread join process using(upid) where pid={pid} and thread.name = 'RenderThread'")[0].utid
frames = q(f"""select s.id, s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id
               where tt.utid={main} and s.depth=0 and s.name like 'Choreographer#doFrame%' and s.dur > 60e6 order by s.ts""")
for f in frames:
    a, b = f.ts, f.ts + f.dur
    sleep = sum(min(x.ts + x.dur, b) - max(x.ts, a) for x in q(f"select ts, dur, state from thread_state where utid={main} and ts < {b} and ts + dur > {a}") if x.state in ("S", "D"))
    if sleep < MIN * 1e6: continue
    st = collections.Counter()
    for x in q(f"select ts, dur, state from thread_state where utid={rt} and ts < {b} and ts + dur > {a}"):
        st[x.state] += min(x.ts + x.dur, b) - max(x.ts, a)
    print(f"\n### {(a - t0)/1e9:6.2f}s frame {f.dur/1e6:.0f}ms, main slept {sleep/1e6:.0f}ms; RenderThread: " + ", ".join(f"{k}={v/1e6:.0f}" for k, v in st.most_common()))
    agg = q(f"""select s.name, count(*) n, sum(min(s.ts + s.dur, {b}) - max(s.ts, {a})) t from slice s join thread_track tt on s.track_id = tt.id
                where tt.utid={rt} and s.ts < {b} and s.ts + s.dur > {a} group by s.name order by t desc limit 14""")
    for r in agg: print(f"   {r.t/1e6:7.1f}ms x{r.n:<3} {r.name[:100]}")
