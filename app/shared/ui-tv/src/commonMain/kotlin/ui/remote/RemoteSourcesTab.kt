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
 * Web 控制台底栏的「数据源」标签: 顶上一排分段 (列表 / 订阅 / 网盘 / 本地 / BT) 与右边的「+」, 下面各页的容器.
 *
 * 各页的内容由各自的脚本画: 列表与添加表单 (`#src-list` / `#src-add`) 是 SOURCES_SCRIPT, 订阅 (`#src-subs`) 是 SUBS_SCRIPT,
 * 云盘卡片 (`#src-drives`) 与本地文件夹、WebDAV 卡片 (`#src-local`) 是 CLOUD_DRIVE_SCRIPT, BT 页的设置项 (`#src-bt`) 是
 * SETTINGS_SCRIPT 按清单的 `page` 分过来的, PikPak (`#set-pikpak`) 是 SETTINGS_EXTRAS_SCRIPT.
 * 分段切换与地址栏 (`#sources/页名`) 在 SCRIPT 的 `showSrcTab` 里; 这里是「+」、它弹的小菜单与添加面板.
 */
internal val SOURCES_TAB_HTML = """
<section class="tab" id="tab-sources" hidden>
<div id="src-top"><div class="seg" id="src-seg"><button type="button" data-stab="list" class="on">列表</button><button type="button" data-stab="subs">订阅</button><button type="button" data-stab="drives">网盘</button><button type="button" data-stab="local">本地</button><button type="button" data-stab="bt">BT</button></div><button type="button" id="src-plus" aria-label="添加" title="添加"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg></button></div>
<div class="src-pane" data-stab="list">
<p class="hint">修改立即保存。正在播放的这一集不受影响，下一集或重新进入播放器时生效。订阅来的源只能启用或停用。</p>
<div id="src-list"></div>
</div>
<div class="src-pane" data-stab="subs" hidden>
<div id="src-subs"></div>
</div>
<div class="src-pane" data-stab="drives" hidden>
<div id="src-drives"></div>
</div>
<div class="src-pane" data-stab="local" hidden>
<p class="hint">本地文件夹和局域网 WebDAV 里有这一集时，开播直接选它，不等在线源。</p>
<div id="src-local"></div>
<button type="button" class="card src-go-cache" data-go-tab="cache"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 9h-4V3H9v6H5l7 7 7-7zm-8 2V5h2v6h1.17L12 13.17 9.83 11H11zm-6 7h14v2H5z"/></svg><span><b>下载的缓存</b><small>在「缓存」标签里管理</small></span><span class="set-nav-go">›</span></button>
</div>
<div class="src-pane" data-stab="bt" hidden>
<p class="hint">BT 源怎么连接、怎么下载；BT 源本身的启用停用在「列表」里。</p>
<div id="src-bt"></div>
<div id="set-pikpak"></div>
</div>
</section>
<div id="add-sheet" class="sheet" hidden>
<div class="sheet-head"><button type="button" class="sheet-btn" id="add-back" hidden aria-label="返回" title="返回"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg></button><div class="sheet-title" id="add-title">添加数据源</div><button type="button" class="sheet-btn" id="add-close" aria-label="关闭" title="关闭"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button></div>
<div class="sheet-body"><div id="src-add"></div></div>
</div>
""".trimIndent()

/** 底栏「数据源」的图标: 空心 (.o) 与选中时的实心 (.f), 同底栏其他几个. */
internal const val SOURCES_TAB_BUTTON =
    """<button data-tab="sources"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M19 15v4H5v-4h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 18.5c-.82 0-1.5-.67-1.5-1.5s.68-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM19 5v4H5V5h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 8.5c-.82 0-1.5-.67-1.5-1.5S6.18 5.5 7 5.5s1.5.68 1.5 1.5S7.83 8.5 7 8.5z"/><path class="f" d="M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"/></svg><span class="tl">数据源</span></button>"""

internal val SOURCES_TAB_STYLE = """
/* 「数据源」标签顶上那一行: 分段 + 「+」, 一直贴在顶上. 粘顶写法同 #search-seg (自身用页面底色, ::before 垫层撑满左右);
   这一行是外层容器, 分段是普通子元素, 灰色轨道照常用 .seg 自己的背景 */
#src-top { position: sticky; top: 8px; z-index: 4; display: flex; align-items: center; gap: 8px; margin: 2px 0 12px; background: var(--bg); }
#src-top::before { content: ''; position: absolute; inset: -10px -16px -4px; background: var(--bg); z-index: -1; }
#src-seg { flex: 1; min-width: 0; margin: 0; }
/* 英文的标签长短差得多 (Subscriptions / BT): 按字的宽度分, 放不下时省略 */
#src-seg button { flex: 1 1 auto; min-width: 0; padding: 8px 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
#src-plus { flex: none; width: 40px; height: 40px; padding: 0; border-radius: 20px; background: var(--p); color: var(--on-p); display: inline-flex; align-items: center; justify-content: center; }
#src-plus svg { width: 24px; height: 24px; fill: currentColor; }
/* 本地页「+」弹的小菜单: 借选集列表的 .ep-menu / .ep-opt */
.src-plus-menu { min-width: 240px; }
.src-plus-h { padding: 8px 12px 4px; font-size: 13px; font-weight: 600; color: var(--sub); }
.src-plus-opt b { display: block; font-size: 15px; }
.src-plus-opt small { display: block; margin-top: 2px; font-size: 12px; font-weight: 400; color: var(--mute); }
/* 本地页最下面一行: 下载的缓存在「缓存」标签 */
.src-go-cache { display: flex; align-items: center; gap: 14px; width: 100%; margin-top: 12px; text-align: left; color: var(--fg); }
.src-go-cache svg { flex: none; width: 24px; height: 24px; fill: var(--p); }
.src-go-cache > span:first-of-type { flex: 1; min-width: 0; }
.src-go-cache b { display: block; font-size: 15px; }
.src-go-cache small { display: block; margin-top: 2px; font-size: 12px; color: var(--mute); }
/* 本地页的卡片收起成一行 (见 CLOUD_DRIVE_SCRIPT 的 foldCard): 图标、名字与媒体文件夹、状态, 展开才是全部内容 */
.cd-fold > summary { list-style: none; display: flex; align-items: center; gap: 10px; cursor: pointer; }
.cd-fold > summary::-webkit-details-marker { display: none; }
.cd-fold > summary .src-ic { width: 30px; height: 30px; margin-right: 0; }
.cd-fold > summary .src-ic.ph { border-radius: 15px; }
.cd-fold > summary .src-ic.ph::before { font-size: 14px; }
.cd-fold-t { flex: 1; min-width: 0; }
.cd-fold-t b { display: block; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cd-fold-t small { display: block; margin-top: 2px; font-size: 12px; color: var(--mute); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cd-pill { flex: none; padding: 2px 8px; border-radius: 8px; font-size: 12px; font-weight: 600; background: var(--ok-bg); color: var(--ok-fg); }
.cd-pill.warn { background: var(--warn-bg); color: var(--warn-fg); }
.cd-pill.err { background: var(--err-bg); color: var(--err-fg); }
.cd-fold > summary::after { content: ''; flex: none; width: 20px; height: 20px; background: var(--mute);
  -webkit-mask: var(--chev) center / contain no-repeat; mask: var(--chev) center / contain no-repeat; }
.cd-fold[open] > summary::after { transform: rotate(180deg); }
.cd-fold[open] > summary { margin-bottom: 12px; }
/* 添加面板第一屏的类型列表 (见 SOURCES_SCRIPT 的 renderAdd): 行同设置的分类列表, 说明可以折两行 */
#src-new-list h2:first-child { margin-top: 8px; }
#src-new-list[hidden], #src-new-panel[hidden] { display: none; }
.add-row .set-nav-txt small { white-space: normal; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
/* BT 页的设置项默认收起: 一行标题 + 现在有几条, 展开才是输入框 */
.gen-fold > summary { list-style: none; display: flex; align-items: center; gap: 10px; cursor: pointer; }
.gen-fold > summary::-webkit-details-marker { display: none; }
.gen-fold > summary .set-title { flex: 1; min-width: 0; margin: 0; }
.gen-fold > summary small { flex: none; font-size: 13px; color: var(--mute); }
.gen-fold > summary::after { content: ''; flex: none; width: 20px; height: 20px; background: var(--mute);
  -webkit-mask: var(--chev) center / contain no-repeat; mask: var(--chev) center / contain no-repeat; }
.gen-fold[open] > summary::after { transform: rotate(180deg); }
.gen-fold[open] > summary { margin-bottom: 10px; }
""".trimIndent()

/**
 * 「+」五页都在 (换页时分段条不变宽窄), 按当前页做不同的事: 订阅 = 跳到订阅卡片底下的地址框; 本地 = 先选本地文件夹还是 WebDAV,
 * 再打开添加面板直接到那个类型的表单; 列表、网盘、BT = 打开添加面板 (第一屏是类型列表; 网盘的接入方式来自订阅或导入的数据源, BT 页本身只有设置).
 * 面板里的类型列表与表单由 SOURCES_SCRIPT 画 (renderAdd / addSourceType), 添加成功或取消时它调 `window.sourceAddSheet.close()`.
 */
internal val SOURCES_TAB_SCRIPT = """
(function () {
  var plus = document.getElementById('src-plus'), sheet = document.getElementById('add-sheet');
  var ACTIONS = { list: 'add', subs: 'sub', drives: 'add', local: 'local', bt: 'add' };
  var menu = null;
  function closeMenu() {
    if (menu) { menu.remove(); menu = null; }
  }
  // SCRIPT 的 showSrcTab 切页时调: 这一页按「+」做什么
  window.srcPlus = function (stab) {
    closeMenu();
    plus.setAttribute('data-act', ACTIONS[stab] || 'add');
  };
  // kind: '' = 类型列表, 'webdav' / 'local' = 直接打开那个类型的表单 (见 SOURCES_SCRIPT 的 addSourceType); 不传 = 只打开面板, 内容不动
  function openAdd(kind) {
    closeMenu();
    window.sheets.open(sheet);
    if (kind !== undefined && window.addSourceType) window.addSourceType(kind);
  }
  window.sourceAddSheet = {
    open: openAdd,
    close: function () { window.sheets.close(sheet); }
  };
  function opt(kind, title, sub) {
    return '<button type="button" class="ep-opt src-plus-opt" data-add-kind="' + kind + '"><span><b>' + title + '</b><small>' + sub + '</small></span></button>';
  }
  function openMenu() {
    closeMenu();
    var m = document.createElement('div');
    m.className = 'ep-menu src-plus-menu';
    m.innerHTML = '<div class="src-plus-h">' + T('添加到本地') + '</div>' +
      opt('local', T('本地文件夹'), T('U 盘、移动硬盘、电视的内部存储')) +
      opt('webdav', 'WebDAV', T('NAS 或其他 WebDAV 服务'));
    document.body.appendChild(m);
    var r = plus.getBoundingClientRect();
    m.style.left = (Math.max(16, r.right - m.offsetWidth) + scrollX) + 'px';
    m.style.top = (r.bottom + scrollY + 6) + 'px';
    menu = m;
  }
  plus.addEventListener('click', function () {
    var a = plus.getAttribute('data-act');
    if (a === 'add') openAdd('');
    else if (a === 'local') { if (menu) closeMenu(); else openMenu(); }
    else if (a === 'sub') {
      var i = document.querySelector('#src-subs .sub-add input');
      if (i) { i.scrollIntoView({ block: 'center', behavior: 'smooth' }); i.focus(); }
    }
  });
  document.addEventListener('click', function (e) {
    var o = e.target.closest && e.target.closest('[data-add-kind]');
    if (o && menu && menu.contains(o)) openAdd(o.getAttribute('data-add-kind'));
  });
  // 按到菜单外面就关: 按下就关, 不等 click (iOS 上点没有点击处理的地方不发 click, 同选集列表)
  document.addEventListener('pointerdown', function (e) {
    var t = e.target;
    if (menu && !(t.closest && (t.closest('.src-plus-menu') || t.closest('#src-plus')))) closeMenu();
  }, true);
  document.getElementById('add-close').addEventListener('click', function () { window.sheets.close(sheet); });
  document.getElementById('tab-sources').addEventListener('click', function (e) {
    var g = e.target.closest('[data-go-tab]');
    if (g && window.showTab) window.showTab(g.getAttribute('data-go-tab'));
  });
})();
""".trimIndent()

/** 数据源标签与设置标签搜索框的文案 (译文表见 [REMOTE_I18N_TABLE]). */
internal fun sourcesTabTexts() = listOf(
    RemoteText("列表", "List", "列表", "列表"),
    RemoteText("添加数据源", "Add source", "添加數據源", "新增資料源"),
    RemoteText("添加到本地", "Add to Local", "添加到本地", "新增到本地"),
    RemoteText("U 盘、移动硬盘、电视的内部存储", "USB drives, external disks, the TV’s internal storage", "U 盤、移動硬盤、電視的內部存儲", "隨身碟、外接硬碟、電視的內部儲存空間"),
    RemoteText("NAS 或其他 WebDAV 服务", "A NAS or another WebDAV service", "NAS 或其他 WebDAV 服務", "NAS 或其他 WebDAV 服務"),
    RemoteText("本地文件夹和局域网 WebDAV 里有这一集时，开播直接选它，不等在线源。", "When a local folder or a WebDAV server on your network has this episode, playback picks it right away without waiting for online sources.", "本地文件夾和局域網 WebDAV 裡有這一集時，開播直接選它，不等在線源。", "本機資料夾和區域網路 WebDAV 裡有這一集時，開播直接選它，不等線上源。"),
    RemoteText("下载的缓存", "Downloaded caches", "下載的緩存", "下載的快取"),
    RemoteText("在「缓存」标签里管理", "Manage them in the “Cache” tab", "在「緩存」標籤裡管理", "在「快取」分頁裡管理"),
    RemoteText("BT 源怎么连接、怎么下载；BT 源本身的启用停用在「列表」里。", "How BT sources connect and download. Turn BT sources themselves on or off under “List”.", "BT 源怎麼連接、怎麼下載；BT 源本身的啟用停用在「列表」裡。", "BT 源怎麼連線、怎麼下載；BT 源本身的啟用停用在「列表」裡。"),
    RemoteText("没有", "None", "沒有", "沒有"),
    RemoteText("顶上分五页：列表、订阅、网盘、本地、BT。右上角的「+」按所在的页添加：列表页添加单个数据源或导入 JSON，本地页添加本地文件夹或 WebDAV。", "Five pages at the top: List, Subscriptions, Cloud drive, Local and BT. The “+” at the top right adds to the current page: a single source or a JSON import on List, a local folder or WebDAV server on Local.", "頂上分五頁：列表、訂閲、網盤、本地、BT。右上角的「+」按所在的頁添加：列表頁添加單個數據源或導入 JSON，本地頁添加本地文件夾或 WebDAV。", "頂上分五頁：列表、訂閱、網盤、本地、BT。右上角的「+」按所在的頁新增：列表頁新增單個資料源或匯入 JSON，本地頁新增本機資料夾或 WebDAV。"),
    RemoteText("BT 页：额外 Tracker、Peer 过滤规则与 PikPak 账号，点一行展开修改。", "BT: extra trackers, peer filter rules and the PikPak account. Tap a row to expand and edit it.", "BT 頁：額外 Tracker、Peer 過濾規則與 PikPak 帳號，點一行展開修改。", "BT 頁：額外 Tracker、Peer 過濾規則與 PikPak 帳號，點一行展開修改。"),
    // 设置标签的搜索框
    RemoteText("搜索设置", "Search settings", "搜索設置", "搜尋設定"),
    RemoteText("没有找到相关的设置", "No matching settings", "沒有找到相關的設置", "沒有找到相關的設定"),
    RemoteText("在别的标签里", "In other tabs", "在別的標籤裡", "在別的分頁裡"),
    RemoteText("数据源列表", "Source list", "數據源列表", "資料源列表"),
    RemoteText("本地文件夹与 WebDAV", "Local folders and WebDAV", "本地文件夾與 WebDAV", "本機資料夾與 WebDAV"),
    RemoteText("BT 设置", "BT settings", "BT 設置", "BT 設定"),
    RemoteText("已下载的缓存", "Downloaded caches", "已下載的緩存", "已下載的快取"),
    RemoteText("数据源 › 列表", "Sources › List", "數據源 › 列表", "資料源 › 列表"),
    RemoteText("数据源 › 订阅", "Sources › Subscriptions", "數據源 › 訂閲", "資料源 › 訂閱"),
    RemoteText("数据源 › 网盘", "Sources › Cloud drive", "數據源 › 網盤", "資料源 › 網盤"),
    RemoteText("数据源 › 本地", "Sources › Local", "數據源 › 本地", "資料源 › 本地"),
    RemoteText("数据源 › BT", "Sources › BT", "數據源 › BT", "資料源 › BT"),
    RemoteText("底栏「缓存」", "“Cache” in the bottom bar", "底欄「緩存」", "底欄「快取」"),
    // 添加面板的类型列表与本地页的卡片
    RemoteText("添加订阅", "Add subscription", "添加訂閲", "新增訂閱"),
    RemoteText("在线源一般都来自订阅，一次加一批", "Online sources usually come from subscriptions, a whole batch at once", "在線源一般都來自訂閲，一次加一批", "線上源一般都來自訂閱，一次加一批"),
    RemoteText("单个数据源", "Single source", "單個數據源", "單個資料源"),
    RemoteText("导入 JSON", "Import JSON", "導入 JSON", "匯入 JSON"),
    RemoteText("粘贴或选文件：单个、列表或订阅内容都可以", "Paste or pick a file: a single source, a list or subscription content", "粘貼或選文件：單個、列表或訂閲內容都可以", "貼上或選檔案：單個、列表或訂閱內容都可以"),
    RemoteText("已停用", "Disabled", "已停用", "已停用"),
    RemoteText("还没有添加媒体文件夹", "No media folders yet", "還沒有添加媒體文件夾", "還沒有新增媒體資料夾"),
    RemoteText("地址", "Address", "地址", "地址"),
    RemoteText("正在添加…", "Adding…", "正在添加…", "正在新增…"),
    RemoteText("缓存放在哪、片库", "Where caches are stored, media library", "緩存放在哪、片庫", "快取放在哪、片庫"),
    RemoteText("在电视上改：设置 → 存储，有一步步的引导", "Change it on the TV under Settings → Storage, which walks you through it", "在電視上改：設置 → 存儲，有一步步的引導", "在電視上改：設定 → 儲存，有一步步的引導"),
)
