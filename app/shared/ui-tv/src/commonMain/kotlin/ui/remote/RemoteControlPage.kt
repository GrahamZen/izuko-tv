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
 * 手机控制中心的网页: 底部四个标签 (搜索 / 播放器 / 缓存 / 设置) 的单页应用; 搜索标签顶上再分「搜索 / 结果」两页,
 * 设置标签再分「常规 / 数据源」两页.
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
    <meta name="color-scheme" content="light dark">
    <meta name="theme-color" content="#fafafa">
    <title>Animeko 控制中心</title>
    <script>
    """.trimIndent() + "\n" + THEME_HEAD_SCRIPT + "\n" + """
    </script>
    <style>
    """.trimIndent() + "\n" + STYLE + "\n" + """
    </style>
    </head>
    <body>
    <header>Animeko 控制中心</header>
    <div id="tv-state" class="tv-state" hidden></div>
    <section class="tab" id="tab-search" hidden>
    <div class="seg" id="search-seg"><button type="button" data-sub="form" class="on">搜索</button><button type="button" data-sub="results">结果<small id="res-count"></small></button></div>
    <div id="search-pane">
    """.trimIndent() + "\n" + searchFormHtml + "\n" + """
    </div>
    <div id="results-pane" hidden>
    <div id="res-head"></div>
    <div id="res-list" class="list"></div>
    <div id="res-foot"></div>
    </div>
    </section>
    <section class="tab" id="tab-player" hidden>
    <div id="player-now"></div>
    <details class="card req" id="stats-box" hidden><summary>播放信息<small>分辨率、编码、码率、丢帧</small></summary>
    <div id="player-stats"></div></details>
    <details class="card req" id="dm-box" hidden><summary>弹幕<small id="dm-sum"></small></summary>
    <div class="dm-wrap"><form class="dm-form" id="dm-send"><input type="text" name="text" maxlength="100" autocomplete="off" placeholder="发一条弹幕（在电视当前进度）"><button type="submit" class="primary">发送</button></form>
    <div id="dm-body"></div><div id="dm-match"></div></div></details>
    <details class="card req" id="tr-box" hidden><summary>音轨与字幕</summary><div class="dm-wrap" id="tr-body"></div></details>
    <details class="card req" id="cm-box" hidden><summary>评论与评分<small id="cm-sum"></small></summary><div class="dm-wrap" id="cm-body"></div></details>
    <div id="player-episode"></div>
    """.trimIndent() + "\n" + requestSectionHtml + "\n" + """
    <div id="player-chips"></div>
    <div id="player-filters"></div>
    <div id="player-sources"></div>
    </section>
    <section class="tab" id="tab-cache" hidden>
    <div id="cl-sum"></div>
    <div id="cl-list"></div>
    </section>
    <section class="tab" id="tab-settings" hidden>
    <div class="seg" id="set-seg"><button type="button" data-ssub="general" class="on">常规</button><button type="button" data-ssub="sources">数据源</button></div>
    <div id="set-general">
    <div id="set-account"></div>
    <div id="set-look"></div>
    <p class="hint">下面只放要打字的设置，开关类的请在电视上改。</p>
    <div id="set-proxy"></div>
    <div id="set-trackers"></div>
    <div id="set-dmfilter"></div>
    <div id="set-logs"></div>
    </div>
    <div id="set-sources" hidden>
    <p class="hint">修改立即保存。正在播放的这一集不受影响，下一集或重新进入播放器时生效。订阅来的源只能启用或停用。</p>
    <div id="src-subs"></div>
    <div id="src-add"></div>
    <div id="src-list"></div>
    </div>
    </section>
    <div id="cache-sheet" class="sheet" hidden>
    <div class="sheet-head"><button type="button" class="sheet-btn" id="cache-back" hidden aria-label="返回">‹</button>
    <div class="sheet-title" id="cache-title">缓存</div><button type="button" class="sheet-btn" id="cache-close" aria-label="关闭">✕</button></div>
    <div class="sheet-body" id="cache-body"></div>
    </div>
    <div id="toast"></div>
    <nav class="tabbar">
    <button data-tab="search"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg><span class="tl">搜索</span></button>
    <button data-tab="player"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M10 16.5l6-4.5-6-4.5v9zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z"/><path class="f" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"/></svg><span class="tl">播放器</span></button>
    <button data-tab="cache"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M19 9h-4V3H9v6H5l7 7 7-7zm-8 2V5h2v6h1.17L12 13.17 9.83 11H11zm-6 7h14v2H5z"/><path class="f" d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg><span class="tl">缓存</span></button>
    <button data-tab="settings"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M19.43 12.98c.04-.32.07-.64.07-.98 0-.34-.03-.66-.07-.98l2.11-1.65c.19-.15.24-.42.12-.64l-2-3.46c-.09-.16-.26-.25-.44-.25-.06 0-.12.01-.17.03l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65C14.46 2.18 14.25 2 14 2h-4c-.25 0-.46.18-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1c-.06-.02-.12-.03-.18-.03-.17 0-.34.09-.43.25l-2 3.46c-.13.22-.07.49.12.64l2.11 1.65c-.04.32-.07.65-.07.98 0 .33.03.66.07.98l-2.11 1.65c-.19.15-.24.42-.12.64l2 3.46c.09.16.26.25.44.25.06 0 .12-.01.17-.03l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.06.02.12.03.18.03.17 0 .34-.09.43-.25l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.65zm-1.98-1.71c.04.31.05.52.05.73 0 .21-.02.43-.05.73l-.14 1.13.89.7 1.08.84-.7 1.21-1.27-.51-1.04-.42-.9.68c-.43.32-.84.56-1.25.73l-1.06.43-.16 1.13-.2 1.35h-1.4l-.19-1.35-.16-1.13-1.06-.43c-.43-.18-.83-.41-1.23-.71l-.91-.7-1.06.43-1.27.51-.7-1.21 1.08-.84.89-.7-.14-1.13c-.03-.31-.05-.54-.05-.74s.02-.43.05-.73l.14-1.13-.89-.7-1.08-.84.7-1.21 1.27.51 1.04.42.9-.68c.43-.32.84-.56 1.25-.73l1.06-.43.16-1.13.2-1.35h1.39l.19 1.35.16 1.13 1.06.43c.43.18.83.41 1.23.71l.91.7 1.06-.43 1.27-.51.7 1.21-1.07.85-.89.7.14 1.13zM12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0 6c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"/><path class="f" d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg><span class="tl">设置</span></button>
    </nav>
    <script>
    var INITIAL_TAB = '$initialTab';
    """.trimIndent() + "\n" + SCRIPT + "\n" + REQUEST_SCRIPT + "\n" + CONTROL_SCRIPT + "\n" + DANMAKU_SCRIPT + "\n" + REVIEW_SCRIPT + "\n" + CACHE_SCRIPT + "\n" + CACHE_LIST_SCRIPT + "\n" + SOURCES_SCRIPT +"\n" + SUBS_SCRIPT + "\n" + SETTINGS_SCRIPT + "\n" + LOOK_SCRIPT + "\n" + LOGS_SCRIPT + "\n" + ACCOUNT_SCRIPT + "\n" + """
    </script>
    </body>
    </html>
    """.trimIndent()

private val STYLE = """
/* 配色全走变量: 这一组是浅色, 深色在 [data-theme="dark"] 里覆盖. 自动 / 浅色 / 深色由 <head> 里那段脚本定 (见 THEME_HEAD_SCRIPT) */
:root {
  color-scheme: light;
  --p: #6750a4; --on-p: #fff; --p-soft: #efe7fb; --seg-on: #fff;
  --bg: #fafafa; --card: #fff; --raised: #fff; --field: #fff; --soft: #f6f2fa; --soft2: #fcfbfd; --disabled: #f4f2f6;
  --fg: #1c1b1f; --sub: #49454f; --mute: #79747e; --chip: #e7e0ec; --on-chip: #1d192b;
  --outline: #cac4d0; --line: #f0edf2; --line2: #e6e0e9;
  --ok: #1e7b34; --ok-bg: #d8f3dc; --ok-fg: #1b5e20; --warn-bg: #fff0c2; --warn-fg: #6b4e00;
  --err: #b3261e; --err-bg: #ffdad6; --err-fg: #410002;
  --shadow: rgba(0,0,0,.08); --shadow-sm: rgba(0,0,0,.12); --shadow-lg: rgba(0,0,0,.16);
  --toast-bg: rgba(28,27,31,.92); --toast-fg: #fff; --fade: rgba(250,250,250,0);
}
/* 深色: Material 3 深色基线 (主色 #d0bcff, 主色上的字 #381e72) */
:root[data-theme="dark"] {
  color-scheme: dark;
  --p: #d0bcff; --on-p: #381e72; --p-soft: #4a4458; --seg-on: #4a4458;
  --bg: #141218; --card: #211f26; --raised: #2b2930; --field: #1d1b20; --soft: #2b2930; --soft2: #1d1b20; --disabled: #2b2930;
  --fg: #e6e0e9; --sub: #cac4d0; --mute: #938f99; --chip: #36343b; --on-chip: #e6e0e9;
  --outline: #56525c; --line: #2f2d35; --line2: #36343b;
  --ok: #86d993; --ok-bg: #1e3a24; --ok-fg: #b7e4c0; --warn-bg: #3d3200; --warn-fg: #f3d77f;
  --err: #f2b8b5; --err-bg: #8c1d18; --err-fg: #ffdad6;
  --shadow: rgba(0,0,0,.4); --shadow-sm: rgba(0,0,0,.3); --shadow-lg: rgba(0,0,0,.5);
  --toast-bg: rgba(230,224,233,.95); --toast-fg: #1c1b1f; --fade: rgba(20,18,24,0);
}
* { box-sizing: border-box; }
body { font-family: system-ui, sans-serif; margin: 0; background: var(--bg); color: var(--fg); -webkit-tap-highlight-color: transparent; }
header { padding: 16px 16px 4px; font-size: 20px; font-weight: 700; }
.tab { padding: 8px 16px calc(150px + env(safe-area-inset-bottom)); }
h2 { font-size: 14px; font-weight: 600; color: var(--sub); margin: 22px 0 8px; }
h2 small { font-weight: 400; color: var(--mute); }
p.hint { color: var(--mute); font-size: 13px; margin: 8px 0; }
input[type=text], input[type=password], textarea { width: 100%; font: inherit; font-size: 16px; padding: 12px 14px; border: 1px solid var(--outline); border-radius: 12px; background: var(--field); color: var(--fg); }
input[type=text]:focus, input[type=password]:focus, textarea:focus { outline: 2px solid var(--p); border-color: transparent; }
.pills { display: flex; flex-wrap: wrap; gap: 8px; }
.pills label { position: relative; }
.pills input { position: absolute; opacity: 0; width: 0; height: 0; }
.pills span { display: inline-block; padding: 7px 14px; border-radius: 18px; background: var(--chip); color: var(--on-chip); font-size: 14px; line-height: 1.3; user-select: none; }
.pills input:checked + span { background: var(--p); color: var(--on-p); }
button { font: inherit; border: 0; cursor: pointer; }
.primary { background: var(--p); color: var(--on-p); font-weight: 600; padding: 13px 18px; border-radius: 14px; font-size: 16px; }
.wide { width: 100%; }
.bar { position: fixed; left: 0; right: 0; bottom: calc(56px + env(safe-area-inset-bottom)); padding: 10px 16px; background: linear-gradient(to top, var(--bg) 70%, var(--fade)); }
/* 底栏: 图标 + 文字, 选中项整组变主色、图标换实心 (.o 空心 / .f 实心; 搜索没有实心款). 不在图标后面衬色块:
   那样图标和文字被拆成上下两块, 重心上浮. 图标内嵌 SVG, 不引外部资源 */
.tabbar { position: fixed; left: 0; right: 0; bottom: 0; z-index: 40; height: calc(56px + env(safe-area-inset-bottom)); padding-bottom: env(safe-area-inset-bottom); display: flex; background: var(--card); border-top: 1px solid var(--line2); }
.tabbar button { flex: 1; min-width: 0; background: none; padding: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px; color: var(--mute); }
.tabbar svg { width: 24px; height: 24px; fill: currentColor; }
.tabbar .f, .tabbar button.on .o { display: none; }
.tabbar button.on .f { display: inline; }
.tabbar .tl { font-size: 12px; line-height: 16px; }
.tabbar button.on { color: var(--p); }
.tabbar button.on .tl { font-weight: 700; }
.card { background: var(--card); border-radius: 14px; padding: 14px 16px; box-shadow: 0 1px 3px var(--shadow); }
.now-title { font-size: 18px; font-weight: 700; }
.now-link { cursor: pointer; }
.now-link::after { content: " ›"; color: var(--mute); font-weight: 400; }
.now-status { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 8px; margin: 10px 0 2px; padding: 8px 12px; border-radius: 12px; font-size: 13px; text-align: left; background: var(--chip); color: var(--on-chip); }
.now-status span { opacity: .8; }
.now-status.ready { background: var(--ok-bg); color: var(--ok-fg); }
.now-status.attention { background: var(--warn-bg); color: var(--warn-fg); }
.now-status.error { background: var(--err-bg); color: var(--err-fg); }
.now-ep { color: var(--sub); font-size: 14px; margin-top: 2px; }
.now-src { color: var(--mute); font-size: 13px; margin-top: 8px; word-break: break-all; }
.now-pick { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px; margin-top: 10px; }
.now-pick + .now-src { margin-top: 6px; font-size: 12px; }
.now-label { font-size: 12px; color: var(--mute); }
.now-srcname { font-size: 14px; font-weight: 700; color: var(--on-p); background: var(--p); padding: 3px 12px; border-radius: 12px; }
.now-meta { font-size: 13px; color: var(--sub); }
.chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
.chip { font-size: 13px; padding: 6px 12px; border-radius: 14px; background: var(--chip); color: var(--on-chip); }
.chip.failed, .chip.captcha, .chip.limited { background: var(--err-bg); color: var(--err-fg); }
.chip.loading { opacity: .6; }
.chip.on { background: var(--p); color: var(--on-p); opacity: 1; }
.chip.cur { background: var(--p-soft); color: var(--p); font-weight: 700; box-shadow: inset 0 0 0 2px var(--p); opacity: 1; }
.chip.on.cur { background: var(--p); color: var(--on-p); box-shadow: none; }
.list { display: flex; flex-direction: column; gap: 8px; }
.item { display: block; text-align: left; width: 100%; background: var(--card); color: var(--fg); border-radius: 12px; padding: 12px 14px; box-shadow: 0 1px 3px var(--shadow); position: relative; }
.item .t { display: block; font-size: 14px; line-height: 1.4; word-break: break-all; }
.item .m { display: block; font-size: 12px; color: var(--mute); margin-top: 4px; }
.item.sel { outline: 2px solid var(--p); }
.item.sel .t { padding-right: 68px; }
.item .badge { position: absolute; top: 10px; right: 10px; font-size: 11px; color: var(--on-p); background: var(--p); padding: 2px 8px; border-radius: 10px; }
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
.ghost { background: var(--chip); color: var(--on-chip); font-weight: 600; padding: 13px 12px; border-radius: 14px; font-size: 15px; }
.progress { margin-top: 14px; }
.track { height: 4px; border-radius: 2px; background: var(--chip); overflow: hidden; }
.track > div { height: 100%; width: 0; background: var(--p); }
.time { font-size: 12px; color: var(--mute); margin-top: 6px; text-align: right; font-variant-numeric: tabular-nums; }
#pb-range { display: block; width: 100%; margin: 0; accent-color: var(--p); }
.pb-time-link { color: var(--p); cursor: pointer; }
.pb-jump { display: flex; gap: 8px; margin-top: 8px; }
.pb-jump[hidden] { display: none; }
.pb-jump input { flex: 1; min-width: 0; }
.pb-jump button { flex: none; background: var(--p); color: var(--on-p); font-weight: 600; padding: 10px 16px; border-radius: 12px; }
.ctrls { display: flex; gap: 10px; margin-top: 10px; }
.ctrls button { flex: 1; background: var(--chip); color: var(--on-chip); font-weight: 600; padding: 12px 8px; border-radius: 12px; font-size: 15px; }
.ctrls button.main { background: var(--p); color: var(--on-p); }
.filters { display: flex; gap: 8px; margin-top: 12px; }
.filters .sel { flex: 1; min-width: 0; }
.filters .sel span { display: block; font-size: 12px; color: var(--mute); margin-bottom: 4px; }
.filters select { width: 100%; font: inherit; font-size: 14px; padding: 8px 6px; border: 1px solid var(--outline); border-radius: 10px; background: var(--field); color: var(--fg); }
.toggle { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--sub); margin-top: 10px; }
.item.ex { opacity: .72; }
.item.blocked { opacity: .45; }
.item .why { display: block; font-size: 12px; color: var(--err); margin-top: 4px; }
.episode { display: block; margin-top: 12px; }
.episode span { display: block; font-size: 12px; color: var(--mute); margin-bottom: 4px; }
.episode select { width: 100%; font: inherit; font-size: 15px; padding: 10px; border: 1px solid var(--outline); border-radius: 12px; background: var(--field); color: var(--fg); }
.qbox { position: relative; }
.sugg { position: absolute; left: 0; right: 0; top: calc(100% + 4px); background: var(--raised); border-radius: 12px; box-shadow: 0 6px 20px var(--shadow-lg); overflow: hidden; z-index: 20; }
/* 下拉行用专用类名: 查询请求表单的 .row > * { flex: 1 } 会把行里两个按钮拉成等宽, × 跑到中间 */
.sugg .srow { display: flex; align-items: center; }
.sugg .srow + .srow { border-top: 1px solid var(--line); }
.sugg .h { flex: 1; min-width: 0; text-align: left; background: none; padding: 12px 14px; font-size: 15px; color: var(--fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sugg .h::before { content: "◷"; color: var(--mute); margin-right: 10px; }
.sugg .x { flex: none; background: none; color: var(--mute); padding: 12px 16px; font-size: 18px; line-height: 1; }
.toggles { display: flex; flex-wrap: wrap; gap: 0 18px; }
.src-item { background: var(--card); border-radius: 12px; padding: 12px 14px; box-shadow: 0 1px 3px var(--shadow); margin-top: 10px; }
.src-item.off .src-name { color: var(--mute); }
.src-top { display: flex; align-items: center; gap: 10px; }
.src-sw input { width: 20px; height: 20px; }
.src-name { flex: 1; min-width: 0; font-size: 15px; font-weight: 600; word-break: break-all; }
.src-name small { display: block; font-weight: 400; font-size: 12px; color: var(--mute); margin-top: 2px; }
.src-desc { font-size: 12px; color: var(--mute); margin-top: 6px; word-break: break-all; }
.src-btns { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.src-btns button { background: var(--chip); color: var(--on-chip); padding: 6px 12px; border-radius: 10px; font-size: 13px; }
.src-btns button:disabled { opacity: .4; }
.src-btns .src-danger { background: var(--err-bg); color: var(--err-fg); }
.src-panel { margin-top: 10px; }
.src-panel textarea, #src-add textarea, #src-import textarea { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.f select { width: 100%; font: inherit; font-size: 15px; padding: 10px; border: 1px solid var(--outline); border-radius: 12px; background: var(--field); color: var(--fg); }
.src-bool { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.je-tabs { display: flex; gap: 6px; align-items: center; margin: 4px 0 6px; }
.je-tab, .je-fmt { background: var(--chip); color: var(--on-chip); padding: 6px 14px; border-radius: 14px; font-size: 13px; }
.je-tab.on { background: var(--p); color: var(--on-p); }
.je-fmt { margin-left: auto; }
.je-group { border: 1px solid var(--line2); border-radius: 12px; margin-top: 10px; background: var(--soft2); }
.je-group > summary { padding: 10px 12px; font-weight: 600; font-size: 14px; cursor: pointer; }
.je-group code, .je-f code, .je-bool code { font-size: 11px; font-weight: 400; color: var(--mute); margin-left: 6px; }
.je-body { padding: 0 12px 12px; }
.je-f { display: block; margin-top: 10px; }
.je-f > span { display: block; font-size: 13px; color: var(--sub); margin-bottom: 4px; }
.je-f input[type=text], .je-f textarea { font-family: ui-monospace, Menlo, monospace; font-size: 13px; padding: 10px 12px; }
.je-bool { display: flex; align-items: center; gap: 8px; margin-top: 10px; font-size: 14px; }
.je-bool input { width: 20px; height: 20px; flex: none; }
.seg { display: flex; background: var(--chip); border-radius: 12px; padding: 3px; margin: 2px 0 12px; }
.seg button { flex: 1; background: none; padding: 8px 6px; border-radius: 10px; font-size: 14px; color: var(--sub); }
.seg button.on { background: var(--seg-on); color: var(--p); font-weight: 700; box-shadow: 0 1px 2px var(--shadow-sm); }
.seg small { font-size: 12px; font-weight: 400; margin-left: 2px; }
#res-head { margin: 4px 0 10px; }
.res-q { font-size: 17px; font-weight: 700; word-break: break-all; }
.res-sub { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; font-size: 12px; color: var(--sub); margin-top: 4px; }
.res-sub span:first-child { min-width: 0; word-break: break-all; }
.res-tip { flex: none; color: var(--mute); }
.res-item { padding-right: 64px; cursor: pointer; }
.res-item .t { font-size: 15px; font-weight: 600; }
.res-rate { display: block; font-size: 12px; color: var(--sub); margin-top: 3px; }
.res-play { position: absolute; right: 10px; top: 50%; transform: translateY(-50%); width: 42px; height: 42px; border-radius: 21px; background: var(--chip); color: var(--p); display: flex; align-items: center; justify-content: center; font-size: 12px; }
.res-item.busy { opacity: .55; }
.res-r18 { display: inline-block; font-size: 10px; font-weight: 700; background: var(--err-bg); color: var(--err-fg); border-radius: 6px; padding: 1px 5px; margin-left: 6px; vertical-align: 2px; }
#res-foot { margin-top: 12px; }
.res-end { text-align: center; }
.sub-card { margin-top: 10px; }
.sub-head { display: flex; align-items: center; gap: 8px; }
.sub-head b { font-size: 15px; }
.sub-head small { flex: 1; font-size: 12px; color: var(--mute); }
.sub-refresh, .sub-del { flex: none; background: var(--chip); color: var(--on-chip); padding: 6px 12px; border-radius: 10px; font-size: 13px; }
.sub-refresh:disabled { opacity: .5; }
.sub-item { border-top: 1px solid var(--line); margin-top: 10px; padding-top: 10px; }
.sub-url { font-size: 13px; word-break: break-all; }
.sub-status { font-size: 12px; color: var(--sub); margin-top: 4px; }
.sub-status.bad { color: var(--err); }
.sub-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; color: var(--mute); margin-top: 6px; }
.sub-del { background: var(--err-bg); color: var(--err-fg); }
.sub-add { display: flex; gap: 8px; margin-top: 12px; }
.sub-add input { flex: 1; min-width: 0; }
.sub-add button { flex: none; padding: 12px 16px; }
.set-card { margin-top: 12px; }
.set-title { font-size: 15px; font-weight: 700; margin-bottom: 10px; }
.set-title small { font-size: 12px; font-weight: 400; color: var(--mute); margin-left: 6px; }
.log-list { display: flex; flex-direction: column; gap: 8px; margin: 10px 0 4px; }
.log-item { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 12px 14px; border-radius: 12px; background: var(--soft); text-decoration: none; }
.log-name { min-width: 0; font-size: 15px; font-weight: 600; color: var(--p); word-break: break-all; }
.log-meta { flex: none; font-size: 12px; color: var(--mute); }
.set-probe { display: flex; justify-content: space-between; font-size: 13px; padding: 7px 0; border-top: 1px solid var(--line); }
.set-probe .ok { color: var(--ok); }
.set-probe .bad { color: var(--err); }
.df-item { display: flex; align-items: center; gap: 10px; border-top: 1px solid var(--line); padding: 8px 0; }
.df-item code { flex: 1; min-width: 0; word-break: break-all; font-size: 13px; }
.cm-warn { color: var(--err); }
/* 评论与评分 (REVIEW_SCRIPT): 收藏 / 我的评分 / 本集评论三段, 细线隔开. 带 #cm-body 前缀是为了压过 .req form 的左右内边距
   (旧版就是被它多缩进了一截, 表单和「收藏状态」对不齐) */
#cm-body .cm-sec { border-top: 1px solid var(--line); padding: 12px 0 0; margin-top: 14px; }
#cm-body .cm-sec:first-child { border-top: 0; padding-top: 0; margin-top: 2px; }
.cm-h { display: flex; align-items: center; justify-content: space-between; min-height: 28px; margin-bottom: 6px; font-size: 13px; font-weight: 600; color: var(--sub); }
.cm-h small { font-weight: 400; color: var(--mute); margin-left: 6px; }
.cm-link { background: none; color: var(--mute); font-size: 13px; padding: 4px 2px; }
.cm-link[hidden] { display: none; }
.cm-types { margin: 0; }
.cm-score { display: flex; align-items: baseline; justify-content: center; gap: 8px; color: var(--fg); }
.cm-score b { font-size: 34px; line-height: 42px; font-weight: 700; font-variant-numeric: tabular-nums; }
.cm-score span { font-size: 15px; font-weight: 600; }
.cm-score.none { color: var(--mute); }
.cm-score.none span { font-size: 13px; font-weight: 400; }
.cm-score.warn { color: var(--err); }
.cm-stars { display: flex; justify-content: center; gap: 2px; padding: 4px 0 12px; touch-action: pan-y; user-select: none; -webkit-user-select: none; cursor: pointer; }
.cm-stars:focus-visible { outline: 2px solid var(--p); outline-offset: 2px; border-radius: 8px; }
.cm-stars svg { flex: 1 1 0; min-width: 0; max-width: 32px; aspect-ratio: 1 / 1; fill: var(--p); pointer-events: none; }
.cm-stars .f, .cm-stars svg.on .o { display: none; }
.cm-stars svg.on .f { display: inline; }
.cm-stars .o { opacity: .5; }
.cm-sec.off .cm-score { display: none; }
.cm-sec.off .cm-stars { opacity: .35; pointer-events: none; }
.cm-tip { text-align: center; margin: -4px 0 10px; }
.cm-sec textarea:disabled { background: var(--disabled); color: var(--mute); }
.cm-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; }
.cm-foot .toggle { margin: 0; font-size: 14px; }
.cm-foot .primary { flex: none; padding: 10px 24px; font-size: 15px; }
.cm-foot .primary:disabled { opacity: .45; }
.cm-emo { flex: none; display: inline-flex; align-items: center; gap: 6px; background: var(--chip); color: var(--on-chip); padding: 8px 14px 8px 10px; border-radius: 18px; font-size: 14px; }
.cm-emo svg { width: 20px; height: 20px; fill: currentColor; }
.cm-emo.on { background: var(--p); color: var(--on-p); }
.cm-preview { margin-top: 8px; padding: 8px 12px; border-radius: 10px; background: var(--soft); font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; }
.cm-preview[hidden], .cm-picker[hidden] { display: none; }
.cm-preview small { display: block; font-size: 12px; color: var(--mute); }
.cm-preview img { max-height: 2em; vertical-align: middle; }
.cm-picker { margin-top: 10px; border: 1px solid var(--line2); border-radius: 12px; overflow: hidden; }
.cm-packs { position: relative; display: flex; gap: 6px; padding: 8px; overflow-x: auto; border-bottom: 1px solid var(--line); }
.cm-packs button { flex: none; background: var(--chip); color: var(--on-chip); padding: 5px 12px; border-radius: 14px; font-size: 13px; white-space: nowrap; }
.cm-packs button.on { background: var(--p); color: var(--on-p); }
.cm-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(44px, 1fr)); gap: 2px; padding: 6px; max-height: 236px; overflow-y: auto; }
.cm-grid button { height: 44px; background: none; padding: 6px; border-radius: 8px; display: flex; align-items: center; justify-content: center; }
.cm-grid button:active { background: var(--chip); }
.cm-grid img { width: 30px; height: 30px; object-fit: contain; }
.cm-pk-msg { text-align: center; margin: 16px 0; }
input[type=checkbox], input[type=radio] { accent-color: var(--p); }
#cm-body form { margin-top: 4px; }
#dm-send { margin-top: 4px; }
#player-stats { padding: 0 16px 14px; }
.stats-row { display: flex; gap: 10px; font-family: ui-monospace, Menlo, monospace; font-size: 12px; padding: 3px 0; }
.stats-row span { flex: none; min-width: 5.5em; color: var(--mute); }
.stats-row b { font-weight: 400; word-break: break-all; }
.dm-wrap { padding: 0 16px 14px; }
.dm-src { border-top: 1px solid var(--line); padding: 10px 0; }
.dm-src.off .dm-name { color: var(--mute); }
.dm-top { display: flex; align-items: center; gap: 10px; }
.dm-name { flex: 1; min-width: 0; font-size: 14px; font-weight: 600; }
.dm-name small { font-weight: 400; color: var(--mute); margin-left: 6px; }
.dm-how { font-size: 12px; color: var(--sub); margin-top: 4px; word-break: break-all; }
.dm-shift { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 8px; font-size: 12px; color: var(--mute); }
.dm-shift button { background: var(--chip); color: var(--on-chip); padding: 5px 10px; border-radius: 9px; font-size: 12px; }
.dm-shift b { min-width: 4.8em; text-align: center; color: var(--fg); font-weight: 600; font-variant-numeric: tabular-nums; }
.dm-form { display: flex; gap: 8px; margin-top: 12px; }
.dm-form input { flex: 1; min-width: 0; }
.dm-form button { flex: none; padding: 12px 16px; }
.dm-list { display: flex; flex-direction: column; gap: 6px; margin-top: 10px; max-height: 340px; overflow-y: auto; }
.dm-list button { text-align: left; background: var(--soft); color: var(--fg); padding: 10px 12px; border-radius: 10px; font-size: 14px; flex: none; }
.dm-list button.sug { outline: 2px solid var(--p); }
#toast { position: fixed; left: 50%; bottom: calc(132px + env(safe-area-inset-bottom)); transform: translateX(-50%); background: var(--toast-bg); color: var(--toast-fg); padding: 10px 16px; border-radius: 20px; font-size: 14px; opacity: 0; transition: opacity .2s; pointer-events: none; max-width: 86%; text-align: center; }
#toast.on { opacity: 1; }
#toast { z-index: 60; }
.cache-entry { margin-top: 10px; }
.sheet { position: fixed; inset: 0; z-index: 50; display: flex; flex-direction: column; background: var(--bg); }
.sheet[hidden] { display: none; }
.sheet-head { display: flex; align-items: center; gap: 8px; padding: 12px 12px 10px; border-bottom: 1px solid var(--line2); }
.sheet-title { flex: 1; min-width: 0; font-size: 16px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sheet-btn { flex: none; width: 38px; height: 38px; border-radius: 19px; background: var(--chip); color: var(--on-chip); font-size: 18px; }
.sheet-btn[hidden] { display: none; }
.sheet-body { flex: 1; overflow-y: auto; padding: 8px 16px 24px; }
.cache-ep { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.cache-ep .n { flex: 1; min-width: 0; font-size: 14px; word-break: break-all; }
.cache-ep .st { font-size: 12px; color: var(--mute); margin-top: 2px; }
.cache-ep .st.ok { color: var(--ok); }
.cache-ep .st.run { color: var(--p); }
.cache-ep .st.bad { color: var(--err); }
.cache-ep > button { flex: none; background: var(--chip); color: var(--on-chip); padding: 7px 12px; border-radius: 10px; font-size: 13px; }
.cache-bar { position: sticky; bottom: -24px; background: var(--bg); padding: 12px 0 24px; }
.cache-bar button:disabled { opacity: .5; }
.tv-state { margin: 6px 16px 0; padding: 8px 12px; border-radius: 12px; font-size: 13px; line-height: 1.45; background: var(--warn-bg); color: var(--warn-fg); }
.tv-state.off { background: var(--err-bg); color: var(--err-fg); }
.acct { display: flex; align-items: center; gap: 12px; }
.acct img, .acct-ph { flex: none; width: 48px; height: 48px; border-radius: 24px; object-fit: cover; background: var(--chip); }
.acct-ph { display: flex; align-items: center; justify-content: center; color: var(--p); font-size: 20px; font-weight: 700; }
.acct-name { font-size: 16px; font-weight: 700; word-break: break-all; }
.acct-sub { font-size: 12px; color: var(--mute); margin-top: 2px; }
.acct-wait { margin-top: 12px; }
.acct-wait .now-status { margin-top: 0; }
.acct-wait a { color: var(--p); }
#cc-chips .chips { margin-top: 4px; }
.cache-ep > button.cache-pack { background: var(--p); color: var(--on-p); }
.cache-packall { margin: 8px 0 2px; }
.cl-free { font-size: 15px; }
.cl-free b { font-size: 20px; }
.cl-free small { font-size: 13px; color: var(--mute); }
.cl-line { font-size: 13px; color: var(--sub); margin-top: 6px; }
.cl-warn { font-size: 13px; color: var(--err); margin-top: 6px; }
.cl-group { margin-top: 12px; }
.cl-head { display: flex; align-items: center; gap: 8px; }
.cl-title { flex: 1; min-width: 0; font-size: 16px; font-weight: 700; word-break: break-all; }
.cl-delall { flex: none; background: var(--err-bg); color: var(--err-fg); padding: 6px 12px; border-radius: 10px; font-size: 13px; }
.cl-delall:disabled { opacity: .5; }
.cl-meta { font-size: 12px; color: var(--mute); margin: 2px 0 4px; }
.cl-ep { display: flex; align-items: center; gap: 8px; padding: 10px 0; border-top: 1px solid var(--line); }
.cl-ep .n { flex: 1; min-width: 0; font-size: 14px; word-break: break-all; }
.cl-st { font-size: 12px; color: var(--mute); margin-top: 3px; }
.cl-st b { font-weight: 600; color: var(--p); }
.cl-st b.ok { color: var(--ok); }
.cl-st b.bad { color: var(--err); }
.cl-st b.pause { color: var(--warn-fg); }
.cl-bar { height: 3px; border-radius: 2px; background: var(--chip); margin-top: 6px; overflow: hidden; }
.cl-bar > div { height: 100%; background: var(--p); }
.cl-ep > button { flex: none; background: var(--chip); color: var(--on-chip); padding: 7px 10px; border-radius: 10px; font-size: 13px; }
.cl-ep > button.cl-del { background: var(--err-bg); color: var(--err-fg); }
.cl-ep > button:disabled { opacity: .5; }
.cl-tag { display: inline-block; font-size: 11px; font-weight: 600; padding: 1px 6px; border-radius: 6px; background: var(--chip); color: var(--sub); margin-left: 6px; vertical-align: 1px; }
.cl-tag.play { background: var(--p); color: var(--on-p); }
.cl-more { margin-top: 8px; }
""".trimIndent()

/**
 * 深浅色: 手机上存 `remote.theme` = auto / light / dark, 默认 auto = 跟着手机系统的深色模式 (prefers-color-scheme,
 * 系统一切换当场跟着变). 放在 <head> 里、样式之前先把 data-theme 定下来, 深色下不会先闪一下白.
 * 设置标签的「外观」卡片 (LOOK_SCRIPT) 经 window.remoteTheme 读写; 存不进 localStorage (无痕模式) 时只在本页生效.
 */
private val THEME_HEAD_SCRIPT = """
(function () {
  var mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null, mem = null;
  function get() {
    if (mem) return mem;
    try { return localStorage.getItem('remote.theme') || 'auto'; } catch (e) { return 'auto'; }
  }
  function apply() {
    var t = get(), dark = t === 'dark' || (t === 'auto' && !!mq && mq.matches);
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
    var m = document.querySelector('meta[name="theme-color"]');
    if (m) m.setAttribute('content', dark ? '#141218' : '#fafafa');
  }
  function set(t) {
    mem = t;
    try { localStorage.setItem('remote.theme', t); } catch (e) {}
    apply();
  }
  apply();
  if (mq) {
    if (mq.addEventListener) mq.addEventListener('change', apply);
    else if (mq.addListener) mq.addListener(apply);
  }
  window.remoteTheme = { get: get, set: set };
})();
""".trimIndent()

/** 「设置」标签的「外观」卡片: 自动 (跟随手机) / 浅色 / 深色, 只存在这台手机上 (见 THEME_HEAD_SCRIPT). */
private val LOOK_SCRIPT = """
(function () {
  var box = document.getElementById('set-look');
  var OPTS = [['auto', '自动'], ['light', '浅色'], ['dark', '深色']];
  function paint() {
    var t = window.remoteTheme.get();
    box.innerHTML = '<div class="card set-card"><div class="set-title">外观<small>只影响这台手机</small></div>' +
      '<div class="seg">' + OPTS.map(function (o) {
        return '<button type="button" data-look="' + o[0] + '"' + (o[0] === t ? ' class="on"' : '') + '>' + o[1] + '</button>';
      }).join('') + '</div><p class="hint">自动：跟着手机系统的深色模式切换。</p></div>';
  }
  box.addEventListener('click', function (e) {
    var b = e.target.closest('[data-look]');
    if (!b) return;
    window.remoteTheme.set(b.getAttribute('data-look'));
    paint();
  });
  paint();
})();
""".trimIndent()

/**
 * 「设置」标签底部的「日志」(见 RemoteLogs): 电视上的日志文件, 点文件名直接下载到手机 —— 同电视上设置 → 日志 →
 * 「扫码传到手机」, 省得再去电视上开一次. 每次切到设置标签重读 (app.log 一直在长).
 */
private val LOGS_SCRIPT = """
(function () {
  var box = document.getElementById('set-logs');
  function load() {
    fetch('api/logs').then(function (r) { return r.ok ? r.json() : { supported: false }; }).then(render)
      .catch(function () { box.innerHTML = ''; });
  }
  window.loadLogs = load;
  function render(d) {
    if (!d.supported) { box.innerHTML = ''; return; }
    box.innerHTML = '<div class="card set-card"><div class="set-title">日志</div>' +
      '<p class="hint">遇到问题时下载下来发给开发者。app.log 是今天的，其余按天保存。</p>' +
      (d.items.length ? '<div class="log-list">' + d.items.map(function (x) {
        return '<a class="log-item" href="api/logs/' + encodeURIComponent(x.name) + '" download="' + esc(x.name) + '">' +
          '<span class="log-name">' + esc(x.name) + '</span><span class="log-meta">' + esc(x.size) + ' · ' + esc(x.time) + '</span></a>';
      }).join('') + '</div>' : '<p class="hint">还没有日志文件</p>') +
      '<p class="hint">点了没开始下载的话，换系统浏览器打开本页再试。</p></div>';
  }
})();
""".trimIndent()

private val SCRIPT = """
(function () {
  // sub: 搜索标签里当前是「搜索」(form) 还是「结果」(results) 那一页; setSub: 设置标签里是「常规」(general) 还是「数据源」(sources)
  var cur = null, sub = 'form', setSub = 'general', ver = '', busy = false;
  // 数据源胶囊的筛选 (null = 全部) 与最近一次完整状态: 点胶囊时就地重画, 不等下一次轮询
  var srcFilter = null, lastState = null;
  // login: 电视刚登录上 (账号卡片发现的), 评论与评分区据此重新读一次
  var hooks = { render: [], unavailable: [], playback: [], login: [] };
  window.remoteHooks = hooks;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  window.esc = esc;
  // 播放卡底部「缓存这部番的剧集…」: 点了打开缓存面板 (见 CACHE_SCRIPT)
  function cacheEntry(id, title) {
    return id ? '<button type="button" class="ghost wide cache-entry" data-cache="' + id + '" data-title="' + esc(title || '') +
      '">缓存这部番的剧集…</button>' : '';
  }
  // 后台会话进行到哪一步了 (服务端给 kind / label / text): 已就绪是绿色, 要处理的是黄色, 出错是红色
  function sessionChip(x) {
    if (!x) return '';
    return '<div class="now-status ' + x.kind + '"><b>' + (x.kind === 'ready' ? '✓ ' : '') + esc(x.label) + '</b>' +
      (x.text ? '<span>' + esc(x.text) + '</span>' : '') + '</div>';
  }
  function mmss(ms) {
    var t = Math.max(0, Math.floor(ms / 1000));
    var h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), sec = t % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') + m : String(m)) + ':' + (sec < 10 ? '0' : '') + sec;
  }
  function toast(msg, ms) {
    var t = document.getElementById('toast');
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(t.hideTimer);
    t.hideTimer = setTimeout(function () { t.classList.remove('on'); }, ms || 2400);
  }
  window.toast = toast;
  function post(path, data) {
    return fetch(path, { method: 'POST', body: new URLSearchParams(data) }).then(function (r) { return r.json(); });
  }
  window.post = post;
  function fail() { toast('发送失败，请确认手机与电视在同一网络'); }
  window.fail = fail;

  // 电视上后台播放的提示 (准备好了、出问题) 同步到手机: 不管停在哪个标签, 每 2 秒问一次有没有新的一条.
  // 第一次不带 after, 服务端只回当前序号当基线, 打开页面时不会弹旧提示.
  // 顺带管顶上的状态条: 电视上 Ani 不在前台 (屏保 / 别的应用), 或者连不上电视 —— 连续两次失败才算, 丢一个包不闪
  var noticeSeq = null, noticeFails = 0;
  var tvState = document.getElementById('tv-state');
  function setTvState(kind, text) {
    tvState.hidden = !kind;
    tvState.className = 'tv-state' + (kind ? ' ' + kind : '');
    tvState.textContent = text || '';
  }
  function pollNotice() {
    if (document.hidden) return;
    fetch('api/notice' + (noticeSeq == null ? '' : '?after=' + noticeSeq))
      .then(function (r) {
        // 地址失效 (电视上重置过地址): 服务端回的是纯文本 404
        if (r.status === 404) throw new Error('gone');
        return r.json();
      })
      .then(function (n) {
        noticeFails = 0;
        if (n.text) toast(n.text, 6000);
        noticeSeq = n.seq;
        if (n.away) setTvState('away', '电视上 Ani 不在前台（屏保或别的应用）：这里的操作照样生效，回到 Ani 就能看到');
        else setTvState('');
      })
      .catch(function (e) {
        if (e && e.message === 'gone') setTvState('off', '这个地址已失效（电视上重置过地址），请在电视上重新扫码');
        else if (++noticeFails >= 2) setTvState('off', '连不上电视：请确认电视开着、Ani 在运行，并且和手机在同一个网络');
      });
  }
  setInterval(pollNotice, 2000);
  pollNotice();

  function show(tab) {
    cur = tab;
    var secs = document.querySelectorAll('.tab');
    for (var i = 0; i < secs.length; i++) secs[i].hidden = secs[i].id !== 'tab-' + tab;
    var bs = document.querySelectorAll('.tabbar button');
    for (var j = 0; j < bs.length; j++) bs[j].classList.toggle('on', bs[j].getAttribute('data-tab') === tab);
    if (history.replaceState) history.replaceState(null, '', '#' + tab);
    if (tab === 'search') showSub(sub);
    if (tab === 'player') poll(true);
    if (tab === 'cache' && window.loadCaches) window.loadCaches();
    if (tab === 'settings') showSetSub(setSub);
    if (tab === 'settings' && window.loadLogs) window.loadLogs();
  }
  // 设置标签里的两页: 「常规」(账号 / 代理 / tracker / 屏蔽词) 与「数据源」(订阅 + 数据源管理), 切到哪页拉哪页
  function showSetSub(which) {
    setSub = which;
    document.getElementById('set-general').hidden = which !== 'general';
    document.getElementById('set-sources').hidden = which !== 'sources';
    var bs = document.querySelectorAll('#set-seg button');
    for (var i = 0; i < bs.length; i++) bs[i].classList.toggle('on', bs[i].getAttribute('data-ssub') === which);
    if (which === 'sources') {
      if (window.loadSources) window.loadSources();
      if (window.loadSubs) window.loadSubs();
    } else {
      if (window.loadSettings) window.loadSettings();
      if (window.loadAccount) window.loadAccount();
    }
  }
  // 搜索标签里的两页: 「搜索」表单 / 「结果」列表 (电视搜索页已加载的结果)
  function showSub(which) {
    sub = which;
    document.getElementById('search-pane').hidden = which !== 'form';
    document.getElementById('results-pane').hidden = which !== 'results';
    var bs = document.querySelectorAll('#search-seg button');
    for (var i = 0; i < bs.length; i++) bs[i].classList.toggle('on', bs[i].getAttribute('data-sub') === which);
    if (which === 'results') pollResults(true);
  }
  document.querySelector('.tabbar').addEventListener('click', function (e) {
    var b = e.target.closest('button');
    if (b) show(b.getAttribute('data-tab'));
  });
  document.getElementById('search-seg').addEventListener('click', function (e) {
    var b = e.target.closest('[data-sub]');
    if (b) showSub(b.getAttribute('data-sub'));
  });
  document.getElementById('set-seg').addEventListener('click', function (e) {
    var b = e.target.closest('[data-ssub]');
    if (b) showSetSub(b.getAttribute('data-ssub'));
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
        showSub('results');
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
    if (document.hidden || cur !== 'search' || sub !== 'results') return;
    if (resBusy) { resAgain = true; return; }
    resBusy = true;
    fetch('api/search/results?v=' + (force ? '' : encodeURIComponent(resVer)))
      .then(function (r) { return r.json(); })
      .then(function (s) {
        resBusy = false;
        if (!s.same) { resVer = s.v; renderResults(s); }
        if (resAgain) { resAgain = false; pollResults(true); }
      })
      .catch(function (e) { resBusy = false; console.error(e); });
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
    // 「结果」分段按钮上带条数, 在「搜索」页也看得到结果回来了没有
    document.getElementById('res-count').textContent =
      s.available && !s.pending && s.items && s.items.length ? ' ' + s.items.length : '';
    if (s.pending) { resEmpty('正在电视上搜索…'); return; }
    if (!s.available) {
      resEmpty('还没有搜索结果', '在「搜索」里搜一下，电视上的结果会列在这里：点条目在电视上打开详情页，点 ▶ 直接播放');
      return;
    }
    var items = s.items || [];
    // 操作提示常驻在条数那一行的右边: 一般是搜完直接跳过来的, 空状态里那句提示根本看不到
    resHead.innerHTML = '<div class="res-q">' + (s.keywords ? '「' + esc(s.keywords) + '」' : '筛选结果') + '</div>' +
      '<div class="res-sub"><span>' + (s.refreshing ? '搜索中' : items.length + ' 条') +
      (s.filters ? ' · ' + esc(s.filters) : '') + '</span><span class="res-tip">点条目看详情，点 ▶ 播放</span></div>';
    if (!items.length) {
      resList.innerHTML = '<div class="empty"><p>' + (s.refreshing ? '正在电视上搜索…'
        : s.error ? '搜索失败：' + esc(s.error) : '没有找到相关条目') + '</p></div>';
    } else {
      resList.innerHTML = items.map(function (x) {
        // 点主体 = 电视打开详情页; 点右边的 ▶ = 直接播放
        return '<div class="item res-item" data-sid="' + x.id + '">' +
          '<span class="t">' + esc(x.title) + (x.nsfw ? '<span class="res-r18">R18</span>' : '') + '</span>' +
          (x.info ? '<span class="m">' + esc(x.info) + '</span>' : '') +
          (x.rating ? '<span class="res-rate">' + esc(x.rating) + '</span>' : '') +
          '<button type="button" class="res-play" aria-label="播放">▶</button></div>';
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
    var play = !!e.target.closest('.res-play');
    b.classList.add('busy');
    post(play ? 'api/search/play' : 'api/search/open', { id: b.getAttribute('data-sid') })
      .then(function (r) {
        b.classList.remove('busy');
        toast(r.message);
        // 进了播放页就切到「播放器」, 数据源结果陆续回来可以直接在手机上挑; 打开详情页则留在结果里
        if (r.player) setTimeout(function () { show('player'); }, 1200);
      })
      .catch(function () { b.classList.remove('busy'); fail(); });
  });

  // ---- 播放器 ----
  // 下拉筛选 (空 = 全部) 与「显示被排除的」: 随轮询发给服务端过滤 (每源 40 条的截断在筛选之后做)
  var fRes = '', fSub = '', fAll = '', fEx = false, fFull = false;
  // 轮询进行中又要求刷新 (比如刚切了筛选): 记一笔, 这次返回后立刻补拉, 否则要等到下一个周期
  var again = false;
  // 「播放信息」展开着才让电视采集 (见 CONTROL_SCRIPT 里的渲染)
  function statsOpen() {
    var d = document.getElementById('stats-box');
    return !!(d && d.open && !d.hidden);
  }
  function query() {
    return '&res=' + encodeURIComponent(fRes) + '&sub=' + encodeURIComponent(fSub) +
      '&all=' + encodeURIComponent(fAll) + '&ex=' + (fEx ? '1' : '') +
      '&full=' + (fFull && srcFilter ? encodeURIComponent(srcFilter) : '') +
      '&stats=' + (statsOpen() ? '1' : '');
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
      // 打出来: 渲染里的异常也落在这个 catch 里, 不打的话界面画一半就停、控制台一行字都没有
      .catch(function (e) { busy = false; console.error(e); });
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
        now.innerHTML = '<div class="card"><div class="now-title now-link" data-subject="' + u.subjectId +
          '" data-title="' + esc(u.title) + '">' + esc(u.title) + '</div>' +
          '<div class="now-src">' + (u.continuing ? '继续播放' : '接下来播放') + (u.episode ? '：' + esc(u.episode) : '') + '</div>' +
          (u.continuing && u.duration
            ? '<div class="progress"><div class="track"><div style="width:' + Math.min(100, u.position * 100 / u.duration) +
              '%"></div></div><div class="time">' + mmss(u.position) + ' / ' + mmss(u.duration) + '</div></div>'
            : '') +
          '<div class="ctrls"><button class="main" id="play-upnext">在电视上播放</button></div>' + cacheEntry(u.subjectId, u.title) + '</div>';
      } else {
        now.innerHTML = '<div class="empty"><p>' + msg + '</p>' + sessionChip(s.session) +
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
    // 正在播哪个数据源放在剧名下、播放键上方: 候选列表里的「正在播放」角标要往下翻很远才看得到
    now.innerHTML = '<div class="card"><div class="now-title now-link" data-subject="' + s.subjectId +
      '" data-title="' + esc(s.title) + '">' + esc(s.title) + '</div>' +
      '<div class="now-pick"><span class="now-label">' + (s.background ? '当前数据源' : '正在播放') + '</span>' +
      (s.selectedSource
        ? '<span class="now-srcname">' + esc(s.selectedSource) + '</span>' +
          (s.selectedMeta ? '<span class="now-meta">' + esc(s.selectedMeta) + '</span>' : '')
        : '<span class="now-meta">尚未选择数据源</span>') + '</div>' +
      (s.selectedTitle ? '<div class="now-src">' + esc(s.selectedTitle) + '</div>' : '') +
      (s.background
        ? sessionChip(s.session) + '<p class="hint">电视未在播放页：可以照常换源和修改查询条件，新数据源会在后台加载，回到播放器即可继续播放。</p>' +
          '<button class="primary wide" id="open-player">在电视上打开播放器</button>'
        : '<div id="player-controls"></div>') +
      cacheEntry(s.subjectId, s.title) + '</div>';
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
      // cur = 正在播的就是这个源 (描边 + ▶), 与筛选选中的 on (实心) 是两回事, 可以同时成立
      var cur = x.id === s.selectedSourceId;
      h += '<button class="chip ' + x.state + (srcFilter === x.id ? ' on' : '') + (cur ? ' cur' : '') + '" data-src="' + esc(x.id) + '">' +
        (cur ? '▶ ' : '') + esc(x.name) + ' ' + n + '</button>';
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
    // 点剧名 = 电视打开这部的详情页 (在播放页时叠在播放器上, 按返回回来)
    var link = e.target.closest('[data-subject]');
    if (link) {
      post('api/player/details', { id: link.getAttribute('data-subject'), title: link.getAttribute('data-title') })
        .then(function (r) { toast(r.message); })
        .catch(fail);
      return;
    }
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
  // 旧书签 #sources: 数据源并进了「设置」, 打开设置标签的「数据源」页
  if (h0 === 'sources') { setSub = 'sources'; h0 = 'settings'; }
  // 等本页其余脚本 (缓存 / 数据源 / 设置等) 都跑完再切标签: 它们的加载函数 (window.loadCaches 等) 是后面才登记的,
  // 直接切的话从 #cache / #settings 书签打开时那一页是空的
  setTimeout(function () {
    show(h0 === 'search' || h0 === 'player' || h0 === 'cache' || h0 === 'settings' ? h0 : INITIAL_TAB);
  }, 0);
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
  // 拖进度条期间不让轮询把滑块拽回去; 松手才发一次跳转
  var dragging = false;
  function paint() {
    var box = document.getElementById('player-controls');
    if (!box) return;
    if (!box.firstChild) {
      box.innerHTML =
        '<div class="progress"><input type="range" id="pb-range" min="0" max="0" step="1000" value="0" aria-label="播放进度">' +
        '<div class="time pb-time-link" id="pb-time"></div></div>' +
        '<form class="pb-jump" id="pb-jump" hidden><input type="text" name="t" inputmode="decimal" autocomplete="off" ' +
        'placeholder="跳到哪里？如 21:30、21.30、1:05:10"><button type="submit">跳转</button></form>' +
        '<div class="ctrls"><button data-act="back">« 10 秒</button>' +
        '<button data-act="toggle" class="main" id="pb-toggle"></button>' +
        '<button data-act="forward">10 秒 »</button></div>';
    }
    var p = pb || { playing: false, position: 0, duration: 0 };
    document.getElementById('pb-toggle').textContent = p.playing ? '暂停' : '播放';
    var range = document.getElementById('pb-range');
    range.disabled = !p.duration;
    if (!dragging) {
      range.max = String(p.duration || 0);
      range.value = String(p.position || 0);
      document.getElementById('pb-time').textContent = fmt(p.position) + ' / ' + (p.duration ? fmt(p.duration) : '--:--');
    }
  }
  hooks.render.push(function () { paint(); });
  hooks.playback.push(function (p) { pb = p; paint(); });

  function seek(ms) {
    if (pb) pb.position = ms;
    paint();
    post('api/player/control', { action: 'seek', ms: String(Math.round(ms)) })
      .then(function (r) { if (r.message) toast(r.message); poll(true); })
      .catch(fail);
  }
  // 21:30 / 21.30 / 1:05:10 (有的数字键盘没有冒号, 点号空格也认); 只有一个数 = 分钟
  function parseTime(text) {
    var parts = String(text).trim().split(/[:：.\s]+/).filter(function (x) { return x !== ''; });
    if (!parts.length || parts.length > 3) return null;
    var nums = parts.map(Number);
    if (nums.some(function (n) { return isNaN(n) || n < 0; })) return null;
    var sec = nums.length === 1 ? nums[0] * 60 : nums.reduce(function (a, n) { return a * 60 + n; }, 0);
    return Math.round(sec * 1000);
  }
  var nowBox = document.getElementById('player-now');
  nowBox.addEventListener('input', function (e) {
    if (e.target.id !== 'pb-range') return;
    dragging = true;
    var d = pb && pb.duration;
    document.getElementById('pb-time').textContent = '跳到 ' + fmt(+e.target.value) + ' / ' + (d ? fmt(d) : '--:--');
  });
  nowBox.addEventListener('change', function (e) {
    if (e.target.id !== 'pb-range') return;
    dragging = false;
    seek(+e.target.value);
  });
  // 点时间文字 = 展开 / 收起「输入时间点」那一行
  nowBox.addEventListener('click', function (e) {
    if (e.target.id !== 'pb-time') return;
    var f = document.getElementById('pb-jump');
    f.hidden = !f.hidden;
    if (!f.hidden) { f.elements.t.value = ''; f.elements.t.focus(); }
  });
  nowBox.addEventListener('submit', function (e) {
    if (e.target.id !== 'pb-jump') return;
    e.preventDefault();
    var ms = parseTime(e.target.elements.t.value);
    if (ms == null) { toast('时间格式不对，例如 21:30 或 1:05:10'); return; }
    if (pb && pb.duration && ms > pb.duration) { toast('超过片长了（' + fmt(pb.duration) + '）'); return; }
    e.target.elements.t.blur();
    e.target.hidden = true;
    seek(ms);
  });

  // 「播放信息」: 可收起的一区, 展开时轮询带 stats=1, 电视才开始每秒采一次, 收起即停. 展开与否记在这台手机上
  var statsBox = document.getElementById('stats-box');
  var statsBody = document.getElementById('player-stats');
  try { statsBox.open = localStorage.getItem('remote.stats') === '1'; } catch (e) {}
  statsBox.addEventListener('toggle', function () {
    try { localStorage.setItem('remote.stats', statsBox.open ? '1' : ''); } catch (e) {}
    if (statsBox.open) { statsBody.innerHTML = '<p class="hint">正在读取…</p>'; poll(true); }
  });
  hooks.playback.push(function (p) {
    if (!statsBox.open || !p.stats) return;
    statsBody.innerHTML = p.stats.length ? p.stats.map(function (r) {
      return '<div class="stats-row"><span>' + esc(r.k) + '</span><b>' + esc(r.v) + '</b></div>';
    }).join('') : '<p class="hint">正在读取…</p>';
  });
  // 没有播放器 (只有「接下来播放」卡或什么都没有) 时不出这一区
  hooks.unavailable.push(function () { statsBox.hidden = true; });
  hooks.render.push(function () { statsBox.hidden = false; });
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
 * 设置标签「数据源」页的脚本 (见 RemoteSources). 列表在切到这一页与每次操作成功后整份重拉; 新增区只在可选类型变了时才
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
    // 「导入 JSON」作为下拉里的第一项: 原先是单独一个折叠框, 没有说明, 不容易看出是干什么的
    addBox.innerHTML =
      '<label class="f"><span>新增数据源</span><select id="src-new"><option value="">选择类型…</option>' +
      '<option value="import">导入 JSON（粘贴别处复制的配置）</option>' +
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
    var panel = document.getElementById('src-new-panel');
    if (e.target.value === 'import') {
      // id 沿用 src-import: 「粘贴即覆盖」认这个表单
      panel.innerHTML = '<form class="src-new-form" id="src-import" data-kind="import">' +
        '<p class="hint">粘贴别处复制的数据源配置（JSON）：单个、列表或订阅内容都可以，每个都新建为本地源。粘贴会整段替换框里原有的内容。</p>' +
        '<textarea name="text" rows="8" spellcheck="false" placeholder="在这里粘贴 JSON"></textarea>' +
        '<div class="row"><button type="button" class="ghost" data-act="cancel-new">取消</button>' +
        '<button type="button" class="ghost" data-imp="clear">清空</button><button type="submit" class="primary">导入</button></div></form>';
      return;
    }
    var t = data.templates[+e.target.value];
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
    if (e.target.closest('[data-act="cancel-new"]')) { clearAdd(); return; }
    var c = e.target.closest('[data-imp="clear"]');
    if (c) {
      var ta = c.form.elements.text;
      ta.value = '';
      ta.focus();
    }
  });
  addBox.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form.classList.contains('src-new-form')) return;
    e.preventDefault();
    if (form.getAttribute('data-kind') === 'import') {
      post('api/sources/import', new FormData(form)).then(function (r) {
        toast(r.message);
        if (r.ok) { clearAdd(); lastTemplates = ''; load(); }
      }).catch(fail);
      return;
    }
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

  // ---- 粘贴即覆盖 ----
  // 局域网页面是 http, 浏览器只在安全上下文 (https / localhost) 给网页主动读剪贴板的接口, 「点按钮读剪贴板」做不到;
  // 但用户自己粘贴时, 浏览器会把粘贴的内容交给 paste 事件. 所以改成: 导入框里任何一次粘贴都整段替换原有内容;
  // JSON 源码框里粘贴的是一整段 JSON 时整段替换, 只粘一小段 (比如一个网址) 仍照常插在光标处
  document.addEventListener('paste', function (e) {
    var ta = e.target;
    var whole = ta.tagName === 'TEXTAREA' && ta.name === 'text' && ta.form && ta.form.id === 'src-import';
    var raw = ta.classList && ta.classList.contains('je-raw');
    if (!whole && !raw) return;
    var text = e.clipboardData ? e.clipboardData.getData('text') : '';
    if (!text) return;
    var t = text.trim(), a = t.charAt(0), z = t.charAt(t.length - 1);
    if (raw && !((a === '{' && z === '}') || (a === '[' && z === ']'))) return;
    e.preventDefault();
    ta.value = text;
    toast('已用粘贴的内容替换原有内容');
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

/**
 * 「数据源」页顶部的订阅块 (见 RemoteSubscriptions): 列表 / 添加 / 立即更新 / 删除. 更新在电视后台跑, 服务端报
 * `updating` 时每 2 秒重拉一次, 更新完顺带刷新下面的数据源列表 (订阅会增删源). 重画时保住正在输入的地址.
 */
private val SUBS_SCRIPT = """
(function () {
  var box = document.getElementById('src-subs');
  var timer = null, wasUpdating = false;
  function load() {
    fetch('api/sources/subs').then(function (r) { return r.json(); }).then(render).catch(function () {});
  }
  window.loadSubs = load;
  function render(d) {
    var items = d.items || [];
    var h = '<div class="card sub-card"><div class="sub-head"><b>订阅</b><small>在线数据源都来自订阅</small>' +
      '<button type="button" class="sub-refresh" data-sub="refresh"' + (d.updating ? ' disabled' : '') + '>' +
      (d.updating ? '更新中…' : '立即更新') + '</button></div>';
    if (!items.length) h += '<p class="hint">还没有订阅，把订阅地址粘贴到下面添加</p>';
    items.forEach(function (s) {
      h += '<div class="sub-item"><div class="sub-url">' + esc(s.url) + '</div>' +
        '<div class="sub-status' + (s.failed ? ' bad' : '') + '">' + esc(s.status) + '</div>' +
        '<div class="sub-meta"><span>' + esc(s.period) + '</span>' +
        '<button type="button" class="sub-del" data-sub="delete" data-id="' + esc(s.id) + '">删除</button></div></div>';
    });
    h += '<form class="sub-add"><input type="text" name="url" inputmode="url" autocomplete="off" spellcheck="false" ' +
      'placeholder="粘贴订阅地址 https://…"><button type="submit" class="primary">添加</button></form></div>';
    var old = box.querySelector('.sub-add input');
    var typed = old ? old.value : '', focused = old && document.activeElement === old;
    box.innerHTML = h;
    var input = box.querySelector('.sub-add input');
    input.value = typed;
    if (focused) input.focus();
    clearTimeout(timer);
    if (d.updating) timer = setTimeout(load, 2000);
    else if (wasUpdating && window.loadSources) window.loadSources();
    wasUpdating = !!d.updating;
  }
  box.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form.classList.contains('sub-add')) return;
    e.preventDefault();
    post('api/sources/subs/add', { url: form.elements.url.value }).then(function (r) {
      toast(r.message);
      if (r.ok) { form.elements.url.value = ''; load(); }
    }).catch(fail);
  });
  box.addEventListener('click', function (e) {
    var b = e.target.closest('[data-sub]');
    if (!b) return;
    if (b.getAttribute('data-sub') === 'refresh') {
      post('api/sources/subs/refresh', {}).then(function (r) { toast(r.message); load(); }).catch(fail);
    } else if (confirm('删除这个订阅？它带来的数据源会一起删除。')) {
      post('api/sources/subs/delete', { id: b.getAttribute('data-id') }).then(function (r) {
        toast(r.message);
        if (r.ok) { load(); if (window.loadSources) window.loadSources(); }
      }).catch(fail);
    }
  });
})();
""".trimIndent()

/**
 * 「设置」标签 (见 RemoteSettings): 代理 (模式 / 地址 / 账号, 保存与测试连接) 与 BT 额外 tracker. 只在切到本标签时
 * 拉一次, 不轮询 —— 表单正在填, 重画会冲掉. 密码框不回显, 留空 = 不改.
 */
private val SETTINGS_SCRIPT = """
(function () {
  var proxyBox = document.getElementById('set-proxy');
  var trBox = document.getElementById('set-trackers');
  var dfBox = document.getElementById('set-dmfilter');
  var MODES = [['DISABLED', '不使用'], ['SYSTEM', '跟随系统'], ['CUSTOM', '自定义']];
  function load() {
    fetch('api/settings').then(function (r) { return r.json(); }).then(render).catch(fail);
  }
  window.loadSettings = load;
  function render(d) {
    var p = d.proxy || {};
    proxyBox.innerHTML = '<form class="card set-card"><div class="set-title">代理</div><div class="pills">' +
      MODES.map(function (m) {
        return '<label><input type="radio" name="mode" value="' + m[0] + '"' + (p.mode === m[0] ? ' checked' : '') + '><span>' + m[1] + '</span></label>';
      }).join('') + '</div>' +
      '<div class="set-custom"' + (p.mode === 'CUSTOM' ? '' : ' hidden') + '>' +
      '<label class="f"><span>代理地址</span><input type="text" name="url" inputmode="url" autocomplete="off" spellcheck="false" value="' +
      esc(p.url) + '" placeholder="http://192.168.1.2:7890"><em>支持 http:// 与 socks5://</em></label>' +
      '<label class="f"><span>用户名（可选）</span><input type="text" name="username" autocomplete="off" value="' + esc(p.username) + '"></label>' +
      '<label class="f"><span>密码（可选）</span><input type="password" name="password" autocomplete="new-password" placeholder="' +
      (p.hasPassword ? '已设置，留空则不改' : '') + '"></label></div>' +
      '<p class="hint set-sys"' + (p.mode === 'SYSTEM' ? '' : ' hidden') + '>电视上通常取不到系统代理，这一档一般等于不使用代理；要走代理请选「自定义」。</p>' +
      '<div class="row"><button type="button" class="ghost" data-set="test">测试连接</button><button type="submit" class="primary">保存</button></div>' +
      '<div class="set-test"></div></form>';
    trBox.innerHTML = '<form class="card set-card"><div class="set-title">BT 额外 Tracker</div>' +
      '<p class="hint">每行一个，BT 下载开始前与内置 tracker 一起添加。</p>' +
      '<textarea name="text" rows="6" spellcheck="false" placeholder="udp://tracker.example.com:1337/announce">' + esc(d.trackers || '') + '</textarea>' +
      '<div class="row"><button type="submit" class="primary">保存</button></div></form>';
    renderFilters(d.dmfilter);
  }
  // 弹幕屏蔽词: 只重画这一块 (别把上面正在填的代理表单冲掉)
  function reloadFilters() {
    fetch('api/settings').then(function (r) { return r.json(); }).then(function (d) { renderFilters(d.dmfilter); }).catch(fail);
  }
  function renderFilters(df) {
    df = df || { enabled: true, items: [] };
    dfBox.innerHTML = '<div class="card set-card"><div class="set-title">弹幕屏蔽词</div>' +
      '<label class="toggle"><input type="checkbox" data-df="switch"' + (df.enabled ? ' checked' : '') + '>启用屏蔽（关掉后下面的规则都不生效）</label>' +
      (df.items.length ? df.items.map(function (x) {
        return '<div class="df-item"><label class="src-sw"><input type="checkbox" data-df="toggle" data-id="' + esc(x.id) + '"' +
          (x.on ? ' checked' : '') + '></label><code>' + esc(x.regex) + '</code>' +
          '<button type="button" class="sub-del" data-df="delete" data-id="' + esc(x.id) + '">删除</button></div>';
      }).join('') : '<p class="hint">还没有屏蔽词</p>') +
      '<form class="sub-add" id="df-add"><input type="text" name="regex" autocomplete="off" placeholder="要屏蔽的词，支持正则">' +
      '<button type="submit" class="primary">添加</button></form>' +
      '<p class="hint">改完立即生效，正在播放的弹幕会马上按新规则重新过滤。</p></div>';
  }
  dfBox.addEventListener('change', function (e) {
    var k = e.target.getAttribute('data-df');
    if (k === 'switch') {
      post('api/settings/dmfilter/switch', { on: e.target.checked ? '1' : '0' }).then(function (r) { toast(r.message); reloadFilters(); }).catch(fail);
    } else if (k === 'toggle') {
      post('api/settings/dmfilter/toggle', { id: e.target.getAttribute('data-id'), on: e.target.checked ? '1' : '0' })
        .then(function (r) { if (r.message) toast(r.message); reloadFilters(); }).catch(fail);
    }
  });
  dfBox.addEventListener('click', function (e) {
    var b = e.target.closest('[data-df="delete"]');
    if (!b) return;
    post('api/settings/dmfilter/delete', { id: b.getAttribute('data-id') }).then(function (r) { toast(r.message); reloadFilters(); }).catch(fail);
  });
  dfBox.addEventListener('submit', function (e) {
    e.preventDefault();
    var input = e.target.elements.regex;
    post('api/settings/dmfilter/add', { regex: input.value }).then(function (r) {
      toast(r.message);
      if (r.ok) { input.value = ''; reloadFilters(); }
    }).catch(fail);
  });
  proxyBox.addEventListener('change', function (e) {
    if (e.target.name !== 'mode') return;
    var f = e.target.form;
    f.querySelector('.set-custom').hidden = e.target.value !== 'CUSTOM';
    f.querySelector('.set-sys').hidden = e.target.value !== 'SYSTEM';
  });
  proxyBox.addEventListener('submit', function (e) {
    e.preventDefault();
    post('api/settings/proxy', new FormData(e.target)).then(function (r) { toast(r.message); if (r.ok) load(); }).catch(fail);
  });
  proxyBox.addEventListener('click', function (e) {
    var b = e.target.closest('[data-set="test"]');
    if (!b) return;
    var out = proxyBox.querySelector('.set-test');
    b.disabled = true;
    out.innerHTML = '<p class="hint">正在测试，最多要十几秒…（按已保存的设置测）</p>';
    post('api/settings/proxy/test', {}).then(function (r) {
      b.disabled = false;
      out.innerHTML = '<p class="hint">' + esc(r.message) + '</p>' + (r.items || []).map(function (x) {
        return '<div class="set-probe"><span>' + esc(x.name) + '</span><b class="' + (x.ok ? 'ok' : 'bad') + '">' + esc(x.text) + '</b></div>';
      }).join('');
    }).catch(function () { b.disabled = false; fail(); });
  });
  trBox.addEventListener('submit', function (e) {
    e.preventDefault();
    post('api/settings/trackers', new FormData(e.target)).then(function (r) { toast(r.message); if (r.ok) load(); }).catch(fail);
  });
})();
""".trimIndent()

/**
 * 「播放器」标签里的「弹幕」与「音轨与字幕」两个可收起的区 (见 RemotePlayerExtras). 数据随播放器状态轮询来,
 * 只在内容变了才重画 (免得关掉手机上正打开的下拉框); 手动匹配的搜索 / 选集流程在单独的容器里, 轮询不碰它.
 */
private val DANMAKU_SCRIPT = """
(function () {
  var hooks = window.remoteHooks;
  var dmBox = document.getElementById('dm-box'), dmBody = document.getElementById('dm-body');
  var dmSum = document.getElementById('dm-sum'), dmMatch = document.getElementById('dm-match');
  var trBox = document.getElementById('tr-box'), trBody = document.getElementById('tr-body');
  var lastDm = '', lastTr = '', title = '', shifts = {}, picked = null;
  function remember(box, key) {
    try { box.open = localStorage.getItem(key) === '1'; } catch (e) {}
    box.addEventListener('toggle', function () { try { localStorage.setItem(key, box.open ? '1' : ''); } catch (e) {} });
  }
  remember(dmBox, 'remote.dm');
  remember(trBox, 'remote.tr');
  function fmtShift(ms) { return (ms > 0 ? '+' : '') + (ms / 1000).toFixed(1) + ' 秒'; }
  function done(r) { if (r.message) toast(r.message); poll(true); }
  function shiftBtn(sv, d, text) {
    return '<button type="button" data-dm="shift" data-sv="' + esc(sv) + '" data-d="' + d + '">' + text + '</button>';
  }
  function trackSelect(kind, label, g, none) {
    return '<label class="episode"><span>' + label + '</span><select data-tr="' + kind + '">' +
      '<option value=""' + (g.sel ? '' : ' selected') + '>' + none + '</option>' +
      g.items.map(function (x) {
        return '<option value="' + esc(x.id) + '"' + (x.id === g.sel ? ' selected' : '') + '>' + esc(x.name) + '</option>';
      }).join('') + '</select></label>';
  }
  hooks.unavailable.push(function () { dmBox.hidden = true; trBox.hidden = true; lastDm = ''; lastTr = ''; });
  hooks.render.push(function (s) {
    title = s.title || '';
    var d = s.danmaku;
    dmBox.hidden = !d;
    if (d) {
      var total = 0;
      d.sources.forEach(function (x) { if (x.on) total += x.count; shifts[x.service] = x.shift; });
      dmSum.textContent = d.enabled ? (d.loading && !d.sources.length ? '加载中' : total + ' 条') : '已关闭';
      var h = '<label class="toggle"><input type="checkbox" data-dm="all"' + (d.enabled ? ' checked' : '') + '>显示弹幕</label>';
      if (!d.sources.length) h += '<p class="hint">' + (d.loading ? '正在加载弹幕…' : '这一集没有找到弹幕') + '</p>';
      d.sources.forEach(function (x) {
        h += '<div class="dm-src' + (x.on ? '' : ' off') + '"><div class="dm-top">' +
          '<label class="src-sw"><input type="checkbox" data-dm="src" data-sv="' + esc(x.service) + '"' + (x.on ? ' checked' : '') + '></label>' +
          '<div class="dm-name">' + esc(x.name) + '<small>' + x.count + ' 条</small></div></div>' +
          '<div class="dm-how">' + esc(x.method) + (x.matched ? '：' + esc(x.matched) : '') + '</div>' +
          '<div class="dm-shift"><span>时间偏移</span>' + shiftBtn(x.service, -1000, '-1') + shiftBtn(x.service, -500, '-0.5') +
          '<b>' + fmtShift(x.shift) + '</b>' + shiftBtn(x.service, 500, '+0.5') + shiftBtn(x.service, 1000, '+1') +
          (x.shift ? shiftBtn(x.service, 'reset', '归零') : '') + '</div></div>';
      });
      h += '<p class="hint">偏移为正数时弹幕晚出现。各源开关与偏移只对这次播放有效。</p>';
      if (d.canMatch) h += '<button type="button" class="ghost wide" data-dm="match">弹幕对不上？手动匹配（弹弹play）</button>';
      if (h !== lastDm) { dmBody.innerHTML = h; lastDm = h; }
    }
    var t = s.tracks || {}, th = '';
    if (t.audio && t.audio.items.length > 1) th += trackSelect('audio', '音轨', t.audio, '自动');
    if (t.subs && t.subs.items.length) th += trackSelect('sub', '字幕', t.subs, '关闭');
    trBox.hidden = !th;
    if (th !== lastTr) { trBody.innerHTML = th; lastTr = th; }
  });
  dmBody.addEventListener('change', function (e) {
    var k = e.target.getAttribute('data-dm');
    if (k === 'all') post('api/player/danmaku/enable', { on: e.target.checked ? '1' : '0' }).then(done).catch(fail);
    else if (k === 'src') {
      post('api/player/danmaku/source', { service: e.target.getAttribute('data-sv'), on: e.target.checked ? '1' : '0' })
        .then(done).catch(fail);
    }
  });
  dmBody.addEventListener('click', function (e) {
    var b = e.target.closest('button[data-dm]');
    if (!b) return;
    var k = b.getAttribute('data-dm');
    if (k === 'shift') {
      var sv = b.getAttribute('data-sv'), dd = b.getAttribute('data-d');
      var next = dd === 'reset' ? 0 : (shifts[sv] || 0) + Number(dd);
      shifts[sv] = next;
      post('api/player/danmaku/shift', { service: sv, ms: String(next) }).then(done).catch(fail);
    } else if (k === 'match') {
      dmMatch.innerHTML = '<form class="dm-form"><input type="text" name="q" autocomplete="off" placeholder="番剧名" value="' +
        esc(title) + '"><button type="submit" class="primary">搜索</button></form><div class="dm-list" id="dm-results"></div>' +
        '<div class="row"><button type="button" class="ghost" data-mt="cancel">取消手动匹配</button></div>';
    }
  });
  function results() { return document.getElementById('dm-results'); }
  function hint(text) { results().innerHTML = '<p class="hint">' + text + '</p>'; }
  dmMatch.addEventListener('submit', function (e) {
    e.preventDefault();
    var q = e.target.elements.q.value.trim();
    if (!q) return;
    e.target.elements.q.blur();
    hint('正在搜索…');
    post('api/player/danmaku/search', { q: q }).then(function (r) {
      if (!r.ok) { hint(esc(r.message)); return; }
      results().innerHTML = r.items.length ? '<p class="hint">选一个条目</p>' + r.items.map(function (x) {
        return '<button type="button" data-mt="subject" data-id="' + esc(x.id) + '" data-name="' + esc(x.name) + '">' + esc(x.name) + '</button>';
      }).join('') : '<p class="hint">没有搜到，换个名字试试</p>';
    }).catch(fail);
  });
  dmMatch.addEventListener('click', function (e) {
    var b = e.target.closest('[data-mt]');
    if (!b) return;
    var k = b.getAttribute('data-mt');
    if (k === 'cancel') { dmMatch.innerHTML = ''; return; }
    if (k === 'subject') {
      picked = { sid: b.getAttribute('data-id'), sname: b.getAttribute('data-name') };
      hint('正在加载剧集…');
      post('api/player/danmaku/episodes', picked).then(function (r) {
        if (!r.ok) { hint(esc(r.message)); return; }
        results().innerHTML = '<p class="hint">' + esc(picked.sname) + '：选一集</p>' + r.items.map(function (x, i) {
          return '<button type="button" data-mt="episode" data-id="' + esc(x.id) + '" data-name="' + esc(x.name) + '"' +
            (i === r.suggested ? ' class="sug"' : '') + '>' + esc(x.name) + '</button>';
        }).join('');
        var sug = results().querySelector('.sug');
        if (sug) results().scrollTop = sug.offsetTop - results().offsetTop - 60;
      }).catch(fail);
    } else if (k === 'episode' && picked) {
      hint('正在加载弹幕…');
      post('api/player/danmaku/apply', {
        sid: picked.sid, sname: picked.sname, eid: b.getAttribute('data-id'), ename: b.getAttribute('data-name')
      }).then(function (r) {
        toast(r.message);
        if (r.ok) { dmMatch.innerHTML = ''; poll(true); } else hint(esc(r.message));
      }).catch(fail);
    }
  });
  trBody.addEventListener('change', function (e) {
    var k = e.target.getAttribute('data-tr');
    if (!k) return;
    post('api/player/track', { kind: k, id: e.target.value }).then(done).catch(fail);
  });
  // 发弹幕: 发在电视当前的播放进度上 (需要登录)
  document.getElementById('dm-send').addEventListener('submit', function (e) {
    e.preventDefault();
    var input = e.target.elements.text, btn = e.target.querySelector('button');
    var text = input.value.trim();
    if (!text) return;
    btn.disabled = true;
    post('api/player/danmaku/send', { text: text }).then(function (r) {
      btn.disabled = false;
      toast(r.message);
      if (r.ok) input.value = '';
    }).catch(function () { btn.disabled = false; fail(); });
  });
})();
""".trimIndent()

/**
 * 缓存面板 (见 RemoteCache): 播放卡底部「缓存这部番的剧集…」与「缓存」标签里各部番的「缓存更多剧集…」打开的全屏面板. 两页 ——
 * 剧集列表 (状态 / 进度 / 失败原因, 勾选几集自动挑资源缓存) 与某一集的资源列表 (分辨率 / 字幕 / 字幕组筛选, 点一条开始缓存).
 * 列表每 2 秒刷新 (进度), 资源页查询中 1.5 秒、查完 5 秒一次; 只在内容变了才重画, 免得关掉正打开的下拉框.
 */
private val CACHE_SCRIPT = """
(function () {
  var sheet = document.getElementById('cache-sheet'), sb = document.getElementById('cache-body');
  var titleEl = document.getElementById('cache-title'), backBtn = document.getElementById('cache-back');
  var subject = null, subjectTitle = '', view = 'eps', ep = null, timer = null, lastHtml = '', lastFilters = '', lastData = null;
  var picked = {}, fRes = '', fSub = '', fAll = '', fEx = false;
  // 资源页的数据源胶囊 (null = 全部) 与「显示全部」, 同播放器标签; lastCands: 点胶囊时就地重画用
  var ccSrc = null, ccFull = false, lastCands = null, lastChips = '';
  function stop() { clearTimeout(timer); timer = null; }
  function openSheet(id, title) {
    subject = id;
    subjectTitle = title || '';
    picked = {};
    sheet.hidden = false;
    document.body.style.overflow = 'hidden';
    showEpisodes();
  }
  function closeSheet() {
    stop();
    if (view === 'cands') post('api/cache/close', {}).catch(function () {});
    sheet.hidden = true;
    document.body.style.overflow = '';
    // 从「缓存」标签打开的: 关上就刷新一次那边的列表 (面板盖着时它不轮询)
    if (window.loadCaches) window.loadCaches();
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-cache]');
    if (b) openSheet(+b.getAttribute('data-cache'), b.getAttribute('data-title'));
  });
  document.getElementById('cache-close').addEventListener('click', closeSheet);
  backBtn.addEventListener('click', function () {
    post('api/cache/close', {}).catch(function () {});
    showEpisodes();
  });

  // ---- 剧集列表 ----
  function showEpisodes() {
    view = 'eps';
    backBtn.hidden = true;
    titleEl.textContent = '缓存 · ' + subjectTitle;
    lastHtml = '';
    sb.innerHTML = '<p class="hint">正在读取剧集…</p>';
    pollEpisodes();
  }
  function pollEpisodes() {
    stop();
    if (sheet.hidden || view !== 'eps') return;
    fetch('api/cache?subject=' + subject).then(function (r) { return r.json(); }).then(function (d) {
      if (view !== 'eps') return;
      renderEpisodes(d);
      timer = setTimeout(pollEpisodes, 2000);
    }).catch(function () { timer = setTimeout(pollEpisodes, 4000); });
  }
  function pickedIds() { return Object.keys(picked).filter(function (k) { return picked[k]; }); }
  function renderEpisodes(d) {
    lastData = d;
    if (!d.ok) { sb.innerHTML = '<p class="hint">' + esc(d.message) + '</p>'; return; }
    if (d.title) { subjectTitle = d.title; titleEl.textContent = '缓存 · ' + d.title; }
    var b = d.batch, running = !!(b && b.running), h = '';
    if (running) {
      h += '<div class="now-status busy"><b>自动缓存中</b><span>' + b.done + ' / ' + b.total + (b.current ? '：' + esc(b.current) : '') + '</span></div>';
    } else if (b && b.failures.length) {
      h += '<div class="now-status error"><b>' + b.failures.length + ' 集没能自动缓存</b><span>原因写在对应那一集下面，可以点「选资源」自己挑</span></div>';
    }
    // 已缓存的合集还覆盖着的集 (同电视缓存页: 点一集直接用合集, 不用再挑); 一次全部补上走自动批量 (它先找合集)
    var packIds = d.episodes.filter(function (x) { return x.status === 'none' && x.pack; }).map(function (x) { return x.id; });
    if (packIds.length && !running) {
      h += '<div class="now-status ready"><b>合集里还有 ' + packIds.length + ' 集没缓存</b>' + (d.packTitle ? '<span>' + esc(d.packTitle) + '</span>' : '') + '</div>' +
        '<button type="button" class="ghost wide cache-packall" data-packall="' + packIds.join(',') + '">全部用合集缓存（' + packIds.length + ' 集）</button>';
    }
    h += '<p class="hint">' + (d.free ? '电视剩余空间 ' + esc(d.free) + '。' : '') +
      '勾选几集后点最下面的按钮，按你的数据源偏好自动挑资源缓存（有已缓存的合集时先用合集）；也可以对某一集点「选资源」自己挑。</p>';
    d.episodes.forEach(function (x) {
      var size = x.size ? ' · ' + x.size : '';
      var st = x.status === 'cached' ? ['ok', '已缓存' + size] : x.status === 'caching' ? ['run', '缓存中 ' + x.progress + '%' + size]
        : x.error ? ['bad', x.error] : x.pack ? ['', '未缓存 · 已缓存的合集里有这一集'] : ['', '未缓存'];
      var free = x.status === 'none';
      if (!free) delete picked[x.id];
      h += '<div class="cache-ep"><label class="src-sw"><input type="checkbox" data-pick="' + x.id + '"' +
        (picked[x.id] ? ' checked' : '') + (free ? '' : ' disabled') + '></label>' +
        '<div class="n">' + (x.watched ? '✓ ' : '') + esc(x.label) + '<div class="st ' + st[0] + '">' + esc(st[1]) + '</div></div>' +
        (free && x.pack ? '<button type="button" class="cache-pack" data-pack="' + x.id + '">用合集</button>' : '') +
        (free ? '<button type="button" data-ep="' + x.id + '" data-label="' + esc(x.label) + '">选资源</button>' : '') + '</div>';
    });
    var n = pickedIds().length;
    h += '<div class="cache-bar"><button type="button" class="primary wide" id="cache-auto"' + (n && !running ? '' : ' disabled') + '>' +
      (running ? '自动缓存进行中…' : n ? '自动挑资源缓存选中的 ' + n + ' 集' : '先勾选要缓存的剧集') + '</button></div>';
    if (h !== lastHtml) { sb.innerHTML = h; lastHtml = h; }
  }

  // ---- 某一集的资源 ----
  function showCandidates(id, label) {
    view = 'cands';
    ep = id;
    backBtn.hidden = false;
    titleEl.textContent = label;
    fRes = fSub = fAll = '';
    fEx = false;
    ccSrc = null;
    ccFull = false;
    lastCands = null;
    lastChips = '';
    lastFilters = '';
    lastHtml = '';
    sb.innerHTML = '<div id="cc-chips"></div><div id="cc-filters"></div><div id="cc-list"><p class="hint">正在查找资源…</p></div>';
    pollCandidates();
  }
  function pollCandidates() {
    stop();
    if (sheet.hidden || view !== 'cands') return;
    fetch('api/cache/candidates?subject=' + subject + '&episode=' + ep + '&res=' + encodeURIComponent(fRes) +
      '&sub=' + encodeURIComponent(fSub) + '&all=' + encodeURIComponent(fAll) + '&ex=' + (fEx ? '1' : '') +
      '&full=' + (ccFull && ccSrc ? encodeURIComponent(ccSrc) : ''))
      .then(function (r) { return r.json(); }).then(function (d) {
        if (view !== 'cands') return;
        renderCandidates(d);
        timer = setTimeout(pollCandidates, d.loading ? 1500 : 5000);
      }).catch(function () { timer = setTimeout(pollCandidates, 4000); });
  }
  function dropdown(key, label, list, cur) {
    var h = '<label class="sel"><span>' + label + '</span><select data-cf="' + key + '"><option value="">全部</option>';
    var has = false;
    (list || []).forEach(function (o) {
      if (o.value === cur) has = true;
      h += '<option value="' + esc(o.value) + '"' + (o.value === cur ? ' selected' : '') + '>' + esc(o.label) + '（' + o.count + '）</option>';
    });
    if (cur && !has) h += '<option value="' + esc(cur) + '" selected>' + esc(cur) + '（0）</option>';
    return h + '</select></label>';
  }
  // 数据源胶囊 (同播放器标签): 点一个只看这个源, 再点一次或点「全部」取消; 纯本地筛选, 就地重画
  function ccChips(d) {
    var h = '<div class="chips"><button type="button" class="chip' + (ccSrc ? '' : ' on') + '" data-cc="">全部</button>';
    d.sources.forEach(function (x) {
      var n = x.state === 'loading' ? '…' : x.state === 'captcha' ? '需验证' : x.state === 'failed' ? '失败' : x.state === 'limited' ? '限流' : x.count;
      h += '<button type="button" class="chip ' + x.state + (ccSrc === x.id ? ' on' : '') + '" data-cc="' + esc(x.id) + '">' +
        esc(x.name) + ' ' + n + '</button>';
    });
    return h + '</div>';
  }
  function renderCandidates(d) {
    var list = document.getElementById('cc-list'), fbox = document.getElementById('cc-filters'), cbox = document.getElementById('cc-chips');
    if (!list) return;
    if (!d.ok) { list.innerHTML = '<p class="hint">' + esc(d.message) + '</p>'; return; }
    lastCands = d;
    // 选中的源已经不在了 (比如被停用): 回到「全部」
    if (ccSrc && !d.sources.some(function (x) { return x.id === ccSrc; }) && !d.groups.some(function (g) { return g.id === ccSrc; })) {
      ccSrc = null;
      ccFull = false;
    }
    var ch = ccChips(d);
    if (ch !== lastChips) { cbox.innerHTML = ch; lastChips = ch; }
    var f = d.filters || {};
    var one = ccSrc ? d.groups.filter(function (g) { return g.id === ccSrc; })[0] : null;
    var fh = '<div class="filters">' + dropdown('res', '分辨率', f.resolution, fRes) + dropdown('sub', '字幕', f.subtitle, fSub) +
      dropdown('all', '字幕组', f.alliance, fAll) + '</div>' +
      '<div class="toggles"><label class="toggle"><input type="checkbox" data-cf="ex"' + (fEx ? ' checked' : '') + '>显示被排除的资源' +
      (d.excludedCount ? '（' + d.excludedCount + ' 条）' : '') + '</label>' +
      // 「显示全部 N 条」: 只在点了某个胶囊、而且这个源确实没列全时出现 (勾着时一直显示, 好取消)
      (one && (ccFull || one.more > 0) ? '<label class="toggle"><input type="checkbox" data-cf="full"' + (ccFull ? ' checked' : '') +
        '>显示全部 ' + one.total + ' 条</label>' : '') + '</div>';
    if (fh !== lastFilters) { fbox.innerHTML = fh; lastFilters = fh; }
    var groups = ccSrc ? d.groups.filter(function (g) { return g.id === ccSrc; }) : d.groups;
    var total = 0;
    groups.forEach(function (g) { total += g.total; });
    var bad = d.sources.filter(function (s) { return s.state === 'failed' || s.state === 'captcha' || s.state === 'limited'; }).length;
    var h = '<p class="hint">' + (d.loading ? '正在查找资源… 已找到 ' + total + ' 条' : '共 ' + total + ' 条') +
      (bad && !ccSrc ? '，' + bad + ' 个数据源没查到' : '') + '。点一条开始缓存。</p>';
    if (!groups.length) {
      var src = ccSrc ? d.sources.filter(function (x) { return x.id === ccSrc; })[0] : null;
      var why = (fRes || fSub || fAll) ? '没有符合筛选条件的资源，试试放宽筛选'
        : !ccSrc ? (d.loading ? '' : '没有找到可以缓存的资源')
        : !src || src.state === 'done' ? '这个数据源没有匹配的资源'
        : src.state === 'loading' ? '这个数据源还在搜索…'
        : src.state === 'captcha' ? '这个数据源需要人机验证，请在电视上处理'
        : src.state === 'limited' ? '这个数据源被限流了，稍后再试'
        : '这个数据源搜索失败';
      if (why && !fEx && d.excludedCount) why += '，也可以勾选「显示被排除的资源」看看';
      if (why) h += '<p class="hint">' + why + '</p>';
    }
    groups.forEach(function (g) {
      h += '<h2>' + esc(g.name) + ' <small>' + g.total + ' 条</small></h2><div class="list">';
      g.items.forEach(function (it) {
        var meta = [it.resolution, it.subtitles, it.alliance, it.size].filter(Boolean).join(' · ');
        h += '<button type="button" class="item' + (it.excluded ? ' ex' : '') + (it.blocked ? ' blocked' : '') + '" data-mid="' + esc(it.id) +
          '" data-title="' + esc(it.title) + '"' + (it.blocked ? ' data-blocked="' + esc(it.reason || '') + '"' : '') + '>' +
          '<span class="t">' + esc(it.title) + '</span><span class="m">' + esc(meta) + '</span>' +
          (it.excluded ? '<span class="why">已排除：' + esc(it.reason || '') + '</span>' : '') + '</button>';
      });
      h += '</div>';
      if (g.more > 0) h += '<p class="hint">还有 ' + g.more + ' 条没列出，' +
        (ccSrc ? '可以勾选上面的「显示全部」' : '点上面这个数据源的胶囊后可以选择显示全部') + '</p>';
    });
    if (h !== lastHtml) { list.innerHTML = h; lastHtml = h; }
  }

  sb.addEventListener('change', function (e) {
    var id = e.target.getAttribute('data-pick');
    if (id) {
      picked[id] = e.target.checked;
      if (lastData) renderEpisodes(lastData);
      return;
    }
    var k = e.target.getAttribute('data-cf');
    if (!k) return;
    if (k === 'res') fRes = e.target.value;
    else if (k === 'sub') fSub = e.target.value;
    else if (k === 'all') fAll = e.target.value;
    else if (k === 'ex') fEx = e.target.checked;
    else if (k === 'full') ccFull = e.target.checked;
    pollCandidates();
  });
  sb.addEventListener('click', function (e) {
    var c = e.target.closest('[data-cc]');
    if (c) {
      var id = c.getAttribute('data-cc');
      ccSrc = !id || id === ccSrc ? null : id;
      // 「显示全部」只对当时选中的那个源: 换源就作废, 并重新拉一次截断后的列表
      var hadFull = ccFull;
      ccFull = false;
      if (lastCands) renderCandidates(lastCands);
      if (hadFull) pollCandidates();
      return;
    }
    var b = e.target.closest('button');
    if (!b) return;
    if (b.id === 'cache-auto') {
      var ids = pickedIds();
      if (!ids.length) return;
      b.disabled = true;
      post('api/cache/auto', { subject: String(subject), episodes: ids.join(',') }).then(function (r) {
        toast(r.message);
        if (r.ok) picked = {};
        pollEpisodes();
      }).catch(fail);
    } else if (b.hasAttribute('data-pack')) {
      // 用已缓存的合集缓存这一集: 不进资源列表
      b.disabled = true;
      post('api/cache/pack', { subject: String(subject), episode: b.getAttribute('data-pack') }).then(function (r) {
        toast(r.message);
        pollEpisodes();
      }).catch(function () { b.disabled = false; fail(); });
    } else if (b.hasAttribute('data-packall')) {
      b.disabled = true;
      post('api/cache/auto', { subject: String(subject), episodes: b.getAttribute('data-packall') }).then(function (r) {
        toast(r.message);
        pollEpisodes();
      }).catch(function () { b.disabled = false; fail(); });
    } else if (b.hasAttribute('data-ep')) {
      showCandidates(+b.getAttribute('data-ep'), b.getAttribute('data-label'));
    } else if (b.hasAttribute('data-mid')) {
      if (b.hasAttribute('data-blocked')) { toast('不能选：' + b.getAttribute('data-blocked')); return; }
      if (!confirm('缓存这个资源？\n' + b.getAttribute('data-title'))) return;
      post('api/cache/pick', { subject: String(subject), episode: String(ep), id: b.getAttribute('data-mid') }).then(function (r) {
        toast(r.message);
        if (r.ok) showEpisodes();
      }).catch(fail);
    }
  });
})();
""".trimIndent()

/**
 * 「缓存」标签 (见 RemoteCacheList): 电视上的全部缓存按番分组, 每集的状态 / 大小 / 速度, 暂停 / 继续 / 删除; 顶上是电视剩余空间
 * 与没下完的还差多少. 停在本标签、缓存面板没盖在上面时每 2 秒刷新, 只在内容变了才重画. 每部番底部的「缓存更多剧集…」打开缓存面板
 * (同播放卡上那个按钮, 点击由 CACHE_SCRIPT 统一接).
 */
private val CACHE_LIST_SCRIPT = """
(function () {
  var tab = document.getElementById('tab-cache');
  var sumBox = document.getElementById('cl-sum'), listBox = document.getElementById('cl-list');
  var sheet = document.getElementById('cache-sheet');
  var timer = null, busy = false, lastSum = '', lastList = '';
  function active() { return !tab.hidden && sheet.hidden && !document.hidden; }
  function load() {
    clearTimeout(timer);
    timer = null;
    if (!active() || busy) return;
    busy = true;
    fetch('api/caches').then(function (r) { return r.json(); }).then(function (d) {
      busy = false;
      render(d);
      if (active()) timer = setTimeout(load, 2000);
    }).catch(function (e) {
      busy = false;
      console.error(e);
      if (active()) timer = setTimeout(load, 4000);
    });
  }
  window.loadCaches = load;
  document.addEventListener('visibilitychange', function () { if (!document.hidden) load(); });
  function render(d) {
    var s = '', h = '';
    if (!d.ok) {
      h = '<p class="hint">' + esc(d.message) + '</p>';
    } else {
      s = '<div class="card"><div class="cl-free">电视剩余空间 <b>' + esc(d.free || '未知') + '</b>' +
        (d.total ? '<small> / 共 ' + esc(d.total) + '</small>' : '') + '</div>';
      if (d.count) s += '<div class="cl-line">' + d.count + ' 集缓存，共 ' + esc(d.used) + '</div>';
      var run = [];
      if (d.downloading) run.push(d.downloading + ' 集下载中' + (d.speed ? ' ↓ ' + esc(d.speed) : ''));
      if (d.pending) run.push('没下完的还差 ' + esc(d.pending));
      if (run.length) s += '<div class="cl-line">' + run.join(' · ') + '</div>';
      if (d.lowSpace) s += '<div class="cl-warn">剩余空间不够把没下完的都下完</div>';
      s += '</div>';
      if (!d.groups.length) {
        h = '<div class="empty"><p>电视上还没有缓存</p><p class="hint">在「播放器」里点「缓存这部番的剧集…」开始缓存</p></div>';
      }
      d.groups.forEach(function (g) {
        // 番名那一行右边: 整部删除 (先确认; 里面有正在播的那一集时确认框多说一句)
        var playingItem = g.items.filter(function (x) { return x.playing; })[0];
        h += '<div class="card cl-group"><div class="cl-head"><div class="cl-title">' + esc(g.title) + '</div>' +
          (g.id ? '<button type="button" class="cl-delall" data-cdelall="' + g.id + '" data-title="' + esc(g.title) +
            '" data-count="' + g.items.length + '"' + (playingItem ? ' data-playing="' + esc(playingItem.label) + '"' : '') +
            '>删除全部</button>' : '') +
          '</div><div class="cl-meta">' + esc(g.meta) + '</div>';
        g.items.forEach(function (x) { h += item(g, x); });
        if (g.id) h += '<button type="button" class="ghost wide cl-more" data-cache="' + g.id + '" data-title="' + esc(g.title) + '">缓存更多剧集…</button>';
        h += '</div>';
      });
    }
    if (s !== lastSum) { sumBox.innerHTML = s; lastSum = s; }
    if (h !== lastList) { listBox.innerHTML = h; lastList = h; }
  }
  function item(g, x) {
    var color = x.st === 'done' ? 'ok' : x.st === 'failed' ? 'bad' : x.st === 'paused' ? 'pause' : '';
    var bits = ['<b class="' + color + '">' + esc(x.text) + '</b>'];
    if (x.size) bits.push(esc(x.size));
    if (x.speed) bits.push('↓ ' + esc(x.speed));
    if (x.source) bits.push(esc(x.source));
    if (x.watched) bits.push(esc(x.watched));
    var act = x.st === 'run' ? '<button type="button" data-cact="pause" data-cid="' + esc(x.cid) + '">暂停</button>'
      : x.st === 'paused' ? '<button type="button" data-cact="resume" data-cid="' + esc(x.cid) + '">继续</button>' : '';
    return '<div class="cl-ep"><div class="n">' + esc(x.label) +
      (x.playing ? '<span class="cl-tag play">正在播放</span>' : '') + (x.pack ? '<span class="cl-tag">合集</span>' : '') +
      '<div class="cl-st">' + bits.join(' · ') + '</div>' +
      (x.progress != null ? '<div class="cl-bar"><div style="width:' + x.progress + '%"></div></div>' : '') + '</div>' + act +
      '<button type="button" class="cl-del" data-cact="delete" data-cid="' + esc(x.cid) + '" data-label="' + esc(g.title + ' ' + x.label) + '"' +
      (x.packShare ? ' data-share="' + x.packShare + '"' : '') + (x.playing ? ' data-playing="1"' : '') + '>删除</button></div>';
  }
  listBox.addEventListener('click', function (e) {
    var all = e.target.closest('[data-cdelall]');
    if (all) {
      if (all.disabled) return;
      var m = '删除「' + all.getAttribute('data-title') + '」的全部 ' + all.getAttribute('data-count') + ' 集缓存？';
      var pl = all.getAttribute('data-playing');
      if (pl) m += '\n\n其中「' + pl + '」正在播放，删除后需要重新选择数据源。';
      if (!confirm(m)) return;
      all.disabled = true;
      post('api/caches/delete-subject', { subject: all.getAttribute('data-cdelall') }).then(function (r) {
        toast(r.message);
        load();
      }).catch(function () { all.disabled = false; fail(); });
      return;
    }
    var b = e.target.closest('[data-cact]');
    if (!b || b.disabled) return;
    var act = b.getAttribute('data-cact');
    if (act === 'delete') {
      var msg = '删除「' + b.getAttribute('data-label') + '」的缓存？';
      // 同电视缓存页的删除确认: 正在播的那条多说一句
      if (b.getAttribute('data-playing')) msg += '\n\n这一集正在播放，删除后需要重新选择数据源。';
      // 合集的文件要等同一个种子的集都删了才一起回收 (见 TorrentMediaCacheEngine)
      var share = b.getAttribute('data-share');
      if (share) msg += '\n\n这一集来自合集，同一个种子还有 ' + share + ' 集缓存着：删掉它不会马上腾出空间，等这些集也都删了才一起回收。';
      if (!confirm(msg)) return;
    }
    b.disabled = true;
    post('api/caches/' + act, { id: b.getAttribute('data-cid') }).then(function (r) {
      toast(r.message);
      load();
    }).catch(function () { b.disabled = false; fail(); });
  });
})();
""".trimIndent()

/**
 * 「播放器」标签里的「评论与评分」区 (见 RemotePlayerExtras): 收藏状态、我的评分 + 短评、发表本集评论. 展开时才拉一次
 * (换了条目再拉), 不随轮询重画 —— 表单正在填. 评分的档位说法同 Bangumi.
 *
 * 版式参照 App 的评分弹窗 (居中的分数 + 评价词、一行十颗星、短评、仅自己可见) 和 Bangumi 的收藏盒 (五种状态横排):
 * 星星点一下定分、按住左右滑动改分; 取消收藏会清掉进度和评价, 先确认. 没收藏时评分区置灰 (服务端本来也拒), 没登录只放登录入口.
 * 本集评论带表情面板 (目录见 RemoteStickers, 图由手机直接去 Bangumi 图片站拉, 不带 Referer) 与实时预览 (正文里认出表情代码才出现).
 */
private val REVIEW_SCRIPT = """
(function () {
  var hooks = window.remoteHooks;
  var box = document.getElementById('cm-box'), body = document.getElementById('cm-body'), sum = document.getElementById('cm-sum');
  var subject = null, loaded = false;
  var TYPES = [['NOT_COLLECTED', '未收藏'], ['WISH', '想看'], ['DOING', '在看'], ['DONE', '看过'], ['ON_HOLD', '搁置'], ['DROPPED', '抛弃']];
  // 评价词同 App 的评分弹窗; 1 分和 10 分带「请谨慎评价」, 也跟 App 一样标红
  var WORDS = ['', '不忍直视（请谨慎评价）', '很差', '差', '较差', '不过不失', '还行', '推荐', '力荐', '神作', '超神作（请谨慎评价）'];
  // 星星同 App (Material 圆角星): .o 空心 / .f 实心, 由 svg 上的 on 切换
  var STAR = '<svg viewBox="0 0 24 24" aria-hidden="true">' +
    '<path class="o" d="M19.65 9.04l-4.84-.42-1.89-4.45c-.34-.81-1.5-.81-1.84 0L9.19 8.63l-4.83.41c-.88.07-1.24 1.17-.57 1.75l3.67 3.18-1.1 4.72c-.2.86.73 1.54 1.49 1.08l4.15-2.5 4.15 2.51c.76.46 1.69-.22 1.49-1.08l-1.1-4.73 3.67-3.18c.67-.58.32-1.68-.56-1.75zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z"/>' +
    '<path class="f" d="M12 17.27l4.15 2.51c.76.46 1.69-.22 1.49-1.08l-1.1-4.72 3.67-3.18c.67-.58.31-1.68-.57-1.75l-4.83-.41-1.89-4.46c-.34-.81-1.5-.81-1.84 0L9.19 8.63l-4.83.41c-.88.07-1.24 1.17-.57 1.75l3.67 3.18-1.1 4.72c-.2.86.73 1.54 1.49 1.08l4.15-2.5z"/></svg>';
  var drag = null;
  // 表情面板: 目录 (api/stickers) 头一回画出评论框时拉一次; pack = 当前分组 (记在手机上); emoOpen = 面板开着 (重画后照旧开着)
  var stickers = null, stickerRe = null, stickerUrl = {}, emoOpen = false, pack = 0;
  try { pack = Number(localStorage.getItem('remote.stickerPack')) || 0; } catch (e) {}
  var EMO_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm3.5-9c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zm-7 0c.83 0 1.5-.67 1.5-1.5S9.33 8 8.5 8 7 8.67 7 9.5 7.67 11 8.5 11zm3.5 6.5c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5z"/></svg>';
  try { box.open = localStorage.getItem('remote.cm') === '1'; } catch (e) {}
  box.addEventListener('toggle', function () {
    try { localStorage.setItem('remote.cm', box.open ? '1' : ''); } catch (e) {}
    if (box.open && !loaded) load();
  });
  hooks.unavailable.push(function () { box.hidden = true; });
  // 电视刚登录上 (多半是在手机上点了下面的「用手机登录」): 重新读, 表单里换成真实的收藏与评分
  hooks.login.push(function () { if (box.open) load(); else loaded = false; });
  hooks.render.push(function (s) {
    box.hidden = false;
    if (s.subjectId !== subject) {
      subject = s.subjectId;
      loaded = false;
      sum.textContent = '';
      if (box.open) load(); else body.innerHTML = '';
    }
  });
  function typeLabel(t) {
    for (var i = 0; i < TYPES.length; i++) if (TYPES[i][0] === t) return TYPES[i][1];
    return t;
  }
  // quiet: 操作完重读时不先清成「正在读取」, 免得整块塌下去再撑开
  function load(quiet) {
    loaded = true;
    if (!quiet) body.innerHTML = '<p class="hint">正在读取…</p>';
    fetch('api/player/review').then(function (r) { return r.json(); }).then(render).catch(fail);
  }
  function render(d) {
    if (!d.ok) { body.innerHTML = '<p class="hint">' + esc(d.message) + '</p>'; return; }
    sum.textContent = typeLabel(d.collection) + (d.score ? ' · ' + d.score + ' 分' : '');
    // 没登录时收藏、评分、评论都做不了, 只放登录入口 (登录上以后 hooks.login 会重读)
    if (!d.loggedIn) {
      body.innerHTML = '<div class="cm-login"><p class="hint cm-warn">还没登录：收藏、评分和评论都要先登录</p>' +
        '<button type="button" class="primary wide" data-login="1">用手机登录 Bangumi</button></div>';
      return;
    }
    // 重画会冲掉正在写的本集评论, 先存下来
    var old = body.querySelector('#cm-post textarea'), draft = old ? old.value : '';
    var collected = d.collection !== 'NOT_COLLECTED', off = collected ? '' : ' disabled';
    var seg = '', stars = '';
    for (var i = 1; i < TYPES.length; i++) {
      seg += '<button type="button" data-ctype="' + TYPES[i][0] + '"' + (TYPES[i][0] === d.collection ? ' class="on"' : '') + '>' + TYPES[i][1] + '</button>';
    }
    for (var j = 0; j < 10; j++) stars += STAR;
    body.innerHTML =
      '<div class="cm-sec"><div class="cm-h"><span>收藏</span>' +
        (collected ? '<button type="button" class="cm-link" data-uncollect="1">取消收藏</button>' : '') + '</div>' +
        '<div class="seg cm-types">' + seg + '</div></div>' +
      '<form id="cm-rate" class="cm-sec' + (collected ? '' : ' off') + '">' +
        '<div class="cm-h"><span>我的评分</span><button type="button" class="cm-link" id="cm-clear">清除</button></div>' +
        '<div class="cm-score" id="cm-score"><b></b><span></span></div>' +
        '<div class="cm-stars" id="cm-stars" role="slider" tabindex="0" aria-label="评分" aria-valuemin="0" aria-valuemax="10">' + stars + '</div>' +
        '<input type="hidden" name="score" value="0">' +
        (collected ? '' : '<p class="hint cm-tip">先在上面选个收藏状态，才能评分</p>') +
        '<textarea name="comment" rows="3" placeholder="写几句短评（可留空）"' + off + '>' + esc(d.comment) + '</textarea>' +
        '<div class="cm-foot"><label class="toggle"><input type="checkbox" name="private" value="1"' + (d.private ? ' checked' : '') + off + '>仅自己可见</label>' +
        '<button type="submit" class="primary"' + off + '>保存</button></div></form>' +
      '<form id="cm-post" class="cm-sec"><div class="cm-h"><span>本集评论' + (d.episode ? '<small>' + esc(d.episode) + '</small>' : '') + '</span></div>' +
        '<textarea name="text" rows="3" placeholder="说点什么"></textarea>' +
        '<div class="cm-preview" id="cm-preview" hidden></div>' +
        '<div class="cm-foot"><button type="button" class="cm-emo" data-emo="1">' + EMO_ICON + '表情</button>' +
        '<button type="submit" class="primary">发表</button></div>' +
        '<div class="cm-picker" id="cm-picker" hidden></div></form>';
    if (draft) body.querySelector('#cm-post textarea').value = draft;
    paintPicker();
    preview();
    loadStickers();
    setScore(collected ? d.score : 0);
  }
  function loadStickers() {
    if (stickers) return;
    stickers = [];
    fetch('api/stickers').then(function (r) { return r.ok ? r.json() : null; }).then(function (d) {
      if (!d || !d.packs || !d.packs.length) { stickersFailed(); return; }
      stickers = d.packs;
      var toks = [];
      stickers.forEach(function (p) { p.items.forEach(function (it) { stickerUrl[it[0]] = it[1]; toks.push(it[0]); }); });
      // 长的在前: 正则按顺序试, 免得短代码抢先吃掉长代码的一截
      toks.sort(function (a, b) { return b.length - a.length; });
      stickerRe = toks.length ? new RegExp(toks.map(reEsc).join('|'), 'g') : null;
      paintPicker();
      preview();
    }).catch(stickersFailed);
  }
  // 没拉到: 下次打开面板再拉
  function stickersFailed() {
    stickers = null;
    var box = document.getElementById('cm-picker');
    if (box && emoOpen) box.innerHTML = '<p class="hint cm-pk-msg">读取表情失败，收起再打开试试</p>';
  }
  function reEsc(s) { return s.replace(/[.*+?^{}()|[\]\\\/]/g, function (c) { return '\\' + c; }); }
  function paintPicker() {
    var box = document.getElementById('cm-picker'), btn = body.querySelector('[data-emo]');
    if (!box) return;
    box.hidden = !emoOpen;
    if (btn) btn.classList.toggle('on', emoOpen);
    if (!emoOpen) return;
    if (!stickers || !stickers.length) {
      box.innerHTML = '<p class="hint cm-pk-msg">正在读取表情…</p>';
      loadStickers();
      return;
    }
    if (pack >= stickers.length) pack = 0;
    box.innerHTML = '<div class="cm-packs">' + stickers.map(function (x, i) {
        return '<button type="button" data-pack="' + i + '"' + (i === pack ? ' class="on"' : '') + '>' + esc(x.name) + '</button>';
      }).join('') + '</div><div class="cm-grid">' + stickers[pack].items.map(function (it) {
        return '<button type="button" data-stk="' + esc(it[0]) + '" title="' + esc(it[0]) + '"><img src="' + esc(it[1]) + '" alt="' +
          esc(it[0]) + '" loading="lazy" referrerpolicy="no-referrer"></button>';
      }).join('') + '</div>';
    // 重画后分组横排回到最左, 选中的那个可能在屏幕外 (最后一组「颜文字」在手机宽度上就露一半), 挪进来
    var row = box.querySelector('.cm-packs'), on = row.querySelector('.on');
    if (on && on.offsetLeft + on.offsetWidth > row.clientWidth) row.scrollLeft = on.offsetLeft - 8;
  }
  // 正文里认出表情代码时, 在输入框下面按电视上的样子预览一遍 (代码换成图); 一枚都没有就不占地方
  function preview() {
    var ta = body.querySelector('#cm-post textarea'), box = document.getElementById('cm-preview');
    if (!ta || !box) return;
    var t = ta.value, out = '', last = 0, n = 0, m;
    if (stickerRe && t) {
      stickerRe.lastIndex = 0;
      while ((m = stickerRe.exec(t))) {
        out += esc(t.slice(last, m.index)) + '<img src="' + esc(stickerUrl[m[0]]) + '" alt="' + esc(m[0]) + '" referrerpolicy="no-referrer">';
        last = m.index + m[0].length;
        n++;
      }
    }
    box.hidden = n === 0;
    if (n) box.innerHTML = '<small>预览</small>' + out + esc(t.slice(last));
  }
  // 插在光标处 (没点过输入框就是末尾). 不去聚焦输入框: 手机上一聚焦就弹键盘, 把表情面板顶走.
  // 光标位置自己记 (caret): 点表情时输入框多半已经失焦, 而失焦的输入框改过 value 之后选区读出来是 0 (Chromium 实测),
  // 连插两枚第二枚就跑到最前面. 离开输入框那一刻 (focusout) 记下的才准; 输入框还聚焦着 (Safari 点按钮不抢焦点) 就读实时的
  var caret = null;
  function rememberCaret(e) {
    var t = e.target;
    if (t && t.name === 'text' && t.closest && t.closest('#cm-post')) caret = [t.selectionStart, t.selectionEnd];
  }
  ['focusout', 'keyup', 'mouseup', 'select', 'input'].forEach(function (k) { body.addEventListener(k, rememberCaret); });
  function insertSticker(tok) {
    var ta = body.querySelector('#cm-post textarea');
    if (!ta) return;
    var len = ta.value.length, live = document.activeElement === ta;
    var s = live ? ta.selectionStart : (caret ? caret[0] : len), e = live ? ta.selectionEnd : (caret ? caret[1] : len);
    s = Math.min(s, len);
    e = Math.min(Math.max(e, s), len);
    ta.value = ta.value.slice(0, s) + tok + ta.value.slice(e);
    caret = [s + tok.length, s + tok.length];
    if (live) { try { ta.setSelectionRange(caret[0], caret[1]); } catch (err) {} }
    preview();
  }
  // 分数 → 星星、大号数字、评价词、隐藏的表单项一起变
  function setScore(n) {
    var form = document.getElementById('cm-rate');
    if (!form) return;
    form.elements.score.value = String(n);
    var st = document.getElementById('cm-stars'), svgs = st.children;
    for (var i = 0; i < svgs.length; i++) svgs[i].classList.toggle('on', i < n);
    st.setAttribute('aria-valuenow', String(n));
    var sc = document.getElementById('cm-score');
    sc.className = 'cm-score' + (n ? (n === 1 || n === 10 ? ' warn' : '') : ' none');
    sc.firstChild.textContent = n ? String(n) : '—';
    sc.lastChild.textContent = n ? WORDS[n] : (form.classList.contains('off') ? '' : '点星星打分，也可以按住左右滑');
    document.getElementById('cm-clear').hidden = !n;
  }
  function scoreAt(st, x) {
    var r = st.getBoundingClientRect();
    return Math.max(1, Math.min(10, Math.ceil((x - r.left) / r.width * 10)));
  }
  // 星星: 点一下定分, 按住左右滑实时改分. 星星行是 touch-action: pan-y, 竖着划照常滚页面 —— 那时浏览器发 pointercancel,
  // 分数退回按下之前, 免得滚页面时手指落在星星上就把分改了. 所以按下那一刻不改分, 抬起或横着滑开了才改
  body.addEventListener('pointerdown', function (e) {
    var st = e.target.closest && e.target.closest('#cm-stars');
    if (!st) return;
    drag = { st: st, from: Number(document.getElementById('cm-rate').elements.score.value), x: e.clientX, moved: false };
    try { st.setPointerCapture(e.pointerId); } catch (err) {}
  });
  body.addEventListener('pointermove', function (e) {
    if (!drag || (!drag.moved && Math.abs(e.clientX - drag.x) < 6)) return;
    drag.moved = true;
    setScore(scoreAt(drag.st, e.clientX));
  });
  body.addEventListener('pointerup', function (e) {
    if (!drag) return;
    setScore(scoreAt(drag.st, e.clientX));
    drag = null;
  });
  body.addEventListener('pointercancel', function () {
    if (!drag) return;
    setScore(drag.from);
    drag = null;
  });
  body.addEventListener('input', function (e) {
    if (e.target.name === 'text' && e.target.closest('#cm-post')) preview();
  });
  body.addEventListener('keydown', function (e) {
    if (e.target.id !== 'cm-stars') return;
    var v = Number(document.getElementById('cm-rate').elements.score.value);
    if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') setScore(Math.max(0, v - 1));
    else if (e.key === 'ArrowRight' || e.key === 'ArrowUp') setScore(Math.min(10, v + 1));
    else return;
    e.preventDefault();
  });
  body.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('button');
    if (!b) return;
    if (b.id === 'cm-clear') { setScore(0); return; }
    if (b.hasAttribute('data-emo')) { emoOpen = !emoOpen; paintPicker(); return; }
    if (b.hasAttribute('data-pack')) {
      pack = Number(b.getAttribute('data-pack')) || 0;
      try { localStorage.setItem('remote.stickerPack', String(pack)); } catch (err) {}
      paintPicker();
      return;
    }
    if (b.hasAttribute('data-stk')) { insertSticker(b.getAttribute('data-stk')); return; }
    var type = b.getAttribute('data-ctype');
    if (b.hasAttribute('data-uncollect')) {
      if (!confirm('取消收藏？这会清除你的观看进度和评价，无法撤销。')) return;
      type = 'NOT_COLLECTED';
    } else if (!type || b.classList.contains('on')) return;
    // 选中态先挪过去, 手感跟得上; 结果回来后重读, 失败了也会被重读纠正
    var all = body.querySelectorAll('.cm-types button');
    for (var i = 0; i < all.length; i++) { all[i].classList.toggle('on', all[i] === b); all[i].disabled = true; }
    post('api/player/review/collect', { type: type }).then(function (r) {
      toast(r.message);
      load(true);
    }).catch(function () { fail(); load(true); });
  });
  body.addEventListener('submit', function (e) {
    e.preventDefault();
    var form = e.target, btn = form.querySelector('button[type="submit"]');
    btn.disabled = true;
    var path = form.id === 'cm-rate' ? 'api/player/review/rate' : 'api/player/comment';
    post(path, new FormData(form)).then(function (r) {
      btn.disabled = false;
      toast(r.message);
      if (!r.ok) return;
      if (form.id === 'cm-post') { form.elements.text.value = ''; caret = null; preview(); }
      else load(true);
    }).catch(function () { btn.disabled = false; fail(); });
  });
})();
""".trimIndent()

/**
 * 「设置」标签顶上的账号卡片 (见 RemoteAccount): 电视登录的是谁; 没登录时「用手机登录」—— 电视向服务器要来 Bangumi 授权链接,
 * 手机打开, 授完权电视自己就登录好了. 等授权期间每 2 秒问一次, 其余时候只在打开这个标签时读一次.
 * 登录按钮 (`data-login`) 在评论与评分区也有一个, 点击统一在这里处理.
 */
private val ACCOUNT_SCRIPT = """
(function () {
  var box = document.getElementById('set-account');
  var hooks = window.remoteHooks;
  var last = '', timer = null, waiting = false, wasIn = null;
  function load() {
    clearTimeout(timer);
    fetch('api/account').then(function (r) { return r.json(); }).then(render).catch(function () {});
  }
  window.loadAccount = load;
  function avatar(d) {
    if (d.avatar) return '<img src="' + esc(d.avatar) + '" alt="" referrerpolicy="no-referrer">';
    return '<div class="acct-ph">' + esc((d.name || '?').charAt(0)) + '</div>';
  }
  function render(d) {
    if (!d.ok) return;
    var l = d.login || { state: 'idle' }, full = !!(d.loggedIn && d.bangumi);
    // 刚登录上 (手机这边发起的, 或者电视上自己登的): 让评论与评分区重新读一次
    if (wasIn === false && full) hooks.login.forEach(function (h) { h(); });
    wasIn = full;
    waiting = l.state === 'waiting';
    var h = '<div class="card set-card"><div class="set-title">账号</div>';
    if (d.loggedIn) {
      h += '<div class="acct">' + avatar(d) + '<div><div class="acct-name">' + esc(d.name || '已登录') + '</div><div class="acct-sub">' +
        (d.bangumi ? '已连接 Bangumi' + (d.bgmName ? '（' + esc(d.bgmName) + '）' : '') : '还没连接 Bangumi：收藏同步、评分、评论要连接后才能用') +
        '</div></div></div>';
    } else if (d.offline) {
      h += '<p class="hint">电视现在连不上 Animeko 服务器，确认不了登录状态，稍后再看。</p>';
    } else {
      h += '<p class="hint">电视还没登录。登录后收藏、看过的进度、评分和评论都会同步到你的 Bangumi 账号。</p>';
    }
    if (waiting) {
      h += '<div class="acct-wait"><div class="now-status busy"><b>等待授权</b><span>在打开的 Bangumi 页面里同意授权，完成后回到这里就行</span></div>' +
        (l.url ? '<p class="hint">授权页没打开？<a href="' + esc(l.url) + '" target="_blank" rel="noopener">点这里打开</a></p>'
          : '<p class="hint">正在向服务器要授权链接…</p>') +
        '<div class="row"><button type="button" class="ghost" data-acct="cancel">取消登录</button></div></div>';
    } else if (!full && !d.offline) {
      if (l.state === 'failed') h += '<div class="now-status error"><b>上次登录没有完成</b><span>' + esc(l.message) + '</span></div>';
      h += '<div class="row"><button type="button" class="primary" data-login="1">' + (d.loggedIn ? '用手机连接 Bangumi' : '用手机登录 Bangumi') +
        '</button></div><p class="hint">在手机上打开 Bangumi 授权页，授权完电视就登录好了，电视上什么都不用做。</p>';
    }
    h += '</div>';
    if (h !== last) { box.innerHTML = h; last = h; }
    if (waiting) timer = setTimeout(load, 2000);
  }
  // 登录按钮 (账号卡片、评论与评分区): 点下去当场先开一个空白页, 等电视要来链接再让它跳过去 ——
  // 等请求回来再开新页面会被浏览器当成弹窗拦掉. 开不了新页面 (有的内置浏览器) 就在本页跳, 授权完按返回回来
  function startLogin(btn) {
    var w = null;
    try { w = window.open('', '_blank'); } catch (e) {}
    btn.disabled = true;
    post('api/account/login', {}).then(function (r) {
      btn.disabled = false;
      if (!r.ok) {
        if (w) w.close();
        toast(r.message);
      } else if (w) {
        w.location.href = r.url;
      } else {
        location.href = r.url;
      }
      load();
    }).catch(function () {
      btn.disabled = false;
      if (w) w.close();
      fail();
    });
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-login]');
    if (b) { if (!b.disabled) startLogin(b); return; }
    if (e.target.closest('[data-acct="cancel"]')) {
      post('api/account/login/cancel', {}).then(function (r) { toast(r.message); load(); }).catch(fail);
    }
  });
  // 从授权页切回来: 马上问一次, 不等下一轮 (后台标签页里的定时器会被浏览器压着)
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && (waiting || !box.closest('.tab').hidden)) load();
  });
})();
""".trimIndent()
