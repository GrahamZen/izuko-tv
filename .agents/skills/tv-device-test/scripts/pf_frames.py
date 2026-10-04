"""列出主线程上长帧 (>MIN ms) 里的片段树 (>=CHILD ms), 以及帧外的长片段.
用法: pf_frames.py <trace> [min_frame_ms=150] [child_ms=8] [t_from_s] [t_to_s]"""
import sys
sys.stdout.reconfigure(encoding="utf-8")
from perfetto.trace_processor import TraceProcessor
path = sys.argv[1]
MIN = float(sys.argv[2]) if len(sys.argv) > 2 else 150
CHILD = float(sys.argv[3]) if len(sys.argv) > 3 else 8
tp = TraceProcessor(trace=path)
q = lambda s: list(tp.query(s))
t0 = q("select start_ts from trace_bounds")[0].start_ts
T_FROM = t0 + int(float(sys.argv[4]) * 1e9) if len(sys.argv) > 4 else 0
T_TO = t0 + int(float(sys.argv[5]) * 1e9) if len(sys.argv) > 5 else 1 << 62
pid = [x.pid for x in q("select pid, name from process where name = 'io.github.grahamzen.anime.tv'")][0]
main = q(f"select utid from thread join process using(upid) where pid={pid} and tid={pid}")[0].utid
frames = q(f"""select s.id, s.ts, s.dur, s.name from slice s join thread_track tt on s.track_id = tt.id
               where tt.utid={main} and s.depth = 0 and s.dur > {MIN * 1e6} and s.ts >= {T_FROM} and s.ts <= {T_TO} order by s.ts""")
for f in frames:
    print(f"\n### {(f.ts - t0)/1e9:7.3f}s {f.dur/1e6:6.1f}ms {f.name}")
    kids = q(f"""select s.ts, s.dur, s.depth, s.name from descendant_slice({f.id}) s where s.dur > {CHILD * 1e6} order by s.ts""")
    for k in kids:
        print(f"   {'  ' * (k.depth - 1)}{k.dur/1e6:6.1f}ms  {k.name[:120]}")
