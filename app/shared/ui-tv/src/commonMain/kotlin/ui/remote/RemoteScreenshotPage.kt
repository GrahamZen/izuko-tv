/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/** 播放卡「截图」面板的样式 (见 [SHOT_SCRIPT]); 精细调整那几行沿用弹幕时间偏移的 `.dm-shift`. */
internal val SHOT_STYLE = """
.now-cache.shot-entry { padding: 5px 9px; }
#shot-sheet .sheet-body { padding-bottom: calc(24px + env(safe-area-inset-bottom)); }
/* 预览图钉在面板顶上 (同 #pick-head 的粘顶: 抵掉 .sheet-body 的上内边距), 往下翻选项时一直看得见 */
.shot-pv-wrap { position: sticky; top: -8px; z-index: 3; margin: -8px -16px 0; padding: 8px 16px 8px; background: var(--bg); }
.shot-pv { position: relative; border-radius: 12px; overflow: hidden; background: #000; aspect-ratio: 16 / 9; }
.shot-pv img { display: block; width: 100%; height: 100%; object-fit: contain; -webkit-touch-callout: default; }
.shot-pv img[hidden] { display: none; }
.shot-msg { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; padding: 16px; text-align: center;
  color: #fff; font-size: 14px; background: rgba(0, 0, 0, .45); }
.shot-msg[hidden] { display: none; }
.shot-sec { margin-top: 16px; }
.shot-h { font-size: 13px; font-weight: 700; color: var(--sub); margin-bottom: 6px; }
.shot-opt { display: flex; align-items: center; gap: 8px; padding: 8px 0; font-size: 15px; }
.shot-opt input { width: 18px; height: 18px; margin: 0; flex: none; }
.shot-opt em { font-style: normal; font-size: 12px; color: var(--mute); }
.shot-opt.off { color: var(--mute); }
.shot-grid { flex: none; display: grid; grid-template-columns: repeat(3, 30px); grid-template-rows: repeat(3, 20px); gap: 4px; padding: 6px;
  border-radius: 10px; background: var(--chip); }
.shot-grid button { border-radius: 5px; background: var(--card); padding: 0; }
.shot-grid button.on { background: var(--p); }
/* 水印: 一个一行, 收起时是缩略图 + 名字, 点开调位置 / 角度 / 大小 (文字水印还有显示哪几样) */
.shot-mark { border-radius: 12px; background: var(--card); margin-bottom: 8px; overflow: hidden; }
.shot-mark-head { display: flex; align-items: center; gap: 10px; padding: 8px 8px 8px 10px; cursor: pointer; }
.shot-thumb { flex: none; width: 64px; height: 36px; border-radius: 6px; background: #2b2b2b; display: flex; align-items: center; justify-content: center; overflow: hidden;
  color: #fff; font-size: 17px; font-weight: 700; }
.shot-thumb img { max-width: 56px; max-height: 30px; object-fit: contain; }
.shot-mark-head .shot-thumb { position: relative; cursor: pointer; }
.shot-swap { position: absolute; right: 2px; bottom: 2px; width: 16px; height: 16px; border-radius: 8px; background: rgba(0,0,0,.65);
  display: flex; align-items: center; justify-content: center; }
.shot-swap svg { width: 12px; height: 12px; fill: #fff; }
.shot-mname { flex: 1; min-width: 0; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.shot-mname small { display: block; font-size: 12px; color: var(--mute); overflow: hidden; text-overflow: ellipsis; }
.shot-chev { flex: none; width: 10px; height: 10px; border-right: 2px solid var(--mute); border-bottom: 2px solid var(--mute); transform: rotate(45deg);
  margin: 0 6px 4px 2px; transition: transform .2s; }
.shot-mark.open .shot-chev { transform: rotate(-135deg); margin-bottom: -4px; }
.shot-x { flex: none; width: 34px; height: 34px; border-radius: 17px; background: var(--chip); color: var(--on-chip); padding: 0; line-height: 0; }
.shot-x svg { width: 18px; height: 18px; fill: currentColor; }
.shot-mark-body { padding: 4px 12px 10px; border-top: 1px solid var(--line); }
.shot-mark-body .dm-shift { margin-top: 6px; }
.shot-mark-body .dm-shift > span { width: 2.6em; font-size: 15px; color: var(--fg); }
/* 九宫格右边并排 X / Y 两行, 展开后不至于太高 */
.shot-pos { display: flex; align-items: center; gap: 12px; margin-top: 6px; }
.shot-xy { flex: 1; min-width: 0; }
.shot-xy .dm-shift { margin-top: 0; flex-wrap: nowrap; }
.shot-xy .dm-shift + .dm-shift { margin-top: 6px; }
.shot-xy .dm-shift > span { width: 1.1em; }
.shot-xy .dm-shift button { min-width: 40px; padding: 0 8px; }
.shot-xy .dm-shift b, .shot-xy .dm-shift .dm-shift-in { min-width: 4em; width: 4em; }
.shot-texts { display: flex; flex-wrap: wrap; gap: 0 16px; }
.shot-mark-body .dm-shift button:disabled { opacity: .4; }
/* 大小的几档预设, 与上面那行的 − 按钮对齐 */
.shot-sizes { display: flex; margin-top: 6px; padding-left: calc(2.6em + 8px); font-size: 15px; }
.shot-sizes .seg { flex: 1; margin: 0; }
/* 「添加水印」子页: 深色底的格子 (logo 多是白字) */
.shot-cands { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 8px; margin-bottom: 12px; }
.shot-cand { display: flex; flex-direction: column; align-items: stretch; gap: 6px; padding: 8px; border-radius: 12px; background: var(--card); text-align: left; }
.shot-cand .shot-thumb { width: 100%; height: 64px; font-size: 24px; }
.shot-cand .shot-thumb img { max-width: 90%; max-height: 52px; }
.shot-cand span:last-child { font-size: 13px; color: var(--sub); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.shot-add-btns { display: flex; flex-direction: column; gap: 8px; }
/* 预设下拉: 挂在 body 上, 要盖过面板 (.sheet 50 起) */
.ep-menu.shot-pmenu { z-index: 59; min-width: 240px; }
.shot-pm-h { padding: 6px 12px 4px; font-size: 12px; font-weight: 700; color: var(--mute); }
.shot-pm-row { display: flex; align-items: center; gap: 4px; }
.shot-pm-row .ep-opt { flex: 1; min-width: 0; flex-direction: column; align-items: flex-start; gap: 2px; }
.shot-pm-row .ep-opt small { font-size: 12px; color: var(--mute); font-weight: 400; }
.shot-pm-row .shot-x { width: 30px; height: 30px; background: none; color: var(--mute); }
.shot-pm-empty { padding: 8px 12px; font-size: 14px; color: var(--mute); }
.shot-pm-save { color: var(--p); font-weight: 600; border-top: 1px solid var(--line); border-radius: 0 0 10px 10px; margin-top: 4px; }
/* 起名字的小窗 (样式同 .link-dlg-box 那一族) */
#shot-pdlg { position: fixed; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center; padding: 16px; background: rgba(0,0,0,.45); }
#shot-pdlg input { margin-top: 12px; }
.shot-add-btns [hidden] { display: none; }
"""

/**
 * 播放卡「截图」(相机按钮, 见 RemoteScreenshot): 电视截下画面、字幕、弹幕三层, 这里按勾选叠加、加水印, 合成后下载.
 *
 * - 水印是一个列表, 可以加好几个 (「添加水印」子页: 番剧 logo、Izuko TV、文字、TMDB 上的全部 logo、自己上传的图). 每个一行, 点开调
 *   位置 (九宫格预设, 右边并排 X / Y 精细调)、角度、大小; 文字水印再多勾显示哪几样 (番名一行, 集数与播放时间一行). 精细调整同弹幕时间
 *   偏移那套 (一下一档、按住数字左右拖、点数字输入): 位置是水印中心在图上的比例 (0–1), 角度就是度数 (−180°–180°, 「归零」恢复),
 *   大小 = logo 高占图高的比例 / 文字首行字高占图高的比例, 下面一排「小号 / 标准 / 大号」预设 (标准 = 这类水印加进来时的大小).
 *   上传的图先按比例缩到 512px 以内再做这些变换.
 * - 每个水印行的书签按钮: 把它的位置、角度、大小 (文字水印还有显示哪几样) 存成有名字的预设, 或者套用存过的; 预设存在电视上
 *   (见 RemoteScreenshotPresets). 点行首的缩略图换图 (同「添加水印」那一页挑), 位置、角度不变, 大小按相对「标准」的倍数换算.
 * - 改选项当场重画预览; 预览是 img, 手机上长按能直接存 (iPhone 存到相册). 选项与加过的水印记在本机 (localStorage), 下次照旧;
 *   从 TMDB 加的 logo 只画在那部番的截图上.
 */
internal val SHOT_SCRIPT = """
(function () {
  var CAMERA = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4zM9 2 7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z"/></svg>';
  var BACK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg>';
  var SWAP = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6.99 11 3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z"/></svg>';
  var BOOKMARK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2zm0 15-5-2.18L7 18V5h10v13z"/></svg>';
  // 播放卡标题行的按钮 (前台播放时才有, 见 PLAYER 卡片)
  window.shotEntry = function () {
    return '<button type="button" class="now-cache shot-entry" data-shot="1" aria-label="' + T('截图') + '" title="' + T('截图') + '">' + CAMERA + '</button>';
  };
  var UPLOAD_MAX = 512;
  function load(key, fallback) {
    try { var v = JSON.parse(localStorage.getItem(key) || 'null'); return v == null ? fallback : v; } catch (e) { return fallback; }
  }
  function store(key, v) { try { localStorage.setItem(key, JSON.stringify(v)); return true; } catch (e) { return false; } }
  var DEFAULTS = { sub: true, dm: false, fmt: 'png' };
  var opts = (function () {
    var o = {}, saved = load('shotOpts', {});
    for (var k in DEFAULTS) o[k] = k in saved && typeof saved[k] === typeof DEFAULTS[k] ? saved[k] : DEFAULTS[k];
    return o;
  })();
  /*
   * 加过的水印: { id, kind: title / app / tmdb / upload / text, name, x, y, deg, size, preset?, align? (text),
   *   title / ep / time (text: 显示哪几样), subject? / src? / thumb? (tmdb) }
   */
  var marks = load('shotMarks', []).filter(function (e) { return e && e.id && e.kind; });
  marks.forEach(function (e) { if (typeof e.deg !== 'number') e.deg = 0; });
  // 上传的图 (按比例缩过的 PNG data URL) 单独存: 存不下就只在这次打开时有效
  var uploads = load('shotUploads', {});
  function saveOpts() { store('shotOpts', opts); }
  var markSaveTimer = null;
  function saveMarks() {
    clearTimeout(markSaveTimer);
    markSaveTimer = setTimeout(function () { store('shotMarks', marks); }, 300);
  }

  var sheet = null, shot = null, imgs = {}, limg = {}, seq = 0, opened = {};
  // 水印预设 (存在电视上, 见 RemoteScreenshotPresets); null = 还没读到
  var presets = null, pmenu = null;
  // 「添加水印」那一页正在给谁换图; null = 新加
  var replacing = null;
  // 预览: 每次改选项 ver 加一, 合成好的图带着自己的 ver; 下载时 ver 对得上就直接用, 否则重新合成
  var ver = 0, blob = null, blobVer = -1, blobUrl = null, timer = null;
  var POS = [['tl', T('左上角')], ['tc', T('顶部居中')], ['tr', T('右上角')], ['ml', T('左侧居中')], ['mc', T('正中间')], ['mr', T('右侧居中')], ['bl', T('左下角')], ['bc', T('底部居中')], ['br', T('右下角')]];
  var LANGS = { zh: T('中文'), ja: T('日文'), en: T('英文'), ko: T('韩文') };
  var TEXT_PARTS = [['title', T('番名')], ['ep', T('集数')], ['time', T('播放时间')]];
  // 各类水印加进来时的大小 (= 「标准」那一档), 与小号 / 大号的倍数
  var DEFAULT_SIZE = { title: 0.1, app: 0.06, tmdb: 0.1, upload: 0.12, text: 0.035 };
  var SIZE_PRESETS = [[0.7, T('小号')], [1, T('标准')], [1.4, T('大号')]];
  function presetSize(e, factor) { return Math.round(DEFAULT_SIZE[e.kind] * factor * 1000) / 1000; }

  function el(id) { return document.getElementById(id); }
  function grid(cur) {
    return '<div class="shot-grid" data-lpos>' + POS.map(function (p) {
      return '<button type="button" data-v="' + p[0] + '" aria-label="' + p[1] + '" title="' + p[1] + '"' + (p[0] === cur ? ' class="on"' : '') + '></button>';
    }).join('') + '</div>';
  }
  function build() {
    sheet = document.createElement('div');
    sheet.id = 'shot-sheet';
    sheet.className = 'sheet';
    sheet.hidden = true;
    sheet.innerHTML = '<div class="sheet-head"><button type="button" class="sheet-btn" id="shot-back" hidden aria-label="' + T('返回') + '" title="' + T('返回') + '">' + BACK + '</button>' +
      '<div class="sheet-title" id="shot-title">' + T('截图') + '</div>' +
      '<button type="button" class="sheet-btn" id="shot-close" aria-label="' + T('关闭') + '" title="' + T('关闭') + '">' + window.ICONS.close + '</button></div>' +
      '<div class="sheet-body" id="shot-body"></div>';
    document.body.appendChild(sheet);
    el('shot-body').innerHTML = '<div id="shot-main">' +
      '<div class="shot-pv-wrap"><div class="shot-pv"><img id="shot-img" alt="" hidden><div class="shot-msg" id="shot-msg"></div></div></div>' +
      '<p class="hint">' + T('长按图片也能保存（iPhone 上可以存到相册）') + '</p>' +
      '<div class="shot-sec"><div class="shot-h">' + T('画面') + '</div>' +
      '<label class="shot-opt" id="shot-sub"><input type="checkbox" data-o="sub"><span>' + T('字幕') + '</span><em></em></label>' +
      '<label class="shot-opt" id="shot-dm"><input type="checkbox" data-o="dm"><span>' + T('弹幕') + '</span><em></em></label></div>' +
      '<div class="shot-sec"><div class="shot-h">' + T('水印') + '</div><div id="shot-marks"></div>' +
      '<button type="button" class="ghost wide ic" id="shot-add">' + window.ICONS.plus + T('添加水印') + '</button></div>' +
      '<div class="shot-sec"><div class="shot-h">' + T('格式') + '</div><div class="seg" data-o="fmt">' +
      '<button type="button" data-v="png">' + T('PNG（无损）') + '</button><button type="button" data-v="jpg">' + T('JPG（更小）') + '</button></div></div>' +
      '<div class="row"><button type="button" class="ghost" id="shot-again">' + T('重新截一张') + '</button>' +
      '<button type="button" class="primary" id="shot-save" disabled>' + T('下载') + '</button></div></div>' +
      '<div id="shot-addpage" hidden><p class="hint" id="shot-addhint"></p>' +
      '<div class="shot-cands" id="shot-cands"></div>' +
      '<div class="shot-cands" id="shot-tmdb" hidden></div>' +
      '<div class="shot-add-btns"><button type="button" class="ghost wide" id="shot-more">' + T('查看 TMDB 上的全部 logo') + '</button>' +
      '<button type="button" class="ghost wide" id="shot-upload">' + T('上传图片') + '</button>' +
      '<input type="file" id="shot-file" accept="image/*" hidden></div></div>';
    el('shot-close').addEventListener('click', close);
    el('shot-back').addEventListener('click', function () { page('main'); });
    sheet.addEventListener('change', function (e) {
      var t = e.target, k = t.getAttribute('data-o');
      if (k) { opts[k] = t.checked; changed(); return; }
      var part = t.getAttribute('data-part');
      if (part) {
        var entry = targetOf(t);
        if (!entry) return;
        entry[part] = t.checked;
        if (entry.preset) place(entry);
        renderMarks();
        markChanged(entry);
        return;
      }
      if (t.id === 'shot-file') upload(t);
    });
    sheet.addEventListener('click', onClick);
    stepper(el('shot-marks'));
  }
  function onClick(e) {
    var t = e.target;
    var b = t.closest('[data-o] > [data-v]');
    if (b) { opts[b.parentElement.getAttribute('data-o')] = b.getAttribute('data-v'); changed(); return; }
    var row = t.closest('.shot-mark');
    if (row) {
      var entry = find(row.getAttribute('data-id'));
      if (!entry) return;
      var lp = t.closest('[data-lpos] > [data-v]');
      if (lp) {
        entry.preset = lp.getAttribute('data-v');
        if (entry.kind === 'text') entry.align = entry.preset.charAt(1);
        place(entry);
        markChanged(entry);
        return;
      }
      var st = t.closest('button[data-st]');
      if (st) { nudge(entry, st.getAttribute('data-st'), Number(st.getAttribute('data-d'))); return; }
      var sz = t.closest('[data-sz] > [data-v]');
      if (sz) { setStep(entry, 'size', presetSize(entry, Number(sz.getAttribute('data-v')))); return; }
      if (t.closest('[data-reset="deg"]')) { setStep(entry, 'deg', 0); return; }
      var pb = t.closest('[data-lact="preset"]');
      if (pb) { togglePresetMenu(pb, entry); return; }
      if (t.closest('[data-lact="del"]')) {
        marks = marks.filter(function (x) { return x !== entry; });
        delete opened[entry.id];
        if (entry.kind === 'upload') { delete uploads[entry.id]; store('shotUploads', uploads); }
        saveMarks();
        renderMarks();
        ver++;
        preview();
        return;
      }
      if (t.closest('[data-swap]')) { page('add', entry); return; }
      if (t.closest('.shot-mark-head')) { opened[entry.id] = !opened[entry.id]; renderMarks(); }
      return;
    }
    var cand = t.closest('[data-cand]');
    if (cand) { addCandidate(cand); return; }
    if (t.closest('#shot-add')) { page('add'); return; }
    if (t.closest('#shot-more')) { loadTmdb(); return; }
    if (t.closest('#shot-upload')) { el('shot-file').click(); return; }
    if (t.closest('#shot-again')) { capture(); return; }
    if (t.closest('#shot-save')) save();
  }
  // target: 给哪个水印换图 (同一页挑, 挑中的换掉它的图); 不给 = 新加
  function page(name, target) {
    var add = name === 'add';
    replacing = add && target || null;
    el('shot-main').hidden = add;
    el('shot-addpage').hidden = !add;
    el('shot-back').hidden = !add;
    el('shot-title').textContent = !add ? T('截图') : replacing ? T('换图') : T('添加水印');
    el('shot-body').scrollTop = 0;
    if (!add) return;
    el('shot-addhint').textContent = replacing ? T('点一下就换掉「{0}」，位置、角度和大小都不变。', replacing.name)
      : T('点一下就加到截图上，可以加好几个；加上之后在列表里调位置、角度和大小。');
    renderCands();
  }
  function msg(text) {
    var m = el('shot-msg');
    m.hidden = !text;
    m.textContent = text || '';
  }
  // 选项的显示跟着 opts 与这一张有没有字幕 / 弹幕
  function sync() {
    [].forEach.call(sheet.querySelectorAll('input[data-o]'), function (i) { i.checked = !!opts[i.getAttribute('data-o')]; });
    [].forEach.call(sheet.querySelectorAll('[data-o] > [data-v]'), function (b) {
      b.classList.toggle('on', opts[b.parentElement.getAttribute('data-o')] === b.getAttribute('data-v'));
    });
    layerOpt('shot-sub', 'subtitles', T('这一刻画面上没有字幕'));
    layerOpt('shot-dm', 'danmaku', T('电视上弹幕关着，或者这一刻屏上没有弹幕'));
  }
  function layerOpt(id, layer, note) {
    var row = el(id), has = !shot || !!(shot.layers || {})[layer];
    row.querySelector('input').disabled = !has;
    row.classList.toggle('off', !has);
    row.querySelector('em').textContent = has ? '' : note;
  }
  function changed() {
    saveOpts();
    sync();
    ver++;
    preview();
  }

  // ---- 水印列表 ----
  function find(id) { for (var i = 0; i < marks.length; i++) if (marks[i].id === id) return marks[i]; return null; }
  function targetOf(node) {
    var row = node.closest('.shot-mark');
    return row ? find(row.getAttribute('data-id')) : null;
  }
  function newId() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }
  // 这一张上画不画它, 不画时说明原因
  function unusable(e) {
    if (e.kind === 'title' && shot && !shot.logo) return T('这部番没有番剧 logo');
    if (e.kind === 'tmdb' && shot && e.subject !== shot.subjectId) return T('这是别的番的 logo，这部不画');
    if (e.kind === 'upload' && !uploads[e.id]) return T('上传的图没存下来，请重新上传');
    if (e.kind === 'text' && !e.title && !e.ep && !e.time) return T('还没勾要显示什么');
    return '';
  }
  function thumbOf(e) {
    if (e.kind === 'title') return shot && shot.logo || '';
    if (e.kind === 'app') return shot && shot.icon || 'api/player/screenshot/icon.png';
    if (e.kind === 'tmdb') return e.thumb || '';
    if (e.kind === 'upload') return uploads[e.id] || '';
    return '';
  }
  // 行上的说明: 文字水印写显示哪几样, 其余写为什么不画
  function noteOf(e) {
    var why = unusable(e);
    if (why || e.kind !== 'text') return why;
    return TEXT_PARTS.filter(function (p) { return e[p[0]]; }).map(function (p) { return p[1]; }).join(T('、'));
  }
  // 精细调整的几个量: 一档多少、范围、拖几像素走一档. deg 是角度, x / y 是比例 (0–1);
  // 大小: logo 是高占图高的比例, 文字是首行字高占图高的比例 (小得多, 所以档也细)
  var STEPS = {
    x: { step: 0.01, min: 0, max: 1, px: 4, digits: 2 },
    y: { step: 0.01, min: 0, max: 1, px: 4, digits: 2 },
    deg: { step: 1, min: -180, max: 180, px: 2 },
    size: { step: 0.01, min: 0.01, max: 1, px: 4, digits: 2 },
    textSize: { step: 0.002, min: 0.01, max: 0.3, px: 4, digits: 3 }
  };
  function spec(e, k) { return k === 'size' && e.kind === 'text' ? STEPS.textSize : STEPS[k]; }
  function fmtStep(e, k) {
    var v = e[k];
    return k === 'deg' ? Math.round(v * 10) / 10 + '°' : v.toFixed(spec(e, k).digits);
  }
  // after: 接在 + 后面的按钮 (角度的「归零」)
  function stepRow(e, k, label, after) {
    return '<div class="dm-shift"><span>' + label + '</span><button type="button" data-st="' + k + '" data-d="-1">−</button>' +
      '<b data-st="' + k + '" title="' + T('点一下输入，按住左右拖可调') + '">' + fmtStep(e, k) + '</b>' +
      '<button type="button" data-st="' + k + '" data-d="1">+</button>' + (after || '') + '</div>';
  }
  function sizePresets(e) {
    return '<div class="shot-sizes"><div class="seg" data-sz>' + SIZE_PRESETS.map(function (p) {
      return '<button type="button" data-v="' + p[0] + '"' + (e.size === presetSize(e, p[0]) ? ' class="on"' : '') + '>' + p[1] + '</button>';
    }).join('') + '</div></div>';
  }
  function renderMarks() {
    el('shot-marks').innerHTML = marks.map(function (e) {
      var note = noteOf(e), thumb = thumbOf(e), open = !!opened[e.id];
      return '<div class="shot-mark' + (open ? ' open' : '') + '" data-id="' + esc(e.id) + '">' +
        '<div class="shot-mark-head"><span class="shot-thumb" data-swap role="button" aria-label="' + T('换图') + '" title="' + T('换图') + '">' +
        (e.kind === 'text' ? 'Aa' : thumb ? '<img src="' + esc(thumb) + '" alt="">' : '') + '<span class="shot-swap">' + SWAP + '</span></span>' +
        '<span class="shot-mname">' + esc(e.name) + (note ? '<small>' + esc(note) + '</small>' : '') + '</span>' +
        '<button type="button" class="shot-x" data-lact="preset" aria-label="' + T('预设') + '" title="' + T('预设') + '">' + BOOKMARK + '</button>' +
        '<button type="button" class="shot-x" data-lact="del" aria-label="' + T('删除') + '" title="' + T('删除') + '">' + window.ICONS.close + '</button>' +
        '<span class="shot-chev" aria-hidden="true"></span></div>' +
        (open ? '<div class="shot-mark-body">' +
          (e.kind === 'text' ? '<div class="shot-texts">' + TEXT_PARTS.map(function (p) {
            return '<label class="shot-opt"><input type="checkbox" data-part="' + p[0] + '"' + (e[p[0]] ? ' checked' : '') + '><span>' + p[1] + '</span></label>';
          }).join('') + '</div>' : '') +
          '<div class="shot-pos">' + grid(e.preset) + '<div class="shot-xy">' + stepRow(e, 'x', 'X') + stepRow(e, 'y', 'Y') + '</div></div>' +
          stepRow(e, 'deg', T('角度'), '<button type="button" data-reset="deg"' + (e.deg ? '' : ' disabled') + '>' + T('归零') + '</button>') +
          stepRow(e, 'size', T('大小')) + sizePresets(e) + '</div>' : '') +
        '</div>';
    }).join('');
  }
  // 只换那几个数字 (拖动、连按时不整块重画, 免得按钮在手指底下被换掉)
  function paintSteps(e) {
    var row = el('shot-marks').querySelector('.shot-mark[data-id="' + e.id + '"]');
    if (!row) return;
    [].forEach.call(row.querySelectorAll('b[data-st]'), function (b) { b.textContent = fmtStep(e, b.getAttribute('data-st')); });
    [].forEach.call(row.querySelectorAll('[data-lpos] > [data-v]'), function (b) { b.classList.toggle('on', b.getAttribute('data-v') === e.preset); });
    [].forEach.call(row.querySelectorAll('[data-sz] > [data-v]'), function (b) { b.classList.toggle('on', e.size === presetSize(e, Number(b.getAttribute('data-v')))); });
    var reset = row.querySelector('[data-reset="deg"]');
    if (reset) reset.disabled = !e.deg;
  }
  function setStep(e, k, v) {
    var s = spec(e, k);
    e[k] = Math.round(Math.max(s.min, Math.min(s.max, v)) * 1000) / 1000;
    // 动了 X / Y 就不再贴着预设位置; 改大小时贴着预设的跟着重新摆 (还贴着边)
    if (k === 'x' || k === 'y') e.preset = null;
    else if (k === 'size' && e.preset) place(e);
    markChanged(e);
  }
  function nudge(e, k, dir) { setStep(e, k, e[k] + dir * spec(e, k).step); }
  function markChanged(e) {
    paintSteps(e);
    saveMarks();
    ver++;
    preview();
  }
  // 水印的外框 (宽, 高, 像素; 旋转不算): logo 按图的比例, 文字按排出来的行
  function boxOf(e, W, H) {
    if (e.kind === 'text') {
      var b = textBlock(measureCtx(), e, W, H);
      return { w: b.w, h: b.h };
    }
    var img = limg[e.id], h = e.size * H;
    return { w: img ? h * img.width / img.height : h, h: h };
  }
  var mctx = null;
  function measureCtx() { return mctx || (mctx = document.createElement('canvas').getContext('2d')); }
  // 预设位置: 外框贴着边距摆, 换算成中心点的 X / Y
  function place(e) {
    if (!e.preset) return;
    var W = shot ? shot.width : 1920, H = shot ? shot.height : 1080, box = boxOf(e, W, H), m = Math.min(W, H) * 0.045;
    var col = e.preset.charAt(1), row = e.preset.charAt(0);
    e.x = Math.round((col === 'l' ? m + box.w / 2 : col === 'r' ? W - m - box.w / 2 : W / 2) / W * 1000) / 1000;
    e.y = Math.round((row === 't' ? m + box.h / 2 : row === 'b' ? H - m - box.h / 2 : H / 2) / H * 1000) / 1000;
  }
  /*
   * 数字的拖动与就地输入 (同弹幕时间偏移): 按住左右拖每几像素走一档 (见 STEPS), 没拖动当轻点 —— 变成输入框, 直接填值
   * (位置、大小填比例, 角度填度数).
   */
  function stepper(box) {
    var drag = null, editing = false;
    box.addEventListener('pointerdown', function (e) {
      var b = e.target.closest('b[data-st]');
      if (!b || editing) return;
      var entry = targetOf(b);
      if (!entry) return;
      drag = { b: b, entry: entry, k: b.getAttribute('data-st'), x: e.clientX, base: entry[b.getAttribute('data-st')], moved: false };
      b.classList.add('dragging');
      try { b.setPointerCapture(e.pointerId); } catch (x) {}
    });
    box.addEventListener('pointermove', function (e) {
      if (!drag) return;
      var dx = e.clientX - drag.x;
      if (!drag.moved && Math.abs(dx) < 5) return;
      drag.moved = true;
      var s = spec(drag.entry, drag.k);
      setStep(drag.entry, drag.k, drag.base + Math.round(dx / s.px) * s.step);
    });
    function end() {
      if (!drag) return;
      var d = drag;
      drag = null;
      d.b.classList.remove('dragging');
      if (!d.moved) edit(d.b, d.entry, d.k);
    }
    box.addEventListener('pointerup', end);
    box.addEventListener('pointercancel', end);
    function edit(b, entry, k) {
      editing = true;
      var input = document.createElement('input');
      input.type = 'text';
      input.className = 'dm-shift-in';
      input.inputMode = 'decimal';
      input.value = String(Math.round(entry[k] * 1000) / 1000);
      b.replaceWith(input);
      input.focus();
      input.select();
      var closed = false;
      function finish(ok) {
        if (closed) return;
        closed = true;
        editing = false;
        var v = parseFloat(input.value.replace('。', '.').replace('－', '-'));
        if (ok && !isNaN(v)) setStep(entry, k, v);
        input.replaceWith(b);
        b.textContent = fmtStep(entry, k);
      }
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); finish(true); }
        else if (e.key === 'Escape') finish(false);
      });
      input.addEventListener('blur', function () { finish(true); });
    }
  }

  // ---- 预设 ----
  function loadPresets() {
    return getJson('api/player/screenshot/presets', 8000).then(function (r) {
      if (r.ok) presets = r.presets;
      return presets;
    }).catch(function () { return presets; });
  }
  function posName(code) {
    for (var i = 0; i < POS.length; i++) if (POS[i][0] === code) return POS[i][1];
    return code;
  }
  // 菜单里每条下面的一行说明: (文字: 显示哪几样) · 位置 · 角度 · 大小
  function presetSummary(p) {
    var parts = [];
    if (p.kind === 'text') {
      parts.push(TEXT_PARTS.filter(function (q) { return p[q[0]]; }).map(function (q) { return q[1]; }).join(T('、')) || T('文字'));
    }
    parts.push(p.preset ? posName(p.preset) : 'X ' + p.x.toFixed(2) + ' Y ' + p.y.toFixed(2));
    if (p.deg) parts.push(Math.round(p.deg) + '°');
    var sp = SIZE_PRESETS.filter(function (q) { return Math.abs(q[0] - p.scale) < 0.005; })[0];
    parts.push(sp ? sp[1] : T('{0} 倍大小', Math.round(p.scale * 100) / 100));
    return parts.join(' · ');
  }
  // 这个水印的设置 → 一条预设 (大小存成相对「标准」的倍数, 文字与 logo 之间套用也合适)
  function presetOf(e, name) {
    var p = { id: newId(), name: name, kind: e.kind === 'text' ? 'text' : 'logo', x: e.x, y: e.y, deg: e.deg,
      scale: Math.round(e.size / DEFAULT_SIZE[e.kind] * 1000) / 1000 };
    if (e.preset) p.preset = e.preset;
    if (e.kind === 'text') { p.align = e.align; p.title = !!e.title; p.ep = !!e.ep; p.time = !!e.time; }
    return p;
  }
  // 套用: 位置 (预设格子, 或 X / Y)、角度、大小; 文字的「显示哪几样」只在文字之间套
  function applyPreset(e, p) {
    var s = spec(e, 'size');
    e.deg = p.deg || 0;
    e.size = Math.round(Math.max(s.min, Math.min(s.max, DEFAULT_SIZE[e.kind] * p.scale)) * 1000) / 1000;
    if (e.kind === 'text') {
      if (p.kind === 'text') { e.title = !!p.title; e.ep = !!p.ep; e.time = !!p.time; }
      e.align = p.align || (p.preset ? p.preset.charAt(1) : e.align);
    }
    e.preset = p.preset || null;
    if (e.preset) place(e);
    else { e.x = p.x; e.y = p.y; }
    renderMarks();
    markChanged(e);
    toast(T('已套用预设「{0}」', p.name));
  }
  function nextPresetName() {
    var names = (presets || []).map(function (p) { return p.name; }), n = (presets || []).length + 1;
    while (names.indexOf(T('预设 {0}', n)) >= 0) n++;
    return T('预设 {0}', n);
  }
  function closePresetMenu() {
    if (pmenu) { pmenu.remove(); pmenu = null; }
  }
  function togglePresetMenu(btn, entry) {
    if (pmenu && pmenu.anchor === btn) { closePresetMenu(); return; }
    closePresetMenu();
    var m = document.createElement('div');
    m.className = 'ep-menu shot-pmenu';
    m.anchor = btn;
    m.entry = entry;
    pmenu = m;
    paintPresetMenu();
    document.body.appendChild(m);
    placePresetMenu();
    if (presets == null) loadPresets().then(function () { if (pmenu === m) { paintPresetMenu(); placePresetMenu(); } });
  }
  function paintPresetMenu() {
    var m = pmenu;
    var h = '<div class="shot-pm-h">' + T('预设') + '</div>';
    if (presets == null) h += '<div class="shot-pm-empty">' + T('正在读取预设…') + '</div>';
    else if (!presets.length) h += '<div class="shot-pm-empty">' + T('还没有预设') + '</div>';
    else h += presets.map(function (p) {
      return '<div class="shot-pm-row"><button type="button" class="ep-opt" data-papply="' + esc(p.id) + '"><span>' + esc(p.name) + '</span>' +
        '<small>' + esc(presetSummary(p)) + '</small></button>' +
        '<button type="button" class="shot-x" data-pdel="' + esc(p.id) + '" aria-label="' + T('删除') + '" title="' + T('删除') + '">' + window.ICONS.close + '</button></div>';
    }).join('');
    h += '<button type="button" class="ep-opt shot-pm-save" data-psave>' + T('把这个水印的设置存成预设') + '</button>';
    m.innerHTML = h;
  }
  // 摆在按钮下面、右边对齐按钮; 下面放不下就摆到上面
  function placePresetMenu() {
    var m = pmenu, r = m.anchor.getBoundingClientRect();
    m.style.maxHeight = Math.max(200, innerHeight - 32) + 'px';
    var left = Math.max(16, Math.min(r.right - m.offsetWidth, innerWidth - 16 - m.offsetWidth));
    var top = r.bottom + 6;
    if (top + m.offsetHeight > innerHeight - 16) top = Math.max(16, r.top - 6 - m.offsetHeight);
    m.style.left = (left + scrollX) + 'px';
    m.style.top = (top + scrollY) + 'px';
  }
  document.addEventListener('click', function (e) {
    if (!pmenu || !pmenu.contains(e.target)) return;
    var entry = pmenu.entry, t = e.target;
    var ap = t.closest('[data-papply]');
    if (ap) {
      var id = ap.getAttribute('data-papply');
      var p = (presets || []).filter(function (x) { return x.id === id; })[0];
      closePresetMenu();
      if (p && find(entry.id)) applyPreset(entry, p);
      return;
    }
    var del = t.closest('[data-pdel]');
    if (del) {
      var pid = del.getAttribute('data-pdel');
      var target = (presets || []).filter(function (x) { return x.id === pid; })[0];
      if (!target || !confirm(T('删掉预设「{0}」？', target.name))) return;
      post('api/player/screenshot/presets/delete', { id: pid }).then(function (r) {
        presets = r.presets || presets;
        if (!r.ok && r.message) toast(r.message);
        if (pmenu) { paintPresetMenu(); placePresetMenu(); }
      }).catch(fail);
      return;
    }
    if (t.closest('[data-psave]')) {
      closePresetMenu();
      askPresetName(entry);
    }
  });
  // 按在菜单外面就关 (iOS 点没有点击处理的地方不发 click, 所以听 pointerdown); 面板滚动时也关, 免得菜单跟按钮错位
  document.addEventListener('pointerdown', function (e) {
    if (pmenu && !pmenu.contains(e.target) && !pmenu.anchor.contains(e.target)) closePresetMenu();
  }, true);
  document.addEventListener('scroll', function (e) { if (pmenu && !pmenu.contains(e.target)) closePresetMenu(); }, true);
  function askPresetName(entry) {
    var d = document.createElement('div');
    d.id = 'shot-pdlg';
    d.innerHTML = '<form class="link-dlg-box"><div class="link-dlg-t">' + T('存成预设') + '</div>' +
      '<p class="dlg-p">' + T('存下这个水印的位置、角度和大小（文字水印还有显示哪几样），以后在别的水印上也能套用。预设存在电视上，换手机、换浏览器也能用。') + '</p>' +
      '<input type="text" name="name" maxlength="40" autocomplete="off" aria-label="' + T('预设名字') + '" value="' + esc(nextPresetName()) + '">' +
      '<div class="row"><button type="button" class="ghost" data-pd="cancel">' + T('取消') + '</button>' +
      '<button type="submit" class="primary">' + T('保存') + '</button></div></form>';
    document.body.appendChild(d);
    var form = d.querySelector('form'), input = form.elements.name;
    input.focus();
    input.select();
    d.addEventListener('click', function (e) {
      if (e.target === d || e.target.closest('[data-pd="cancel"]')) d.remove();
    });
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var name = input.value.trim();
      if (!name) { toast(T('请给预设起个名字')); return; }
      var saveBtn = form.querySelector('button[type=submit]');
      saveBtn.disabled = true;
      post('api/player/screenshot/presets/add', { preset: JSON.stringify(presetOf(entry, name)) }).then(function (r) {
        saveBtn.disabled = false;
        if (r.presets) presets = r.presets;
        if (!r.ok) { toast(r.message || T('没存上')); return; }
        d.remove();
        toast(T('已存成预设「{0}」', name));
      }).catch(function () { saveBtn.disabled = false; fail(); });
    });
  }

  // ---- 添加水印 ----
  // lazy: 缩略图之后由 queueThumbs 慢慢要 (TMDB 那一批), 否则直接给地址; text: 直接写字 (文字水印)
  function candHtml(attrs, thumb, label, lazy, text) {
    var inner = text ? esc(text) : lazy ? '' : thumb ? '<img src="' + esc(thumb) + '" alt="">' : '';
    return '<button type="button" class="shot-cand" ' + attrs + '><span class="shot-thumb"' + (lazy ? ' data-thumb="' + esc(thumb) + '"' : '') + '>' +
      inner + '</span><span>' + esc(label) + '</span></button>';
  }
  function renderCands() {
    var h = '';
    if (shot && shot.logo) h += candHtml('data-cand="title"', shot.logo, T('番剧 logo'));
    h += candHtml('data-cand="app"', shot && shot.icon || 'api/player/screenshot/icon.png', 'Izuko TV');
    h += candHtml('data-cand="text"', '', T('番名、集数、播放时间'), false, 'Aa');
    el('shot-cands').innerHTML = h;
  }
  function addEntry(entry, preset) {
    entry.id = entry.id || newId();
    entry.deg = 0;
    entry.preset = preset;
    entry.x = 0.5;
    entry.y = 0.5;
    marks.push(entry);
    opened = {};
    opened[entry.id] = true;
    place(entry);
    saveMarks();
    page('main');
    renderMarks();
    ensureImg(entry).then(function () { place(entry); paintSteps(entry); ver++; preview(); });
  }
  function addCandidate(btn) {
    var kind = btn.getAttribute('data-cand');
    if (kind === 'title') commit({ kind: 'title', name: T('番剧 logo'), size: DEFAULT_SIZE.title }, 'tl');
    else if (kind === 'app') commit({ kind: 'app', name: 'Izuko TV', size: DEFAULT_SIZE.app }, 'tr');
    else if (kind === 'text') commit({ kind: 'text', name: T('文字'), title: true, ep: true, time: true, align: 'r', size: DEFAULT_SIZE.text }, 'br');
    else if (kind === 'tmdb') {
      var data = JSON.parse(btn.getAttribute('data-logo'));
      commit({ kind: 'tmdb', name: data.name, subject: shot ? shot.subjectId : null, src: data.src, thumb: data.thumb, size: DEFAULT_SIZE.tmdb }, 'tl');
    }
  }
  // 子页上挑中了 props (新水印的样子, preset = 新加时摆在哪); img: 已经在手上的图 (上传的)
  function commit(props, preset, img) {
    if (replacing) replaceEntry(replacing, props, img);
    else {
      if (img) limg[props.id] = img;
      addEntry(props, preset);
    }
  }
  /*
   * 换图: 位置 (格子或 X / Y)、角度不动; 大小按相对「标准」的倍数换算 (logo 与文字量纲不同); 文字换文字保留显示哪几样,
   * 换成文字时三样都显示. 贴着格子的按新图的比例重新贴边.
   */
  function replaceEntry(e, props, img) {
    var scale = e.size / DEFAULT_SIZE[e.kind], wasText = e.kind === 'text';
    if (e.kind === 'upload' && props.kind !== 'upload') { delete uploads[e.id]; store('shotUploads', uploads); }
    ['src', 'thumb', 'subject'].forEach(function (k) { delete e[k]; if (props[k] !== undefined) e[k] = props[k]; });
    e.kind = props.kind;
    e.name = props.name;
    if (e.kind === 'text') {
      if (!wasText) { e.title = e.ep = e.time = true; e.align = e.preset ? e.preset.charAt(1) : 'r'; }
    } else {
      ['title', 'ep', 'time', 'align'].forEach(function (k) { delete e[k]; });
    }
    var s = spec(e, 'size');
    e.size = Math.round(Math.max(s.min, Math.min(s.max, DEFAULT_SIZE[e.kind] * scale)) * 1000) / 1000;
    delete limg[e.id];
    if (img) limg[e.id] = img;
    place(e);
    saveMarks();
    page('main');
    renderMarks();
    ensureImg(e).then(function () { place(e); paintSteps(e); ver++; preview(); });
  }
  var tmdbLoaded = false;
  // 列出来之后按钮就收起; 没拿到时留着, 可以再点
  function loadTmdb() {
    var box = el('shot-tmdb'), more = el('shot-more');
    box.hidden = false;
    more.hidden = true;
    if (tmdbLoaded) return;
    box.innerHTML = '<p class="hint">' + T('正在从 TMDB 找 logo…') + '</p>';
    getJson('api/player/screenshot/logos', 30000).then(function (r) {
      if (!r.ok) { box.innerHTML = '<p class="hint">' + esc(r.message || '') + '</p>'; more.hidden = false; return; }
      if (!r.logos.length) { box.innerHTML = '<p class="hint">' + T('TMDB 上没有这部番的 logo') + '</p>'; return; }
      tmdbLoaded = true;
      box.innerHTML = r.logos.map(function (l, i) {
        var name = T('TMDB logo {0}', i + 1) + (l.lang ? ' · ' + (LANGS[l.lang] || l.lang.toUpperCase()) : '');
        var data = esc(JSON.stringify({ name: name, src: l.src, thumb: l.thumb }));
        return candHtml('data-cand="tmdb" data-logo="' + data + '"', l.thumb, name, true);
      }).join('');
      queueThumbs(box);
    }).catch(function () { box.innerHTML = '<p class="hint">' + T('没能从 TMDB 拿到这部番的 logo') + '</p>'; more.hidden = false; });
  }
  // 缩略图经电视转发, 电视同时只拉几张: 两张两张地要, 回 503 (排不上) 的过一会儿再要
  function queueThumbs(box) {
    var list = [].slice.call(box.querySelectorAll('[data-thumb]')), running = 0;
    function one(span) {
      running++;
      loadWithRetry([span.getAttribute('data-thumb')], 3).then(function (img) {
        if (img) span.appendChild(img);
        running--;
        next();
      });
    }
    function next() {
      while (running < 2 && list.length) one(list.shift());
    }
    next();
  }
  function loadImg(src) {
    return new Promise(function (ok, no) {
      var i = new Image();
      i.onload = function () { ok(i); };
      i.onerror = function () { no(new Error(src)); };
      i.src = src;
    });
  }
  // 依次试 srcs 里的地址, 都不行就隔 1.5 秒再来一轮, 最多 rounds 轮; 拿不到为 null
  function loadWithRetry(srcs, rounds) {
    var i = 0;
    function attempt() {
      return loadImg(srcs[i % srcs.length]).catch(function () {
        i++;
        if (i >= srcs.length * rounds) return null;
        return new Promise(function (ok) { setTimeout(ok, i % srcs.length ? 0 : 1500); }).then(attempt);
      });
    }
    return attempt();
  }
  // 「Izuko TV」: 应用图标 + 字, 先画成一张图, 之后与别的 logo 一样摆放
  function appMark(icon) {
    var s = 128, gap = 28, fs = 68, c = document.createElement('canvas'), g = c.getContext('2d');
    g.font = '700 ' + fs + 'px ' + FONT;
    var tw = Math.ceil(g.measureText('Izuko TV').width);
    c.width = s + gap + tw + 4;
    c.height = s;
    g.save();
    g.beginPath();
    roundRect(g, 0, 0, s, s, s * 0.22);
    g.clip();
    g.drawImage(icon, 0, 0, s, s);
    g.restore();
    g.font = '700 ' + fs + 'px ' + FONT;
    g.fillStyle = '#fff';
    g.textBaseline = 'middle';
    g.fillText('Izuko TV', s + gap, s / 2);
    return c;
  }
  function ensureImg(e) {
    if (e.kind === 'text' || limg[e.id]) return Promise.resolve(limg[e.id] || null);
    var p;
    if (e.kind === 'title') p = Promise.resolve(imgs.logo || null);
    else if (e.kind === 'app') p = Promise.resolve(imgs.icon ? appMark(imgs.icon) : null);
    else if (e.kind === 'tmdb') p = loadWithRetry(e.src || [], 2);
    else p = uploads[e.id] ? loadImg(uploads[e.id]).catch(function () { return null; }) : Promise.resolve(null);
    return p.then(function (img) { if (img) limg[e.id] = img; return img; });
  }
  // 上传: 先按比例缩到 UPLOAD_MAX 以内 (再大的图也是这个尺寸起步), 存成 PNG
  function upload(input) {
    var file = input.files && input.files[0];
    input.value = '';
    if (!file) return;
    var url = URL.createObjectURL(file);
    loadImg(url).then(function (img) {
      URL.revokeObjectURL(url);
      var k = Math.min(1, UPLOAD_MAX / Math.max(img.naturalWidth, img.naturalHeight));
      var c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(img.naturalWidth * k));
      c.height = Math.max(1, Math.round(img.naturalHeight * k));
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      var entry = { id: replacing ? replacing.id : newId(), kind: 'upload', name: file.name || T('上传的图片'), size: DEFAULT_SIZE.upload };
      uploads[entry.id] = c.toDataURL('image/png');
      if (!store('shotUploads', uploads)) toast(T('图片太大，存不下，只在这次截图里能用'));
      commit(entry, 'tl', c);
    }).catch(function () {
      URL.revokeObjectURL(url);
      toast(T('图片读不出来'));
    });
  }

  // ---- 截图与合成 ----
  function capture() {
    var my = ++seq;
    shot = null;
    imgs = {};
    limg = {};
    blob = null;
    blobVer = -1;
    el('shot-save').disabled = true;
    el('shot-img').hidden = true;
    msg(T('正在截图…'));
    sync();
    renderMarks();
    post('api/player/screenshot', {}).then(function (r) {
      if (my !== seq) return;
      if (!r.ok) { msg(r.message || T('截图失败')); return; }
      shot = r;
      sync();
      msg(T('正在从电视取画面…'));
      var layers = r.layers || {};
      // 画面必须有; 字幕 / 弹幕 / logo 取不到就当没有
      var optional = function (src) { return src ? loadImg(src).catch(function () { return null; }) : Promise.resolve(null); };
      return Promise.all([loadImg(layers.frame), optional(layers.subtitles), optional(layers.danmaku), optional(r.logo), optional(r.icon)])
        .then(function (list) {
          if (my !== seq) return;
          imgs = { frame: list[0], subtitles: list[1], danmaku: list[2], logo: list[3], icon: list[4] };
          if (!imgs.logo) shot.logo = null;
          if (!imgs.subtitles) delete layers.subtitles;
          if (!imgs.danmaku) delete layers.danmaku;
          sync();
          renderMarks();
          return Promise.all(marks.map(ensureImg));
        })
        .then(function () {
          if (my !== seq) return;
          // 预设位置按这一张的尺寸、这一部的文字重新摆
          marks.forEach(function (e) { if (e.preset) place(e); });
          renderMarks();
          ver++;
          preview();
        });
    }).catch(function () {
      if (my !== seq) return;
      msg(T('没取到画面，请重新截一张'));
    });
  }
  function preview() {
    if (!imgs.frame) return;
    clearTimeout(timer);
    timer = setTimeout(function () {
      var my = ver;
      compose(function (b) {
        if (!b || my !== ver) return;
        blob = b;
        blobVer = my;
        if (blobUrl) URL.revokeObjectURL(blobUrl);
        blobUrl = URL.createObjectURL(b);
        var img = el('shot-img');
        img.src = blobUrl;
        img.hidden = false;
        msg('');
        el('shot-save').disabled = false;
      });
    }, 80);
  }
  function compose(done) {
    var f = imgs.frame, c = document.createElement('canvas');
    c.width = f.naturalWidth;
    c.height = f.naturalHeight;
    var g = c.getContext('2d');
    g.drawImage(f, 0, 0);
    // 叠放次序同电视: 字幕在视频里, 弹幕盖在上面; 水印在最上, 按列表顺序
    if (opts.sub && imgs.subtitles) g.drawImage(imgs.subtitles, 0, 0, c.width, c.height);
    if (opts.dm && imgs.danmaku) g.drawImage(imgs.danmaku, 0, 0, c.width, c.height);
    marks.forEach(function (e) {
      if (unusable(e)) return;
      if (e.kind === 'text') drawText(g, c.width, c.height, e);
      else if (limg[e.id]) drawLogo(g, c.width, c.height, e, limg[e.id]);
    });
    c.toBlob(done, opts.fmt === 'jpg' ? 'image/jpeg' : 'image/png', 0.92);
  }
  function shadow(g, k) {
    g.shadowColor = 'rgba(0, 0, 0, .55)';
    g.shadowBlur = 8 * k;
    g.shadowOffsetY = 2 * k;
  }
  // 以水印中心 (x, y) 为原点转 deg 度
  function drawLogo(g, W, H, e, img) {
    var h = e.size * H, w = h * img.width / img.height;
    g.save();
    shadow(g, H / 1080);
    g.translate(e.x * W, e.y * H);
    g.rotate(e.deg * Math.PI / 180);
    g.drawImage(img, -w / 2, -h / 2, w, h);
    g.restore();
  }
  var FONT = 'system-ui, -apple-system, "PingFang SC", "Noto Sans SC", "Microsoft YaHei", sans-serif';
  function fmtTime(ms) {
    var s = Math.floor((ms || 0) / 1000), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (x < 10 ? '0' : '') + x;
  }
  // 文字水印排版: 番名一行、集数与时间一行; 首行字高 = size × 图高, 第二行小一号 (只有第二行时也按小一号)
  function textBlock(g, e, W, H) {
    var lines = [], sub = [], base = e.size * H, bw = 0, bh = 0, gap = base * 0.26;
    var s = shot || { title: T('番名'), episode: T('集数'), position: 0 };
    if (e.title && s.title) lines.push({ t: s.title, size: base, weight: 700 });
    if (e.ep && s.episode) sub.push(s.episode + (s.episodeName ? ' ' + s.episodeName : ''));
    if (e.time) sub.push(fmtTime(s.position));
    if (sub.length) lines.push({ t: sub.join('  ·  '), size: base * 0.74, weight: 500 });
    lines.forEach(function (l) {
      g.font = l.weight + ' ' + l.size + 'px ' + FONT;
      // 太长的截短, 不让水印横穿整张图
      var max = W * 0.6;
      if (g.measureText(l.t).width > max) {
        while (l.t.length > 1 && g.measureText(l.t + '…').width > max) l.t = l.t.slice(0, -1);
        l.t += '…';
      }
      l.w = g.measureText(l.t).width;
      l.h = l.size * 1.25;
      bw = Math.max(bw, l.w);
      bh += l.h;
    });
    if (lines.length) bh += gap * (lines.length - 1);
    return { lines: lines, w: bw, h: bh, gap: gap, base: base };
  }
  // 行在块里怎么对齐跟着最后选的预设列 (靠右的预设右对齐, 居中的居中)
  function drawText(g, W, H, e) {
    var b = textBlock(g, e, W, H);
    if (!b.lines.length) return;
    g.save();
    shadow(g, b.base / 38);
    g.translate(e.x * W, e.y * H);
    g.rotate(e.deg * Math.PI / 180);
    g.fillStyle = '#fff';
    g.textBaseline = 'middle';
    var y = -b.h / 2;
    b.lines.forEach(function (l) {
      g.font = l.weight + ' ' + l.size + 'px ' + FONT;
      var x = e.align === 'l' ? -b.w / 2 : e.align === 'r' ? b.w / 2 - l.w : -l.w / 2;
      g.fillText(l.t, x, y + l.h / 2);
      y += l.h + b.gap;
    });
    g.restore();
  }
  function roundRect(g, x, y, w, h, r) {
    g.moveTo(x + r, y);
    g.arcTo(x + w, y, x + w, y + h, r);
    g.arcTo(x + w, y + h, x, y + h, r);
    g.arcTo(x, y + h, x, y, r);
    g.arcTo(x, y, x + w, y, r);
    g.closePath();
  }
  function fileName() {
    var parts = [shot.title, shot.episode, fmtTime(shot.position).replace(/:/g, '-')].filter(Boolean);
    return parts.join(' ').replace(/[\\/:*?"<>|]+/g, ' ').trim() + (opts.fmt === 'jpg' ? '.jpg' : '.png');
  }
  function save() {
    if (!imgs.frame) return;
    var go = function (b) {
      if (!b) { toast(T('生成图片失败')); return; }
      var url = URL.createObjectURL(b), a = document.createElement('a');
      a.href = url;
      a.download = fileName();
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
    };
    if (blob && blobVer === ver) go(blob);
    else compose(go);
  }
  function open() {
    if (!sheet) build();
    page('main');
    window.sheets.open(sheet);
    capture();
    loadPresets();
  }
  function close() {
    seq++;
    clearTimeout(timer);
    closePresetMenu();
    window.sheets.close(sheet);
    // 4K 截图解出来几十 MB, 关了就放掉
    imgs = {};
    limg = {};
    shot = null;
    blob = null;
    tmdbLoaded = false;
    el('shot-tmdb').hidden = true;
    el('shot-tmdb').innerHTML = '';
    el('shot-more').hidden = false;
    if (blobUrl) { URL.revokeObjectURL(blobUrl); blobUrl = null; }
    el('shot-img').removeAttribute('src');
  }
  document.addEventListener('click', function (e) {
    if (e.target.closest('[data-shot]')) open();
  });
})();
"""
