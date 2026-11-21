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
/* 每份报告可左滑删除 (外层 .sw 见 SCRIPT 的 swRow): 这里的行是卡片里的浅底块, 不要 .sw 默认的投影 */
#set-perf .sw { box-shadow: none; }
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
 * 「设置 → 维护 → 性能诊断」: 设备体检 / 录制 N 秒 / 方法采样 (下载 .trace), 报告按类型分两个标签列出 (默认收起只显示结论, 点标题行展开看关键数字,
 * 可下载整份 JSON; 左滑删除, 同播放记录). 录制中每秒读一次状态; 其余时候只在进「维护」与点按钮时读.
 */
internal val PERF_SCRIPT = """
(function () {
  var box = document.getElementById('set-perf');
  if (!box) return;
  var data = null, open = {}, details = {}, timer = null, busy = false, dur = 30;
  /** 当前标签: health = 设备体检, network = 网络体检, record = 录制 (记在这台手机上). */
  var tab = 'health';
  try { tab = localStorage.getItem('perf.tab') || tab; } catch (e) {}
  function isNet(r) { return r.kind === 'network' || r.kind === 'netauto'; }
  function kindOf(r) { return r.kind === 'health' ? 'health' : isNet(r) ? 'network' : 'record'; }
  function setTab(t) { tab = t; try { localStorage.setItem('perf.tab', t); } catch (e) {} }
  /** 有诊断在跑 (录制 / 设备体检 / 网络体检) */
  function running(d) { return d.status.state !== 'idle' || !!(d.network && d.network.state === 'running'); }
  function load() {
    window.getJson('api/diag').then(function (d) {
      // 录制 / 体检刚结束: 切到刚出的那份报告所在的标签 (报告照常收起, 标题下有结论)
      if (data && running(data) && !running(d) && d.reports.length) setTab(kindOf(d.reports[0]));
      data = d;
      render();
      clearTimeout(timer);
      if (polling(d) && !document.hidden) timer = setTimeout(load, 1000);
    }).catch(function () { setHtml(box, ''); });
  }
  window.loadPerf = load;
  /** 录制 / 体检 / 方法采样进行中: 每秒读一次状态 */
  function polling(d) { return running(d) || !!(d.trace && d.trace.running); }
  document.addEventListener('visibilitychange', function () { if (!document.hidden && data && polling(data)) load(); });
  function kindText(r) {
    if (r.kind === 'auto') return T('自动 · 画面卡顿');
    if (r.kind === 'netauto') return T('自动 · 网络异常');
    if (r.kind === 'network') return T('网络体检');
    return r.kind === 'health' ? T('设备体检') : T('录制 {0} 秒', r.seconds);
  }
  /** 网络体检里一个请求: 状态码或失败在哪一段, 各段在第几毫秒结束, 连到的地址 */
  function stepText(s) {
    return ({ dns: T('解析域名'), connect: T('连接'), tls: T('加密握手'), request: T('发请求'), response: T('等回应'), setup: T('准备') })[s] || T('未知');
  }
  function probeText(p) {
    if (p.skipped) return T('没发（前面的都连不上）');
    var parts = [];
    if (p.dnsMs != null) parts.push(T('解析 {0}', p.dnsMs));
    if (p.connectMs != null) parts.push(T('连接 {0}', p.connectMs));
    if (p.tlsMs != null) parts.push(T('握手 {0}', p.tlsMs));
    if (p.ttfbMs != null) parts.push(T('首字节 {0}', p.ttfbMs));
    if (p.totalMs != null) parts.push(T('共 {0} 毫秒', p.totalMs));
    return (p.status != null ? 'HTTP ' + p.status : T('失败在{0}', stepText(p.failedAt))) + (parts.length ? ' · ' + parts.join(' / ') : '') +
      (p.remote ? ' · ' + p.remote : '') + (p.protocol ? ' · ' + p.protocol : '') + (p.echRetried ? ' · ' + T('换过加密配置重试') : '') +
      (p.error ? ' · ' + p.error : '');
  }
  function networkDetail(r) {
    var n = r.network || {}, e = n.environment || {}, L = r.labels || {}, h = '', rows = [];
    function route(x) { return L['route:' + x] || x; }
    if (r.trigger) h += '<p class="hint">' + esc(T('自动体检的原因：{0}', r.trigger)) + '</p>';
    h += findings(r.findings);
    var transport = function (t) { return ({ wifi: 'Wi-Fi', ethernet: T('有线'), cellular: T('移动网络'), vpn: 'VPN' })[t] || t; };
    rows.push([T('网络'), e.connected ? (e.transports || []).map(transport).join(' + ') + (e.validated === false ? ' · ' + T('系统判定上不了外网') : '') : T('没联网')]);
    rows.push([T('DNS 服务器'), (e.dnsServers || []).join(', ') + (e.privateDns && e.privateDns !== 'off' ? ' · ' + T('私人 DNS：{0}', e.privateDns) : '')]);
    rows.push([T('地址'), [e.ipv4 ? 'IPv4' : '', e.ipv6 ? 'IPv6' : ''].filter(Boolean).join(' + ')]);
    rows.push([T('代理'), [e.systemProxy ? T('系统 {0}', e.systemProxy) : '', e.appProxy ? T('应用 {0}', e.appProxy) : ''].filter(Boolean).join(' · ') || T('没有')]);
    rows.push([T('Bangumi 线路'), (e.routes || []).map(route).join(' → ') + (e.settledRoute ? ' · ' + T('现在走{0}', route(e.settledRoute)) : '')]);
    var ec = e.echConfig;
    rows.push([T('加密直连'), !e.echUsable ? T('用不了（设备不支持或设了代理）') : ec && ec.bundled ? T('用的是包里带的配置') :
      T('配置 {0} 秒前取过', ec && ec.fetchedAgoSeconds != null ? ec.fetchedAgoSeconds : '?')]);
    rows.push([T('登录'), e.loggedIn ? T('已登录') : T('没登录')]);
    if (e.clockSkewSeconds != null) rows.push([T('时钟'), T('比服务器快 {0} 秒', e.clockSkewSeconds)]);
    rows.push([T('用时'), T('{0} 秒', Math.round((n.durationMs || 0) / 1000))]);
    h += kv(rows);
    (n.routes || []).forEach(function (rt) {
      var t = rt.trace || {}, cf = ['sni', 'loc', 'colo', 'http', 'tls', 'warp'].filter(function (k) { return t[k]; }).map(function (k) { return k + '=' + t[k]; });
      h += '<div class="set-title">' + esc(route(rt.route)) + (rt.enabled ? '' : esc(T('（设置里没开）'))) + '</div>' +
        kv((cf.length ? [[T('Cloudflare'), cf.join(' · ')]] : []).concat((rt.requests || []).map(function (p) { return [L[p.name] || p.name, probeText(p)]; })));
    });
    // 加密直连清单里的地址逐个握手 (清单顺序, 排第一的先试)
    if ((n.echAddressChecks || []).length) {
      h += '<div class="set-title">' + T('加密直连的各个地址') + '</div>' + kv(n.echAddressChecks.map(function (c) {
        return [c.address, probeText(c.probe) + (c.sni ? ' · sni=' + c.sni : '')];
      }));
    }
    // 体检开始时把仓库里的清单都拉了一遍: 拉没拉到、几条、用的是哪份
    if ((n.lists || []).length) {
      h += '<div class="set-title">' + T('清单') + '</div>' + kv(n.lists.map(function (l) {
        var where = l.bundled ? T('安装包里自带的') : l.updatedAgoSeconds != null ? T('{0} 分钟前拉到的', Math.round(l.updatedAgoSeconds / 60)) : '';
        return [l.file, T('{0} 条', l.entries) + (where ? ' · ' + where : '') + (l.refreshed ? '' : ' · ' + T('这次没拉到新的'))];
      }));
    }
    var bad = {};
    (r.suspiciousDns || []).forEach(function (x) { bad[x] = true; });
    h += '<div class="set-title">' + T('域名解析') + '</div>' + kv((n.dns || []).map(function (d) {
      return [d.host + (bad[d.host] ? ' ' + T('（疑似被污染）') : ''),
        T('系统 DNS：{0}', d.system && d.system.length ? d.system.join(', ') : (d.systemError || '—')) + (d.systemMs != null ? ' · ' + T('{0} 毫秒', d.systemMs) : '') +
        ' · ' + T('加密 DNS：{0}', d.doh && d.doh.length ? d.doh.join(', ') : (d.dohError || '—'))];
    }));
    h += '<div class="set-title">' + T('其他服务') + '</div>' + kv((n.services || []).map(function (p) { return [(L[p.name] || p.name) + ' · ' + p.via, probeText(p)]; }));
    var a = n.app || {}, app = [];
    (a.inFlight || []).forEach(function (x) { app.push([x.host, T('{0} 个请求还没结束，最久的等了 {1} 秒', x.count, x.oldestSeconds)]); });
    (a.routeCounts || []).forEach(function (x) { app.push([route(x.route), T('最近 10 分钟成功 {0} 次、失败 {1} 次', x.ok, x.failed)]); });
    (a.recentFailures || []).slice(0, 8).forEach(function (x) {
      app.push([T('{0} 秒前', x.agoSeconds), route(x.route) + ' · ' + x.host + (x.path || '') + ' · ' + T('{0} 毫秒', x.elapsedMs) + ' · ' + x.cause]);
    });
    if (app.length) h += '<div class="set-title">' + T('应用里的 Bangumi 请求') + '</div>' + kv(app);
    return h;
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
    if (isNet(r)) {
      return networkDetail(r) + '<a class="ghost" href="api/diag/report/' + encodeURIComponent(r.id) + '" download="izuko-network-' + esc(r.id) + '.json">' + T('下载报告') + '</a>';
    }
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
      '<p class="hint">' + T('播放时画面异常卡顿（掉帧、画面停住、主线程卡住）会自动录一份，连同卡之前一分钟的情况，在「录制」里标着「自动」。') + '</p>' +
      '<p class="hint">' + T('网络体检把 Bangumi 每条线路（官方、加密直连、镜像）上常用的请求和反馈、清单、TMDB 等服务各测一遍，记下域名解析、连接、握手各花多久、失败在哪一步，大约一分钟。Bangumi 请求接连连不上时会自动做一次，标着「自动」。') + '</p>';
    var net = data.network || {};
    if (net.state === 'running') {
      h += '<p class="perf-status">' + (net.auto ? T('Bangumi 请求接连失败，正在自动网络体检（{0} 秒）：{1}', net.elapsed, net.step)
        : T('正在网络体检（{0} 秒）：{1}', net.elapsed, net.step)) + '</p>';
    }
    if (s.state === 'recording' && s.auto) {
      h += '<p class="perf-status">' + T('播放画面卡顿了，正在自动录制，还剩 {0} 秒。', s.remaining) + '</p>';
    } else if (s.state === 'recording') {
      h += '<p class="perf-status">' + T('正在录制，还剩 {0} 秒。请在电视上操作要测的界面。', s.remaining) + '</p>' +
        '<div class="row"><button type="button" class="ghost" data-perf="stop">' + T('提前结束') + '</button></div>';
    } else if (s.state === 'checking') {
      h += '<p class="perf-status">' + T('正在体检…') + '</p>';
    } else {
      h += '<div class="row"><button type="button" class="ghost" data-perf="health"' + (busy ? ' disabled' : '') + '>' + T('设备体检') + '</button>' +
        '<button type="button" class="ghost" data-perf="network"' + (busy || net.state === 'running' ? ' disabled' : '') + '>' + T('网络体检') + '</button>' +
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
      var tabs = [['health', T('设备体检')], ['network', T('网络体检')], ['record', T('录制')]];
      h += '<div class="seg perf-tabs">' + tabs.map(function (t) {
        var n = data.reports.filter(function (r) { return kindOf(r) === t[0]; }).length;
        return '<button type="button" data-perf-tab="' + t[0] + '"' + (tab === t[0] ? ' class="on"' : '') + '>' + t[1] + ' <small>' + n + '</small></button>';
      }).join('') + '</div>';
      var list = data.reports.filter(function (r) { return kindOf(r) === tab; });
      h += list.length ? '<div class="perf-list">' + list.map(function (r) {
        var o = !!open[r.id];
        // 左滑露出「删除」(滑到底松手直接删), 同播放记录
        return window.swRow('', '<button type="button" class="sw-btn del" data-perf-del="' + esc(r.id) + '">' + window.ICONS.trash + T('删除') + '</button>',
          '<div class="perf-item sw-row" data-perf-row="' + esc(r.id) + '"><button type="button" class="perf-item-h" data-perf-id="' + esc(r.id) + '" aria-expanded="' + o + '"><span>' + kindText(r) +
          (kindOf(r) === 'record' ? ' · ' + esc(r.page) : '') + '</span><small>' + esc(r.time) + '</small></button>' +
          (o ? '<div class="perf-detail">' + (details[r.id] ? detailHtml(details[r.id]) : '<p class="hint">' + T('读取中…') + '</p>') + '</div>'
            : (r.headline ? '<div class="perf-head">' + esc(r.headline) + '</div>' : '')) + '</div>');
      }).join('') + '</div>' : '<p class="hint">' + T('还没有诊断报告') + '</p>';
    } else if (idle) {
      h += '<p class="hint">' + T('还没有诊断报告') + '</p>';
    }
    // 开着的行 / 正在拖时别重画 (录制中每秒刷新), 不然行被弹回去
    if (window.swBusy(box)) return;
    setHtml(box, h + '</div>');
    window.swPeek(box, 'perf');
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
      } else if (act === 'network') {
        busy = true; render();
        window.post('api/diag/network', {}).then(function (d) {
          busy = false;
          if (d.ok !== false) setTab('network');
          toast(d.message);
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
    // 左滑露出的「删除」: 删成了这一行收起来, 再读一次列表; 没删成行放回去
    var del = e.target.closest('[data-perf-del]');
    if (del) {
      var id = del.getAttribute('data-perf-del'), sw = del.closest('.sw');
      window.post('api/diag/delete', { id: id }).then(function (r) {
        if (!r.ok) { if (r.message) toast(r.message); window.swClose(sw); load(); return; }
        delete open[id];
        delete details[id];
        sw.style.height = sw.offsetHeight + 'px';
        void sw.offsetHeight;
        sw.classList.add('gone');
        setTimeout(load, 260);
      }, function () { window.swClose(sw); fail(); });
      return;
    }
    var t = e.target.closest('[data-perf-tab]');
    if (t) { setTab(t.getAttribute('data-perf-tab')); render(); return; }
    var head = e.target.closest('button[data-perf-id]');
    if (head) toggle(head.getAttribute('data-perf-id'));
  });
})();
""".trimIndent()
