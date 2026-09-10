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
 * Web 控制台 (网页里标题叫「Animeko 控制台」; 手机、电脑的浏览器都能开, 原叫「手机遥控 / 控制中心」) 的网页: 底部四个标签 (搜索 / 播放器 / 缓存 / 设置) 的单页应用; 搜索标签顶上再分「搜索 / 结果」两页,
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
    /** 跟电视主题色生成的配色 (见 [RemoteTheme]), 接在默认样式后面盖住默认值; 空 = 用默认的 */
    themeCss: String = "",
): String =
    """
    <!doctype html>
    <html lang="zh-CN">
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="color-scheme" content="light dark">
    <meta name="theme-color" content="#f7f2fa">
    <meta name="referrer" content="no-referrer">
    <title>Animeko 控制台</title>
    <script>
    """.trimIndent() + "\n" + THEME_HEAD_SCRIPT + "\n" + """
    </script>
    <style>
    """.trimIndent() + "\n" + STYLE + "\n" + themeCss + """
    </style>
    </head>
    <body>
    <header><span>Animeko 控制台</span><button type="button" id="help-btn" aria-label="使用说明" title="使用说明">?<span class="help-dot" hidden></span></button></header>
    <div id="tv-state" class="tv-state" hidden></div>
    <div id="ld-bar" class="ld-bar" hidden><div class="ld-row"><span>电视上的二维码仍在显示</span><button type="button" class="ld-x" data-ld="hide" aria-label="收起" title="收起">×</button></div><div class="ld-row ld-acts"><button type="button" class="ld-close" data-ld="close">关闭电视上的二维码</button><label class="ld-mode">以后：<select data-ld-mode></select></label></div></div>
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
    <div class="dm-wrap"><form class="dm-form" id="dm-send"><input type="text" name="text" maxlength="100" autocomplete="off" placeholder="发一条弹幕（在电视当前进度）"><button type="submit" class="primary" aria-label="发送" title="发送"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3.4 20.4 20.85 12.92a1 1 0 0 0 0-1.84L3.4 3.6a.99.99 0 0 0-1.39.91L2 9.12c0 .5.37.93.87.99L17 12 2.87 13.88c-.5.07-.87.5-.87 1l.01 4.61c0 .71.73 1.2 1.39.91z"/></svg></button></form>
    <div id="dm-body"></div><div id="dm-match"></div></div></details>
    <details class="card req" id="tr-box" hidden><summary>音轨与字幕</summary><div class="dm-wrap" id="tr-body"></div></details>
    <details class="card req" id="cm-box" hidden><summary>评论与评分<small id="cm-sum"></small></summary><div class="dm-wrap" id="cm-body"></div></details>
    """.trimIndent() + "\n" + requestSectionHtml + "\n" + """
    <div id="player-chips"></div>
    <div id="player-filters"></div>
    <div id="player-sources"></div>
    </section>
    <section class="tab" id="tab-cache" hidden>
    <div id="cl-sum"></div>
    <div id="cl-list"></div>
    <div id="cl-pick"></div>
    </section>
    <section class="tab" id="tab-settings" hidden>
    <div class="seg" id="set-seg"><button type="button" data-ssub="general" class="on">常规</button><button type="button" data-ssub="sources">数据源</button></div>
    <div id="set-general">
    <div id="set-account"></div>
    <div id="set-history"></div>
    <div id="set-look"></div>
    <div id="set-front"></div>
    <div id="set-keep"></div>
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
    <div class="sheet-head"><button type="button" class="sheet-btn" id="cache-back" hidden aria-label="返回" title="返回"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg></button>
    <div class="sheet-title" id="cache-title">缓存</div><button type="button" class="sheet-btn" id="cache-close" aria-label="关闭" title="关闭"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button></div>
    <div class="sheet-body" id="cache-body"></div>
    </div>
    <div id="hist-sheet" class="sheet" hidden>
    <div class="sheet-head"><div class="sheet-title">播放记录</div><button type="button" class="sheet-btn" id="hist-close" aria-label="关闭" title="关闭"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button></div>
    <div class="sheet-body" id="hist-body"></div>
    </div>
    <div id="pick-sheet" class="sheet" hidden>
    <div class="sheet-head"><div class="sheet-title">挑番缓存</div><button type="button" class="sheet-btn" id="pick-close" aria-label="关闭" title="关闭"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button></div>
    <div class="sheet-body"><div class="seg" id="pick-seg"><button type="button" data-ptype="DOING" class="on">在看</button><button type="button" data-ptype="WISH">想看</button></div><div id="pick-body"></div></div>
    </div>
    <div id="help-sheet" class="sheet" hidden>
    <div class="sheet-head"><div class="sheet-title" id="help-title">使用说明</div><button type="button" class="sheet-btn" id="help-close" aria-label="关闭" title="关闭"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button></div>
    <div class="sheet-body" id="help-body"></div>
    </div>
    <div id="toast"></div>
    <div id="sel-bar" hidden><button type="button" data-sel="cancel">取消</button><span class="sel-n"></span><button type="button" data-sel="all">全选</button><button type="button" class="ic sel-del" data-sel="del">删除</button></div>
    <nav class="tabbar">
    <button data-tab="search"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg><span class="tl">搜索</span></button>
    <button data-tab="player"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M10 16.5l6-4.5-6-4.5v9zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z"/><path class="f" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"/></svg><span class="tl">播放器</span></button>
    <button data-tab="cache"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M19 9h-4V3H9v6H5l7 7 7-7zm-8 2V5h2v6h1.17L12 13.17 9.83 11H11zm-6 7h14v2H5z"/><path class="f" d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg><span class="tl">缓存</span></button>
    <button data-tab="settings"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M19.43 12.98c.04-.32.07-.64.07-.98 0-.34-.03-.66-.07-.98l2.11-1.65c.19-.15.24-.42.12-.64l-2-3.46c-.09-.16-.26-.25-.44-.25-.06 0-.12.01-.17.03l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65C14.46 2.18 14.25 2 14 2h-4c-.25 0-.46.18-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1c-.06-.02-.12-.03-.18-.03-.17 0-.34.09-.43.25l-2 3.46c-.13.22-.07.49.12.64l2.11 1.65c-.04.32-.07.65-.07.98 0 .33.03.66.07.98l-2.11 1.65c-.19.15-.24.42-.12.64l2 3.46c.09.16.26.25.44.25.06 0 .12-.01.17-.03l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.06.02.12.03.18.03.17 0 .34-.09.43-.25l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.65zm-1.98-1.71c.04.31.05.52.05.73 0 .21-.02.43-.05.73l-.14 1.13.89.7 1.08.84-.7 1.21-1.27-.51-1.04-.42-.9.68c-.43.32-.84.56-1.25.73l-1.06.43-.16 1.13-.2 1.35h-1.4l-.19-1.35-.16-1.13-1.06-.43c-.43-.18-.83-.41-1.23-.71l-.91-.7-1.06.43-1.27.51-.7-1.21 1.08-.84.89-.7-.14-1.13c-.03-.31-.05-.54-.05-.74s.02-.43.05-.73l.14-1.13-.89-.7-1.08-.84.7-1.21 1.27.51 1.04.42.9-.68c.43-.32.84-.56 1.25-.73l1.06-.43.16-1.13.2-1.35h1.39l.19 1.35.16 1.13 1.06.43c.43.18.83.41 1.23.71l.91.7 1.06-.43 1.27-.51.7 1.21-1.07.85-.89.7.14 1.13zM12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0 6c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"/><path class="f" d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg><span class="tl">设置</span></button>
    </nav>
    <script>
    var INITIAL_TAB = '$initialTab';
    """.trimIndent() + "\n" + SCRIPT + "\n" + REQUEST_SCRIPT + "\n" + CONTROL_SCRIPT + "\n" + DANMAKU_SCRIPT + "\n" + REVIEW_SCRIPT + "\n" + CACHE_SCRIPT + "\n" + CACHE_LIST_SCRIPT + "\n" + SOURCES_SCRIPT +"\n" + SUBS_SCRIPT + "\n" + SETTINGS_SCRIPT + "\n" + LOOK_SCRIPT + "\n" + LOGS_SCRIPT + "\n" + ACCOUNT_SCRIPT + "\n" + HISTORY_SCRIPT + "\n" + HELP_SCRIPT + "\n" + PICK_SCRIPT + "\n" + """
    </script>
    </body>
    </html>
    """.trimIndent()

private val STYLE = """
/* 配色全走变量: 这一组是浅色, 深色在 [data-theme="dark"] 里覆盖. 自动 / 浅色 / 深色由 <head> 里那段脚本定 (见 THEME_HEAD_SCRIPT) */
:root {
  color-scheme: light;
  --p: #6750a4; --on-p: #fff; --p-soft: #efe7fb; --seg-on: #fff;
  --bg: #f7f2fa; --card: #fff; --raised: #fff; --field: #fff; --soft: #f6f2fa; --soft2: #fcfbfd; --disabled: #f4f2f6;
  --fg: #1c1b1f; --sub: #49454f; --mute: #79747e; --chip: #e7e0ec; --on-chip: #1d192b;
  --outline: #cac4d0; --line: #f0edf2; --line2: #e6e0e9;
  --ok: #1e7b34; --ok-bg: #d8f3dc; --ok-fg: #1b5e20; --warn-bg: #fff0c2; --warn-fg: #6b4e00;
  --err: #b3261e; --err-bg: #ffdad6; --err-fg: #410002; --del: #b3261e;
  --shadow: rgba(0,0,0,.08); --shadow-sm: rgba(0,0,0,.12); --shadow-lg: rgba(0,0,0,.16);
  --toast-bg: rgba(28,27,31,.92); --toast-fg: #fff; --fade: rgba(247,242,250,0);
  --now-glow: rgba(255,255,255,.55);
  --now-fg: #1c1b1f; --now-fg2: rgba(28,27,31,.76); --now-fg3: rgba(28,27,31,.62); --now-pill: rgba(255,255,255,.58); --now-track: rgba(28,27,31,.16);
  --now-accent-pill: rgba(239,231,251,.84);
}
/* 深色: Material 3 深色基线 (主色 #d0bcff, 主色上的字 #381e72) */
:root[data-theme="dark"] {
  color-scheme: dark;
  --p: #d0bcff; --on-p: #381e72; --p-soft: #4a4458; --seg-on: #4a4458;
  --bg: #141218; --card: #211f26; --raised: #2b2930; --field: #1d1b20; --soft: #2b2930; --soft2: #1d1b20; --disabled: #2b2930;
  --fg: #e6e0e9; --sub: #cac4d0; --mute: #938f99; --chip: #36343b; --on-chip: #e6e0e9;
  --outline: #56525c; --line: #2f2d35; --line2: #36343b;
  --ok: #86d993; --ok-bg: #1e3a24; --ok-fg: #b7e4c0; --warn-bg: #3d3200; --warn-fg: #f3d77f;
  /* --del: 滑动删除那颗实心按钮的底色 (白字). 深色下不能用 --err —— 那是给文字用的浅粉 (#f2b8b5), 当底色配白字又淡又糊 */
  --err: #f2b8b5; --err-bg: #8c1d18; --err-fg: #ffdad6; --del: #d32f2f;
  --shadow: rgba(0,0,0,.4); --shadow-sm: rgba(0,0,0,.3); --shadow-lg: rgba(0,0,0,.5);
  --toast-bg: rgba(230,224,233,.95); --toast-fg: #1c1b1f; --fade: rgba(20,18,24,0);
  --now-glow: rgba(0,0,0,.55);
  --now-fg: #f4eff4; --now-fg2: rgba(244,239,244,.78); --now-fg3: rgba(244,239,244,.64); --now-pill: rgba(255,255,255,.12); --now-track: rgba(255,255,255,.22);
  --now-accent-pill: rgba(56,30,114,.55);
}
* { box-sizing: border-box; }
body { font-family: system-ui, sans-serif; margin: 0; background: var(--bg); color: var(--fg); -webkit-tap-highlight-color: transparent; }
header { padding: 16px 16px 4px; font-size: 20px; font-weight: 700; display: flex; align-items: center; gap: 8px; }
/* 右上角「?」= 当前标签页的使用说明 (见 HELP_SCRIPT); 没打开过时带小红点 */
#help-btn { position: relative; flex: none; margin-left: auto; width: 32px; height: 32px; padding: 0; border-radius: 16px;
  background: var(--chip); color: var(--on-chip); font-size: 17px; font-weight: 700; line-height: 32px; text-align: center; }
.help-dot { position: absolute; top: 0; right: 0; width: 9px; height: 9px; border-radius: 50%; background: var(--err); box-shadow: 0 0 0 2px var(--bg); }
#help-sheet { z-index: 54; }
/* 缓存标签最下面「挑番缓存」(见 PICK_SCRIPT): 在看 / 想看的番, 行同搜索结果; 有新集的那句用主题色 */
#cl-pick { margin-top: 14px; }
#pick-seg { margin-bottom: 10px; }
.pick-item .m.pick-new { color: var(--p); font-weight: 600; }
/* 左滑「收藏」的小菜单挂在 body 上: 要盖过全屏面板 (.sheet 50 / 缓存面板 52), 否则在「挑番缓存」面板里弹出来被压在下面, 看着像没反应 */
.ep-menu.coll-menu { z-index: 58; }
.help-card { margin-top: 12px; }
.help-list { margin: 8px 0 0; padding-left: 18px; font-size: 14px; line-height: 1.6; }
.help-list li + li { margin-top: 4px; }
.tab { padding: 8px 16px calc(150px + env(safe-area-inset-bottom)); }
h2 { font-size: 14px; font-weight: 600; color: var(--sub); margin: 22px 0 8px; }
h2 small { font-weight: 400; color: var(--mute); }
p.hint { color: var(--mute); font-size: 13px; margin: 8px 0; }
input[type=text], input[type=password], input[type=email], textarea { width: 100%; font: inherit; font-size: 16px; padding: 12px 14px; border: 1px solid var(--outline); border-radius: 12px; background: var(--field); color: var(--fg); }
input[type=text]:focus, input[type=password]:focus, input[type=email]:focus, textarea:focus { outline: 2px solid var(--p); border-color: transparent; }
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
/* 播放卡: 剧名下面一行是第几集; 资源名退成次要信息, 只占一行 (整季合集的名字很长, 完整的在 title 里) */
.now-ep { font-size: 15px; font-weight: 600; margin-top: 2px; color: var(--sub); }
/* 第几集那一行兼做选集: 字 + 箭头, 点了弹自己画的选集列表 (.ep-menu, 见 SCRIPT 的 openEpMenu). 字号 / 颜色沿用 .now-ep */
.now-ep-pick { display: inline-flex; align-items: baseline; gap: 5px; max-width: 100%; padding: 0; background: none; text-align: left; cursor: pointer; }
/* 卡片里的按钮默认去掉文字光晕, 这一行是文字, 照样要 */
.now-card.cv button.now-ep-pick { text-shadow: 0 0 1px var(--now-glow), 0 0 8px var(--now-glow); }
.now-ep-t { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 展开 / 下拉的箭头 (全页统一): 原来是字体里的 ▾ ▸, 11~14px 小得看不清, 换成 Material expand_more 的图形,
   用 mask 画成当前颜色, 各处给 18~22px. 用法: content 空 + background 着色 + mask: var(--chev) */
:root { --chev: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M16.59 8.59 12 13.17 7.41 8.59 6 10l6 6 6-6z'/%3E%3C/svg%3E"); }
.now-ep-caret { flex: none; align-self: center; width: 20px; height: 20px; margin-left: -2px; background: var(--p);
  -webkit-mask: var(--chev) center / contain no-repeat; mask: var(--chev) center / contain no-repeat; }
/* 看过的这一集: 同选集列表里的 ✓ */
.now-ep-seen { color: var(--ok); margin-right: 4px; }
/* 选集列表: 宽度按最长那一行 (max-content, 不折行), 上限屏宽减两边 16px, 超过上限的那几行才折; 挂在 body 上, 高过屏幕就滚 */
.ep-menu { position: absolute; z-index: 45; box-sizing: border-box; width: max-content; max-width: calc(100vw - 32px); overflow-y: auto;
  padding: 6px; border-radius: 14px; background: var(--card); box-shadow: 0 10px 30px rgba(0,0,0,.25), 0 0 0 1px var(--line2); }
.ep-opt { display: flex; align-items: baseline; gap: 8px; width: 100%; padding: 10px 12px; border-radius: 10px; background: none;
  color: var(--fg); font-size: 15px; text-align: left; }
.ep-opt.cur { background: var(--p-soft); color: var(--p); font-weight: 700; }
.ep-mark { flex: none; width: 1em; color: var(--ok); }
.ep-name { line-height: 1.35; }
@media (hover: hover) and (pointer: fine) { .ep-opt:not(.cur):hover { background: var(--line); } }
.now-card .now-src { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; word-break: normal; }
/* 点播放卡的数据源胶囊后, 滚到的那一条底色亮一下 (见 SCRIPT 里的点击处理与 groupHtml) */
.item { transition: background-color .5s; }
.item.flash { background-color: var(--p-soft); }
.now-label { font-size: 12px; color: var(--mute); }
.now-srcname { font-size: 14px; font-weight: 700; color: var(--on-p); background: var(--p); padding: 3px 12px; border-radius: 12px; }
.now-meta { font-size: 13px; color: var(--sub); }
.chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
/* 尺寸三档 (全页统一): 大按钮与输入框 46px / 行内与标题行的小动作按钮 34px / 选择胶囊 (筛选、排序、贴纸包、表单切换) 32px */
.chip { box-sizing: border-box; height: 32px; font-size: 14px; line-height: 1.3; padding: 0 14px; border-radius: 16px; background: var(--chip); color: var(--on-chip); }
.chip.failed, .chip.captcha, .chip.limited { background: var(--err-bg); color: var(--err-fg); }
.chip.loading { opacity: .6; }
.chip.on { background: var(--p); color: var(--on-p); opacity: 1; }
.chip.cur { background: var(--p-soft); color: var(--p); font-weight: 700; box-shadow: inset 0 0 0 2px var(--p); opacity: 1; }
.chip.on.cur { background: var(--p); color: var(--on-p); box-shadow: none; }
.list { display: flex; flex-direction: column; gap: 8px; }
.item { display: block; text-align: left; width: 100%; background: var(--card); color: var(--fg); border-radius: 12px; padding: 12px 14px; box-shadow: 0 1px 3px var(--shadow); position: relative; }
.item .t { display: block; font-size: 14px; line-height: 1.4; overflow-wrap: anywhere; }
.item .m { display: block; font-size: 12px; color: var(--mute); margin-top: 4px; }
.item.sel { outline: 2px solid var(--p); }
.item.sel .t { padding-right: 68px; }
.item .badge { position: absolute; top: 10px; right: 10px; font-size: 11px; color: var(--on-p); background: var(--p); padding: 2px 8px; border-radius: 10px; }
.empty { text-align: center; padding: 40px 8px; color: var(--sub); }
.req { margin-top: 12px; padding: 0; }
.req summary { padding: 14px 16px; font-weight: 600; cursor: pointer; list-style: none; }
.req summary::-webkit-details-marker { display: none; }
/* 可收起的卡片 (播放信息 / 弹幕 / 音轨与字幕 / 评论与评分 / 编辑查询): 原来标题行没有任何箭头, 看不出能展开;
   右端一个箭头, 展开时朝上 */
.req > summary { display: flex; align-items: baseline; }
.req > summary::after { content: ""; flex: none; align-self: center; margin-left: auto; width: 22px; height: 22px;
  background: var(--mute); -webkit-mask: var(--chev) center / contain no-repeat; mask: var(--chev) center / contain no-repeat;
  transition: transform .15s; }
.req[open] > summary::after { transform: rotate(180deg); }
.req summary small { font-weight: 400; color: var(--mute); margin-left: 6px; }
.req form { padding: 0 16px 16px; }
.f { display: block; margin-top: 12px; }
.f > span { display: block; font-size: 13px; color: var(--sub); margin-bottom: 6px; }
.f > em { display: block; font-style: normal; font-size: 12px; color: var(--mute); margin-top: 4px; }
.row { display: flex; gap: 10px; margin-top: 16px; }
.row > * { flex: 1; }
.ghost { background: var(--chip); color: var(--on-chip); font-weight: 600; padding: 13px 12px; border-radius: 14px; font-size: 15px; }
/* 图标按钮 (window.ICONS): .ic = 图标 + 文字 (不那么通用的动作, 字留着说清楚); .icb = 只有图标, 给列表行里反复出现的
   通用动作 (暂停 / 继续 / 删除 / 上移 / 下移 / 关闭), 必须带 aria-label + title */
.ic { display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
.ic > svg, .icb > svg { flex: none; width: 18px; height: 18px; fill: currentColor; }
.primary.ic > svg, .ghost.ic > svg { width: 20px; height: 20px; }
.icb { display: inline-flex; align-items: center; justify-content: center; padding: 0 !important; width: 38px; height: 34px; }
/* 原来用字符凑的 ‹ ✕ × ▶ 换成 SVG: 字体里的这些符号各平台大小 / 基线不一 (iOS 上 ✕ 偏细偏小) */
.sheet-btn { display: inline-flex; align-items: center; justify-content: center; }
.sheet-btn svg { width: 20px; height: 20px; fill: currentColor; }
.sugg .x svg { display: block; width: 18px; height: 18px; fill: currentColor; }
.dm-form button svg { display: block; width: 20px; height: 20px; fill: currentColor; }
.cl-go svg { width: 12px; height: 12px; fill: currentColor; vertical-align: -1px; }
.progress { margin-top: 14px; }
.track { height: 4px; border-radius: 2px; background: var(--chip); overflow: hidden; }
.track > div { height: 100%; width: 0; background: var(--p); }
.time { font-size: 12px; color: var(--mute); margin-top: 6px; text-align: right; font-variant-numeric: tabular-nums; }
#pb-range { display: block; width: 100%; margin: 0; accent-color: var(--p); }
.pb-time-link { display: flex; justify-content: space-between; color: var(--p); cursor: pointer; }
/* 点时间 = 就地改成输入框 (左边已播时间那里), 「跳转」确认 (有的数字键盘没有回车); 不再在下面展开一行 */
/* 时间那一行定高, 输入框与按钮都收在这个高度里: 点开输入框卡片不会被撑高 */
#pb-time { height: 24px; margin-top: 2px; align-items: center; }
.pb-jump { display: inline-flex; align-items: center; gap: 4px; }
#pb-jump input { box-sizing: border-box; width: 6.5em; min-width: 0; height: 22px; margin: 0; padding: 0 8px; border-radius: 8px;
  border: 1px solid var(--p); background: var(--card); color: var(--now-fg); font: inherit; font-size: 13px; line-height: 20px; }
#pb-jump button { flex: none; box-sizing: border-box; height: 22px; padding: 0 10px; background: var(--p); color: var(--on-p);
  font-weight: 600; font-size: 12px; line-height: 22px; border-radius: 8px; }
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
/* 大下拉框与文字输入框同高 (46px); 字号 16px: 小于 16px 时 iOS Safari 点进去会把整页放大 */
.episode select { box-sizing: border-box; width: 100%; height: 46px; font: inherit; font-size: 16px; padding: 0 10px; border: 1px solid var(--outline); border-radius: 12px; background: var(--field); color: var(--fg); }
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
/* 数据源图标 (window.srcIcon): 名字前一枚小方圆图; 没有图标时是首字母圆标 (字用 ::before 画, 不进 innerText) */
.src-ic { display: inline-block; flex: none; width: 22px; height: 22px; border-radius: 6px; object-fit: cover; vertical-align: -5px;
  margin-right: 8px; background: var(--chip); }
.src-ic.ph { display: inline-flex; align-items: center; justify-content: center; border-radius: 11px; }
.src-ic.ph::before { content: attr(data-ph); font-size: 12px; font-weight: 700; color: var(--on-chip); line-height: 1; }
.src-top .src-ic { width: 30px; height: 30px; margin-right: 0; }
.src-top .src-ic.ph { border-radius: 15px; }
.src-top .src-ic.ph::before { font-size: 14px; }
.src-item.off .src-ic { opacity: .45; }
.src-sw input { width: 20px; height: 20px; }
.src-name { flex: 1; min-width: 0; font-size: 15px; font-weight: 600; word-break: break-all; }
.src-name small { display: block; font-weight: 400; font-size: 12px; color: var(--mute); margin-top: 2px; }
.src-desc { font-size: 12px; color: var(--mute); margin-top: 6px; word-break: break-all; }
.src-btns { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.src-btns button { box-sizing: border-box; height: 34px; background: var(--chip); color: var(--on-chip); padding: 0 12px; border-radius: 10px; font-size: 13px; }
.src-btns button:disabled { opacity: .4; }
.src-btns .src-danger { background: var(--err-bg); color: var(--err-fg); }
.src-panel { margin-top: 10px; }
.src-panel textarea, #src-add textarea, #src-import textarea { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.f select { box-sizing: border-box; width: 100%; height: 46px; font: inherit; font-size: 16px; padding: 0 10px; border: 1px solid var(--outline); border-radius: 12px; background: var(--field); color: var(--fg); }
.src-bool { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.je-tabs { display: flex; gap: 6px; align-items: center; margin: 4px 0 6px; }
.je-tab, .je-fmt { box-sizing: border-box; height: 32px; background: var(--chip); color: var(--on-chip); padding: 0 14px; border-radius: 16px; font-size: 14px; }
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
.res-top { display: flex; align-items: baseline; gap: 10px; }
.res-q { flex: 1; min-width: 0; font-size: 17px; font-weight: 700; word-break: break-all; }
.res-n { flex: none; font-size: 12px; color: var(--sub); }
.res-sub { font-size: 12px; color: var(--sub); margin-top: 4px; word-break: break-all; }
.res-item { padding-right: 14px; cursor: pointer; }
.res-item .t { font-size: 15px; font-weight: 600; }
.res-rate { display: block; font-size: 12px; color: var(--sub); margin-top: 3px; }
/* 列表项的「播放」(搜索结果 / 播放记录). 有封面的行不放任何按钮: 右边那片封面整块就是播放 (透明热区, 只占文字以外的右边 36%;
   列表顶上的提示说清楚). 按下时那块压暗、正中亮出播放图标, 松手才触发, 请求没回来前一直亮着 (.hit, 脚本加). 没封面的行在
   封面本该在的右边放一枚大一点的主题色浅底按钮 —— 所有行都是「右边 = 播放」. 点其余地方 = 打开详情 */
/* 所有行都一样: 右边 36% 是播放热区 (文字只占左边 64%), 图标在同一个位置 —— 对准有封面行压暗那块的视觉中心 (封面从行宽约 53%
   处露出, 到右边缘 → 约 76%): 热区左边在 64%, 宽 36%, left 33% 就落在 64 + 0.33 × 36 ≈ 76%. 没封面的行图标常驻 (主题色浅底),
   有封面的行平时藏着、按下 / 悬停才亮 */
.res-play { position: absolute; top: 0; right: 0; bottom: 0; width: 36%; padding: 0; background: none; border-radius: 0; }
.play-glyph { position: absolute; top: 50%; left: 33%; margin: -22px 0 0 -22px; width: 44px; height: 44px;
  display: flex; align-items: center; justify-content: center; color: var(--p); transition: transform .12s, opacity .12s, color .12s; }
.play-glyph svg { width: 30px; height: 30px; fill: currentColor; }
.res-play:active .play-glyph { transform: scale(.88); }
.res-item > .t, .res-item > .m, .res-item > .res-rate, .hist-item > .hist-main { max-width: 64%; }
/* 只有一个三角, 不要圆底. 有封面的行平时不露; 按下 / 请求中 / 悬停: 右边那块压暗 + 白三角 (一圈淡阴影, 浅色封面上也看得清).
   没封面的行三角常驻 (主题色, 手机没有悬停, 得看得出哪里能点), 压暗时同样变白 */
.cv > .res-play .play-glyph { opacity: 0; transform: scale(.8); }
.press > .res-play .play-glyph, .hit > .res-play .play-glyph { opacity: 1; transform: scale(1); color: #fff;
  filter: drop-shadow(0 1px 3px rgba(0,0,0,.5)); }
/* 按下的压暗层与清楚的封面是同一块 (同 .cv-art 的位置与羽化), 不是热区那个直角矩形; 按下 = .press (脚本按 pointer 事件加),
   请求中 = .hit */
.res-item, .hist-item { isolation: isolate; }
.res-item::after, .hist-item::after { content: ""; position: absolute; top: 0; right: 0; width: 52%; height: 100%; z-index: -1;
  pointer-events: none; background: rgba(0,0,0,.22); opacity: 0; transition: opacity .12s; clip-path: inset(0 round 0 12px 12px 0);
  -webkit-mask-image: linear-gradient(to right, transparent, black 65%); mask-image: linear-gradient(to right, transparent, black 65%); }
.res-item.press::after, .res-item.hit::after, .hist-item.press::after, .hist-item.hit::after { opacity: 1; }
/* 电脑上 (鼠标能悬停): 移到右边就压暗、亮出白三角, 不用等按下 (有没有封面都一样); 手机没有悬停, 仍是按下才出现 */
@media (hover: hover) and (pointer: fine) {
  .res-item:has(> .res-play:hover)::after, .hist-item:has(> .res-play:hover)::after { opacity: 1; }
  .res-play:hover .play-glyph { opacity: 1; transform: scale(1); color: #fff; filter: drop-shadow(0 1px 3px rgba(0,0,0,.5)); }
}
/* 有封面的播放记录: 进度条停在封面前面 */
.cv .hist-bar { width: 72%; }
.cv.res-item, .cv.hist-item { padding-right: 14px; }
.res-item.busy { opacity: .55; }
/* 「缓存」标签番名那一块也是右边封面 = 播放 (同搜索结果); 压暗层贴着那块的右上圆角 */
.cl-top { position: relative; isolation: isolate; }
.cl-top::after { content: ""; position: absolute; top: 0; right: 0; width: 52%; height: 100%; z-index: -1;
  pointer-events: none; background: rgba(0,0,0,.22); opacity: 0; transition: opacity .12s; clip-path: inset(0 round 0 14px 0 0);
  -webkit-mask-image: linear-gradient(to right, transparent, black 65%); mask-image: linear-gradient(to right, transparent, black 65%); }
.cl-top.press::after, .cl-top.hit::after { opacity: 1; }
@media (hover: hover) and (pointer: fine) {
  .cl-top:has(> .res-play:hover)::after { opacity: 1; }
}
.res-r18 { display: inline-block; font-size: 10px; font-weight: 700; background: var(--err-bg); color: var(--err-fg); border-radius: 6px; padding: 1px 5px; margin-left: 6px; vertical-align: 2px; }
#res-foot { margin-top: 12px; }
.res-end { text-align: center; }
.sub-card { margin-top: 10px; }
.sub-head { display: flex; align-items: center; gap: 8px; }
.sub-head b { font-size: 15px; }
.sub-head small { flex: 1; font-size: 12px; color: var(--mute); }
.sub-refresh, .sub-del { flex: none; box-sizing: border-box; height: 34px; background: var(--chip); color: var(--on-chip); padding: 0 12px; border-radius: 10px; font-size: 13px; }
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
/* 播放记录 (HISTORY_SCRIPT): 设置里账号下面的入口卡片 + 全屏面板里一部番一行 */
.hist-entry { display: flex; align-items: center; gap: 12px; width: 100%; text-align: left; color: var(--fg); }
.hist-entry svg { flex: none; width: 26px; height: 26px; fill: var(--p); }
.hist-txt { flex: 1; min-width: 0; }
.hist-txt b { display: block; font-size: 15px; }
.hist-txt small { display: block; font-size: 12px; color: var(--mute); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-go { flex: none; color: var(--mute); font-size: 22px; line-height: 1; }
#set-account .acct { cursor: pointer; }
.hist-item { padding-right: 14px; cursor: pointer; }
.hist-item.busy { opacity: .55; }
.hist-main { min-width: 0; }
.hist-main .t { font-size: 15px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-bar { height: 3px; border-radius: 2px; background: var(--chip); margin-top: 6px; overflow: hidden; }
.hist-bar > div { height: 100%; background: var(--p); }
.hist-time { display: block; font-size: 12px; color: var(--mute); margin-top: 4px; }
/* 左右滑出按钮 (仿 iOS 列表, 见 SCRIPT 的 swRow): 行包在 .sw 里, 按钮垫在行下面, 行随手指平移露出它们.
   圆角与投影挪到外层 (内层裁掉按钮的角); 竖向滚动仍归浏览器 (touch-action: pan-y) */
.sw { position: relative; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 3px var(--shadow); }
/* 行上的字不让选: 选中了再横拖, 浏览器会当成拖动选中的文字, 把这次手势掐掉 (电脑上鼠标拖实测); 也挡掉 iOS 长按的菜单 */
.sw > .item, .sw > .sw-row { box-shadow: none; touch-action: pan-y; transition: transform .22s cubic-bezier(.2, .8, .2, 1);
  -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; }
.sw > .item.dragging, .sw > .sw-row.dragging { transition: none; }
/* 卡片里面的一行行 (缓存每一集): 贴满卡片左右 (抵掉卡片内边距), 不带圆角投影; 行是透明的 (缓存卡底下垫着模糊封面),
   所以停着时两边按钮藏起来, 往行底下多铺的那截也不要 (没有圆角缺口要补, 透明的行会透出来) */
.sw.flat { margin: 0 -16px; border-radius: 0; box-shadow: none; }
.sw.flat > .sw-row { padding-left: 16px; padding-right: 16px; }
/* 没滑开时两边按钮一律藏起来: 平铺行是透明的; 卡片行点一下进「请求中」会变半透明 (.busy), 也会透出底下的颜色 */
.sw:not(.xr):not(.xl) > .sw-acts { visibility: hidden; }
.sw.flat .sw-btn::before, .sw.flat .sw-btn::after { display: none; }
/* 行是透明的, 按钮垫在它下面会整颗透出来: 按行让出来的宽度 (--sx, swSet 写) 裁剪, 像不透明的行那样一点点露出;
   拖动中跟手不过渡, 松手吸附时与行同一条曲线 */
.sw.flat > .sw-acts { transition: clip-path .22s cubic-bezier(.2, .8, .2, 1); }
.sw.flat.sw-drag > .sw-acts { transition: none; }
.sw.flat > .sw-acts.r { clip-path: inset(0 0 0 calc(100% - var(--sx, 0px))); }
.sw.flat > .sw-acts.l { clip-path: inset(0 calc(100% - var(--sx, 0px)) 0 0); }
/* 缓存卡顶上的番名那一块也能左滑 (全部删除): 外层接过它原来抵掉卡片内边距的负边距与上面两个圆角 */
.sw.flat.cl-top-sw { margin: -14px -16px 0; border-radius: 14px 14px 0 0; }
.cl-top-sw > .cl-top { margin: 0; }
/* 番名那块左滑时封面右上角不再圆: 那个圆角是给卡片右上角的, 滑开后它挪到卡片中间, 圆角外露出白底 (缝隙在它和「全部删除」之间);
   卡片外沿仍由外层 .cl-top-sw 的圆角裁. 行是透明的, 不能用「按钮往行底下多铺一截」的办法补 (会从行后面透出来) */
.cl-top-sw.xl > .cl-top.cv > .cv-art { clip-path: none; }
/* 同理番名那块自己: 它带上面两个圆角且 .cv 是 overflow:hidden, 封面被它的右上圆角裁掉一角 —— 左滑时这个角挪到「全部删除」旁边露缝
   (右滑挪的是左上角, 那里没有图, 看不出来). 滑开时去掉右上圆角, 卡片外沿照样由外层裁 */
.cl-top-sw.xl > .cl-top { border-top-right-radius: 0; }
.sw img { -webkit-user-drag: none; }
.sw-acts { position: absolute; top: 0; bottom: 0; display: flex; }
.sw-acts.l { left: 0; }
.sw-acts.r { right: 0; }
.sw.xr > .sw-acts.r, .sw.xl > .sw-acts.l { visibility: hidden; }
.sw-btn { position: relative; width: 76px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; padding: 0;
  border-radius: 0; color: #fff; font-size: 12px; font-weight: 600; }
/* 挨着行的那颗按钮把自己的底色往行底下多铺一截: 行的圆角后面不露页面底色 (白色缺口), 拖过头时露出的也是同色 */
.sw-acts.r > .sw-btn:first-child::before, .sw-acts.l > .sw-btn:last-child::after { content: ""; position: absolute; top: 0; bottom: 0;
  width: 48px; background: inherit; }
.sw-acts.r > .sw-btn:first-child::before { right: 100%; }
.sw-acts.l > .sw-btn:last-child::after { left: 100%; }
/* 拖过头按钮跟着撑宽 (拖动中宽度不过渡, 松手缩回 / 铺满时过渡); 滑到底时内容整组挪到行边 (swPad 写内边距, 这一下有过渡) */
.sw-btn { box-sizing: border-box; transition: width .22s cubic-bezier(.2, .8, .2, 1), padding .15s ease; }
.sw.sw-drag .sw-btn { transition: padding .15s ease; }
.sw-btn svg { width: 22px; height: 22px; fill: currentColor; }
.sw-btn.cache { background: var(--p); color: var(--on-p); }
.sw-btn.coll { background: #e0932f; }
.sw-btn.del { background: var(--del); }
.sw-btn:disabled { opacity: .6; }
/* 删掉的那一行: 收起高度与间距再移除 */
.sw.gone { height: 0 !important; opacity: 0; margin-top: -8px; transition: height .25s, opacity .2s, margin-top .25s; }
.sub-item[data-lp] { position: relative; -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; }
.sel-mark { display: none; position: absolute; left: 14px; top: 50%; width: 22px; height: 22px; margin-top: -11px; box-sizing: border-box;
  border-radius: 50%; border: 2px solid var(--mute); background: var(--card); }
.selecting [data-lp], .selecting .sw > [data-lp] { padding-left: 50px; transition: padding-left .18s; }
.selecting .sub-item[data-lp] { padding-left: 34px; }
.selecting [data-lp] > .sel-mark { display: block; }
.selecting .sub-item > .sel-mark { left: 0; margin-top: -6px; }
[data-lp].picked > .sel-mark { background: var(--p); border-color: var(--p); }
[data-lp].picked > .sel-mark::after { content: ""; position: absolute; left: 6px; top: 2px; width: 5px; height: 10px; box-sizing: border-box;
  border: solid var(--on-p); border-width: 0 2px 2px 0; transform: rotate(45deg); }
.selecting .res-play, .selecting .sub-del { visibility: hidden; }
.cl-ep[data-lp] { position: relative; -webkit-touch-callout: none; }
.selecting .cl-ep > .icb { visibility: hidden; }
.selecting .cl-ep .cl-go { display: none; }
#sel-bar { position: fixed; left: 0; right: 0; bottom: 0; z-index: 56; box-sizing: border-box; height: calc(56px + env(safe-area-inset-bottom));
  padding: 0 12px env(safe-area-inset-bottom); display: flex; align-items: center; gap: 8px; background: var(--card);
  border-top: 1px solid var(--line2); box-shadow: 0 -2px 10px var(--shadow); }
#sel-bar[hidden] { display: none; }
#sel-bar .sel-n { flex: 1; min-width: 0; text-align: center; font-size: 14px; font-weight: 600; }
#sel-bar button { flex: none; box-sizing: border-box; height: 38px; padding: 0 14px; border-radius: 10px; background: var(--chip); color: var(--on-chip); font-size: 14px; }
#sel-bar .sel-del { background: var(--err-bg); color: var(--err-fg); }
#sel-bar button:disabled { opacity: .45; }
#sel-bar svg { width: 18px; height: 18px; fill: currentColor; }
body.sel-on .sheet-body { padding-bottom: calc(80px + env(safe-area-inset-bottom)); }
/* 列表项的封面底图 (播放记录 / 搜索结果, window.coverLayers): 整张卡片先铺一层放大虚化的封面当底色 (染色), 右边再叠一张清楚的
   封面, 往左羽化进底色; 文字只占左边六成多, 压在底色上. 用 <img> 不用 CSS 背景图: 要 loading="lazy" (进了视口才拉) 与
   referrerpolicy —— CSS 背景图不认页面的 no-referrer, 实测照带来源. 两张同一地址, 浏览器只拉一次 */
.cv { position: relative; overflow: hidden; isolation: isolate; }
/* 带模糊 / 遮罩的层在 iOS Safari 上会绕过父元素「圆角 + overflow:hidden」的裁剪, 直角露在圆角外面 (手机上边角又圆又尖,
   电脑上正常): 每层自己按卡片圆角再裁一次 (clip-path), 不裁卡片本身 —— 那样会连阴影一起裁掉 */
.cv > .cv-bg { position: absolute; left: -24px; top: -24px; width: calc(100% + 48px); height: calc(100% + 48px); z-index: -2;
  object-fit: cover; filter: blur(20px) saturate(1.3); opacity: .32; clip-path: inset(24px round 12px); }
.cv > .cv-art { position: absolute; top: 0; right: 0; width: 52%; height: 100%; z-index: -1; object-fit: cover; object-position: center 25%;
  clip-path: inset(0 round 0 12px 12px 0);
  -webkit-mask-image: linear-gradient(to right, transparent, black 65%); mask-image: linear-gradient(to right, transparent, black 65%); }
/* NSFW 在「模糊」模式下 (同电视网格打码): 清楚那层也糊掉, 只剩色块 */
.nsfw-blur > .cv-art { filter: blur(14px) saturate(1.1); }
/* 封面下载完才淡入 (脚本在 load 时加 .ld): 新滑进来的行不从空白突然跳出一张图 */
.cv > .cv-bg, .cv > .cv-art { transition: opacity .25s; }
.cv > img:not(.ld) { opacity: 0; }
.cv > .t, .cv > .m, .cv > .res-rate, .cv > .hist-main { max-width: 64%; }
/* 「播放器」标签那张卡的底图 (这一集的剧照, 没有就横屏图, 见 paintNow): 铺满整张卡, 卡片大小不变. 上面盖一层半透明的
   卡片底色 (.now-scrim: 文字靠左, 所以左厚右薄, 往下再压一点), 文字带一圈淡光晕 (--now-glow: 浅色白、深色黑);
   带底色的按钮不要光晕, 图标按钮用 drop-shadow */
/* 剧照上下没铺到的地方 (剧照居中、上下羽化, 见下一条): 垫同一张图的重度模糊, 卡片边上是这一集的颜色而不是一片底色
   (同缓存卡的整卡模糊底); 深色下先压暗, 免得亮图把卡片抬成一片灰 */
.now-card.cv > .cv-bg { left: -40px; top: -40px; width: calc(100% + 80px); height: calc(100% + 80px);
  filter: blur(40px) saturate(1.4); opacity: .55; clip-path: inset(40px round 14px); }
:root[data-theme="dark"] .now-card.cv > .cv-bg { filter: blur(40px) saturate(1.4) brightness(.65); opacity: .5; }
/* 剧照按原比例铺满卡片宽度、上下居中 (不再按卡片高度放大后左右裁掉), 上下两边都羽化进底色;
   卡片比图还矮 (「接下来播放」那种短卡) 时 max-height 封顶, 退回按卡片大小裁 (object-fit: cover), 羽化照样在上下边 */
.now-card.cv > .cv-art { top: 50%; left: 0; right: 0; bottom: auto; width: 100%; height: auto; max-height: 100%; z-index: -2;
  transform: translateY(-50%); object-fit: cover; object-position: center 30%;
  /* 不给图自己加圆角裁剪: 图居中后四角落在卡片里面, 圆角会在羽化带上露出缺口. 上下各 14px (= 卡片圆角) 全透明,
     图贴到卡片上下边 (短卡) 时角上也没东西, iOS 上不会漏出卡片圆角外 (见上面 .cv 的注释) */
  -webkit-mask-image: linear-gradient(to bottom, transparent 14px, black 30%, black 70%, transparent calc(100% - 14px));
  mask-image: linear-gradient(to bottom, transparent 14px, black 30%, black 70%, transparent calc(100% - 14px)); }
.now-scrim { position: absolute; inset: 0; z-index: -1; background: var(--card); clip-path: inset(0 round 14px);
  -webkit-mask-image: linear-gradient(to right, rgba(0,0,0,.6), rgba(0,0,0,.12) 75%), linear-gradient(to bottom, rgba(0,0,0,.1), rgba(0,0,0,.42));
  mask-image: linear-gradient(to right, rgba(0,0,0,.6), rgba(0,0,0,.12) 75%), linear-gradient(to bottom, rgba(0,0,0,.1), rgba(0,0,0,.42)); }
.now-card.cv { text-shadow: 0 0 1px var(--now-glow), 0 0 8px var(--now-glow); }
.now-card.cv button, .now-card.cv .now-srcname { text-shadow: none; }
.now-card.cv .pb-ctrls button { filter: drop-shadow(0 0 6px var(--now-glow)); }
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
/* 评论框底部一行: 「表情」与「发表」同高 */
.cm-foot .primary { flex: none; box-sizing: border-box; height: 40px; padding: 0 24px; font-size: 15px; }
.cm-foot .primary:disabled { opacity: .45; }
.cm-emo { flex: none; box-sizing: border-box; height: 40px; display: inline-flex; align-items: center; gap: 6px; background: var(--chip); color: var(--on-chip); padding: 0 14px 0 10px; border-radius: 20px; font-size: 14px; }
.cm-emo svg { width: 20px; height: 20px; fill: currentColor; }
.cm-emo.on { background: var(--p); color: var(--on-p); }
.cm-preview { margin-top: 8px; padding: 8px 12px; border-radius: 10px; background: var(--soft); font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; }
.cm-preview[hidden], .cm-picker[hidden] { display: none; }
.cm-preview small { display: block; font-size: 12px; color: var(--mute); }
.cm-preview img { max-height: 2em; vertical-align: middle; }
.cm-picker { margin-top: 10px; border: 1px solid var(--line2); border-radius: 12px; overflow: hidden; }
.cm-packs { position: relative; display: flex; gap: 6px; padding: 8px; overflow-x: auto; border-bottom: 1px solid var(--line); }
.cm-packs button { flex: none; box-sizing: border-box; height: 32px; background: var(--chip); color: var(--on-chip); padding: 0 14px; border-radius: 16px; font-size: 14px; white-space: nowrap; }
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
.dm-shift button { box-sizing: border-box; height: 34px; background: var(--chip); color: var(--on-chip); padding: 0 10px; border-radius: 10px; font-size: 13px; }
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
/* 播放卡标题行: 剧名 + 右边的「缓存」小按钮 (仿 iOS 播放卡右上角的小圆钮) */
.now-head { display: flex; align-items: flex-start; gap: 10px; }
.now-head .now-title { flex: 1; min-width: 0; }
.now-cache { flex: none; display: inline-flex; align-items: center; gap: 4px; margin-top: 1px; padding: 5px 12px 5px 9px; border-radius: 15px;
  background: var(--chip); color: var(--on-chip); font-size: 13px; font-weight: 600; }
.now-cache svg { width: 16px; height: 16px; fill: currentColor; }
/* 播放控制 (仿 iOS 播放卡): 后退 10 秒 / 播放暂停 / 前进 10 秒 三个大图标, 不要按钮底色 */
.pb-ctrls { display: flex; justify-content: center; align-items: center; gap: 40px; margin-top: 4px; }
.pb-ctrls button { background: none; color: var(--fg); padding: 4px; border-radius: 50%; line-height: 0; }
.pb-ctrls button:active { opacity: .45; }
.pb-ctrls svg { width: 34px; height: 34px; fill: currentColor; }
.pb-ctrls .pb-main svg { width: 50px; height: 50px; }
/* 播放卡的配色 (按语义分, 不是一种颜色刷到底):
   - 内容文字 (只读) 用中性色三级: --now-fg 剧名 / --now-fg2 集名、规格、时间 / --now-fg3 小标签;
   - 能点的用主题色: 播放暂停 = 实心主题色圆钮 (主操作); 前进后退、进度条、剧名后的 › = 主题色;
     「缓存」= 主题色浅底胶囊 (次要操作); 「接下来播放」卡的播放按钮 = 实心主题色 (主操作);
   - 只是信息的数据源名 = 中性半透明胶囊;
   - 状态用状态色: 「正在播放」前一个绿点, 暂停时变灰 (脚本按播放状态切 .paused).
   胶囊都带毛玻璃, 压在图上也干净. 进度条自绘, 填充比例由脚本写进 --pct */
.now-card { color: var(--now-fg); }
.now-card .now-link::after { color: var(--p); }
.now-card .now-label { color: var(--now-fg3); }
.now-card .now-label.live::before { content: ""; display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 6px;
  vertical-align: 1px; background: var(--ok); }
.now-card .now-label.live.paused::before { background: var(--now-fg3); }
.now-card .now-meta, .now-card .now-src, .now-card .now-ep { color: var(--now-fg2); }
/* 正在播的数据源: 实心主题色 + 白字 (同下面选中的筛选胶囊 .chip.on), 卡片里最醒目的一块信息 */
.now-card .now-srcname { background: var(--p); color: var(--on-p); font-weight: 700; cursor: pointer; }
/* 点它滚到下面正在播的那一条 (见 SCRIPT 里的点击处理); 小箭头提示能点 */
.now-card .now-srcname::after { content: ""; display: inline-block; width: 18px; height: 18px; margin: 0 -5px 0 2px; vertical-align: -4px;
  background: currentColor; opacity: .9; -webkit-mask: var(--chev) center / contain no-repeat; mask: var(--chev) center / contain no-repeat; }
#player-chips { scroll-margin-top: 12px; }
/* 候选按类型分段 (本地缓存 / 在线源 / BT 源), 各自可收起 */
.src-sec { margin-top: 14px; }
.src-sec > summary { display: flex; align-items: baseline; gap: 8px; padding: 10px 2px; font-size: 17px; font-weight: 700; cursor: pointer;
  list-style: none; border-bottom: 1px solid var(--line2); }
.src-sec > summary::-webkit-details-marker { display: none; }
/* 收起时箭头朝右, 展开朝下 (同一个图形转 90°) */
.src-sec > summary::before { content: ""; flex: none; align-self: center; width: 22px; height: 22px; background: var(--p);
  -webkit-mask: var(--chev) center / contain no-repeat; mask: var(--chev) center / contain no-repeat;
  transform: rotate(-90deg); transition: transform .15s; }
.src-sec[open] > summary::before { transform: none; }
.src-sec > summary small { font-size: 12px; font-weight: 400; color: var(--mute); }
.now-card .now-cache { background: var(--now-accent-pill); color: var(--p); }
.now-card .now-srcname, .now-card .now-cache { -webkit-backdrop-filter: blur(10px); backdrop-filter: blur(10px); }.now-card .pb-ctrls button { color: var(--p); }
.now-card .pb-ctrls .pb-main { background: var(--p); color: var(--on-p); padding: 10px; }
.now-card .pb-ctrls .pb-main svg { width: 38px; height: 38px; }
.now-card .pb-time-link, .now-card .time { color: var(--now-fg2); }
.now-card .track { background: var(--now-track); }
.now-card .track > div { background: var(--p); }
.now-card #pb-range, .now-card #pb-vol { -webkit-appearance: none; appearance: none; height: 22px; background: transparent; }
.now-card #pb-range::-webkit-slider-runnable-track, .now-card #pb-vol::-webkit-slider-runnable-track { height: 4px; border-radius: 2px;
  background: linear-gradient(to right, var(--p) var(--pct, 0%), var(--now-track) var(--pct, 0%)); }
.now-card #pb-range::-webkit-slider-thumb, .now-card #pb-vol::-webkit-slider-thumb { -webkit-appearance: none; appearance: none;
  width: 14px; height: 14px; margin-top: -5px; border: 0; border-radius: 50%; background: var(--p); }
.now-card #pb-range::-moz-range-track, .now-card #pb-vol::-moz-range-track { height: 4px; border-radius: 2px; background: var(--now-track); }
.now-card #pb-range::-moz-range-progress, .now-card #pb-vol::-moz-range-progress { height: 4px; border-radius: 2px; background: var(--p); }
.now-card #pb-range::-moz-range-thumb, .now-card #pb-vol::-moz-range-thumb { width: 14px; height: 14px; border: 0; border-radius: 50%; background: var(--p); }
.now-card #pb-range:disabled { opacity: .5; }
/* 音量条 (播放器音量, 见 RemotePlayerHandle.setVolume): 仿 iOS 播放卡, 两头一小一大两个喇叭, 左边那个点了静音 */
.pb-vol { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.pb-vol[hidden] { display: none; }
.pb-vol button, .pb-vol-hi { flex: none; background: none; padding: 4px; line-height: 0; color: var(--now-fg2); }
.pb-vol svg { width: 20px; height: 20px; fill: currentColor; }
.pb-vol input { flex: 1; min-width: 0; }
.pb-vol.muted input { opacity: .4; }
.now-card .ctrls button.main { background: var(--p); color: var(--on-p); }
.sheet { position: fixed; inset: 0; z-index: 50; display: flex; flex-direction: column; background: var(--bg); }
/* 缓存面板可以从播放记录面板里 (右滑「缓存」) 打开, 要盖在它上面 */
#cache-sheet { z-index: 52; }
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
.cache-ep > button { flex: none; box-sizing: border-box; height: 34px; background: var(--chip); color: var(--on-chip); padding: 0 12px; border-radius: 10px; font-size: 13px; }
.cache-bar { position: sticky; bottom: -24px; background: var(--bg); padding: 12px 0 24px; display: flex; gap: 10px; }
.cache-bar #cache-auto { flex: 1; min-width: 0; width: auto; }
.cache-bar .cache-all { flex: none; }
.cache-bar button:disabled { opacity: .5; }
.tv-state { margin: 6px 16px 0; padding: 8px 12px; border-radius: 12px; font-size: 13px; line-height: 1.45; background: var(--warn-bg); color: var(--warn-fg); }
.tv-state.off { background: var(--err-bg); color: var(--err-fg); }
.tv-front { margin-left: 8px; padding: 3px 10px; border-radius: 8px; background: var(--warn-fg); color: var(--warn-bg); font-size: 13px; font-weight: 600; }
.tv-front:disabled { opacity: .5; }
.ld-bar { margin: 6px 16px 0; padding: 10px 12px; border-radius: 12px; background: var(--card); border: 1px solid var(--line); }
.ld-row { display: flex; align-items: center; gap: 10px; }
.ld-row span { flex: 1; min-width: 0; font-size: 14px; font-weight: 600; }
.ld-close { flex: none; padding: 7px 12px; border-radius: 10px; background: var(--p); color: var(--on-p); font-size: 14px; font-weight: 600; }
.ld-close:disabled { opacity: .5; }
.ld-x { flex: none; width: 30px; height: 30px; margin-right: -4px; border-radius: 15px; background: none; color: var(--mute); font-size: 20px; line-height: 1; }
.ld-acts { margin-top: 8px; flex-wrap: wrap; }
.ld-mode { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--mute); }
.ld-mode select { font-size: 13px; padding: 5px 6px; border-radius: 8px; border: 1px solid var(--line); background: var(--field); color: inherit; }
.look-ld { display: block; margin-top: 14px; }
.look-ld span { display: block; font-size: 14px; margin-bottom: 6px; }
.look-ld select { width: 100%; box-sizing: border-box; font-size: 14px; padding: 9px 10px; border-radius: 10px; border: 1px solid var(--line); background: var(--field); color: inherit; }
.acct { display: flex; align-items: center; gap: 12px; }
.acct img, .acct-ph { flex: none; width: 48px; height: 48px; border-radius: 24px; object-fit: cover; background: var(--chip); }
.acct-ph { display: flex; align-items: center; justify-content: center; color: var(--p); font-size: 20px; font-weight: 700; }
.acct-name { font-size: 16px; font-weight: 700; word-break: break-all; }
.acct-sub { font-size: 12px; color: var(--mute); margin-top: 2px; }
.acct-wait { margin-top: 12px; }
.acct-more { margin-left: auto; flex: none; font-size: 13px; color: var(--mute); }
.acct-menu { display: flex; gap: 8px; margin-top: 12px; }
/* 账号菜单三个钮 (修改昵称 / 绑定邮箱 / 退出登录) 挤一行: 字号小一档、图标与左右内边距收紧, 不折行 (360 宽的手机也放得下) */
.acct-menu button { flex: 1; min-width: 0; padding: 13px 6px; font-size: 14px; gap: 4px; white-space: nowrap; }
.acct-menu button svg { flex: none; width: 16px; height: 16px; }
.acct-menu .acct-danger { background: var(--err-bg); color: var(--err-fg); }
.acct-nick { display: flex; gap: 8px; margin-top: 12px; }
.acct-nick input { flex: 1; min-width: 0; }
.acct-nick button { flex: none; background: var(--p); color: var(--on-p); font-weight: 600; padding: 10px 16px; border-radius: 12px; }
.acct-nick button:disabled { opacity: .5; }
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
/* 番名 + 统计那一块: 撑满卡片顶部 (抵掉卡片内边距), 有封面时同列表项的底图 (右边清楚、往左羽化, 整块虚化染色);
   只到统计为止, 下面一集一集的行不铺图 (它们右边有按钮). 圆角只在上面两个角 */
.cl-top { margin: -14px -16px 0; padding: 14px 16px 8px; border-radius: 14px 14px 0 0; }
.cl-top.cv > .cv-bg { clip-path: inset(24px round 14px 14px 0 0); }
.cl-top.cv > .cv-art { clip-path: inset(0 round 0 14px 0 0); }
/* 番名块的清楚封面: 左边羽化之外, 下边也羽化进整卡的模糊底 (两道渐变取交集), 不在番名块底边一刀切 */
.cl-top.cv > .cv-art {
  -webkit-mask-image: linear-gradient(to right, transparent, black 65%), linear-gradient(to bottom, black 45%, transparent);
  -webkit-mask-composite: source-in;
  mask-image: linear-gradient(to right, transparent, black 65%), linear-gradient(to bottom, black 45%, transparent);
  mask-composite: intersect; }
.cl-top .cl-title, .cl-top > .cl-meta { max-width: 64%; }
.cl-top.cv .cl-cache { margin-left: auto; }
/* 番名行的两个按钮与下面每一集的暂停 / 删除同高 (34px), 同一张卡里按钮一样大 */
.cl-head .now-cache { margin-top: 0; box-sizing: border-box; height: 34px; padding: 0 14px 0 11px; border-radius: 17px; }
.cl-delall.icb { width: 38px; height: 34px; }
/* 整张缓存卡的模糊底: 同一张封面 (浏览器只拉一次) 重度模糊 + 低不透明度, 盖在卡片底色上; 番名那块自己的模糊层就不要了, 免得叠两遍.
   同 .cv: iOS 上模糊层会绕过圆角裁剪, 自己按圆角 clip-path */
.cl-group.amb { position: relative; overflow: hidden; isolation: isolate; }
.cl-group > .cl-amb-bg { position: absolute; left: -40px; top: -40px; width: calc(100% + 80px); height: calc(100% + 80px); z-index: -2;
  object-fit: cover; filter: var(--amb-f, blur(40px) saturate(1.4)); opacity: var(--amb-op, .26); clip-path: inset(40px round 14px);
  pointer-events: none; transition: opacity .25s; }
.cl-group > .cl-amb-bg:not(.ld) { opacity: 0; }
/* 深色: 亮封面 (白底的居多) 直接叠会把卡片抬成一片灰, 次要文字看不清 —— 先压暗再叠 */
:root[data-theme="dark"] .cl-group { --amb-op: .3; --amb-f: blur(40px) saturate(1.4) brightness(.65); }
/* 深色下垫了模糊底, 卡片被抬亮到跟 --chip 差不多: 暂停钮 / 合集标签 / 进度条底 / 分隔线会融进去, 改用半透明白 */
:root[data-theme="dark"] .cl-group.amb .cl-ep > button:not(.cl-del),
:root[data-theme="dark"] .cl-group.amb .cl-tag:not(.play),
:root[data-theme="dark"] .cl-group.amb .cl-bar { background: rgba(255,255,255,.13); }
:root[data-theme="dark"] .cl-group.amb .cl-ep { border-top-color: rgba(255,255,255,.09); }
.cl-group.amb .cl-top.cv > .cv-bg { display: none; }
.cl-head { display: flex; align-items: center; gap: 8px; }
.cl-title { flex: 1; min-width: 0; font-size: 16px; font-weight: 700; overflow-wrap: anywhere; }
.cl-title[data-open] { cursor: pointer; }
.cl-title[data-open]::after { content: " ›"; color: var(--mute); font-weight: 400; }
.cl-delall { flex: none; background: var(--err-bg); color: var(--err-fg); padding: 6px 12px; border-radius: 10px; font-size: 13px; }
.cl-delall:disabled { opacity: .5; }
.cl-meta { font-size: 12px; color: var(--mute); margin: 2px 0 4px; }
.cl-ep { display: flex; align-items: center; gap: 8px; padding: 10px 0; border-top: 1px solid var(--line); }
.cl-ep .n { flex: 1; min-width: 0; font-size: 14px; overflow-wrap: anywhere; }
.cl-ep[data-play] { cursor: pointer; }
.cl-ep.busy { opacity: .55; }
.cl-go { color: var(--p); font-size: 11px; margin-right: 6px; vertical-align: 1px; }
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
    // 同 STYLE 里两套 --bg: 地址栏与页面底色连成一片
    if (m) m.setAttribute('content', dark ? '#141218' : '#f7f2fa');
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
  // 缓存面板「全选」/「全部用合集缓存」的范围 (同 CACHE_SCRIPT 的 pickAllSp; 电视上没有全选, 所以只在网页上设)
  var PICK_SCOPE_KEY = 'ani-cache-pick-scope', PICK_SCOPES = [['main', '仅正片'], ['all', '正片和特别篇']];
  function pickScopeNow() { try { return localStorage.getItem(PICK_SCOPE_KEY) === 'all' ? 'all' : 'main'; } catch (e) { return 'main'; } }
  function paint() {
    var t = window.remoteTheme.get();
    box.innerHTML = '<div class="card set-card"><div class="set-title">本机偏好<small>只影响这台手机</small></div>' +
      '<div class="seg">' + OPTS.map(function (o) {
        return '<button type="button" data-look="' + o[0] + '"' + (o[0] === t ? ' class="on"' : '') + '>' + o[1] + '</button>';
      }).join('') + '</div><p class="hint">自动：跟着手机系统的深色模式切换。</p>' +
      // 同顶上「电视上的二维码弹窗还开着」那一条里的「以后」下拉框 (见 SCRIPT 的 ldSetMode)
      (window.LD_OPTS ? '<label class="look-ld"><span>打开网页时，电视上的二维码</span><select data-ld-mode>' +
        window.LD_OPTS.map(function (o) {
          return '<option value="' + o[0] + '"' + (o[0] === window.ldMode() ? ' selected' : '') + '>' + o[1] + '</option>';
        }).join('') + '</select></label>' : '') +
      '<label class="look-ld"><span>「全选」包含哪些剧集</span><select data-pick-scope>' +
      PICK_SCOPES.map(function (o) {
        return '<option value="' + o[0] + '"' + (o[0] === pickScopeNow() ? ' selected' : '') + '>' + o[1] + '</option>';
      }).join('') + '</select></label></div>';
  }
  box.addEventListener('change', function (e) {
    if (e.target.hasAttribute('data-ld-mode') && window.ldSetMode) window.ldSetMode(e.target.value);
    if (e.target.hasAttribute('data-pick-scope')) {
      try { if (e.target.value === 'all') localStorage.setItem(PICK_SCOPE_KEY, 'all'); else localStorage.removeItem(PICK_SCOPE_KEY); } catch (x) {}
      if (window.refreshCachePick) window.refreshCachePick();
    }
  });
  document.addEventListener('ldmode', paint);
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
  // 播放卡标题行右边的「缓存」小按钮, 点开这部番的缓存面板 (类名 cache-entry 留给测试脚本认)
  function cacheEntry(id, title) {
    return id ? '<button type="button" class="now-cache cache-entry" data-cache="' + id + '" data-title="' + esc(title || '') +
      '" aria-label="缓存这部番的剧集">' + window.ICONS.download + '缓存</button>' : '';
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
  // 请求一律带超时: 电视卡住时 TCP 可能挂几十秒, 不设上限的话轮询一个接一个挂着, 占满手机浏览器对同一地址的连接 (约 6 个),
  // 换源 / 跳转这些操作只能排队. 读 (轮询) 默认 8 秒; 写 (post) 放宽到 45 秒 —— 有的操作本来就慢 (测试代理最长 25 秒、删整部缓存)
  function fetchT(url, opts, ms) {
    var o = opts || {}, ctl = window.AbortController ? new AbortController() : null, t = null;
    if (ctl) { o.signal = ctl.signal; t = setTimeout(function () { ctl.abort(); }, ms || 8000); }
    return fetch(url, o).then(function (r) { clearTimeout(t); return r; }, function (e) { clearTimeout(t); throw e; });
  }
  window.fetchT = fetchT;
  /** 读接口: 非 2xx (服务端回纯文本 500 / 404) 也当失败, 不去解析 JSON 报一个看不懂的错 */
  function getJson(url, ms) {
    return fetchT(url, null, ms).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); });
  }
  window.getJson = getJson;
  function post(path, data) {
    return fetchT(path, { method: 'POST', body: new URLSearchParams(data) }, 45000).then(function (r) { return r.json(); });
  }
  window.post = post;
  // 请求没回应: 分不清是电视休眠了 (没开「后台常驻」时 Shield 一休眠就把 Ani 收掉)、Ani 没在运行, 还是不在同一个网络, 都说上;
  // 上次连上时「后台常驻」没开 (lastKeep, 见 pollNotice) 就顺带说去哪开
  function fail() {
    toast('无法连接电视。请确认电视已唤醒、Ani 正在运行，并且手机和电视连接到同一网络。' +
      (lastKeep === false ? '想在电视休眠或离开 Ani 后继续连接，请在网页的「设置」中开启「后台保持连接」。' : ''), 6000);
  }
  window.fail = fail;
  function failRead() { toast('读取失败，请确认手机与电视在同一网络'); }
  window.failRead = failRead;
  /** 内容 (生成它的 HTML) 没变就不重画; 上次写进去的记在节点上, 出错提示也走这里, 不会出现「记着旧的、画着别的」 */
  function setHtml(el, h) {
    if (el._h === h) return false;
    el.innerHTML = h;
    el._h = h;
    return true;
  }
  window.setHtml = setHtml;
  // 全屏面板 (缓存 / 播放记录 / 挑番 / 使用说明) 统一开关: 可以叠着开 (从播放记录或挑番右滑打开缓存面板), 后开的盖在上面
  // (z-index 按打开顺序); 背景页只在全部关上后才恢复滚动; 关上时在面板上发 sheetclose (冒泡), 被盖住的列表据此恢复刷新
  var sheetStack = [];
  window.sheets = {
    open: function (el) {
      var i = sheetStack.indexOf(el);
      if (i >= 0) sheetStack.splice(i, 1);
      sheetStack.push(el);
      el.style.zIndex = String(50 + sheetStack.length);
      el.hidden = false;
      document.body.style.overflow = 'hidden';
    },
    close: function (el) {
      var i = sheetStack.indexOf(el);
      if (i >= 0) sheetStack.splice(i, 1);
      if (el.hidden) return;
      el.hidden = true;
      if (!sheetStack.length) document.body.style.overflow = '';
      el.dispatchEvent(new CustomEvent('sheetclose', { bubbles: true }));
    },
    any: function () { return sheetStack.length > 0; }
  };

  // 电视上后台播放的提示 (准备好了、出问题) 同步到手机: 不管停在哪个标签, 每 2 秒问一次有没有新的一条.
  // 第一次不带 after, 服务端只回当前序号当基线, 打开页面时不会弹旧提示.
  // 顺带管顶上的状态条: 电视上 Ani 不在前台 (屏保 / 别的应用), 或者连不上电视 —— 连续两次失败才算, 丢一个包不闪
  // lastKeep: 上次连上时「退出 Ani 后保留 Web 控制台」开没开 (null = 还没连上过), 断连时没开就提示去设置里开
  var noticeSeq = null, noticeFails = 0, noticeBusy = false, noticeSkip = 0, lastKeep = null;
  var tvState = document.getElementById('tv-state');
  // btn: 「不在前台」那一条里的入口 (切到电视前台, 见 TvRemoteControl.manualFront; 设置里有同一个开关, 可以提前开或撤销):
  // 'enable' = 还没开, 点了先确认再开 / 'how' = 开了还没授权, 点了说怎么授权 / 'go' = 开了且授了权, 点了直接切.
  // 内容没变不重画: 每 2 秒一轮, 重画会把正要点的按钮换掉
  var FRONT_HOW = '在电视上完成授权：打开「设置 → 应用 → 特殊应用权限 → 显示在其他应用的上层」，然后为 Animeko 开启权限。只需授权一次，仅用于从手机打开 Ani。';
  function setTvState(kind, text, btn) {
    var key = (kind || '') + '|' + (text || '') + '|' + (btn || '');
    if (tvState._k === key) return;
    tvState._k = key;
    tvState.hidden = !kind;
    tvState.className = 'tv-state' + (kind ? ' ' + kind : '');
    tvState.textContent = text || '';
    if (btn) tvState.insertAdjacentHTML('beforeend', '<button type="button" class="tv-front" data-tv-front="' + btn + '">' +
      (btn === 'how' ? '查看授权方法' : '打开 Ani') + '</button>');
  }
  function frontNow(b) {
    b.disabled = true;
    post('api/tv/front', {}).then(function (r) { b.disabled = false; toast(r.message); }).catch(function () { b.disabled = false; fail(); });
  }
  tvState.addEventListener('click', function (e) {
    var b = e.target.closest('[data-tv-front]');
    if (!b) return;
    var k = b.getAttribute('data-tv-front');
    if (k === 'go') { frontNow(b); return; }
    if (k === 'how') { alert(FRONT_HOW); return; }
    if (!confirm('允许从手机打开电视上的 Ani？开启后，在手机上搜索或点播时，电视会自动打开 Ani。' +
      '首次使用需要在电视上授权，可随时在设置中关闭。')) return;
    b.disabled = true;
    post('api/settings/front', { on: '1' }).then(function (r) {
      tvState._k = null; // 下一轮按新状态重画这一条
      if (r.granted) frontNow(b);
      // 电视回的话里说了下一步 (Ani 在后台: 回到 Ani 时会直接打开授权页, 30 分钟内有效)
      else { b.disabled = false; alert(r.message || ('已打开。还差一步：' + FRONT_HOW)); }
      pollNotice();
    }).catch(function () { b.disabled = false; fail(); });
  });
  function pollNotice() {
    // 上一个还没回来就不再发 (以前每 2 秒照发, 电视卡住时一个个挂着, 占满手机对同一地址的连接)
    if (document.hidden || noticeBusy) return;
    // 连不上时退避: 失败了隔几轮再问 (最多隔 4 轮), 第一次成功就回到每 2 秒
    if (noticeSkip > 0) { noticeSkip--; return; }
    noticeBusy = true;
    fetchT('api/notice' + (noticeSeq == null ? '' : '?after=' + noticeSeq), null, 6000)
      .then(function (r) {
        // 地址失效 (电视上重置过地址): 服务端回的是纯文本 404
        if (r.status === 404) throw new Error('gone');
        return r.json();
      })
      .then(function (n) {
        noticeBusy = false;
        noticeFails = 0;
        if (n.text) toast(n.text, 6000);
        noticeSeq = n.seq;
        if (n.away && n.frontOn && n.frontGranted) setTvState('away', '电视当前没有显示 Ani。搜索或点播时会自动打开 Ani。', 'go');
        else if (n.away && n.frontOn) setTvState('away', '电视当前没有显示 Ani。完成一次授权后，就可以从手机打开 Ani。', 'how');
        else if (n.away) setTvState('away', '电视当前没有显示 Ani。搜索和点播仍会发送到电视，打开 Ani 后即可看到。', 'enable');
        else setTvState('');
        ldShow(!!n.launchDialog);
        lastKeep = !!n.keep;
      })
      .catch(function (e) {
        noticeBusy = false;
        if (e && e.message === 'gone') { setTvState('off', '这个地址已失效（电视上重置过地址），请在电视上重新扫码'); return; }
        if (++noticeFails >= 2) setTvState('off', '电视已断开。请确认电视已唤醒、Ani 正在运行，并且手机和电视连接到同一网络。' +
          (lastKeep === false ? '想在电视休眠或离开 Ani 后继续连接，请先在电视上打开 Ani，再到网页的「设置」中开启「后台保持连接」。' : ''));
        noticeSkip = Math.min(noticeFails - 1, 4);
      });
  }
  setInterval(pollNotice, 2000);
  // 电视上启动时的二维码弹窗 (见 TvRemoteControl.closeLaunchDialog). 这台手机上怎么处理 (记在手机上, 这一条里和设置 → 外观里
  // 是同一个下拉框, 随时能改回来): 每次提示 (默认) = 顶上一条「还开着 [关掉电视上的弹窗] ×」, 点了才关, × 只收起这一条 (本次);
  // 自动关掉 = 打开网页直接关; 不提示也不关 = 什么都不出现. 遥控器关掉后下一轮提示轮询就收起.
  // 关掉之后提示一句以后去哪找码 —— 弹窗里写着的那句随它关掉就看不到了
  var LD_KEY = 'ani-launch-dialog-mode', LD_OLD_KEY = 'ani-launch-dialog-auto';
  var LD_OPTS = [['ask', '每次询问'], ['auto', '自动关闭'], ['off', '保持显示']];
  var ldBar = document.getElementById('ld-bar'), ldSel = ldBar.querySelector('select');
  var ldAutoClosing = false, ldDismissed = false, ldOpen = false;
  // 旧版的勾选框「以后这台手机打开就自动关闭」勾过的, 算「自动关掉」
  function ldMode() {
    try {
      var m = localStorage.getItem(LD_KEY);
      if (m === 'auto' || m === 'off') return m;
      if (localStorage.getItem(LD_OLD_KEY) === '1') return 'auto';
    } catch (e) {}
    return 'ask';
  }
  function ldSetMode(m) {
    try {
      if (m === 'ask') localStorage.removeItem(LD_KEY); else localStorage.setItem(LD_KEY, m);
      localStorage.removeItem(LD_OLD_KEY);
    } catch (e) {}
    ldSel.value = m;
    ldShow(ldOpen);
    document.dispatchEvent(new CustomEvent('ldmode'));
  }
  window.LD_OPTS = LD_OPTS;
  window.ldMode = ldMode;
  window.ldSetMode = ldSetMode;
  // 自动关的那一下电视要等 1.5 秒才真关, 这期间轮询还说「开着」: 不闪出来. × 收起的只管这一次打开
  function ldShow(open) {
    ldOpen = open;
    ldBar.hidden = !open || ldAutoClosing || ldDismissed || ldMode() !== 'ask';
  }
  function ldClose(auto) {
    return post('api/launch-dialog/close', auto ? { auto: '1' } : {}).then(function (r) {
      if (r && r.closed) toast(auto ? '已自动关闭电视上的二维码。可在「设置 → 本机偏好」中更改。需要重新扫码时，长按遥控器播放键。'
        : '已关闭电视上的二维码。需要重新扫码时，长按遥控器播放键，在动作面板右上角扫码。', 6000);
      return r;
    });
  }
  function ldAutoClose() {
    ldAutoClosing = true;
    ldShow(ldOpen);
    ldClose(true).then(function () { setTimeout(function () { ldAutoClosing = false; }, 3000); }, function () { ldAutoClosing = false; });
  }
  ldSel.innerHTML = LD_OPTS.map(function (o) { return '<option value="' + o[0] + '">' + o[1] + '</option>'; }).join('');
  ldSel.value = ldMode();
  // 在这一条里改「以后」: 选自动关掉 = 这次也顺手关; 选不提示 = 收起这一条, 弹窗留给遥控器
  ldSel.addEventListener('change', function () {
    var m = ldSel.value;
    ldSetMode(m);
    if (m === 'auto') ldAutoClose();
    else if (m === 'off') toast('以后不再提醒。请用遥控器关闭电视上的二维码；可在「设置 → 本机偏好」中重新开启提醒。', 5000);
  });
  ldBar.addEventListener('click', function (e) {
    var b = e.target.closest('[data-ld]');
    if (!b) return;
    if (b.getAttribute('data-ld') === 'hide') { ldDismissed = true; ldShow(ldOpen); return; }
    b.disabled = true;
    ldClose(false).then(function () { b.disabled = false; ldShow(false); }, function () { b.disabled = false; fail(); });
  });
  if (ldMode() === 'auto') ldAutoClose();
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
    if (tab === 'settings' && window.loadHistory) window.loadHistory();
  }
  // 别的脚本 (播放记录面板点 ▶ 之后) 切标签用
  window.showTab = show;
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
        '<button type="button" class="x" data-del="' + esc(h) + '" aria-label="删除这条记录" title="删除这条记录">' + window.ICONS.close + '</button></div>';
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
    // 面板 (缓存 / 使用说明) 盖在上面时不刷新, 关上后下一轮接着来
    if (document.hidden || cur !== 'search' || sub !== 'results' || window.sheets.any()) return;
    // 上一个还没回来: 定时器这一下直接跳过; 只有「要求立刻刷新」(force) 才记一笔、回来后补拉 —— 以前定时器撞上也记,
    // 补的又是全量, 响应一慢就每轮全量 + 整页重画, 越慢越全量
    if (resBusy) { if (force) resAgain = true; return; }
    resBusy = true;
    fetchT('api/search/results?v=' + (force ? '' : encodeURIComponent(resVer)))
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
  // 列表项的封面底图 (见样式 .cv): 虚化的一层 + 清楚的一层; 进了视口才拉 (搜索结果一页几十条), 不带 Referer. 播放记录面板也用.
  // 可以给一串候选 (播放记录: 单集剧照 → 横屏图 → 竖版封面), 拉不到就换下一张 (见下面的 error 监听)
  function coverLayers(u) {
    var list = (typeof u === 'string' ? [u] : u || []).filter(Boolean);
    if (!list.length) return '';
    var a = ' src="' + esc(list[0]) + '" data-alt="' + esc(list.slice(1).join(' ')) + '" alt="" loading="lazy" referrerpolicy="no-referrer">';
    return '<img class="cv-bg"' + a + '<img class="cv-art"' + a;
  }
  window.coverLayers = coverLayers;
  // 数据源名字前的图标 (见 RemoteSourceIcons): 电视按 id 回这个源自己的图标; 没有 (404) 或拉不到就换成首字母圆标 (同 App).
  // 拉不到的记下来, 列表重画时直接出圆标, 不再每次去要
  var srcIconBad = {};
  function srcIcon(id, name) {
    var ph = esc(((name || '?').trim().charAt(0) || '?').toUpperCase());
    if (!id || srcIconBad[id]) return '<span class="src-ic ph" data-ph="' + ph + '"></span>';
    return '<img class="src-ic" src="api/source-icon?id=' + encodeURIComponent(id) + '" alt="" data-id="' + esc(id) + '" data-ph="' + ph + '">';
  }
  window.srcIcon = srcIcon;
  document.addEventListener('error', function (e) {
    var t = e.target;
    if (!t || t.tagName !== 'IMG' || !t.classList || !t.classList.contains('src-ic')) return;
    srcIconBad[t.getAttribute('data-id')] = true;
    var s = document.createElement('span');
    s.className = 'src-ic ph';
    s.setAttribute('data-ph', t.getAttribute('data-ph') || '?');
    t.replaceWith(s);
  }, true);
  // iOS Safari 要页面上有 touchstart 监听, 按住时 :active 样式才生效 (列表项封面按下的反馈靠它)
  document.addEventListener('touchstart', function () {}, { passive: true });
  // 列表项封面按下的反馈 (压暗 + 播放图标): 用 .press 而不是 :active —— 压暗那层画在行上 (与封面同一块), 不在热区按钮上
  function pressOff() {
    [].forEach.call(document.querySelectorAll('.press'), function (x) { x.classList.remove('press'); });
  }
  document.addEventListener('pointerdown', function (e) {
    var b = e.target.closest && e.target.closest('.res-play');
    if (b && !b.closest('.selecting')) b.parentNode.classList.add('press'); // 多选时点行 = 勾选, 不给播放的反馈
  });
  ['pointerup', 'pointercancel'].forEach(function (t) { document.addEventListener(t, pressOff); });
  // 播放卡上的数据源胶囊: 点了滚到下面的数据源选择区 (按源筛选的胶囊那一行)
  document.addEventListener('click', function (e) {
    if (!e.target.closest('#player-now .now-srcname')) return;
    // 滚到正在播的那一条: 展开它所在那一段, 滚到屏幕中间, 底色亮一下 (前后就是同一个源的别的线路, 换线最顺手).
    // 列表每次轮询都可能整块重画, 所以「亮到什么时候」记在 window.flashSel 里由 groupHtml 画, 不靠这个节点上的 class.
    // 它不在列表里 (筛选看的是别的源 / 超出每源 40 条) 就退回滚到数据源胶囊那一行
    var st = window.lastPlayerState && window.lastPlayerState(), sl = document.getElementById('player-sources');
    if (st && sl) {
      window.openSourceSection(st.selectedSourceId);
      window.flashSel = { id: st.selectedId, until: Date.now() + 1600 };
      sl.innerHTML = window.renderPlayerList(st);
      var cur = sl.querySelector('.item.sel');
      if (cur) {
        cur.scrollIntoView({ behavior: 'smooth', block: 'center' });
        setTimeout(function () {
          var x = document.querySelector('#player-sources .item.flash');
          if (x) x.classList.remove('flash');
        }, 1600);
        return;
      }
    }
    var t = document.getElementById('player-chips');
    if (t) t.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
  window.addEventListener('scroll', pressOff, { passive: true });
  // 播放卡上的图标 (仿 iOS 播放卡): 实心播放 / 暂停, 圈里写着 10 的后退 / 前进, 下载 (缓存)
  var REPLAY = 'M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z';
  var TEN = '<text x="12" y="15.4" text-anchor="middle" font-size="6.2" font-weight="700">10</text>';
  window.ICONS = {
    play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.14v13.72a1 1 0 0 0 1.52.85l10.6-6.86a1 1 0 0 0 0-1.7L9.52 4.29A1 1 0 0 0 8 5.14z"/></svg>',
    pause: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 5h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zm7 0h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"/></svg>',
    back10: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="' + REPLAY + '"/>' + TEN + '</svg>',
    fwd10: '<svg viewBox="0 0 24 24" aria-hidden="true"><path transform="matrix(-1 0 0 1 24 0)" d="' + REPLAY + '"/>' + TEN + '</svg>',
    // 音量条两头: 小喇叭 (点了静音) / 静音喇叭 / 大喇叭 (装饰)
    volLow: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 9v6h4l5 5V4l-5 5H7z"/></svg>',
    volOff: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>',
    volUp: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>',
    download: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a1 1 0 0 1 1 1v8.59l2.3-2.3a1 1 0 1 1 1.4 1.42l-4 4a1 1 0 0 1-1.4 0l-4-4a1 1 0 1 1 1.4-1.42l2.3 2.3V4a1 1 0 0 1 1-1zM5 18a1 1 0 0 1 1 1v.5c0 .28.22.5.5.5h11a.5.5 0 0 0 .5-.5V19a1 1 0 1 1 2 0v.5a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 19.5V19a1 1 0 0 1 1-1z"/></svg>',
    // 按钮用的图标 (Material 线性款): 列表行里反复出现的通用动作只放图标, 其余 图标 + 文字, 见 STYLE 的 .ic / .icb
    trash: svgIcon('M16 9v10H8V9h8m-1.5-6h-5l-1 1H5v2h14V4h-3.5l-1-1zM18 7H6v12c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7z'),
    edit: svgIcon('M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM5.92 19H5v-.92l9.06-9.06.92.92L5.92 19zM20.71 5.63l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83a1 1 0 0 0 0-1.41z'),
    up: svgIcon('M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z'),
    down: svgIcon('M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z'),
    copy: svgIcon('M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z'),
    share: svgIcon('M16 5l-1.42 1.42-1.59-1.59V16h-1.98V4.83L9.42 6.42 8 5l4-4 4 4zm4 5v11c0 1.1-.9 2-2 2H6a2 2 0 0 1-2-2V10c0-1.11.89-2 2-2h3v2H6v11h12V10h-3V8h3a2 2 0 0 1 2 2z'),
    refresh: svgIcon('M17.65 6.35A7.96 7.96 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z'),
    tv: svgIcon('M21 3H3c-1.11 0-2 .89-2 2v12a2 2 0 0 0 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5a2 2 0 0 0-2-2zm0 14H3V5h18v12z'),
    logout: svgIcon('M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5-5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z'),
    plus: svgIcon('M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z'),
    close: svgIcon('M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z'),
    star: svgIcon('M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z')
  };
  function svgIcon(d) { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="' + d + '"/></svg>'; }
  // 底图拉不到: 换下一个候选 (地址里没有空格, 用空格分隔); 全都拉不到就把这一层藏起来, 露出卡片本来的底色
  document.addEventListener('error', function (e) {
    var img = e.target;
    if (!img || img.tagName !== 'IMG' || !(img.classList.contains('cv-art') || img.classList.contains('cv-bg'))) return;
    var alt = (img.getAttribute('data-alt') || '').split(' ').filter(Boolean);
    if (alt.length) {
      img.setAttribute('data-alt', alt.slice(1).join(' '));
      img.src = alt[0];
    } else {
      img.style.display = 'none';
    }
  }, true);
  // 封面下载完再显示 (样式里没 .ld 的底图是透明的, 加上就淡入), 不从空白突然跳出来
  document.addEventListener('load', function (e) {
    var img = e.target;
    if (img && img.tagName === 'IMG' && (img.classList.contains('cv-art') || img.classList.contains('cv-bg'))) img.classList.add('ld');
  }, true);
  function markLoaded(root) {
    [].forEach.call(root.querySelectorAll('img.cv-art, img.cv-bg'), function (i) { if (i.complete && i.naturalWidth) i.classList.add('ld'); });
  }
  // ---- 左右滑出按钮 (搜索结果 / 播放记录, 仿 iOS 列表) ----
  // 行包在 .sw 里, 按钮垫在行下面 (.sw-acts.l 在左、.r 在右); 横着拖 = 行跟着手指走露出按钮, 松手按露出过半与否吸附开合.
  // 竖着滑照常滚动 (行上 touch-action: pan-y, 先动 8px 看方向); 拖过的那一下不算点击; 同时只开一行, 点别处收起,
  // 点开着的那一行主体 = 收起 (不打开详情). 事件全挂在 document 上, 两个列表共用
  var swOpen = null, swDrag = null, swSwallow = false;
  function swSet(row, x) {
    row._x = x;
    row.style.transform = x ? 'translateX(' + x + 'px)' : '';
    // 行往哪边移就藏起对面那组按钮 (它往行底下多铺的那截会从行后面露出来); 回到 0 不动, 收回动画途中不闪
    // 回到 0 等收回动画走完再清 (卡片里的平铺行靠这两个类决定停着时藏按钮)
    var sw = row.parentNode;
    // 行让出来多宽: 平铺行 (透明) 的按钮只露这一截 (见样式 .sw.flat > .sw-acts 的 clip-path), 否则一动整颗按钮就从透明的行后面透出来
    sw.style.setProperty('--sx', Math.abs(x) + 'px');
    clearTimeout(sw._xt);
    if (x) {
      sw.classList.toggle('xr', x > 0);
      sw.classList.toggle('xl', x < 0);
    } else {
      sw._xt = setTimeout(function () { if (!row._x) sw.classList.remove('xr', 'xl'); }, 260);
    }
  }
  function swClose(sw) {
    if (!sw) return;
    var row = sw.querySelector(':scope > .item, :scope > .sw-row');
    if (row) swSet(row, 0);
    sw.classList.remove('swiped', 'full');
    [].forEach.call(sw.querySelectorAll(':scope > .sw-acts > .sw-btn'), swReset);
    if (swOpen === sw) swOpen = null;
  }
  /** 挨着行的那颗按钮: 右边一组的第一颗 / 左边一组的最后一颗 (拖过头时撑宽的、滑到底时执行的就是它) */
  function swEdge(sw, side) {
    var a = sw.querySelector(':scope > .sw-acts.' + side);
    return a ? (side === 'r' ? a.firstElementChild : a.lastElementChild) : null;
  }
  // 拖过按钮宽度: 边上那颗按钮跟着撑宽 (行与按钮之间不留空); 拖过行宽一半 = 「滑到底」, 按钮内容贴到行边上提示松手就执行
  function swStretch(d, x) {
    var side = x > 0 ? 'l' : 'r', btn = side === 'l' ? d.bl : d.br, other = side === 'l' ? d.br : d.bl;
    swReset(other);
    var over = btn ? Math.abs(x) - (side === 'l' ? d.l : d.r) : 0;
    if (btn) btn.style.width = over > 0 ? (btn._w + over) + 'px' : '';
    var full = over > 0 && Math.abs(x) >= d.w * 0.5;
    if (full !== d.full) {
      d.full = full;
      d.fullSide = side;
      d.sw.classList.toggle('full', full);
      try { if (full && navigator.vibrate) navigator.vibrate(10); } catch (e) {}
    }
    if (btn) swPad(btn, side, full ? Math.abs(x) - btn._w : 0);
  }
  // 滑到底时图标和字作为一组贴到行边: 撑宽出来的那截用内边距占掉, 剩下原按钮宽的一截里照常居中 (各自左对齐的话字比图标宽, 两者错开)
  function swPad(btn, side, p) {
    btn.style.paddingRight = side === 'r' && p > 0 ? p + 'px' : '';
    btn.style.paddingLeft = side === 'l' && p > 0 ? p + 'px' : '';
  }
  function swReset(btn) {
    if (!btn) return;
    btn.style.width = '';
    btn.style.paddingLeft = btn.style.paddingRight = '';
  }
  // 滑到底松手: 直接执行边上那颗按钮. 删除 = 行滑出去、按钮铺满, 列表随后把整行收起 (删不成由列表 swClose 放回来);
  // 收藏 = 停在打开的位置 (菜单挂在按钮下面); 其它 (缓存) = 行收回
  function swFire(d, side) {
    var btn = side === 'l' ? d.bl : d.br, sign = side === 'l' ? 1 : -1;
    d.sw.classList.remove('full');
    if (btn.classList.contains('del')) {
      d.sw.classList.add('swiped');
      btn.style.width = d.w + 'px';
      swPad(btn, side, d.w - btn._w);
      swSet(d.row, sign * d.w);
      if (swOpen === d.sw) swOpen = null;
    } else if (btn.hasAttribute('data-coll')) {
      swReset(btn);
      swSet(d.row, sign * (side === 'l' ? d.l : d.r));
      swOpen = d.sw;
    } else {
      swReset(btn);
      swSet(d.row, 0);
      if (swOpen === d.sw) swOpen = null;
    }
    btn.click();
  }
  window.swClose = swClose;
  function swWidth(sw, side) {
    var a = sw.querySelector(':scope > .sw-acts.' + side);
    return a ? a.offsetWidth : 0;
  }
  document.addEventListener('pointerdown', function (e) {
    swSwallow = false;
    var t = e.target;
    var row = t.closest && t.closest('.sw > .item, .sw > .sw-row');
    var onActs = t.closest && t.closest('.sw-acts');
    if (swOpen && !onActs && !(row && row.parentNode === swOpen) && !(t.closest && t.closest('.ep-menu'))) {
      swClose(swOpen);
      if (row) swSwallow = true; // 点的是另一行: 这一下只收起, 不当作点击
    }
    if (!row || onActs || row.closest('.selecting') || (e.pointerType === 'mouse' && e.button !== 0)) return;
    var sw = row.parentNode;
    swDrag = { row: row, sw: sw, x0: e.clientX, y0: e.clientY, base: row._x || 0, id: e.pointerId, mode: 0,
      l: swWidth(sw, 'l'), r: swWidth(sw, 'r'), w: sw.offsetWidth, bl: swEdge(sw, 'l'), br: swEdge(sw, 'r'), full: false };
    [swDrag.bl, swDrag.br].forEach(function (b) { if (b) b._w = b.offsetWidth; });
  });
  document.addEventListener('pointermove', function (e) {
    var d = swDrag;
    if (!d || e.pointerId !== d.id) return;
    var dx = e.clientX - d.x0, dy = e.clientY - d.y0;
    if (!d.mode) {
      if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return;
      if (Math.abs(dy) >= Math.abs(dx)) { swDrag = null; return; } // 竖着的: 交给滚动
      d.mode = 1;
      pressOff(); // 横着拖了就不是在按封面
      d.row.classList.add('dragging');
      d.sw.classList.add('sw-drag');
      try { d.row.setPointerCapture(e.pointerId); } catch (x) {}
      if (swOpen && swOpen !== d.sw) swClose(swOpen);
    }
    // 超过按钮宽度后照样跟手 (见 swStretch, 拖过一半松手直接执行); 没有按钮的那一侧拖不动, 最多拖一整行宽
    var x = d.base + dx;
    if (!d.l && x > 0) x = 0;
    if (!d.r && x < 0) x = 0;
    x = Math.max(-d.w, Math.min(d.w, x));
    swSet(d.row, x);
    swStretch(d, x);
  });
  function swEnd(e) {
    var d = swDrag;
    if (!d || e.pointerId !== d.id) return;
    swDrag = null;
    if (!d.mode) return;
    d.row.classList.remove('dragging');
    d.sw.classList.remove('sw-drag');
    if (d.full) {
      swFire(d, d.fullSide); // 先执行 (那个 click 不能被下面的「吞掉拖完的点击」吞了)
      swSwallow = true;
      return;
    }
    swSwallow = true; // 拖完松手紧跟着的那个 click 不算
    swReset(d.bl);
    swReset(d.br);
    var x = d.row._x || 0, to = 0;
    if (d.l && x > d.l / 2) to = d.l;
    else if (d.r && x < -d.r / 2) to = -d.r;
    swSet(d.row, to);
    if (to) swOpen = d.sw; else if (swOpen === d.sw) swOpen = null;
  }
  document.addEventListener('pointerup', swEnd);
  document.addEventListener('pointercancel', swEnd);
  // 行上不许原生拖拽 (拖封面图 / 拖选中的文字): 一开始拖浏览器就发 pointercancel, 横滑刚动就被掐断 (电脑上鼠标实测)
  document.addEventListener('dragstart', function (e) {
    var t = e.target && e.target.nodeType === 3 ? e.target.parentNode : e.target;
    if (t && t.closest && t.closest('.sw')) e.preventDefault();
  });
  // 捕获阶段, 先于各列表自己的点击处理
  document.addEventListener('click', function (e) {
    if (swSwallow) { swSwallow = false; e.stopPropagation(); e.preventDefault(); return; }
    var row = e.target.closest && e.target.closest('.sw > .item, .sw > .sw-row');
    if (row && row._x) { swClose(row.parentNode); e.stopPropagation(); e.preventDefault(); }
  }, true);
  // 点了露出来的按钮 (缓存 / 删除) 行就收回去; 「收藏」等它的小菜单关了再收
  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('.sw-btn');
    if (b && !b.hasAttribute('data-coll') && !b.closest('.sw.swiped')) swClose(b.closest('.sw'));
  });
  /**
   * 一行包成可左右滑的: left / right 是垫在下面的按钮 (HTML, 可空), row 是行本身 (独立卡片的行带 .item, 卡片里的行带 .sw-row);
   * cls 给外层加的类 (卡片里的行用 'flat', 见样式 .sw.flat).
   */
  function swRow(left, right, row, cls) {
    return '<div class="sw' + (cls ? ' ' + cls : '') + '">' + (left ? '<div class="sw-acts l">' + left + '</div>' : '') +
      (right ? '<div class="sw-acts r">' + right + '</div>' : '') + row + '</div>';
  }
  window.swRow = swRow;
  /** box 里有行正开着 / 正在拖: 定时重画的列表 (缓存 / 订阅) 这时先别重画, 否则开着的行被弹回去 */
  window.swBusy = function (box) {
    return !!((swOpen && box.contains(swOpen)) || (swDrag && swDrag.mode && box.contains(swDrag.sw)));
  };
  /**
   * 一次性滑开提示: 手势是看不见的, 光靠文字 / 说明页很难被发现 (缓存的删除只有滑出来这一条路). 列表第一次真有行、而且看得见时,
   * 第一行 (rowSel 挑哪一行) 自动往左滑开一截露出右边的按钮, 停一下再弹回去. 每个列表只做一次, 记在这台手机的浏览器里;
   * 存储用不了 (无痕 / 内置浏览器) 就不做, 免得每次打开都滑.
   */
  function swPeek(box, key, rowSel) {
    var k = 'remote.peek.' + key;
    try { if (localStorage.getItem(k)) return; } catch (e) { return; }
    if (swOpen || swDrag || box.closest('.selecting') || document.hidden || !box.offsetParent) return;
    var row = box.querySelector(rowSel || '.sw > .item, .sw > .sw-row');
    var sw = row && row.parentNode, w = sw ? swWidth(sw, 'r') : 0;
    if (!w) return;
    var r = sw.getBoundingClientRect();
    if (r.top < 0 || r.top > innerHeight - 60) return; // 还没滚进屏幕: 等下次画的时候再说
    try { localStorage.setItem(k, '1'); } catch (e) { return; }
    setTimeout(function () {
      if (swOpen || swDrag || !row.isConnected) return;
      swSet(row, -Math.round(w * 0.6));
      setTimeout(function () { if (row.isConnected && swOpen !== sw && !(swDrag && swDrag.row === row)) swSet(row, 0); }, 700);
    }, 450);
  }
  window.swPeek = swPeek;

  // ---- 长按多选删除 (播放记录 / 订阅 / 缓存标签的每一集) ----
  // 行带 data-lp (值 = 这一行的 id): 按住 480ms 不动 = 在这一行上发 longpress 事件, 列表自己决定进多选 (selStart).
  // 多选时: 列表容器带 .selecting, 行左边出勾选圈; 点行 = 勾 / 取消 (行里原来的点击都不触发, 滑动也不响应);
  // 底部操作栏盖住底栏: 取消 / 已选 N 项 / 全选 / 删除 (先确认). 点列表外面 (关面板、切标签) 退出多选.
  // 列表重画后调 selSync(容器) 把勾补回去 (容器常驻, 重画只换里面的行; 已经不在的 id 顺带丢掉)
  var lp = null, sel = null;
  var selBar = document.getElementById('sel-bar');
  function lpCancel() { if (lp) { clearTimeout(lp.t); lp = null; } }
  document.addEventListener('pointerdown', function (e) {
    lpCancel();
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    var row = e.target.closest && e.target.closest('[data-lp]');
    if (!row || row.closest('.selecting')) return;
    lp = { x: e.clientX, y: e.clientY, id: e.pointerId, t: setTimeout(function () {
      lp = null;
      // 按住期间列表重画过 (订阅更新中每 2 秒重拉): 换成新画出来的同一行
      if (!row.isConnected) row = document.querySelector('[data-lp="' + CSS.escape(row.getAttribute('data-lp')) + '"]');
      if (!row) return;
      swDrag = null; pressOff();
      swSwallow = true; // 松手紧跟着的那个 click 不算 (否则刚勾上的又被点掉, 或者打开了详情)
      if (swOpen) swClose(swOpen);
      try { if (navigator.vibrate) navigator.vibrate(12); } catch (x) {}
      row.dispatchEvent(new CustomEvent('longpress', { bubbles: true }));
    }, 480) };
  });
  document.addEventListener('pointermove', function (e) {
    if (lp && e.pointerId === lp.id && Math.abs(e.clientX - lp.x) + Math.abs(e.clientY - lp.y) > 8) lpCancel();
  });
  document.addEventListener('pointerup', lpCancel);
  document.addEventListener('pointercancel', lpCancel);
  // 安卓长按会弹系统菜单 (图片另存为之类)
  document.addEventListener('contextmenu', function (e) { if (e.target.closest && e.target.closest('[data-lp]')) e.preventDefault(); });
  function selRows() { return [].slice.call(sel.o.box.querySelectorAll('[data-lp]')); }
  /** 进入多选. o = { box: 列表容器, ask(n): 确认删除的话, del(ids): Promise<是否删成> }; first = 长按的那一行, 先勾上 */
  function selStart(o, first) {
    selEnd();
    sel = { o: o, ids: {} };
    if (first) sel.ids[first] = true;
    o.box.classList.add('selecting');
    selBar.querySelector('.sel-del').innerHTML = window.ICONS.trash + '删除';
    selBar.hidden = false;
    document.body.classList.add('sel-on');
    selSync(o.box);
  }
  function selEnd() {
    if (!sel) return;
    var box = sel.o.box;
    sel = null;
    box.classList.remove('selecting');
    [].forEach.call(box.querySelectorAll('[data-lp].picked'), function (r) { r.classList.remove('picked'); });
    selBar.hidden = true;
    document.body.classList.remove('sel-on');
  }
  function selSync(box) {
    if (!sel || sel.o.box !== box) return;
    var rows = selRows(), live = {}, n = 0;
    if (!rows.length) { selEnd(); return; }
    rows.forEach(function (r) {
      var id = r.getAttribute('data-lp'), on = !!sel.ids[id];
      if (on) { live[id] = true; n++; }
      r.classList.toggle('picked', on);
    });
    sel.ids = live;
    selBar.querySelector('.sel-n').textContent = n ? '已选 ' + n + ' 项' : '点选要删除的项';
    selBar.querySelector('[data-sel="all"]').textContent = n === rows.length ? '全不选' : '全选';
    selBar.querySelector('[data-sel="del"]').disabled = !n;
  }
  window.selStart = selStart;
  window.selEnd = selEnd;
  window.selSync = selSync;
  // 捕获阶段: 多选时点行只管勾选, 不让行自己的点击处理 (打开详情 / 播放 / 删除钮) 收到
  document.addEventListener('click', function (e) {
    if (!sel || e.defaultPrevented) return; // 已被滑动那边吞掉的 (长按松手那一下)
    var t = e.target;
    if (t.closest('#sel-bar')) return;
    var row = t.closest('[data-lp]');
    if (row && sel.o.box.contains(row)) {
      e.stopPropagation();
      e.preventDefault();
      var id = row.getAttribute('data-lp');
      if (sel.ids[id]) delete sel.ids[id]; else sel.ids[id] = true;
      selSync(sel.o.box);
    } else if (!sel.o.box.contains(t)) {
      selEnd(); // 点了列表外面: 退出多选, 这一下照常生效
    }
  }, true);
  selBar.addEventListener('click', function (e) {
    var b = e.target.closest('[data-sel]');
    if (!b || !sel) return;
    var k = b.getAttribute('data-sel'), o = sel.o;
    if (k === 'cancel') { selEnd(); return; }
    if (k === 'all') {
      var rows = selRows(), all = rows.every(function (r) { return sel.ids[r.getAttribute('data-lp')]; });
      sel.ids = {};
      if (!all) rows.forEach(function (r) { sel.ids[r.getAttribute('data-lp')] = true; });
      selSync(o.box);
      return;
    }
    var ids = Object.keys(sel.ids);
    if (!ids.length || !confirm(o.ask(ids.length))) return;
    b.disabled = true;
    o.del(ids).then(function (ok) {
      if (ok) selEnd(); else b.disabled = false;
    }, function () { b.disabled = false; fail(); });
  });

  // 列表按行增量更新 (搜索结果): 内容没变的行原样留着 (封面不重建、不重新解码, 不会闪白), 变了的行才换, 翻页只在末尾追加.
  // 行的「内容」按生成它的那段 HTML 比 (记在节点上), 不看当前 DOM —— 底图换过候选、按下态这些都不算变
  function patchList(box, rows) {
    var kids = box.children;
    for (var i = 0; i < rows.length; i++) {
      var cur = kids[i];
      if (cur && cur._h === rows[i]) continue;
      var t = document.createElement('div');
      t.innerHTML = rows[i];
      var el = t.firstChild;
      el._h = rows[i];
      if (cur) box.replaceChild(el, cur); else box.appendChild(el);
      markLoaded(el);
    }
    while (kids.length > rows.length) box.removeChild(box.lastChild);
  }
  window.patchList = patchList;
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
    // 关键词一行, 条数右对齐在同一行 (标题折行时条数贴着第一行); 筛选条件有才另起一行
    resHead.innerHTML = '<div class="res-top"><div class="res-q">' + (s.keywords ? '「' + esc(s.keywords) + '」' : '筛选结果') + '</div>' +
      '<span class="res-n">' + (s.refreshing ? '搜索中' : items.length + ' 条') + '</span></div>' +
      (s.filters ? '<div class="res-sub">' + esc(s.filters) + '</div>' : '');
    if (!items.length) {
      resList.innerHTML = '<div class="empty"><p>' + (s.refreshing ? '正在电视上搜索…'
        : s.error ? '搜索失败：' + esc(s.error) : '没有找到相关条目') + '</p></div>';
    } else {
      patchList(resList, items.map(function (x) {
        // 点主体 = 电视打开详情页; 点右边的封面 (没封面的是 ▶) = 直接播放.
        // 右滑露出「缓存」(打开这部番的缓存面板), 左滑露出「收藏」(设收藏状态)
        return swRow(
          '<button type="button" class="sw-btn cache" data-cache="' + x.id + '" data-title="' + esc(x.title) + '">' +
            window.ICONS.download + '缓存</button>',
          '<button type="button" class="sw-btn coll" data-coll="' + x.id + '">' + window.ICONS.star + '收藏</button>',
          '<div class="item res-item' + (x.cover ? ' cv' : '') + (x.blur ? ' nsfw-blur' : '') + '" data-sid="' + x.id + '">' +
          (x.cover ? coverLayers(x.cover) : '') +
          '<span class="t">' + esc(x.title) + (x.nsfw ? '<span class="res-r18">R18</span>' : '') + '</span>' +
          (x.info ? '<span class="m">' + esc(x.info) + '</span>' : '') +
          (x.rating ? '<span class="res-rate">' + esc(x.rating) + '</span>' : '') +
          '<button type="button" class="res-play" aria-label="播放"><span class="play-glyph">' + window.ICONS.play + '</span></button></div>');
      }));
    }
    var foot = '';
    if (items.length) {
      if (s.appending) foot = '<p class="hint res-end">正在加载…</p>';
      else if (s.error) foot = '<button type="button" class="ghost wide" data-more="1">加载失败，点这里重试</button>';
      else if (s.end) foot = '<p class="hint res-end">没有更多了</p>';
      else if (s.live) foot = '<button type="button" class="ghost wide" data-more="1">加载更多</button>';
      else foot = '<p class="hint res-end">电视已离开搜索页</p>' +
        '<button type="button" class="ghost wide ic" data-resume="1">' + window.ICONS.tv + '让电视回到搜索页，继续加载</button>';
    }
    resFoot.innerHTML = foot;
    observeMore();
    swPeek(resList, 'res');
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
  resFoot.addEventListener('click', function (e) {
    if (e.target.closest('[data-more]')) { askMore(); return; }
    // 电视离开了搜索页: 让它带着这次的查询回去 (从第一页重新加载, 之后照常往下翻)
    var r = e.target.closest('[data-resume]');
    if (r && !r.disabled) {
      r.disabled = true;
      post('api/search/results/resume', {}).then(function (x) { r.disabled = false; toast(x.message); })
        .catch(function () { r.disabled = false; fail(); });
    }
  });
  resList.addEventListener('click', function (e) {
    var b = e.target.closest('.res-item');
    if (!b || b.classList.contains('busy')) return;
    var play = !!e.target.closest('.res-play');
    b.classList.add('busy');
    if (play) b.classList.add('hit'); // 封面上的播放图标在请求回来前一直亮着
    post(play ? 'api/search/play' : 'api/search/open', { id: b.getAttribute('data-sid') })
      .then(function (r) {
        b.classList.remove('busy', 'hit');
        toast(r.message);
        // 进了播放页就切到「播放器」, 数据源结果陆续回来可以直接在手机上挑; 打开详情页则留在结果里
        if (r.player) setTimeout(function () { show('player'); }, 1200);
      })
      .catch(function () { b.classList.remove('busy', 'hit'); fail(); });
  });

  // 搜索结果左滑「收藏」: 先读这部番现在的收藏状态, 弹一个同选集列表样式的小菜单 (当前那项打 ✓), 点一项就设;
  // 菜单关掉时那一行收回去
  var COLL_TYPES = [['WISH', '想看'], ['DOING', '在看'], ['DONE', '看过'], ['ON_HOLD', '搁置'], ['DROPPED', '抛弃'],
    ['NOT_COLLECTED', '取消收藏']];
  var collMenu = null;
  function closeCollMenu() {
    if (!collMenu) return;
    var sw = collMenu._sw;
    collMenu.remove();
    collMenu = null;
    swClose(sw);
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('[data-coll]');
    if (!b) return;
    var id = b.getAttribute('data-coll');
    if (collMenu) closeCollMenu();
    fetch('api/subject/collection?id=' + encodeURIComponent(id)).then(function (r) { return r.json(); }).then(function (d) {
      if (!d.ok) { toast(d.message || '读取收藏状态失败'); swClose(b.closest('.sw')); return; }
      var m = document.createElement('div');
      m.className = 'ep-menu coll-menu';
      m.id = 'coll-menu';
      m.setAttribute('role', 'listbox');
      m.innerHTML = COLL_TYPES.map(function (c) {
        var cur = c[0] === d.collection || (c[0] === 'NOT_COLLECTED' && !d.collection);
        // 属性名别用 data-ctype: 「评论与评分」那排收藏按钮用的就是它
        return '<button type="button" role="option" class="ep-opt' + (cur ? ' cur' : '') + '" data-colltype="' + c[0] + '">' +
          '<span class="ep-mark">' + (cur ? '✓' : '') + '</span><span class="ep-name">' +
          (c[0] === 'NOT_COLLECTED' && cur ? '未收藏' : c[1]) + '</span></button>';
      }).join('');
      document.body.appendChild(m);
      // 右对齐在按钮下面; 下面放不下 (离底栏太近) 就开在上面
      var r = b.getBoundingClientRect();
      m.style.left = (Math.max(16, Math.min(r.right - m.offsetWidth, innerWidth - 16 - m.offsetWidth)) + scrollX) + 'px';
      var top = r.bottom + 6;
      if (top + m.offsetHeight > innerHeight - 72) top = Math.max(16, r.top - 6 - m.offsetHeight);
      m.style.top = (top + scrollY) + 'px';
      m._sw = b.closest('.sw');
      m._sid = id;
      collMenu = m;
    }).catch(function () { swClose(b.closest('.sw')); fail(); });
  });
  document.addEventListener('click', function (e) {
    if (!collMenu) return;
    var o = e.target.closest && e.target.closest('#coll-menu .ep-opt');
    if (o) {
      var id = collMenu._sid, cur = o.classList.contains('cur');
      closeCollMenu();
      if (cur) return;
      post('api/subject/collection', { id: id, type: o.getAttribute('data-colltype') })
        .then(function (r) {
          toast(r.message);
          // 在「挑番缓存」面板里改的: 这部番可能换了分段 (想看 → 在看), 列表重读
          if (r.ok && window.loadPick) window.loadPick();
        })
        .catch(fail);
      return;
    }
    if (!e.target.closest('#coll-menu') && !e.target.closest('[data-coll]')) closeCollMenu();
  }, true);
  document.querySelector('.tabbar').addEventListener('click', closeCollMenu);

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
    // 缓存面板等盖在播放卡上时不刷新 (看不见), 关上后下一轮接着来
    if (document.hidden || cur !== 'player' || window.sheets.any()) return;
    // 定时器撞上在途请求直接跳过, 只有 force 才记一笔补拉 (同 pollResults: 以前补的是全量, 慢响应时每轮全量)
    if (busy) { if (force) again = true; return; }
    busy = true;
    fetchT('api/player?v=' + (force ? '' : encodeURIComponent(ver)) + query())
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

  // 画「播放器」标签那张卡, art = 底图候选 (剧照 → 横屏图, 铺满卡片 + 半透明底色层, 见样式 .now-card). 轮询时卡片常整张
  // 重画: 同一组图就把原来的 <img> 挪进新卡片, 不重新解码、不闪
  function paintNow(now, html, art) {
    var key = art && art.length ? art.join(' ') : '';
    var old = key && now.getAttribute('data-art') === key ? [].slice.call(now.querySelectorAll('.now-card > .cv-bg, .now-card > .cv-art')) : [];
    now.innerHTML = html;
    now.setAttribute('data-art', key);
    var card = now.querySelector('.now-card');
    if (!card || !key) return;
    card.classList.add('cv');
    if (old.length) old.forEach(function (i) { card.appendChild(i); });
    else card.insertAdjacentHTML('beforeend', window.coverLayers(art));
    card.insertAdjacentHTML('beforeend', '<div class="now-scrim"></div>');
  }

  // 播放卡剧名下面「第几集」那一行, 兼做选集 (原来下面单独一块「选集」下拉框, 与这一行重复): 一行字 + 箭头, 点了弹
  // 自己画的选集列表 (openEpMenu). 不用原生 select: iPhone 上它的菜单宽度由系统定, 长集名折成两行. 集名前的双空格换成「·」
  function epLine(s) {
    var text = s.episode ? s.episode.replace(/\s{2,}/g, ' · ') : '';
    var list = s.episodes || [];
    if (!list.length) return text ? '<div class="now-ep">' + esc(text) + '</div>' : '';
    var cur = list.filter(function (e) { return e.current; })[0];
    // 看过没有: 电视选集列表的标签以「✓ 」开头 (见 RemotePlayerHandle.episodeLabel). 当前集换成这一行的写法时把 ✓ 带上,
    // 否则下拉框里恰恰只有当前这集没有标记
    var seen = !!(cur && /^✓/.test(cur.label));
    return '<button type="button" class="now-ep now-ep-pick" id="ep-pick" aria-haspopup="listbox" aria-label="选集"><span class="now-ep-t">' +
      (seen && text ? '<span class="now-ep-seen" title="看过">✓</span>' : '') +
      esc(text || (cur ? cur.label.replace(/^✓\s*/, '') : '选集')) + '</span>' +
      '<span class="now-ep-caret" aria-hidden="true"></span></button>';
  }

  function render(s) {
    var now = document.getElementById('player-now');
    var chips = document.getElementById('player-chips');
    var src = document.getElementById('player-sources');
    if (!s.available) {
      closeEpMenu();
      var msg = s.reason === 'background'
        ? '电视当前不在播放页' + (s.title ? '：' + esc(s.title) : '')
        : '电视上没有正在播放的内容';
      if (s.upNext) {
        // 什么都没在播: 同动作面板那张「接下来播放」卡, 点一下电视直接进播放页
        var u = s.upNext;
        // 复用播放时那张卡的结构与样式: 剧名 / 副标题 / 进度 / 按钮排, 只是按钮只有一颗
        paintNow(now, '<div class="card now-card"><div class="now-head"><div class="now-title now-link" data-subject="' + u.subjectId +
          '" data-title="' + esc(u.title) + '">' + esc(u.title) + '</div>' + cacheEntry(u.subjectId, u.title) + '</div>' +
          '<div class="now-src">' + (u.continuing ? '继续播放' : '接下来播放') + (u.episode ? '：' + esc(u.episode) : '') + '</div>' +
          (u.continuing && u.duration
            ? '<div class="progress"><div class="track"><div style="width:' + Math.min(100, u.position * 100 / u.duration) +
              '%"></div></div><div class="time">' + mmss(u.position) + ' / ' + mmss(u.duration) + '</div></div>'
            : '') +
          '<div class="ctrls"><button class="main ic" id="play-upnext">' + window.ICONS.play + '在电视上播放</button></div></div>', u.art);
      } else {
        paintNow(now, '<div class="empty"><p>' + msg + '</p>' + sessionChip(s.session) +
          (s.reason === 'background' ? '<button class="primary ic" id="open-player">' + window.ICONS.tv + '在电视上打开播放器</button>' : '') + '</div>', null);
      }
      chips.innerHTML = '';
      document.getElementById('player-filters').innerHTML = '';
      lastFiltersHtml = '';
      src.innerHTML = '';
      hooks.unavailable.forEach(function (h) { h(s); });
      return;
    }
    // 正在播哪个数据源放在剧名下、播放键上方: 候选列表里的「正在播放」角标要往下翻很远才看得到
    paintNow(now, '<div class="card now-card"><div class="now-head"><div class="now-title now-link" data-subject="' + s.subjectId +
      '" data-title="' + esc(s.title) + '">' + esc(s.title) + '</div>' + cacheEntry(s.subjectId, s.title) + '</div>' +
      // 第几集单独一行 (资源名常常看不出来: BT / 缓存的整季合集就叫「[01-12 合集]」), 这一行本身就是选集下拉框, 见 epLine
      epLine(s) +
      '<div class="now-pick"><span class="now-label' + (s.background ? '' : ' live') + '">' + (s.background ? '当前数据源' : '正在播放') + '</span>' +
      (s.selectedSource
        ? '<span class="now-srcname">' + esc(s.selectedSource) + '</span>' +
          (s.selectedMeta ? '<span class="now-meta">' + esc(s.selectedMeta) + '</span>' : '')
        : '<span class="now-meta">尚未选择数据源</span>') + '</div>' +
      (s.selectedTitle ? '<div class="now-src" title="' + esc(s.selectedTitle) + '">' + esc(s.selectedTitle) + '</div>' : '') +
      (s.background
        ? sessionChip(s.session) + '<p class="hint">电视未在播放页：可以照常换源和修改查询条件，新数据源会在后台加载，回到播放器即可继续播放。</p>' +
          '<button class="primary wide ic" id="open-player">' + window.ICONS.tv + '在电视上打开播放器</button>'
        : '<div id="player-controls"></div>') +
      '</div>', s.art);
    lastState = s;
    chips.innerHTML = renderChips(s);
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

  // 选集: 播放卡上「第几集」那一行的透明下拉框 (见 epLine). 选完先失焦 —— 有焦点时 paintNow 不重画卡片
  // 选集列表: 自己画 (原生 select 在 iPhone 上菜单宽度由系统定, 长集名折成两行). 宽度按最长那一行 (不折行),
  // 上限是屏宽减两边 16px, 超过上限的那几行才折. 挂在 body 上 —— 卡片 overflow: hidden 会把它裁掉, 且轮询整卡重画
  // 也不会把开着的列表关掉. 按那一行的位置摆在它下面, 当前集滚到列表中间
  var epMenu = null;
  function closeEpMenu() {
    if (epMenu) { epMenu.remove(); epMenu = null; }
  }
  function openEpMenu(anchor) {
    var s = lastState;
    if (!s || !(s.episodes || []).length) return;
    closeEpMenu();
    var text = s.episode ? s.episode.replace(/\s{2,}/g, ' · ') : '';
    var m = document.createElement('div');
    m.className = 'ep-menu';
    m.id = 'ep-menu';
    m.setAttribute('role', 'listbox');
    m.innerHTML = s.episodes.map(function (e) {
      // 电视选集列表的标签以「✓ 」开头 = 看过; 记号单独一栏对齐, 当前集用播放卡那一行的写法
      var seen = /^✓/.test(e.label);
      var name = e.current && text ? text : e.label.replace(/^✓\s*/, '');
      return '<button type="button" role="option" class="ep-opt' + (e.current ? ' cur' : '') + '" data-ep="' + e.id + '"' +
        (e.current ? ' aria-selected="true"' : '') + '><span class="ep-mark">' + (seen ? '✓' : '') + '</span>' +
        '<span class="ep-name">' + esc(name) + '</span></button>';
    }).join('');
    document.body.appendChild(m);
    var r = anchor.getBoundingClientRect();
    m.style.left = (Math.max(16, Math.min(r.left, innerWidth - 16 - m.offsetWidth)) + scrollX) + 'px';
    m.style.top = (r.bottom + scrollY + 6) + 'px';
    m.style.maxHeight = Math.max(240, innerHeight - r.bottom - 80) + 'px';
    var cur = m.querySelector('.ep-opt.cur');
    if (cur) m.scrollTop = cur.offsetTop - (m.clientHeight - cur.offsetHeight) / 2;
    epMenu = m;
  }
  document.getElementById('player-now').addEventListener('click', function (e) {
    var p = e.target.closest('#ep-pick');
    if (!p) return;
    if (epMenu) closeEpMenu(); else openEpMenu(p);
  });
  // 选一集 / 点列表外面关掉 (捕获阶段: 先于页面上别的点击处理)
  document.addEventListener('click', function (e) {
    if (!epMenu) return;
    var o = e.target.closest('.ep-opt');
    if (o && epMenu.contains(o)) {
      closeEpMenu();
      if (o.classList.contains('cur')) return;
      post('api/player/episode', { id: o.getAttribute('data-ep') })
        .then(function (r) { toast(r.message); poll(true); })
        .catch(fail);
      return;
    }
    if (!e.target.closest('#ep-menu') && !e.target.closest('#ep-pick')) closeEpMenu();
  }, true);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeEpMenu(); });
  window.addEventListener('resize', closeEpMenu);
  document.querySelector('.tabbar').addEventListener('click', closeEpMenu);

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
    if (srcFilter) {
      groups.forEach(function (g) { h += groupHtml(g, s); });
      return h;
    }
    // 没点某个源时: 按类型分成可各自收起的几段 —— 本地缓存 / 在线源 / BT 源 (BT 一搜几百条, 在线源不该要翻过它们才找得到).
    // 默认全展开, 可手动收起; 点了某个源的胶囊 / 播放卡上的数据源胶囊时展开对应那段; 开合记在 secOpen, 轮询重画照着画
    SECTIONS.forEach(function (k) {
      var gs = groups.filter(function (g) { return (g.kind || 'web') === k[0]; });
      if (!gs.length) return;
      var n = gs.reduce(function (a, g) { return a + g.total; }, 0);
      var hasSel = gs.some(function (g) { return g.items.some(function (it) { return it.id === s.selectedId; }); });
      h += '<details class="src-sec" data-sec="' + k[0] + '"' + (secOpen[k[0]] ? ' open' : '') + '><summary>' + k[1] +
        '<small>' + gs.length + ' 个源 · ' + n + ' 条' + (hasSel ? ' · 正在播放的在这里' : '') + '</small></summary>' +
        gs.map(function (g) { return groupHtml(g, s); }).join('') + '</details>';
    });
    return h;
  }
  var SECTIONS = [['cache', '本地缓存'], ['web', '在线源'], ['bt', 'BT 源']];
  var secOpen = { cache: true, web: true, bt: true };
  // 某个源 (id) 在哪一段; 展开那一段, 下次重画照着画
  window.openSourceSection = function (id) {
    var g = lastState && lastState.groups.filter(function (x) { return x.id === id; })[0];
    if (g) secOpen[g.kind || 'web'] = true;
  };
  // 播放卡上的数据源胶囊 (另一段脚本) 点了要展开并重画列表
  window.lastPlayerState = function () { return lastState; };
  window.renderPlayerList = renderList;
  function groupHtml(g, s) {
    var h = '<h2>' + (g.kind === 'cache' ? '' : window.srcIcon(g.id, g.name)) + esc(g.name) + ' <small>' + g.total + ' 条</small></h2><div class="list">';
    // 点了播放卡的数据源胶囊后亮一下的那一条 (见那里的点击处理)
    var flashId = window.flashSel && window.flashSel.until > Date.now() ? window.flashSel.id : null;
    g.items.forEach(function (it) {
      var sel = it.id === s.selectedId;
      // 去重: 在线源的「字幕组」常就是字幕语言 (简中 · 简中)
      var meta = [it.cached ? '已缓存' : '', it.resolution, it.subtitles, it.alliance, it.size]
        .filter(function (v, i, a) { return v && a.indexOf(v) === i; }).join(' · ');
      h += '<button class="item' + (sel ? ' sel' : '') + (it.id === flashId ? ' flash' : '') + (it.excluded ? ' ex' : '') + (it.blocked ? ' blocked' : '') +
        '" data-id="' + esc(it.id) + '"' + (it.blocked ? ' data-blocked="' + esc(it.reason || '') + '"' : '') + '>' +
        '<span class="t">' + esc(it.title) + '</span><span class="m">' + esc(meta) + '</span>' +
        (it.excluded ? '<span class="why">已排除：' + esc(it.reason || '') + '</span>' : '') +
        (sel ? '<span class="badge">' + (s.background ? '当前' : '正在播放') + '</span>' : '') + '</button>';
    });
    h += '</div>';
    if (g.more > 0) h += '<p class="hint">还有 ' + g.more + ' 条未列出，' +
      (srcFilter ? '可以勾选上面的「显示全部」' : '点上面这个数据源的胶囊后可以选择显示全部') + '</p>';
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
    // 点了某个源: 它所在那段展开, 取消筛选回到分段列表时就停在展开的那段
    if (id) window.openSourceSection(id);
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
    // 分段标题: 点击在 details 自己开合之前, 记下点完之后的状态
    var sm = e.target.closest('details.src-sec > summary');
    if (sm) { secOpen[sm.parentNode.getAttribute('data-sec')] = !sm.parentNode.open; return; }
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
  // 正在就地改时间 (见 openEdit); editText = 输入框里当前的字, 卡片被整张重画时照着补回去
  var editing = false, editText = '';
  // 音量条: 拖动期间、松手后一会儿不让轮询把它拽回去 (电视那边落地、下一次轮询带回来要一点时间); 拖动中节流着发
  var volDragging = false, volHoldUntil = 0, volTimer = null, volLocal = null;
  function sendVol(v) { post('api/player/control', { action: 'volume', v: String(v / 100) }).catch(fail); }
  function paint() {
    var box = document.getElementById('player-controls');
    if (!box) return;
    if (!box.firstChild) {
      box.innerHTML =
        '<div class="progress"><input type="range" id="pb-range" min="0" max="0" step="1000" value="0" aria-label="播放进度">' +
        '<div class="time pb-time-link" id="pb-time"></div></div>' +
        '<div class="pb-ctrls"><button data-act="back" aria-label="后退 10 秒">' + window.ICONS.back10 + '</button>' +
        '<button data-act="toggle" class="pb-main" id="pb-toggle" aria-label="播放"></button>' +
        '<button data-act="forward" aria-label="前进 10 秒">' + window.ICONS.fwd10 + '</button></div>' +
        '<div class="pb-vol" id="pb-volrow" hidden><button type="button" id="pb-mute" aria-label="静音"></button>' +
        '<input type="range" id="pb-vol" min="0" max="100" step="1" value="100" aria-label="音量">' +
        '<span class="pb-vol-hi">' + window.ICONS.volUp + '</span></div>';
    }
    // 正改着时间时卡片被整张重画了: 把输入框 (连同已输入的字) 补回去
    if (editing && !document.getElementById('pb-jump')) openEdit(editText, true);
    var p = pb || { playing: false, position: 0, duration: 0 };
    // 播放中给「暂停」图标, 暂停时给「播放」; 状态没变不重画 svg
    var tg = document.getElementById('pb-toggle'), icon = p.playing ? 'pause' : 'play';
    if (tg.getAttribute('data-icon') !== icon) {
      tg.setAttribute('data-icon', icon);
      tg.setAttribute('aria-label', p.playing ? '暂停' : '播放');
      tg.innerHTML = window.ICONS[icon];
    }
    // 「正在播放」前的状态点: 播放中绿、暂停灰 (卡片每次重画后这里都会跟着再跑一次)
    var live = document.querySelector('#player-now .now-label.live');
    if (live) live.classList.toggle('paused', !p.playing);
    // 音量: 电视给了才显示 (取不到音量控制的播放器不给)
    var vr = document.getElementById('pb-volrow');
    if (vr) {
      var hasVol = p.volume != null;
      vr.hidden = !hasVol;
      vr.classList.toggle('muted', !!p.muted);
      if (hasVol) {
        // 拖动中 / 刚松手: 用手上的值 (这时卡片被整张重画, 新滑块也不会跳回默认); 其余时候跟电视
        var held = (volDragging || Date.now() < volHoldUntil) && volLocal != null;
        var vol = document.getElementById('pb-vol'), vp = held ? volLocal : Math.round(p.volume * 100);
        if (vol.value !== String(vp)) vol.value = String(vp);
        vol.style.setProperty('--pct', vp + '%');
      }
      var mb = document.getElementById('pb-mute'), mi = p.muted ? 'volOff' : 'volLow';
      if (mb.getAttribute('data-icon') !== mi) {
        mb.setAttribute('data-icon', mi);
        mb.setAttribute('aria-label', p.muted ? '取消静音' : '静音');
        mb.innerHTML = window.ICONS[mi];
      }
    }
    var range = document.getElementById('pb-range');
    range.disabled = !p.duration;
    if (!dragging) {
      range.max = String(p.duration || 0);
      range.value = String(p.position || 0);
      // 进度条填充比例 (样式里的 --pct)
      range.style.setProperty('--pct', (p.duration ? Math.min(100, (p.position || 0) * 100 / p.duration) : 0) + '%');
      // 同 iOS 播放卡: 左边已播, 右边剩余 (左边正在就地改时间就只更新右边)
      var remain = p.duration ? '-' + fmt(Math.max(0, p.duration - (p.position || 0))) : '--:--';
      var tm = document.getElementById('pb-time');
      if (editing && tm.children[1]) tm.children[1].textContent = remain;
      else if (!editing) tm.innerHTML = '<span>' + fmt(p.position) + '</span><span>' + remain + '</span>';
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
    if (e.target.closest && e.target.closest('#pb-jump')) { editText = e.target.value; return; }
    if (e.target.id === 'pb-vol') {
      volDragging = true;
      var vt = e.target;
      volLocal = +vt.value;
      vt.style.setProperty('--pct', vt.value + '%');
      if (!volTimer) volTimer = setTimeout(function () { volTimer = null; sendVol(+vt.value); }, 150);
      return;
    }
    if (e.target.id !== 'pb-range') return;
    dragging = true;
    var d = pb && pb.duration;
    e.target.style.setProperty('--pct', (d ? Math.min(100, +e.target.value * 100 / d) : 0) + '%');
    document.getElementById('pb-time').textContent = '跳到 ' + fmt(+e.target.value) + ' / ' + (d ? fmt(d) : '--:--');
  });
  // 音量条左边的喇叭: 静音 / 取消静音
  nowBox.addEventListener('click', function (e) {
    if (!e.target.closest('#pb-mute')) return;
    post('api/player/control', { action: 'mute' }).then(function () { poll(true); }).catch(fail);
  });
  nowBox.addEventListener('change', function (e) {
    if (e.target.id === 'pb-vol') {
      volDragging = false;
      volHoldUntil = Date.now() + 2000;
      clearTimeout(volTimer);
      volTimer = null;
      volLocal = +e.target.value;
      sendVol(volLocal);
      return;
    }
    if (e.target.id !== 'pb-range') return;
    dragging = false;
    seek(+e.target.value);
  });
  // 点时间文字 = 左边已播时间那里就地变成输入框 (填好当前时间、全选, 直接打字就替换), 回车 / 「跳转」就跳; Esc / 点别处取消
  function openEdit(text, focus) {
    var t = document.getElementById('pb-time');
    if (!t) return;
    editing = true;
    editText = text;
    var right = t.children[1] ? t.children[1].outerHTML : '<span></span>';
    t.innerHTML = '<form class="pb-jump" id="pb-jump"><input type="text" name="t" inputmode="decimal" autocomplete="off" ' +
      'placeholder="如 21:30" aria-label="跳到的时间"><button type="submit">跳转</button></form>' + right;
    var inp = t.querySelector('input');
    inp.value = text;
    if (focus) { inp.focus(); inp.select(); }
  }
  function closeEdit() {
    if (!editing) return;
    editing = false;
    paint();
  }
  nowBox.addEventListener('click', function (e) {
    if (e.target.closest('#pb-jump') || !e.target.closest('#pb-time') || editing) return;
    openEdit(fmt(pb ? pb.position : 0), true);
  });
  // 点「跳转」时别让输入框先失焦 (失焦 = 取消; iOS 上按钮不接焦点, 失焦会抢在点击前面)
  nowBox.addEventListener('pointerdown', function (e) { if (e.target.closest('#pb-jump button')) e.preventDefault(); });
  nowBox.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && e.target.closest && e.target.closest('#pb-jump')) closeEdit();
  });
  nowBox.addEventListener('focusout', function (e) {
    var f = e.target.closest && e.target.closest('#pb-jump');
    if (!f || (e.relatedTarget && f.contains(e.relatedTarget))) return;
    // 卡片重画时会补一个新的输入框并聚焦过去: 稍等再看焦点是不是还在输入框里
    setTimeout(function () {
      var ff = document.getElementById('pb-jump');
      if (editing && !(ff && ff.contains(document.activeElement))) closeEdit();
    }, 150);
  });
  nowBox.addEventListener('submit', function (e) {
    if (e.target.id !== 'pb-jump') return;
    e.preventDefault();
    var ms = parseTime(e.target.elements.t.value);
    if (ms == null) { toast('时间格式不对，例如 21:30 或 1:05:10'); return; }
    if (pb && pb.duration && ms > pb.duration) { toast('超过片长了（' + fmt(pb.duration) + '）'); return; }
    editing = false;
    e.target.elements.t.blur();
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
    // 播放 / 暂停按手机上看到的状态发明确的 play / pause, 不发 toggle: 电视休眠时按下, Ani 被叫回前台那一刻会自己续播
    // (见 TvRemoteControl.awaitFront), 再 toggle 就又暂停了
    if (act === 'toggle' && pb) { act = pb.playing ? 'pause' : 'play'; pb.playing = !pb.playing; paint(); }
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

  var getJson = window.getJson;
  function load() {
    getJson('api/sources').then(function (d) { data = d; renderAdd(); renderList(); }).catch(failRead);
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
      }).catch(failRead);
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
      // 上移 / 下移: 每行都有的排序箭头, 只放图标; 其余动作 图标 + 文字
      var I = window.ICONS;
      var b = '<button data-act="up" class="icb" aria-label="上移" title="上移"' + (i === 0 ? ' disabled' : '') + '>' + I.up + '</button>' +
        '<button data-act="down" class="icb" aria-label="下移" title="下移"' + (i === list.length - 1 ? ' disabled' : '') + '>' + I.down + '</button>';
      if (s.editor !== 'none') b += '<button data-act="edit" class="ic">' + I.edit + '编辑</button>';
      if (fromSub) b += '<button data-act="copy" class="ic">' + I.copy + '复制为本地源</button>';
      if (s.exportable) b += '<button data-act="export" class="ic">' + I.share + '导出</button>';
      if (!fromSub) b += '<button data-act="delete" class="src-danger ic">' + I.trash + '删除</button>';
      return '<div class="src-item' + (s.enabled ? '' : ' off') + '" data-i="' + i + '">' +
        '<div class="src-top"><label class="src-sw"><input type="checkbox" data-act="enable"' + (s.enabled ? ' checked' : '') + '></label>' +
        window.srcIcon(s.id, s.name) + '<div class="src-name">' + esc(s.name) + '<small>' + esc(tags) + '</small></div></div>' +
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
    }).catch(failRead);
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
      '<button type="button" class="sub-refresh ic" data-sub="refresh"' + (d.updating ? ' disabled' : '') + '>' +
      window.ICONS.refresh + (d.updating ? '更新中…' : '立即更新') + '</button></div>';
    if (!items.length) h += '<p class="hint">还没有订阅，把订阅地址粘贴到下面添加</p>';
    items.forEach(function (s) {
      h += '<div class="sub-item" data-lp="' + esc(s.id) + '"><span class="sel-mark" aria-hidden="true"></span><div class="sub-url">' + esc(s.url) + '</div>' +
        '<div class="sub-status' + (s.failed ? ' bad' : '') + '">' + esc(s.status) + '</div>' +
        '<div class="sub-meta"><span>' + esc(s.period) + '</span>' +
        '<button type="button" class="sub-del icb" data-sub="delete" data-id="' + esc(s.id) + '" aria-label="删除订阅" title="删除订阅">' +
        window.ICONS.trash + '</button></div></div>';
    });
    h += '<form class="sub-add"><input type="text" name="url" inputmode="url" autocomplete="off" spellcheck="false" ' +
      'placeholder="粘贴订阅地址 https://…"><button type="submit" class="primary">添加</button></form></div>';
    var old = box.querySelector('.sub-add input');
    var typed = old ? old.value : '', focused = old && document.activeElement === old;
    box.innerHTML = h;
    var input = box.querySelector('.sub-add input');
    input.value = typed;
    if (focused) input.focus();
    window.selSync(box);
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
  // 长按一条订阅: 进多选 (那一条先勾上), 底部操作栏一次删几个
  box.addEventListener('longpress', function (e) {
    window.selStart({
      box: box,
      ask: function (n) { return '删除选中的 ' + n + ' 个订阅？它们带来的数据源会一起删除。'; },
      del: function (ids) {
        return post('api/sources/subs/delete', { ids: ids.join(',') }).then(function (r) {
          toast(r.message);
          if (r.ok) { load(); if (window.loadSources) window.loadSources(); }
          return r.ok;
        });
      }
    }, e.target.getAttribute('data-lp'));
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
  var frontBox = document.getElementById('set-front');
  var keepBox = document.getElementById('set-keep');
  // 退出 Ani 后保留 Web 控制台 (见 TvRemoteControl.keepAliveOnExit): 默认关; 开着时电视上按返回退出 Ani, 手机还能连
  function renderKeep(k) {
    if (!k) { keepBox.innerHTML = ''; return; }
    keepBox.innerHTML = '<div class="card set-card"><div class="set-title">后台保持连接</div>' +
      '<label class="toggle"><input type="checkbox" data-keep' + (k.enabled ? ' checked' : '') + '>电视休眠或离开 Ani 后仍保持连接</label>' +
      '<p class="hint">开启后，Ani 会继续在后台运行，并占用少量内存。配合「从手机打开 Ani」，电视休眠或退出 Ani 后，也可以从手机重新打开。' +
      '关闭后，电视休眠或退出 Ani 就会断开，需要先在电视上打开 Ani 才能连接。</p></div>';
  }
  keepBox.addEventListener('change', function (e) {
    var i = e.target;
    if (!i.hasAttribute('data-keep')) return;
    i.disabled = true;
    post('api/settings/keep', { on: i.checked ? '1' : '' }).then(function (r) {
      toast(r.message);
      renderKeep(r);
    }).catch(function () { i.disabled = false; i.checked = !i.checked; fail(); });
  });
  var MODES = [['DISABLED', '不使用'], ['SYSTEM', '跟随系统'], ['CUSTOM', '自定义']];
  // 切到电视前台 (见 TvRemoteControl.frontState): 默认关; 开了还要在电视上授权一次「显示在其他应用的上层」.
  // 授权后回到本标签会重新拉一次 (load), 状态跟着更新
  function renderFront(f) {
    if (!f) { frontBox.innerHTML = ''; return; }
    // 授过权的 (以前开过又关了) 不再说「首次开启时需要授权」
    var st = !f.needsPermission ? '无需额外授权'
      : f.granted ? '已授权'
      : !f.enabled ? '首次开启时，需要在电视上允许 Animeko「显示在其他应用的上层」。'
      : '尚未授权。Ani 显示在电视上时会直接打开授权页；否则请在 30 分钟内回到 Ani。' +
        '也可在电视设置中为 Animeko 开启「显示在其他应用的上层」。';
    frontBox.innerHTML = '<div class="card set-card"><div class="set-title">从手机打开 Ani</div>' +
      '<label class="toggle"><input type="checkbox" data-front' + (f.enabled ? ' checked' : '') + '>允许从手机打开电视上的 Ani</label>' +
      '<p class="hint">开启后，在手机上搜索或点播时，电视会自动打开 Ani；同时开启「后台保持连接」时，电视休眠也会先唤醒。' +
      '关闭后，Ani 仍在后台时，搜索和点播仍会发送到电视，但需要手动打开 Ani 才能看到。</p>' +
      '<p class="hint">' + st + '</p></div>';
  }
  frontBox.addEventListener('change', function (e) {
    var i = e.target;
    if (!i.hasAttribute('data-front')) return;
    i.disabled = true;
    post('api/settings/front', { on: i.checked ? '1' : '' }).then(function (r) {
      toast(r.message);
      renderFront(r);
    }).catch(function () { i.disabled = false; i.checked = !i.checked; fail(); });
  });
  function load() {
    window.getJson('api/settings').then(render).catch(failRead);
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
    renderFront(d.front);
    renderKeep(d.keep);
    renderFilters(d.dmfilter);
  }
  // 弹幕屏蔽词: 只重画这一块 (别把上面正在填的代理表单冲掉)
  function reloadFilters() {
    window.getJson('api/settings').then(function (d) { renderFilters(d.dmfilter); }).catch(failRead);
  }
  function renderFilters(df) {
    df = df || { enabled: true, items: [] };
    dfBox.innerHTML = '<div class="card set-card"><div class="set-title">弹幕屏蔽词</div>' +
      '<label class="toggle"><input type="checkbox" data-df="switch"' + (df.enabled ? ' checked' : '') + '>启用屏蔽（关掉后下面的规则都不生效）</label>' +
      (df.items.length ? df.items.map(function (x) {
        return '<div class="df-item"><label class="src-sw"><input type="checkbox" data-df="toggle" data-id="' + esc(x.id) + '"' +
          (x.on ? ' checked' : '') + '></label><code>' + esc(x.regex) + '</code>' +
          '<button type="button" class="sub-del icb" data-df="delete" data-id="' + esc(x.id) + '" aria-label="删除" title="删除">' +
          window.ICONS.trash + '</button></div>';
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
  var subject = null, subjectTitle = '', view = 'eps', ep = null, timer = null, lastData = null;
  var picked = {}, fRes = '', fSub = '', fAll = '', fEx = false;
  // 资源页的数据源胶囊 (null = 全部) 与「显示全部」, 同播放器标签; lastCands: 点胶囊时就地重画用
  var ccSrc = null, ccFull = false, lastCands = null;
  // 各块只在内容变了才重画 (免得关掉正打开的下拉框); 出错提示也走它, 下一次正常就能画回来
  var setHtml = window.setHtml;
  // 「全选」/「全部用合集缓存」的范围 (记在这台手机上, 设置 → 外观里改): 默认只选正片, 特别篇 (SP / OVA 等, x.sp) 要自己勾
  function pickAllSp() { try { return localStorage.getItem('ani-cache-pick-scope') === 'all'; } catch (e) { return false; } }
  function inScope(x) { return !x.sp || pickAllSp(); }
  window.refreshCachePick = function () { if (lastData && view === 'eps' && !sheet.hidden) renderEpisodes(lastData); };
  function stop() { clearTimeout(timer); timer = null; }
  function openSheet(id, title) {
    subject = id;
    subjectTitle = title || '';
    picked = {};
    window.sheets.open(sheet);
    showEpisodes();
  }
  // 关上时 sheetclose 事件让被盖住的列表 (「缓存」标签、挑番面板) 刷新一次
  function closeSheet() {
    stop();
    if (view === 'cands') post('api/cache/close', {}).catch(function () {});
    window.sheets.close(sheet);
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
    setHtml(sb, '<p class="hint">正在读取剧集…</p>');
    pollEpisodes();
  }
  function pollEpisodes() {
    stop();
    if (sheet.hidden || view !== 'eps') return;
    window.getJson('api/cache?subject=' + subject).then(function (d) {
      if (view !== 'eps') return;
      renderEpisodes(d);
      timer = setTimeout(pollEpisodes, 2000);
    }).catch(function () { timer = setTimeout(pollEpisodes, 4000); });
  }
  function pickedIds() { return Object.keys(picked).filter(function (k) { return picked[k]; }); }
  function renderEpisodes(d) {
    lastData = d;
    if (!d.ok) { setHtml(sb, '<p class="hint">' + esc(d.message) + '</p>'); return; }
    if (d.title) { subjectTitle = d.title; titleEl.textContent = '缓存 · ' + d.title; }
    var b = d.batch, running = !!(b && b.running), h = '';
    if (running) {
      h += '<div class="now-status busy"><b>自动缓存中</b><span>' + b.done + ' / ' + b.total + (b.current ? '：' + esc(b.current) : '') + '</span></div>';
    } else if (b && b.failures.length) {
      h += '<div class="now-status error"><b>' + b.failures.length + ' 集没能自动缓存</b><span>原因写在对应那一集下面，可以点「选资源」自己挑</span></div>';
    }
    // 已缓存的合集还覆盖着的集 (同电视缓存页: 点一集直接用合集, 不用再挑); 一次全部补上走自动批量 (它先找合集)
    var packIds = d.episodes.filter(function (x) { return x.status === 'none' && x.pack && inScope(x); }).map(function (x) { return x.id; });
    if (packIds.length && !running) {
      h += '<div class="now-status ready"><b>合集里还有 ' + packIds.length + ' 集没缓存</b>' + (d.packTitle ? '<span>' + esc(d.packTitle) + '</span>' : '') + '</div>' +
        '<button type="button" class="ghost wide cache-packall" data-packall="' + packIds.join(',') + '">全部用合集缓存（' + packIds.length + ' 集）</button>';
    }
    h += '<p class="hint">' + (d.free ? '电视剩余空间 ' + esc(d.free) + '。' : '') +
      '勾选几集后点最下面的按钮自动缓存：有已缓存的合集先用合集，' + esc(d.autoHint || '否则按你的数据源偏好自动挑') +
      '；也可以对某一集点「选资源」自己挑。</p>';
    // 有没缓存的特别篇时才说全选的范围 (记在这台手机上, 见 inScope), 顺带说去哪改
    if (d.episodes.some(function (x) { return x.status === 'none' && x.sp; })) {
      h += '<p class="hint">' + (pickAllSp() ? '「全选」会同时选择正片和特别篇。可在「设置 → 本机偏好」中更改。'
        : '「全选」默认只选择正片。特别篇需要手动选择，可在「设置 → 本机偏好」中更改。') + '</p>';
    }
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
    // 全选只管还没缓存的集 (其余的勾选框本来就是灰的), 默认只管正片 (见 inScope, 有没选上的特别篇时写明「全选正片」);
    // 都勾上了就变「全不选」(连手动勾的特别篇一起清)
    var freeIds = d.episodes.filter(function (x) { return x.status === 'none' && inScope(x); }).map(function (x) { return String(x.id); });
    var spLeft = d.episodes.some(function (x) { return x.status === 'none' && !inScope(x); });
    var allOn = freeIds.length > 0 && freeIds.every(function (id) { return picked[id]; });
    h += '<div class="cache-bar"><button type="button" class="ghost cache-all" data-pickall="' + (allOn ? '0' : '1') + '"' +
      (freeIds.length && !running ? '' : ' disabled') + '>' + (allOn ? '取消全选' : spLeft ? '全选正片' : '全选') + '</button>' +
      '<button type="button" class="primary wide" id="cache-auto"' + (n && !running ? '' : ' disabled') + '>' +
      (running ? '自动缓存进行中…' : n ? '自动挑资源缓存选中的 ' + n + ' 集' : '先勾选要缓存的剧集') + '</button></div>';
    setHtml(sb, h);
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
    // 三块都是新建的节点, 各自从头比
    setHtml(sb, '<div id="cc-chips"></div><div id="cc-filters"></div><div id="cc-list"><p class="hint">正在查找资源…</p></div>');
    pollCandidates();
  }
  function pollCandidates() {
    stop();
    if (sheet.hidden || view !== 'cands') return;
    window.getJson('api/cache/candidates?subject=' + subject + '&episode=' + ep + '&res=' + encodeURIComponent(fRes) +
      '&sub=' + encodeURIComponent(fSub) + '&all=' + encodeURIComponent(fAll) + '&ex=' + (fEx ? '1' : '') +
      // 第一次打开一集时服务端最长要 15 + 15 秒 (读条目 + 建请求, 见 RemoteCache.ensureBrowse), 别比它先放弃
      '&full=' + (ccFull && ccSrc ? encodeURIComponent(ccSrc) : ''), 35000)
      .then(function (d) {
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
    if (!d.ok) { setHtml(list, '<p class="hint">' + esc(d.message) + '</p>'); return; }
    lastCands = d;
    // 选中的源已经不在了 (比如被停用): 回到「全部」
    if (ccSrc && !d.sources.some(function (x) { return x.id === ccSrc; }) && !d.groups.some(function (g) { return g.id === ccSrc; })) {
      ccSrc = null;
      ccFull = false;
    }
    setHtml(cbox, ccChips(d));
    var f = d.filters || {};
    var one = ccSrc ? d.groups.filter(function (g) { return g.id === ccSrc; })[0] : null;
    var fh = '<div class="filters">' + dropdown('res', '分辨率', f.resolution, fRes) + dropdown('sub', '字幕', f.subtitle, fSub) +
      dropdown('all', '字幕组', f.alliance, fAll) + '</div>' +
      '<div class="toggles"><label class="toggle"><input type="checkbox" data-cf="ex"' + (fEx ? ' checked' : '') + '>显示被排除的资源' +
      (d.excludedCount ? '（' + d.excludedCount + ' 条）' : '') + '</label>' +
      // 「显示全部 N 条」: 只在点了某个胶囊、而且这个源确实没列全时出现 (勾着时一直显示, 好取消)
      (one && (ccFull || one.more > 0) ? '<label class="toggle"><input type="checkbox" data-cf="full"' + (ccFull ? ' checked' : '') +
        '>显示全部 ' + one.total + ' 条</label>' : '') + '</div>';
    setHtml(fbox, fh);
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
      h += '<h2>' + (g.kind === 'cache' ? '' : window.srcIcon(g.id, g.name)) + esc(g.name) + ' <small>' + g.total + ' 条</small></h2><div class="list">';
      g.items.forEach(function (it) {
        var meta = [it.resolution, it.subtitles, it.alliance, it.size].filter(function (v, i, a) { return v && a.indexOf(v) === i; }).join(' · ');
        h += '<button type="button" class="item' + (it.excluded ? ' ex' : '') + (it.blocked ? ' blocked' : '') + '" data-mid="' + esc(it.id) +
          '" data-title="' + esc(it.title) + '"' + (it.blocked ? ' data-blocked="' + esc(it.reason || '') + '"' : '') + '>' +
          '<span class="t">' + esc(it.title) + '</span><span class="m">' + esc(meta) + '</span>' +
          (it.excluded ? '<span class="why">已排除：' + esc(it.reason || '') + '</span>' : '') + '</button>';
      });
      h += '</div>';
      if (g.more > 0) h += '<p class="hint">还有 ' + g.more + ' 条没列出，' +
        (ccSrc ? '可以勾选上面的「显示全部」' : '点上面这个数据源的胶囊后可以选择显示全部') + '</p>';
    });
    setHtml(list, h);
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
    if (b.hasAttribute('data-pickall')) {
      if (!lastData || !lastData.ok) return;
      var on = b.getAttribute('data-pickall') === '1';
      lastData.episodes.forEach(function (x) { if (x.status === 'none' && (!on || inScope(x))) picked[x.id] = on; });
      renderEpisodes(lastData);
      return;
    }
    if (b.id === 'cache-auto') {
      var ids = pickedIds();
      if (!ids.length) return;
      b.disabled = true;
      // 失败时 (r.ok=false / 网络错) 把按钮放回来: 列表内容没变不会重画, 不放的话按钮一直是灰的
      post('api/cache/auto', { subject: String(subject), episodes: ids.join(',') }).then(function (r) {
        toast(r.message);
        if (r.ok) picked = {}; else b.disabled = false;
        pollEpisodes();
      }).catch(function () { b.disabled = false; fail(); });
    } else if (b.hasAttribute('data-pack')) {
      // 用已缓存的合集缓存这一集: 不进资源列表
      b.disabled = true;
      post('api/cache/pack', { subject: String(subject), episode: b.getAttribute('data-pack') }).then(function (r) {
        toast(r.message);
        if (!r.ok) b.disabled = false;
        pollEpisodes();
      }).catch(function () { b.disabled = false; fail(); });
    } else if (b.hasAttribute('data-packall')) {
      b.disabled = true;
      post('api/cache/auto', { subject: String(subject), episodes: b.getAttribute('data-packall') }).then(function (r) {
        toast(r.message);
        if (!r.ok) b.disabled = false;
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
  var timer = null, busy = false, lastSum = '', lastList = '';
  // 任何全屏面板盖着 (缓存面板、挑番、使用说明) 都不刷新; 面板关上时 (sheetclose) 刷新一次
  function active() { return !tab.hidden && !window.sheets.any() && !document.hidden; }
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
  document.addEventListener('sheetclose', function () { load(); });
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
        h = '<div class="empty"><p>电视上还没有缓存</p><p class="hint">在「播放器」卡片右上角点「缓存」开始缓存</p></div>';
      }
      d.groups.forEach(function (g) {
        // 番名那一行右边: 整部删除 (先确认; 里面有正在播的那一集时确认框多说一句)
        var playingItem = g.items.filter(function (x) { return x.playing; })[0];
        // 点番名 = 电视打开详情页; 点一集 = 电视上播这一集 (见下面的点击处理)
        // 番名 + 统计那一块带竖版封面底图 (同列表项, .cv); data-art 用来在列表重画时认出同一组图, 把原来的 <img> 挪回来 (不闪)
        // 整张卡用同一张封面的重度模糊打底 (.cl-amb-bg, 下面一集一集的行也带这部番的颜色, 不再一片白); 清楚的竖版封面仍只在番名那块.
        // 不把清楚的封面铺满整张卡: 集数一多卡片很高, 竖图要放大好几倍只剩一条局部, 下载行的字和按钮也压在图上看不清
        var art = g.cover ? esc(g.cover.join(' ')) : '';
        h += '<div class="card cl-group' + (g.cover ? ' amb" data-art="' + art : '') + '">' +
          (g.cover ? '<img class="cv-bg cl-amb-bg" src="' + esc(g.cover[0]) + '" data-alt="' + esc(g.cover.slice(1).join(' ')) +
            '" alt="" loading="lazy" referrerpolicy="no-referrer">' : '') +
          (function () {
            // 番名那一块: 右滑露出「缓存」(打开这部番的缓存面板, 同搜索结果 / 播放记录的右滑); 左滑露出「全部删除」(同下面每一集的左滑删除,
            // 先确认; 里面有正在播的那一集时确认框多说一句)
            var top = '<div class="cl-top sw-row' + (g.cover ? ' cv" data-art="' + art : '') + '">' +
              (g.cover ? window.coverLayers(g.cover) : '') +
              '<div class="cl-head"><div class="cl-title"' + (g.id ? ' data-open="' + g.id + '"' : '') + '>' + esc(g.title) + '</div>' +
              '</div><div class="cl-meta">' + esc(g.meta) + '</div>' +
              // 右边封面 (没封面时同一个位置的 ▶) = 播放, 播哪一集同搜索结果 / 详情页的播放按钮 (按观看进度, 见 RemoteSearchResults.play)
              (g.id ? '<button type="button" class="res-play" data-sid="' + g.id + '" aria-label="播放"><span class="play-glyph">' +
                window.ICONS.play + '</span></button>' : '') + '</div>';
            if (!g.id) return '<div class="sw flat cl-top-sw">' + top + '</div>';
            return window.swRow(
              '<button type="button" class="sw-btn cache" data-cache="' + g.id + '" data-title="' + esc(g.title) + '">' + window.ICONS.download + '缓存</button>',
              '<button type="button" class="sw-btn del" data-cdelall="' + g.id + '" data-title="' + esc(g.title) +
              '" data-count="' + g.items.length + '"' + (playingItem ? ' data-playing="' + esc(playingItem.label) + '"' : '') + '>' +
              window.ICONS.trash + '全部删除</button>', top, 'flat cl-top-sw');
          })();
        g.items.forEach(function (x) { h += item(g, x); });
        h += '</div>';
      });
    }
    if (s !== lastSum) { sumBox.innerHTML = s; lastSum = s; }
    // 有一集正滑开着 / 正在拖: 这一轮先不重画 (lastList 不更新, 收回后下一轮再画)
    if (h !== lastList && !window.swBusy(listBox)) {
      // 下载中每 2 秒就有字变 (速度 / 进度), 整块重画: 番名那块的封面 <img> 按 data-art 认出来原样挪回去, 不重新解码、不闪
      // 整张卡的模糊底 (.cl-group.amb) 同理
      var SEL = '.cl-top.cv, .cl-group.amb', keep = {};
      [].forEach.call(listBox.querySelectorAll(SEL), function (t) {
        keep[t.className + '|' + t.getAttribute('data-art')] = [].slice.call(t.querySelectorAll(':scope > img'));
      });
      listBox.innerHTML = h;
      lastList = h;
      [].forEach.call(listBox.querySelectorAll(SEL), function (t) {
        var old = keep[t.className + '|' + t.getAttribute('data-art')], fresh = t.querySelectorAll(':scope > img');
        if (!old) return;
        for (var i = 0; i < fresh.length && i < old.length; i++) t.replaceChild(old[i], fresh[i]);
      });
    }
    // 长按多选中: 重画后把勾补回去 (每 2 秒刷新一次, 已经不在的集顺带丢掉)
    window.selSync(listBox);
    // 一次性滑开提示挑第一集那行 (露出「删除」), 不挑番名那块 (露出的是「全部删除」, 吓人)
    window.swPeek(listBox, 'cache', '.sw > .cl-ep');
  }
  function item(g, x) {
    var color = x.st === 'done' ? 'ok' : x.st === 'failed' ? 'bad' : x.st === 'paused' ? 'pause' : '';
    var bits = ['<b class="' + color + '">' + esc(x.text) + '</b>'];
    if (x.size) bits.push(esc(x.size));
    if (x.speed) bits.push('↓ ' + esc(x.speed));
    if (x.source) bits.push(esc(x.source));
    if (x.watched) bits.push(esc(x.watched));
    // 每一集右边的暂停 / 继续: 行行都有的通用动作, 只放图标. 删除改成左滑露出 (点了 / 滑到底仍先确认: 缓存删了要重新下)
    var I = window.ICONS;
    var act = x.st === 'run' ? '<button type="button" class="icb" data-cact="pause" data-cid="' + esc(x.cid) + '" aria-label="暂停下载" title="暂停下载">' + I.pause + '</button>'
      : x.st === 'paused' ? '<button type="button" class="icb" data-cact="resume" data-cid="' + esc(x.cid) + '" aria-label="继续下载" title="继续下载">' + I.play + '</button>' : '';
    var del = '<button type="button" class="sw-btn del" data-cact="delete" data-cid="' + esc(x.cid) + '" data-label="' + esc(g.title + ' ' + x.label) + '"' +
      (x.packShare ? ' data-share="' + x.packShare + '"' : '') + (x.playing ? ' data-playing="1"' : '') + '>' + I.trash + '删除</button>';
    return window.swRow('', del, '<div class="cl-ep sw-row" data-play="' + esc(x.cid) + '" data-lp="' + esc(x.cid) + '">' +
      '<span class="sel-mark" aria-hidden="true"></span><div class="n"><span class="cl-go">' + I.play + '</span>' + esc(x.label) +
      (x.playing ? '<span class="cl-tag play">正在播放</span>' : '') + (x.pack ? '<span class="cl-tag">合集</span>' : '') +
      '<div class="cl-st">' + bits.join(' · ') + '</div>' +
      (x.progress != null ? '<div class="cl-bar"><div style="width:' + x.progress + '%"></div></div>' : '') + '</div>' + act + '</div>', 'flat');
  }
  listBox.addEventListener('click', function (e) {
    // 多选中: 点一集只管勾选 (多选那边在捕获阶段接走), 番名那块等其余地方也不开详情 / 不删
    if (listBox.classList.contains('selecting')) return;
    var all = e.target.closest('[data-cdelall]');
    if (all) {
      if (all.disabled) return;
      var m = '删除「' + all.getAttribute('data-title') + '」的全部 ' + all.getAttribute('data-count') + ' 集缓存？';
      var pl = all.getAttribute('data-playing');
      if (pl) m += '\n\n其中「' + pl + '」正在播放，删除后需要重新选择数据源。';
      // 不删了 / 删失败: 滑到底时番名那块已经滑出去, 放回来
      if (!confirm(m)) { window.swClose(all.closest('.sw')); return; }
      all.disabled = true;
      post('api/caches/delete-subject', { subject: all.getAttribute('data-cdelall') }).then(function (r) {
        toast(r.message);
        load();
      }).catch(function () { all.disabled = false; window.swClose(all.closest('.sw')); fail(); });
      return;
    }
    var b = e.target.closest('[data-cact]');
    if (!b) {
      // 番名那一块右边的封面 / ▶: 电视上接着看 (同搜索结果), 进了播放页就切到「播放器」
      var pb = e.target.closest('.cl-top .res-play');
      if (pb) {
        var top = pb.closest('.cl-top');
        if (top.classList.contains('busy')) return;
        top.classList.add('busy', 'hit');
        post('api/search/play', { id: pb.getAttribute('data-sid') }).then(function (r) {
          top.classList.remove('busy', 'hit');
          toast(r.message);
          if (r.player) setTimeout(function () { if (window.showTab) window.showTab('player'); }, 1200);
        }).catch(function () { top.classList.remove('busy', 'hit'); fail(); });
        return;
      }
      var t = e.target.closest('[data-open]');
      if (t) {
        post('api/caches/open', { subject: t.getAttribute('data-open') }).then(function (r) { toast(r.message); }).catch(fail);
        return;
      }
      // 点一集 (按钮以外的地方): 电视上播这一集, 进了播放页就切到「播放器」(同播放记录)
      var row = e.target.closest('.cl-ep[data-play]');
      if (row && !row.classList.contains('busy')) {
        row.classList.add('busy');
        post('api/caches/play', { id: row.getAttribute('data-play') }).then(function (r) {
          row.classList.remove('busy');
          toast(r.message);
          if (r.player) setTimeout(function () { if (window.showTab) window.showTab('player'); }, 1200);
        }).catch(function () { row.classList.remove('busy'); fail(); });
      }
      return;
    }
    if (b.disabled) return;
    var act = b.getAttribute('data-cact');
    if (act === 'delete') {
      var msg = '删除「' + b.getAttribute('data-label') + '」的缓存？';
      // 同电视缓存页的删除确认: 正在播的那条多说一句
      if (b.getAttribute('data-playing')) msg += '\n\n这一集正在播放，删除后需要重新选择数据源。';
      // 合集的文件要等同一个种子的集都删了才一起回收 (见 TorrentMediaCacheEngine)
      var share = b.getAttribute('data-share');
      if (share) msg += '\n\n这一集来自合集，同一个种子还有 ' + share + ' 集缓存着：删掉它不会马上腾出空间，等这些集也都删了才一起回收。';
      // 不删了: 滑到底时行已经滑出去, 放回来
      if (!confirm(msg)) { window.swClose(b.closest('.sw')); return; }
    }
    b.disabled = true;
    post('api/caches/' + act, { id: b.getAttribute('data-cid') }).then(function (r) {
      toast(r.message);
      load();
    }).catch(function () { b.disabled = false; window.swClose(b.closest('.sw')); fail(); });
  });
  // 长按一集: 进多选 (那一集先勾上), 底部操作栏一次删几集 (同播放记录 / 订阅); 可以跨番勾
  listBox.addEventListener('longpress', function (e) {
    window.selStart({
      box: listBox,
      ask: function (n) { return '删除选中的 ' + n + ' 集缓存？删除后需要重新缓存。'; },
      del: function (ids) {
        return post('api/caches/delete', { ids: ids.join(',') }).then(function (r) {
          toast(r.message);
          load();
          return !!r.ok;
        });
      }
    }, e.target.getAttribute('data-lp'));
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
    window.getJson('api/player/review').then(render).catch(function () {
      // 不能停在「正在读取…」: 给出失败, 并允许收起再展开时重读
      loaded = false;
      if (quiet) failRead();
      else body.innerHTML = '<p class="hint">读取失败，收起再展开试试</p>';
    });
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
  // 点头像 / 名字展开的账号菜单 (修改昵称 / 绑定邮箱 / 退出登录); nick = 正在改昵称
  var menu = false, nick = false, lastData = null;
  // 邮箱登录 / 注册 (没登录时) 或绑定 / 更换邮箱 (已登录时, 在账号菜单里): null = 收着; step 'email' 填邮箱 → 'code' 填验证码
  var em = null;
  var MAIL = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4-8 5-8-5V6l8 5 8-5v2z"/></svg>';
  function emailFlow(d) {
    var bind = !!d.loggedIn;
    if (em.step === 'email') {
      return '<form class="acct-nick" id="acct-email"><input type="email" name="e" inputmode="email" autocomplete="email" placeholder="' +
        (bind ? '要绑定的邮箱' : '邮箱') + '" value="' + esc(em.email || '') + '"><button type="submit">发送验证码</button></form>' +
        '<p class="hint">' + (bind ? (d.email ? '现在绑定的是 ' + esc(d.email) + '，换成新邮箱后要用新邮箱登录。' : '绑定后也能用这个邮箱登录。')
          : '登录 Animeko 账号，没注册过的邮箱会直接注册。要同步 Bangumi 的收藏、评分和评论，登录后再连接 Bangumi。') + '</p>' +
        '<div class="row"><button type="button" class="ghost" data-acct="email-close">取消</button></div>';
    }
    return '<p class="hint">验证码已发到 <b>' + esc(em.email) + '</b>' + (em.existing === false && !bind ? '（还没注册过，验证后会注册新账号）' : '') + '。</p>' +
      '<form class="acct-nick" id="acct-otp"><input type="text" name="c" inputmode="numeric" autocomplete="one-time-code" maxlength="10" placeholder="验证码">' +
      '<button type="submit">' + (bind ? '绑定' : '登录') + '</button></form>' +
      '<div class="row"><button type="button" class="ghost" data-acct="email-resend">重新发送</button>' +
      '<button type="button" class="ghost" data-acct="email-back">换个邮箱</button></div>';
  }
  function sendOtp(email, btn) {
    btn.disabled = true;
    post('api/account/email/send', { email: email }).then(function (r) {
      btn.disabled = false;
      toast(r.message);
      if (!r.ok || !em) return;
      em.step = 'code';
      em.email = email;
      em.existing = r.existing;
      rerender();
      var f = document.getElementById('acct-otp');
      if (f) f.elements.c.focus();
    }).catch(function () { btn.disabled = false; fail(); });
  }
  function rerender() { if (lastData) render(lastData); }
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
    lastData = d;
    if (!d.loggedIn) { menu = false; nick = false; }
    // 登录状态变了 (邮箱登录成功 / 在电视上退出了): 这一轮的邮箱流程作废
    if (em && em.bind !== !!d.loggedIn) em = null;
    var l = d.login || { state: 'idle' }, full = !!(d.loggedIn && d.bangumi);
    // 刚登录上 (手机这边发起的, 或者电视上自己登的): 让评论与评分区重新读一次
    if (wasIn === false && full) hooks.login.forEach(function (h) { h(); });
    wasIn = full;
    waiting = l.state === 'waiting';
    var h = '<div class="card set-card"><div class="set-title">账号</div>';
    if (d.loggedIn) {
      h += '<div class="acct" data-acct="menu">' + avatar(d) + '<div><div class="acct-name">' + esc(d.name || '已登录') + '</div><div class="acct-sub">' +
        (d.bangumi ? '已连接 Bangumi' + (d.bgmName ? '（' + esc(d.bgmName) + '）' : '') : '还没连接 Bangumi，连接后收藏、进度和评分会同步到你的 Bangumi 账号') +
        '</div></div><span class="acct-more">' + (menu ? '收起' : '管理') + '</span></div>';
      if (menu && nick) {
        h += '<form class="acct-nick" id="acct-nick"><input type="text" name="n" maxlength="20" autocomplete="off" placeholder="新昵称" value="' +
          esc(d.nickname || '') + '"><button type="submit">保存</button></form>' +
          '<p class="hint">6–20 个字符（汉字、假名算 2 个），只能用中日文、字母、数字和下划线</p>';
      } else if (menu && em) {
        h += emailFlow(d);
      } else if (menu) {
        h += '<div class="acct-menu"><button type="button" class="ghost ic" data-acct="nick">' + window.ICONS.edit + '修改昵称</button>' +
          '<button type="button" class="ghost ic" data-acct="email">' + MAIL + (d.email ? '更换邮箱' : '绑定邮箱') + '</button>' +
          '<button type="button" class="ghost acct-danger ic" data-acct="logout">' + window.ICONS.logout + '退出登录</button></div>';
      }
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
      // 另一条路: 邮箱登录 / 注册 Animeko 账号 (同 App 登录页的邮箱登录, 不用浏览器)
      if (!d.loggedIn) {
        h += em ? emailFlow(d) : '<div class="row"><button type="button" class="ghost ic" data-acct="email">' + MAIL + '用邮箱登录 / 注册</button></div>';
      }
    }
    h += '</div>';
    if (h !== last) {
      // 重画保住正在填的 (邮箱 / 验证码 / 昵称) 与焦点
      var typed = {}, act = document.activeElement, focus = act && box.contains(act) && act.form ? act.form.id + '.' + act.name : null;
      [].forEach.call(box.querySelectorAll('form[id] input[name]'), function (i) { typed[i.form.id + '.' + i.name] = i.value; });
      box.innerHTML = h;
      last = h;
      [].forEach.call(box.querySelectorAll('form[id] input[name]'), function (i) {
        var k = i.form.id + '.' + i.name;
        if (typed[k] != null) i.value = typed[k];
        if (k === focus) i.focus();
      });
    }
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
      return;
    }
    if (e.target.closest('#set-account [data-acct="menu"]')) { menu = !menu; nick = false; em = null; rerender(); return; }
    // 邮箱: 打开 / 收起 / 换个邮箱 / 重新发送
    if (e.target.closest('[data-acct="email"]')) {
      em = { step: 'email', email: '', bind: !!(lastData && lastData.loggedIn) };
      rerender();
      var ef = document.getElementById('acct-email');
      if (ef) ef.elements.e.focus();
      return;
    }
    if (e.target.closest('[data-acct="email-close"]')) { em = null; rerender(); return; }
    if (e.target.closest('[data-acct="email-back"]')) { if (em) em.step = 'email'; rerender(); return; }
    var rs = e.target.closest('[data-acct="email-resend"]');
    if (rs) { if (em && !rs.disabled) sendOtp(em.email, rs); return; }
    if (e.target.closest('[data-acct="nick"]')) {
      nick = true;
      rerender();
      var f = document.getElementById('acct-nick');
      if (f) f.elements.n.focus();
      return;
    }
    if (e.target.closest('[data-acct="logout"]')) {
      if (!confirm('退出电视上的登录？\n\n退出后收藏同步、评分和评论都要重新登录才能用。')) return;
      post('api/account/logout', {}).then(function (r) { toast(r.message); menu = false; load(); }).catch(fail);
    }
  });
  box.addEventListener('submit', function (e) {
    var f = e.target;
    if (f.id === 'acct-email') {
      e.preventDefault();
      sendOtp(f.elements.e.value.trim(), f.querySelector('button'));
      return;
    }
    if (f.id === 'acct-otp') {
      e.preventDefault();
      var vb = f.querySelector('button');
      vb.disabled = true;
      post('api/account/email/verify', { code: f.elements.c.value.trim() }).then(function (r) {
        vb.disabled = false;
        toast(r.message);
        if (r.ok) { em = null; menu = false; load(); }
      }).catch(function () { vb.disabled = false; fail(); });
      return;
    }
    if (e.target.id !== 'acct-nick') return;
    e.preventDefault();
    var btn = e.target.querySelector('button');
    btn.disabled = true;
    post('api/account/nickname', { nickname: e.target.elements.n.value.trim() }).then(function (r) {
      btn.disabled = false;
      toast(r.message);
      if (r.ok) { menu = false; nick = false; load(); }
    }).catch(function () { btn.disabled = false; fail(); });
  });
  // 从授权页切回来: 马上问一次, 不等下一轮 (后台标签页里的定时器会被浏览器压着)
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && (waiting || !box.closest('.tab').hidden)) load();
  });
})();
""".trimIndent()

/**
 * 右上角「?」: 当前标签页的使用说明 (全屏面板, 同播放记录那种). 页面里不再常驻「点哪里干什么 / 怎么滑」这类教程文字 ——
 * 常驻的字看过一次就成了背景, 反而占地方; 会影响决定的后果 / 限制 (改完立即生效、订阅来的源只能启停…) 仍留在原地.
 * 从没打开过时按钮上带小红点 (记在这台手机的浏览器里). 手势另有列表里的一次性滑开提示 (swPeek), 不指望用户先来读说明.
 */
private val HELP_SCRIPT = """
(function () {
  var btn = document.getElementById('help-btn'), sheet = document.getElementById('help-sheet');
  var body = document.getElementById('help-body'), title = document.getElementById('help-title'), dot = btn.querySelector('.help-dot');
  var SEEN = 'remote.helpSeen', seen = true;
  try { seen = !!localStorage.getItem(SEEN); } catch (e) {}
  dot.hidden = seen;
  function sec(t, items) {
    return '<div class="card help-card"><div class="set-title">' + t + '</div><ul class="help-list">' +
      items.map(function (x) { return '<li>' + x + '</li>'; }).join('') + '</ul></div>';
  }
  var SEARCH = sec('搜索', [
    '输入关键词，按需要选排序、最低评分和标签，点「在电视上搜索」，电视会跳到搜索结果。',
    '点搜索框会列出最近搜过的词：点一条直接搜，点 × 删掉这条记录。'
  ]) + sec('结果', [
    '列的是电视搜索页已经加载的结果，翻到底会自动加载更多。',
    '点右边的封面（没有封面的点 ▶）：电视直接开始播放；点标题或其他地方：电视打开详情页。',
    '右滑一行：缓存这部番；左滑：设置收藏状态。滑过一半松手直接执行。',
    '电视离开了搜索页时，底部可以让它回到原来的搜索结果。'
  ]);
  var PLAYER = sec('播放卡', [
    '点剧名：电视打开详情页（叠在播放器上，按返回回来）。',
    '「第几集」那一行可以点开选集，打 ✓ 的是看过的。',
    '点数据源胶囊：跳到下面正在播的那一条；右上角「缓存」：打开这部番的缓存面板。',
    '播放 / 暂停、后退 / 前进 10 秒；拖进度条跳转，点时间可以直接输入要跳到哪。'
  ]) + sec('数据源', [
    '点一条就换成它播放；上面的胶囊可以只看某个源，下拉框按分辨率、字幕、字幕组筛。',
    '弹幕、音轨与字幕、播放信息、评论与评分在下面可以展开的卡片里。',
    '电视退出了播放器（播放还在后台留着）时也能换源，播放控制要回到播放器才能用。'
  ]);
  var CACHE = sec('缓存', [
    '列出电视上的全部缓存，按番分组，下载中的会自动刷新；顶上是电视的剩余空间。',
    '点击番名：在电视上打开详情页。点击右侧封面或 ▶：按观看进度继续播放（同详情页的播放按钮）。',
    '点击某一集：在电视上播放这一集。',
    '「全选」默认只选择正片，特别篇需要手动选择。可在「设置 → 本机偏好」中改为同时选择特别篇。',
    '左滑可删除该集缓存，删除前会再次确认。点击行尾按钮可暂停或继续。长按可进入多选，跨番批量删除。',
    '番名那一行右滑：缓存更多剧集；左滑：删除这部番的全部缓存（先确认）。滑过一半松手直接执行。',
    '最下面「挑番缓存」：从在看 / 想看里挑番缓存，在看里有新集的排在前面，并标出几集还没缓存。'
  ]);
  var GENERAL = sec('账号', [
    '没登录时可以「用手机登录 Bangumi」（在手机上授权），或用邮箱登录 / 注册 Animeko 账号。',
    '点头像或名字：修改昵称、绑定 / 更换邮箱、退出登录。'
  ]) + sec('播放记录', [
    '点右边的封面（或 ▶）：在电视上接着看，看完的播下一集；点其他地方：电视打开详情页。',
    '右滑缓存，左滑删除，滑过一半松手直接执行；长按一行可以多选，一起删除。'
  ]) + sec('其他', [
    '「本机偏好」只影响这台手机；代理、BT Tracker、弹幕屏蔽词改完立即生效；最底下可以下载电视的日志。'
  ]);
  var SOURCES = sec('数据源', [
    '订阅：粘贴订阅地址添加，在线数据源都来自订阅；长按订阅可以多选删除。',
    '数据源可以启用 / 停用、上下调整顺序、编辑、复制、导入导出；订阅来的源只能启用或停用。'
  ]);
  function content() {
    var s = document.querySelector('section.tab:not([hidden])'), tab = s ? s.id.replace('tab-', '') : 'search';
    if (tab === 'player') return ['播放器', PLAYER];
    if (tab === 'cache') return ['缓存', CACHE];
    if (tab === 'settings') {
      var src = document.getElementById('set-sources');
      return ['设置', src && !src.hidden ? SOURCES + GENERAL : GENERAL + SOURCES];
    }
    return ['搜索', SEARCH];
  }
  function open() {
    var c = content();
    title.textContent = '使用说明 · ' + c[0];
    body.innerHTML = c[1];
    body.scrollTop = 0;
    window.sheets.open(sheet);
    if (!seen) {
      seen = true;
      dot.hidden = true;
      try { localStorage.setItem(SEEN, '1'); } catch (e) {}
    }
  }
  function close() { window.sheets.close(sheet); }
  btn.addEventListener('click', open);
  document.getElementById('help-close').addEventListener('click', close);
})();
""".trimIndent()

/**
 * 缓存标签最下面的「挑番缓存」(见 RemoteCollections): 在看 / 想看的番, 想提前缓存时不用先搜名字、也不用先进一次播放器.
 * 行与搜索结果同一套 (右滑缓存 = 打开这部番的缓存面板, 左滑改收藏, 点封面播放 / 点其他地方开详情, 接口也复用搜索结果的);
 * 在看里有新集的排前面并标出几集还没缓存. 两段各读一次, 缓存面板关上时重读当前段 (刚缓存了, 「未缓存」的数跟着变).
 */
private val PICK_SCRIPT = """
(function () {
  var box = document.getElementById('cl-pick'), sheet = document.getElementById('pick-sheet');
  var body = document.getElementById('pick-body'), seg = document.getElementById('pick-seg');
  var type = 'DOING', data = {}, loading = {};
  box.innerHTML = '<button type="button" class="ghost wide ic" id="pick-open">' + window.ICONS.download + '挑番缓存（在看 / 想看）</button>';
  // 两段各一个容器, 切换只是显示 / 隐藏: 画好的那段原样留着, 切回来不重画、封面不重新淡入
  body.innerHTML = '<div class="pick-pane" data-pt="DOING"></div><div class="pick-pane" data-pt="WISH" hidden></div>';
  /** 读过的一段这么久之内切回来不重新请求 (打开面板 / 改了收藏 / 缓存面板关上时照样强制重读). */
  var FRESH = 60000;
  function row(x) {
    return window.swRow(
      '<button type="button" class="sw-btn cache" data-cache="' + x.id + '" data-title="' + esc(x.title) + '">' + window.ICONS.download + '缓存</button>',
      '<button type="button" class="sw-btn coll" data-coll="' + x.id + '">' + window.ICONS.star + '收藏</button>',
      '<div class="item res-item pick-item' + (x.cover ? ' cv' : '') + (x.blur ? ' nsfw-blur' : '') + '" data-sid="' + x.id + '">' +
      (x.cover ? window.coverLayers(x.cover) : '') +
      '<span class="t">' + esc(x.title) + '</span>' +
      (x.line ? '<span class="m' + (x.fresh ? ' pick-new' : '') + '">' + esc(x.line) + '</span>' : '') +
      '<button type="button" class="res-play" aria-label="播放"><span class="play-glyph">' + window.ICONS.play + '</span></button></div>');
  }
  function pane(t) { return body.querySelector('.pick-pane[data-pt="' + t + '"]'); }
  function paint(t) {
    var p = pane(t), d = data[t];
    if (!d) {
      if (!p.querySelector('.list')) p.innerHTML = '<p class="hint">正在读取…</p>';
      return;
    }
    if (!d.ok) {
      p.innerHTML = '<div class="empty"><p>' + esc(d.message || '读取失败') + '</p>' +
        (d.needLogin ? '<p class="hint">在「设置」里登录后再来</p>' : '') + '</div>';
      return;
    }
    var items = d.items || [];
    if (!items.length) {
      p.innerHTML = '<div class="empty"><p>' + (t === 'DOING' ? '没有在看的番' : '没有想看的番') + '</p></div>';
      return;
    }
    var list = p.querySelector(':scope > .list');
    if (!list) {
      p.innerHTML = '<p class="hint pick-stale" hidden>电视没连上服务器，下面是它上次同步的列表</p><div class="list"></div>';
      list = p.querySelector(':scope > .list');
    }
    p.querySelector('.pick-stale').hidden = !d.stale;
    // 按行增量更新 (同搜索结果): 没变的行原样留着, 封面不重建、不闪
    window.patchList(list, items.map(row));
  }
  function load(t, force) {
    var old = data[t];
    if (loading[t] || (!force && old && old.ok && Date.now() - old.at < FRESH)) return;
    loading[t] = true;
    fetch('api/collections?type=' + t).then(function (r) { return r.json(); }).then(function (d) {
      loading[t] = false;
      d.at = Date.now();
      data[t] = d;
      if (!sheet.hidden) paint(t);
    }).catch(function () {
      loading[t] = false;
      if (!data[t]) data[t] = { ok: false, message: '读取失败，关掉再打开试试' };
      if (!sheet.hidden) paint(t);
    });
  }
  // 改了收藏状态之后: 当前段就地重读 (先留着旧的不闪「正在读取」), 另一段作废, 切过去时重读
  window.loadPick = function () {
    if (sheet.hidden) return;
    Object.keys(data).forEach(function (k) { if (k !== type && data[k]) data[k].at = 0; });
    load(type, true);
  };
  function open() {
    window.sheets.open(sheet);
    paint(type);
    load(type, true);
  }
  function close() { window.sheets.close(sheet); }
  box.addEventListener('click', function (e) { if (e.target.closest('#pick-open')) open(); });
  document.getElementById('pick-close').addEventListener('click', close);
  seg.addEventListener('click', function (e) {
    var b = e.target.closest('[data-ptype]');
    if (!b) return;
    type = b.getAttribute('data-ptype');
    [].forEach.call(seg.children, function (c) { c.classList.toggle('on', c === b); });
    [].forEach.call(body.querySelectorAll('.pick-pane'), function (p) { p.hidden = p.getAttribute('data-pt') !== type; });
    paint(type);
    load(type, false);
  });
  body.addEventListener('click', function (e) {
    var b = e.target.closest('.pick-item');
    if (!b || b.classList.contains('busy')) return;
    var play = !!e.target.closest('.res-play');
    b.classList.add('busy');
    if (play) b.classList.add('hit');
    post(play ? 'api/search/play' : 'api/search/open', { id: b.getAttribute('data-sid') }).then(function (r) {
      b.classList.remove('busy', 'hit');
      toast(r.message);
      // 进了播放页: 收起面板切到「播放器」(同播放记录)
      if (r.player) {
        close();
        setTimeout(function () { if (window.showTab) window.showTab('player'); }, 1200);
      }
    }).catch(function () { b.classList.remove('busy', 'hit'); fail(); });
  });
  // 从这里右滑打开的缓存面板关上了 (多半刚缓存了几集): 重读当前这一段, 「几集没缓存」跟着变
  document.getElementById('cache-sheet').addEventListener('sheetclose', function () { if (!sheet.hidden) load(type, true); });
})();
""".trimIndent()

/**
 * 「设置」里的**播放记录** (见 RemoteHistory): 账号卡片下面一张入口卡片 (几部 + 最近看的哪部), 点它或点账号头像 / 名字打开全屏面板
 * (同缓存面板那种). 一部番一行: 封面、番名、看到哪 (进度条) / 已看完、时间; 点一行 = 电视打开详情页 (面板不关, 方便接着点别的),
 * 点 ▶ = 接着看 (看完的播下一集), 进了播放页就收起面板切到「播放器」, 同搜索结果. 列表在切到设置标签和打开面板时各读一次.
 */
private val HISTORY_SCRIPT = """
(function () {
  var entry = document.getElementById('set-history'), sheet = document.getElementById('hist-sheet'), hb = document.getElementById('hist-body');
  var ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>';
  var items = null, loading = false;
  function fetchHistory(lite) {
    return fetch('api/history' + (lite ? '?lite=1' : '')).then(function (r) { return r.ok ? r.json() : null; });
  }
  // 设置标签里的入口卡片: 只要条数和最近那部, 不带图 (后端找图要查缓存, 入口不等它)
  function load() {
    fetchHistory(true).then(function (d) { if (d && d.ok) paintEntry(d.items || []); }).catch(function () {});
  }
  window.loadHistory = load;
  function loadList() {
    if (loading) return;
    loading = true;
    fetchHistory(false).then(function (d) {
      loading = false;
      if (!d || !d.ok) return;
      items = d.items || [];
      paintEntry(items);
      if (!sheet.hidden) paintList();
    }).catch(function () {
      loading = false;
      if (!sheet.hidden && !items) hb.innerHTML = '<p class="hint">读取播放记录失败，关掉再打开试试</p>';
    });
  }
  function paintEntry(list) {
    var n = list.length;
    entry.innerHTML = '<button type="button" class="card set-card hist-entry" data-hist="1">' + ICON +
      '<span class="hist-txt"><b>播放记录</b><small>' + (n ? n + ' 部 · 最近看了「' + esc(list[0].title) + '」' : '还没有播放记录') +
      '</small></span><span class="hist-go">›</span></button>';
  }
  function paintList() {
    if (!items) { hb.innerHTML = '<p class="hint">正在读取…</p>'; return; }
    if (!items.length) {
      hb.innerHTML = '<div class="empty"><p>还没有播放记录</p><p class="hint">在电视上看过的番会按最近看的顺序列在这里</p></div>';
      return;
    }
    // 怎么用 (点封面接着看 / 滑动 / 长按多选) 在右上角「?」里, 第一行会自动滑开一次提示 (swPeek)
    hb.innerHTML = '<div class="list">' + items.map(function (x) {
      var imgs = x.imgs || [];
      // 右滑露出「缓存」(打开这部番的缓存面板), 左滑露出「删除」(删掉这部番的播放记录)
      return window.swRow(
        '<button type="button" class="sw-btn cache" data-cache="' + x.id + '" data-title="' + esc(x.title) + '">' +
          window.ICONS.download + '缓存</button>',
        '<button type="button" class="sw-btn del" data-hdel="' + x.id + '">' + window.ICONS.trash + '删除</button>',
        '<div class="item hist-item' + (imgs.length ? ' cv' : '') + '" data-sid="' + x.id + '" data-lp="' + x.id + '">' +
        (imgs.length ? window.coverLayers(imgs) : '') + '<span class="sel-mark" aria-hidden="true"></span>' +
        '<div class="hist-main"><span class="t">' + esc(x.title) + '</span><span class="m">' + esc(x.line) + '</span>' +
        (x.percent != null ? '<div class="hist-bar"><div style="width:' + x.percent + '%"></div></div>' : '') +
        (x.time ? '<span class="hist-time">' + esc(x.time) + '</span>' : '') + '</div>' +
        '<button type="button" class="res-play" aria-label="播放"><span class="play-glyph">' + window.ICONS.play + '</span></button></div>');
    }).join('') + '</div>';
    window.selSync(hb);
    window.swPeek(hb, 'hist');
  }
  function open() {
    window.sheets.open(sheet);
    paintList();
    loadList();
  }
  function close() {
    window.selEnd();
    window.sheets.close(sheet);
  }
  /** 删掉这些番的播放记录 (左滑一部 / 长按多选几部); 删成的行收起来移除. 返回 Promise<是否删成> */
  function deleteSubjects(ids) {
    return post('api/history/delete', { ids: ids.join(',') }).then(function (r) {
      toast(r.message);
      if (!r.ok) return false;
      var gone = (r.deleted || ids).map(String);
      items = (items || []).filter(function (x) { return gone.indexOf(String(x.id)) < 0; });
      paintEntry(items);
      [].forEach.call(hb.querySelectorAll('.sw'), function (sw) {
        var row = sw.querySelector('[data-lp]');
        if (!row || gone.indexOf(row.getAttribute('data-lp')) < 0) return;
        sw.style.height = sw.offsetHeight + 'px';
        void sw.offsetHeight;
        sw.classList.add('gone');
        setTimeout(function () { sw.remove(); }, 260);
      });
      setTimeout(function () { if (!items.length) paintList(); }, 270);
      return true;
    });
  }
  // 长按一行: 进多选, 那一行先勾上
  hb.addEventListener('longpress', function (e) {
    window.selStart({
      box: hb,
      ask: function (n) { return '删除选中的 ' + n + ' 部番的播放记录？电视上的播放历史也会一起删掉。'; },
      del: deleteSubjects
    }, e.target.getAttribute('data-lp'));
  });
  // 入口: 设置里那张「播放记录」卡片, 以及账号卡片上的头像 / 名字
  document.addEventListener('click', function (e) {
    if (e.target.closest('[data-hist]')) open();
  });
  document.getElementById('hist-close').addEventListener('click', close);
  hb.addEventListener('click', function (e) {
    // 左滑露出的「删除」: 删掉这部番的播放记录 (滑开再点本身就是两步, 不另外确认); 成功后这一行收起来移除
    var del = e.target.closest('[data-hdel]');
    if (del) {
      if (del.disabled) return;
      del.disabled = true;
      // 删不成: 滑到底时行已经滑出去了, 放回来
      deleteSubjects([del.getAttribute('data-hdel')]).then(function (ok) { if (!ok) { del.disabled = false; window.swClose(del.closest('.sw')); } })
        .catch(function () { del.disabled = false; window.swClose(del.closest('.sw')); fail(); });
      return;
    }
    var b = e.target.closest('.hist-item');
    if (!b || b.classList.contains('busy')) return;
    var play = !!e.target.closest('.res-play');
    b.classList.add('busy');
    if (play) b.classList.add('hit'); // 同搜索结果
    post(play ? 'api/history/play' : 'api/history/open', { id: b.getAttribute('data-sid') }).then(function (r) {
      b.classList.remove('busy', 'hit');
      toast(r.message);
      // 进了播放页: 收起面板切到「播放器」, 数据源结果陆续回来可以直接挑 (同搜索结果)
      if (r.player) {
        close();
        setTimeout(function () { if (window.showTab) window.showTab('player'); }, 1200);
      }
    }).catch(function () { b.classList.remove('busy', 'hit'); fail(); });
  });
})();
""".trimIndent()
