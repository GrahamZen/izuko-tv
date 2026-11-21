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
 * 接口见 [RemoteDanmaku]:
 * - 播放器标签「弹幕」里的导入 (`#dm-import`): 选文件 (可多选, 逐个上传) 或粘贴这一集的网页链接; 换集时重新读列表.
 * - 「设置 → 播放与弹幕」的弹幕服务器 (`#set-dmservers`): 列表、停用、测试, 手动添加与删除.
 */
internal val DANMAKU_EXTRAS_SCRIPT = """
(function () {
  var hooks = window.remoteHooks, setHtml = window.setHtml;
  var box = document.getElementById('dm-import');
  var lastEpisode = null;

  function loadImports() {
    getJson('api/danmaku/import/list').then(renderImports).catch(function () {});
  }
  function renderImports(d) {
    if (!d.ok) { setHtml(box, ''); return; }
    var h = '<div class="dm-imp"><p class="hint">' + T('给这一集加上自己找的弹幕，只保存在这台电视上。') + '</p>' +
      '<button type="button" class="ghost wide" data-imp="pick">' + T('导入弹幕文件（XML、JSON）') + '</button>';
    if (d.canLink) {
      h += '<form class="dm-form" id="dm-imp-link"><input type="text" name="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="' +
        T('粘贴这一集的网页链接') + '"><button type="submit" class="primary">' + T('取弹幕') + '</button></form>';
    } else {
      h += '<p class="hint">' + T('在「设置 → 播放与弹幕」添加弹幕服务器后，还能用网页链接取弹幕。') + '</p>';
    }
    d.items.forEach(function (x) {
      h += '<div class="dm-src"><div class="dm-top"><div class="dm-name">' + esc(x.label) + '<small>' + T('{0} 条', x.count) + '</small></div>' +
        '<button type="button" class="ghost" data-imp="del" data-id="' + esc(x.id) + '">' + T('删除') + '</button></div></div>';
    });
    setHtml(box, h + '</div>');
  }
  if (box) {
    hooks.unavailable.push(function () { lastEpisode = null; setHtml(box, ''); });
    hooks.render.push(function (s) {
      var episode = s.episode || '';
      if (episode === lastEpisode) return;
      lastEpisode = episode;
      loadImports();
    });
    box.addEventListener('click', function (e) {
      var b = e.target.closest('[data-imp]');
      if (!b) return;
      if (b.getAttribute('data-imp') === 'pick') pickFiles();
      else post('api/danmaku/import/delete', { id: b.getAttribute('data-id') }).then(afterImport).catch(fail);
    });
    box.addEventListener('submit', function (e) {
      e.preventDefault();
      var input = e.target.elements.url, btn = e.target.querySelector('button');
      var url = input.value.trim();
      if (!url) return;
      btn.disabled = true;
      toast(T('正在让服务器取弹幕…'));
      // 服务器可能要现抓, 比一般操作慢
      fetchT('api/danmaku/import/link', { method: 'POST', body: new URLSearchParams({ url: url }) }, 60000)
        .then(function (r) { return r.json(); })
        .then(function (r) {
          btn.disabled = false;
          if (r.ok) input.value = '';
          afterImport(r);
        })
        .catch(function () { btn.disabled = false; fail(); });
    });
  }
  function afterImport(r) {
    toast(r.message);
    loadImports();
    poll(true);
  }
  // 选文件要在点击里直接调起 (手机浏览器只认用户手势)
  function pickFiles() {
    var input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.accept = '.xml,.json,text/xml,application/json';
    input.addEventListener('change', function () {
      var files = [].slice.call(input.files || []);
      if (files.length) uploadFrom(files, 0, 0, '');
    });
    input.click();
  }
  function uploadFrom(files, index, succeeded, lastMessage) {
    if (index >= files.length) {
      var message = files.length === 1 ? lastMessage : T('导入完成：{0} 个成功，{1} 个失败', succeeded, files.length - succeeded);
      afterImport({ message: message });
      return;
    }
    if (files.length > 1) toast(T('正在导入第 {0} / {1} 个文件', index + 1, files.length));
    fetchT('api/danmaku/import/file?name=' + encodeURIComponent(files[index].name), {
      method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: files[index]
    }, 60000).then(function (r) {
      if (r.status === 413) return { ok: false, message: T('文件太大（最多 16 MB）') };
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (r) {
      uploadFrom(files, index + 1, succeeded + (r.ok ? 1 : 0), r.message);
    }).catch(fail);
  }

  var sv = document.getElementById('set-dmservers');
  if (!sv) return;
  function loadServers() {
    getJson('api/danmaku/servers').then(renderServers).catch(function () {});
  }
  function renderServers(d) {
    var h = '<div class="card set-card"><div class="set-title">' + T('弹幕服务器') + '</div>' +
      '<p class="hint">' + T('兼容弹弹play 接口的服务器，每台在播放器的弹幕源里单独一项。播放时会把番名和集数发给这些服务器，只添加你信任的。') + '</p>';
    (d.items || []).forEach(function (x) {
      var from = x.manual ? T('手动添加') : T('来自订阅「{0}」', x.from);
      h += '<div class="dm-src' + (x.on ? '' : ' off') + '"><div class="dm-top">' +
        '<label class="src-sw"><input type="checkbox" data-ds="on" data-url="' + esc(x.url) + '"' + (x.on ? ' checked' : '') + '></label>' +
        '<div class="dm-name">' + esc(x.name) + '<small>' + esc(x.host) + ' · ' + esc(from) + '</small></div></div>' +
        '<div class="row"><button type="button" class="ghost" data-ds="test" data-url="' + esc(x.url) + '">' + T('测试连接') + '</button>' +
        (x.manual ? '<button type="button" class="ghost" data-ds="del" data-url="' + esc(x.url) + '">' + T('删除') + '</button>' : '') + '</div></div>';
    });
    h += '<form id="ds-add"><label class="f"><span>' + T('名字（显示在播放器里，可以不填）') + '</span><input type="text" name="name" autocomplete="off"><em>' +
      T('不填就用域名。播放器里大约放得下 6 个字，太长或太像时不好认。') + '</em></label>' +
      '<label class="f"><span>' + T('服务器地址') + '</span><input type="text" name="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://"><em>' +
      T('填到 /api/v2 之前。带访问令牌的话，令牌写在地址里。') + '</em></label>' +
      '<div class="row"><button type="submit" class="primary">' + T('添加') + '</button></div></form>' +
      '<p class="hint">' + T('订阅文件的 danmakuServers 字段里写的服务器也会出现在这里。') + '</p></div>';
    setHtml(sv, h);
  }
  sv.addEventListener('change', function (e) {
    var t = e.target;
    if (t.getAttribute('data-ds') !== 'on') return;
    post('api/danmaku/servers/enable', { url: t.getAttribute('data-url'), on: t.checked ? '1' : '0' }).then(loadServers).catch(fail);
  });
  sv.addEventListener('click', function (e) {
    var b = e.target.closest('button[data-ds]');
    if (!b) return;
    var url = b.getAttribute('data-url');
    if (b.getAttribute('data-ds') === 'test') {
      b.disabled = true;
      post('api/danmaku/servers/test', { url: url }).then(function (r) {
        b.disabled = false;
        toast(r.message);
      }).catch(function () { b.disabled = false; fail(); });
    } else if (confirm(T('删除这台弹幕服务器？'))) {
      post('api/danmaku/servers/delete', { url: url }).then(function (r) {
        toast(r.message);
        loadServers();
      }).catch(fail);
    }
  });
  sv.addEventListener('submit', function (e) {
    e.preventDefault();
    var form = e.target, btn = form.querySelector('button[type=submit]');
    btn.disabled = true;
    post('api/danmaku/servers/add', new FormData(form)).then(function (r) {
      btn.disabled = false;
      toast(r.message);
      if (r.ok) loadServers();
    }).catch(function () { btn.disabled = false; fail(); });
  });
  document.addEventListener('click', function (e) {
    if (e.target.closest('.set-nav-row[data-group="play"]')) loadServers();
  });
  loadServers();
})();
""".trimIndent()
