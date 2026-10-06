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
.perf-list { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
.perf-item { padding: 12px 14px; border-radius: 12px; background: var(--soft); cursor: pointer; }
.perf-item-h { display: flex; justify-content: space-between; gap: 10px; font-size: 14px; font-weight: 600; }
.perf-item-h small { flex: none; font-size: 12px; font-weight: 400; color: var(--mute); }
.perf-head { margin-top: 4px; font-size: 13px; color: var(--sub); }
.perf-detail { margin-top: 10px; cursor: default; }
.perf-f { display: flex; gap: 8px; margin: 6px 0; font-size: 14px; line-height: 1.5; }
.perf-f::before { content: ''; flex: none; width: 8px; height: 8px; margin-top: 7px; border-radius: 50%; background: var(--mute); }
.perf-f.bad::before { background: var(--err); }
.perf-f.warn::before { background: #e8a000; }
.perf-f.ok::before { background: var(--ok); }
.perf-kv { display: grid; grid-template-columns: fit-content(40%) 1fr; gap: 4px 12px; margin: 10px 0 4px; font-size: 13px; }
.perf-kv dt { color: var(--mute); }
.perf-kv dd { margin: 0; word-break: break-word; }
.perf-detail a.ghost { display: block; margin-top: 10px; text-align: center; text-decoration: none; }
""".trimIndent()

/**
 * 「设置 → 维护 → 性能诊断」: 设备体检 / 录制 N 秒, 报告列表 (点开看结论与关键数字, 可下载整份 JSON).
 * 录制中每秒读一次状态; 其余时候只在进「维护」与点按钮时读.
 */
internal val PERF_SCRIPT = """
(function () {
  var box = document.getElementById('set-perf');
  if (!box) return;
  var data = null, open = {}, details = {}, timer = null, busy = false, dur = 30;
  function load() {
    window.getJson('api/diag').then(function (d) {
      // 录制 / 体检刚结束: 直接展开刚出的那份报告
      var finished = data && data.status.state !== 'idle' && d.status.state === 'idle' && d.reports.length;
      data = d;
      if (finished) {
        var id = d.reports[0].id;
        open = {};
        open[id] = true;
        if (!details[id]) window.getJson('api/diag/report/' + encodeURIComponent(id)).then(function (r) { details[id] = r; render(); }).catch(failRead);
      }
      render();
      clearTimeout(timer);
      if (d.status.state !== 'idle' && !document.hidden) timer = setTimeout(load, 1000);
    }).catch(function () { setHtml(box, ''); });
  }
  window.loadPerf = load;
  document.addEventListener('visibilitychange', function () { if (!document.hidden && data && data.status.state !== 'idle') load(); });
  function kindText(r) {
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
      h += kv(rows);
    }
    return h + '<a class="ghost" href="api/diag/report/' + encodeURIComponent(r.id) + '" download="izuko-perf-' + esc(r.id) + '.json">' + T('下载报告') + '</a>';
  }
  function render() {
    if (!data) return;
    var s = data.status, idle = s.state === 'idle';
    var h = '<div class="card set-card"><div class="set-title">' + T('性能诊断') + '</div>' +
      '<p class="hint">' + T('体检看设备、内存、存储和最近为什么被关掉；录制时在电视上照常操作要测的界面，结束后分析卡顿是 GC、内存不足、主线程还是 GPU 造成的。') + '</p>';
    if (s.state === 'recording') {
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
    if (data.reports.length) {
      h += '<div class="perf-list">' + data.reports.map(function (r) {
        var o = open[r.id];
        return '<div class="perf-item" data-perf-id="' + esc(r.id) + '"><div class="perf-item-h"><span>' + kindText(r) +
          (r.kind === 'health' ? '' : ' · ' + esc(r.page)) + '</span><small>' + esc(r.time) + '</small></div>' +
          (o ? '<div class="perf-detail">' + (details[r.id] ? detailHtml(details[r.id]) : '<p class="hint">' + T('读取中…') + '</p>') + '</div>'
            : (r.headline ? '<div class="perf-head">' + esc(r.headline) + '</div>' : '')) + '</div>';
      }).join('') + '</div>';
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
          details[d.report.id] = d.report; open = {}; open[d.report.id] = true;
          load();
        }, function () { busy = false; fail(); load(); });
      } else if (act === 'record') {
        busy = true; render();
        window.post('api/diag/record', { seconds: dur }).then(function (d) {
          busy = false;
          toast(d.message);
          load();
        }, function () { busy = false; fail(); load(); });
      } else if (act === 'stop') {
        window.post('api/diag/stop', {}).then(function () { load(); }, fail);
      }
      return;
    }
    if (e.target.closest('.perf-detail')) return;
    var item = e.target.closest('[data-perf-id]');
    if (item) toggle(item.getAttribute('data-perf-id'));
  });
})();
""".trimIndent()
