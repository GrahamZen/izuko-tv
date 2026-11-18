/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.notice.TvNotices

/**
 * 手机控制台上的通知 (见 docs/contributing/code/notices.md「手机控制台」): 与电视上同一份列表.
 *
 * - `GET api/notices`: 列表 (那句话在电视上算好、看没看过、控制台去处、有没有电视去处);
 * - `POST api/notices/seen` (`key` 可多个): 这几条在网页上显示到屏幕上了, 算看过 —— 电视下次进应用不为它们弹;
 * - `POST api/notices/open` (`key`): 电视打开这一条的去处 (手机上处理不了的那几种).
 *
 * 实时提示搭在网页每 2 秒一次的 `api/notice` 上 ([putPoll]): 回包带新的几条, 网页亮红点、弹一条提示.
 */
internal object RemoteNotices {
    const val PATH = "api/notices"
    const val PATH_SEEN = "api/notices/seen"
    const val PATH_OPEN = "api/notices/open"

    /** 回包里最多带几条新的 (网页只拿来比有没有新来的、弹一条提示). */
    private const val POLL_ITEMS = 5

    fun handle(request: LanHttpRequest, navigator: AniNavigator?, scope: CoroutineScope): JsonObject? {
        TvNotices.install()
        val get = request.method == "GET"
        val post = request.method == "POST"
        return when {
            request.path == PATH && get -> list()
            request.path == PATH_SEEN && post -> seen(request)
            request.path == PATH_OPEN && post -> open(request, navigator, scope)
            else -> null
        }
    }

    private fun list(): JsonObject = buildJsonObject {
        putJsonArray("items") {
            for (n in TvNotices.shown.value.orEmpty()) {
                addJsonObject {
                    put("key", n.key)
                    put("text", n.title)
                    n.summary?.let { put("sub", it) }
                    put("at", n.notice.atMillis)
                    put("fresh", !n.entry.seen)
                    put("pending", n.entry.pending)
                    n.kind.consoleHref(n.notice)?.let { put("href", it) }
                    put("tv", n.actions.isNotEmpty())
                    // 说明里的外部链接: 网页上是可点的链接
                    n.detail?.qr?.let { qr ->
                        putJsonObject("link") {
                            put("t", qr.caption)
                            put("u", qr.url)
                        }
                    }
                }
            }
        }
    }

    private fun seen(request: LanHttpRequest): JsonObject {
        val keys = request.formFieldList().filter { it.first == "key" }.map { it.second }.toSet()
        TvNotices.markSeen(TvNotices.shown.value.orEmpty().filter { it.key in keys })
        return result(true, "")
    }

    private fun open(request: LanHttpRequest, navigator: AniNavigator?, scope: CoroutineScope): JsonObject {
        val key = request.formFieldList().lastOrNull { it.first == "key" }?.second
        val item = TvNotices.shown.value.orEmpty().firstOrNull { it.key == key }
            ?: return result(false, tr("这条通知已经处理过了"))
        if (item.actions.isEmpty()) return result(false, tr("这条通知只是告知，没有要打开的内容"))
        // 电视上处理这一条由 TV 根部的通知宿主做 (一个操作的直接做, 几个的打开列表、焦点在第一个操作上); 界面还没起来时做不了
        if (navigator == null) return result(false, tr("电视上的 Izuko 界面还没打开"))
        scope.launch(Dispatchers.Main) { TvNotices.handle(item.key) }
        return result(true, tr("已在电视上打开"))
    }

    /** 网页每 2 秒的 `api/notice` 里带上: 有几条新的, 最新几条的键与那句话 (网页据此亮红点、弹一条提示). */
    fun putPoll(builder: JsonObjectBuilder) {
        val fresh = TvNotices.shown.value.orEmpty().filter { !it.entry.seen }
        builder.putJsonObject("notices") {
            put("fresh", fresh.size)
            putJsonArray("items") {
                for (n in fresh.take(POLL_ITEMS)) addJsonObject {
                    put("k", n.key)
                    put("t", n.title)
                    n.summary?.let { put("s", it) }
                }
            }
        }
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}

/** 顶上的铃铛 (同「?」), 新通知的横幅, 列表的行. */
internal val NOTICE_STYLE = """
#notice-btn { position: relative; flex: none; margin-left: auto; width: 32px; height: 32px; padding: 0; border-radius: 16px;
  background: var(--chip); color: var(--on-chip); display: flex; align-items: center; justify-content: center; }
#notice-btn svg { width: 18px; height: 18px; fill: currentColor; }
#notice-btn + #help-btn { margin-left: 0; }
#notice-banner { position: fixed; left: 50%; top: calc(12px + env(safe-area-inset-top)); transform: translateX(-50%); z-index: 70;
  max-width: 90%; width: max-content; box-sizing: border-box; padding: 12px 18px; border-radius: 16px; border: none;
  background: var(--toast-bg); color: var(--toast-fg); font-size: 14px; line-height: 1.55; text-align: left; }
@media (min-width: 560px) { #notice-banner { max-width: 30rem; } }
.notice-row { display: flex; align-items: center; gap: 12px; width: 100%; text-align: left; margin: 8px 0; padding: 12px 14px; border: none; color: inherit; font: inherit; }
.notice-dot { flex: none; width: 8px; height: 8px; border-radius: 50%; }
.notice-dot.on { background: var(--err); }
.notice-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.notice-title { font-weight: 600; }
.notice-sub { opacity: .85; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.notice-main small { opacity: .7; }
.notice-link { display: block; margin: -2px 0 8px 34px; font-size: 14px; }
.notice-go { flex: none; opacity: .6; font-size: 20px; }
.notice-sec { margin: 16px 4px 0; font-size: 13px; font-weight: 600; opacity: .7; }
.notice-sec:first-child { margin-top: 4px; }
""".trimIndent()

/**
 * 控制台上的通知: 顶上「?」左边一颗铃铛 (有新的带红点), 点开是通知列表 (全屏面板, 同使用说明);
 * 网页开着时来了新通知在顶上弹一条横幅 (这台手机上可以关, 设置 → 这台手机). 列表里点一条: 有控制台去处的去那一页,
 * 没有的让电视打开它的去处. 显示到屏幕上的才算看过 (滑到才算).
 */
internal val NOTICE_SCRIPT = """
(function () {
  var header = document.querySelector('header'), help = document.getElementById('help-btn');
  if (!header) return;
  var btn = document.createElement('button');
  btn.type = 'button';
  btn.id = 'notice-btn';
  btn.setAttribute('aria-label', T('通知'));
  btn.title = T('通知');
  btn.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"/></svg><span class="help-dot" hidden></span>';
  header.insertBefore(btn, help);
  var dot = btn.querySelector('.help-dot');
  var sheet = document.createElement('div');
  sheet.id = 'notice-sheet';
  sheet.className = 'sheet';
  sheet.hidden = true;
  sheet.innerHTML = '<div class="sheet-head"><div class="sheet-title">' + T('通知') + '</div>' +
    '<button type="button" class="sheet-btn" id="notice-close" aria-label="' + T('关闭') + '" title="' + T('关闭') + '"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button></div>' +
    '<div class="sheet-body" id="notice-body"></div>';
  document.body.appendChild(sheet);
  var body = sheet.querySelector('#notice-body');
  var banner = document.createElement('button');
  banner.type = 'button';
  banner.id = 'notice-banner';
  banner.hidden = true;
  document.body.appendChild(banner);
  var BANNER_KEY = 'remote.noticeBanner';
  function bannerOn() { try { return localStorage.getItem(BANNER_KEY) !== 'off'; } catch (e) { return true; } }
  // 今天的写刚刚 / 几分钟前 / 几小时前, 昨天写昨天, 今年的写月日, 更早的写年月日 (同电视上的通知弹窗)
  function timeText(at) {
    var d = Date.now() - at, a = new Date(at), n = new Date(), y = new Date(n.getTime());
    y.setDate(n.getDate() - 1);
    if (d < 60000) return T('刚刚');
    if (d < 3600000) return T('{0} 分钟前', Math.floor(d / 60000));
    if (a.toDateString() === n.toDateString()) return T('{0} 小时前', Math.floor(d / 3600000));
    if (a.toDateString() === y.toDateString()) return T('昨天');
    if (a.getFullYear() === n.getFullYear()) return T('{0} 月 {1} 日', a.getMonth() + 1, a.getDate());
    return T('{0} 年 {1} 月 {2} 日', a.getFullYear(), a.getMonth() + 1, a.getDate());
  }
  var items = [];
  function render() {
    if (!items.length) {
      body.innerHTML = '<p class="hint">' + T('暂无通知') + '</p>';
      return;
    }
    // 新的与看过的都有时分两段, 各一行小标题 (同电视上的通知弹窗); 只有一种时不写
    var sectioned = items.some(function (n) { return n.fresh; }) && items.some(function (n) { return !n.fresh; });
    body.innerHTML = items.map(function (n, i) {
      var how = n.href ? T('在手机上处理') : n.tv ? T('在电视上打开') : '';
      var head = sectioned && (i === 0 || (!n.fresh && items[i - 1].fresh))
        ? '<div class="notice-sec">' + (n.fresh ? T('新通知') : T('之前的')) + '</div>' : '';
      return head + '<button type="button" class="card notice-row" data-ni="' + i + '">' +
        '<span class="notice-dot' + (n.fresh ? ' on' : '') + '"></span>' +
        '<span class="notice-main"><span class="notice-title">' + window.esc(n.text) + '</span>' +
        (n.sub ? '<span class="notice-sub">' + window.esc(n.sub) + '</span>' : '') + '<small>' + timeText(n.at) + (how ? ' · ' + how : '') + '</small></span>' +
        (how ? '<span class="notice-go">›</span>' : '') + '</button>' +
        (n.link ? '<a class="notice-link" href="' + window.esc(n.link.u) + '" target="_blank" rel="noopener">' + window.esc(n.link.t) + '</a>' : '');
    }).join('');
  }
  // 显示到屏幕上的才算看过 (一行露出一半以上; 列表长、一屏放不下的, 滑到才算), 攒一会儿一起报给电视
  var observer = null, pendingSeen = {}, seenTimer = null;
  function flushSeen() {
    clearTimeout(seenTimer);
    seenTimer = null;
    var keys = Object.keys(pendingSeen);
    pendingSeen = {};
    if (!keys.length) return;
    var p = new URLSearchParams();
    keys.forEach(function (k) { p.append('key', k); });
    window.fetchT('api/notices/seen', { method: 'POST', body: p }, 8000).catch(function () {});
  }
  function seen(row) {
    var n = items[+row.getAttribute('data-ni')];
    if (!n || !n.fresh) return;
    pendingSeen[n.key] = true;
    if (!seenTimer) seenTimer = setTimeout(flushSeen, 400);
  }
  function watchRows() {
    if (observer) observer.disconnect();
    var rows = body.querySelectorAll('[data-ni]');
    if (!window.IntersectionObserver) {
      rows.forEach(seen);
      return;
    }
    observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting) return;
        seen(e.target);
        observer.unobserve(e.target);
      });
    }, { root: body, threshold: 0.5 });
    rows.forEach(function (row) { observer.observe(row); });
  }
  function load() {
    return window.getJson('api/notices').then(function (d) {
      items = d.items || [];
      render();
      watchRows();
    }, function () { window.failRead(); });
  }
  function hideBanner() {
    banner.hidden = true;
    clearTimeout(banner.hideTimer);
  }
  function open() {
    hideBanner();
    body.innerHTML = '<p class="hint">' + T('正在读取…') + '</p>';
    window.sheets.open(sheet);
    load();
  }
  function close() {
    if (observer) observer.disconnect();
    flushSeen();
    window.sheets.close(sheet);
  }
  // 控制台页面的地址 (如 #settings/feedback): 切到那个标签, 设置里的再进那一组
  function go(href) {
    var h = href.replace(/^#/, ''), k = h.indexOf('/'), tab = k < 0 ? h : h.slice(0, k), g = k < 0 ? null : h.slice(k + 1);
    if (window.showTab) window.showTab(tab);
    if (g && tab === 'settings' && window.openSetGroup) window.openSetGroup(g);
  }
  btn.addEventListener('click', open);
  banner.addEventListener('click', open);
  sheet.querySelector('#notice-close').addEventListener('click', close);
  body.addEventListener('click', function (e) {
    var row = e.target.closest('[data-ni]');
    var n = row && items[+row.getAttribute('data-ni')];
    if (!n) return;
    if (n.href) {
      close();
      go(n.href);
    } else if (n.tv) {
      window.post('api/notices/open', { key: n.key }).then(function (r) {
        toast(r.message);
        if (r.ok) close();
      }, window.fail);
    }
  });
  var known = null;
  window.onNoticePoll = function (n) {
    var info = n.notices;
    if (!info) return;
    dot.hidden = !(info.fresh > 0);
    var list = info.items || [];
    // 第一轮只记下已有的, 打开网页时不为旧的弹横幅
    var added = known === null ? [] : list.filter(function (x) { return known.indexOf(x.k) < 0; });
    known = list.map(function (x) { return x.k; });
    if (!added.length) return;
    if (!sheet.hidden) {
      load();
      return;
    }
    if (!bannerOn()) return;
    banner.textContent = added.length === 1 ? T('新通知：{0}', added[0].s ? added[0].t + ' · ' + added[0].s : added[0].t) : T('{0} 条新通知', added.length);
    banner.hidden = false;
    clearTimeout(banner.hideTimer);
    banner.hideTimer = setTimeout(hideBanner, 8000);
  };
  // 地址带 #notices 的: 一打开就是通知列表. 等网页自己切完标签再开
  if (location.hash === '#notices') setTimeout(open, 0);
  // 设置 → 这台手机: 新通知的横幅要不要
  var group = document.querySelector('.set-group[data-group="device"]');
  if (group) {
    var box = document.createElement('div');
    group.appendChild(box);
    var paint = function () {
      var on = bannerOn();
      box.innerHTML = '<div class="card set-card"><div class="set-title">' + T('新通知提示') + '<small>' + T('只影响这台手机') + '</small></div>' +
        '<div class="seg"><button type="button" data-nb="on"' + (on ? ' class="on"' : '') + '>' + T('提示') + '</button>' +
        '<button type="button" data-nb="off"' + (on ? '' : ' class="on"') + '>' + T('不提示') + '</button></div>' +
        '<p class="hint">' + T('网页开着时，电视上来了新通知就在顶上提示一条；点它打开通知列表。') + '</p></div>';
    };
    paint();
    box.addEventListener('click', function (e) {
      var b = e.target.closest('[data-nb]');
      if (!b) return;
      try { localStorage.setItem(BANNER_KEY, b.getAttribute('data-nb')); } catch (x) {}
      paint();
    });
  }
})();
""".trimIndent()

internal fun noticeTexts() = listOf(
    RemoteText("通知", "Notifications", "通知", "通知"),
    RemoteText("暂无通知", "No notifications", "暫無通知", "暫無通知"),
    RemoteText("新通知", "New", "新通知", "新通知"),
    RemoteText("之前的", "Earlier", "之前的", "之前的"),
    RemoteText("在手机上处理", "Handle on phone", "在手機上處理", "在手機上處理"),
    RemoteText("在电视上打开", "Open on TV", "在電視上打開", "在電視上開啟"),
    RemoteText("新通知：{0}", "New notification: {0}", "新通知：{0}", "新通知：{0}"),
    RemoteText("{0} 条新通知", "{0} new notifications", "{0} 條新通知", "{0} 則新通知"),
    RemoteText("新通知提示", "New notification alerts", "新通知提示", "新通知提示"),
    RemoteText("提示", "Show", "提示", "提示"),
    RemoteText("不提示", "Don’t show", "不提示", "不提示"),
    RemoteText(
        "网页开着时，电视上来了新通知就在顶上提示一条；点它打开通知列表。",
        "While this page is open, new notifications from the TV appear at the top. Tap one to open the list.",
        "網頁開着時，電視上來了新通知就在頂上提示一條；點它打開通知列表。",
        "網頁開著時，電視上來了新通知就在頂端提示一則；點它開啟通知列表。",
    ),
    RemoteText("这条通知已经处理过了", "This notification has already been handled", "這條通知已經處理過了", "這則通知已經處理過了"),
    RemoteText("这条通知只是告知，没有要打开的内容", "This notification is for information only; there is nothing to open", "這條通知只是告知，沒有要打開的內容", "這則通知只是告知，沒有要開啟的內容"),
    RemoteText("电视上的 Izuko 界面还没打开", "Izuko isn't open on the TV yet", "電視上的 Izuko 介面還沒打開", "電視上的 Izuko 介面還沒開啟"),
    RemoteText("已在电视上打开", "Opened on the TV", "已在電視上打開", "已在電視上開啟"),
    RemoteText("刚刚", "Just now", "剛剛", "剛剛"),
    RemoteText("{0} 分钟前", "{0} min ago", "{0} 分鐘前", "{0} 分鐘前"),
    RemoteText("{0} 小时前", "{0} h ago", "{0} 小時前", "{0} 小時前"),
    RemoteText("昨天", "Yesterday", "昨天", "昨天"),
    RemoteText("{0} 月 {1} 日", "{0}/{1}", "{0} 月 {1} 日", "{0} 月 {1} 日"),
    RemoteText("{0} 年 {1} 月 {2} 日", "{1}/{2}/{0}", "{0} 年 {1} 月 {2} 日", "{0} 年 {1} 月 {2} 日"),
)
