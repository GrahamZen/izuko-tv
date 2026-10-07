/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/** 播放卡「失败报告」入口与面板的样式 (见 [FAIL_SCRIPT]). */
internal val FAIL_STYLE = """
.now-fail { display: flex; align-items: center; gap: 6px; width: 100%; margin-top: 10px; padding: 8px 12px; border-radius: 12px; background: var(--err-bg); color: var(--err-fg); font-size: 13px; text-align: left; }
.now-fail svg { flex: none; width: 16px; height: 16px; fill: currentColor; }
.now-fail span { flex: 1; min-width: 0; }
.now-fail b { flex: none; font-weight: 700; }
#fail-sheet .sheet-body { padding-bottom: calc(24px + env(safe-area-inset-bottom)); }
.fail-tools { display: flex; gap: 8px; margin: 4px 0 12px; }
.fail-list { display: flex; flex-direction: column; gap: 10px; }
.fail-sec { margin-top: 6px; font-size: 13px; font-weight: 700; color: var(--sub); }
.fail-sec:first-child { margin-top: 0; }
.fail-item { padding: 12px 14px; border-radius: 12px; background: var(--soft); }
.fail-h { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; }
.fail-reason { font-size: 15px; font-weight: 700; color: var(--err); }
.fail-h small { flex: none; font-size: 12px; color: var(--mute); }
.fail-meta { margin-top: 4px; font-size: 13px; color: var(--sub); word-break: break-word; }
.fail-title { margin-top: 2px; font-size: 13px; color: var(--mute); word-break: break-all; }
.fail-root { margin-top: 8px; font-size: 13px; line-height: 1.5; word-break: break-word; }
.fail-item details { margin-top: 8px; font-size: 13px; }
.fail-item summary { color: var(--p); cursor: pointer; }
.fail-chain { margin: 8px 0 0; padding-left: 16px; line-height: 1.5; word-break: break-word; }
.fail-stack { margin: 8px 0 0; padding: 8px; max-height: 320px; overflow: auto; border-radius: 8px; background: var(--bg); font-size: 11px; line-height: 1.4; white-space: pre; }
.fail-copy { width: 100%; min-height: 160px; margin-top: 12px; font: 12px/1.4 monospace; }
""".trimIndent()

/**
 * 播放卡上的「失败报告」(服务端见 RemotePlaybackFailures): 这一集或换集前那一集播放失败过 (`api/player` 带 `failures` /
 * `prevFailures`) 才在卡上画入口, 点开读 `api/player/failures`, 两集分两段、新的在上面; 面板开着时次数变了就重读. 「复制报告」把整份拼成纯文本 (局域网是 http,
 * 复制走 execCommand, 不行就把文本框露出来让人长按复制).
 */
internal val FAIL_SCRIPT = """
(function () {
  var WARN = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>';
  var NL = String.fromCharCode(10);
  var sheet = null, data = null, shownCount = '', subjectTitle = '';
  // 播放卡上的入口 (只在失败过时有, 见 PLAYER 卡片)
  window.failEntry = function (s) {
    if (!s.failures && !s.prevFailures) return '';
    var text = s.failures ? T('这一集播放失败过 {0} 次', s.failures) : T('上一集播放失败过 {0} 次', s.prevFailures);
    return '<button type="button" class="now-fail" data-fails="1">' + WARN + '<span>' + text + '</span><b>' + T('查看报告') + ' ›</b></button>';
  };
  function el(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function clock(ms) { var d = new Date(ms); return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()); }
  function build() {
    sheet = document.createElement('div');
    sheet.id = 'fail-sheet';
    sheet.className = 'sheet';
    sheet.hidden = true;
    sheet.innerHTML = '<div class="sheet-head"><div class="sheet-title">' + T('播放失败报告') + '</div>' +
      '<button type="button" class="sheet-btn" id="fail-close" aria-label="' + T('关闭') + '" title="' + T('关闭') + '">' + window.ICONS.close + '</button></div>' +
      '<div class="sheet-body"><p class="hint">' + T('这一集每次播放失败都记在这里（包括自动换源前的那几次），新的在上面；换了集的话，上一集的记录接在后面。网址里的参数（签名、令牌）已去掉。') + '</p>' +
      '<div class="fail-tools"><button type="button" class="ghost" id="fail-copy-btn">' + T('复制报告') + '</button>' +
      '<button type="button" class="ghost" id="fail-reload">' + T('刷新') + '</button></div>' +
      '<div id="fail-list" class="fail-list"></div>' +
      '<textarea id="fail-copy" class="fail-copy" readonly hidden></textarea></div>';
    document.body.appendChild(sheet);
  }
  function itemHtml(e) {
    var causes = e.causes || [];
    var root = causes.length ? causes[causes.length - 1] : '';
    var meta = [e.stage, e.source, e.host].filter(function (x) { return x; }).map(window.esc).join(' · ');
    return '<div class="fail-item"><div class="fail-h"><span class="fail-reason">' + window.esc(e.reason) + '</span><small>' + clock(e.time) + '</small></div>' +
      '<div class="fail-meta">' + meta + '</div>' +
      (e.title ? '<div class="fail-title">' + window.esc(e.title) + '</div>' : '') +
      (e.network ? '<div class="fail-root">' + T('电视连不上资源服务器（域名解析失败、连接失败或超时）。换个数据源，或检查电视的网络与 DNS 设置。') + '</div>' : '') +
      (root ? '<div class="fail-root">' + window.esc(root) + '</div>' : '') +
      (causes.length > 1 || e.stack
        ? '<details><summary>' + T('详细信息') + '</summary>' +
          (causes.length > 1 ? '<ol class="fail-chain">' + causes.map(function (c) { return '<li>' + window.esc(c) + '</li>'; }).join('') + '</ol>' : '') +
          (e.stack ? '<pre class="fail-stack">' + window.esc(e.stack) + '</pre>' : '') + '</details>'
        : '') +
      '</div>';
  }
  function render() {
    var list = data ? data.entries : [];
    var prev = data && data.previous;
    var h = list.length ? list.map(itemHtml).join('') : '<p class="empty">' + T('这一集还没有失败记录') + '</p>';
    // 换集前那一集的 (自动换源、太短的视频播完都可能带着换集)
    if (prev) h = '<div class="fail-sec">' + T('这一集') + '</div>' + h + '<div class="fail-sec">' + T('上一集：{0}', window.esc(prev.episode)) + '</div>' +
      prev.entries.map(itemHtml).join('');
    window.setHtml(el('fail-list'), h);
  }
  function load() {
    window.getJson('api/player/failures').then(function (d) { data = d; render(); }).catch(window.failRead);
  }
  function entryText(e) {
    return ['[' + clock(e.time) + '] ' + e.reason + ' · ' + [e.stage, e.source, e.host].filter(function (x) { return x; }).join(' · '),
      e.title || ''].concat(e.causes || []).concat(e.stack ? ['', e.stack] : []).join(NL);
  }
  function reportText() {
    var parts = [T('播放失败报告') + (subjectTitle ? ' · ' + subjectTitle : '')].concat((data ? data.entries : []).map(entryText));
    var prev = data && data.previous;
    if (prev) parts = parts.concat(['== ' + T('上一集：{0}', prev.episode) + ' =='], prev.entries.map(entryText));
    return parts.join(NL + NL);
  }
  function copy() {
    var ta = el('fail-copy');
    ta.value = reportText();
    ta.hidden = false;
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, ta.value.length);
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) {}
    if (ok) ta.hidden = true;
    window.toast(ok ? T('已复制') : T('请长按文本框手动复制'));
  }
  function open() {
    if (!sheet) build();
    data = null;
    el('fail-copy').hidden = true;
    window.setHtml(el('fail-list'), '<p class="empty">' + T('读取中…') + '</p>');
    window.sheets.open(sheet);
    load();
  }
  document.addEventListener('click', function (e) {
    if (e.target.closest('[data-fails]')) open();
    else if (e.target.closest('#fail-close')) window.sheets.close(sheet);
    else if (e.target.closest('#fail-copy-btn')) copy();
    else if (e.target.closest('#fail-reload')) load();
  });
  // 面板开着时又失败了 (播放卡上的次数变了) 就重读
  window.remoteHooks.render.push(function (s) {
    var n = (s.failures || 0) + ':' + (s.prevFailures || 0);
    subjectTitle = s.title || '';
    if (sheet && !sheet.hidden && n !== shownCount) load();
    shownCount = n;
  });
})();
""".trimIndent()
