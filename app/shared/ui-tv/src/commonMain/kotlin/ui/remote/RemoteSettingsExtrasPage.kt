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
 * 「设置」里两张手写的卡片:
 * - 「资源与弹幕 → PikPak」(`#set-pikpak`, 接口见 [RemotePikPakSettings]): 用户名与密码, 密码只写不读 (留空 = 不改).
 *   数据随 `api/settings` 来, SETTINGS_SCRIPT 的 render 调 `window.renderPikPak`; 保存后按回应只重画这一张.
 * - 「维护 → 设置备份」(`#set-backup`, 接口见 [RemoteSettingsBackup]): 导出成文件存在手机上; 导入时选文件,
 *   先在手机上认一下像不像备份、确认会覆盖全部设置, 再把文件原文发给电视.
 */
internal val SETTINGS_EXTRAS_SCRIPT = """
(function () {
  var pkBox = document.getElementById('set-pikpak');
  var bkBox = document.getElementById('set-backup');
  function pkStatus(p) {
    var s = p.signedIn ? T('已登录')
      : p.username && p.hasPassword ? T('已填好账号，用到 PikPak 时登录')
      : p.username ? T('还没填密码') : T('还没填账号');
    return p.enabled ? s : s + T('；电视上还没打开「启用 PikPak」，打开后才会用');
  }
  window.renderPikPak = function (p) {
    if (!pkBox) return;
    if (!p) { pkBox.innerHTML = ''; return; }
    pkBox.innerHTML = '<form class="card set-card"><div class="set-title">PikPak</div>' +
      '<p class="hint">' + T('用 PikPak 的离线下载解析 BT 资源，需要 PikPak 会员。「启用 PikPak」开关在电视上。') + '</p>' +
      '<label class="f"><span>' + T('用户名') + '</span><input type="text" name="username" autocomplete="off" autocapitalize="off" spellcheck="false" value="' +
      esc(p.username) + '" placeholder="' + T('邮箱 / 手机号') + '"></label>' +
      '<label class="f"><span>' + T('密码') + '</span><input type="password" name="password" autocomplete="new-password" placeholder="' +
      (p.hasPassword ? T('已设置，留空则不改') : '') + '"><em>' +
      T('用 Google 登录的账号，要先在 PikPak 的账号设置里设一个密码。换用户名时，一起填上新账号的密码。') + '</em></label>' +
      '<p class="hint">' + esc(pkStatus(p)) + '</p>' +
      '<div class="row"><button type="button" class="ghost" data-pk="test">' + T('测试登录') + '</button><button type="submit" class="primary">' + T('保存') + '</button></div>' +
      '<div class="pk-test"></div></form>';
  };
  if (pkBox) {
    pkBox.addEventListener('submit', function (e) {
      e.preventDefault();
      post('api/settings/pikpak', new FormData(e.target)).then(function (r) {
        toast(r.message);
        if (r.pikpak) window.renderPikPak(r.pikpak);
      }).catch(fail);
    });
    pkBox.addEventListener('click', function (e) {
      var b = e.target.closest('[data-pk="test"]');
      if (!b) return;
      var out = pkBox.querySelector('.pk-test');
      b.disabled = true;
      out.innerHTML = '<p class="hint">' + T('正在登录 PikPak，最多要半分钟…（按已保存的账号测）') + '</p>';
      post('api/settings/pikpak/test', {}).then(function (r) {
        b.disabled = false;
        out.innerHTML = '<p class="hint">' + esc(r.message) + '</p>';
      }).catch(function () { b.disabled = false; out.innerHTML = ''; fail(); });
    });
  }

  if (!bkBox) return;
  bkBox.innerHTML = '<div class="card set-card"><div class="set-title">' + T('设置备份') + '</div>' +
    '<p class="hint">' + T('把应用设置导出成文件存在手机上，重装或换电视后再导入。文件里有 Bangumi 登录凭据，不要发给别人；PikPak 账号不在里面。') + '</p>' +
    '<div class="row"><button type="button" class="ghost" data-bk="export">' + T('导出') + '</button>' +
    '<button type="button" class="ghost" data-bk="import">' + T('导入') + '</button></div></div>';
  function ymd() {
    var d = new Date();
    return '' + d.getFullYear() + ('0' + (d.getMonth() + 1)).slice(-2) + ('0' + d.getDate()).slice(-2);
  }
  // 文件内容就是电视给的原文 (与设置页复制到剪贴板的一样), 不重新排版
  function exportBackup(b) {
    b.disabled = true;
    getJson('api/settings/backup/export', 20000).then(function (d) {
      b.disabled = false;
      if (!d.ok) { toast(d.message); return; }
      var a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([d.content], { type: 'application/json' }));
      a.download = 'izuko-settings-' + ymd() + '.json';
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(function () { URL.revokeObjectURL(a.href); }, 30000);
      toast(T('已导出设置备份'));
    }).catch(function () { b.disabled = false; fail(); });
  }
  // 选文件要在点击里直接调起 (手机浏览器只认用户手势)
  function pickBackup(b) {
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json,application/json,text/plain';
    input.addEventListener('change', function () {
      var f = input.files && input.files[0];
      if (!f) return;
      var reader = new FileReader();
      reader.onload = function () { importBackup(b, String(reader.result || '')); };
      reader.onerror = function () { toast(T('读不了这个文件')); };
      reader.readAsText(f);
    });
    input.click();
  }
  // 不是 JSON 对象, 或者一个设置字段都没有 (比如选成了导出的用户文件): 不发给电视
  function looksLikeBackup(text) {
    var o = null;
    try { o = JSON.parse(text); } catch (e) { return false; }
    return !!o && typeof o === 'object' && ('uiSettings' in o || 'danmakuConfig' in o || 'mediaSelectorSettings' in o);
  }
  function importBackup(b, text) {
    if (!looksLikeBackup(text)) { toast(T('这个文件不是设置备份，或者已经损坏')); return; }
    if (!confirm(T('这会覆盖当前应用的所有设置，且无法撤销，确认导入吗？'))) return;
    b.disabled = true;
    fetchT('api/settings/backup/import', { method: 'POST', headers: { 'Content-Type': 'text/plain;charset=UTF-8' }, body: text }, 45000)
      .then(function (r) {
        if (r.status === 413) return { ok: false, message: T('文件太大，不像设置备份') };
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (r) {
        b.disabled = false;
        toast(r.message);
        if (!r.ok) return;
        // 设置与 Bangumi 登录状态都可能变了
        if (window.loadSettings) window.loadSettings();
        if (window.loadAccount) window.loadAccount();
      }).catch(function () { b.disabled = false; fail(); });
  }
  bkBox.addEventListener('click', function (e) {
    var b = e.target.closest('[data-bk]');
    if (!b || b.disabled) return;
    if (b.getAttribute('data-bk') === 'export') exportBackup(b);
    else pickBackup(b);
  });
})();
""".trimIndent()
