/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/**
 * 设置标签首页搜索框下面的「最近改过」: 这台手机上最近改过的几张设置卡片, 点一下直接进那一组、滚到那张卡片并闪一下;
 * 数据源 BT 页的项跳到那一页并展开.
 *
 * 不逐张卡片登记: 设置各组与 BT 页里任何一张卡片发出 change / submit (改了开关、下拉, 点了保存) 就记下它,
 * 记的是卡片所在的容器 id 加清单项的 key (通用卡片才有), 标题现取 (换了语言也对), 取不到时用记下时的标题.
 * 账号、反馈、维护几组里是操作不是设置, 不记. 只存在这台手机的浏览器里 (localStorage), 最多 4 条.
 */
internal val SETTINGS_RECENT_SCRIPT = """
(function () {
  var box = document.getElementById('set-recent'), KEY = 'ani-settings-recent', MAX = 4;
  var SKIP = { account: true, feedback: true, maintain: true };
  var shown = [];
  function read() {
    try { var v = JSON.parse(localStorage.getItem(KEY) || '[]'); return Array.isArray(v) ? v : []; } catch (e) { return []; }
  }
  function write(list) { try { localStorage.setItem(KEY, JSON.stringify(list)); } catch (e) {} }
  // 卡片里套的卡片算外层那张
  function outer(card) {
    while (card && card.parentNode && card.parentNode.closest && card.parentNode.closest('.card')) card = card.parentNode.closest('.card');
    return card;
  }
  function titleOf(card) {
    var t = card.querySelector('.set-title');
    if (t) {
      t = t.cloneNode(true);
      [].forEach.call(t.querySelectorAll('small, .src-ic'), function (x) { x.remove(); });
      return t.textContent.trim();
    }
    var l = card.querySelector('.toggle');
    return l ? l.textContent.trim() : '';
  }
  // 卡片 -> { g: 组 (BT 页是 'bt'), c: 容器 id, k: 清单项的 key (通用卡片才有), t: 标题 }; 不该记的返回 null
  function entryOf(el) {
    var card = outer(el.closest && el.closest('.card'));
    var c = card && card.parentNode;
    if (!c || !c.id) return null;
    var sg = card.closest('.set-group');
    var g = c.id === 'src-bt' || c.id === 'set-pikpak' ? 'bt' : sg && sg.getAttribute('data-group');
    if (!g || SKIP[g]) return null;
    var kEl = card.hasAttribute('data-fold') || card.hasAttribute('data-gkey') ? card : card.querySelector('[data-gkey]');
    var k = kEl ? kEl.getAttribute('data-fold') || kEl.getAttribute('data-gkey') : '';
    var t = titleOf(card);
    return t ? { g: g, c: c.id, k: k, t: t } : null;
  }
  function same(a, b) { return a.g === b.g && a.c === b.c && a.k === b.k; }
  function findCard(x) {
    var c = document.getElementById(x.c);
    if (!c) return null;
    if (!x.k) return c.querySelector('.card');
    var k = CSS.escape(x.k);
    var el = c.querySelector('[data-fold="' + k + '"]') || c.querySelector('[data-gkey="' + k + '"]');
    return el ? outer(el.closest('.card')) : null;
  }
  function record(e) {
    var x = entryOf(e.target);
    if (!x) return;
    write([x].concat(read().filter(function (y) { return !same(x, y); })).slice(0, MAX));
    paint();
  }
  document.addEventListener('change', record, true);
  document.addEventListener('submit', record, true);
  function paint() {
    shown = read().filter(function (x) { return x.g === 'bt' || !!document.querySelector('.set-group[data-group="' + x.g + '"]'); });
    box.hidden = !shown.length;
    if (!shown.length) { box.innerHTML = ''; return; }
    // 一条一个胶囊: 点名字跳过去, 右边的 × 只把这一条从列表里去掉
    box.innerHTML = '<div class="set-recent-h">' + T('最近改过') + '</div><div class="chips">' + shown.map(function (x, i) {
      var card = findCard(x), t = card && titleOf(card);
      return '<span class="set-recent-chip"><button type="button" class="set-recent-go" data-recent="' + i + '">' + esc(t || x.t) + '</button>' +
        '<button type="button" class="set-recent-x" data-recent-x="' + i + '" aria-label="' + T('移除') + '" title="' + T('移除') + '">' +
        window.ICONS.close + '</button></span>';
    }).join('') + '</div>';
  }
  // SCRIPT 画设置首页时调 (paintSetGroup)
  window.paintRecentSettings = paint;
  function flash(x) {
    var card = findCard(x);
    if (!card) return;
    if (card.tagName === 'DETAILS') card.open = true;
    card.scrollIntoView({ block: 'center', behavior: 'smooth' });
    card.classList.remove('set-flash');
    void card.offsetWidth;
    card.classList.add('set-flash');
  }
  box.addEventListener('click', function (e) {
    var del = e.target.closest('[data-recent-x]');
    if (del) {
      var gone = shown[+del.getAttribute('data-recent-x')];
      if (gone) write(read().filter(function (y) { return !same(gone, y); }));
      paint();
      return;
    }
    var b = e.target.closest('[data-recent]');
    var x = b && shown[+b.getAttribute('data-recent')];
    if (!x) return;
    if (x.g === 'bt') window.showSrcTab('bt'); else window.openSetGroup(x.g);
    // 进组时页面先滚回顶上; BT 页的卡片随设置重读重画, 等它画完再找
    setTimeout(function () { flash(x); }, x.g === 'bt' ? 400 : 60);
  });
  paint();
})();
""".trimIndent()

internal val SETTINGS_RECENT_STYLE = """
/* 设置首页「最近改过」(见 SETTINGS_RECENT_SCRIPT); 点了跳过去的那张卡片描一圈主题色再淡掉 */
#set-recent { margin: 0 0 12px; }
#set-recent[hidden] { display: none; }
.set-recent-h { margin: 0 0 8px; font-size: 13px; font-weight: 600; color: var(--sub); }
#set-recent .chips { margin-top: 0; }
.set-recent-chip { display: inline-flex; align-items: center; height: 32px; border-radius: 16px; background: var(--chip); color: var(--on-chip); overflow: hidden; }
.set-recent-go { height: 100%; padding: 0 2px 0 14px; background: none; color: inherit; font-size: 14px; }
.set-recent-x { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 100%; padding: 0 4px 0 0; background: none; color: var(--mute); }
.set-recent-x svg { width: 16px; height: 16px; fill: currentColor; }
.set-flash { animation: set-flash 1.6s ease-out; }
@keyframes set-flash { 0%, 35% { box-shadow: 0 0 0 2px var(--p), 0 1px 3px var(--shadow); } 100% { box-shadow: 0 1px 3px var(--shadow); } }
""".trimIndent()

/** 「最近改过」的文案 (译文表见 [REMOTE_I18N_TABLE]). */
internal fun settingsRecentTexts() = listOf(
    RemoteText("最近改过", "Recently changed", "最近改過", "最近改過"),
)
