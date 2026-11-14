/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

internal val TOGETHER_STYLE = """
.tg-status { display: inline-flex; align-items: center; gap: 6px; }
.tg-dot { width: 8px; height: 8px; border-radius: 50%; background: #34c759; flex: none; }
.tg-dot.warn { background: #ffb020; }
.tg-head { margin-top: 10px; }
.tg-head b { display: block; font-size: 19px; line-height: 1.35; }
.tg-head p { margin: 4px 0 0; color: var(--mute); font-size: 14px; }
.tg-ids { display: flex; gap: 28px; margin-top: 14px; flex-wrap: wrap; }
.tg-ids div { display: flex; flex-direction: column; gap: 2px; }
.tg-ids small { color: var(--mute); font-size: 13px; }
.tg-ids b { font-size: 24px; letter-spacing: 1px; font-variant-numeric: tabular-nums; }
.tg-copy { margin-top: 12px; }
.tg-copy button { width: 100%; }
.tg-join { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 12px; }
.req .tg-join { padding: 0; }
.tg-join label { display: flex; flex-direction: column; gap: 4px; min-width: 0; color: var(--mute); font-size: 13px; }
.tg-join input { min-width: 0; }
.tg-join button { grid-column: 1 / -1; }
.tg-invite { margin-top: 12px; resize: none; font-size: 14px; }
.tg-label { margin: 18px 0 8px; color: var(--mute); font-size: 13px; }
.tg-members { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.tg-members li { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-radius: 14px; background: var(--chip); }
.tg-avatar { width: 34px; height: 34px; border-radius: 50%; background: var(--p); color: var(--on-p); display: flex; align-items: center; justify-content: center; font-weight: 600; flex: none; }
.tg-who { flex: 1; min-width: 0; }
.tg-who b { display: block; font-size: 15px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tg-who small { color: var(--mute); font-size: 12px; }
.tg-state { color: var(--mute); font-size: 13px; white-space: nowrap; }
.tg-transfer { flex: none; padding: 4px 10px; font-size: 13px; }
.tg-actions { display: flex; gap: 8px; margin-top: 16px; flex-wrap: wrap; }
.tg-actions button { flex: 1; min-width: 8em; }
""".trimIndent()

/**
 * 播放器标签里的「一起看」卡 (服务端见 [RemoteTogether]): 手机上替电视进出房间 —— 打字、复制粘贴邀请都比遥控器方便。
 * - 不在房间里: 输入房间号与密码加入; 把朋友发来的邀请粘贴进任一输入框直接加入 (由服务端从整段话里认出房间号与密码);
 *   或打开电视自己的房间。
 * - 在房间里 (照电视上的「一起看」弹窗): 卡头是房间状态; 一行标题说此刻在做什么、一句说明; 房间号、密码与「复制邀请」(一段话复制下来发给朋友);
 *   每个成员一张卡片 (身份、是不是这台电视、在看什么; 电视是房主时别人的卡片上有「转让房主」); 底下开关「跟随房主播放」、离开,
 *   房主可以更换密码 (只有房间的主人)、解散。
 * 地址带 `#player/together` (电视「一起看」弹窗的二维码) 时打开播放器标签、展开这张卡片。
 * 卡片展开着时每 3 秒读一次 `api/together`; 内容没变不重画 (输入框里打了一半的号码不会被冲掉)。
 */
internal val TOGETHER_SCRIPT = """
(function () {
  var box = document.getElementById('tg-box');
  if (!box) return;
  var body = document.getElementById('tg-body'), sum = document.getElementById('tg-sum');
  // inviteShown: 复制没成功时把邀请的文本框露出来, 让人长按手动复制
  // pending: 这台手机刚发出去、还没回的进房动作 ('create' / 'join'), 按钮上照它写「正在…」
  var state = null, busy = false, pending = '', inviteShown = false, timer = null;
  function fmtNumber(n) { return String(n).length === 8 ? n.slice(0, 4) + ' ' + n.slice(4) : n; }
  function fmtPassword(p) { return String(p).length === 6 ? p.slice(0, 3) + ' ' + p.slice(3) : p; }
  /** 成员此刻在做什么 (电视算好给的 activity): 有播放会话不等于在看 */
  function memberState(m) {
    var ep = m.watching ? m.watching.sort : '';
    switch (m.activity) {
      case 'offline': return T('已离线');
      case 'free': return T('自由观看');
      case 'preparing': return T('准备中 · 第 {0} 话', ep);
      case 'buffering': return T('缓冲中 · 第 {0} 话', ep);
      case 'paused': return T('已暂停 · 第 {0} 话', ep);
      case 'playing': return T('播放中 · 第 {0} 话', ep);
      default: return T('未播放');
    }
  }
  /** 一行标题: 此刻在做什么。标题与下面的说明都照电视算好的处境 (situation) 写, 与电视弹窗同一套 */
  function headline(s) {
    var p = s.playback;
    switch (s.situation) {
      case 'host_idle': return T('选择剧集，开始一起看');
      case 'member_free': return T('自由观看');
      case 'member_host_offline': return T('等待房主重新连接');
      case 'member_waiting': return T('等待房主开始播放');
      default: return p ? T('正在一起看《{0}》第 {1} 话', p.subject, p.sort) : '';
    }
  }
  function status(s) {
    var reconnecting = s.connection === 'reconnecting';
    return '<span class="tg-status"><span class="tg-dot' + (reconnecting ? ' warn' : '') + '"></span>' +
      (reconnecting ? T('正在重新连接…') : s.host ? T('房间已开启') : T('已加入房间')) + '</span>';
  }
  function memberName(m) { return m.nickname || T('观众') + ' ' + String(m.id).slice(0, 4); }
  function memberRow(s, m) {
    var name = memberName(m);
    var who = (m.host ? T('房主') : T('成员')) + (m.id === s.self ? ' · ' + T('此电视') : '');
    // 电视是房主时, 别的在线成员那一行可以转让房主; 正在等谁接手就在他那一行写「正在转让…」
    var transfer = '';
    if (s.host && m.id !== s.self && m.state !== 'disconnected') {
      transfer = s.transferring === m.id
        ? '<button type="button" class="ghost tg-transfer" disabled>' + T('正在转让…') + '</button>'
        : '<button type="button" class="ghost tg-transfer" data-tg="transfer" data-id="' + window.esc(m.id) + '"' + (busy || s.transferring ? ' disabled' : '') + '>' + T('转让房主') + '</button>';
    }
    return '<li><span class="tg-avatar">' + window.esc(name.charAt(0)) + '</span>' +
      '<span class="tg-who"><b>' + window.esc(name) + '</b><small>' + window.esc(who) + '</small></span>' +
      '<span class="tg-state">' + window.esc(memberState(m)) + '</span>' + transfer + '</li>';
  }
  function role(s) {
    if (s.connection === 'reconnecting') return T('电视正在重新连接房间…');
    switch (s.situation) {
      case 'host_idle': case 'host_watching': return T('此电视是房主。已开启跟随的成员将同步播放。');
      case 'member_free': return T('电视正在自由观看，播放不随房主变化。');
      case 'member_host_offline': return T('房主已离线，重新连接后将恢复同步。');
      case 'member_waiting': return T('房主开始观看后，将自动跟随播放。');
      default: return T('电视正在跟随房主播放。');
    }
  }
  function button(what, text, primary) {
    return '<button type="button" class="' + (primary ? 'primary' : 'ghost') + '" data-tg="' + what + '"' + (busy ? ' disabled' : '') + '>' + text + '</button>';
  }
  function render() {
    var s = state, h = '', off = busy ? ' disabled' : '';
    if (!s) { window.setHtml(body, '<p class="hint">' + T('正在加载房间信息…') + '</p>'); return; }
    if (s.state === 'in_room') {
      window.setHtml(sum, status(s));
      h += '<div class="tg-head"><b>' + window.esc(headline(s)) + '</b><p>' + role(s) + '</p></div>';
      h += '<div class="tg-ids"><div><small>' + T('房间号') + '</small><b>' + window.esc(fmtNumber(s.number)) + '</b></div>' +
        '<div><small>' + T('密码') + '</small><b>' + window.esc(fmtPassword(s.password)) + '</b></div></div>';
      h += '<div class="tg-copy">' + button('copy', T('复制邀请'), true) + '</div>';
      // execCommand 只复制得了选中的文字: 文本框平时藏着, 复制时露出来选中
      h += '<textarea class="tg-invite" id="tg-invite-text" rows="4" readonly' + (inviteShown ? '' : ' hidden') + '>' + window.esc(s.invite) + '</textarea>';
      h += '<div class="tg-label">' + T('成员 · {0}', s.members.length) + '</div>';
      h += '<ul class="tg-members">' + s.members.map(function (m) { return memberRow(s, m); }).join('') + '</ul>';
      // 被请来临时当房主的电视没有「更换密码」: 房间是别人的, 密码只有房间的主人能换
      h += '<div class="tg-actions">' +
        (s.host ? (s.owner ? button('password', T('更换密码')) : '') : button('follow', s.following ? T('改为自由观看') : T('跟随房主播放'))) +
        button('leave', s.host ? T('解散房间') : T('离开房间')) + '</div>';
    } else if (s.state === 'joining' && s.handoff) {
      // 房主换了人, 电视正以新的身份回同一个房间
      window.setHtml(sum, '');
      h += '<p class="hint">' + T('正在切换房主…') + '</p>';
    } else {
      inviteShown = false;
      window.setHtml(sum, '');
      // 正在开自己的房间, 还是在加入别人的: 这台手机刚点的照点的算, 电视那边发起的照它说的算
      var entering = !busy && s.state === 'joining';
      var opening = pending === 'create' || (entering && s.opening), joining = pending === 'join' || (entering && !s.opening);
      h += '<p class="hint">' + T('与朋友同步观看同一集，由房主控制播放。') + '</p>';
      h += '<form class="tg-join" id="tg-join">' +
        // 标签写是什么, 输入框里的提示只写位数: 两样都写进提示的话英文放不下 (输入框只有半行宽)
        '<label>' + T('房间号') + '<input type="text" name="number" inputmode="numeric" maxlength="9" autocomplete="off" placeholder="' + T('8 位数字') + '"' + off + '></label>' +
        '<label>' + T('密码') + '<input type="text" name="password" inputmode="numeric" maxlength="7" autocomplete="off" placeholder="' + T('6 位数字') + '"' + off + '></label>' +
        '<button type="submit" class="primary"' + off + '>' + (joining ? T('正在加入房间…') : T('加入房间')) + '</button></form>';
      h += '<p class="hint">' + T('将邀请粘贴到任一输入框，即可加入房间。') + '</p>';
      h += '<div class="tg-actions">' + button('create', opening ? T('正在开启房间…') : T('开启房间')) + '</div>';
    }
    window.setHtml(body, h);
  }
  function load() {
    window.getJson('api/together').then(function (d) { if (!busy) { state = d; render(); } }).catch(function () {});
  }
  function act(path, data, done) {
    if (busy) return;
    busy = true;
    pending = path === 'api/together/create' ? 'create' : path === 'api/together/join' ? 'join' : '';
    render();
    window.post(path, data).then(function (r) {
      busy = false;
      pending = '';
      if (r && r.ok && r.state) { state = r; if (done) done(r); } else window.toast((r && r.message) || T('无法完成操作，请重试。'));
      render();
    }).catch(function () { busy = false; pending = ''; render(); window.fail(); });
  }
  // 粘贴的是自己的邀请时打开的是电视自己的房间, 电视是房主
  function joined(r) { window.toast(r.host ? T('房间 {0} 已开启', fmtNumber(r.number)) : T('电视已加入房间')); }
  function copyInvite() {
    var ta = document.getElementById('tg-invite-text');
    if (!ta) return;
    ta.hidden = false;
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, ta.value.length);
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) {}
    if (ok) ta.hidden = !inviteShown; else inviteShown = true;
    window.toast(ok ? T('已复制邀请') : T('请长按文本框手动复制'));
  }
  body.addEventListener('submit', function (e) {
    if (e.target.id !== 'tg-join') return;
    e.preventDefault();
    var f = e.target.elements, number = f.number.value.replace(/\D/g, ''), password = f.password.value.replace(/\D/g, '');
    if (number.length !== 8 || password.length !== 6) { window.toast(T('请输入 8 位房间号和 6 位密码')); return; }
    act('api/together/join', { number: number, password: password }, joined);
  });
  // 局域网页面是 http, 网页读不了剪贴板; 用户自己粘贴时浏览器会把内容交给 paste 事件。
  // 粘贴的不止一个号码 (数字多于房间号) 就当成邀请, 整段交给服务端认出房间号与密码后加入。
  body.addEventListener('paste', function (e) {
    var input = e.target;
    if (!input.form || input.form.id !== 'tg-join') return;
    var text = e.clipboardData ? e.clipboardData.getData('text') : '';
    if (text.replace(/\D/g, '').length <= 8) return;
    e.preventDefault();
    act('api/together/join', { invite: text }, joined);
  });
  body.addEventListener('click', function (e) {
    var b = e.target.closest('[data-tg]');
    if (!b || !state) return;
    var what = b.getAttribute('data-tg');
    if (what === 'create') act('api/together/create', {}, function (r) { window.toast(T('房间 {0} 已开启', fmtNumber(r.number))); });
    else if (what === 'copy') copyInvite();
    else if (what === 'follow') act('api/together/follow', { value: state.following ? '0' : '1' });
    else if (what === 'password') {
      if (state.members.length > 1 && !window.confirm(T('更换密码后，其他成员需要使用新密码重新加入。'))) return;
      act('api/together/password', {}, function () { window.toast(T('已更换密码')); });
    } else if (what === 'leave') {
      var wasHost = state.host;
      if (wasHost && !window.confirm(T('解散后所有成员都会退出房间。'))) return;
      act('api/together/leave', {}, function () { window.toast(wasHost ? T('已解散房间') : T('电视已离开房间')); });
    } else if (what === 'transfer') {
      var id = b.getAttribute('data-id');
      var target = state.members.filter(function (m) { return m.id === id; })[0];
      if (!target) return;
      var name = memberName(target);
      if (!window.confirm(T('将房主转让给 {0}？转让后此电视将成为成员。', name))) return;
      act('api/together/transfer', { member: id }, function () { window.toast(T('正在将房主转让给 {0}…', name)); });
    }
  });
  function poll() {
    clearInterval(timer);
    timer = null;
    if (box.open) timer = setInterval(function () { if (!document.hidden) load(); }, 3000);
  }
  box.addEventListener('toggle', function () { if (box.open) load(); else inviteShown = false; poll(); });
  // 电视「一起看」弹窗的二维码带着这个锚点: 等页面切完默认标签 (那一步也是 setTimeout 0, 排在前面) 再切到播放器、展开卡片
  if (location.hash === '#player/together') {
    setTimeout(function () {
      if (window.showTab) window.showTab('player');
      box.open = true;
      box.scrollIntoView({ block: 'start' });
    }, 0);
  }
  load();
})();
""".trimIndent()
