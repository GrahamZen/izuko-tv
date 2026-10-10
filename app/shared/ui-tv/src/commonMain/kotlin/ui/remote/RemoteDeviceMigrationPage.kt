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
 * 「设置 → 维护 → 换电视」卡片 (`#set-migrate`, 接口见 [RemoteDeviceMigration]). 每台电视的控制台里都一样:
 * 当旧电视用时点「复制本机地址」; 当新电视用时把旧电视的地址粘进来「读取」, 看清搬什么、搬到哪个用户, 确认后由新电视自己去取,
 * 网页只轮询进度. 搬完新电视重启, 轮询连不上时提示过一会儿刷新.
 */
internal val DEVICE_MIGRATION_SCRIPT = """
(function () {
  var box = document.getElementById('set-migrate');
  if (!box) return;
  // 本页就是这台电视的控制台根地址 (http://IP:端口/token/), 去掉 #标签
  var own = location.origin + location.pathname;
  box.innerHTML = '<div class="card set-card"><div class="set-title">' + T('换电视') + '</div>' +
    '<p class="hint">' + T('把另一台电视上 Izuko 的用户、收藏与播放记录、设置、数据源和登录一次搬到这台。两台电视要连在同一个网络里，并且都更新到最新版。') + '</p>' +
    '<p class="hint">' + T('1. 在旧电视的控制台里打开这一页，点「复制本机地址」。') + '<br>' + T('2. 回到新电视的控制台，把地址粘贴到下面，点「读取」。') + '</p>' +
    '<div class="row"><button type="button" class="ghost" data-mg="copy">' + T('复制本机地址') + '</button></div>' +
    '<label class="f"><span>' + T('旧电视控制台的地址') + '</span><input type="text" inputmode="url" name="mg-url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="http://"></label>' +
    '<div class="row"><button type="button" class="primary" data-mg="preview">' + T('读取') + '</button></div>' +
    '<div class="mg-out"></div></div>';
  var input = box.querySelector('input[name="mg-url"]');
  var out = box.querySelector('.mg-out');
  var url = '';
  var polling = null;
  var doneTicks = 0;

  // 网页是 http, 没有剪贴板接口; 选中一个临时文本框再复制, 复制不了就把地址摆出来让人长按
  function copyOwn() {
    var ta = document.createElement('textarea');
    ta.value = own;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) {}
    ta.remove();
    if (ok) { toast(T('已复制本机地址')); return; }
    out.innerHTML = '<p class="hint">' + T('请长按下面的地址手动复制') + '</p><p class="hint mg-own">' + esc(own) + '</p>';
  }
  function mb(n) { return (n / 1048576).toFixed(1) + ' MB'; }
  function preview() {
    var v = input.value.trim();
    if (!v) { toast(T('请粘贴旧电视控制台的完整地址')); return; }
    out.innerHTML = '<p class="hint">' + T('正在读取旧电视…') + '</p>';
    post('api/migrate/preview', { url: v }).then(function (r) {
      if (!r.ok) { out.innerHTML = '<p class="hint">' + esc(r.message) + '</p>'; return; }
      url = v;
      var h = '';
      r.notes.forEach(function (n) { h += '<p class="hint">' + esc(n) + '</p>'; });
      r.profiles.forEach(function (p) { h += '<p class="hint"><b>' + esc(p.name) + '</b><br>' + esc(p.detail) + '</p>'; });
      h += '<div class="row"><button type="button" class="ghost" data-mg="dismiss">' + T('取消') + '</button>' +
        '<button type="button" class="primary" data-mg="start">' + T('开始搬') + '</button></div>';
      out.innerHTML = h;
    }).catch(function () { out.innerHTML = ''; fail(); });
  }
  function start(b) {
    if (!confirm(T('开始搬？这台电视的设置会换成旧电视的，搬完这台电视会重启。'))) return;
    b.disabled = true;
    post('api/migrate/start', { url: url }).then(function (r) {
      if (!r.ok) { b.disabled = false; toast(r.message, 5000); return; }
      poll();
    }).catch(function () { b.disabled = false; fail(); });
  }
  function render(s) {
    if (s.state === 'running') {
      var p = s.total > 0 ? ' ' + mb(s.done) + ' / ' + mb(s.total) : (s.done > 0 ? ' ' + mb(s.done) : '');
      out.innerHTML = '<p class="hint">' + esc(s.text) + p + '</p>' +
        (s.cancellable ? '<div class="row"><button type="button" class="ghost" data-mg="cancel">' + T('停止') + '</button></div>' : '');
      return true;
    }
    if (s.state === 'done') {
      out.innerHTML = '<p class="hint">' + esc(s.message) + '</p><p class="hint">' + T('电视重启后，刷新本页就能看到搬过来的用户。') + '</p>';
      // 再看几秒: 电视叫不回前台、重启不了时, 提示会换成「请自己重开」
      return ++doneTicks < 8;
    }
    if (s.state === 'failed') out.innerHTML = '<p class="hint">' + esc(s.message) + '</p>';
    return false;
  }
  function poll() {
    clearTimeout(polling);
    getJson('api/migrate/status', 5000).then(function (s) {
      if (render(s)) polling = setTimeout(poll, 1000);
    }).catch(function () {
      // 搬完重启时连不上是正常的
      out.innerHTML = '<p class="hint">' + T('电视正在重启，过一会儿刷新本页。') + '</p>';
    });
  }
  box.addEventListener('click', function (e) {
    var b = e.target.closest('[data-mg]');
    if (!b || b.disabled) return;
    var a = b.getAttribute('data-mg');
    if (a === 'copy') copyOwn();
    else if (a === 'preview') preview();
    else if (a === 'dismiss') out.innerHTML = '';
    else if (a === 'start') start(b);
    else if (a === 'cancel') post('api/migrate/cancel', {}).then(function (r) { toast(r.message); poll(); }).catch(fail);
  });
  // 刷新页面时正在搬: 接着显示进度
  getJson('api/migrate/status', 5000).then(function (s) { if (s.state === 'running') poll(); }).catch(function () {});
})();
""".trimIndent()
