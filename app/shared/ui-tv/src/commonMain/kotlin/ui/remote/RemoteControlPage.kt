/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import me.him188.ani.app.ui.foundation.lan.escapeHtml
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.mediafetch_request_editor_episode_ep
import me.him188.ani.app.ui.lang.mediafetch_request_editor_episode_ep_supporting
import me.him188.ani.app.ui.lang.mediafetch_request_editor_episode_info_supporting
import me.him188.ani.app.ui.lang.mediafetch_request_editor_episode_sort
import me.him188.ani.app.ui.lang.mediafetch_request_editor_episode_sort_supporting
import me.him188.ani.app.ui.lang.mediafetch_request_editor_primary_name
import me.him188.ani.app.ui.lang.mediafetch_request_editor_primary_name_supporting
import me.him188.ani.app.ui.lang.mediafetch_request_editor_restore_names
import me.him188.ani.app.ui.lang.mediafetch_request_editor_save_and_refresh
import me.him188.ani.app.ui.lang.mediafetch_request_editor_secondary_names
import me.him188.ani.app.ui.lang.mediafetch_request_editor_secondary_names_supporting
import me.him188.ani.app.ui.lang.mediafetch_request_editor_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * 手机控制中心的网页: 底部四个标签 (搜索 / 结果 / 播放器 / 数据源) 的单页应用.
 *
 * 带少量脚本 (与「搜索输入」时代的纯表单页不同): 数据源结果是陆续回来的, 不刷新就看不到新结果, 所以「播放器」
 * 标签每秒轮询一次 `api/player` (带版本号, 没变化服务端只回一个标志). 提交都走 `fetch` 表单编码,
 * 回应是 `{ok, message}`, 页面底部弹一条提示. 微信 / 支付宝内置浏览器与系统浏览器都能跑.
 *
 * **脚本里不写 `$`**: 这段是 Kotlin 原始字符串, `$` 会被当成模板插值; 字符串拼接一律用 `+`.
 */
internal fun renderRemoteControlPage(
    initialTab: String,
    searchFormHtml: String,
    requestSectionHtml: String,
): String =
    """
    <!doctype html>
    <html lang="zh-CN">
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <title>Animeko 控制中心</title>
    <style>
    """.trimIndent() + "\n" + STYLE + "\n" + """
    </style>
    </head>
    <body>
    <header>Animeko 控制中心</header>
    <section class="tab" id="tab-search" hidden>
    """.trimIndent() + "\n" + searchFormHtml + "\n" + """
    </section>
    <section class="tab" id="tab-results" hidden>
    <div id="res-head"></div>
    <div id="res-list" class="list"></div>
    <div id="res-foot"></div>
    </section>
    <section class="tab" id="tab-player" hidden>
    <div id="player-now"></div>
    <div id="player-episode"></div>
    """.trimIndent() + "\n" + requestSectionHtml + "\n" + """
    <div id="player-chips"></div>
    <div id="player-filters"></div>
    <div id="player-sources"></div>
    </section>
    <section class="tab" id="tab-sources" hidden>
    <p class="hint">修改立即保存。正在播放的这一集不受影响，下一集或重新进入播放器时生效。订阅来的源只能启用或停用。</p>
    <details class="card req" id="src-import-box"><summary>导入 JSON</summary>
    <form id="src-import"><textarea name="text" rows="8" spellcheck="false" placeholder="粘贴数据源 JSON：单个、列表或订阅内容都可以"></textarea>
    <div class="row"><button type="submit" class="primary">导入</button></div></form></details>
    <div id="src-add"></div>
    <div id="src-list"></div>
    </section>
    <div id="toast"></div>
    <nav class="tabbar"><button data-tab="search">搜索</button><button data-tab="results">结果</button><button data-tab="player">播放器</button><button data-tab="sources">数据源</button></nav>
    <script>
    var INITIAL_TAB = '$initialTab';
    """.trimIndent() + "\n" + SCRIPT + "\n" + REQUEST_SCRIPT + "\n" + CONTROL_SCRIPT + "\n" + SOURCES_SCRIPT + "\n" + """
    </script>
    </body>
    </html>
    """.trimIndent()

private val STYLE = """
:root { --p: #6750a4; --bg: #fafafa; --card: #fff; --fg: #1c1b1f; --sub: #49454f; --mute: #79747e; --chip: #e7e0ec; }
* { box-sizing: border-box; }
body { font-family: system-ui, sans-serif; margin: 0; background: var(--bg); color: var(--fg); -webkit-tap-highlight-color: transparent; }
header { padding: 16px 16px 4px; font-size: 20px; font-weight: 700; }
.tab { padding: 8px 16px 150px; }
h2 { font-size: 14px; font-weight: 600; color: var(--sub); margin: 22px 0 8px; }
h2 small { font-weight: 400; color: var(--mute); }
p.hint { color: var(--mute); font-size: 13px; margin: 8px 0; }
input[type=text], textarea { width: 100%; font: inherit; font-size: 16px; padding: 12px 14px; border: 1px solid #cac4d0; border-radius: 12px; background: #fff; color: var(--fg); }
input[type=text]:focus, textarea:focus { outline: 2px solid var(--p); border-color: transparent; }
.pills { display: flex; flex-wrap: wrap; gap: 8px; }
.pills label { position: relative; }
.pills input { position: absolute; opacity: 0; width: 0; height: 0; }
.pills span { display: inline-block; padding: 7px 14px; border-radius: 18px; background: var(--chip); color: #1d192b; font-size: 14px; line-height: 1.3; user-select: none; }
.pills input:checked + span { background: var(--p); color: #fff; }
button { font: inherit; border: 0; cursor: pointer; }
.primary { background: var(--p); color: #fff; font-weight: 600; padding: 13px 18px; border-radius: 14px; font-size: 16px; }
.wide { width: 100%; }
.bar { position: fixed; left: 0; right: 0; bottom: 56px; padding: 10px 16px; background: linear-gradient(to top, var(--bg) 70%, rgba(250,250,250,0)); }
.tabbar { position: fixed; left: 0; right: 0; bottom: 0; height: calc(56px + env(safe-area-inset-bottom)); padding-bottom: env(safe-area-inset-bottom); display: flex; background: #fff; border-top: 1px solid #e6e0e9; }
.tabbar button { flex: 1; background: none; font-size: 15px; color: var(--mute); }
.tabbar button.on { color: var(--p); font-weight: 700; }
.card { background: var(--card); border-radius: 14px; padding: 14px 16px; box-shadow: 0 1px 3px rgba(0,0,0,.08); }
.now-title { font-size: 18px; font-weight: 700; }
.now-ep { color: var(--sub); font-size: 14px; margin-top: 2px; }
.now-src { color: var(--mute); font-size: 13px; margin-top: 8px; word-break: break-all; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
.chip { font-size: 13px; padding: 6px 12px; border-radius: 14px; background: var(--chip); color: #1d192b; }
.chip.failed, .chip.captcha, .chip.limited { background: #ffdad6; color: #410002; }
.chip.loading { opacity: .6; }
.chip.on { background: var(--p); color: #fff; opacity: 1; }
.list { display: flex; flex-direction: column; gap: 8px; }
.item { display: block; text-align: left; width: 100%; background: var(--card); color: var(--fg); border-radius: 12px; padding: 12px 14px; box-shadow: 0 1px 3px rgba(0,0,0,.08); position: relative; }
.item .t { display: block; font-size: 14px; line-height: 1.4; word-break: break-all; }
.item .m { display: block; font-size: 12px; color: var(--mute); margin-top: 4px; }
.item.sel { outline: 2px solid var(--p); }
.item.sel .t { padding-right: 68px; }
.item .badge { position: absolute; top: 10px; right: 10px; font-size: 11px; color: #fff; background: var(--p); padding: 2px 8px; border-radius: 10px; }
.empty { text-align: center; padding: 40px 8px; color: var(--sub); }
.req { margin-top: 12px; padding: 0; }
.req summary { padding: 14px 16px; font-weight: 600; cursor: pointer; list-style: none; }
.req summary::-webkit-details-marker { display: none; }
.req summary small { font-weight: 400; color: var(--mute); margin-left: 6px; }
.req form { padding: 0 16px 16px; }
.f { display: block; margin-top: 12px; }
.f > span { display: block; font-size: 13px; color: var(--sub); margin-bottom: 6px; }
.f > em { display: block; font-style: normal; font-size: 12px; color: var(--mute); margin-top: 4px; }
.row { display: flex; gap: 10px; margin-top: 16px; }
.row > * { flex: 1; }
.ghost { background: var(--chip); color: #1d192b; font-weight: 600; padding: 13px 12px; border-radius: 14px; font-size: 15px; }
.progress { margin-top: 14px; }
.track { height: 4px; border-radius: 2px; background: var(--chip); overflow: hidden; }
.track > div { height: 100%; width: 0; background: var(--p); }
.time { font-size: 12px; color: var(--mute); margin-top: 6px; text-align: right; font-variant-numeric: tabular-nums; }
.ctrls { display: flex; gap: 10px; margin-top: 10px; }
.ctrls button { flex: 1; background: var(--chip); color: #1d192b; font-weight: 600; padding: 12px 8px; border-radius: 12px; font-size: 15px; }
.ctrls button.main { background: var(--p); color: #fff; }
.filters { display: flex; gap: 8px; margin-top: 12px; }
.filters .sel { flex: 1; min-width: 0; }
.filters .sel span { display: block; font-size: 12px; color: var(--mute); margin-bottom: 4px; }
.filters select { width: 100%; font: inherit; font-size: 14px; padding: 8px 6px; border: 1px solid #cac4d0; border-radius: 10px; background: #fff; color: var(--fg); }
.toggle { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--sub); margin-top: 10px; }
.item.ex { opacity: .72; }
.item.blocked { opacity: .45; }
.item .why { display: block; font-size: 12px; color: #b3261e; margin-top: 4px; }
.episode { display: block; margin-top: 12px; }
.episode span { display: block; font-size: 12px; color: var(--mute); margin-bottom: 4px; }
.episode select { width: 100%; font: inherit; font-size: 15px; padding: 10px; border: 1px solid #cac4d0; border-radius: 12px; background: #fff; color: var(--fg); }
.qbox { position: relative; }
.sugg { position: absolute; left: 0; right: 0; top: calc(100% + 4px); background: #fff; border-radius: 12px; box-shadow: 0 6px 20px rgba(0,0,0,.16); overflow: hidden; z-index: 20; }
/* 下拉行用专用类名: 查询请求表单的 .row > * { flex: 1 } 会把行里两个按钮拉成等宽, × 跑到中间 */
.sugg .srow { display: flex; align-items: center; }
.sugg .srow + .srow { border-top: 1px solid #f0edf2; }
.sugg .h { flex: 1; min-width: 0; text-align: left; background: none; padding: 12px 14px; font-size: 15px; color: var(--fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sugg .h::before { content: "◷"; color: var(--mute); margin-right: 10px; }
.sugg .x { flex: none; background: none; color: var(--mute); padding: 12px 16px; font-size: 18px; line-height: 1; }
.toggles { display: flex; flex-wrap: wrap; gap: 0 18px; }
.src-item { background: var(--card); border-radius: 12px; padding: 12px 14px; box-shadow: 0 1px 3px rgba(0,0,0,.08); margin-top: 10px; }
.src-item.off .src-name { color: var(--mute); }
.src-top { display: flex; align-items: center; gap: 10px; }
.src-sw input { width: 20px; height: 20px; }
.src-name { flex: 1; min-width: 0; font-size: 15px; font-weight: 600; word-break: break-all; }
.src-name small { display: block; font-weight: 400; font-size: 12px; color: var(--mute); margin-top: 2px; }
.src-desc { font-size: 12px; color: var(--mute); margin-top: 6px; word-break: break-all; }
.src-btns { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.src-btns button { background: var(--chip); color: #1d192b; padding: 6px 12px; border-radius: 10px; font-size: 13px; }
.src-btns button:disabled { opacity: .4; }
.src-btns .src-danger { background: #ffdad6; color: #410002; }
.src-panel { margin-top: 10px; }
.src-panel textarea, #src-add textarea, #src-import textarea { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.f select { width: 100%; font: inherit; font-size: 15px; padding: 10px; border: 1px solid #cac4d0; border-radius: 12px; background: #fff; color: var(--fg); }
.src-bool { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
#src-import-box { margin-top: 10px; }
.je-tabs { display: flex; gap: 6px; align-items: center; margin: 4px 0 6px; }
.je-tab, .je-fmt { background: var(--chip); color: #1d192b; padding: 6px 14px; border-radius: 14px; font-size: 13px; }
.je-tab.on { background: var(--p); color: #fff; }
.je-fmt { margin-left: auto; }
.je-group { border: 1px solid #e6e0e9; border-radius: 12px; margin-top: 10px; background: #fcfbfd; }
.je-group > summary { padding: 10px 12px; font-weight: 600; font-size: 14px; cursor: pointer; }
.je-group code, .je-f code, .je-bool code { font-size: 11px; font-weight: 400; color: var(--mute); margin-left: 6px; }
.je-body { padding: 0 12px 12px; }
.je-f { display: block; margin-top: 10px; }
.je-f > span { display: block; font-size: 13px; color: var(--sub); margin-bottom: 4px; }
.je-f input[type=text], .je-f textarea { font-family: ui-monospace, Menlo, monospace; font-size: 13px; padding: 10px 12px; }
.je-bool { display: flex; align-items: center; gap: 8px; margin-top: 10px; font-size: 14px; }
.je-bool input { width: 20px; height: 20px; flex: none; }
#res-head { margin: 4px 0 10px; }
.res-q { font-size: 17px; font-weight: 700; word-break: break-all; }
.res-q small { font-size: 12px; font-weight: 400; color: var(--mute); margin-left: 8px; }
.res-f { font-size: 12px; color: var(--sub); margin-top: 2px; }
.res-item { padding-right: 58px; }
.res-item .t { font-size: 15px; font-weight: 600; }
.res-rate { display: block; font-size: 12px; color: var(--sub); margin-top: 3px; }
.res-play { position: absolute; right: 12px; top: 50%; transform: translateY(-50%); width: 34px; height: 34px; border-radius: 17px; background: var(--chip); color: var(--p); display: flex; align-items: center; justify-content: center; font-size: 12px; }
.res-item.busy { opacity: .55; }
.res-r18 { display: inline-block; font-size: 10px; font-weight: 700; background: #ffdad6; color: #410002; border-radius: 6px; padding: 1px 5px; margin-left: 6px; vertical-align: 2px; }
#res-foot { margin-top: 12px; }
.res-end { text-align: center; }
#toast { position: fixed; left: 50%; bottom: 132px; transform: translateX(-50%); background: rgba(28,27,31,.92); color: #fff; padding: 10px 16px; border-radius: 20px; font-size: 14px; opacity: 0; transition: opacity .2s; pointer-events: none; max-width: 86%; text-align: center; }
#toast.on { opacity: 1; }
""".trimIndent()

private val SCRIPT = """
(function () {
  var cur = null, ver = '', busy = false;
  // 数据源胶囊的筛选 (null = 全部) 与最近一次完整状态: 点胶囊时就地重画, 不等下一次轮询
  var srcFilter = null, lastState = null;
  var hooks = { render: [], unavailable: [], playback: [] };
  window.remoteHooks = hooks;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  window.esc = esc;
  function mmss(ms) {
    var t = Math.max(0, Math.floor(ms / 1000));
    var h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), sec = t % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') + m : String(m)) + ':' + (sec < 10 ? '0' : '') + sec;
  }
  function toast(msg) {
    var t = document.getElementById('toast');
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(t.hideTimer);
    t.hideTimer = setTimeout(function () { t.classList.remove('on'); }, 2400);
  }
  window.toast = toast;
  function post(path, data) {
    return fetch(path, { method: 'POST', body: new URLSearchParams(data) }).then(function (r) { return r.json(); });
  }
  window.post = post;
  function fail() { toast('发送失败，请确认手机与电视在同一网络'); }
  window.fail = fail;

  function show(tab) {
    cur = tab;
    var secs = document.querySelectorAll('.tab');
    for (var i = 0; i < secs.length; i++) secs[i].hidden = secs[i].id !== 'tab-' + tab;
    var bs = document.querySelectorAll('.tabbar button');
    for (var j = 0; j < bs.length; j++) bs[j].classList.toggle('on', bs[j].getAttribute('data-tab') === tab);
    if (history.replaceState) history.replaceState(null, '', '#' + tab);
    if (tab === 'player') poll(true);
    if (tab === 'results') pollResults(true);
    if (tab === 'sources' && window.loadSources) window.loadSources();
  }
  document.querySelector('.tabbar').addEventListener('click', function (e) {
    var b = e.target.closest('button');
    if (b) show(b.getAttribute('data-tab'));
  });

  // ---- 搜索 ----
  // 搜索框下拉的搜索记录 (与电视共用同一份): 聚焦或输入时弹出, 按包含过滤; 点一条直接搜, × 删除.
  // 变量别叫 history: 那会遮住浏览器的 window.history, 切标签时改地址栏要用它
  var searchForm = document.getElementById('search-form');
  var qInput = document.getElementById('q');
  var sugg = document.getElementById('sugg');
  var searchHistory = [];
  function loadHistory() {
    fetch('api/search/history')
      .then(function (r) { return r.json(); })
      .then(function (r) {
        searchHistory = r.items || [];
        if (document.activeElement === qInput) showSugg();
      })
      .catch(function () {});
  }
  function showSugg() {
    var typed = qInput.value.trim();
    var k = typed.toLowerCase();
    var list = searchHistory.filter(function (h) {
      return h !== typed && (!k || h.toLowerCase().indexOf(k) >= 0);
    }).slice(0, 8);
    if (!list.length) { sugg.hidden = true; return; }
    sugg.innerHTML = list.map(function (h) {
      return '<div class="srow"><button type="button" class="h" data-q="' + esc(h) + '">' + esc(h) + '</button>' +
        '<button type="button" class="x" data-del="' + esc(h) + '" aria-label="删除">×</button></div>';
    }).join('');
    sugg.hidden = false;
  }
  function doSearch() {
    sugg.hidden = true;
    qInput.blur();
    post('api/search', new FormData(searchForm))
      .then(function (r) {
        toast(r.message);
        if (!r.ok) return;
        setTimeout(loadHistory, 800);
        // 搜完就去「结果」: 电视换上这次的结果之前服务端回 pending, 页面显示「正在搜索」
        show('results');
      })
      .catch(fail);
  }
  qInput.addEventListener('focus', showSugg);
  qInput.addEventListener('input', showSugg);
  qInput.addEventListener('blur', function () { setTimeout(function () { sugg.hidden = true; }, 150); });
  // 按下时不让输入框失焦: 否则 blur 先把下拉收起, 点击落空
  sugg.addEventListener('mousedown', function (e) { e.preventDefault(); });
  sugg.addEventListener('click', function (e) {
    var del = e.target.closest('[data-del]');
    if (del) {
      var t = del.getAttribute('data-del');
      searchHistory = searchHistory.filter(function (h) { return h !== t; });
      showSugg();
      post('api/search/history/delete', { q: t }).catch(fail);
      return;
    }
    var b = e.target.closest('[data-q]');
    if (!b) return;
    qInput.value = b.getAttribute('data-q');
    doSearch();
  });
  searchForm.addEventListener('submit', function (e) { e.preventDefault(); doSearch(); });
  loadHistory();

  // ---- 结果 ----
  // 电视搜索页已经加载的结果原样列过来 (纯文字); 点一条电视直接进播放页, 随后切到「播放器」好在手机上选源.
  // 同播放器标签: 带版本号轮询, 没变化服务端只回 same, 列表只在内容变了时重画 (不打断手机上的滚动)
  var resVer = '', resBusy = false, resAgain = false, resLast = null, resMoreAsked = false;
  var resHead = document.getElementById('res-head');
  var resList = document.getElementById('res-list');
  var resFoot = document.getElementById('res-foot');
  function pollResults(force) {
    if (document.hidden || cur !== 'results') return;
    if (resBusy) { resAgain = true; return; }
    resBusy = true;
    fetch('api/search/results?v=' + (force ? '' : encodeURIComponent(resVer)))
      .then(function (r) { return r.json(); })
      .then(function (s) {
        resBusy = false;
        if (!s.same) { resVer = s.v; renderResults(s); }
        if (resAgain) { resAgain = false; pollResults(true); }
      })
      .catch(function () { resBusy = false; });
  }
  setInterval(function () { pollResults(false); }, 1000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) pollResults(true); });
  function resEmpty(msg, hint) {
    resHead.innerHTML = '';
    resList.innerHTML = '<div class="empty"><p>' + msg + '</p>' + (hint ? '<p class="hint">' + hint + '</p>' : '') + '</div>';
    resFoot.innerHTML = '';
  }
  function renderResults(s) {
    resLast = s;
    if (s.pending) { resEmpty('正在电视上搜索…'); return; }
    if (!s.available) {
      resEmpty('还没有搜索结果', '在「搜索」里搜一下，电视上的结果会列在这里，点一条就在电视上开始播放');
      return;
    }
    var items = s.items || [];
    resHead.innerHTML = '<div class="res-q">' + (s.keywords ? '「' + esc(s.keywords) + '」' : '筛选结果') +
      '<small>' + (s.refreshing ? '搜索中' : items.length + ' 条') + '</small></div>' +
      (s.filters ? '<div class="res-f">' + esc(s.filters) + '</div>' : '');
    if (!items.length) {
      resList.innerHTML = '<div class="empty"><p>' + (s.refreshing ? '正在电视上搜索…'
        : s.error ? '搜索失败：' + esc(s.error) : '没有找到相关条目') + '</p></div>';
    } else {
      resList.innerHTML = items.map(function (x) {
        return '<button class="item res-item" data-sid="' + x.id + '">' +
          '<span class="t">' + esc(x.title) + (x.nsfw ? '<span class="res-r18">R18</span>' : '') + '</span>' +
          (x.info ? '<span class="m">' + esc(x.info) + '</span>' : '') +
          (x.rating ? '<span class="res-rate">' + esc(x.rating) + '</span>' : '') +
          '<span class="res-play" aria-hidden="true">▶</span></button>';
      }).join('');
    }
    var foot = '';
    if (items.length) {
      if (s.appending) foot = '<p class="hint res-end">正在加载…</p>';
      else if (s.error) foot = '<button type="button" class="ghost wide" data-more="1">加载失败，点这里重试</button>';
      else if (s.end) foot = '<p class="hint res-end">没有更多了</p>';
      else if (s.live) foot = '<button type="button" class="ghost wide" data-more="1">加载更多</button>';
      else foot = '<p class="hint res-end">电视已离开搜索页，回到搜索页后可以继续加载</p>';
    }
    resFoot.innerHTML = foot;
    observeMore();
  }
  // 翻到底自动加载下一页 (没有 IntersectionObserver 的浏览器就点按钮); 出错时不自动重试, 等人点
  var resIo = window.IntersectionObserver ? new IntersectionObserver(function (es) {
    if (es.some(function (e) { return e.isIntersecting; })) askMore();
  }, { rootMargin: '300px' }) : null;
  function observeMore() {
    if (!resIo) return;
    resIo.disconnect();
    var b = resFoot.querySelector('[data-more]');
    if (b && resLast && !resLast.error) resIo.observe(b);
  }
  function askMore() {
    if (resMoreAsked) return;
    resMoreAsked = true;
    post('api/search/results/more', {})
      .then(function (r) {
        resMoreAsked = false;
        if (!r.ok) toast(r.message);
        pollResults(true);
      })
      .catch(function () { resMoreAsked = false; fail(); });
  }
  resFoot.addEventListener('click', function (e) { if (e.target.closest('[data-more]')) askMore(); });
  resList.addEventListener('click', function (e) {
    var b = e.target.closest('.res-item');
    if (!b || b.classList.contains('busy')) return;
    b.classList.add('busy');
    post('api/search/play', { id: b.getAttribute('data-sid') })
      .then(function (r) {
        b.classList.remove('busy');
        toast(r.message);
        if (r.player) setTimeout(function () { show('player'); }, 1200);
      })
      .catch(function () { b.classList.remove('busy'); fail(); });
  });

  // ---- 播放器 ----
  // 下拉筛选 (空 = 全部) 与「显示被排除的」: 随轮询发给服务端过滤 (每源 40 条的截断在筛选之后做)
  var fRes = '', fSub = '', fAll = '', fEx = false, fFull = false;
  // 轮询进行中又要求刷新 (比如刚切了筛选): 记一笔, 这次返回后立刻补拉, 否则要等到下一个周期
  var again = false;
  function query() {
    return '&res=' + encodeURIComponent(fRes) + '&sub=' + encodeURIComponent(fSub) +
      '&all=' + encodeURIComponent(fAll) + '&ex=' + (fEx ? '1' : '') +
      '&full=' + (fFull && srcFilter ? encodeURIComponent(srcFilter) : '');
  }
  function poll(force) {
    if (document.hidden || cur !== 'player') return;
    if (busy) { again = true; return; }
    busy = true;
    fetch('api/player?v=' + (force ? '' : encodeURIComponent(ver)) + query())
      .then(function (r) { return r.json(); })
      .then(function (s) {
        busy = false;
        if (!s.same) {
          ver = s.v;
          render(s);
        }
        if (s.playback) hooks.playback.forEach(function (h) { h(s.playback); });
        if (again) { again = false; poll(true); }
      })
      .catch(function () { busy = false; });
  }
  window.poll = poll;
  setInterval(function () { poll(false); }, 1500);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) poll(true); });

  function render(s) {
    var now = document.getElementById('player-now');
    var chips = document.getElementById('player-chips');
    var src = document.getElementById('player-sources');
    if (!s.available) {
      var msg = s.reason === 'background'
        ? '电视当前不在播放页' + (s.title ? '：' + esc(s.title) : '')
        : '电视上没有正在播放的内容';
      if (s.upNext) {
        // 什么都没在播: 同动作面板那张「接下来播放」卡, 点一下电视直接进播放页
        var u = s.upNext;
        // 复用播放时那张卡的结构与样式: 剧名 / 副标题 / 进度 / 按钮排, 只是按钮只有一颗
        now.innerHTML = '<div class="card"><div class="now-title">' + esc(u.title) + '</div>' +
          '<div class="now-src">' + (u.continuing ? '继续播放' : '接下来播放') + (u.episode ? '：' + esc(u.episode) : '') + '</div>' +
          (u.continuing && u.duration
            ? '<div class="progress"><div class="track"><div style="width:' + Math.min(100, u.position * 100 / u.duration) +
              '%"></div></div><div class="time">' + mmss(u.position) + ' / ' + mmss(u.duration) + '</div></div>'
            : '') +
          '<div class="ctrls"><button class="main" id="play-upnext">在电视上播放</button></div></div>';
      } else {
        now.innerHTML = '<div class="empty"><p>' + msg + '</p>' +
          (s.reason === 'background' ? '<button class="primary" id="open-player">在电视上打开播放器</button>' : '') + '</div>';
      }
      chips.innerHTML = '';
      document.getElementById('player-episode').innerHTML = '';
      lastEpisodesHtml = '';
      document.getElementById('player-filters').innerHTML = '';
      lastFiltersHtml = '';
      src.innerHTML = '';
      hooks.unavailable.forEach(function (h) { h(s); });
      return;
    }
    now.innerHTML = '<div class="card"><div class="now-title">' + esc(s.title) + '</div>' +
      '<div class="now-src">' + (s.selectedTitle ? (s.background ? '当前数据源：' : '正在播放：') + esc(s.selectedTitle) : '尚未选择数据源') + '</div>' +
      (s.background
        ? '<p class="hint">电视未在播放页：可以照常换源和修改查询条件，新数据源会在后台加载，回到播放器即可继续播放。</p>' +
          '<button class="primary wide" id="open-player">在电视上打开播放器</button>'
        : '<div id="player-controls"></div>') +
      '</div>';
    lastState = s;
    chips.innerHTML = renderChips(s);
    renderEpisodes(s);
    renderFilters(s);
    src.innerHTML = renderList(s);
    hooks.render.forEach(function (h) { h(s); });
  }

  function renderChips(s) {
    // 选中的源已经不在了 (比如改了查询条件后被禁用), 筛选自动回到「全部」
    if (srcFilter && !s.sources.some(function (x) { return x.id === srcFilter; }) &&
        !s.groups.some(function (g) { return g.id === srcFilter; })) srcFilter = null;
    var h = '<div class="chips"><button class="chip' + (srcFilter ? '' : ' on') + '" data-src="">全部</button>';
    s.sources.forEach(function (x) {
      var n = x.state === 'loading' ? '…' : x.state === 'captcha' ? '需验证' : x.state === 'failed' ? '失败' : x.state === 'limited' ? '限流' : x.count;
      h += '<button class="chip ' + x.state + (srcFilter === x.id ? ' on' : '') + '" data-src="' + esc(x.id) + '">' +
        esc(x.name) + ' ' + n + '</button>';
    });
    return h + '</div>';
  }

  // 选集下拉框: 同样只在内容变了时才重画 (理由同下面的筛选下拉框)
  var lastEpisodesHtml = '';
  function renderEpisodes(s) {
    var list = s.episodes || [];
    var h = '';
    if (list.length) {
      h = '<label class="episode"><span>选集</span><select id="ep-select">';
      list.forEach(function (e) {
        h += '<option value="' + e.id + '"' + (e.current ? ' selected' : '') + '>' +
          (e.current ? '▶ ' : '') + esc(e.label) + '</option>';
      });
      h += '</select></label>';
    }
    if (h !== lastEpisodesHtml) {
      document.getElementById('player-episode').innerHTML = h;
      lastEpisodesHtml = h;
    }
  }
  document.getElementById('player-episode').addEventListener('change', function (e) {
    if (e.target.id !== 'ep-select') return;
    post('api/player/episode', { id: e.target.value })
      .then(function (r) { toast(r.message); poll(true); })
      .catch(fail);
  });

  // 下拉框只在内容真的变了时才重画: 每秒一次的轮询若无条件重画, 手机上正打开的选择器会被关掉
  var lastFiltersHtml = '';
  function dropdown(key, label, list, cur) {
    var h = '<label class="sel"><span>' + label + '</span><select data-f="' + key + '"><option value="">全部</option>';
    var has = false;
    (list || []).forEach(function (o) {
      if (o.value === cur) has = true;
      h += '<option value="' + esc(o.value) + '"' + (o.value === cur ? ' selected' : '') + '>' +
        esc(o.label) + '（' + o.count + '）</option>';
    });
    // 选着的那一项在新结果里没有了: 仍然留在框里, 让人看得出为什么列表是空的
    if (cur && !has) h += '<option value="' + esc(cur) + '" selected>' + esc(cur) + '（0）</option>';
    return h + '</select></label>';
  }
  // 「显示全部 N 条」: 只在点了某个数据源胶囊、而且这个源确实没列全时出现 (勾着的时候一直显示, 好取消)
  function fullToggle(s) {
    if (!srcFilter) return '';
    var g = s.groups.filter(function (x) { return x.id === srcFilter; })[0];
    if (!g || (!fFull && !(g.more > 0))) return '';
    return '<label class="toggle"><input type="checkbox" data-f="full"' + (fFull ? ' checked' : '') + '>显示全部 ' +
      g.total + ' 条</label>';
  }
  function renderFilters(s) {
    var f = s.filters || {};
    var h = '<div class="filters">' +
      dropdown('res', '分辨率', f.resolution, fRes) +
      dropdown('sub', '字幕', f.subtitle, fSub) +
      dropdown('all', '字幕组', f.alliance, fAll) + '</div>' +
      '<div class="toggles"><label class="toggle"><input type="checkbox" data-f="ex"' + (fEx ? ' checked' : '') + '>显示被排除的资源' +
      (s.excludedCount ? '（' + s.excludedCount + ' 条）' : '') + '</label>' + fullToggle(s) + '</div>';
    if (h !== lastFiltersHtml) {
      document.getElementById('player-filters').innerHTML = h;
      lastFiltersHtml = h;
    }
  }

  function renderList(s) {
    var groups = srcFilter ? s.groups.filter(function (g) { return g.id === srcFilter; }) : s.groups;
    var h = '';
    if (!groups.length) {
      var one = srcFilter ? s.sources.filter(function (x) { return x.id === srcFilter; })[0] : null;
      var why = (fRes || fSub || fAll) ? '没有符合筛选条件的结果'
        : !srcFilter ? (s.loading ? '正在搜索数据源…' : '没有找到可用的数据源，可以试试修改查询条件')
        : !one ? '这个数据源没有匹配的结果'
        : one.state === 'loading' ? '这个数据源还在搜索…'
        : one.state === 'captcha' ? '这个数据源需要人机验证，请在电视上处理'
        : one.state === 'failed' ? '这个数据源搜索失败'
        : one.state === 'limited' ? '这个数据源被限流了，稍后再试'
        : '这个数据源没有匹配的结果';
      if (!fEx && s.excludedCount) why += '，可以勾选「显示被排除的资源」看看';
      h += '<p class="hint">' + why + '</p>';
    }
    groups.forEach(function (g) {
      h += '<h2>' + esc(g.name) + ' <small>' + g.total + ' 条</small></h2><div class="list">';
      g.items.forEach(function (it) {
        var sel = it.id === s.selectedId;
        var meta = [it.cached ? '已缓存' : '', it.resolution, it.subtitles, it.alliance, it.size].filter(Boolean).join(' · ');
        h += '<button class="item' + (sel ? ' sel' : '') + (it.excluded ? ' ex' : '') + (it.blocked ? ' blocked' : '') +
          '" data-id="' + esc(it.id) + '"' + (it.blocked ? ' data-blocked="' + esc(it.reason || '') + '"' : '') + '>' +
          '<span class="t">' + esc(it.title) + '</span><span class="m">' + esc(meta) + '</span>' +
          (it.excluded ? '<span class="why">已排除：' + esc(it.reason || '') + '</span>' : '') +
          (sel ? '<span class="badge">' + (s.background ? '当前' : '正在播放') + '</span>' : '') + '</button>';
      });
      h += '</div>';
      if (g.more > 0) h += '<p class="hint">还有 ' + g.more + ' 条未列出，' +
        (srcFilter ? '可以勾选上面的「显示全部」' : '点上面这个数据源的胶囊后可以选择显示全部') + '</p>';
    });
    return h;
  }

  document.getElementById('player-now').addEventListener('click', function (e) {
    if (e.target.id === 'play-upnext') {
      post('api/player/upnext', {})
        .then(function (r) { toast(r.message); setTimeout(function () { poll(true); }, 1500); })
        .catch(fail);
      return;
    }
    if (e.target.id !== 'open-player') return;
    post('api/player/open', {}).then(function (r) { toast(r.message); setTimeout(function () { poll(true); }, 1500); }).catch(fail);
  });
  document.getElementById('player-chips').addEventListener('click', function (e) {
    var c = e.target.closest('.chip');
    if (!c || !lastState) return;
    // 点已选中的源或「全部」= 取消筛选; 数据源筛选纯在本地做, 就地重画
    var id = c.getAttribute('data-src');
    srcFilter = !id || id === srcFilter ? null : id;
    this.innerHTML = renderChips(lastState);
    document.getElementById('player-sources').innerHTML = renderList(lastState);
    // 「显示全部」只对当时选中的那个源: 换源就作废, 并重新拉一次截断后的列表
    var hadFull = fFull;
    fFull = false;
    renderFilters(lastState);
    if (hadFull) poll(true);
  });
  document.getElementById('player-filters').addEventListener('change', function (e) {
    var k = e.target.getAttribute('data-f');
    if (!k) return;
    if (k === 'ex') fEx = e.target.checked;
    else if (k === 'full') fFull = e.target.checked;
    else if (k === 'res') fRes = e.target.value;
    else if (k === 'sub') fSub = e.target.value;
    else if (k === 'all') fAll = e.target.value;
    poll(true);
  });
  document.getElementById('player-sources').addEventListener('click', function (e) {
    var b = e.target.closest('.item');
    if (!b || b.classList.contains('sel')) return;
    if (b.hasAttribute('data-blocked')) { toast('不能选择：' + b.getAttribute('data-blocked')); return; }
    var olds = document.querySelectorAll('.item.sel');
    for (var i = 0; i < olds.length; i++) olds[i].classList.remove('sel');
    b.classList.add('sel');
    post('api/player/select', { id: b.getAttribute('data-id') })
      .then(function (r) { toast(r.message); poll(true); })
      .catch(fail);
  });

  var h0 = location.hash.slice(1);
  show(h0 === 'search' || h0 === 'results' || h0 === 'player' || h0 === 'sources' ? h0 : INITIAL_TAB);
})();
""".trimIndent()

/**
 * 「播放器」标签里的「编辑查询请求」: 与电视上的编辑框同一组字段、同一批文案 (按系统语言).
 * 折叠在 `<details>` 里 —— 平时用不上, 展开才占地方. 内容由脚本按 `api/player` 里的 `request` 预填.
 */
internal suspend fun renderPlayerRequestSection(): String {
    suspend fun t(r: StringResource): String = getString(r).escapeHtml()
    return """
<details class="card req" id="req" hidden>
<summary>${t(Lang.mediafetch_request_editor_title)}<small id="req-sum"></small></summary>
<form id="req-form">
<label class="f"><span>${t(Lang.mediafetch_request_editor_primary_name)}</span><input type="text" name="primary" autocomplete="off"><em>${t(Lang.mediafetch_request_editor_primary_name_supporting)}</em></label>
<label class="f"><span>${t(Lang.mediafetch_request_editor_secondary_names)}（每行一个）</span><textarea name="others" rows="3"></textarea><em>${t(Lang.mediafetch_request_editor_secondary_names_supporting)}</em></label>
<p class="hint">${t(Lang.mediafetch_request_editor_episode_info_supporting)}</p>
<label class="f"><span>${t(Lang.mediafetch_request_editor_episode_sort)}</span><input type="text" name="sort" inputmode="decimal" autocomplete="off"><em>${t(Lang.mediafetch_request_editor_episode_sort_supporting)}</em></label>
<label class="f"><span>${t(Lang.mediafetch_request_editor_episode_ep)}</span><input type="text" name="ep" inputmode="decimal" autocomplete="off"><em>${t(Lang.mediafetch_request_editor_episode_ep_supporting)}</em></label>
<div class="row"><button type="button" class="ghost" id="req-reset">${t(Lang.mediafetch_request_editor_restore_names)}</button><button type="submit" class="primary">${t(Lang.mediafetch_request_editor_save_and_refresh)}</button></div>
</form>
</details>
""".trim()
}

/**
 * 查询请求表单的脚本. **只在用户没动过表单时才按服务端刷新内容** —— 轮询每秒一次, 不这样的话正在输入的
 * 字会被冲掉; 提交成功后放开, 下一次轮询拿到的就是电视上生效的新条件.
 */
private val REQUEST_SCRIPT = """
(function () {
  var hooks = window.remoteHooks;
  var form = document.getElementById('req-form');
  var box = document.getElementById('req');
  var NL = String.fromCharCode(10);
  var dirty = false, last = '';
  form.addEventListener('input', function () { dirty = true; });
  function fill(r) {
    form.elements.primary.value = r.primary || '';
    form.elements.others.value = (r.others || []).join(NL);
    form.elements.sort.value = r.sort || '';
    form.elements.ep.value = r.ep || '';
  }
  hooks.render.push(function (s) {
    box.hidden = !s.request;
    if (!s.request) return;
    var key = JSON.stringify(s.request);
    if (key !== last && !dirty) fill(s.request);
    last = key;
    document.getElementById('req-sum').textContent =
      '：' + s.request.primary + (s.requestIsDefault ? '' : '（已修改）');
  });
  hooks.unavailable.push(function () { box.hidden = true; });
  function done(r) {
    toast(r.message);
    if (r.ok) { dirty = false; last = ''; box.open = false; poll(true); }
  }
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    post('api/player/request', new FormData(form)).then(done).catch(fail);
  });
  document.getElementById('req-reset').addEventListener('click', function () {
    post('api/player/request', { reset: '1' }).then(done).catch(fail);
  });
})();
""".trimIndent()

/**
 * 播放控制的脚本: 「正在播放」卡片里的进度条 + 后退 10 秒 / 播放暂停 / 前进 10 秒.
 * 卡片随候选变化整块重画, 所以控件在每次 render 后补上; 进度由每次轮询附带的 `playback` 刷新.
 * 点播放暂停先在本地翻转一次按钮文字, 不等下一次轮询 —— 否则按下去要过一秒才有反应, 像没按到.
 */
private val CONTROL_SCRIPT = """
(function () {
  var hooks = window.remoteHooks;
  var pb = null;
  function two(n) { return (n < 10 ? '0' : '') + n; }
  function fmt(ms) {
    var t = Math.max(0, Math.floor(ms / 1000));
    var h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), sec = t % 60;
    return (h ? h + ':' + two(m) : String(m)) + ':' + two(sec);
  }
  function paint() {
    var box = document.getElementById('player-controls');
    if (!box) return;
    if (!box.firstChild) {
      box.innerHTML =
        '<div class="progress"><div class="track"><div id="pb-fg"></div></div><div class="time" id="pb-time"></div></div>' +
        '<div class="ctrls"><button data-act="back">« 10 秒</button>' +
        '<button data-act="toggle" class="main" id="pb-toggle"></button>' +
        '<button data-act="forward">10 秒 »</button></div>';
    }
    var p = pb || { playing: false, position: 0, duration: 0 };
    document.getElementById('pb-toggle').textContent = p.playing ? '暂停' : '播放';
    document.getElementById('pb-time').textContent = fmt(p.position) + ' / ' + (p.duration ? fmt(p.duration) : '--:--');
    document.getElementById('pb-fg').style.width = (p.duration ? Math.min(100, p.position * 100 / p.duration) : 0) + '%';
  }
  hooks.render.push(function () { paint(); });
  hooks.playback.push(function (p) { pb = p; paint(); });
  document.getElementById('player-now').addEventListener('click', function (e) {
    var b = e.target.closest('[data-act]');
    if (!b) return;
    var act = b.getAttribute('data-act');
    if (act === 'toggle' && pb) { pb.playing = !pb.playing; paint(); }
    post('api/player/control', { action: act })
      .then(function (r) { if (r.message) toast(r.message); poll(true); })
      .catch(fail);
  });
})();
""".trimIndent()

/**
 * 「数据源」标签的脚本 (见 RemoteSources). 列表在切到本标签与每次操作成功后整份重拉; 新增区只在可选类型变了时才
 * 重画, 免得正在填的表单被冲掉. 参数表单按服务端给的参数模型现画, visibleWhen 在本地即时切换.
 * 局域网是 http, 浏览器的剪贴板接口不可用 (只在安全上下文里有), 复制走 execCommand, 不行就提示长按手动复制.
 */
private val SOURCES_SCRIPT = """
(function () {
  var box = document.getElementById('src-list');
  var addBox = document.getElementById('src-add');
  var data = null, lastTemplates = '';

  function getJson(url) { return fetch(url).then(function (r) { return r.json(); }); }
  function load() {
    getJson('api/sources').then(function (d) { data = d; renderAdd(); renderList(); }).catch(fail);
  }
  window.loadSources = load;

  // ---- 参数表单 (通用参数源) ----
  function paramForm(params) {
    return params.map(function (p) {
      var vis = p.visibleWhen
        ? ' data-vis="' + esc(p.visibleWhen.param) + '" data-vals="' + esc(JSON.stringify(p.visibleWhen.values)) + '"' : '';
      var desc = p.description ? '<em>' + esc(p.description) + '</em>' : '';
      var name = 'p:' + p.name;
      if (p.type === 'boolean') {
        return '<label class="f src-bool"' + vis + '><input type="checkbox" name="' + esc(name) + '" value="true"' +
          (p.value ? ' checked' : '') + '>' + esc(p.name) + desc + '</label>';
      }
      var input;
      if (p.type === 'enum') {
        input = '<select name="' + esc(name) + '">' + p.options.map(function (o) {
          return '<option' + (o === p.value ? ' selected' : '') + '>' + esc(o) + '</option>';
        }).join('') + '</select>';
      } else {
        input = '<input type="text" name="' + esc(name) + '" value="' + esc(p.value) + '" placeholder="' +
          esc(p.placeholder || '') + '" autocomplete="off">';
      }
      return '<label class="f"' + vis + '><span>' + esc(p.name) + (p.required ? ' *' : '') + '</span>' + input + desc + '</label>';
    }).join('');
  }
  function applyVis(form) {
    if (!form) return;
    var els = form.querySelectorAll('[data-vis]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var ctl = form.elements['p:' + el.getAttribute('data-vis')];
      var v = !ctl ? '' : ctl.type === 'checkbox' ? String(ctl.checked) : ctl.value;
      el.hidden = JSON.parse(el.getAttribute('data-vals')).indexOf(v) < 0;
    }
  }
  function btns(cancelAct, label) {
    return '<div class="row"><button type="button" class="ghost" data-act="' + cancelAct + '">取消</button>' +
      '<button type="submit" class="primary">' + label + '</button></div>';
  }
  function copyText(ta) {
    ta.focus();
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) {}
    if (!ok && navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(ta.value); ok = true; }
    toast(ok ? '已复制' : '请长按文本框手动复制');
  }

  // ---- JSON 编辑器 (RSS / 选择器源): 按 JSON 结构生成表单, 随时可切到源码 ----
  // 不用任何外部库: 网页是电视在局域网里发的, 手机未必连得上 CDN
  var NL = String.fromCharCode(10);
  var JE_LABELS = {
    name: '名称', description: '描述', iconUrl: '图标地址', tier: '层级', channelTiers: '线路层级',
    searchConfig: '搜索配置', searchUrl: '搜索链接', searchUseOnlyFirstWord: '只用第一个词搜索',
    searchRemoveSpecial: '去掉特殊字符', searchUseSubjectNamesCount: '使用的条目名数量', rawBaseUrl: '基础地址',
    requestInterval: '请求间隔（毫秒）', searchCacheTtl: '搜索缓存时长（毫秒）', subjectFormatId: '条目格式',
    channelFormatId: '线路格式', defaultResolution: '默认分辨率', defaultSubtitleLanguage: '默认字幕语言',
    onlySupportsPlayers: '仅支持的播放器', filterByEpisodeSort: '按集数过滤', filterBySubjectName: '按条目名过滤',
    selectMedia: '选择媒体', matchVideo: '匹配视频'
  };
  function jeLabel(key) {
    if (typeof key === 'number') return '#' + (key + 1);
    var zh = JE_LABELS[key];
    return zh ? esc(zh) + '<code>' + esc(key) + '</code>' : esc(key);
  }
  function jeAttr(path, type, key) {
    return ' data-path="' + esc(JSON.stringify(path)) + '" data-type="' + type + '" data-label="' +
      esc(typeof key === 'number' ? '#' + (key + 1) : (JE_LABELS[key] || key)) + '"';
  }
  function jeFields(obj, path, depth) {
    var keys = Object.keys(obj);
    if (!keys.length) return '<p class="hint">（空）</p>';
    return keys.map(function (k) { return jeField(k, obj[k], path.concat([k]), depth); }).join('');
  }
  function jeField(key, v, path, depth) {
    var label = jeLabel(key);
    if (Array.isArray(v)) {
      if (v.every(function (x) { return x === null || typeof x !== 'object'; })) {
        var elem = v.length && typeof v[0] === 'number' ? 'numlines' : 'lines';
        return '<label class="je-f"><span>' + label + '（每行一个）</span><textarea rows="' + Math.max(2, v.length + 1) + '"' +
          jeAttr(path, elem, key) + '>' + esc(v.join(NL)) + '</textarea></label>';
      }
      return '<details class="je-group"><summary>' + label + '（' + v.length + ' 项）</summary><div class="je-body">' +
        v.map(function (x, i) { return jeField(i, x, path.concat([i]), depth + 1); }).join('') + '</div></details>';
    }
    if (v !== null && typeof v === 'object') {
      return '<details class="je-group"' + (depth === 0 ? ' open' : '') + '><summary>' + label + '</summary>' +
        '<div class="je-body">' + jeFields(v, path, depth + 1) + '</div></details>';
    }
    if (typeof v === 'boolean') {
      return '<label class="je-bool"><input type="checkbox"' + (v ? ' checked' : '') + jeAttr(path, 'bool', key) + '>' +
        label + '</label>';
    }
    if (typeof v === 'number') {
      return '<label class="je-f"><span>' + label + '</span><input type="text" inputmode="decimal" value="' + v + '"' +
        jeAttr(path, 'num', key) + '></label>';
    }
    var text = v === null ? '' : String(v);
    var type = v === null ? 'nullable' : 'str';
    if (text.length > 60 || text.indexOf(NL) >= 0) {
      return '<label class="je-f"><span>' + label + '</span><textarea rows="' + Math.min(8, Math.max(2, Math.ceil(text.length / 40))) +
        '"' + jeAttr(path, type, key) + '>' + esc(text) + '</textarea></label>';
    }
    return '<label class="je-f"><span>' + label + '</span><input type="text" value="' + esc(text) + '"' +
      (v === null ? ' placeholder="（空）"' : '') + jeAttr(path, type, key) + '></label>';
  }
  function jsonEditorHtml() {
    return '<div class="je-tabs"><button type="button" class="je-tab on" data-je="form">表单</button>' +
      '<button type="button" class="je-tab" data-je="raw">源码</button>' +
      '<button type="button" class="je-fmt" data-je="fmt" hidden>整理格式</button></div>' +
      '<div class="je-form"></div><textarea name="text" rows="16" spellcheck="false" class="je-raw" hidden></textarea>';
  }
  function jeRender(form, value) {
    form.jeValue = value;
    var box = form.querySelector('.je-form');
    var args = value && value.arguments;
    if (!args || typeof args !== 'object') {
      box.innerHTML = '<p class="hint">这份 JSON 里没有 arguments，只能在源码里改</p>';
      return;
    }
    box.innerHTML = '<p class="hint">类型 ' + esc(value.factoryId) + ' · 版本 ' + esc(value.version) + '</p>' +
      jeFields(args, ['arguments'], 0);
  }
  function jeMode(form) {
    var t = form.querySelector('.je-tab.on');
    return t ? t.getAttribute('data-je') : null;
  }
  function jeShow(form, m) {
    var tabs = form.querySelectorAll('.je-tab');
    for (var i = 0; i < tabs.length; i++) tabs[i].classList.toggle('on', tabs[i].getAttribute('data-je') === m);
    form.querySelector('.je-form').hidden = m !== 'form';
    form.querySelector('.je-raw').hidden = m !== 'raw';
    form.querySelector('.je-fmt').hidden = m !== 'raw';
  }
  function initJsonEditor(form, text) {
    form.querySelector('.je-raw').value = text;
    try { jeRender(form, JSON.parse(text)); jeShow(form, 'form'); }
    catch (e) { jeShow(form, 'raw'); }
  }
  // 表单里的值写回一份原 JSON 的深拷贝: 表单没列出的字段 (如 factoryId / version) 原样保留
  function jeCollect(form) {
    var obj = JSON.parse(JSON.stringify(form.jeValue));
    var els = form.querySelectorAll('[data-path]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var path = JSON.parse(el.getAttribute('data-path'));
      var type = el.getAttribute('data-type');
      var v;
      if (type === 'bool') v = el.checked;
      else if (type === 'num') {
        var t = el.value.trim();
        v = Number(t);
        if (t === '' || isNaN(v)) throw new Error('「' + el.getAttribute('data-label') + '」需要填数字');
      } else if (type === 'lines' || type === 'numlines') {
        v = el.value.split(NL).map(function (x) { return x.trim(); }).filter(function (x) { return x.length; });
        if (type === 'numlines') v = v.map(Number);
      } else if (type === 'nullable') v = el.value === '' ? null : el.value;
      else v = el.value;
      var o = obj;
      for (var j = 0; j < path.length - 1; j++) o = o[path[j]];
      o[path[path.length - 1]] = v;
    }
    return obj;
  }
  // 提交前调用: 表单模式下把表单转回 JSON 放进 textarea (服务端只认 text 字段); 出错返回 false 并提示
  function syncJson(form) {
    if (!form.querySelector('.je-form') || jeMode(form) !== 'form') return true;
    try { form.querySelector('.je-raw').value = JSON.stringify(jeCollect(form), null, 2); return true; }
    catch (e) { toast(e.message); return false; }
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-je]');
    if (!b) return;
    var form = b.closest('form');
    if (!form) return;
    var want = b.getAttribute('data-je');
    var raw = form.querySelector('.je-raw');
    if (want === 'fmt') {
      try { raw.value = JSON.stringify(JSON.parse(raw.value), null, 2); }
      catch (err) { toast('JSON 格式有误：' + err.message); }
      return;
    }
    if (want === jeMode(form)) return;
    if (want === 'raw') {
      if (syncJson(form)) jeShow(form, 'raw');
    } else {
      try { jeRender(form, JSON.parse(raw.value)); jeShow(form, 'form'); }
      catch (err) { toast('JSON 格式有误，先在源码里改好：' + err.message); }
    }
  });

  // ---- 新增 ----
  function renderAdd() {
    var t = data.templates || [];
    var key = JSON.stringify(t);
    if (key === lastTemplates) return;
    lastTemplates = key;
    addBox.innerHTML = !t.length ? '' :
      '<label class="f"><span>新增数据源</span><select id="src-new"><option value="">选择类型…</option>' +
      t.map(function (x, i) { return '<option value="' + i + '">' + esc(x.name) + '</option>'; }).join('') +
      '</select></label><div id="src-new-panel"></div>';
  }
  function clearAdd() {
    var sel = document.getElementById('src-new');
    if (sel) sel.value = '';
    var panel = document.getElementById('src-new-panel');
    if (panel) panel.innerHTML = '';
  }
  addBox.addEventListener('change', function (e) {
    if (e.target.id !== 'src-new') { if (e.target.form) applyVis(e.target.form); return; }
    var t = data.templates[+e.target.value];
    var panel = document.getElementById('src-new-panel');
    if (!t) { panel.innerHTML = ''; return; }
    var hint = t.description ? '<p class="hint">' + esc(t.description) + '</p>' : '';
    if (t.editor === 'json') {
      getJson('api/sources/template?factoryId=' + encodeURIComponent(t.factoryId)).then(function (r) {
        if (!r.ok) { toast(r.message); return; }
        panel.innerHTML = '<form class="src-new-form" data-kind="json">' + hint + jsonEditorHtml() +
          '<p class="hint">这是一份空白模板，在表单里填好即可；别处分享的配置可以切到「源码」整段粘贴。</p>' +
          btns('cancel-new', '添加') + '</form>';
        initJsonEditor(panel.querySelector('form'), r.json);
      }).catch(fail);
    } else {
      panel.innerHTML = '<form class="src-new-form" data-kind="params">' + hint + paramForm(t.params || []) +
        btns('cancel-new', '添加') + '</form>';
      applyVis(panel.querySelector('form'));
    }
  });
  addBox.addEventListener('click', function (e) {
    var b = e.target.closest('[data-act="cancel-new"]');
    if (b) clearAdd();
  });
  addBox.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form.classList.contains('src-new-form')) return;
    e.preventDefault();
    var t = data.templates[+document.getElementById('src-new').value];
    if (!t) return;
    if (!syncJson(form)) return;
    var fd = new FormData(form);
    var json = form.getAttribute('data-kind') === 'json';
    if (!json) fd.append('factoryId', t.factoryId);
    post(json ? 'api/sources/import' : 'api/sources/save-params', fd).then(function (r) {
      toast(r.message);
      if (r.ok) { clearAdd(); lastTemplates = ''; load(); }
    }).catch(fail);
  });

  // ---- 导入 ----
  document.getElementById('src-import').addEventListener('submit', function (e) {
    e.preventDefault();
    var form = e.target;
    post('api/sources/import', new FormData(form)).then(function (r) {
      toast(r.message);
      if (r.ok) { form.elements.text.value = ''; document.getElementById('src-import-box').open = false; lastTemplates = ''; load(); }
    }).catch(fail);
  });

  // ---- 列表 ----
  function renderList() {
    var list = data.sources || [];
    if (!list.length) { box.innerHTML = '<p class="hint">还没有数据源</p>'; return; }
    box.innerHTML = list.map(function (s, i) {
      var fromSub = s.subscription != null;
      var tags = [s.kind, fromSub ? '来自订阅' : ''].filter(Boolean).join(' · ');
      var b = '<button data-act="up"' + (i === 0 ? ' disabled' : '') + '>上移</button>' +
        '<button data-act="down"' + (i === list.length - 1 ? ' disabled' : '') + '>下移</button>';
      if (s.editor !== 'none') b += '<button data-act="edit">编辑</button>';
      if (fromSub) b += '<button data-act="copy">复制为本地源</button>';
      if (s.exportable) b += '<button data-act="export">导出</button>';
      if (!fromSub) b += '<button data-act="delete" class="src-danger">删除</button>';
      return '<div class="src-item' + (s.enabled ? '' : ' off') + '" data-i="' + i + '">' +
        '<div class="src-top"><label class="src-sw"><input type="checkbox" data-act="enable"' + (s.enabled ? ' checked' : '') + '></label>' +
        '<div class="src-name">' + esc(s.name) + '<small>' + esc(tags) + '</small></div></div>' +
        (s.description ? '<div class="src-desc">' + esc(s.description) + '</div>' : '') +
        (fromSub ? '<div class="src-desc">订阅来的源会随订阅更新被覆盖，所以只能启用或停用；想改的话先「复制为本地源」。</div>' : '') +
        '<div class="src-btns">' + b + '</div><div class="src-panel" hidden></div></div>';
    }).join('');
  }
  function itemOf(el) {
    var item = el.closest('.src-item');
    return item ? { item: item, s: data.sources[+item.getAttribute('data-i')] } : null;
  }
  function act(path, body) {
    return post(path, body).then(function (r) {
      if (r.message) toast(r.message);
      if (r.ok) load();
    }).catch(fail);
  }
  function openPanel(item, html) {
    var p = item.querySelector('.src-panel');
    p.innerHTML = html;
    p.hidden = false;
    return p;
  }
  function withExport(s, then) {
    getJson('api/sources/export?id=' + encodeURIComponent(s.id)).then(function (r) {
      if (!r.ok) { toast(r.message); return; }
      then(r.json);
    }).catch(fail);
  }
  function edit(item, s) {
    if (s.editor === 'params') {
      var p = openPanel(item, '<form class="src-form" data-kind="params">' + paramForm(s.params || []) + btns('cancel', '保存') + '</form>');
      applyVis(p.querySelector('form'));
    } else if (s.editor === 'json') {
      withExport(s, function (text) {
        var p = openPanel(item, '<form class="src-form" data-kind="json">' + jsonEditorHtml() +
          '<p class="hint">可以在表单里逐项改，也可以切到「源码」整段换成别处复制来的同类型配置；保存前会校验格式。</p>' +
          btns('cancel', '保存') + '</form>');
        initJsonEditor(p.querySelector('form'), text);
      });
    }
  }
  box.addEventListener('change', function (e) {
    if (e.target.getAttribute('data-act') === 'enable') {
      var x = itemOf(e.target);
      act('api/sources/enable', { id: x.s.id, enabled: e.target.checked ? '1' : '0' });
    } else if (e.target.form) {
      applyVis(e.target.form);
    }
  });
  box.addEventListener('click', function (e) {
    var b = e.target.closest('button[data-act]');
    if (!b) return;
    var x = itemOf(b);
    if (!x) return;
    var a = b.getAttribute('data-act');
    if (a === 'up' || a === 'down') act('api/sources/move', { id: x.s.id, dir: a });
    else if (a === 'delete') { if (confirm('删除「' + x.s.name + '」？')) act('api/sources/delete', { id: x.s.id }); }
    else if (a === 'copy') act('api/sources/copy', { id: x.s.id });
    else if (a === 'edit') edit(x.item, x.s);
    else if (a === 'export') {
      withExport(x.s, function (text) {
        var p = openPanel(x.item, '<textarea rows="10" readonly spellcheck="false"></textarea>' +
          '<div class="row"><button type="button" class="ghost" data-act="cancel">收起</button>' +
          '<button type="button" class="primary" data-act="copytext">复制</button></div>');
        p.querySelector('textarea').value = text;
      });
    }
    else if (a === 'copytext') copyText(x.item.querySelector('.src-panel textarea'));
    else if (a === 'cancel') x.item.querySelector('.src-panel').hidden = true;
  });
  box.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form.classList.contains('src-form')) return;
    e.preventDefault();
    var x = itemOf(form);
    if (!syncJson(form)) return;
    var fd = new FormData(form);
    fd.append('id', x.s.id);
    act(form.getAttribute('data-kind') === 'json' ? 'api/sources/save-json' : 'api/sources/save-params', fd);
  });
})();
""".trimIndent()
