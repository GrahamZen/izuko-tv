/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/** 「设置 → 维护 → 性能诊断」卡片的样式 (接口见 RemotePerfDiagnostics). */
internal val PERF_STYLE = """
.perf-dur { font: inherit; font-size: 15px; padding: 0 10px; border-radius: 14px; border: 0; background: var(--chip); color: var(--on-chip); flex: none !important; }
.perf-status { margin: 12px 0 0; font-size: 15px; }
/* 体检与录制各一个标签, 报告按类型分开列 */
.perf-tabs { margin: 16px 0 0; }
.perf-list { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
.perf-item { border-radius: 12px; background: var(--soft); }
/* 标题行是展开 / 收起的按钮: 右端箭头, 展开时朝上 (同 .req 卡片) */
.perf-item-h { display: flex; align-items: center; gap: 10px; width: 100%; padding: 12px 14px; background: none; color: inherit; text-align: left; font-size: 14px; font-weight: 600; }
.perf-item-h > span { flex: 1; min-width: 0; }
.perf-item-h small { flex: none; font-size: 12px; font-weight: 400; color: var(--mute); }
.perf-item-h::after { content: ""; flex: none; width: 22px; height: 22px; background: var(--mute);
  -webkit-mask: var(--chev) center / contain no-repeat; mask: var(--chev) center / contain no-repeat; transition: transform .15s; }
.perf-item-h[aria-expanded=true]::after { transform: rotate(180deg); }
.perf-head { margin: -8px 14px 0; padding-bottom: 12px; font-size: 13px; color: var(--sub); }
.perf-detail { padding: 0 14px 12px; }
.perf-f { display: flex; gap: 8px; margin: 6px 0; font-size: 14px; line-height: 1.5; }
.perf-f::before { content: ''; flex: none; width: 8px; height: 8px; margin-top: 7px; border-radius: 50%; background: var(--mute); }
.perf-f.bad::before { background: var(--err); }
.perf-f.warn::before { background: #e8a000; }
.perf-f.ok::before { background: var(--ok); }
.perf-kv { display: grid; grid-template-columns: fit-content(40%) 1fr; gap: 4px 12px; margin: 10px 0 4px; font-size: 13px; }
.perf-kv dt { color: var(--mute); }
.perf-kv dd { margin: 0; word-break: break-word; }
.perf-detail a.ghost { display: block; margin-top: 10px; text-align: center; text-decoration: none; }
.perf-trace-title { margin-top: 20px; }
a.perf-trace-dl { display: flex; align-items: center; justify-content: center; text-decoration: none; }
""".trimIndent()

/**
 * 可下载整份 JSON). 录制中每秒读一次状态; 其余时候只在进「维护」与点按钮时读.
 * 「设置 → 维护 → 性能诊断」: 设备体检 / 录制 N 秒 / 方法采样 (下载 .trace), 报告按类型分两个标签列出 (默认收起只显示结论, 点标题行展开看关键数字,
 */
internal val PERF_SCRIPT = """
(function () {
  var box = document.getElementById('set-perf');
  if (!box) return;
  var data = null, open = {}, details = {}, timer = null, busy = false, dur = 30;
  /** 当前标签: health = 设备体检, record = 录制 (记在这台手机上). */
  var tab = 'health';
  try { tab = localStorage.getItem('perf.tab') || tab; } catch (e) {}
  function kindOf(r) { return r.kind === 'health' ? 'health' : 'record'; }
  function setTab(t) { tab = t; try { localStorage.setItem('perf.tab', t); } catch (e) {} }
  function load() {
    window.getJson('api/diag').then(function (d) {
      // 录制 / 体检刚结束: 切到刚出的那份报告所在的标签 (报告照常收起, 标题下有结论)
      if (data && data.status.state !== 'idle' && d.status.state === 'idle' && d.reports.length) setTab(kindOf(d.reports[0]));
      data = d;
      render();
      clearTimeout(timer);
      if (polling(d) && !document.hidden) timer = setTimeout(load, 1000);
    }).catch(function () { setHtml(box, ''); });
  }
  window.loadPerf = load;
  /** 录制 / 体检 / 方法采样进行中: 每秒读一次状态 */
  function polling(d) { return d.status.state !== 'idle' || !!(d.trace && d.trace.running); }
  document.addEventListener('visibilitychange', function () { if (!document.hidden && data && polling(data)) load(); });
  function kindText(r) {
    if (r.kind === 'auto') return T('自动 · 画面卡顿');
    return r.kind === 'health' ? T('设备体检') : T('录制 {0} 秒', r.seconds);
  }
  function findings(list) {
    return (list || []).map(function (f) { return '<div class="perf-f ' + esc(f.level) + '">' + esc(f.text) + '</div>'; }).join('');
  }
  function kv(rows) {
    return '<dl class="perf-kv">' + rows.filter(function (r) { return r[1] != null && r[1] !== ''; }).map(function (r) {
      return '<dt>' + esc(r[0]) + '</dt><dd>' + esc(r[1]) + '</dd>';
    }).join('') + '</dl>';
  }
  function p90(s) { return s ? T('{0} 毫秒', s[1]) : null; }
  function detailHtml(r) {
    var h = findings(r.findings), rows = [];
    if (r.kind === 'health') {
      var d = r.device || {}, m = r.memory || {}, s = r.storage || {}, a = r.app || {}, dp = r.display || {};
      rows.push([T('设备'), d.model + (d.soc ? ' · ' + d.soc : '')]);
      rows.push([T('系统'), d.android]);
      rows.push([T('CPU / GPU'), T('{0} 核', d.cores) + ' · ' + d.gpu]);
      rows.push([T('内存'), T('可用 {0} / 共 {1} MB', m.availMb, m.totalMb)]);
      if (m.processMb) rows.push([T('Izuko 占用'), T('{0} MB（Java {1} · Native {2} · 显存 {3} · 其它 {4}）', m.processMb.pss, m.processMb.java, m.processMb.native, m.processMb.graphics, m.processMb.other)]);
      rows.push([T('存储'), T('剩余 {0} / 共 {1} MB', s.freeMb, s.totalMb)]);
      rows.push([T('界面'), dp.window + (dp.mode ? ' · ' + T('屏幕 {0}', dp.mode) : '')]);
      rows.push([T('编译'), a.compiled + (a.odexMb ? ' · odex ' + a.odexMb + ' MB' : '')]);
      h += kv(rows);
      if (r.exits && r.exits.length) {
        h += '<div class="set-title">' + T('最近的进程退出') + '</div>' + kv(r.exits.slice(0, 6).map(function (e) {
          return [e.time, e.reason + ' · ' + e.state + (e.pssMb ? ' · ' + e.pssMb + ' MB' : '')];
        }));
      }
    } else {
      var f = r.frames || {}, st = f.stagesMs || {}, g = r.gc || {}, me = r.memory || {}, c = r.cpu || {}, j = r.jankCauses || {}, mt = r.mainThread || {};
      rows.push([T('页面'), r.page]);
      rows.push([T('帧'), T('{0} 帧 · {1} fps · 刷新 {2} Hz · 界面 {3}', f.count, f.fps, f.refreshHz, f.window)]);
      rows.push([T('慢帧'), T('{0} 个（{1}%）· 掉帧 {2} 次', f.janky, f.jankyPercent, f.missedVsync)]);
      rows.push([T('慢帧原因'), T('主线程 GC {0} · 后台 GC {1} · 缺页 {2} · 主线程卡住 {3} · 内存不足 {4}', j.gcMain, j.gcBackground, j.pageFault, j.mainBusy, j.lowMemory)]);
      rows.push([T('主线程 p90'), p90(st.ui)]);
      rows.push([T('等主线程 p90'), p90(st.waitMain)]);
      rows.push([T('同步 / 下发 p90'), st.sync && st.issue ? T('{0} / {1} 毫秒', st.sync[1], st.issue[1]) : null]);
      rows.push([T('GPU p90'), p90(st.gpu)]);
      rows.push([T('GC'), T('{0} 次 · {1} 毫秒 · 阻塞 {2} 次 · 每秒分配 {3} MB', g.count, g.timeMs, g.blockingCount, g.allocMbPerSec)]);
      rows.push([T('内存'), T('可用最低 {0} / 共 {1} MB · 占用峰值 {2} MB · Java 堆 {3}/{4} MB', me.availMinMb, me.totalMb, me.pssMaxMb, me.javaUsedMaxMb, me.javaMaxMb)]);
      rows.push([T('缺页'), T('主线程 {0} · 全进程 {1}', me.mainMajorFaults, me.processMajorFaults)]);
      rows.push([T('CPU'), T('{0} 核（共 {1} 核）', c.coresUsed, c.cores) + (c.threads ? ' · ' + c.threads.slice(0, 3).map(function (t) { return t.name + ' ' + t.ms + 'ms'; }).join(', ') : '')]);
      if (mt.stalls) rows.push([T('主线程卡住'), T('{0} 次 · 最长 {1} 毫秒', mt.stalls, mt.longestMs)]);
      // 录制时在播视频: 开播各步、视频出帧、卡的那几秒 (见 PerfPlaybackAnalysis)
      var pb = r.playback;
      if (pb) {
        if (pb.trigger) rows.push([T('触发'), pb.trigger.reason + ' · ' + T('第 {0} 秒', pb.trigger.atSec)]);
        var start = (pb.start || []).filter(function (m) { return m.sinceStartSec != null; });
        if (start.length) rows.push([T('开播'), start.map(function (m) { return T(m.what) + ' ' + T('{0} 秒', m.sinceStartSec); }).join(' · ')]);
        if (pb.rendered) rows.push([T('视频画面'), T('出帧 {0} · 丢帧 {1} · 跳帧 {2} · 片源 {3} fps', pb.rendered, pb.dropped, pb.skipped, pb.fps || '?') +
          (pb.displayHz ? ' · ' + T('屏幕 {0} Hz', pb.displayHz) : '')]);
        if (pb.stutterSeconds && pb.stutterSeconds.length) rows.push([T('卡的秒'), pb.stutterSeconds.join('、')]);
        rows.push([T('视频解码器'), pb.decoder]);
        rows.push([T('片源格式'), pb.format]);
      }
      h += kv(rows);
      var bad = pb ? (pb.seconds || []).filter(function (s) { return s.stutter; }).slice(0, 8) : [];
      if (bad.length) {
        h += '<div class="set-title">' + T('卡的那几秒') + '</div>' + kv(bad.map(function (s) {
          return [T('第 {0} 秒', s.sec), T('出帧 {0} · 丢 {1} · 跳 {2} · CPU {3} 核（{4}）· 选源器 {5} 毫秒 · 在查 {6} 个源 · GC {7} 次 · 缓冲余量 {8} 毫秒',
            s.rendered, s.dropped, s.skipped, s.cores, s.top || '—', s.selectorMs, s.searching, s.gc, s.bufferedMs == null ? '—' : s.bufferedMs) +
            (s.memAvailMb != null ? ' · ' + T('可用内存 {0} MB', s.memAvailMb) : '') + (s.mainStallMs ? ' · ' + T('主线程卡 {0} 毫秒', s.mainStallMs) : '')];
        }));
      }
    }
    return h + '<a class="ghost" href="api/diag/report/' + encodeURIComponent(r.id) + '" download="izuko-perf-' + esc(r.id) + '.json">' + T('下载报告') + '</a>';
  }
  function render() {
    if (!data) return;
    var s = data.status, idle = s.state === 'idle';
    var h = '<div class="card set-card"><div class="set-title">' + T('性能诊断') + '</div>' +
      '<p class="hint">' + T('体检看设备、内存、存储和最近为什么被关掉；录制时在电视上照常操作要测的界面，结束后分析卡顿是 GC、内存不足、主线程还是 GPU 造成的。') + '</p>' +
      '<p class="hint">' + T('查播放卡顿：先点录制，再在电视上开播一集，报告里会列出开播各步用时、视频画面卡在哪几秒，以及那几秒是搜索、CPU、GC 还是网络在拖累。') + '</p>' +
      '<p class="hint">' + T('播放时画面异常卡顿（掉帧、画面停住、主线程卡住）会自动录一份，连同卡之前一分钟的情况，在「录制」里标着「自动」。') + '</p>';
    if (s.state === 'recording' && s.auto) {
      h += '<p class="perf-status">' + T('播放画面卡顿了，正在自动录制，还剩 {0} 秒。', s.remaining) + '</p>';
    } else if (s.state === 'recording') {
      h += '<p class="perf-status">' + T('正在录制，还剩 {0} 秒。请在电视上操作要测的界面。', s.remaining) + '</p>' +
        '<div class="row"><button type="button" class="ghost" data-perf="stop">' + T('提前结束') + '</button></div>';
    } else if (s.state === 'checking') {
      h += '<p class="perf-status">' + T('正在体检…') + '</p>';
    } else {
      h += '<div class="row"><button type="button" class="ghost" data-perf="health"' + (busy ? ' disabled' : '') + '>' + T('设备体检') + '</button>' +
        '<button type="button" class="primary" data-perf="record"' + (busy ? ' disabled' : '') + '>' + T('录制') + '</button>' +
        '<select class="perf-dur" data-perf="dur">' + [10, 30, 60].map(function (n) {
          return '<option value="' + n + '"' + (n === dur ? ' selected' : '') + '>' + T('{0} 秒', n) + '</option>';
        }).join('') + '</select></div>';
    }
    var mt = data.trace || {};
    h += '<div class="set-title perf-trace-title">' + T('方法采样') + '</div>' +
      '<p class="hint">' + T('看主线程卡在哪段代码上：开始后在电视上操作要测的界面，结束后下载 .trace 文件，用 Android Studio 的 Profiler 打开。采样本身很重，时长会被放大，只用来看卡在哪。') + '</p>';
    if (mt.running) {
      h += '<p class="perf-status">' + T('正在方法采样，还剩 {0} 秒。', mt.remaining) + '</p>';
    } else {
      h += '<div class="row"><button type="button" class="ghost" data-perf="trace"' + (busy ? ' disabled' : '') + '>' + T('采样 {0} 秒', 10) + '</button>' +
        (mt.size ? '<a class="ghost perf-trace-dl" href="api/diag/trace/file" download="izuko-method-trace.trace">' + T('下载上次的（{0}）', mt.time) + '</a>' : '') + '</div>';
    }
    if (data.reports.length) {
      var tabs = [['health', T('设备体检')], ['record', T('录制')]];
      h += '<div class="seg perf-tabs">' + tabs.map(function (t) {
        var n = data.reports.filter(function (r) { return kindOf(r) === t[0]; }).length;
        return '<button type="button" data-perf-tab="' + t[0] + '"' + (tab === t[0] ? ' class="on"' : '') + '>' + t[1] + ' <small>' + n + '</small></button>';
      }).join('') + '</div>';
      var list = data.reports.filter(function (r) { return kindOf(r) === tab; });
      h += list.length ? '<div class="perf-list">' + list.map(function (r) {
        var o = !!open[r.id];
        return '<div class="perf-item"><button type="button" class="perf-item-h" data-perf-id="' + esc(r.id) + '" aria-expanded="' + o + '"><span>' + kindText(r) +
          (r.kind === 'health' ? '' : ' · ' + esc(r.page)) + '</span><small>' + esc(r.time) + '</small></button>' +
          (o ? '<div class="perf-detail">' + (details[r.id] ? detailHtml(details[r.id]) : '<p class="hint">' + T('读取中…') + '</p>') + '</div>'
            : (r.headline ? '<div class="perf-head">' + esc(r.headline) + '</div>' : '')) + '</div>';
      }).join('') + '</div>' : '<p class="hint">' + T('还没有诊断报告') + '</p>';
    } else if (idle) {
      h += '<p class="hint">' + T('还没有诊断报告') + '</p>';
    }
    setHtml(box, h + '</div>');
  }
  function toggle(id) {
    open[id] = !open[id];
    render();
    if (open[id] && !details[id]) {
      window.getJson('api/diag/report/' + encodeURIComponent(id)).then(function (r) { details[id] = r; render(); }).catch(failRead);
    }
  }
  box.addEventListener('change', function (e) {
    if (e.target.getAttribute('data-perf') === 'dur') dur = parseInt(e.target.value, 10) || 30;
  });
  box.addEventListener('click', function (e) {
    var b = e.target.closest('[data-perf]');
    if (b && b.tagName === 'BUTTON') {
      var act = b.getAttribute('data-perf');
      if (act === 'health') {
        busy = true; render();
        window.post('api/diag/health', {}).then(function (d) {
          busy = false;
          if (!d.ok) { toast(d.message); load(); return; }
          details[d.report.id] = d.report;
          setTab('health');
          load();
        }, function () { busy = false; fail(); load(); });
      } else if (act === 'record') {
        busy = true; render();
        window.post('api/diag/record', { seconds: dur }).then(function (d) {
          busy = false;
          if (d.ok !== false) setTab('record');
          toast(d.message);
          load();
        }, function () { busy = false; fail(); load(); });
      } else if (act === 'stop') {
        window.post('api/diag/stop', {}).then(function () { load(); }, fail);
      } else if (act === 'trace') {
        busy = true; render();
        window.post('api/diag/trace', { seconds: 10 }).then(function (d) {
          busy = false;
          toast(d.message);
          load();
        }, function () { busy = false; fail(); load(); });
      }
      return;
    }
    var t = e.target.closest('[data-perf-tab]');
    if (t) { setTab(t.getAttribute('data-perf-tab')); render(); return; }
    var head = e.target.closest('button[data-perf-id]');
    if (head) toggle(head.getAttribute('data-perf-id'));
  });
})();
""".trimIndent()
