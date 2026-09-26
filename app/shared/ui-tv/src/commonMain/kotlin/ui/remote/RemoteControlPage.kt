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
 * Web 控制台 (网页里标题叫「Izuko TV 控制台」; 手机、电脑的浏览器都能开, 原叫「手机遥控 / 控制中心」) 的网页: 底部四个标签 (搜索 / 播放器 / 缓存 / 设置) 的单页应用; 搜索标签顶上再分「搜索 / 结果」两页,
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
    /** 当前语言的译文 (见 [RemoteI18n.pageScript]); 空 = 简体 */
    i18nScript: String = "",
    /** 这份脚本的版本号, 显示在设置页底部: 手机上刷没刷新、拿到的是不是新脚本, 对一下这个号就知道 */
    pageVersion: String = "",
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
    <title>Izuko TV 控制台</title>
    <script>
    """.trimIndent() + "\n" + "window.pageVersion = '" + pageVersion + "';\n" + i18nScript + "\n" + LANG_SCRIPT + "\n" + THEME_HEAD_SCRIPT + "\n" + """
    </script>
    <style>
    """.trimIndent() + "\n" + STYLE + "\n" + themeCss + """
    </style>
    </head>
    <body>
    <header><span>Izuko TV 控制台</span><button type="button" id="help-btn" aria-label="使用说明" title="使用说明">?<span class="help-dot" hidden></span></button></header>
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
    <div id="player-refetch"></div>
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
    <div id="set-bangumi"></div>
    <div id="set-tmdb"></div>
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
    <div class="sheet-body"><div id="pick-head"><div class="seg" id="pick-seg"><button type="button" data-ptype="DOING" class="on">在看</button><button type="button" data-ptype="WISH">想看</button><button type="button" data-ptype="SCHEDULE">新番时间表</button></div><div id="pick-days" hidden></div></div><div id="pick-body"></div></div>
    </div>
    <div id="help-sheet" class="sheet" hidden>
    <div class="sheet-head"><div class="sheet-title" id="help-title">使用说明</div><button type="button" class="sheet-btn" id="help-close" aria-label="关闭" title="关闭"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button></div>
    <div class="sheet-body" id="help-body"></div>
    </div>
    <div id="toast"></div>
    <div id="sel-bar" hidden><button type="button" data-sel="cancel">取消</button><span class="sel-n"></span><button type="button" data-sel="all">全选</button><button type="button" class="sel-pause" data-sel="pause" hidden></button><button type="button" class="sel-pause" data-sel="resume" hidden></button><button type="button" class="ic sel-del" data-sel="del">删除</button></div>
    <nav class="tabbar">
    <button data-tab="search"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg><span class="tl">搜索</span></button>
    <button data-tab="player"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M10 16.5l6-4.5-6-4.5v9zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z"/><path class="f" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"/></svg><span class="tl">播放器</span></button>
    <button data-tab="cache"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M19 9h-4V3H9v6H5l7 7 7-7zm-8 2V5h2v6h1.17L12 13.17 9.83 11H11zm-6 7h14v2H5z"/><path class="f" d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg><span class="tl">缓存</span></button>
    <button data-tab="settings"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="o" d="M19.43 12.98c.04-.32.07-.64.07-.98 0-.34-.03-.66-.07-.98l2.11-1.65c.19-.15.24-.42.12-.64l-2-3.46c-.09-.16-.26-.25-.44-.25-.06 0-.12.01-.17.03l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65C14.46 2.18 14.25 2 14 2h-4c-.25 0-.46.18-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1c-.06-.02-.12-.03-.18-.03-.17 0-.34.09-.43.25l-2 3.46c-.13.22-.07.49.12.64l2.11 1.65c-.04.32-.07.65-.07.98 0 .33.03.66.07.98l-2.11 1.65c-.19.15-.24.42-.12.64l2 3.46c.09.16.26.25.44.25.06 0 .12-.01.17-.03l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.06.02.12.03.18.03.17 0 .34-.09.43-.25l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.65zm-1.98-1.71c.04.31.05.52.05.73 0 .21-.02.43-.05.73l-.14 1.13.89.7 1.08.84-.7 1.21-1.27-.51-1.04-.42-.9.68c-.43.32-.84.56-1.25.73l-1.06.43-.16 1.13-.2 1.35h-1.4l-.19-1.35-.16-1.13-1.06-.43c-.43-.18-.83-.41-1.23-.71l-.91-.7-1.06.43-1.27.51-.7-1.21 1.08-.84.89-.7-.14-1.13c-.03-.31-.05-.54-.05-.74s.02-.43.05-.73l.14-1.13-.89-.7-1.08-.84.7-1.21 1.27.51 1.04.42.9-.68c.43-.32.84-.56 1.25-.73l1.06-.43.16-1.13.2-1.35h1.39l.19 1.35.16 1.13 1.06.43c.43.18.83.41 1.23.71l.91.7 1.06-.43 1.27-.51.7 1.21-1.07.85-.89.7.14 1.13zM12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0 6c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"/><path class="f" d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg><span class="tl">设置</span></button>
    </nav>
    <script>
    var INITIAL_TAB = '$initialTab';
    """.trimIndent() + "\n" + SCRIPT + "\n" + REQUEST_SCRIPT + "\n" + CONTROL_SCRIPT + "\n" + DANMAKU_SCRIPT + "\n" + REVIEW_SCRIPT + "\n" + CACHE_SCRIPT + "\n" + CACHE_LIST_SCRIPT + "\n" + SOURCES_SCRIPT + "\n" + SUBS_SCRIPT + "\n" + SETTINGS_SCRIPT + "\n" + LOOK_SCRIPT + "\n" + LOGS_SCRIPT + "\n" + ACCOUNT_SCRIPT + "\n" + HISTORY_SCRIPT + "\n" + HELP_SCRIPT + "\n" + PICK_SCRIPT + "\n" + """
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
/* 缓存标签最下面「挑番缓存」(见 PICK_SCRIPT): 在看 / 想看的番与新番时间表, 行同搜索结果; 有新集的那句
   (时间表里是自己在看 / 想看的番) 用主题色 */
#cl-pick { margin-top: 14px; }
/* 分段 (与时间表的星期) 贴在面板顶上: 一天的列表常要往下翻, 换一天不必先滚回去. 头自带底色、左右撑满面板,
   滚过去的行不会从两侧漏出来; flow-root 让分段、星期的下边距算在头里 (不然那一截透明).
   粘性定位从面板的内容框算起, top 抵掉面板 8px 的上内边距才贴到顶边 (同 .cache-bar 的 bottom: -24px) */
#pick-head { position: sticky; top: -8px; z-index: 3; display: flow-root; margin: -8px -16px 0; padding: 8px 16px 0; background: var(--bg); }
#pick-seg { margin-bottom: 10px; }
/* 星期: 二级标签, 比上面的分段轻一档 —— 选中的主题色加下划线, 今天没选中时字深一点 */
#pick-days { display: flex; margin: -2px 0 10px; border-bottom: 1px solid var(--line2); }
#pick-days[hidden] { display: none; }
#pick-days button { position: relative; flex: 1; background: none; padding: 8px 0 10px; font-size: 15px; color: var(--mute); }
#pick-days button[data-today] { color: var(--fg); font-weight: 600; }
#pick-days button.on { color: var(--p); font-weight: 700; }
#pick-days button.on::after { content: ''; position: absolute; left: 50%; bottom: -1px; width: 22px; height: 3px; margin-left: -11px;
  border-radius: 3px 3px 0 0; background: var(--p); }
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
/* 搜索筛选里「更多年份 / 收起」: 长得跟胶囊一样, 但它是按钮不是选项 */
.morebtn { padding: 6px 13px; border-radius: 18px; background: none; border: 1px solid var(--outline); color: var(--sub); font-size: 14px; line-height: 1.3; }
/* .pills 自己是 flex, 优先级压过 UA 样式表给 [hidden] 的 display:none, 不补这条藏不住 */
.pills[hidden] { display: none; }
#year-rest { margin-top: 8px; }
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
/* 数据源列表顶部的工具行 ("全部测试") */
/*
 * 工具行始终跟随滚动粘在顶部: 数据源一二十个, 翻到下面想选一个还得先拖回去。
 * 左右 margin 归零改用 padding: 两边留缝的话, 滚动的内容会从背景旁边透出来。
 * 上间距改由 #src-add 给 (自己带 margin-top 的话, 吸顶时会在上面留出一道空隙)。
 */
#src-add { display: block; margin-bottom: 18px; }
/* 负 margin 把背景撞满 .tab 的左右内边距 (各 16px): 条目的描边与阴影会向外
   扩散到那一带, 面板只有内容区那么宽的话正好盖不住, 滚动时会从两侧露出来 */
.src-tools { position: sticky; top: var(--seg-h, 0px); z-index: 3; background: var(--bg); display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 0 -16px; padding: 10px 16px 12px; }
.src-tools button { padding: 9px 14px; border-radius: 14px; background: var(--chip); color: var(--on-chip); font-size: 14px; }
.src-tools button:disabled { opacity: .55; }
.src-tools .hint { margin: 0; flex: 1; min-width: 12em; }
/* 多选模式: 批量栏跟随滚动粘在顶部, 选中的行描一圈边 */
/*
 * 选择模式分两行: 上面是选了多少 / 全选 / 完成, 下面一排才是真正动数据的操作。
 * 挤在一行时「完成」和「删除」会挨在一起, 手机上很容易点错。
 */
.src-tools.picking { flex-direction: column; align-items: stretch; }
.src-bar { display: flex; align-items: center; gap: 10px; }
/* 宽度够就把按钮撑开: 手机上点得到才是最要紧的 */
.src-bar-ops button { flex: 1; min-width: 4.5em; padding: 11px 8px; }
.src-tools.picking .src-count { flex: 1; }
.src-tools .src-count { font-size: 14px; color: var(--mute); }
.src-pickbox { display: flex; align-items: center; }
.src-pickbox input { width: 20px; height: 20px; }
/* 选择模式下藏掉启用开关与单行按钮: 两套操作同时在场很容易误点 */
#src-list.picking .src-sw, #src-list.picking .src-btns { display: none; }
#src-list.picking .src-item { cursor: pointer; }
/*
 * 选中整行染主色淡底 (--p-soft) 再加一圈实描边: 只靠左侧那个小勾在手机上看不清
 * 选中了哪几行。淡底负责一眼扫过去的识别, 描边负责单行的确认, 两者缺一不可。
 */
.src-item.picked { background: var(--p-soft); box-shadow: 0 0 0 2px var(--p), 0 2px 8px var(--shadow-sm); }
.src-item.picked .src-name { color: var(--fg); }
#src-list.picking input[type=checkbox] { accent-color: var(--p); }
/* 数据源「测试」的结果: 留在那一行下面, 成功绿 / 失败红 (见 SOURCES_SCRIPT 的 testSource) */
.src-test { margin: 8px 2px 0; font-size: 13px; color: var(--mute); }
.src-test.ok { color: #2e7d32; }
.src-test.bad { color: var(--err); }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) .src-test.ok { color: #7bc67e; } }
:root[data-theme="dark"] .src-test.ok { color: #7bc67e; }
/* 数据源列表上方的「重新搜索(含新数据源)」: 不抢眼, 但找得到 */
.src-refetch { margin: 2px 2px 14px; }
.src-refetch button { padding: 9px 14px; border-radius: 14px; background: var(--chip); color: var(--on-chip); font-size: 14px; }
.src-refetch button:disabled { opacity: .5; }
.src-refetch .hint { margin-top: 6px; }
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
.src-name { flex: 1; min-width: 0; font-size: 15px; font-weight: 600; overflow: hidden; }
.src-name .t { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/*
 * 名字放不下时自己走一趟再走回来: 数据源名常常很长 (带站点 / 类型 / 备注),
 * 截断了看不出是哪个。滚动距离每行不同, 由 JS 量出来写进 --mq。
 * 没溢出的行不加 .mq, 不白白挂一个永久动画。
 */
.src-name.mq .t { text-overflow: clip; animation: src-mq 12s ease-in-out infinite; }
@keyframes src-mq { 0%, 12% { transform: translateX(0); } 45%, 58% { transform: translateX(var(--mq, 0)); } 92%, 100% { transform: translateX(0); } }
.src-name small { display: block; font-weight: 400; font-size: 12px; color: var(--mute); margin-top: 2px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 上移 / 下移挤到名字右边: 留在下面那排的话按钮经常溢成两行, 条目高度翻倍。
   底色与圆角跟下方那排保持一致, 不然挪出来之后就成了两枚裸图标 */
.src-ord { display: flex; gap: 6px; flex: none; }
.src-ord button { background: var(--chip); color: var(--on-chip); border-radius: 10px; }
.src-ord button:disabled { opacity: .4; }
#src-list.picking .src-ord { display: none; }
.src-desc { font-size: 12px; color: var(--mute); margin-top: 6px; word-break: break-all; }
.src-btns { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.src-btns button { box-sizing: border-box; height: 34px; background: var(--chip); color: var(--on-chip); padding: 0 12px; border-radius: 10px; font-size: 13px; }
.src-btns button:disabled { opacity: .4; }
.src-btns .src-danger { background: var(--err-bg); color: var(--err-fg); }
.src-panel { margin-top: 10px; }
.src-panel textarea, #src-add textarea, #src-import textarea { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.f select { box-sizing: border-box; width: 100%; height: 46px; font: inherit; font-size: 16px; padding: 0 10px; border: 1px solid var(--outline); border-radius: 12px; background: var(--field); color: var(--fg); }
#src-import input[type=file] { max-width: 100%; font: inherit; font-size: 14px; color: var(--fg); }
#src-import .imp-or { display: block; font-style: normal; font-size: 12px; color: var(--mute); margin: 12px 0 6px; }
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
/* 分页标签一直贴在顶上: 翻到下面想换页不必先滚回去。胶囊本身是圆角的, 两侧会漏出底下滚过的内容,
   所以垫一层撑满容器宽度的背景 (::before, .tab 左右各 16px 内边距). 粘性定位自成层叠上下文, 负 z-index 的垫层
   画在标签自身背景之上, 所以灰色轨道不能靠 .seg 的背景, 由 ::after 画在垫层上面 (同为 -1, 后出现的在上).
   iOS Safari 按贴着视口顶边的粘性元素给状态栏取色: 标签自身背景用页面底色 (反正被垫层盖住), 吸顶时离顶边留 8px,
   顶边那一排全是垫层 —— 不论按元素背景还是按像素取色, 吸顶前后状态栏都是底色 */
#set-seg, #search-seg { position: sticky; top: 8px; z-index: 4; background: var(--bg); }
#set-seg::before, #search-seg::before { content: ''; position: absolute; inset: -10px -16px -4px; background: var(--bg); z-index: -1; }
#set-seg::after, #search-seg::after { content: ''; position: absolute; inset: 0; border-radius: inherit; background: var(--chip); z-index: -1; }
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
/* 播放页候选行左滑露出的「打开链接」(见 SCRIPT 的 itemSwipe), 以及滑到底时弹的小窗 (openLinkDialog) */
.sw-btn.link { background: #2f7bf0; }
#link-dlg, #login-dlg { position: fixed; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center; padding: 16px;
  background: rgba(0,0,0,.45); }
.link-dlg-box { box-sizing: border-box; display: flex; flex-direction: column; width: 100%; max-width: 560px; max-height: 100%;
  background: var(--card); color: var(--fg); border-radius: 16px; padding: 20px 18px 16px; box-shadow: 0 8px 28px rgba(0,0,0,.3); }
.link-dlg-t { font-size: 17px; font-weight: 700; }
/* 地址整条显示, 长了就在框里滚动 (小窗最高撑到接近满屏), 能选中复制 */
.link-dlg-u { flex: 1 1 auto; min-height: 0; margin-top: 12px; padding: 10px 12px; border-radius: 12px; background: var(--chip);
  font-size: 14px; line-height: 1.5; overflow-wrap: anywhere; overflow-y: auto; overscroll-behavior: contain;
  -webkit-user-select: text; user-select: text; }
#link-dlg a.primary { text-decoration: none; text-align: center; }
/* 手机授权前的教学 (loginGuide): 三步, 第二步是重点 —— 授权完停在打不开的页面是正常的 */
.login-steps { margin: 12px 0 4px; padding-left: 1.4em; font-size: 15px; line-height: 1.6; }
.login-steps li + li { margin-top: 8px; }
.login-steps .risk { display: block; margin-top: 4px; color: var(--err); }
/* 设置里「已登录改用镜像」的询问 (bgmAsk): 说明两段 (第二段是风险, 红字), 三个选择竖排 —— 一行放不下 */
.dlg-p { margin: 12px 0 0; font-size: 15px; line-height: 1.6; }
.dlg-p.risk { color: var(--err); }
.dlg-acts { display: flex; flex-direction: column; gap: 8px; margin-top: 16px; }
/* 播放页候选行包进 .sw 之后: 选中的描边往里收, 否则被外层的圆角裁剪整圈裁掉; 被排除 / 不可选的半透明挪到行里的内容上 ——
   行本身半透明的话, 滑动时垫在下面的按钮会从行后面透出来 */
.sw > .item.sel { outline-offset: -2px; }
.sw > .item.ex, .sw > .item.blocked { opacity: 1; }
.sw > .item.ex > * { opacity: .72; }
.sw > .item.blocked > * { opacity: .45; }
/* 「跳到正在播的那一条」亮一下的底色要淡入淡出: .sw > .item 的平移过渡会盖掉 .item 原来的背景过渡, 补回来; 拖动中照旧不过渡 */
#player-sources .sw > .item:not(.dragging) { transition: transform .22s cubic-bezier(.2, .8, .2, 1), background-color .5s; }
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
#sel-bar .sel-n { flex: 1; min-width: 0; text-align: center; font-size: 14px; font-weight: 600;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
#sel-bar .sel-pause { padding: 0 10px; }
#sel-bar button { flex: none; box-sizing: border-box; height: 38px; padding: 0 14px; border-radius: 10px; background: var(--chip); color: var(--on-chip); font-size: 14px; }
#sel-bar .sel-del { background: var(--err-bg); color: var(--err-fg); }
#sel-bar button:disabled { opacity: .45; }
#sel-bar button[hidden] { display: none; }
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
/* 评分表单底部一行的「保存」 */
.cm-foot .primary { flex: none; box-sizing: border-box; height: 40px; padding: 0 24px; font-size: 15px; }
.cm-foot .primary:disabled { opacity: .45; }
.cm-web { display: block; box-sizing: border-box; margin-top: 8px; text-align: center; text-decoration: none; }
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
/* 时间偏移那一行: 手机上连按的是这几颗, 所以给足高度与间距 (原来 34px / 6px 挨得太近, 容易按错邻居) */
.dm-shift { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 8px; font-size: 12px; color: var(--mute); }
/* touch-action: manipulation = 不等"是不是双击", 于是既没有 300ms 的犹豫, 也不会双击放大页面 —— 手机上连按
   这几颗时把页面缩放掉是最烦的一件事 (用户 2026-09-19) */
.dm-shift button { box-sizing: border-box; height: 40px; min-width: 46px; background: var(--chip); color: var(--on-chip);
  padding: 0 12px; border-radius: 12px; font-size: 14px; font-variant-numeric: tabular-nums; touch-action: manipulation; }
.dm-shift button:active { opacity: .55; }
/* 数字本身可点 (点开就地输入), 所以做成看得出能按的样子 */
/* 数字: 轻点开输入框, 按住左右拖就连续调 (见 CONTROL_SCRIPT 的 pointer 处理).
   pan-y = 纵向照旧滚页面, 横向归我们, 拖的时候页面不会跟着乱动 */
.dm-shift b { box-sizing: border-box; height: 40px; min-width: 4.8em; padding: 0 6px; display: inline-flex; align-items: center;
  justify-content: center; border-radius: 12px; border: 1px solid var(--line); cursor: ew-resize;
  color: var(--fg); font-weight: 600; font-variant-numeric: tabular-nums;
  touch-action: pan-y; user-select: none; -webkit-user-select: none; }
.dm-shift b.dragging { border-color: var(--p); color: var(--p); }
.dm-shift b:active { opacity: .55; }
.dm-shift .dm-shift-in { box-sizing: border-box; height: 40px; min-width: 4.8em; width: 4.8em; padding: 0 6px; text-align: center;
  border-radius: 12px; border: 1px solid var(--p); background: var(--card); color: var(--fg);
  font-size: 14px; font-weight: 600; font-variant-numeric: tabular-nums; }
.dm-form { display: flex; gap: 8px; margin-top: 12px; }
.dm-form input { flex: 1; min-width: 0; }
.dm-form button { flex: none; padding: 12px 16px; }
.dm-list { display: flex; flex-direction: column; gap: 6px; margin-top: 10px; max-height: 340px; overflow-y: auto; }
.dm-list button { text-align: left; background: var(--soft); color: var(--fg); padding: 10px 12px; border-radius: 10px; font-size: 14px; flex: none; }
.dm-list button.sug { outline: 2px solid var(--p); }
/* 气泡里的话不短 (最长的那条 40 多字, 手机上要折三行): 行距必须给够, 不然中文几行挤成一块;
   宽屏上再宽就成一长条了, 所以另给一个绝对上限 */
#toast { position: fixed; left: 50%; bottom: calc(132px + env(safe-area-inset-bottom)); transform: translateX(-50%); background: var(--toast-bg); color: var(--toast-fg); padding: 12px 18px; border-radius: 16px; font-size: 14px; line-height: 1.55; opacity: 0; transition: opacity .2s; pointer-events: none; max-width: 86%; width: max-content; box-sizing: border-box; text-align: center; }
@media (min-width: 560px) { #toast { max-width: 30rem; } }
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
.now-card #pb-range, .now-card #pb-vol, .now-card #pb-speed { -webkit-appearance: none; appearance: none; height: 22px; background: transparent; }
.now-card #pb-range::-webkit-slider-runnable-track, .now-card #pb-vol::-webkit-slider-runnable-track, .now-card #pb-speed::-webkit-slider-runnable-track { height: 4px; border-radius: 2px;
  background: linear-gradient(to right, var(--p) var(--pct, 0%), var(--now-track) var(--pct, 0%)); }
.now-card #pb-range::-webkit-slider-thumb, .now-card #pb-vol::-webkit-slider-thumb, .now-card #pb-speed::-webkit-slider-thumb { -webkit-appearance: none; appearance: none;
  width: 14px; height: 14px; margin-top: -5px; border: 0; border-radius: 50%; background: var(--p); }
.now-card #pb-range::-moz-range-track, .now-card #pb-vol::-moz-range-track, .now-card #pb-speed::-moz-range-track { height: 4px; border-radius: 2px; background: var(--now-track); }
.now-card #pb-range::-moz-range-progress, .now-card #pb-vol::-moz-range-progress, .now-card #pb-speed::-moz-range-progress { height: 4px; border-radius: 2px; background: var(--p); }
.now-card #pb-range::-moz-range-thumb, .now-card #pb-vol::-moz-range-thumb, .now-card #pb-speed::-moz-range-thumb { width: 14px; height: 14px; border: 0; border-radius: 50%; background: var(--p); }
.now-card #pb-range:disabled { opacity: .5; }
/* 音量条 (播放器音量, 见 RemotePlayerHandle.setVolume): 仿 iOS 播放卡, 两头一小一大两个喇叭, 左边那个点了静音 */
.pb-vol { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.pb-vol[hidden] { display: none; }
.pb-vol button, .pb-vol-hi { flex: none; background: none; padding: 4px; line-height: 0; color: var(--now-fg2); }
.pb-vol svg { width: 20px; height: 20px; fill: currentColor; }
.pb-vol input { flex: 1; min-width: 0; }
.pb-vol.muted input { opacity: .4; }
/* 加减样式 (设置里可选): 滑条一碰就能从 20% 滑到 100%, 这套一下只动一档, 手机上不容易把音量弄炸。
   手感与弹幕时间偏移那行一致 —— 加减按钮 / 数字轻点开输入 / 按住数字左右拖。 */
/* 上面那排播控是裸图标, 这排是实心 chip, 视觉重量差一截 —— 沿用滑条那 8px 会贴到播放键上; 下面的倍速滑条同理 */
.pb-vol-btns { gap: 10px; margin-top: 18px; }
.pb-vol-btns + .pb-speed { margin-top: 12px; }
.pb-vol-btns button[data-vol] { box-sizing: border-box; height: 40px; min-width: 46px; padding: 0 12px; line-height: 0;
  border-radius: 12px; background: var(--chip); color: var(--on-chip); touch-action: manipulation; }
.pb-vol-btns button[data-vol]:active { opacity: .55; }
/* pan-y = 纵向照旧滚页面, 横向归我们 */
.pb-vol-btns b[data-vol] { box-sizing: border-box; flex: 1; height: 40px; min-width: 4em; padding: 0 6px;
  display: inline-flex; align-items: center; justify-content: center; border-radius: 12px; border: 1px solid var(--line);
  cursor: ew-resize; color: var(--now-fg); font-size: 14px; font-weight: 600; font-variant-numeric: tabular-nums;
  touch-action: pan-y; user-select: none; -webkit-user-select: none; }
.pb-vol-btns b[data-vol].dragging { border-color: var(--p); color: var(--p); }
.pb-vol-btns b[data-vol]:active { opacity: .55; }
.pb-vol-btns .pb-vol-in { box-sizing: border-box; flex: 1; height: 40px; min-width: 4em; padding: 0 6px; text-align: center;
  border-radius: 12px; border: 1px solid var(--p); background: var(--card); color: var(--now-fg);
  font-size: 14px; font-weight: 600; font-variant-numeric: tabular-nums; }
.pb-vol-btns.muted b[data-vol] { opacity: .4; }
/* 倍速行: 与音量行同构 (左图标 / 中滑条 / 右读数), 右边读数定宽, 拖的时候不会把滑条挤得一跳一跳 */
.pb-speed { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.pb-speed[hidden] { display: none; }
.pb-speed svg { width: 20px; height: 20px; fill: currentColor; }
.pb-speed > button { flex: none; background: none; padding: 4px; line-height: 0; color: var(--now-fg2); }
.pb-speed input { flex: 1; min-width: 0; }
.pb-speed .pb-speed-val { flex: none; width: 44px; text-align: right; font-size: 12px; font-weight: 600;
  color: var(--now-fg2); font-variant-numeric: tabular-nums; }
.pb-speed.on .pb-speed-val, .pb-speed.on > button { color: var(--p); }
/* 跳片头的 85 秒: 跟在前进 10 秒后面, 同一个图标只是数字不同; 略小一圈并淡一档, 与前后退那两颗分出主次 */
.pb-ctrls .pb-skip { color: var(--now-fg2); }
.pb-ctrls .pb-skip svg { width: 28px; height: 28px; }
.pb-system { display: flex; justify-content: center; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.pb-system[hidden] { display: none; }
.pb-system button[hidden] { display: none; }
.pb-system button[disabled] { opacity: .45; }
.pb-system button { padding: 7px 12px; border-radius: 16px; background: var(--now-accent-pill); color: var(--p); font-size: 12px; font-weight: 600;
  -webkit-backdrop-filter: blur(10px); backdrop-filter: blur(10px); }
.pb-system button.on { background: var(--p); color: var(--on-p); }
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
/* 点头像展开的账号菜单 (退出登录): 按钮占满一行 */
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
/* 选资源页顶上的「搜索名与集数」卡 (见 CACHE_SCRIPT 的 ccNames): 与下面的数据源胶囊拉开, 别贴着 */
#cc-names .req { margin: 4px 0 12px; }
/* 主搜索名下面的候选行: 点一个就把它换上去 (见 wireNameSwap). 名字可能很长, 单颗最多占一行, 超出省略号 */
.name-swap { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 8px; }
.name-swap[hidden] { display: none; }
.name-swap-lead { font-size: 12px; color: var(--mute); }
.name-swap button { max-width: 100%; padding: 6px 12px; border-radius: 13px; background: var(--chip); color: var(--on-chip);
  font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.name-swap button:active { opacity: .55; }
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
 * 多语言 (见 RemoteI18n), 放在 <head> 最前面: `T('简体原文', 参数…)` 查当前语言的译文 (I18N 由服务端按 app 语言塞进来,
 * 没有 = 简体), `{0}` `{1}` … 依次换成参数. 静态 HTML (标签栏、面板标题、按钮的 aria-label 等) 由 SCRIPT 开头调一次
 * translateStatic, 逐个文本节点 / 属性按同一张表整段替换.
 */
private val LANG_SCRIPT = """
var I18N = window.I18N || {}, LANG = window.LANG || 'zh-CN';
function T(s) {
  var r = Object.prototype.hasOwnProperty.call(I18N, s) ? I18N[s] : s;
  for (var i = 1; i < arguments.length; i++) r = r.split('{' + (i - 1) + '}').join(arguments[i]);
  return r;
}
function translateStatic(root) {
  if (LANG === 'zh-CN') return;
  document.documentElement.lang = LANG;
  document.title = T(document.title);
  var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null), n, k;
  while ((n = w.nextNode())) {
    k = n.nodeValue.trim();
    if (k && Object.prototype.hasOwnProperty.call(I18N, k)) n.nodeValue = n.nodeValue.split(k).join(I18N[k]);
  }
  ['title', 'aria-label', 'placeholder'].forEach(function (a) {
    root.querySelectorAll('[' + a + ']').forEach(function (el) { el.setAttribute(a, T(el.getAttribute(a))); });
  });
}
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
  var OPTS = [['auto', T('自动')], ['light', T('浅色')], ['dark', T('深色')]];
  // 缓存面板「全选」/「全部用合集缓存」的范围 (同 CACHE_SCRIPT 的 pickAllSp; 电视上没有全选, 所以只在网页上设)
  var PICK_SCOPE_KEY = 'ani-cache-pick-scope', PICK_SCOPES = [['main', T('仅正片')], ['all', T('正片和特别篇')]];
  function pickScopeNow() { try { return localStorage.getItem(PICK_SCOPE_KEY) === 'all' ? 'all' : 'main'; } catch (e) { return 'main'; } }
  // 播放卡上的音量控件长什么样. 滑条在手机上一碰就从头滑到尾 (用户 2026-09-20: 容易误触把音量弄得很大),
  // 所以另给一档"加减"——同弹幕时间偏移那套: 按一下走一档, 按住数字左右拖微调, 点数字直接输数。
  var VOL_STYLE_KEY = 'ani-volume-style';
  var VOL_STYLES = [
    ['buttons', T('加减按钮（不易误触）')],
    ['slider', T('滑动条')],
    ['hidden', T('不显示')],
  ];
  function volStyleNow() {
    try {
      var v = localStorage.getItem(VOL_STYLE_KEY);
      return v === 'slider' || v === 'hidden' ? v : 'buttons';
    } catch (e) { return 'buttons'; }
  }
  window.volStyle = volStyleNow;
  // 锁屏 / 控制中心要不要自动接入 (见 CONTROL_SCRIPT). 接入必须独占手机的音频焦点 —— 系统只把真正在出声的页面
  // 放进 Now Playing, 想混音的 ambient 类别又进不去 —— 所以正在放的音乐一定会被打断. 默认手动: 想边听歌边看的人
  // 不会被抢走声音, 需要时播放页上有按钮.
  var MEDIA_AUTO_KEY = 'ani-media-session-auto';
  var MEDIA_MODES = [
    ['manual', T('需要时手动接入')],
    ['auto', T('自动接入')],
  ];
  function mediaAutoNow() {
    try {
      var v = localStorage.getItem(MEDIA_AUTO_KEY);
      return v === 'auto' ? v : 'manual';
    } catch (e) { return 'manual'; }
  }
  function paint() {
    var t = window.remoteTheme.get();
    box.innerHTML = '<div class="card set-card"><div class="set-title">' + T('本机偏好') +
      // 版本号跟着标题走: 手机上这份脚本是不是新的, 对一眼就知道 (装了新包不等于手机上换了脚本)
      '<small>' + T('只影响这台手机') + (window.pageVersion ? ' · v' + window.pageVersion : '') + '</small></div>' +
      '<div class="seg">' + OPTS.map(function (o) {
        return '<button type="button" data-look="' + o[0] + '"' + (o[0] === t ? ' class="on"' : '') + '>' + o[1] + '</button>';
      }).join('') + '</div><p class="hint">' + T('自动：跟着手机系统的深色模式切换。') + '</p>' +
      // 同顶上「电视上的二维码弹窗还开着」那一条里的「以后」下拉框 (见 SCRIPT 的 ldSetMode)
      (window.LD_OPTS ? '<label class="look-ld"><span>' + T('打开网页时，电视上的二维码') + '</span><select data-ld-mode>' +
        window.LD_OPTS.map(function (o) {
          return '<option value="' + o[0] + '"' + (o[0] === window.ldMode() ? ' selected' : '') + '>' + o[1] + '</option>';
        }).join('') + '</select></label>' : '') +
      '<label class="look-ld"><span>' + T('「全选」包含哪些剧集') + '</span><select data-pick-scope>' +
      PICK_SCOPES.map(function (o) {
        return '<option value="' + o[0] + '"' + (o[0] === pickScopeNow() ? ' selected' : '') + '>' + o[1] + '</option>';
      }).join('') + '</select></label>' +
      '<label class="look-ld"><span>' + T('播放卡的音量控件') + '</span><select data-vol-style>' +
      VOL_STYLES.map(function (o) {
        return '<option value="' + o[0] + '"' + (o[0] === volStyleNow() ? ' selected' : '') + '>' + o[1] + '</option>';
      }).join('') + '</select></label>' +
      (window.mediaSessionSupported ? '<label class="look-ld"><span>' + T('锁屏 / 控制中心') + '</span><select data-media-auto>' +
        MEDIA_MODES.map(function (o) {
          return '<option value="' + o[0] + '"' + (o[0] === mediaAutoNow() ? ' selected' : '') + '>' + o[1] + '</option>';
        }).join('') + '</select></label><p class="hint">' + T('接入会占用手机的音频焦点，正在放的音乐会被暂停。') + '</p>' : '') +
      '</div>';
  }
  box.addEventListener('change', function (e) {
    if (e.target.hasAttribute('data-vol-style')) {
      try { localStorage.setItem(VOL_STYLE_KEY, e.target.value); } catch (x) {}
      if (window.repaintPlayerCard) window.repaintPlayerCard();
    }
    if (e.target.hasAttribute('data-ld-mode') && window.ldSetMode) window.ldSetMode(e.target.value);
    if (e.target.hasAttribute('data-pick-scope')) {
      try { if (e.target.value === 'all') localStorage.setItem(PICK_SCOPE_KEY, 'all'); else localStorage.removeItem(PICK_SCOPE_KEY); } catch (x) {}
      if (window.refreshCachePick) window.refreshCachePick();
    }
    if (e.target.hasAttribute('data-media-auto')) {
      try {
        if (e.target.value === 'manual') localStorage.removeItem(MEDIA_AUTO_KEY);
        else localStorage.setItem(MEDIA_AUTO_KEY, e.target.value);
      } catch (x) {}
      if (window.refreshMediaSessionMode) window.refreshMediaSessionMode();
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
    box.innerHTML = '<div class="card set-card"><div class="set-title">' + T('日志') + '</div>' +
      '<p class="hint">' + T('遇到问题时下载下来发给开发者。app.log 是今天的，其余按天保存。') + '</p>' +
      (d.items.length ? '<div class="log-list">' + d.items.map(function (x) {
        return '<a class="log-item" href="api/logs/' + encodeURIComponent(x.name) + '" download="' + esc(x.name) + '">' +
          '<span class="log-name">' + esc(x.name) + '</span><span class="log-meta">' + esc(x.size) + ' · ' + esc(x.time) + '</span></a>';
      }).join('') + '</div>' : '<p class="hint">' + T('还没有日志文件') + '</p>') +
      '<p class="hint">' + T('点了没开始下载的话，换系统浏览器打开本页再试。') + '</p></div>';
  }
})();
""".trimIndent()

private val SCRIPT = """
(function () {
  // 静态 HTML 按当前语言翻一遍 (见 LANG_SCRIPT)
  translateStatic(document.body);
  // sub: 搜索标签里当前是「搜索」(form) 还是「结果」(results) 那一页; setSub: 设置标签里是「常规」(general) 还是「数据源」(sources)
  var cur = null, sub = 'form', setSub = 'general', ver = '', busy = false;
  // CONTROL_SCRIPT 在支持 Media Session 的浏览器里显式启用后改成 true. 这时即使页面进后台也继续拉轻量播放状态,
  // 否则 iPhone 锁屏 / 控制中心里的进度和播放键会停在旧状态. 浏览器仍可能自行节流, 但每次系统按钮操作还会主动补拉.
  window.mediaSessionActive = false;
  // 数据源胶囊的筛选 (null = 全部) 与最近一次完整状态: 点胶囊时就地重画, 不等下一次轮询
  var srcFilter = null, lastState = null;
  // login: 电视刚登录上 (账号卡片发现的), 评论与评分区据此重新读一次
  var hooks = { render: [], unavailable: [], playback: [], login: [] };
  /**
   * 逐个跑钩子, 一个抛异常不影响其他的。
   *
   * 原先是 `list.forEach(function (h) { h(arg); })` —— 排在前面的钩子一抛, 后面的全不执行。
   * 而画播放控件的 paint() 恰恰排在较后面, 于是任意一个无关的钩子 (系统媒体控件 /
   * 弹幕 / 统计) 出事, 进度条和播放键就整个不出现。
   * 2026-09-20 用户报「来回切番剧之后控件不加载」。
   *
   * 异常另外发回电视: 手机上的控制台看不到, 这是这类问题唯一的现场 (见 RemoteClientLog)。
   * clientLog 自带 10 秒去重, 每秒轮询一直抛也不会刷爆日志。
   */
  function runHooks(name, list, arg) {
    for (var i = 0; i < list.length; i++) {
      try {
        list[i](arg);
      } catch (e) {
        console.error(name + ' hook #' + i + ' failed', e);
        if (window.clientLog) window.clientLog(name + ' hook #' + i + ' failed: ' + (e && e.message || e));
      }
    }
  }
  window.runHooks = runHooks;
  window.remoteHooks = hooks;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  window.esc = esc;
  /*
   * 「搜索名」表单里主名与次要名的互换 (播放器的编辑查询请求 / 缓存面板的搜索名, 两份表单同一套).
   *
   * 次要名本来只能靠手改两个框来顶替主名 (剪一个、粘一个, 手机上尤其烦)。这里在主名输入框下面列出当前的
   * 次要名, 点一下就顶上来, 原来的主名同时降回次要名那一栏 —— 一次点击完成对调, 谁都不会丢。
   * 候选实时取自那个 textarea, 所以"每行一个"的增删照旧, 加完立刻就能点。
   */
  window.wireNameSwap = function (form) {
    if (!form) return;
    var primary = form.elements.primary, others = form.elements.others;
    if (!primary || !others) return;
    if (form.repaintNameSwap) { form.repaintNameSwap(); return; }
    var row = document.createElement('div');
    row.className = 'name-swap';
    primary.parentNode.insertBefore(row, primary.nextSibling);
    function lines() {
      return others.value.split('\n').map(function (x) { return x.trim(); }).filter(function (x) { return x; });
    }
    function paint() {
      var list = lines();
      row.hidden = !list.length;
      row.innerHTML = list.length
        ? '<span class="name-swap-lead">' + T('换成') + '</span>' + list.map(function (name, i) {
          return '<button type="button" data-swap="' + i + '" title="' + esc(name) + '">' + esc(name) + '</button>';
        }).join('')
        : '';
    }
    row.addEventListener('click', function (e) {
      var b = e.target.closest('[data-swap]');
      if (!b) return;
      var list = lines(), i = +b.getAttribute('data-swap');
      if (list[i] == null) return;
      var was = primary.value.trim();
      primary.value = list[i];
      // 原主名降回次要名那一栏; 原来就是空的就不留空行
      list[i] = was;
      others.value = list.filter(function (x) { return x; }).join('\n');
      paint();
      // 两份表单都靠 input 事件判断"用户动过了"(动过就不再跟着轮询重画), 这一下是程序改的, 补一个
      others.dispatchEvent(new Event('input', { bubbles: true }));
    });
    others.addEventListener('input', paint);
    form.repaintNameSwap = paint;
    paint();
  };
  // 播放卡底部「缓存这部番的剧集…」: 点了打开缓存面板 (见 CACHE_SCRIPT)
  // 播放卡标题行右边的「缓存」小按钮, 点开这部番的缓存面板 (类名 cache-entry 留给测试脚本认)
  function cacheEntry(id, title) {
    return id ? '<button type="button" class="now-cache cache-entry" data-cache="' + id + '" data-title="' + esc(title || '') +
      '" aria-label="' + T('缓存这部番的剧集') + '">' + window.ICONS.download + T('缓存') + '</button>' : '';
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
    toast(T('无法连接电视。请确认电视已唤醒、Izuko 正在运行，并且手机和电视连接到同一网络。') +
      (lastKeep === false ? T('想在电视休眠或离开 Izuko 后继续连接，请在网页的「设置」中开启「后台保持连接」。') : ''), 6000);
  }
  window.fail = fail;
  function failRead() { toast(T('读取失败，请确认手机与电视在同一网络')); }
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
  // lastBgm: 上次看到的 Bangumi 线路 (见 pollNotice)
  var noticeSeq = null, noticeFails = 0, noticeBusy = false, noticeSkip = 0, lastKeep = null, lastBgm = null;
  var tvState = document.getElementById('tv-state');
  // btn: 「不在前台」那一条里的入口 (切到电视前台, 见 TvRemoteControl.manualFront; 设置里有同一个开关, 可以提前开或撤销):
  // 'enable' = 还没开, 点了先确认再开 / 'how' = 开了还没授权, 点了说怎么授权 / 'go' = 开了且授了权, 点了直接切.
  // 内容没变不重画: 每 2 秒一轮, 重画会把正要点的按钮换掉
  var FRONT_HOW = T('在电视上完成授权：打开「设置 → 应用 → 特殊应用权限 → 显示在其他应用的上层」，然后为 Izuko TV 开启权限。只需授权一次，仅用于从手机打开 Izuko TV。');
  function setTvState(kind, text, btn) {
    var key = (kind || '') + '|' + (text || '') + '|' + (btn || '');
    if (tvState._k === key) return;
    tvState._k = key;
    tvState.hidden = !kind;
    tvState.className = 'tv-state' + (kind ? ' ' + kind : '');
    tvState.textContent = text || '';
    if (btn) tvState.insertAdjacentHTML('beforeend', '<button type="button" class="tv-front" data-tv-front="' + btn + '">' +
      (btn === 'how' ? T('查看授权方法') : T('打开 Izuko')) + '</button>');
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
    if (!confirm(T('允许从手机打开电视上的 Izuko？开启后，在手机上搜索或点播时，电视会自动打开 Izuko。') +
      T('首次使用需要在电视上授权，可随时在设置中关闭。'))) return;
    b.disabled = true;
    post('api/settings/front', { on: '1' }).then(function (r) {
      tvState._k = null; // 下一轮按新状态重画这一条
      if (r.granted) frontNow(b);
      // 电视回的话里说了下一步 (Ani 在后台: 回到 Ani 时会直接打开授权页, 30 分钟内有效)
      else { b.disabled = false; alert(r.message || (T('已打开。还差一步：') + FRONT_HOW)); }
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
        // 有没下完的缓存时补一句: BT 服务只在 Ani 前台时才起, 这会儿下载也是停着的 (见 TvRemoteControl.noticeState)
        var cacheHalted = n.cachePending ? T('缓存也要等电视上打开 Izuko 才会继续。') : '';
        if (n.away && n.frontOn && n.frontGranted) setTvState('away', T('电视当前没有显示 Izuko。搜索或点播时会自动打开 Izuko。') + cacheHalted, 'go');
        else if (n.away && n.frontOn) setTvState('away', T('电视当前没有显示 Izuko。完成一次授权后，就可以从手机打开 Izuko。') + cacheHalted, 'how');
        else if (n.away) setTvState('away', T('电视当前没有显示 Izuko。搜索和点播仍会发送到电视，打开 Izuko 后即可看到。') + cacheHalted, 'enable');
        else setTvState('');
        ldShow(!!n.launchDialog);
        lastKeep = !!n.keep;
        // app 里换了语言 (见 RemoteI18n): 整页重载拿新的译文
        if (n.lang && n.lang !== LANG) location.reload();
        // Bangumi 的线路变了 (电视上改了连接方式、自动改成用镜像、或刚在网页上改的): 账号卡片按新状态重读
        if (n.bgm !== lastBgm) {
          if (lastBgm !== null && window.loadAccount) window.loadAccount();
          lastBgm = n.bgm;
        }
      })
      .catch(function (e) {
        noticeBusy = false;
        if (e && e.message === 'gone') { setTvState('off', T('这个地址已失效（电视上重置过地址），请在电视上重新扫码')); return; }
        if (++noticeFails >= 2) setTvState('off', T('电视已断开。请确认电视已唤醒、Izuko 正在运行，并且手机和电视连接到同一网络。') +
          (lastKeep === false ? T('想在电视休眠或离开 Izuko 后继续连接，请先在电视上打开 Izuko，再到网页的「设置」中开启「后台保持连接」。') : ''));
        noticeSkip = Math.min(noticeFails - 1, 4);
      });
  }
  setInterval(pollNotice, 2000);
  // 电视上启动时的二维码弹窗 (见 TvRemoteControl.closeLaunchDialog). 这台手机上怎么处理 (记在手机上, 这一条里和设置 → 外观里
  // 是同一个下拉框, 随时能改回来): 每次提示 (默认) = 顶上一条「还开着 [关掉电视上的弹窗] ×」, 点了才关, × 只收起这一条 (本次);
  // 自动关掉 = 打开网页直接关; 不提示也不关 = 什么都不出现. 遥控器关掉后下一轮提示轮询就收起.
  // 关掉之后提示一句以后去哪找码 —— 弹窗里写着的那句随它关掉就看不到了
  var LD_KEY = 'ani-launch-dialog-mode', LD_OLD_KEY = 'ani-launch-dialog-auto';
  var LD_OPTS = [['ask', T('每次询问')], ['auto', T('自动关闭')], ['off', T('保持显示')]];
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
      if (r && r.closed) toast(auto ? T('已自动关闭二维码。需要时长按遥控器播放键，右上角可重新扫码。')
        : T('已关闭二维码。需要时长按遥控器播放键，右上角可重新扫码。'), 5000);
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
    else if (m === 'off') toast(T('以后不再提醒。请用遥控器关闭电视上的二维码；可在「设置 → 本机偏好」中重新开启提醒。'), 5000);
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
    // 数据源页的工具行也是 sticky 的, 要让开上面这一行, 否则两个叠在一起
    var seg = document.getElementById('set-seg');
    document.documentElement.style.setProperty('--seg-h', (seg.offsetHeight + 4) + 'px');
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
  // 季度从属年份, 跟电视筛选弹窗一致: 没选年份时整节藏起来 (首次渲染时服务端已按当前值定过一次)
  function syncSeasonSection() {
    var sec = document.getElementById('season-section');
    if (!sec || !searchForm) return;
    var picked = searchForm.querySelector('input[name=year]:checked');
    var on = !!(picked && picked.value);
    sec.hidden = !on;
    if (!on) {
      var none = searchForm.querySelector('input[name=season][value=""]');
      if (none) none.checked = true;
    }
  }
  // 年份从 1943 起, 默认只摊开最近十几年 (服务端渲染时就分好了), 更早的折在「更多年份」后面.
  // 收起会藏掉已选的老年份, 所以选着折起来的年份时按钮自己隐藏, 只剩「改选别的年份」这一条路.
  var yearMore = document.getElementById('year-more');
  var yearRest = document.getElementById('year-rest');
  function syncYearMore() {
    if (!yearMore || !yearRest || !searchForm) return;
    var picked = searchForm.querySelector('input[name=year]:checked');
    var pickedIsHidden = !!(picked && picked.value && yearRest.contains(picked));
    yearMore.hidden = !yearRest.hidden && pickedIsHidden;
    yearMore.textContent = yearRest.hidden ? T('更多年份') : T('收起');
  }
  if (yearMore && yearRest) {
    yearMore.addEventListener('click', function () {
      yearRest.hidden = !yearRest.hidden;
      syncYearMore();
    });
  }
  if (searchForm) {
    searchForm.addEventListener('change', function (e) {
      if (e.target && e.target.name === 'year') { syncSeasonSection(); syncYearMore(); }
    });
  }
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
        '<button type="button" class="x" data-del="' + esc(h) + '" aria-label="' + T('删除这条记录') + '" title="' + T('删除这条记录') + '">' + window.ICONS.close + '</button></div>';
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
        // 同播放器轮询: 渲染成功了才能推进版本号, 否则一次异常就把列表永久卡在旧内容上
        if (!s.same) { renderResults(s); resVer = s.v; }
        if (resAgain) { resAgain = false; pollResults(true); }
      })
      .catch(function (e) {
        resBusy = false;
        console.error(e);
        if (window.clientLog) window.clientLog('search poll/render failed: ' + (e && e.message || e));
      });
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
  // 圈里的秒数 (同 Material 的 replay_10 / forward_10, 只是数字可换)
  function skipDigits(n) { return '<text x="12" y="15.4" text-anchor="middle" font-size="6.2" font-weight="700">' + n + '</text>'; }
  var TEN = skipDigits('10');
  window.ICONS = {
    play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.14v13.72a1 1 0 0 0 1.52.85l10.6-6.86a1 1 0 0 0 0-1.7L9.52 4.29A1 1 0 0 0 8 5.14z"/></svg>',
    pause: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 5h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zm7 0h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"/></svg>',
    back10: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="' + REPLAY + '"/>' + TEN + '</svg>',
    fwd10: '<svg viewBox="0 0 24 24" aria-hidden="true"><path transform="matrix(-1 0 0 1 24 0)" d="' + REPLAY + '"/>' + TEN + '</svg>',
    // 跳片头用的 85 秒: 跟前进 10 秒同一个圈, 只换圈里的数字
    fwd85: '<svg viewBox="0 0 24 24" aria-hidden="true"><path transform="matrix(-1 0 0 1 24 0)" d="' + REPLAY + '"/>' + skipDigits('85') + '</svg>',
    // 音量条两头: 小喇叭 (点了静音) / 静音喇叭 / 大喇叭 (装饰)
    // 加减样式的音量按钮 (见 volRowHtml)
    minus: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 11h14a1 1 0 0 1 0 2H5a1 1 0 0 1 0-2z"/></svg>',
    plus: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11 5a1 1 0 0 1 2 0v6h6a1 1 0 0 1 0 2h-6v6a1 1 0 0 1-2 0v-6H5a1 1 0 0 1 0-2h6V5z"/></svg>',
    volLow: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 9v6h4l5 5V4l-5 5H7z"/></svg>',
    volOff: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>',
    volUp: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>',
    // 测试连接: 插头
    plug: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16 7V3h-2v4h-4V3H8v4H7a1 1 0 0 0-1 1v4a6 6 0 0 0 5 5.91V22h2v-4.09A6 6 0 0 0 18 12V8a1 1 0 0 0-1-1h-1z"/></svg>',
    // 倍速: 两个并排的播放三角 (同电视播放器里的倍速标记)
    speed: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 18l8.5-6L4 6v12zm9.5-12v12l8.5-6-8.5-6z"/></svg>',
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
    star: svgIcon('M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z'),
    // 打开链接 (播放页候选行左滑): 方框右上角伸出箭头
    openLink: svgIcon('M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z')
  };
  function svgIcon(d) { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="' + d + '"/></svg>'; }
  // 底图 (以及带 alt-src 的图, 如账号头像) 拉不到: 换下一个候选 (地址里没有空格, 用空格分隔); 全都拉不到就把这一层藏起来,
  // 露出卡片本来的底色
  document.addEventListener('error', function (e) {
    var img = e.target;
    if (!img || img.tagName !== 'IMG' ||
        !(img.classList.contains('cv-art') || img.classList.contains('cv-bg') || img.classList.contains('alt-src'))) return;
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
  /**
   * 进入多选. o = { box: 列表容器, ask(n): 确认删除的话, del(ids): Promise<是否删成>,
   * setPaused(ids, paused): 可选, 有就多出「暂停 / 继续」两个键 (缓存标签) }; first = 长按的那一行, 先勾上
   */
  function selStart(o, first) {
    selEnd();
    sel = { o: o, ids: {} };
    if (first) sel.ids[first] = true;
    o.box.classList.add('selecting');
    selBar.querySelector('.sel-del').innerHTML = window.ICONS.trash + T('删除');
    // 暂停 / 继续只放图标: 底栏在手机上排不下第五、六个带字的键
    [].forEach.call(selBar.querySelectorAll('.sel-pause'), function (x) {
      x.hidden = !o.setPaused;
      var isPause = x.getAttribute('data-sel') === 'pause', label = isPause ? T('暂停') : T('继续');
      x.innerHTML = isPause ? window.ICONS.pause : window.ICONS.play;
      x.setAttribute('aria-label', label);
      x.setAttribute('title', label);
    });
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
    selBar.querySelector('.sel-n').textContent = n ? T('已选 {0} 项', n)
      : (sel.o.setPaused ? T('点选要操作的项') : T('点选要删除的项'));
    selBar.querySelector('[data-sel="all"]').textContent = n === rows.length ? T('全不选') : T('全选');
    selBar.querySelector('[data-sel="del"]').disabled = !n;
    [].forEach.call(selBar.querySelectorAll('.sel-pause'), function (x) { x.disabled = !n; });
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
    // 暂停 / 继续: 可逆, 不用确认; 做完退出多选 (状态随列表下一次刷新变过来)
    if (k === 'pause' || k === 'resume') {
      var pids = Object.keys(sel.ids);
      if (!pids.length || !o.setPaused) return;
      b.disabled = true;
      o.setPaused(pids, k === 'pause').then(function (ok) {
        if (ok) selEnd(); else b.disabled = false;
      }, function () { b.disabled = false; fail(); });
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
    if (s.pending) { resEmpty(T('正在电视上搜索…')); return; }
    if (!s.available) {
      resEmpty(T('还没有搜索结果'), T('在「搜索」里搜一下，电视上的结果会列在这里：点条目在电视上打开详情页，点 ▶ 直接播放'));
      return;
    }
    var items = s.items || [];
    // 关键词一行, 条数右对齐在同一行 (标题折行时条数贴着第一行); 筛选条件有才另起一行
    resHead.innerHTML = '<div class="res-top"><div class="res-q">' + (s.keywords ? T('「{0}」', esc(s.keywords)) : T('筛选结果')) + '</div>' +
      '<span class="res-n">' + (s.refreshing ? T('搜索中') : T('{0} 条', items.length)) + '</span></div>' +
      (s.filters ? '<div class="res-sub">' + esc(s.filters) + '</div>' : '');
    if (!items.length) {
      resList.innerHTML = '<div class="empty"><p>' + (s.refreshing ? T('正在电视上搜索…')
        : s.error ? T('搜索失败：') + esc(s.error) : T('没有找到相关条目')) + '</p></div>';
    } else {
      patchList(resList, items.map(function (x) {
        // 点主体 = 电视打开详情页; 点右边的封面 (没封面的是 ▶) = 直接播放.
        // 右滑露出「缓存」(打开这部番的缓存面板), 左滑露出「收藏」(设收藏状态)
        return swRow(
          '<button type="button" class="sw-btn cache" data-cache="' + x.id + '" data-title="' + esc(x.title) + '">' +
            window.ICONS.download + T('缓存') + '</button>',
          '<button type="button" class="sw-btn coll" data-coll="' + x.id + '">' + window.ICONS.star + T('收藏') + '</button>',
          '<div class="item res-item' + (x.cover ? ' cv' : '') + (x.blur ? ' nsfw-blur' : '') + '" data-sid="' + x.id + '">' +
          (x.cover ? coverLayers(x.cover) : '') +
          '<span class="t">' + esc(x.title) + (x.nsfw ? '<span class="res-r18">R18</span>' : '') + '</span>' +
          (x.info ? '<span class="m">' + esc(x.info) + '</span>' : '') +
          (x.rating ? '<span class="res-rate">' + esc(x.rating) + '</span>' : '') +
          '<button type="button" class="res-play" aria-label="' + T('播放') + '"><span class="play-glyph">' + window.ICONS.play + '</span></button></div>');
      }));
    }
    var foot = '';
    if (items.length) {
      if (s.appending) foot = '<p class="hint res-end">' + T('正在加载…') + '</p>';
      else if (s.error) foot = '<button type="button" class="ghost wide" data-more="1">' + T('加载失败，点这里重试') + '</button>';
      else if (s.end) foot = '<p class="hint res-end">' + T('没有更多了') + '</p>';
      else if (s.live) foot = '<button type="button" class="ghost wide" data-more="1">' + T('加载更多') + '</button>';
      else foot = '<p class="hint res-end">' + T('电视已离开搜索页') + '</p>' +
        '<button type="button" class="ghost wide ic" data-resume="1">' + window.ICONS.tv + T('让电视回到搜索页，继续加载') + '</button>';
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
  // 菜单关掉时那一行收回去. 没有「取消收藏」: Bangumi 没有这个操作, 不想看了就选「抛弃」
  var COLL_TYPES = [['WISH', T('想看')], ['DOING', T('在看')], ['DONE', T('看过')], ['ON_HOLD', T('搁置')], ['DROPPED', T('抛弃')]];
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
      if (!d.ok) { toast(d.message || T('读取收藏状态失败')); swClose(b.closest('.sw')); return; }
      var m = document.createElement('div');
      m.className = 'ep-menu coll-menu';
      m.id = 'coll-menu';
      m.setAttribute('role', 'listbox');
      m.innerHTML = COLL_TYPES.map(function (c) {
        var cur = c[0] === d.collection;
        // 属性名别用 data-ctype: 「评论与评分」那排收藏按钮用的就是它
        return '<button type="button" role="option" class="ep-opt' + (cur ? ' cur' : '') + '" data-colltype="' + c[0] + '">' +
          '<span class="ep-mark">' + (cur ? '✓' : '') + '</span><span class="ep-name">' + c[1] + '</span></button>';
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
    if (!o) return;
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
  }, true);
  // 按到菜单外面就关. 按下就关, 不等 click: iOS 上点没有点击处理的地方 (面板空白处、标题) 不发 click, 菜单会一直关不掉.
  // 这里只摘菜单, 滑开的那一行交给滑动那套收起 (按在别的行上 = 只收起、不当作点击, 同没开菜单时)
  document.addEventListener('pointerdown', function (e) {
    var t = e.target;
    if (collMenu && !(t.closest && (t.closest('#coll-menu') || t.closest('[data-coll]')))) {
      collMenu.remove();
      collMenu = null;
    }
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
    // 系统媒体控件启用后不受页面是否可见 / 当前标签 / 覆盖面板限制: 锁屏时本页必然 hidden, 用户也可能开着面板锁屏.
    if (!window.mediaSessionActive && (document.hidden || cur !== 'player' || window.sheets.any())) return;
    // 定时器撞上在途请求直接跳过, 只有 force 才记一笔补拉 (同 pollResults: 以前补的是全量, 慢响应时每轮全量)
    if (busy) { if (force) again = true; return; }
    busy = true;
    fetchT('api/player?v=' + (force ? '' : encodeURIComponent(ver)) + query())
      .then(function (r) { return r.json(); })
      .then(function (s) {
        busy = false;
        if (!s.same) {
          // **先渲染成功再记版本号**: 反过来的话 render 一抛异常, 版本号却已经推进了 ——
          // 下一轮轮询带着它去问, 服务端只回 same:true, 完整状态再也不会下发,
          // 控件就永远停在渲染失败那一刻。
          // 2026-09-20 用户报「来回切番剧后控件不加载, 按一下播放键或者进设置再回来就好了」:
          // 那两条路恰恰都走 poll(true) (版本号置空强制全量), 绕过了这个死锁。
          render(s);
          ver = s.v;
        }
        if (s.playback) window.runHooks('playback', hooks.playback, s.playback);
        if (again) { again = false; poll(true); }
      })
      // 打出来: 渲染里的异常也落在这个 catch 里, 不打的话界面画一半就停、控制台一行字都没有
      .catch(function (e) {
        busy = false;
        console.error(e);
        // 手机上的控制台看不到, 而这是"控件不加载"这类问题唯一的现场 (见 RemoteClientLog).
        // clientLog 在另一个 IIFE 里, 走 window; 它自带 10 秒去重, 连续失败也不会刷爆日志。
        if (window.clientLog) window.clientLog('player poll/render failed: ' + (e && e.message || e));
      });
  }
  window.poll = poll;
  // 接入系统控件后页面隐藏 (多半是锁屏) 仍要拉状态, 但锁屏上的进度不需要 1.5 秒的精度, 而每拉一次就是
  // 一个打到电视的请求 —— 降到 6 秒; 系统控件上的每次操作都另有 poll(true) 补拉, 不影响手感.
  var pollSkips = 0;
  setInterval(function () {
    if (window.mediaSessionActive && document.hidden) {
      if (++pollSkips % 4 !== 0) return;
    } else pollSkips = 0;
    flushList();
    poll(false);
  }, 1500);
  /** 候选列表因为有行滑开而跳过的那次重画 (见 render), 行收起后用最近一份状态补上. */
  var listStale = false;
  function flushList() {
    var src = document.getElementById('player-sources');
    if (!listStale || !lastState || window.swBusy(src)) return;
    listStale = false;
    src.innerHTML = renderList(lastState);
  }
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
    return '<button type="button" class="now-ep now-ep-pick" id="ep-pick" aria-haspopup="listbox" aria-label="' + T('选集') + '"><span class="now-ep-t">' +
      (seen && text ? '<span class="now-ep-seen" title="' + T('看过') + '">✓</span>' : '') +
      esc(text || (cur ? cur.label.replace(/^✓\s*/, '') : T('选集'))) + '</span>' +
      '<span class="now-ep-caret" aria-hidden="true"></span></button>';
  }

  function render(s) {
    var now = document.getElementById('player-now');
    var chips = document.getElementById('player-chips');
    var src = document.getElementById('player-sources');
    if (!s.available) {
      closeEpMenu();
      var msg = s.reason === 'background'
        ? T('电视当前不在播放页') + (s.title ? T('：') + esc(s.title) : '')
        : T('电视上没有正在播放的内容');
      if (s.upNext) {
        // 什么都没在播: 同动作面板那张「接下来播放」卡, 点一下电视直接进播放页
        var u = s.upNext;
        // 复用播放时那张卡的结构与样式: 剧名 / 副标题 / 进度 / 按钮排, 只是按钮只有一颗
        paintNow(now, '<div class="card now-card"><div class="now-head"><div class="now-title now-link" data-subject="' + u.subjectId +
          '" data-title="' + esc(u.title) + '">' + esc(u.title) + '</div>' + cacheEntry(u.subjectId, u.title) + '</div>' +
          '<div class="now-src">' + (u.continuing ? T('继续播放') : T('接下来播放')) + (u.episode ? T('：') + esc(u.episode) : '') + '</div>' +
          (u.continuing && u.duration
            ? '<div class="progress"><div class="track"><div style="width:' + Math.min(100, u.position * 100 / u.duration) +
              '%"></div></div><div class="time">' + mmss(u.position) + ' / ' + mmss(u.duration) + '</div></div>'
            : '') +
          '<div class="ctrls"><button class="main ic" id="play-upnext">' + window.ICONS.play + T('在电视上播放') + '</button></div></div>', u.art);
      } else {
        paintNow(now, '<div class="empty"><p>' + msg + '</p>' + sessionChip(s.session) +
          (s.reason === 'background' ? '<button class="primary ic" id="open-player">' + window.ICONS.tv + T('在电视上打开播放器') + '</button>' : '') + '</div>', null);
      }
      chips.innerHTML = '';
      document.getElementById('player-filters').innerHTML = '';
      lastFiltersHtml = '';
      renderRefetch(false);
      listStale = false;
      src.innerHTML = '';
      window.runHooks('unavailable', hooks.unavailable, s);
      return;
    }
    // 正在播哪个数据源放在剧名下、播放键上方: 候选列表里的「正在播放」角标要往下翻很远才看得到
    paintNow(now, '<div class="card now-card"><div class="now-head"><div class="now-title now-link" data-subject="' + s.subjectId +
      '" data-title="' + esc(s.title) + '">' + esc(s.title) + '</div>' + cacheEntry(s.subjectId, s.title) + '</div>' +
      // 第几集单独一行 (资源名常常看不出来: BT / 缓存的整季合集就叫「[01-12 合集]」), 这一行本身就是选集下拉框, 见 epLine
      epLine(s) +
      '<div class="now-pick"><span class="now-label' + (s.background ? '' : ' live') + '">' + (s.background ? T('当前数据源') : T('正在播放')) + '</span>' +
      (s.selectedSource
        ? '<span class="now-srcname">' + esc(s.selectedSource) + '</span>' +
          (s.selectedMeta ? '<span class="now-meta">' + esc(s.selectedMeta) + '</span>' : '')
        : '<span class="now-meta">' + T('尚未选择数据源') + '</span>') + '</div>' +
      (s.selectedTitle ? '<div class="now-src" title="' + esc(s.selectedTitle) + '">' + esc(s.selectedTitle) + '</div>' : '') +
      (s.background
        ? sessionChip(s.session) + '<p class="hint">' + T('电视未在播放页：可以照常换源和修改查询条件，新数据源会在后台加载，回到播放器即可继续播放。') + '</p>' +
          '<button class="primary wide ic" id="open-player">' + window.ICONS.tv + T('在电视上打开播放器') + '</button>'
        : '<div id="player-controls"></div>') +
      '</div>', s.art);
    lastState = s;
    chips.innerHTML = renderChips(s);
    renderFilters(s);
    renderRefetch(true, s.sources.some(function (x) { return x.state === 'paused'; }));
    // 有行正滑开 / 正在拖时先不重画 (重画会把它弹回去), 记一笔由 flushList 在收起后补画 ——
    // 状态没变时服务端只回 same, 等不来下一次 render
    if (window.swBusy(src)) listStale = true;
    else {
      listStale = false;
      src.innerHTML = renderList(s);
      window.swPeek(src, 'player-sources', '.sw > .item');
    }
    window.runHooks('render', hooks.render, s);
  }

  function renderChips(s) {
    // 选中的源已经不在了 (比如改了查询条件后被禁用), 筛选自动回到「全部」
    if (srcFilter && !s.sources.some(function (x) { return x.id === srcFilter; }) &&
        !s.groups.some(function (g) { return g.id === srcFilter; })) srcFilter = null;
    var h = '<div class="chips"><button class="chip' + (srcFilter ? '' : ' on') + '" data-src="">' + T('全部') + '</button>';
    s.sources.forEach(function (x) {
      var n = x.state === 'loading' ? '…' : x.state === 'captcha' ? T('需验证') : x.state === 'failed' ? T('失败') : x.state === 'limited' ? T('限流') : x.count;
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
  // 选一集 (捕获阶段: 先于页面上别的点击处理)
  document.addEventListener('click', function (e) {
    if (!epMenu) return;
    var o = e.target.closest('.ep-opt');
    if (!o || !epMenu.contains(o)) return;
    closeEpMenu();
    if (o.classList.contains('cur')) return;
    post('api/player/episode', { id: o.getAttribute('data-ep') })
      .then(function (r) { toast(r.message); poll(true); })
      .catch(fail);
  }, true);
  // 按到列表外面就关: 按下就关, 不等 click (iOS 上点没有点击处理的地方不发 click, 同收藏菜单)
  document.addEventListener('pointerdown', function (e) {
    var t = e.target;
    if (epMenu && !(t.closest && (t.closest('#ep-menu') || t.closest('#ep-pick')))) closeEpMenu();
  }, true);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeEpMenu(); });
  window.addEventListener('resize', closeEpMenu);
  document.querySelector('.tabbar').addEventListener('click', closeEpMenu);

  // 下拉框只在内容真的变了时才重画: 每秒一次的轮询若无条件重画, 手机上正打开的选择器会被关掉
  var lastFiltersHtml = '';
  function dropdown(key, label, list, cur) {
    var h = '<label class="sel"><span>' + label + '</span><select data-f="' + key + '"><option value="">' + T('全部') + '</option>';
    var has = false;
    (list || []).forEach(function (o) {
      if (o.value === cur) has = true;
      h += '<option value="' + esc(o.value) + '"' + (o.value === cur ? ' selected' : '') + '>' +
        esc(o.label) + T('（{0}）', o.count) + '</option>';
    });
    // 选着的那一项在新结果里没有了: 仍然留在框里, 让人看得出为什么列表是空的
    if (cur && !has) h += '<option value="' + esc(cur) + '" selected>' + esc(cur) + T('（{0}）', 0) + '</option>';
    return h + '</select></label>';
  }
  // 「显示全部 N 条」: 只在点了某个数据源胶囊、而且这个源确实没列全时出现 (勾着的时候一直显示, 好取消)
  function fullToggle(s) {
    if (!srcFilter) return '';
    var g = s.groups.filter(function (x) { return x.id === srcFilter; })[0];
    if (!g || (!fFull && !(g.more > 0))) return '';
    return '<label class="toggle"><input type="checkbox" data-f="full"' + (fFull ? ' checked' : '') + '>' + T('显示全部 {0} 条', g.total) +
      '</label>';
  }
  function renderFilters(s) {
    var f = s.filters || {};
    var h = '<div class="filters">' +
      dropdown('res', T('分辨率'), f.resolution, fRes) +
      dropdown('sub', T('字幕'), f.subtitle, fSub) +
      dropdown('all', T('字幕组'), f.alliance, fAll) + '</div>' +
      '<div class="toggles"><label class="toggle"><input type="checkbox" data-f="ex"' + (fEx ? ' checked' : '') + '>' + T('显示被排除的资源') +
      (s.excludedCount ? T('（{0} 条）', s.excludedCount) : '') + '</label>' + fullToggle(s) + '</div>';
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
      var why = (fRes || fSub || fAll) ? T('没有符合筛选条件的结果')
        : !srcFilter ? (s.loading ? T('正在搜索数据源…') : T('没有找到可用的数据源，可以试试修改查询条件'))
        : !one ? T('这个数据源没有匹配的结果')
        : one.state === 'loading' ? T('这个数据源还在搜索…')
        : one.state === 'captcha' ? T('这个数据源需要人机验证，请在电视上处理')
        : one.state === 'failed' ? T('这个数据源搜索失败')
        : one.state === 'limited' ? T('这个数据源被限流了，稍后再试')
        : T('这个数据源没有匹配的结果');
      if (!fEx && s.excludedCount) why += T('，可以勾选「显示被排除的资源」看看');
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
        '<small>' + T('{0} 个源 · {1} 条', gs.length, n) + (hasSel ? ' ' + T('· 正在播放的在这里') : '') + '</small></summary>' +
        gs.map(function (g) { return groupHtml(g, s); }).join('') + '</details>';
    });
    return h;
  }
  /*
   * 「重新搜索(含新数据源)」: 这一次搜索用的是进播放页那一刻的数据源列表 (会话建立时取的快照),
   * 之后新增 / 启用的源、更新过的订阅都不在里面。
   *
   * 它在列表**上方**的独立容器里, 不跟着列表画: 点了某个数据源的胶囊时列表只剩那一段 (见 renderList
   * 的 srcFilter 分支), 跟在列表末尾的话, 正好会在「刚加的源没出现」这个要用它的场景下不见了。
   *
   * 有数据源被暂停时 (电视开播时还没查完的), 前面多一个「完整搜索」: 全部放开, 本播放页之后一直搜完。
   */
  var refetchShown = null;
  function renderRefetch(on, paused) {
    var key = on ? (paused ? 'on+paused' : 'on') : 'off';
    if (key === refetchShown) return;   // 每秒一次的轮询无条件重画会把按下去的按钮换掉
    refetchShown = key;
    document.getElementById('player-refetch').innerHTML = on
      ? '<div class="src-refetch">' +
        (paused ? '<button type="button" id="src-full">' + T('完整搜索') + '</button> ' : '') +
        '<button type="button" id="src-refetch">' + T('重新搜索（含新数据源）') + '</button>' +
        '<p class="hint">' + T('这次搜索用的是进入播放页时的数据源列表。刚加的数据源或刚更新的订阅要按一下才会参与，之后可能需要重新选片源。') + '</p></div>'
      : '';
  }
  var SECTIONS = [['cache', T('本地缓存')], ['web', T('在线源')], ['bt', T('BT 源')]];
  var secOpen = { cache: true, web: true, bt: true };
  // 某个源 (id) 在哪一段; 展开那一段, 下次重画照着画
  window.openSourceSection = function (id) {
    var g = lastState && lastState.groups.filter(function (x) { return x.id === id; })[0];
    if (g) secOpen[g.kind || 'web'] = true;
  };
  // 播放卡上的数据源胶囊 (另一段脚本) 点了要展开并重画列表
  window.lastPlayerState = function () { return lastState; };
  window.renderPlayerList = renderList;
  /*
   * 候选行左右滑 (见 swRow, 按钮写法同搜索结果): 右滑露出「缓存」= 用这一条缓存电视当前在播的这一集 (本地缓存那组本身就是缓存,
   * 不给); 左滑露出「打开链接」= 在手机上打开它在数据源上的链接 (网页源是站点上这一集的播放页, 直链源是视频地址本身).
   * 链接只认 http(s), 服务端也只给这两种.
   */
  function itemSwipe(it, row) {
    var left = it.cached ? '' :
      '<button type="button" class="sw-btn cache" data-pcache="' + esc(it.id) + '">' + window.ICONS.download + T('缓存') + '</button>';
    var right = it.url && /^https?:\/\//i.test(it.url)
      ? '<button type="button" class="sw-btn link" data-plink="' + esc(it.url) + '">' + window.ICONS.openLink + T('打开链接') + '</button>'
      : '';
    return left || right ? window.swRow(left, right, row) : row;
  }
  /** 滑到底「打开链接」的小窗 (见候选列表的点击处理): 链接本身是 <a target=_blank>, 由人点才能新开标签页. */
  function openLinkDialog(url) {
    var old = document.getElementById('link-dlg');
    if (old) old.remove();
    var d = document.createElement('div');
    d.id = 'link-dlg';
    d.innerHTML = '<div class="link-dlg-box"><div class="link-dlg-t">' + T('打开链接') + '</div>' +
      '<div class="link-dlg-u">' + esc(url) + '</div><div class="row">' +
      '<button type="button" class="ghost" data-ldlg="close">' + T('取消') + '</button>' +
      '<a class="primary" href="' + esc(url) + '" target="_blank" rel="noopener noreferrer" data-ldlg="open">' + T('在新标签页打开') + '</a>' +
      '</div></div>';
    // 点链接照默认行为新开标签页, 点取消或外面的空白处关掉; 关放到下一轮, 不在链接自己的点击里把它从页面上拿掉
    d.addEventListener('click', function (e) {
      if (e.target === d || e.target.closest('[data-ldlg]')) setTimeout(function () { d.remove(); }, 0);
    });
    document.body.appendChild(d);
  }
  function groupHtml(g, s) {
    var h = '<h2>' + (g.kind === 'cache' ? '' : window.srcIcon(g.id, g.name)) + esc(g.name) + ' <small>' + T('{0} 条', g.total) + '</small></h2><div class="list">';
    // 点了播放卡的数据源胶囊后亮一下的那一条 (见那里的点击处理)
    var flashId = window.flashSel && window.flashSel.until > Date.now() ? window.flashSel.id : null;
    g.items.forEach(function (it) {
      var sel = it.id === s.selectedId;
      // 去重: 在线源的「字幕组」常就是字幕语言 (简中 · 简中)
      var meta = [it.cached ? T('已缓存') : '', it.resolution, it.subtitles, it.alliance, it.size]
        .filter(function (v, i, a) { return v && a.indexOf(v) === i; }).join(' · ');
      h += itemSwipe(it, '<button class="item' + (sel ? ' sel' : '') + (it.id === flashId ? ' flash' : '') + (it.excluded ? ' ex' : '') + (it.blocked ? ' blocked' : '') +
        '" data-id="' + esc(it.id) + '"' + (it.blocked ? ' data-blocked="' + esc(it.reason || '') + '"' : '') + '>' +
        '<span class="t">' + esc(it.title) + '</span><span class="m">' + esc(meta) + '</span>' +
        (it.excluded ? '<span class="why">' + T('已排除：') + esc(it.reason || '') + '</span>' : '') +
        (sel ? '<span class="badge">' + (s.background ? T('当前') : T('正在播放')) + '</span>' : '') + '</button>');
    });
    h += '</div>';
    if (g.more > 0) h += '<p class="hint">' + T('还有 {0} 条未列出，', g.more) +
      (srcFilter ? T('可以勾选上面的「显示全部」') : T('点上面这个数据源的胶囊后可以选择显示全部')) + '</p>';
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
  document.getElementById('player-refetch').addEventListener('click', function (e) {
    var r = e.target.closest('#src-full');
    if (r) {
      r.disabled = true;
      post('api/player/full-search', {})
        .then(function (x) { if (x.message) toast(x.message); poll(true); })
        .catch(fail);
      return;
    }
    var b = e.target.closest('#src-refetch');
    if (!b) return;
    b.disabled = true;
    post('api/player/refetch', {})
      .then(function (r) { if (r.message) toast(r.message); poll(true); })
      .catch(fail)
      .then(function () { b.disabled = false; });
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
  // 滑开露出的两颗按钮 (同搜索结果, 滑过一半松手时由 swFire 替人点):
  // 「打开链接」: 点按钮是真的点击, 直接新标签页打开. 滑到底松手时是脚本替人点的 (isTrusted 为 false), iOS 不把拖动过的
  // 那一下算作点击, 这时新开标签页会被静默拦掉 —— 改弹 openLinkDialog, 由人点里面的链接, 新标签页照常打开.
  // 「缓存」用这一条缓存电视当前在播的这一集, 这一集已经在下载或已经缓存好时服务端只回一句提示
  document.getElementById('player-sources').addEventListener('click', function (e) {
    var l = e.target.closest('[data-plink]');
    if (l) {
      var url = l.getAttribute('data-plink');
      if (e.isTrusted) window.open(url, '_blank', 'noopener,noreferrer');
      else openLinkDialog(url);
      return;
    }
    var b = e.target.closest('[data-pcache]');
    if (!b) return;
    b.disabled = true;
    post('api/player/cache', { id: b.getAttribute('data-pcache') })
      .then(function (r) { toast(r.message); })
      .catch(fail)
      .then(function () { b.disabled = false; });
  });
  document.getElementById('player-sources').addEventListener('click', function (e) {
    // 分段标题: 点击在 details 自己开合之前, 记下点完之后的状态
    var sm = e.target.closest('details.src-sec > summary');
    if (sm) { secOpen[sm.parentNode.getAttribute('data-sec')] = !sm.parentNode.open; return; }
    var b = e.target.closest('.item');
    if (!b || b.classList.contains('sel')) return;
    if (b.hasAttribute('data-blocked')) { toast(T('不能选择：') + b.getAttribute('data-blocked')); return; }
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
<label class="f"><span>${t(Lang.mediafetch_request_editor_secondary_names)}${tr("（每行一个）")}</span><textarea name="others" rows="3"></textarea><em>${t(Lang.mediafetch_request_editor_secondary_names_supporting)}</em></label>
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
    // 主名下面那行"换成…"跟着新值重画 (见 wireNameSwap)
    window.wireNameSwap(form);
  }
  hooks.render.push(function (s) {
    box.hidden = !s.request;
    if (!s.request) return;
    var key = JSON.stringify(s.request);
    if (key !== last && !dirty) fill(s.request);
    last = key;
    document.getElementById('req-sum').textContent =
      T('：') + s.request.primary + (s.requestIsDefault ? '' : T('（已修改）'));
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
 * 静音载体那一帧画面的 H.264 数据 (480x270, 由 TV 横幅缩成)。
 *
 * 载体是现拼的 MP4 (见 CONTROL_SCRIPT 的 silentClipUrl): 视频轨**只有这一帧**, 靠 sample duration 撑满
 * 整集; 音频轨是 N 个一模一样的静音 AAC 帧。画面是死的 —— 想画实时内容得 canvas.captureStream() ->
 * video.srcObject, 那条在 iOS 上根本播不出来 (WebKit #181663), 所以封面 / 标题 / 进度进不去。画个图案
 * 只是免得小窗是一块纯黑让人以为坏了。
 *
 * 重新生成 (pip.png 是那张底图):
 *
 *     ffmpeg -loop 1 -i pip.png -frames:v 1 -vf scale=480:270 -c:v libx264 -profile:v baseline
 *            -level 3.0 -pix_fmt yuv420p -bsf:v h264_mp4toannexb -f h264 one.h264
 *
 * 再把 Annex B 切成 NAL, 取 type 7 / 8 / 5 分别做 SPS / PPS / IDR (type 6 的 SEI 丢掉), 各自 base64。
 * baseline + yuv420p 别改: iOS 对这一帧挑剔, 换了可能解不出来而小窗一片黑。
 */
private const val CARRIER_SPS_BASE64 = "Z0LAHtkB4I/rARAAAAMAEAAAAwMg8WLkgA=="
private const val CARRIER_PPS_BASE64 = "aMuDyyA="
private const val CARRIER_IDR_BASE64 =
    "ZYiEH8RigACU/HDHABk5OTk5OTk5OTk5OTk5OTk5OTk5OTk5OTk5OTk5Op666666666666666666666666666666666666666666///4IQ1wBqpPn5/933eHRnHkd+dAnN+Wqty1QHzcT0Uv//F+NB2Ba6nE1o+o2LlT0v5E4lYZ1uSSAXp6mBlXbHpDOj+EhXsNmG94srDttQZnRzmbDLX+uc/VAVFwVUer8NdO8BZd0BZ1LcVVOglSPgwCleG0cme8cwz/+j4e/1Cddddddddddddddddddddddf/5O8sIAo4BWh9DZAm/WARXo2VytqSuLEefcJJG453/e7/+QNYBeh9OHJ/bTN3wMw0cMdbfiIfPAGEDy2tC+mgB/4cBQLw6KmD09MxC8ks8Xd54edBmTIyqudV+OgsHCL1cIk8v9QFmm+1en1OedhWAQjXGGdn+BD9Fbep+oLa6666666666666666666/5Fk34LwUR0dRhGYdWQN+7A5INk4W9/kX/+CIIm70fuj/hCPvWgzXX+0tmPyDQUcAvsaK7f/+bgPd/mYbWrVfmc/9B8NBrdiFd5m3CM5/NqYWE/IHxxYEPURh3nL/AS/pe0oKa66666666666666666//+1ocBNwE5TG1naI5OOAi8U/tL//BWcIFvJe6vDhCzOw/5mCGSrr//7MUOAm4BemZZ3d5uXgHeJf9pf/4IjggfXlv/CpQzMwmrTOrTOrdNKfSmKD27yfhDyMwkHk49+zNMFtdddddddddddddddddf7Q/7DgKATZ/NHQxjyZjsxEu0UsPMwUxBKO1119rext8g0FHAksaJMPGz/34dIkM5N3XzQ2oTvplVibV5lVv6Q+lIUDkCamaWSQfRJVlyP1GJNMHMO///+/qPrrrrrrrrrrrrrrrrr///YIwUB6mfLUwU11/2t/wWAimQwIBML7nLf+cf/8NG/lpsHBvIza7fwmp80ktL/uePr6AxId65SQZeCPesOajMmfdBNTa3QD0A/8//6igqtYdJSQBv/iQw6mSkRHm10lTXfhk/2/+dV1wWl4TzgAl1zF/b/pTD7BGPolM5bKH/9hMYJHoPeVryQe9eV3RVBPXXXXXXXXXXXXXXXX///BCHOADAt8SbQ/8nIuQF4wrEi+AbtorUo5MnIyV0Qmlv0wU1110v//k0FwJIEaJj70CMzNqQSZ8ozjADgH5/z/YmlsZ/jTQTZ7qHMzaYZheLxyf4duH8vP7rcea/n/6TT6TagTxfkS/DsnH8CV6H8vy6QFQ9PRT/NjhBoDeVj9rrrhqtY4zjmGJ6uhYDPIUb8y9v/9q/YTLggth2K6uJa9lzGoJ666666666666666///4LgRcAnCsGUM7/F453/IiIiy5BpuAkMNSbAlZkf7vamZeMzXn8KkNjHMp+IgpPAGGc4+uuv//7wXAiwCiyTmaYvb7f+N3uOQIG4DEWKKBTgJww8eqlfw3UN12+3ychIikXvwRm8ETso3f/ZXYVLMM7gEnjbs/AGb/WbAFhyIzYq0blqA2wRR/5///dzj2HSQxFO4jksnHP72lAK5tGRhdKttv/ok1VYApxG7yeGESAEZpvGX0IjjOzvwn40wyde+GkwnpggKEtuJ+AiXLgZAhjgxEEqU/Snw2Hm/3gr9gbQPNPeIwicKYJ666666666666666/ylYg+QaCjgFsYzb15/8dTOBqGmqyov///ww6fhvV4H+UOgBx+Ub8Mny7///ug6TR88AF/lHhf474gYv5kAfba4q/+OPoOxXyw95OB1NoKW0eZX+bf8b7+CjgjN0G3UYzkngGQM6993/yBrgjq7kbGx0BVKdDM+xt9tNO77l/3jpH9RD/f4AqxEA1E+M0cH1BJt8+//IiJ3l0icAnlNoDQ2WUKHfIks7AE+4db6l+qeMvwf+7QgTUyAKXkEdfaEl3R7QSDOiaqXjfdmozNYfI+RWd2XzwxvVZx6Px/gdoNeX/KzVYQ/gBGSXkBlCf33gJHho68GPiFZH2091vdffDj0W0hAxCC08FjZCXrgxzFdFsjWg8fyquZimkMzSrs12kf/B+oBBPi8q1Al8yUu0TnjE4DASnTYwCIaSj5PgsnH99Kqqvzc9bkGmn/F+f9DlYgPtpnumCPp6fCmOD1v94R75raZ2R9+IwTBPXXXXXXXXXXXXXXXmuD/+g0CbAi6Ls7+NoC/qXnSG4z3f93//0Cs4yilv5EiOnHf9rAHZiTubqLIyes83Mcjb8VQLKmsQwkVqaHxTCTkEJmC//C+gyCKANVI2n5qN8HdN6fjR3N+f5U/3d3/RfANkH2ogRfnBjcA5vstz/bvv/9BYo6yQdT4v6p/z50mgnBYGpgYw4s//zScOAmiohIOAvOeLR6Q2J+RbIonCxRDCRRHWIIxUozP8CT9V6dyWI3Jdc52W3OXiLPmYgJR0maAOSD1N8j01Jzx4+M9/bsxy7Df43AHBfolcA7t5NeeA6/kVM3/vkewfgMx3HtLNDGdJ9qbW+wuSQLYdjBC6+eblfOsP/+wQBMPqZr6f0/+w4M8DKSZZ6ZZt+yipJglrrrrrrrrrrrrrrrvv2tpRoZ7DQIgQW/WnUz6Y1jnvNCxd+H0p9ggK/9NPRNJhkDFdNXdv8nrIllmiGCuj7Wu11XF//QaBBHH1wbu5kEF6bf3364Tvz//a3tLECpvAIR9CW23AQvWMEX6PHp87fwy4bM4uRtP8kO/hCKS9f+X9gh8Ax4jDv9va1zYJPpLn+ROw7+seE7mRVdeeAskwT111111111111111116U+px1eOBMCNr5/hx0pDk/UjKtMLwAK7Z4Y7yT6CV0pQlSrBb6eJD4yH6nvDA0HOgbDlsfGWK3LS3gSoCr78XpM/BYKdY83TD954YRKfU8diF7fT/+q52C0+Ikoy8lFcsuXebah///xeC4PYAszDNizBo7WX4Xl3m3/7u78QTgFFhIXxAMWLLQxJltjc33mTIi4/+CeU5cWVId7OeFcrwKUidPQ0Tw35/yI0W7iT9lYs39/7N9o4QJjvwLyHsFhnuBKQHkO/EeOd7MZ8f8N2MOPBGX6ndZvoIQk+u1+mCeuuuuuuuuuuuuuuuuu1xP1X68FgJlqPSiACkm+t5Ej+ZyoqKv/mz9y0Hfd1vQZY/c+15uI6XeH6S+wuEX4EO3vP+aPfqIf9B8cAlw8V+QyKo8f///0GiKtSy5o/ANrnMc6Ij6fcmk333L/0GjgzDi0Mv9/kR0TWbn1+8V4/+gWQVo1niS62+Tf7v/1Q6NtXEuBPWnHf4JHu39p/3NvcnG/JJ35UvBHvBI4NVToi/V4BwPsnG+P8LuE0ahfyL//BWcIuKGdT3gAQO5r+/g74iCGCWDFi6666666666666666666WlrrrrrpaWlrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrw=="

/**
 * 播放控制的脚本: 「正在播放」卡片里的进度条 + 后退 10 秒 / 播放暂停 / 前进 10 秒.
 * 卡片随候选变化整块重画, 所以控件在每次 render 后补上; 进度由每次轮询附带的 `playback` 刷新.
 * 点播放暂停先在本地翻转一次按钮文字, 不等下一次轮询 —— 否则按下去要过一秒才有反应, 像没按到.
 */
private val CONTROL_SCRIPT = """
(function () {
  var hooks = window.remoteHooks;
  var pb = null;
  var seekDebounce = null;
  var mediaSupported = !!(navigator.mediaSession && window.MediaMetadata);
  // 设置页据此决定显不显示「锁屏 / 控制中心」那一项 (LOOK_SCRIPT)
  window.mediaSessionSupported = mediaSupported;
  var mediaEnabled = false, mediaCarrier = null, carrierUrl = '', mediaState = null;
  var CARRIER_SPS = '$CARRIER_SPS_BASE64', CARRIER_PPS = '$CARRIER_PPS_BASE64', CARRIER_IDR = '$CARRIER_IDR_BASE64';
  // 上一次推给系统的内容; 每轮轮询都重建 MediaMetadata / 调 setPositionState 是白工, 变了才推
  var lastMetaKey = '', lastPlayKey = '';
  // 上一次真贴上去的封面, 新的还没探到时沿用它 (见 updateMediaMetadata 里的说明)
  var lastArtwork = [];
  function mediaMode() {
    try {
      var v = localStorage.getItem('ani-media-session-auto');
      return v === 'auto' ? v : 'manual';
    } catch (e) { return 'manual'; }
  }
  function mediaAutoEnabled() { return mediaMode() !== 'manual'; }
  /** 电视退出播放页后是否留着控件 (留着才能从锁屏唤醒). */
  function two(n) { return (n < 10 ? '0' : '') + n; }
  function fmt(ms) {
    var t = Math.max(0, Math.floor(ms / 1000));
    var h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), sec = t % 60;
    return (h ? h + ':' + two(m) : String(m)) + ':' + two(sec);
  }
  // iOS 只会把真正取得音频焦点的页面稳定放进锁屏 / 控制中心. 这里放一段 PCM 静音, 不传输也不播放电视内容;
  // 必须由用户点按钮触发 play(), 遵守 Safari 的媒体自动播放限制. 不把 audio 设为 muted —— muted 元素不会取得媒体焦点.
  //
  // 两条实测出来的硬约束 (2026-09-18, 各撞了一轮):
  //   1. **必须是真实的媒体元素, 不能用 Web Audio**: iOS 一锁屏 / 切后台就挂起 Web Audio 渲染, 只有"有媒体
  //      元素正在播"的页面才留得住音频会话 —— 用 AudioContext 静音流时放一分钟左右卡片就没了。
  //   2. **iOS 的锁屏进度条读的是这个元素自己的时间轴** (元素直连 MPNowPlayingInfoCenter), setPositionState
  //      只是补充 —— 拿一秒的循环当载体, 进度条就在 0 与 1 秒之间跳。
  // 两条合起来只剩一条路: **让载体的时间轴就是电视这一集的时间轴** —— 时长按剧集长度生成, currentTime 跟着
  // 电视位置走。这样系统读元素也对, 读 setPositionState 也对。
  /*
   * 把这一侧的状态发回电视的日志 (见 RemoteClientLog)。**电视那边只记慢请求**, 网页里出的事在 logcat 里
   * 本来一点痕迹都没有 —— 2026-09-20 排"全屏里总时长只有几秒"时手机侧整个是盲的, 只能靠电视日志里的副作用
   * 反推, 绕了两轮。所以留这条单向通道, 只在关键节点发。
   *
   * 自带去重与限流: 这些地方万一进了循环 (占位片每秒绕一次就是活生生的例子), 不能把 logcat 和局域网一起刷爆。
   */
  var lastClientLog = '', lastClientLogAt = 0;
  function clientLog(msg) {
    try {
      var text = String(msg);
      var now = Date.now();
      if (text === lastClientLog && now - lastClientLogAt < 10000) return;
      lastClientLog = text;
      lastClientLogAt = now;
      post('api/client-log', { msg: text }).catch(function () {});
    } catch (e) { /* 诊断而已, 失败就算了 */ }
  }
  // 轮询那几块在别的 IIFE 里, 它们的 catch 要用这条路把渲染异常发回电视
  window.clientLog = clientLog;
  var carrierSeconds = 0;
  var carrierReady = false;
  var carrierSelfActAt = 0, carrierSelfSeekAt = 0;
  /*
   * 载体是**一个带静音音轨的 `<video>`**, 不是 `<audio>`:
   *   - 换成 video 才进得了全屏和小窗 (画中画), 而那两处的进度条 / 快进快退 / 拖动**全是系统原生的**,
   *     用户直接就能用 —— 这正是 `<audio>` 给不了的。
   *   - **有音轨这件事是关键**: iOS 只暂停"无音轨 / muted"的后台视频, 带音轨的和 `<audio>` 一样能接着播
   *     (2026-09-20 用户拿在线视频站实测过)。所以别为了省事去掉音轨或加 muted。
   *
   * 文件是按集长现拼的 MP4: 视频轨**只有 1 帧** (见 CARRIER_IDR_BASE64), 靠 sample duration 撑满全程;
   * 音频轨是 N 个一模一样的静音 AAC 帧。每帧等长等大又全塞在一个 chunk 里, 于是 stts/stsc/stsz/stco
   * 四张表都与时长无关 —— 只有 mdat 变长, 约 173 字节/秒 (24 分钟 250 KB, 2 小时 1.2 MB)。
   */
  var AAC_RATE = 44100, AAC_FRAME = 1024, MP4_TS = 1000;
  var CARRIER_W = 480, CARRIER_H = 270;
  var SILENT_AAC = [0x01, 0x18, 0x20, 0x07];
  var MP4_MATRIX = null;
  function b64bytes(str) {
    var bin = atob(str), a = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    return a;
  }
  function u32(n) { return [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]; }
  function u16(n) { return [(n >>> 8) & 255, n & 255]; }
  function zeros(n) { var a = []; while (a.length < n) a.push(0); return a; }
  function chars(str) { var a = []; for (var i = 0; i < str.length; i++) a.push(str.charCodeAt(i)); return a; }
  function mbox(type, parts) {
    var body = [];
    for (var i = 0; i < parts.length; i++) body = body.concat(parts[i]);
    return u32(8 + body.length).concat(chars(type), body);
  }
  function mfull(type, flags, parts) { return mbox(type, [[0].concat(u32(flags).slice(1))].concat(parts)); }
  // MPEG-4 描述符: 长度是 7 位一组的变长编码
  function mdesc(tag, body) {
    var n = body.length;
    return [tag].concat(n < 0x80 ? [n] : [0x80 | (n >> 7), n & 0x7F], body);
  }
  function esdsBox() {
    var dsi = mdesc(0x05, [0x12, 0x08]);   // AudioSpecificConfig: AAC-LC / 44100 / 单声道
    var dcd = mdesc(0x04, [0x40, 0x15].concat(zeros(3), u32(0), u32(0), dsi));
    return mfull('esds', 0, [mdesc(0x03, u16(1).concat([0], dcd, mdesc(0x06, [0x02])))]);
  }
  function avcCBox(sps, pps) {
    return mbox('avcC', [[1, sps[1], sps[2], sps[3], 0xFF, 0xE1].concat(
      u16(sps.length), Array.prototype.slice.call(sps),
      [1], u16(pps.length), Array.prototype.slice.call(pps))]);
  }
  function videoStbl(sps, pps, sampleLen, dur, off) {
    var avc1 = mbox('avc1', [
      zeros(6).concat(u16(1), zeros(16), u16(CARRIER_W), u16(CARRIER_H),
        u32(0x00480000), u32(0x00480000), zeros(4), u16(1), zeros(32), u16(0x0018), [255, 255]),
      avcCBox(sps, pps)]);
    return mbox('stbl', [
      mfull('stsd', 0, [u32(1), avc1]),
      mfull('stts', 0, [u32(1), u32(1), u32(dur)]),   // 就这一帧, 一直显示到片尾
      mfull('stss', 0, [u32(1), u32(1)]),
      mfull('stsc', 0, [u32(1), u32(1), u32(1), u32(1)]),
      mfull('stsz', 0, [u32(sampleLen), u32(1)]),
      mfull('stco', 0, [u32(1), u32(off)])]);
  }
  function audioStbl(n, off) {
    var mp4a = mbox('mp4a', [
      zeros(6).concat(u16(1), zeros(8), u16(1), u16(16), zeros(4), u32(AAC_RATE * 65536)),
      esdsBox()]);
    return mbox('stbl', [
      mfull('stsd', 0, [u32(1), mp4a]),
      mfull('stts', 0, [u32(1), u32(n), u32(AAC_FRAME)]),
      mfull('stsc', 0, [u32(1), u32(1), u32(n), u32(1)]),
      mfull('stsz', 0, [u32(SILENT_AAC.length), u32(n)]),
      mfull('stco', 0, [u32(1), u32(off)])]);
  }
  function trakBox(id, timescale, dur, handler, name, stbl, movieDur, w, h) {
    var tkhd = mfull('tkhd', 7, [u32(0).concat(u32(0), u32(id), zeros(4), u32(movieDur), zeros(8),
      u16(0), u16(0), u16(handler === 'soun' ? 0x0100 : 0), zeros(2), MP4_MATRIX,
      u32(w * 65536), u32(h * 65536))]);
    var mdhd = mfull('mdhd', 0, [u32(0).concat(u32(0), u32(timescale), u32(dur), [0x55, 0xC4, 0, 0])]);
    var hdlr = mfull('hdlr', 0, [zeros(4).concat(chars(handler), zeros(12), chars(name), [0])]);
    var dinf = mbox('dinf', [mfull('dref', 0, [u32(1), mfull('url ', 1, [])])]);
    var mh = handler === 'vide' ? mfull('vmhd', 1, [zeros(8)]) : mfull('smhd', 0, [zeros(4)]);
    return mbox('trak', [tkhd, mbox('mdia', [mdhd, hdlr, mbox('minf', [mh, dinf, stbl])])]);
  }
  function silentClipUrl(seconds) {
    if (!MP4_MATRIX) {
      MP4_MATRIX = u32(0x10000).concat(u32(0), u32(0), u32(0), u32(0x10000), u32(0), u32(0), u32(0), u32(0x40000000));
    }
    var sps = b64bytes(CARRIER_SPS), pps = b64bytes(CARRIER_PPS), idr = b64bytes(CARRIER_IDR);
    var n = Math.max(1, Math.round(seconds * AAC_RATE / AAC_FRAME));
    var videoDur = Math.max(1, Math.round(seconds * MP4_TS)), sampleLen = 4 + idr.length;
    var ftyp = mbox('ftyp', [chars('isom').concat(u32(0x200), chars('isomiso2avc1mp41'))]);
    function moov(vOff, aOff) {
      var mvhd = mfull('mvhd', 0, [u32(0).concat(u32(0), u32(MP4_TS), u32(videoDur),
        u32(0x00010000), u16(0x0100), zeros(10), MP4_MATRIX, zeros(24), u32(3))]);
      return mbox('moov', [mvhd,
        trakBox(1, MP4_TS, videoDur, 'vide', 'VideoHandler',
          videoStbl(sps, pps, sampleLen, videoDur, vOff), videoDur, CARRIER_W, CARRIER_H),
        trakBox(2, AAC_RATE, n * AAC_FRAME, 'soun', 'SoundHandler',
          audioStbl(n, aOff), videoDur, 0, 0)]);
    }
    // chunk 偏移要指进 mdat, 而 mdat 在哪又取决于 moov 有多长 —— 先拿假偏移量算一份长度 (长度与偏移无关)
    var probe = moov(0, 0);
    var mdatStart = ftyp.length + probe.length + 8;
    var head = ftyp.concat(moov(mdatStart, mdatStart + sampleLen));
    var mdatLen = 8 + sampleLen + SILENT_AAC.length * n;
    var out = new Uint8Array(head.length + mdatLen), at = head.length;
    out.set(head, 0);
    out.set(u32(mdatLen), at); out.set(chars('mdat'), at + 4); at += 8;
    out.set(u32(idr.length), at); at += 4;          // avcC 是长度前缀格式, 不是 Annex B
    out.set(idr, at); at += idr.length;
    // 几十万帧逐个 set 太慢: 铺一帧之后成倍往后复制
    var tail = out.subarray(at);
    tail.set(SILENT_AAC, 0);
    for (var filled = SILENT_AAC.length; filled < tail.length;) {
      var take = Math.min(filled, tail.length - filled);
      tail.set(tail.subarray(0, take), filled);
      filled += take;
    }
    return URL.createObjectURL(new Blob([out], { type: 'video/mp4' }));
  }
  // 换集 / 第一次拿到片长时重建载体. 换 src 会短暂中断音频 (= 松一下焦点), 所以只在片长真的变了时做.
  var carrierPendingSeconds = 0;
  /** 上一条「推迟重建」日志记的是哪个长度; 只用于去重, 不参与任何逻辑. */
  var carrierDeferNoted = 0;
  function ensureCarrier(seconds) {
    if (!mediaCarrier || !(seconds > 0)) return;
    if (Math.abs(carrierSeconds - seconds) < 1) return;
    /*
     * **正在全屏 / 小窗里时不能换 src**: iOS 的原生播放器还认着换之前那一份, 时长和进度都不跟着变。
     * 2026-09-20 真机就是这么坏的 —— 加载还没完就按了全屏 (那时载体是一秒的循环占位片), 之后正确长度
     * 那份再也顶不上去, 于是全屏里总时长只有几秒、进度在 0 和 1 秒之间绕, 而占位片每绕回一次都引出一个
     * currentTime≈0 的 seeked, 被当成"用户拖到了片头"发给电视 (电视日志: jumped back 1049262ms -> 0ms)。
     * 记下来, 等退出全屏 / 小窗再换。
     */
    /*
     * **页面在后台时同样不能换**: 换 src 会在两份媒体之间留下一个空档, 前台时新的那份
     * 马上 play() 就接上了; 而页面隐藏 / 锁屏时 iOS 不允许自动播新源, 空档就成了永久的 ——
     * 控制中心那张卡被系统清掉, 手机上看着还在, 实际已经是个死壳 (进度 0、按钮无效)。
     * 同类报告: Safari 锁屏时播放列表换曲, "the playback card in Control Center is cleared
     * once one track stops before the next can play" (discussions.apple.com/thread/253331448),
     * 以及锁屏后无法自动播下一首 (developer.apple.com/forums/thread/706499)。
     * 2026-09-20 用户复现: 切番剧时赶在加载完成前把网页最小化 (那时载体还是一秒占位片,
     * 拿到片长后必定要重建一次), 控件就被抢走。
     *
     * 代价是后台期间锁屏进度条还按旧时间轴画 (iOS 画的是载体自己的时间轴, 见下面
     * updateMediaPlayback 的说明) —— 比起整个控件被收走, 这个代价小得多, 而且回前台立刻补正。
     */
    if (carrierPresenting() || document.hidden) {
      var why = document.hidden ? 'hidden' : 'presenting';
      // 这一句每轮轮询都会走到 (carrierSeconds 没变), 只在想要的长度真的变了时记一条。
      // 去重用独立的 [carrierDeferNoted], 不跟 carrierPendingSeconds 共用 —— 后者还被
      // applyPendingCarrier 改, 拿它当判据压不住 (2026-09-20 实测: PiP 期间每 10 秒漏一条,
      // 恰好是 clientLog 自带去重的周期)。
      if (carrierDeferNoted !== seconds) {
        carrierDeferNoted = seconds;
        clientLog('carrier rebuild deferred (' + why + '): want ' + seconds.toFixed(1) + 's, have ' + carrierSeconds.toFixed(1) + 's');
      }
      carrierPendingSeconds = seconds;
      return;
    }
    carrierDeferNoted = 0;
    carrierPendingSeconds = 0;
    // 先把文件拼出来再改状态: 反过来写的话, 一次失败就会让 carrierSeconds 停在新值上, 此后每一轮都在
    // 上面那行提前返回, 载体永远换不过去
    var next;
    try {
      next = silentClipUrl(seconds);
    } catch (e) {
      console.warn('Carrier build failed for ' + seconds + 's', e);
      clientLog('carrier build FAILED for ' + seconds.toFixed(1) + 's: ' + (e && e.message || e));
      return;
    }
    clientLog('carrier rebuilt: ' + seconds.toFixed(1) + 's (was ' + carrierSeconds.toFixed(1) + 's)');
    carrierSeconds = seconds;
    carrierReady = false;
    // 换 src 会把 currentTime 打回 0 并引出 seeked —— 不按住这一下, 它就会被当成"用户拖到了片头"发给电视
    carrierSelfSeekAt = Date.now();
    var wasPlaying = !mediaCarrier.paused;
    var stale = carrierUrl;
    carrierUrl = next;
    mediaCarrier.loop = false;
    mediaCarrier.src = carrierUrl;
    // 换完再撤旧的, 别在元素还指着它时就回收
    if (stale) URL.revokeObjectURL(stale);
    if (wasPlaying) {
      var again = mediaCarrier.play();
      if (again && again.catch) again.catch(function () {});
    }
  }
  // 载体与电视对表. 差得不多就别动 —— 每次都写 currentTime 会让系统频繁重画, 反而抖.
  function syncCarrier(seconds) {
    if (!mediaCarrier || !carrierReady || !(carrierSeconds > 0)) return;
    var target = Math.max(0, Math.min(carrierSeconds - 0.25, seconds));
    if (Math.abs(mediaCarrier.currentTime - target) < 2) return;
    // 自己写的这一下会引出 seeked, 别把它当成"用户在全屏里拖了进度条"再发回电视
    carrierSelfSeekAt = Date.now();
    try { mediaCarrier.currentTime = target; } catch (e) { console.warn('Silent carrier seek rejected', e); }
  }
  /*
   * ====== 全屏与小窗 (画中画) ======
   * 载体本身就是那个 video, 所以全屏 / 小窗里的进度条、快进快退、拖动**全是系统原生的** —— 载体的时长
   * 就是这一集的时长, 位置也一直跟电视对着表, 用户拖到哪我们就把哪转给电视。
   *
   * **只给一颗「全屏」按钮就够**: iOS 原生全屏播放器的退出键旁边自带画中画按钮, 小窗从那里进;
   * 小窗还能拖到屏幕边上藏成一个把手, 于是不用每次下拉控制中心。所以这边不另做进小窗的按钮,
   * 但**照样要盯着 pipActive** —— 用户从全屏转进小窗之后, 那上面的按键还得转发给电视。
   *
   * **画面是死的**: 想画实时内容得 canvas.captureStream() -> video.srcObject, iOS 上播不出来
   * (WebKit #181663)。**图标也不会实时跟着电视变**: 页面切后台后 JS 基本停跑, 与锁屏控件同一个老限制。
   */
  var pipActive = false, fsActive = false;
  function carrierPresenting() { return pipActive || fsActive; }
  /*
   * 自己调 play/pause/playbackRate 时按一下时间戳, 免得自家动作被当成"用户按的"又发回电视。
   * **别用"布尔 + setTimeout(0) 清掉"那种写法**: 媒体元素的事件是排队派发的, 不保证比那个 0 毫秒
   * 定时器先到 —— 标记先被清掉, 自家这一下就原样发回电视了。时间戳没有这个时序问题。
   */
  var CARRIER_SELF_MS = 500;
  function carrierSelfJustActed() { return Date.now() - carrierSelfActAt < CARRIER_SELF_MS; }
  function carrierQuiet(fn) {
    carrierSelfActAt = Date.now();
    try { fn(); } finally { carrierSelfActAt = Date.now(); }
  }
  function carrierFullscreenCan() {
    var v = mediaCarrier;
    if (v) return typeof v.webkitEnterFullscreen === 'function' || typeof v.requestFullscreen === 'function';
    return !!(window.HTMLVideoElement && (HTMLVideoElement.prototype.webkitEnterFullscreen
      || HTMLVideoElement.prototype.requestFullscreen));
  }
  /*
   * **载体还是占位片的时候不许进全屏**。iOS 的全屏播放器认死进去时的那一份, 带着一秒的占位片进去之后
   * 就回不了头了 (见 ensureCarrier)。所以片长还没到、或者载体还没换过来时, 按钮置灰。
   * 载体还没建 (没接入) 时是可以的 —— 点下去会当场按这一集的长度建好再进。
   */
  function carrierFullscreenReady() {
    // 已经在全屏 / 小窗里了, 这颗按钮只是个状态显示, 不用再拦
    if (carrierPresenting()) return true;
    if (!(pb && pb.duration > 0)) return false;
    var v = mediaCarrier;
    if (!v) return true;
    return !v.loop && Math.abs(carrierSeconds - pb.duration / 1000) < 2;
  }
  function enterCarrierFullscreen() {
    var v = mediaCarrier;
    if (!v || !carrierFullscreenReady()) return;
    // iOS 两条硬要求: 得在用户手势的调用栈里, 而且视频要正在播。所以 play() 之后**同步**就切, 等 promise 会掉出手势栈。
    carrierQuiet(function () {
      var started = v.play();
      if (started && started.catch) started.catch(function (e) { console.warn('Carrier play rejected', e); });
      try {
        // iPhone 上 requestFullscreen 对 video 不管用, 得用 webkit 这个才进原生播放器 (画中画按钮也在那上面)
        if (typeof v.webkitEnterFullscreen === 'function') v.webkitEnterFullscreen();
        else if (v.requestFullscreen) {
          v.requestFullscreen().catch(function (e) {
            console.warn('Fullscreen rejected', e);
            toast(T('这个浏览器不让网页全屏'));
          });
        }
      } catch (e) {
        console.warn('Fullscreen rejected', e);
        toast(T('这个浏览器不让网页全屏'));
      }
    });
  }
  // 呈现期间 / 页面在后台时欠下的那次重建, 回来之后补上
  function applyPendingCarrier() {
    if (carrierPresenting() || document.hidden || !(carrierPendingSeconds > 0)) return;
    var want = carrierPendingSeconds;
    carrierPendingSeconds = 0;
    ensureCarrier(want);
  }
  // 回到前台: 把后台期间欠下的载体重建补上 (这时 play() 不再需要用户手势,
  // 见 keepMediaAudioAlive), 锁屏进度条的时间轴随之对回来
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) applyPendingCarrier();
  });
  // 全屏 / 小窗里的播放状态跟电视走 —— 那上面的图标认的是这个元素自己在不在播
  function syncCarrierPlayback() {
    var v = mediaCarrier;
    if (!v || !carrierPresenting()) return;
    var want = !!(pb && pb.playing);
    if (want === !v.paused) return;
    carrierQuiet(function () {
      if (want) {
        var again = v.play();
        if (again && again.catch) again.catch(function (e) { console.warn('Carrier resume rejected', e); });
      } else v.pause();
    });
  }
  function createCarrier() {
    var v = document.createElement('video');
    /*
     * 一上来就按这一集的长度建。**别先上占位片再换** —— 用户点「全屏」那一下是同步执行的, 而把载体换成
     * 整集那份要等下一轮轮询, 于是进全屏时手上还是占位片, 而 iOS 的全屏播放器会认死进去时那一份 (见
     * ensureCarrier 里的说明)。只有连片长都还不知道时才退回一秒的循环占位片, 先把音频焦点占住。
     */
    var known = pb && pb.duration > 0 ? pb.duration / 1000 : 0;
    try {
      carrierUrl = silentClipUrl(known > 0 ? known : 1);
      carrierSeconds = known;
    } catch (e) {
      console.warn('Carrier build failed for ' + known + 's', e);
      clientLog('carrier create FAILED for ' + known.toFixed(1) + 's, falling back to the 1s placeholder: ' + (e && e.message || e));
      carrierUrl = silentClipUrl(1);
      carrierSeconds = 0;
      known = 0;
    }
    clientLog('carrier created: ' + (known > 0 ? known.toFixed(1) + 's' : '1s placeholder (duration unknown)'));
    v.src = carrierUrl;
    v.loop = !(known > 0);
    v.preload = 'auto';
    // 原生控件: 页面里看不见 (元素在屏幕外), 但全屏播放器靠它才给出进度条和那颗画中画按钮
    v.controls = true;
    v.setAttribute('playsinline', '');
    v.setAttribute('aria-hidden', 'true');
    // 不能 display:none —— 那样的元素进不了全屏 / 小窗; 挪到屏幕外就行
    v.style.cssText = 'position:fixed;left:-9999px;top:0;width:2px;height:2px;opacity:0;pointer-events:none';
    // currentTime 必须等元数据到了再写, 否则静默失败或者跳回 0
    v.addEventListener('loadedmetadata', function () {
      carrierReady = true;
      // 这一行是判断"载体到底换过去没有"的硬证据: 想要的和 iOS 真读出来的时长摆在一起
      clientLog('carrier ready: element ' + (isFinite(v.duration) ? v.duration.toFixed(1) + 's' : String(v.duration))
        + ', wanted ' + carrierSeconds.toFixed(1) + 's, loop=' + v.loop);
      if (pb) syncCarrier((pb.position || 0) / 1000);
    });
    // iOS 挑不挑我们这份手拼的 MP4, 只有这里看得出来 (挑的话全屏里就是一片黑 / 没时长)
    v.addEventListener('error', function () {
      var err = v.error;
      console.warn('Carrier failed to load', err);
      clientLog('carrier LOAD ERROR code=' + (err && err.code) + ' wanted=' + carrierSeconds.toFixed(1) + 's');
    });
    // 载体放到头了 (电视这一集也快完了): 电视还在播就停在末尾接着占位, 别让 ended 把焦点交还
    v.addEventListener('ended', function () {
      if (!mediaEnabled || !(pb && pb.playing)) return;
      carrierQuiet(function () {
        try { v.currentTime = Math.max(0, carrierSeconds - 0.25); } catch (e) {}
        var back = v.play();
        if (back && back.catch) back.catch(function () {});
      });
    });
    v.addEventListener('pause', function () {
      if (!mediaEnabled || carrierSelfJustActed()) return;
      // 全屏 / 小窗里按的暂停 = 用户的意思, 转给电视; 其它情况是系统把我们按停了, 自己接回来
      // (页面被切走时定时器会被冻住, 只能挂事件等回到前台那一刻自愈)
      if (carrierPresenting()) {
        if (pb && pb.playing) sendMediaControl('pause');
        return;
      }
      if (!(pb && pb.playing)) return;
      var back = v.play();
      if (back && back.catch) back.catch(function () {});
    });
    v.addEventListener('play', function () {
      if (!mediaEnabled || carrierSelfJustActed()) return;
      if (carrierPresenting() && pb && !pb.playing) sendMediaControl('play');
    });
    // 全屏 / 小窗里拖进度条、按 ±15 秒: 走与控件同一条路 (seek 里有 pending 闸, 轮询不会把它拽回去)
    v.addEventListener('seeked', function () {
      if (!mediaEnabled || carrierSelfJustActed()) return;
      if (!carrierPresenting() || Date.now() - carrierSelfSeekAt < 1000) return;
      /*
       * 要挡的是**占位片** —— 它的时间轴根本不是这一集的: 一秒绕回一次, 每次都引出 currentTime≈0 的
       * seeked, 不挡就是每秒给电视发一次 seek(0) (2026-09-20 真机, 电视日志里连着出现
       * "jumped back: 1049262ms -> 0ms")。
       *
       * **但"载体比电视长几秒/短几秒"不算这种情况, 不能一起挡掉。** 在全屏 / 小窗里换集时载体的重建
       * 是挂起的 (见 ensureCarrier), 载体还是上一集的时长 —— 我起初要求两边时长对得上才转发, 结果
       * 换完集快进快退整个失灵, 得退出小窗才好 (2026-09-20 用户实测, 日志:
       * "carrier rebuild deferred (presenting): want 1377.0s, have 1402.1s")。
       * 位置是按秒对表的, 差那么点只影响显示的总长, 拖到哪就是哪 —— 夹进这一集的范围里发出去就行。
       */
      var tvDur = (pb && pb.duration || 0) / 1000;
      if (!carrierReady || v.loop || !(carrierSeconds > 0) || !(tvDur > 0)) {
        clientLog('seek from ' + (carrierPresenting() ? 'presentation' : 'page') + ' dropped: ready=' + carrierReady
          + ' loop=' + v.loop + ' carrier=' + carrierSeconds.toFixed(1) + 's tv=' + tvDur.toFixed(1) + 's');
        return;
      }
      seek(Math.round(Math.max(0, Math.min(tvDur, v.currentTime)) * 1000));
    });
    // 原生全屏播放器里的倍速菜单: 它改的是这个元素的 playbackRate, 转给电视才算数
    v.addEventListener('ratechange', function () {
      if (!mediaEnabled || carrierSelfJustActed()) return;
      if (!carrierPresenting() || !pb || pb.speed == null) return;
      // 档位与范围跟电视走 (speedMin/speedMax/speedStep 来自 RemotePlayerHandle) —— 同一个常量别在这边
      // 再抄一份, 倍速那次的教训就是抄完悄悄偏掉
      var step = Math.max(1, Math.round((pb.speedStep || 0.25) * 100));
      var lo = Math.round((pb.speedMin || 0.25) * 100), hi = Math.round((pb.speedMax || 4) * 100);
      var pct = Math.max(lo, Math.min(hi, Math.round(Math.round(v.playbackRate * 100) / step) * step));
      if (pct === Math.round(pb.speed * 100)) return;
      spDragging = false;
      spHoldUntil = Date.now() + 2000;
      spLocal = pct;
      clearTimeout(spTimer);
      spTimer = null;
      sendSpeed(pct);
      paint();
    });
    // 用户可能从全屏里转进小窗, 那之后按键还得照样转发, 所以这两个状态都要盯着
    function modeChanged() {
      var on = v.webkitPresentationMode ? v.webkitPresentationMode === 'picture-in-picture'
        : document.pictureInPictureElement === v;
      if (on === pipActive) return;
      pipActive = on;
      clientLog('pip ' + (on ? 'entered' : 'left') + ', carrier ' + carrierSeconds.toFixed(1) + 's');
      applyPendingCarrier();
      paint();
    }
    v.addEventListener('webkitpresentationmodechanged', modeChanged);
    v.addEventListener('enterpictureinpicture', modeChanged);
    v.addEventListener('leavepictureinpicture', modeChanged);
    v.addEventListener('webkitbeginfullscreen', function () {
      fsActive = true;
      clientLog('fullscreen entered, carrier ' + carrierSeconds.toFixed(1) + 's, element '
        + (isFinite(v.duration) ? v.duration.toFixed(1) + 's' : String(v.duration)) + ', loop=' + v.loop);
      paint();
    });
    v.addEventListener('webkitendfullscreen', function () {
      fsActive = false;
      clientLog('fullscreen left');
      applyPendingCarrier();
      paint();
    });
    document.addEventListener('fullscreenchange', function () {
      var on = document.fullscreenElement === v;
      if (on === fsActive) return;
      fsActive = on;
      applyPendingCarrier();
      paint();
    });
    document.body.appendChild(v);
    return v;
  }
  function setMediaHandler(action, handler) {
    try { navigator.mediaSession.setActionHandler(action, handler); } catch (e) { console.debug('MediaSession action unavailable: ' + action, e); }
  }
  // 封面候选按顺序探一遍, 回调第一个真能加载的。art 是一串候选 (这一集的剧照 → 整部的横屏图, 见
  // RemoteEpisodeArt), 网页那张卡片本来就是一个个试的; 而**系统只会用我们给的第一条**, 把整串交上去
  // 等于"第一条取不到就没图" —— 控件上的封面时有时无多半是这么来的。探测会命中浏览器缓存, 系统随后
  // 再取那张图是白拿的。
  function pickArtwork(list, done) {
    var i = 0;
    (function next() {
      if (i >= list.length) {
        // 一条都没取到 = 交给系统的 artwork 是空的, 锁屏上那块就是白的 (2026-09-20 用户报"封面变成全白")
        clientLog('artwork: none of ' + list.length + ' candidates loaded');
        done([]);
        return;
      }
      var url = list[i++];
      var img = new Image();
      img.onload = function () {
        if (i > 1) clientLog('artwork: candidate ' + i + '/' + list.length + ' won');
        done([{ src: url }]);
      };
      img.onerror = next;
      img.src = url;
    })();
  }
  function updateMediaMetadata() {
    if (!mediaEnabled || !mediaState) return;
    var list = [];
    (mediaState.art || []).forEach(function (src) {
      try { list.push(new URL(src, location.href).href); } catch (e) {}
    });
    var metaKey = (mediaState.title || '') + '|' + (mediaState.episode || '') + '|' + list.join(',');
    if (metaKey === lastMetaKey) return;
    lastMetaKey = metaKey;
    var title = mediaState.title || 'Izuko TV', artist = mediaState.episode || '';
    function apply(artwork) {
      // 探测期间换集了就作废, 别把上一集的图贴到这一集上
      if (lastMetaKey !== metaKey) return;
      var use = artwork && artwork.length ? artwork : lastArtwork;
      if (artwork && artwork.length) lastArtwork = artwork;
      else if (!use.length) clientLog('artwork: nothing to show (no candidate yet, no previous one)');
      navigator.mediaSession.metadata = new MediaMetadata({
        title: title,
        artist: artist,
        album: 'Izuko TV',
        artwork: use
      });
    }
    /*
     * 标题先上, 不等图 (图还在查的那几秒里控件也该是对的)。**但空着上等于把封面擦成白的。**
     *
     * 2026-09-20 用户报"换了两集之后封面变成全白"。排查时否掉了两个想当然的解释:
     *   - **不是"页在后台所以图加载不了"**: 后台里轮询、updateMediaMetadata、clientLog 的 POST 全都照跑。
     *     (进度条走 hooks.playback 每轮都更新, 封面走 hooks.render, 两条路不同, 但后台都不拦。)
     *   - **不是手机连不上 TMDB**: 这些地址是经电视 `api/img` 转发的 (见 RemoteEpisodeArt), 手机只连电视。
     *
     * 真正的判据来自电视日志: [RemoteImageProxy] **只记失败**, 而那一段一条都没有 —— 也就是手机压根
     * 没去请求过图, 说明 `s.art` 是空列表 (电视没给出这一集的候选图, TMDB 没匹配到 still)。于是
     * [pickArtwork] 立刻 done([]), 空 artwork 一交上去, 锁屏上那块就是白的。
     *
     * 所以没拿到新的之前沿用上一张: 旧封面总比一块白好, 探到了立刻换掉。
     */
    apply([]);
    pickArtwork(list, apply);
  }
  // 该占着焦点时确认音轨还在播 (解锁过一次之后 play() 不再需要用户手势). 与 audio 的 pause 监听一道,
  // 页面每次被唤醒都自愈一次.
  function keepMediaAudioAlive() {
    if (!mediaEnabled || !mediaCarrier || !mediaCarrier.paused) return;
    if (!(pb && pb.playing)) return;
    var back = mediaCarrier.play();
    if (back && back.catch) back.catch(function () {});
  }
  function updateMediaPlayback() {
    if (!mediaEnabled) return;
    keepMediaAudioAlive();
    // 电视退到后台 = 肯定没在播 (后台保留会话是被按住暂停的), 而且这时多半连 playback 都收不到 —— 必须显式
    // 按暂停显示. 原来的写法在 pb 为空时直接 return, 控件就一直停在"播放中" (用户 2026-09-18).
    var playing = !!(pb && pb.playing) && !(mediaState && mediaState.background);
    navigator.mediaSession.playbackState = playing ? 'playing' : 'paused';
    // 不带参数的 setPositionState() 是**清空**, 锁屏进度条会当场掉回 0: pb 还没到 / 这一瞬拿不到片长时
    // 提前返回, 保持上一次的位置.
    if (!pb || !(pb.duration > 0)) return;
    var duration = pb.duration / 1000;
    // 载体的对表**必须在去重之前**: 电视停着 (刚被叫醒还没起播) 时位置不变, 下面那个 playKey 会一路提前返回,
    // 可载体为了占住焦点一直在播, 每秒偏 1 秒 —— iOS 画的是载体自己的时间轴, 于是进度条在真值和载体跑到的
    // 地方之间来回跳 (用户 2026-09-18: 用快进唤醒之后又跳了; 按播放键唤醒没事, 因为那时位置一直在变,
    // 每一轮都会走到这里校正).
    ensureCarrier(duration);
    syncCarrier((pb.position || 0) / 1000);
    var playKey = (playing ? 1 : 0) + '|' + Math.round((pb.position || 0) / 1000) +
      '|' + Math.round((pb.duration || 0) / 1000);
    if (playKey === lastPlayKey) return;
    lastPlayKey = playKey;
    try {
      navigator.mediaSession.setPositionState({
        duration: duration,
        // 永远给 1, **不能给 0**: 规范里 playbackRate 为 0 时 setPositionState 直接抛 TypeError, 整次调用
        // 作废 —— 表现成"暂停时快进快退控件纹丝不动、拖完弹回原处, 一按播放才跳到正确位置"(2026-09-18 排查
        // 了半天的那个). 暂停时进度条不往前走这件事不用我们管: WebKit 构造 NowPlaying 数据时就是
        // rate = isPlaying ? rate : 0, playbackState 设成 paused 它自己会冻住.
        playbackRate: 1,
        position: Math.max(0, Math.min(duration, (pb.position || 0) / 1000))
      });
    } catch (e) { console.warn('MediaSession position rejected', e); }
    if (!mediaCarrier) return;
    // 电视没在播却还得占着焦点 (刚叫醒还没起播 / 后台保持) 时, 让载体几乎不走 —— iOS 画的是它自己的时间轴,
    // 由着它 1 倍速跑, 电视一停就越跑越偏 (电视后台时连播放数据都收不到, 想校正都没得校). 夹不住这个速率的
    // 浏览器会照常 1 倍速, 还有上面每轮 2 秒内的对表兜底.
    // 电视在倍速时载体也得跟着走: 由着它 1 倍跑, 每两秒被对表拽一下, 全屏的进度条就会一跳一跳;
    // 而且原生全屏里那个倍速菜单读的就是这个值, 跟上了才显示得对 (setPositionState 那边保持 1, 见上面)
    var wantRate = playing ? Math.max(0.05, (pb && pb.speed) || 1) : 0.05;
    if (Math.abs(mediaCarrier.playbackRate - wantRate) > 0.001) {
      carrierQuiet(function () {
        try { mediaCarrier.playbackRate = wantRate; } catch (e) { console.debug('Carrier rate rejected', e); }
      });
    }
    if (playing) {
      var started = mediaCarrier.play();
      if (started && started.catch) started.catch(function () {});
    } else {
      // 必须真的 pause: iOS 的锁屏按钮与进度条跟的是这个元素在不在播, 不是 playbackState —— 让音轨一直
      // 播着、只把 playbackState 设成 paused 的写法试过, 按钮永远停在"暂停"图标, 怎么按都切不动
      // (2026-09-18 实测). 代价是交还音频焦点: iOS 上网页音频停了约 30 秒系统就收走 Now Playing,
      // 所以电视暂停久了控件会消失 —— 这是网页播放器的固有行为, 想留住它只能一直出声, 不做那个取舍.
      mediaCarrier.pause();
    }
  }
  function sendMediaControl(action) {
    if (pb) pb.playing = action === 'play';
    holdPlayState(action === 'play');
    if (mediaCarrier) {
      if (action === 'play') {
        var started = mediaCarrier.play();
        if (started && started.catch) started.catch(function () {});
      } else mediaCarrier.pause();
    }
    updateMediaPlayback();
    paint();
    post('api/player/control', { action: action })
      .then(function (r) { if (r.message) toast(r.message); poll(true); })
      .catch(fail);
  }
  function switchMediaEpisode(step) {
    var episodes = mediaState && mediaState.episodes || [];
    var at = episodes.findIndex(function (e) { return e.current; });
    var target = at >= 0 ? episodes[at + step] : null;
    if (!target) return;
    post('api/player/episode', { id: String(target.id) })
      .then(function (r) { if (r.message) toast(r.message); poll(true); })
      .catch(fail);
  }
  // 自动接入模式: 页面刚打开时 play() 必被浏览器的自动播放策略拒掉, 只能等第一次手势 —— 在控制台上
  // 随便点一下就解锁了; 解锁之后 play() / pause() 就能跟着电视状态自由切换 (不用再要手势).
  function tryAutoMediaSession() {
    if (!mediaSupported || mediaEnabled || !mediaAutoEnabled()) return;
    if (!mediaState || mediaState.background) return;
    enableMediaSession(true);
  }
  document.addEventListener('pointerdown', tryAutoMediaSession, true);
  // 设置页改了这一项时: 重画按钮 (手动模式才有), 开了就当场试一次 (这一下点击本身就是手势)
  window.refreshMediaSessionMode = function () {
    paint();
    tryAutoMediaSession();
  };
  function registerMediaHandlers() {
    setMediaHandler('play', function () { sendMediaControl('play'); });
    setMediaHandler('pause', function () { sendMediaControl('pause'); });
    setMediaHandler('stop', function () { sendMediaControl('pause'); });
    // 位置用 lastKnownPosition 兜底: 原来写成 if (pb) ... , pb 一旦是空的 (电视退出播放页那阵子收不到播放数据)
    // 这两个键就**什么都不做** —— 按下去毫无动静, 连唤醒都不会发生 (用户 2026-09-18: 快进唤醒不生效).
    setMediaHandler('seekbackward', function (d) {
      seek(Math.max(0, seekBase() - ((d && d.seekOffset) || 10) * 1000));
    });
    setMediaHandler('seekforward', function (d) {
      var limit = (pb && pb.duration) || lastKnownDuration;
      var target = seekBase() + ((d && d.seekOffset) || 10) * 1000;
      seek(limit > 0 ? Math.min(limit, target) : target);
    });
    // 拖动过程中系统一路发 seekTime (带 fastSeek), 松手才发最后一次. 每一下都发给电视 = 电视被灌一串跳转,
    // 回报的位置一直在追, 控件上就是拖完还在跳; 全都不理又怕有的浏览器只发 fastSeek 那一种. 所以拖动中只更新
    // 本页显示, 停手 300 毫秒才真发, 松手那一下立刻发.
    setMediaHandler('seekto', function (d) {
      if (!pb || !d || d.seekTime == null) return;
      var ms = d.seekTime * 1000;
      if (seekDebounce) { clearTimeout(seekDebounce); seekDebounce = null; }
      if (d.fastSeek === true) {
        pb.position = ms;
        paint();
        seekDebounce = setTimeout(function () { seekDebounce = null; seek(ms); }, 300);
        return;
      }
      seek(ms);
    });
    setMediaHandler('previoustrack', function () { switchMediaEpisode(-1); });
    setMediaHandler('nexttrack', function () { switchMediaEpisode(1); });
  }
  function clearMediaHandlers() {
    ['play', 'pause', 'stop', 'seekbackward', 'seekforward', 'seekto', 'previoustrack', 'nexttrack'].forEach(function (action) {
      setMediaHandler(action, null);
    });
  }
  function disableMediaSession(quiet) {
    cancelPendingDisable();
    if (!mediaEnabled && !mediaCarrier) return;
    mediaEnabled = false;
    window.mediaSessionActive = false;
    if (mediaCarrier) { mediaCarrier.pause(); mediaCarrier.remove(); mediaCarrier = null; }
    if (carrierUrl) { URL.revokeObjectURL(carrierUrl); carrierUrl = ''; }
    clearMediaHandlers();
    lastMetaKey = '';
    lastPlayKey = '';
    navigator.mediaSession.metadata = null;
    navigator.mediaSession.playbackState = 'none';
    try { navigator.mediaSession.setPositionState(); } catch (e) {}
    paint();
    if (!quiet) toast(T('已退出手机的锁屏和控制中心'));
  }
  function enableMediaSession(silent) {
    if (!mediaSupported || mediaEnabled) return;
    mediaCarrier = createCarrier();
    registerMediaHandlers();
    // play 必须直接发生在这次点击里; 等它成功后再把电视的暂停状态同步回来.
    var started = mediaCarrier.play();
    Promise.resolve(started).then(function () {
      mediaEnabled = true;
      window.mediaSessionActive = true;
      updateMediaMetadata();
      updateMediaPlayback();
      paint();
      if (!silent) toast(T('已接入手机的锁屏和控制中心'));
      poll(true);
    }).catch(function (e) {
      // 自动接入被自动播放策略挡下是常态 (页面还没被碰过), 不打扰用户, 下次手势再试
      console.debug('MediaSession blocked', e);
      disableMediaSession(true);
      if (!silent) toast(T('浏览器不允许启用系统播放控件，请用系统浏览器打开后再试'));
    });
  }
  // 拖进度条期间不让轮询把滑块拽回去; 松手才发一次跳转
  var dragging = false;
  // 正在就地改时间 (见 openEdit); editText = 输入框里当前的字, 卡片被整张重画时照着补回去
  var editing = false, editText = '';
  // 音量条: 拖动期间、松手后一会儿不让轮询把它拽回去 (电视那边落地、下一次轮询带回来要一点时间); 拖动中节流着发
  var volDragging = false, volHoldUntil = 0, volTimer = null, volLocal = null;
  function sendVol(v) { post('api/player/control', { action: 'volume', v: String(v / 100) }).catch(fail); }
  /*
   * 加减样式的音量 (见 volRowHtml): 一下一档、按住数字左右拖、点数字直接输 —— 手感抄弹幕时间偏移,
   * 但**发送时机不能抄**: 偏移是个设定值, 中途那些值没意义, 所以它停手才发; 音量要边调边听才知道停在哪,
   * 必须当场生效 (旧的滑条样式本来就是这样)。
   */
  /*
   * 一档多少个百分点。**别取整**: 电视的档位不一定落在整百分比上 (Shield 系统音量 15 档 = 6.67 一档),
   * 取整成 7 的话本地走 7/14/21, 电视却是 7/13/20, 松手就被电视值拉回去 —— 看着像"一会儿 5 一会儿 7"。
   * 所以本地也按档号算 (volSnap), 只在显示那一下取整。
   */
  var volStep = 5, volEditing = false;
  // 吸附到最近的档
  function volSnap(v) { return Math.max(0, Math.min(100, Math.round(v / volStep) * volStep)); }
  function volNow() {
    if (volLocal != null && (volDragging || Date.now() < volHoldUntil)) return volLocal;
    return pb && pb.volume != null ? Math.round(pb.volume * 100) : 0;
  }
  function paintVol() {
    var b = document.getElementById('pb-vol-val');
    if (b && !volEditing) b.textContent = Math.round(volNow()) + '%';
  }
  // 第一下立刻发, 之后每 VOL_SEND_MS 发一次最新值 —— 连按/快拖时既听得到变化, 又不会一档一个请求
  var VOL_SEND_MS = 150, volSentAt = 0;
  function pushVol() {
    var wait = VOL_SEND_MS - (Date.now() - volSentAt);
    clearTimeout(volTimer);
    volTimer = null;
    if (wait <= 0) { volSentAt = Date.now(); sendVol(volLocal); return; }
    volTimer = setTimeout(function () { volTimer = null; volSentAt = Date.now(); sendVol(volLocal); }, wait);
  }
  function setVolLocal(v) {
    volLocal = Math.max(0, Math.min(100, v));
    volHoldUntil = Date.now() + 2500;
    paintVol();
    pushVol();
  }
  function bumpVol(dir) { setVolLocal(volSnap(volNow()) + dir * volStep); }
  // 点数字就地输入 (同弹幕偏移): 想直接定到 30% 不用点七八下
  function openVolEdit() {
    var b = document.getElementById('pb-vol-val');
    if (!b || volEditing) return;
    volEditing = true;
    var input = document.createElement('input');
    input.type = 'text';
    input.className = 'pb-vol-in';
    input.inputMode = 'numeric';
    input.value = String(Math.round(volNow()));
    input.setAttribute('aria-label', T('音量'));
    b.replaceWith(input);
    input.focus();
    input.select();
    function close(save) {
      if (!volEditing) return;
      volEditing = false;
      if (save) {
        var v = parseInt(input.value.replace(/[^0-9]/g, ''), 10);
        if (!isNaN(v)) { clearTimeout(volTimer); volTimer = null; volLocal = volSnap(v); volHoldUntil = Date.now() + 2500; volSentAt = Date.now(); sendVol(volLocal); }
      }
      var nb = document.createElement('b');
      nb.id = 'pb-vol-val';
      nb.setAttribute('data-vol', 'edit');
      nb.setAttribute('title', T('点一下输入，按住左右拖可调'));
      nb.textContent = Math.round(volNow()) + '%';
      input.replaceWith(nb);
    }
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); close(true); }
      else if (e.key === 'Escape') close(false);
    });
    input.addEventListener('blur', function () { close(true); });
  }
  // 倍速条: 同音量那套 (拖动中节流着发, 松手后一小会儿不让轮询把它拽回去)
  var spDragging = false, spHoldUntil = 0, spTimer = null, spLocal = null;
  function sendSpeed(v) { post('api/player/control', { action: 'speed', v: String(v / 100) }).catch(fail); }
  // 100 -> "1.0x", 125 -> "1.25x" (整十的省掉末位 0, 读数才不会一会儿三位一会儿四位)
  function speedText(v) {
    var t = (v / 100).toFixed(2);
    if (t.charAt(t.length - 1) === '0') t = t.slice(0, -1);
    return t + 'x';
  }
  /*
   * 音量控件两种样式 (设置 - 本机偏好 - 「播放卡的音量控件」, 见 LOOK_SCRIPT 的 VOL_STYLES):
   * - buttons (默认): 同弹幕时间偏移那套 —— 一下一档、按住数字左右拖微调、点数字直接输。滑条在手机上
   *   一碰就从头滑到尾, 音量一下子拉满 (用户 2026-09-20);
   * - slider: 原来那根;
   * - hidden: 不显示 (电视音量多半用遥控器调)。
   */
  function volRowHtml() {
    var style = window.volStyle ? window.volStyle() : 'buttons';
    if (style === 'hidden') return '';
    var mute = '<button type="button" id="pb-mute" aria-label="' + T('静音') + '"></button>';
    if (style === 'slider') {
      return '<div class="pb-vol" id="pb-volrow" hidden>' + mute +
        '<input type="range" id="pb-vol" min="0" max="100" step="1" value="100" aria-label="' + T('音量') + '">' +
        '<span class="pb-vol-hi">' + window.ICONS.volUp + '</span></div>';
    }
    return '<div class="pb-vol pb-vol-btns" id="pb-volrow" hidden>' + mute +
      '<button type="button" data-vol="-1" aria-label="' + T('调低音量') + '">' + window.ICONS.minus + '</button>' +
      '<b id="pb-vol-val" data-vol="edit" title="' + T('点一下输入，按住左右拖可调') + '"></b>' +
      '<button type="button" data-vol="1" aria-label="' + T('调高音量') + '">' + window.ICONS.plus + '</button></div>';
  }
  // 设置里换了样式要立刻重建这张卡 (整块重画才会换掉控件)
  window.repaintPlayerCard = function () {
    var box = document.getElementById('player-controls');
    if (box) box.innerHTML = '';
    paint();
  };
  function paint() {
    var box = document.getElementById('player-controls');
    if (!box) return;
    if (!box.firstChild) {
      box.innerHTML =
        '<div class="progress"><input type="range" id="pb-range" min="0" max="0" step="1000" value="0" aria-label="' + T('播放进度') + '">' +
        '<div class="time pb-time-link" id="pb-time"></div></div>' +
        '<div class="pb-ctrls"><button data-act="back" aria-label="' + T('后退 10 秒') + '">' + window.ICONS.back10 + '</button>' +
        '<button data-act="toggle" class="pb-main" id="pb-toggle" aria-label="' + T('播放') + '"></button>' +
        '<button data-act="forward" aria-label="' + T('前进 10 秒') + '">' + window.ICONS.fwd10 + '</button>' +
        '<button data-act="skip" class="pb-skip" aria-label="' + T('前进 85 秒（跳过片头）') + '" title="' + T('前进 85 秒（跳过片头）') + '">' + window.ICONS.fwd85 + '</button></div>' +
        volRowHtml() +
        '<div class="pb-speed" id="pb-speedrow" hidden><button type="button" id="pb-speed-reset" aria-label="' + T('恢复正常倍速') + '" title="' + T('恢复正常倍速') + '">' + window.ICONS.speed + '</button>' +
        '<input type="range" id="pb-speed" min="25" max="400" step="25" value="100" aria-label="' + T('倍速') + '">' +
        '<span class="pb-speed-val" id="pb-speed-val"></span></div>' +
        '<div class="pb-system" id="pb-system-row" hidden><button type="button" id="pb-system" hidden></button>' +
        '<button type="button" id="pb-fs" hidden></button></div>';
    }
    // 正改着时间时卡片被整张重画了: 把输入框 (连同已输入的字) 补回去
    if (editing && !document.getElementById('pb-jump')) openEdit(editText, true);
    var p = pb || { playing: false, position: 0, duration: 0 };
    // 播放中给「暂停」图标, 暂停时给「播放」; 状态没变不重画 svg
    var tg = document.getElementById('pb-toggle'), icon = p.playing ? 'pause' : 'play';
    if (tg.getAttribute('data-icon') !== icon) {
      tg.setAttribute('data-icon', icon);
      tg.setAttribute('aria-label', p.playing ? T('暂停') : T('播放'));
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
        // 拖动中 / 刚松手 / 刚按过加减: 用手上的值 (这时卡片被整张重画, 新控件也不会跳回电视那份旧值)
        var held = (volDragging || Date.now() < volHoldUntil) && volLocal != null;
        var vp = Math.round(held ? volLocal : p.volume * 100);
        var vol = document.getElementById('pb-vol');
        if (vol) {
          if (vol.value !== String(vp)) vol.value = String(vp);
          vol.style.setProperty('--pct', vp + '%');
        }
        var vb = document.getElementById('pb-vol-val');
        if (vb && !volEditing) vb.textContent = vp + '%';
        // 一档多少跟电视走 (系统音量按档取整, 见 RemotePlayerHandle.playbackJson 的 volumeStep)
        if (p.volumeStep) volStep = Math.max(1, p.volumeStep * 100);
      }
      var mb = document.getElementById('pb-mute'), mi = p.muted ? 'volOff' : 'volLow';
      if (mb.getAttribute('data-icon') !== mi) {
        mb.setAttribute('data-icon', mi);
        mb.setAttribute('aria-label', p.muted ? T('取消静音') : T('静音'));
        mb.innerHTML = window.ICONS[mi];
      }
    }
    // 倍速: 同音量, 电视给了才显示 (取不到倍速能力的播放器不给).
    // 范围与档位都跟电视走 (speedMin/speedMax/speedStep 来自 TV_PLAYBACK_SPEED_RANGE 与 SLIDER_VALUE_STEP),
    // 这边不另抄一份常量 —— 抄了就会像以前那样悄悄偏掉 (电视 0.25x–4x 步进 0.25, 这里却是 0.5x–2.5x 步进 0.05).
    var sr = document.getElementById('pb-speedrow');
    if (sr) {
      var hasSpeed = p.speed != null;
      sr.hidden = !hasSpeed;
      if (hasSpeed) {
        var sl = document.getElementById('pb-speed');
        // 电视没给档位时退回 0.25 (与 SLIDER_VALUE_STEP 一致), 不要退回 1 —— 那会让滑条能停在电视产生不了的值上
        var st = Math.max(1, Math.round((p.speedStep != null ? p.speedStep : 0.25) * 100));
        // 端点向内取整到档位上, 保证每一格都落在电视的网格里
        var lo = Math.ceil(Math.round((p.speedMin != null ? p.speedMin : 0.25) * 100) / st) * st;
        var hi = Math.floor(Math.round((p.speedMax != null ? p.speedMax : 4) * 100) / st) * st;
        if (sl.step !== String(st)) sl.step = String(st);
        if (sl.min !== String(lo)) sl.min = String(lo);
        if (sl.max !== String(hi)) sl.max = String(hi);
        var heldSp = (spDragging || Date.now() < spHoldUntil) && spLocal != null;
        var sv = heldSp ? spLocal : Math.round(p.speed * 100);
        if (sl.value !== String(sv)) sl.value = String(sv);
        sl.style.setProperty('--pct', (hi > lo ? (sv - lo) * 100 / (hi - lo) : 0) + '%');
        document.getElementById('pb-speed-val').textContent = speedText(sv);
        // 不是 1 倍速时整行点亮, 一眼看得出来现在是变速的
        sr.classList.toggle('on', sv !== 100);
      }
    }
    var sb = document.getElementById('pb-system');
    if (sb) {
      sb.hidden = !(mediaSupported && !mediaAutoEnabled());
      sb.classList.toggle('on', mediaEnabled);
      sb.textContent = T(mediaEnabled ? '退出锁屏 / 控制中心' : '接入锁屏 / 控制中心');
    }
    var fsBtn = document.getElementById('pb-fs');
    if (fsBtn) {
      // 载体只在接入之后才有 —— 没接入时也把按钮显出来, 点了会先接入再全屏
      fsBtn.hidden = !carrierFullscreenCan();
      var fsReady = carrierFullscreenReady();
      fsBtn.disabled = !fsReady;
      fsBtn.classList.toggle('on', carrierPresenting());
      fsBtn.textContent = T(fsReady ? '全屏（可转小窗）' : '全屏（正在准备）');
    }
    var sysRow = document.getElementById('pb-system-row');
    if (sysRow) sysRow.hidden = (!sb || sb.hidden) && (!fsBtn || fsBtn.hidden);
    syncCarrierPlayback();
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
  /*
   * **拆控件要等它稳下来**。
   *
   * 切番剧 / 换集时服务端会有一小段 `available:false` (旧会话已注销、新会话还没注册,
   * 见 TvRemoteControl.registerPlayer)。原先这一下就把系统控件整个拆掉 —— 而拆了之后要重新
   * enable, enable 必须 play() 一个新载体; 页面在后台 / 锁屏时 iOS 不允许自动播放, play() 被拒,
   * catch 里又 disable —— 循环下去控件就永久空了。
   * 2026-09-20 真机: 切番剧时赶在加载完成前最小化网页, 控制中心剩个死壳; 日志里是 12 秒内
   * 四条 `carrier created: 1s placeholder` (拆了建、建不起来又拆)。
   *
   * 而且“电视不在播放页就拆控件”本身就跟设计意图矛盾: 见 registerMediaHandlers 里
   * seekbackward 的说明 —— 控件恰恰要在那阵子能用来把电视唤醒。
   *
   * 所以改成: 状态稳定持续 [DISABLE_SETTLE_MS] 才真的拆, 期间恢复就撤销。同
   * SwitchMediaOnPlayerErrorExtension 里「缓存从列表消失要稳定 2 秒才算被删除」的做法。
   * 拆之前控件显示成暂停 (updateMediaPlayback 已经把 background 当成“肯定没在播”), 不会误导。
   */
  var DISABLE_SETTLE_MS = 8000;
  var disableTimer = null;
  function scheduleDisableMediaSession() {
    if (disableTimer || (!mediaEnabled && !mediaCarrier)) return;
    disableTimer = setTimeout(function () {
      disableTimer = null;
      disableMediaSession(true);
    }, DISABLE_SETTLE_MS);
  }
  function cancelPendingDisable() {
    if (disableTimer) { clearTimeout(disableTimer); disableTimer = null; }
  }
  hooks.render.push(function (s) {
    mediaState = s;
    if (s.background) scheduleDisableMediaSession();
    else {
      cancelPendingDisable();
      updateMediaMetadata();
      tryAutoMediaSession();
      keepMediaAudioAlive();
    }
    paint();
  });
  hooks.unavailable.push(function () { mediaState = null; scheduleDisableMediaSession(); });
  hooks.playback.push(function (p) { pb = stateGuard(p); updateMediaPlayback(); paint(); });

  // 从控件发出的指令要走一个来回 (POST + 电视上真的执行 + 下一次轮询), 这中间回报的还是操作前的状态。锁屏时
  // 这一点格外要命: 页面的定时器被系统冻着, 操作后往往只拉得到那一瞬的状态, 之后再没有下一次轮询来纠正 ——
  // 于是拖完退回原处 (位置), 或者电视明明在播、控件上却是播放图标 (状态). 两样都压到电视确认为止,
  // 超过 5 秒就松手 (电视没听见, 或者用户自己在电视上动了).
  var seekPendingMs = -1;
  var seekPendingAt = 0;
  var playPending = null;
  var playPendingAt = 0;
  // 电视退出播放页那阵子收不到播放数据, 快进快退仍要有个位置可依 (见 seekBase)
  var lastKnownPosition = 0;
  var lastKnownDuration = 0;
  // 要盖住服务端那套唤醒 (打开播放页 + 等保留会话转回前台, 上限 8 秒) 再加上起播的时间. 短了的话窗口一到期
  // 就按"没在播"处理 —— 载体被 pause, 焦点交还, 卡片当场空掉 (用户 2026-09-18: 唤醒后过一会控件就没了).
  var PLAY_HOLD_MS = 12000;
  function seekBase() { return (pb && pb.position) || lastKnownPosition; }
  function stateGuard(p) {
    if (!p) return p;
    if (p.position > 0) lastKnownPosition = p.position;
    if (p.duration > 0) lastKnownDuration = p.duration;
    if (seekPendingMs >= 0) {
      if (Math.abs((p.position || 0) - seekPendingMs) < 2500 || Date.now() - seekPendingAt > 5000) seekPendingMs = -1;
      else p.position = seekPendingMs;
    }
    if (playPending !== null) {
      if (!!p.playing === playPending || Date.now() - playPendingAt > PLAY_HOLD_MS) playPending = null;
      else p.playing = playPending;
    }
    return p;
  }
  // 电视 seek 时会短暂报"没在播"(缓冲), 别把它当成用户暂停了
  function holdPlayState(playing) {
    playPending = playing;
    playPendingAt = Date.now();
  }

  function seek(ms) {
    seekPendingMs = ms;
    seekPendingAt = Date.now();
    if (pb) holdPlayState(!!pb.playing);
    if (pb) pb.position = ms;
    // 控件上的进度条只认 setPositionState, 不调这一下就要等下一次轮询 —— 而那一下又被 seekGuard 压着,
    // 表现成"按了快进快退, 控件上纹丝不动" (用户 2026-09-18).
    updateMediaPlayback();
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
    if (e.target.id === 'pb-speed') {
      spDragging = true;
      var st = e.target;
      spLocal = +st.value;
      var slo = +st.min, shi = +st.max;
      st.style.setProperty('--pct', (shi > slo ? (spLocal - slo) * 100 / (shi - slo) : 0) + '%');
      document.getElementById('pb-speed-val').textContent = speedText(spLocal);
      document.getElementById('pb-speedrow').classList.toggle('on', spLocal !== 100);
      if (!spTimer) spTimer = setTimeout(function () { spTimer = null; sendSpeed(+st.value); }, 150);
      return;
    }
    if (e.target.id !== 'pb-range') return;
    dragging = true;
    var d = pb && pb.duration;
    e.target.style.setProperty('--pct', (d ? Math.min(100, +e.target.value * 100 / d) : 0) + '%');
    document.getElementById('pb-time').textContent = T('跳到 {0}', fmt(+e.target.value)) + ' / ' + (d ? fmt(d) : '--:--');
  });
  // 音量条左边的喇叭: 静音 / 取消静音
  nowBox.addEventListener('click', function (e) {
    if (e.target.closest('#pb-system')) {
      if (mediaEnabled) disableMediaSession(false); else enableMediaSession();
      return;
    }
    if (e.target.closest('#pb-fs')) {
      if (!carrierFullscreenReady()) return;
      // 全屏要有载体在播, 所以没接入的先接入 (接入本身也要在这次手势里完成; createCarrier 会直接按
      // 这一集的长度建, 所以这一下进去的就是对的时间轴)
      if (!mediaEnabled) enableMediaSession();
      enterCarrierFullscreen();
      return;
    }
    if (e.target.closest('#pb-speed-reset')) {
      spDragging = false;
      spHoldUntil = Date.now() + 2000;
      spLocal = 100;
      sendSpeed(100);
      paint();
      return;
    }
    var vb = e.target.closest('[data-vol]');
    if (vb) {
      var d = vb.getAttribute('data-vol');
      // edit 那下由 endVolDrag 按「有没有真拖动」决定开不开输入框, 这里只当没有 pointer 事件时的兜底
      if (d === 'edit') { if (!volDragMoved) openVolEdit(); } else bumpVol(+d);
      return;
    }
    if (!e.target.closest('#pb-mute')) return;
    post('api/player/control', { action: 'mute' }).then(function () { poll(true); }).catch(fail);
  });
  /*
   * 按住音量数字左右拖: 每 VOL_DRAG_PX 像素一档 (档位跟电视走, 见 volStep)。
   * 没拖动就当轻点, 打开输入框 —— 与弹幕时间偏移同一套。
   */
  var VOL_DRAG_PX = 14;
  var volDragX = 0, volDragBase = 0, volDragMoved = false, volDragOn = false;
  nowBox.addEventListener('pointerdown', function (e) {
    var b = e.target.closest('b[data-vol="edit"]');
    if (!b || volEditing) return;
    volDragOn = true;
    volDragging = true;
    volDragX = e.clientX;
    volDragBase = volNow();
    volDragMoved = false;
    b.classList.add('dragging');
    try { b.setPointerCapture(e.pointerId); } catch (x) {}
  });
  nowBox.addEventListener('pointermove', function (e) {
    if (!volDragOn) return;
    var dx = e.clientX - volDragX;
    // 起手留一点余量: 手指落下时的轻微晃动不该算成调整
    if (!volDragMoved && Math.abs(dx) < 5) return;
    volDragMoved = true;
    var next = Math.max(0, Math.min(100, (Math.round(volDragBase / volStep) + Math.round(dx / VOL_DRAG_PX)) * volStep));
    if (next === volLocal) return;
    setVolLocal(next);
  });
  function endVolDrag() {
    if (!volDragOn) return;
    volDragOn = false;
    volDragging = false;
    volHoldUntil = Date.now() + 2000;
    var b = nowBox.querySelector('b[data-vol="edit"]');
    if (b) b.classList.remove('dragging');
    if (volDragMoved) {
      // 补一次最终值: 节流的定时器可能正押着最后那一档
      clearTimeout(volTimer);
      volTimer = null;
      volSentAt = Date.now();
      sendVol(volLocal);
    } else {
      // 没拖动 = 轻点, 就地开输入框 (click 那边的 edit 分支被 volEditing 挡掉, 不会开两次)
      openVolEdit();
    }
  }
  nowBox.addEventListener('pointerup', endVolDrag);
  nowBox.addEventListener('pointercancel', endVolDrag);
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
    if (e.target.id === 'pb-speed') {
      spDragging = false;
      spHoldUntil = Date.now() + 2000;
      clearTimeout(spTimer);
      spTimer = null;
      spLocal = +e.target.value;
      sendSpeed(spLocal);
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
      'placeholder="' + T('如 21:30') + '" aria-label="' + T('跳到的时间') + '"><button type="submit">' + T('跳转') + '</button></form>' + right;
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
    if (ms == null) { toast(T('时间格式不对，例如 21:30 或 1:05:10')); return; }
    if (pb && pb.duration && ms > pb.duration) { toast(T('超过片长了（{0}）', fmt(pb.duration))); return; }
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
    if (statsBox.open) { statsBody.innerHTML = '<p class="hint">' + T('正在读取…') + '</p>'; poll(true); }
  });
  hooks.playback.push(function (p) {
    if (!statsBox.open || !p.stats) return;
    statsBody.innerHTML = p.stats.length ? p.stats.map(function (r) {
      return '<div class="stats-row"><span>' + esc(r.k) + '</span><b>' + esc(r.v) + '</b></div>';
    }).join('') : '<p class="hint">' + T('正在读取…') + '</p>';
  });
  // 没有播放器 (只有「接下来播放」卡或什么都没有) 时不出这一区
  hooks.unavailable.push(function () { statsBox.hidden = true; });
  hooks.render.push(function () { statsBox.hidden = false; });
  document.getElementById('player-now').addEventListener('click', function (e) {
    var b = e.target.closest('[data-act]');
    if (!b) return;
    var act = b.getAttribute('data-act');
    // 跳片头: 按当前位置算, 走跟拖进度条同一条路 (服务端没有这个动作)
    if (act === 'skip') { seek(Math.max(0, (pb && pb.position || 0) + 85000)); return; }
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
    getJson('api/sources').then(function (d) { data = d; renderAdd(); renderList(); })
      .catch(function (e) {
        // 渲染抛异常也会落到这里, 被说成「读取失败」—— 把真正的错误发回电视才看得见
        if (window.clientLog) window.clientLog('sources load/render failed: ' + (e && (e.stack || e.message) || e));
        failRead();
      });
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
    return '<div class="row"><button type="button" class="ghost" data-act="' + cancelAct + '">' + T('取消') + '</button>' +
      '<button type="submit" class="primary">' + label + '</button></div>';
  }
  function copyText(ta) {
    ta.focus();
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) {}
    if (!ok && navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(ta.value); ok = true; }
    toast(ok ? T('已复制') : T('请长按文本框手动复制'));
  }

  // ---- JSON 编辑器 (RSS / 选择器源): 按 JSON 结构生成表单, 随时可切到源码 ----
  // 不用任何外部库: 网页是电视在局域网里发的, 手机未必连得上 CDN
  var NL = String.fromCharCode(10);
  var JE_LABELS = {
    name: T('名称'), description: T('描述'), iconUrl: T('图标地址'), tier: T('层级'), channelTiers: T('线路层级'),
    searchConfig: T('搜索配置'), searchUrl: T('搜索链接'), searchUseOnlyFirstWord: T('只用第一个词搜索'),
    searchRemoveSpecial: T('去掉特殊字符'), searchUseSubjectNamesCount: T('使用的条目名数量'), rawBaseUrl: T('基础地址'),
    requestInterval: T('请求间隔（毫秒）'), searchCacheTtl: T('搜索缓存时长（毫秒）'), subjectFormatId: T('条目格式'),
    channelFormatId: T('线路格式'), defaultResolution: T('默认分辨率'), defaultSubtitleLanguage: T('默认字幕语言'),
    onlySupportsPlayers: T('仅支持的播放器'), filterByEpisodeSort: T('按集数过滤'), filterBySubjectName: T('按条目名过滤'),
    selectMedia: T('选择媒体'), matchVideo: T('匹配视频')
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
    if (!keys.length) return '<p class="hint">' + T('（空）') + '</p>';
    return keys.map(function (k) { return jeField(k, obj[k], path.concat([k]), depth); }).join('');
  }
  function jeField(key, v, path, depth) {
    var label = jeLabel(key);
    if (Array.isArray(v)) {
      if (v.every(function (x) { return x === null || typeof x !== 'object'; })) {
        var elem = v.length && typeof v[0] === 'number' ? 'numlines' : 'lines';
        return '<label class="je-f"><span>' + label + T('（每行一个）') + '</span><textarea rows="' + Math.max(2, v.length + 1) + '"' +
          jeAttr(path, elem, key) + '>' + esc(v.join(NL)) + '</textarea></label>';
      }
      return '<details class="je-group"><summary>' + label + T('（{0} 项）', v.length) + '</summary><div class="je-body">' +
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
      (v === null ? ' placeholder="' + T('（空）') + '"' : '') + jeAttr(path, type, key) + '></label>';
  }
  function jsonEditorHtml() {
    return '<div class="je-tabs"><button type="button" class="je-tab on" data-je="form">' + T('表单') + '</button>' +
      '<button type="button" class="je-tab" data-je="raw">' + T('源码') + '</button>' +
      '<button type="button" class="je-fmt" data-je="fmt" hidden>' + T('整理格式') + '</button></div>' +
      '<div class="je-form"></div><textarea name="text" rows="16" spellcheck="false" class="je-raw" hidden></textarea>';
  }
  function jeRender(form, value) {
    form.jeValue = value;
    var box = form.querySelector('.je-form');
    var args = value && value.arguments;
    if (!args || typeof args !== 'object') {
      box.innerHTML = '<p class="hint">' + T('这份 JSON 里没有 arguments，只能在源码里改') + '</p>';
      return;
    }
    box.innerHTML = '<p class="hint">' + T('类型 {0} · 版本 {1}', esc(value.factoryId), esc(value.version)) + '</p>' +
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
        if (t === '' || isNaN(v)) throw new Error(T('「{0}」需要填数字', el.getAttribute('data-label')));
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
      catch (err) { toast(T('JSON 格式有误：') + err.message); }
      return;
    }
    if (want === jeMode(form)) return;
    if (want === 'raw') {
      if (syncJson(form)) jeShow(form, 'raw');
    } else {
      try { jeRender(form, JSON.parse(raw.value)); jeShow(form, 'form'); }
      catch (err) { toast(T('JSON 格式有误，先在源码里改好：') + err.message); }
    }
  });

  // ---- 导入 ----
  // 服务端按 Content-Length 卡 64 KiB (LanHttpServer), post() 又是 application/x-www-form-urlencoded,
  // 所以按编码后的长度算, 并留出余量
  var IMPORT_LIMIT = 56 * 1024;

  function importBodyBytes(text) {
    return ('text=' + encodeURIComponent(text)).length;
  }

  /** 认服务端那三种形状, 取出里面的数据源数组; 不是列表返回 null. */
  function importList(text) {
    var root;
    try { root = JSON.parse(text); } catch (e) { return null; }
    if (Array.isArray(root)) return root;
    if (!root || typeof root !== 'object') return null;
    if (Array.isArray(root.mediaSources)) return root.mediaSources;
    var box = root.exportedMediaSourceDataList;
    if (box && Array.isArray(box.mediaSources)) return box.mediaSources;
    return null;
  }

  function importCount(text) {
    var list = importList(text);
    if (list) return list.length;
    return importBodyBytes(text) > 0 && text.trim().charAt(0) === '{' ? 1 : 0;
  }

  /**
   * 超过单次上限的内容按条拆成多份, 每份都能单独导入. 拆不动 (不是列表, 或单个数据源本身就超限) 返回 null,
   * 由调用方提示; 不超限则原样一份发出去 (解析不了的也交给服务端报错, 这里不抢着判)
   */
  function importChunks(text) {
    if (importBodyBytes(text) <= IMPORT_LIMIT) return [text];
    var list = importList(text);
    if (!list || !list.length) return null;
    var chunks = [], current = [];
    for (var i = 0; i < list.length; i++) {
      var one = JSON.stringify([list[i]]);
      if (importBodyBytes(one) > IMPORT_LIMIT) return null;
      if (current.length && importBodyBytes(JSON.stringify(current.concat([list[i]]))) > IMPORT_LIMIT) {
        chunks.push(JSON.stringify(current));
        current = [];
      }
      current.push(list[i]);
    }
    if (current.length) chunks.push(JSON.stringify(current));
    return chunks;
  }

  /** 拆分 + 逐批发 + 汇总: 选文件与粘贴都走这条. */
  function importSourcesJson(text) {
    var chunks = importChunks(text);
    if (!chunks) {
      return Promise.resolve({ ok: false, message: T('内容太大了，请按数据源拆成几份分别导入') });
    }
    if (chunks.length > 1) toast(T('内容较大，分 {0} 批导入', chunks.length));
    return postImport(chunks);
  }
  /** 多份时逐份发 (不并发, 电视那边只有一个线程池), 汇总服务端报回来的数量. */
  function postImport(chunks) {
    if (chunks.length === 1) return post('api/sources/import', { text: chunks[0] });
    var added = 0, failed = 0, last = '';
    var chain = Promise.resolve();
    chunks.forEach(function (body) {
      chain = chain.then(function () {
        return post('api/sources/import', { text: body }).then(function (r) {
          if (r.ok) added += (r.added || 0); else failed++;
          if (r.message) last = r.message;
        });
      });
    });
    return chain.then(function () {
      if (added <= 0) return { ok: false, message: last || T('没有导入任何数据源') };
      var message = T('已导入 {0} 个数据源', added);
      if (failed) message += T('；{0} 批失败', failed);
      return { ok: true, message: message };
    });
  }

  function readImportFile(input, file) {
    var form = input.form;
    var reader = new FileReader();
    reader.onload = function () {
      var text = String(reader.result == null ? '' : reader.result);
      form.elements.text.value = text;
      var n = importCount(text);
      toast(n > 0 ? T('已读取 {0}（{1} 个数据源）', file.name, n) : T('已读取 {0}', file.name));
    };
    reader.onerror = function () { toast(T('读取文件失败')); };
    reader.readAsText(file);
  }

  // ---- 新增 ----
  function renderAdd() {
    var t = data.templates || [];
    var key = JSON.stringify(t);
    if (key === lastTemplates) return;
    lastTemplates = key;
    // 「导入 JSON」作为下拉里的第一项: 原先是单独一个折叠框, 没有说明, 不容易看出是干什么的
    addBox.innerHTML =
      '<label class="f"><span>' + T('新增数据源') + '</span><select id="src-new"><option value="">' + T('选择类型…') + '</option>' +
      '<option value="import">' + T('导入 JSON（选文件或粘贴）') + '</option>' +
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
    if (e.target.getAttribute && e.target.getAttribute('data-imp') === 'file') {
      var file = e.target.files && e.target.files[0];
      if (file) readImportFile(e.target, file);
      return;
    }
    if (e.target.id !== 'src-new') { if (e.target.form) applyVis(e.target.form); return; }
    var panel = document.getElementById('src-new-panel');
    if (e.target.value === 'import') {
      // id 沿用 src-import: 「粘贴即覆盖」认这个表单
      panel.innerHTML = '<form class="src-new-form" id="src-import" data-kind="import">' +
        '<p class="hint">' + T('粘贴别处复制的数据源配置（JSON）：单个、列表或订阅内容都可以，每个都新建为本地源。粘贴会整段替换框里原有的内容。') + '</p>' +
        '<label class="f"><span>' + T('选择 JSON 文件') + '</span>' +
        '<input type="file" accept=".json,application/json,text/plain" data-imp="file"></label>' +
        '<em class="imp-or">' + T('或者把内容粘贴到下面：') + '</em>' +
        '<textarea name="text" rows="8" spellcheck="false" placeholder="' + T('在这里粘贴 JSON') + '"></textarea>' +
        '<div class="row"><button type="button" class="ghost" data-act="cancel-new">' + T('取消') + '</button>' +
        '<button type="button" class="ghost" data-imp="clear">' + T('清空') + '</button><button type="submit" class="primary">' + T('导入') + '</button></div></form>';
      return;
    }
    var t = data.templates[+e.target.value];
    if (!t) { panel.innerHTML = ''; return; }
    var hint = t.description ? '<p class="hint">' + esc(t.description) + '</p>' : '';
    if (t.editor === 'json') {
      getJson('api/sources/template?factoryId=' + encodeURIComponent(t.factoryId)).then(function (r) {
        if (!r.ok) { toast(r.message); return; }
        panel.innerHTML = '<form class="src-new-form" data-kind="json">' + hint + jsonEditorHtml() +
          '<p class="hint">' + T('这是一份空白模板，在表单里填好即可；别处分享的配置可以切到「源码」整段粘贴。') + '</p>' +
          btns('cancel-new', T('添加')) + '</form>';
        initJsonEditor(panel.querySelector('form'), r.json);
      }).catch(failRead);
    } else {
      panel.innerHTML = '<form class="src-new-form" data-kind="params">' + hint + paramForm(t.params || []) +
        btns('cancel-new', T('添加')) + '</form>';
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
      var text = form.elements.text.value;
      if (!text.trim()) { toast(T('先选一个 JSON 文件，或把内容粘贴到框里')); return; }
      importSourcesJson(text).then(function (r) {
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
    toast(T('已用粘贴的内容替换原有内容'));
  });
  // ---- 列表 ----
  // ---- 多选 ----
  // 选中的源记 id 而不是下标: 批量操作中途会重新 load(), 下标会错位
  var picking = false, picked = {};
  function pickedSources() {
    return (data.sources || []).filter(function (s) { return picked[s.id]; });
  }
  function toolsHtml() {
    if (!picking) {
      return '<div class="src-tools"><button type="button" data-bulk="start">' + T('选择') + '</button>' +
        '<span class="hint">' + T('选择后可以批量启用、测试、导出或删除') + '</span></div>';
    }
    var n = pickedSources().length;
    var total = (data.sources || []).length;
    var off = n ? '' : ' disabled';
    return '<div class="src-tools picking">' +
      '<div class="src-bar">' +
        '<button type="button" data-bulk="all">' + (n >= total ? T('取消全选') : T('全选')) + '</button>' +
        '<span class="src-count">' + T('已选 {0} 个', n) + '</span>' +
        '<button type="button" data-bulk="done">' + T('完成') + '</button>' +
      '</div>' +
      '<div class="src-bar src-bar-ops">' +
        '<button type="button" data-bulk="enable"' + off + '>' + T('启用') + '</button>' +
        '<button type="button" data-bulk="disable"' + off + '>' + T('停用') + '</button>' +
        '<button type="button" data-bulk="test"' + off + '>' + T('测试') + '</button>' +
        '<button type="button" data-bulk="export"' + off + '>' + T('导出') + '</button>' +
        '<button type="button" class="src-danger" data-bulk="delete"' + off + '>' + T('删除') + '</button>' +
      '</div>' +
      '<span class="hint">' + T('测的是能不能连上站点，不代表一定搜得到资源') + '</span></div>' +
      '<div class="src-panel" id="src-bulk" hidden></div>';
  }
  function renderList() {
    var list = data.sources || [];
    if (!list.length) { box.innerHTML = '<p class="hint">' + T('还没有数据源') + '</p>'; return; }
    // 先量再改 class: 一改 class, CSS 立刻把每行的按钮藏了, 再去量就量到收缩后的高度
    var prevHeight = box.offsetHeight;
    box.className = picking ? 'picking' : '';
    box.innerHTML = toolsHtml() +
      list.map(function (s, i) {
      var fromSub = s.subscription != null;
      var tags = [s.kind, fromSub ? T('来自订阅') : ''].filter(Boolean).join(' · ');
      // 上移 / 下移: 每行都有的排序箭头, 只放图标; 其余动作 图标 + 文字
      var I = window.ICONS;
      var ord = '<div class="src-ord">' +
        '<button data-act="up" class="icb" aria-label="' + T('上移') + '" title="' + T('上移') + '"' + (i === 0 ? ' disabled' : '') + '>' + I.up + '</button>' +
        '<button data-act="down" class="icb" aria-label="' + T('下移') + '" title="' + T('下移') + '"' + (i === list.length - 1 ? ' disabled' : '') + '>' + I.down + '</button></div>';
      var b = '<button data-act="test" class="ic">' + I.plug + T('测试') + '</button>';
      if (s.editor !== 'none') b += '<button data-act="edit" class="ic">' + I.edit + T('编辑') + '</button>';
      if (fromSub) b += '<button data-act="copy" class="ic">' + I.copy + T('复制为本地源') + '</button>';
      if (s.exportable) b += '<button data-act="export" class="ic">' + I.share + T('导出') + '</button>';
      if (!fromSub) b += '<button data-act="delete" class="src-danger ic">' + I.trash + T('删除') + '</button>';
      return '<div class="src-item' + (s.enabled ? '' : ' off') + (picking && picked[s.id] ? ' picked' : '') +
        '" data-i="' + i + '" data-id="' + esc(s.id) + '">' +
        '<div class="src-top">' +
        (picking ? '<label class="src-pickbox"><input type="checkbox" data-act="pick"' + (picked[s.id] ? ' checked' : '') + '></label>' : '') +
        '<label class="src-sw"><input type="checkbox" data-act="enable"' + (s.enabled ? ' checked' : '') + '></label>' +
        window.srcIcon(s.id, s.name) +
        '<div class="src-name"><span class="t">' + esc(s.name) + '</span><small>' + esc(tags) + '</small></div>' + ord + '</div>' +
        (s.description ? '<div class="src-desc">' + esc(s.description) + '</div>' : '') +
        (fromSub ? '<div class="src-desc">' + T('订阅来的源会随订阅更新被覆盖，所以只能启用或停用；想改的话先「复制为本地源」。') + '</div>' : '') +
        '<div class="src-btns">' + b + '</div><div class="src-panel" hidden></div></div>';
    }).join('');
    applyMarquee();
    padBottom(prevHeight);
  }
  /** 名字真的放不下才滚: 溢出多少就滚多少, 写进 --mq 给动画用. */
  function applyMarquee() {
    var names = box.querySelectorAll('.src-name');
    for (var i = 0; i < names.length; i++) {
      var el = names[i], t = el.querySelector('.t');
      if (!t) continue;
      var over = t.scrollWidth - el.clientWidth;
      if (over > 4) {
        el.classList.add('mq');
        el.style.setProperty('--mq', (-over - 6) + 'px');
      }
    }
  }
  /*
   * 进选择模式会把每行的按钮整排藏掉, 列表矮一大截 (二十几个源就是上千像素)。停在列表底部时
   * 内容不够长, 浏览器会把滚动位置往回钳 —— 那是一段看得见的滚动。补一段等高的占位把总高度
   * 维持住, 滚动位置就不会被动。
   *
   * 除此之外**不主动改 scrollTop**: 布局瞬变无妨, 但只要动了滚动位置, 眼睛就会看到页面在滑。
   */
  function padBottom(prevHeight) {
    box.style.paddingBottom = '';
    var diff = prevHeight - box.offsetHeight;
    if (diff > 0) box.style.paddingBottom = diff + 'px';
  }
  /*
   * 批量操作: 全部用现有的单个接口一条条发, 服务端不需要另开批量接口。
   *
   * 顺序发而不是一次全发 (与批量测试不同): 启用 / 删除会改动列表本身, 并发时后发的请求
   * 可能基于已经陈旧的顺序。按钮上实时显示进度。
   */
  function runBulk(action, btn) {
    if (action === 'start') { picking = true; picked = {}; renderList(); return; }
    if (action === 'done') { picking = false; picked = {}; renderList(); return; }
    if (action === 'hide') { var hp = document.getElementById('src-bulk'); if (hp) hp.hidden = true; return; }
    if (action === 'copy') { copyText(document.querySelector('#src-bulk textarea')); return; }
    if (action === 'all') {
      var list = data.sources || [];
      var isAll = pickedSources().length >= list.length;
      picked = {};
      if (!isAll) list.forEach(function (x) { picked[x.id] = true; });
      renderList();
      return;
    }
    var xs = pickedSources();
    if (!xs.length) return;
    if (action === 'enable' || action === 'disable') {
      // 本来就是这个状态的跳过; 其余一次请求发完 —— 服务端一次改完, 数据源列表只重建一次
      // (逐个发的话每改一个, 电视上所有数据源实例都要重建一遍)
      var on = action === 'enable';
      var todo = xs.filter(function (x) { return !!x.enabled !== on; });
      var already = xs.length - todo.length;
      if (!todo.length) { toast(on ? T('选中的都已经启用了') : T('选中的都已经停用了')); return; }
      btn.disabled = true;
      post('api/sources/enable', { ids: todo.map(function (x) { return x.id; }).join('\n'), enabled: on ? '1' : '0' })
        .then(function (r) {
          if (r && r.ok === false) { toast(r.message); return; }
          toast(T('完成 {0} 个', todo.length) +
            (already ? (on ? T('，跳过 {0} 个已经启用的', already) : T('，跳过 {0} 个已经停用的', already)) : ''));
          picked = {};
          load();
        })
        .catch(fail)
        .then(function () { btn.disabled = false; });
    } else if (action === 'delete') {
      // 订阅来的源删不掉 (跟单行逻辑一致), 先挑出去免得一串失败
      var del = xs.filter(function (x) { return x.subscription == null; });
      var skipped = xs.length - del.length;
      if (!del.length) { toast(T('选中的都是订阅源，不能删除')); return; }
      // 列出具体名字: 只报个数的话, 选错了也看不出来, 而这一步不可撤销
      var show = del.slice(0, 10).map(function (x) { return '・' + x.name; }).join('\n');
      if (del.length > 10) show += '\n' + T('…以及其余 {0} 个', del.length - 10);
      if (!confirm(T('删除选中的 {0} 个数据源？此操作不可撤销。', del.length) + '\n\n' + show)) return;
      bulkStep(btn, del, function (x) { return post('api/sources/delete', { id: x.id }); }, skipped, true);
    } else if (action === 'test') {
      bulkTest(btn, xs);
    } else if (action === 'export') {
      bulkExport(btn, xs);
    }
  }
  /*
   * 批量测试同时发出去, 由浏览器自己限同域并发 —— 不在服务端并发, 那样一个慢站就能把整批拖住
   * (单个最多等 20 秒, 二十几个源一条条来要好几分钟)。
   * 测试是只读的, 不改动列表, 所以能并发; 启用 / 停用 / 删除那几种会改数据, 仍然一条条来 (见 bulkStep)。
   */
  function bulkTest(btn, list) {
    var items = box.querySelectorAll('.src-item');
    var all = data.sources || [];
    var jobs = list.map(function (x) {
      var item = items[all.indexOf(x)];
      return item ? testInto(item, x) : Promise.resolve(false);
    });
    if (!jobs.length) return;
    var label = btn.innerHTML, done = 0, ok = 0;
    btn.disabled = true;
    function tick() { btn.textContent = done + '/' + jobs.length; }
    tick();
    jobs.forEach(function (p) { p.then(function (r) { done++; if (r) ok++; tick(); }); });
    Promise.all(jobs).then(function () {
      btn.disabled = false;
      btn.innerHTML = label;
      toast(T('测试完成：{0} 个正常，{1} 个有问题', ok, jobs.length - ok));
    });
  }
  /** 一步一步跑完 [list]; [reload] = 完事后重拉列表 (改动了数据的那几种). */
  function bulkStep(btn, list, runOne, skipped, reload) {
    var label = btn.innerHTML, ok = 0, bad = 0;
    btn.disabled = true;
    (function step(i) {
      if (i >= list.length) {
        btn.disabled = false;
        btn.innerHTML = label;
        toast(T('完成 {0} 个', ok) +
          (bad ? T('，{0} 个失败', bad) : '') +
          (skipped ? T('，跳过 {0} 个订阅源', skipped) : ''));
        if (reload) { picked = {}; load(); }
        return;
      }
      btn.textContent = (i + 1) + '/' + list.length;
      Promise.resolve(runOne(list[i]))
        .then(function (r) { if (r === false || (r && r.ok === false)) bad++; else ok++; })
        .catch(function () { bad++; })
        .then(function () { step(i + 1); });
    })(0);
  }
  /**
   * 把选中的源合成一份顶层 JSON 数组 —— 导入端直接认这个形状 (见 `RemoteSources.parseExported`),
   * 所以导出的东西可以原样粘回「导入」。
   */
  function bulkExport(btn, list) {
    var ex = list.filter(function (x) { return x.exportable; });
    var skipped = list.length - ex.length;
    if (!ex.length) { toast(T('选中的数据源都不支持导出')); return; }
    var label = btn.innerHTML, out = [];
    btn.disabled = true;
    (function step(i) {
      if (i >= ex.length) {
        btn.disabled = false;
        btn.innerHTML = label;
        var p = document.getElementById('src-bulk');
        if (!p) return;
        p.innerHTML = '<p class="hint">' + T('已合成 {0} 个数据源的配置，可以直接用「导入」粘贴回去。', out.length) +
          (skipped ? T('（{0} 个不支持导出，已跳过）', skipped) : '') + '</p>' +
          '<textarea rows="10" readonly spellcheck="false"></textarea>' +
          '<div class="row"><button type="button" class="ghost" data-bulk="hide">' + T('收起') + '</button>' +
          '<button type="button" class="primary" data-bulk="copy">' + T('复制') + '</button></div>';
        p.querySelector('textarea').value = JSON.stringify(out, null, 2);
        p.hidden = false;
        return;
      }
      btn.textContent = (i + 1) + '/' + ex.length;
      getJson('api/sources/export?id=' + encodeURIComponent(ex[i].id))
        .then(function (r) {
          if (r.ok && r.json) {
            try { out.push(JSON.parse(r.json)); } catch (e) { /* 单个解析不了就跳过 */ }
          }
        })
        .catch(function () { /* 单个失败不中断整批 */ })
        .then(function () { step(i + 1); });
    })(0);
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
  /*
   * 「测试」: 同电视设置页数据源那一行的测试 (都是问 MediaSource.checkConnection)。
   *
   * 结果写在这一行下面而不是只弹 toast: 一个个试过去时 toast 一闪而过, 分不清哪条结果对应哪个源。
   * 测试期间按钮禁用并改字, 免得连点几次堆一串请求 (站点慢的时候一次要十几秒)。
   */
  function testSource(item, s, btn) {
    var label = btn.innerHTML;
    btn.disabled = true;
    btn.textContent = T('测试中');
    return testInto(item, s).then(function (ok) { btn.disabled = false; btn.innerHTML = label; return ok; });
  }
  /** 测一个源并把结果写在这一行下面; 不碰按钮, 批量测试时没有单行按钮可用. */
  function testInto(item, s) {
    var out = item.querySelector('.src-test');
    if (!out) {
      out = document.createElement('div');
      out.className = 'src-test';
      item.querySelector('.src-btns').insertAdjacentElement('afterend', out);
    }
    out.className = 'src-test';
    out.textContent = T('正在测试…');
    return post('api/sources/test', { id: s.id })
      .then(function (r) {
        out.className = 'src-test ' + (r.ok ? 'ok' : 'bad');
        out.textContent = r.message || (r.ok ? T('连接正常') : T('连接失败'));
        return !!r.ok;
      })
      .catch(function (e) {
        out.className = 'src-test bad';
        out.textContent = T('测试失败：{0}', (e && e.message) || e);
        return false;
      })
      .then(function (ok) { return ok; });
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
      var p = openPanel(item, '<form class="src-form" data-kind="params">' + paramForm(s.params || []) + btns('cancel', T('保存')) + '</form>');
      applyVis(p.querySelector('form'));
    } else if (s.editor === 'json') {
      withExport(s, function (text) {
        var p = openPanel(item, '<form class="src-form" data-kind="json">' + jsonEditorHtml() +
          '<p class="hint">' + T('可以在表单里逐项改，也可以切到「源码」整段换成别处复制来的同类型配置；保存前会校验格式。') + '</p>' +
          btns('cancel', T('保存')) + '</form>');
        initJsonEditor(p.querySelector('form'), text);
      });
    }
  }
  box.addEventListener('change', function (e) {
    if (e.target.getAttribute('data-act') === 'pick') {
      var p = itemOf(e.target);
      if (p) {
        if (e.target.checked) picked[p.s.id] = true; else delete picked[p.s.id];
        renderList();
      }
      return;
    }
    if (e.target.getAttribute('data-act') === 'enable') {
      var x = itemOf(e.target);
      act('api/sources/enable', { id: x.s.id, enabled: e.target.checked ? '1' : '0' });
    } else if (e.target.form) {
      applyVis(e.target.form);
    }
  });
  box.addEventListener('click', function (e) {
    var bulk = e.target.closest('button[data-bulk]');
    if (bulk) { runBulk(bulk.getAttribute('data-bulk'), bulk); return; }
    // 选择模式下点整行就切换选中 (手机上只点得到勾选框太难)。
    // 勾选框自己有 change 事件, 这里避开它, 否则一次点击会切两次。
    if (picking) {
      var row = e.target.closest('.src-item');
      if (row && !e.target.closest('.src-pickbox')) {
        var rs = (data.sources || [])[+row.getAttribute('data-i')];
        if (rs) {
          if (picked[rs.id]) delete picked[rs.id]; else picked[rs.id] = true;
          renderList();
        }
        return;
      }
    }
    var b = e.target.closest('button[data-act]');
    if (!b) return;
    var x = itemOf(b);
    if (!x) return;
    var a = b.getAttribute('data-act');
    if (a === 'test') { testSource(x.item, x.s, b); return; }
    if (a === 'up' || a === 'down') act('api/sources/move', { id: x.s.id, dir: a });
    else if (a === 'delete') { if (confirm(T('删除「{0}」？', x.s.name))) act('api/sources/delete', { id: x.s.id }); }
    else if (a === 'copy') act('api/sources/copy', { id: x.s.id });
    else if (a === 'edit') edit(x.item, x.s);
    else if (a === 'export') {
      withExport(x.s, function (text) {
        var p = openPanel(x.item, '<textarea rows="10" readonly spellcheck="false"></textarea>' +
          '<div class="row"><button type="button" class="ghost" data-act="cancel">' + T('收起') + '</button>' +
          '<button type="button" class="primary" data-act="copytext">' + T('复制') + '</button></div>');
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
    var h = '<div class="card sub-card"><div class="sub-head"><b>' + T('订阅') + '</b><small>' + T('在线数据源都来自订阅') + '</small>' +
      '<button type="button" class="sub-refresh ic" data-sub="refresh"' + (d.updating ? ' disabled' : '') + '>' +
      window.ICONS.refresh + (d.updating ? T('更新中…') : T('立即更新')) + '</button></div>';
    if (!items.length) h += '<p class="hint">' + T('还没有订阅，把订阅地址粘贴到下面添加') + '</p>';
    items.forEach(function (s) {
      h += '<div class="sub-item" data-lp="' + esc(s.id) + '"><span class="sel-mark" aria-hidden="true"></span><div class="sub-url">' + esc(s.url) + '</div>' +
        '<div class="sub-status' + (s.failed ? ' bad' : '') + '">' + esc(s.status) + '</div>' +
        '<div class="sub-meta"><span>' + esc(s.period) + '</span>' +
        '<button type="button" class="sub-del icb" data-sub="delete" data-id="' + esc(s.id) + '" aria-label="' + T('删除订阅') + '" title="' + T('删除订阅') + '">' +
        window.ICONS.trash + '</button></div></div>';
    });
    h += '<form class="sub-add"><input type="text" name="url" inputmode="url" autocomplete="off" spellcheck="false" ' +
      'placeholder="' + T('粘贴订阅地址 https://…') + '"><button type="submit" class="primary">' + T('添加') + '</button></form></div>';
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
      ask: function (n) { return T('删除选中的 {0} 个订阅？它们带来的数据源会一起删除。', n); },
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
    } else if (confirm(T('删除这个订阅？它带来的数据源会一起删除。'))) {
      post('api/sources/subs/delete', { id: b.getAttribute('data-id') }).then(function (r) {
        toast(r.message);
        if (r.ok) { load(); if (window.loadSources) window.loadSources(); }
      }).catch(fail);
    }
  });
})();
""".trimIndent()

/**
 * 「设置」标签 (见 RemoteSettings): 代理 (模式 / 地址 / 账号, 保存与测试连接)、Bangumi 连接方式 (自带镜像清单、登录是否经过镜像与自建地址)
 * 与 BT 额外 tracker. 只在切到本标签时
 * 拉一次, 不轮询 —— 表单正在填, 重画会冲掉. 密码框不回显, 留空 = 不改.
 */
private val SETTINGS_SCRIPT = """
(function () {
  var proxyBox = document.getElementById('set-proxy');
  var bgmBox = document.getElementById('set-bangumi');
  var tmdbBox = document.getElementById('set-tmdb');
  var trBox = document.getElementById('set-trackers');
  var dfBox = document.getElementById('set-dmfilter');
  var frontBox = document.getElementById('set-front');
  var keepBox = document.getElementById('set-keep');
  // 退出 Ani 后保留 Web 控制台 (见 TvRemoteControl.keepAliveOnExit): 默认关; 开着时电视上按返回退出 Ani, 手机还能连
  function renderKeep(k) {
    if (!k) { keepBox.innerHTML = ''; return; }
    keepBox.innerHTML = '<div class="card set-card"><div class="set-title">' + T('后台保持连接') + '</div>' +
      '<label class="toggle"><input type="checkbox" data-keep' + (k.enabled ? ' checked' : '') + '>' + T('电视休眠或离开 Izuko 后仍保持连接') + '</label>' +
      '<p class="hint">' + T('开启后，Izuko 会继续在后台运行，并占用少量内存。配合「从手机打开 Izuko」，电视休眠或退出 Izuko 后，也可以从手机重新打开。') +
      T('关闭后，电视休眠或退出 Izuko 就会断开，需要先在电视上打开 Izuko 才能连接。') + '</p></div>';
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
  var MODES = [['DISABLED', T('不使用')], ['SYSTEM', T('跟随系统')], ['CUSTOM', T('自定义')]];
  var BGM_MODES = [['AUTO', T('官方连不上时用镜像')], ['MIRROR', T('用镜像')], ['DIRECT', T('只连官方')], ['CUSTOM', T('用我自己的镜像')]];
  function bgmUsesMirrors(mode) { return mode === 'AUTO' || mode === 'MIRROR'; }
  // Bangumi 连接方式 (同设置页那一组): 「官方连不上时用镜像」用自带清单, 不用填; 自建地址才要输入.
  // 推荐的是上面的代理 (直连官方). 自带镜像是第三方反代, 默认不带登录, 勾「登录与收藏同步也经过镜像」要先确认风险;
  // 自建地址会带登录, 所以那一档只提示填自己的服务器, 不推荐第三方
  function bgmCredHint(on) {
    return on ? T('你的登录凭证与收藏数据会经过镜像，风险由你自行承担。')
      : T('镜像不带登录：登录与收藏同步仍只走官方地址，第三方镜像看不到你的账号。');
  }
  // 上次读到的 Bangumi 连接方式 (含电视是否登录着), 保存前判断要不要先问
  var bgmSaved = null;
  function renderBangumi(b) {
    b = b || { mode: 'AUTO', custom: '', mirrors: [], allowCredentials: false };
    bgmSaved = b;
    var mirrors = (b.mirrors || []).map(esc).join(T('、'));
    bgmBox.innerHTML = '<form class="card set-card"><div class="set-title">' + T('Bangumi 连接方式') + '</div>' +
      '<p class="hint">' + T('中国大陆连不上 Bangumi 官方时，推荐优先设置上面的代理：直连官方，不经过任何第三方。也可以经镜像浏览。') + '</p><div class="pills">' +
      BGM_MODES.map(function (m) {
        return '<label><input type="radio" name="mode" value="' + m[0] + '"' + (b.mode === m[0] ? ' checked' : '') + '><span>' + m[1] + '</span></label>';
      }).join('') + '</div>' +
      '<p class="hint bgm-auto-only"' + (b.mode === 'AUTO' ? '' : ' hidden') + '>' + T('确定连不上官方后会自动改成「用镜像」，之后不再先试官方；需要时再改回这一档。') + '</p>' +
      '<div class="bgm-auto"' + (bgmUsesMirrors(b.mode) ? '' : ' hidden') + '>' +
      (mirrors ? '<p class="hint">' + T('自带镜像：{0}（清单每天自动更新，按顺序尝试），不用填地址。', mirrors) + '</p>' : '') +
      '<label class="toggle"><input type="checkbox" name="cred" value="1"' + (b.allowCredentials ? ' checked' : '') + '>' +
      T('登录与收藏同步也经过镜像') + '</label><p class="hint bgm-cred">' + bgmCredHint(b.allowCredentials) + '</p></div>' +
      '<div class="bgm-custom"' + (b.mode === 'CUSTOM' ? '' : ' hidden') + '>' +
      '<label class="f"><span>' + T('镜像地址') + '</span><input type="text" name="custom" inputmode="url" autocomplete="off" spellcheck="false" value="' +
      esc(b.custom) + '" placeholder="bangumi.example.com"><em>' +
      T('填你自己搭的反代的根域名，它要把 Bangumi 的各个子域（api、next、lain 等）原样转发。登录会经过它，所以只填自己的服务器。') +
      '</em></label></div>' +
      '<div class="row"><button type="submit" class="primary">' + T('保存') + '</button></div></form>';
  }
  bgmBox.addEventListener('change', function (e) {
    var t = e.target, f = t.form;
    if (t.name === 'cred') {
      // 勾上要先确认风险 (点保存才生效); 取消勾选不用问
      if (t.checked && !confirm(T('镜像由第三方运营。打开后，你的 Bangumi 登录凭证、收藏与观看进度都会经过镜像服务器，对方可以看到并使用你的账号，由此产生的风险由你自行承担。') +
          '\n\n' + T('更安全的做法是设置代理：应用直连 Bangumi 官方，不经过任何第三方。'))) t.checked = false;
      f.querySelector('.bgm-cred').textContent = bgmCredHint(t.checked);
      return;
    }
    if (t.name !== 'mode') return;
    f.querySelector('.bgm-auto-only').hidden = t.value !== 'AUTO';
    f.querySelector('.bgm-auto').hidden = !bgmUsesMirrors(t.value);
    f.querySelector('.bgm-custom').hidden = t.value !== 'CUSTOM';
  });
  bgmBox.addEventListener('submit', function (e) {
    e.preventDefault();
    var f = e.target, m = f.querySelector('input[name="mode"]:checked'), cred = !!(f.elements.cred && f.elements.cred.checked);
    function save() {
      post('api/settings/bangumi', new FormData(f)).then(function (r) { toast(r.message); if (r.ok) load(); }).catch(fail);
    }
    // 电视登录着 (同设置页, 见 BangumiMirrorConsent): 改成「用镜像」而凭证不经过镜像先问; 用着镜像时关掉凭证先提醒
    if (bgmSaved && bgmSaved.loggedIn && m && m.value === 'MIRROR' && !cred) {
      if (bgmSaved.mode !== 'MIRROR') { bgmAsk(f, save); return; }
      if (bgmSaved.allowCredentials &&
          !confirm(T('现在用的是镜像。关闭后，登录后的请求（收藏、进度，以及登录后浏览条目）只走官方，官方连不上时都会失败。'))) return;
    }
    save();
  });
  // 已登录改用镜像的三个选择: 允许凭证经过镜像 / 退出登录再切 / 不切. 登录后连浏览都带着令牌, 凭证不经过镜像时
  // 官方一连不上就什么都用不了, 所以不能不问就切
  function bgmAsk(f, save) {
    var old = document.getElementById('login-dlg');
    if (old) old.remove();
    var d = document.createElement('div');
    d.id = 'login-dlg';
    d.innerHTML = '<div class="link-dlg-box"><div class="link-dlg-t">' + T('已登录，改用镜像？') + '</div>' +
      '<p class="dlg-p">' + T('你已登录 Bangumi。登录后的请求（收藏、进度、评分，以及登录后浏览条目）默认只走官方，不经过第三方镜像，官方连不上时都会失败。') + '</p>' +
      '<p class="dlg-p risk">' + T('选「允许并改用镜像」后，你的 Bangumi 登录凭证、收藏与观看进度都会经过镜像服务器，对方可以看到并使用你的账号，由此产生的风险由你自行承担。不想交出账号的话，可以退出登录，只用镜像浏览。') + '</p>' +
      '<div class="dlg-acts"><button type="button" class="ghost" data-bgm-ask="allow">' + T('允许并改用镜像') + '</button>' +
      '<button type="button" class="ghost" data-bgm-ask="logout">' + T('退出登录并改用镜像') + '</button>' +
      '<button type="button" class="primary" data-bgm-ask="cancel">' + T('取消') + '</button></div></div>';
    d.addEventListener('click', function (e) {
      var b = e.target.closest('[data-bgm-ask]');
      if (!b && e.target !== d) return;
      var a = b ? b.getAttribute('data-bgm-ask') : 'cancel';
      d.remove();
      if (a === 'allow') {
        f.elements.cred.checked = true;
        f.querySelector('.bgm-cred').textContent = bgmCredHint(true);
        save();
      } else if (a === 'logout') {
        post('api/account/logout', {}).then(function (r) {
          if (!r.ok) { toast(r.message); return; }
          save();
          if (window.loadAccount) window.loadAccount();
        }).catch(fail);
      }
    });
    document.body.appendChild(d);
  }
  // TMDB 图片 (同设置页「背景图 (TMDB)」那一组): 自动选择 / 清单里的某个地址 / 自定义 / 不加载.
  // 清单在项目仓库里维护, 电视每天拉一次; 自定义才要输入
  function tmdbChoice(t) {
    return t.disabled ? 'off' : t.mode === 'FIXED' ? t.fixed : t.mode === 'CUSTOM' ? 'custom' : 'auto';
  }
  // 入口地址去掉 https://; 含 {path} 的模板只显示域名 (同 EndpointUrls.displayName)
  function tmdbHost(u) {
    var h = String(u).replace('https://', '');
    return String(u).indexOf('{path}') < 0 ? h : h.split('/')[0].split('?')[0];
  }
  function tmdbShow(f, c) {
    f.querySelector('.tmdb-auto').hidden = c !== 'auto';
    f.querySelector('.tmdb-fixed').hidden = c === 'auto' || c === 'custom' || c === 'off';
    f.querySelector('.tmdb-custom').hidden = c !== 'custom';
    f.querySelector('.tmdb-off').hidden = c !== 'off';
  }
  function renderTmdb(t) {
    t = t || { disabled: false, mode: 'AUTO', fixed: '', custom: '', hosts: [] };
    var hosts = t.hosts || [];
    var cur = tmdbChoice(t);
    var opts = [['auto', T('自动选择')]].concat(hosts.map(function (h) { return [h, tmdbHost(h)]; }));
    // 选定的那个后来被清单去掉了: 照样列出来
    if (t.mode === 'FIXED' && t.fixed && hosts.indexOf(t.fixed) < 0) opts.push([t.fixed, tmdbHost(t.fixed)]);
    opts.push(['custom', T('自定义')], ['off', T('不加载')]);
    tmdbBox.innerHTML = '<form class="card set-card"><div class="set-title">' + T('TMDB 图片') + '</div>' +
      '<p class="hint">' + T('背景图与剧照来自 TMDB。原站连不上时（例如中国移动的网络），自动选择会换到能用的地址。') + '</p><div class="pills">' +
      opts.map(function (o) {
        return '<label><input type="radio" name="choice" value="' + esc(o[0]) + '"' + (cur === o[0] ? ' checked' : '') + '><span>' + esc(o[1]) + '</span></label>';
      }).join('') + '</div>' +
      '<p class="hint tmdb-auto">' + T('按顺序用第一个连得上的：{0}（清单每天从项目仓库更新）', hosts.map(tmdbHost).map(esc).join(T('、'))) + '</p>' +
      '<p class="hint tmdb-fixed">' + T('只用这一个地址，连不上也不换') + '</p>' +
      '<p class="hint tmdb-off">' + T('背景图与剧照改用条目封面，不再请求 TMDB。') + '</p>' +
      '<div class="tmdb-custom"><label class="f"><span>' + T('自定义地址') + '</span><input type="text" name="custom" inputmode="url" autocomplete="off" spellcheck="false" value="' +
      esc(t.custom) + '" placeholder="https://img.example.com"><em>' +
      esc(T('与原站内容相同的地址，图片路径会接在后面；要把路径放进参数的代理，写成含 {path} 的模板，如 {0}', 'https://wsrv.nl/?url=image.tmdb.org{path}')) +
      '</em></label></div>' +
      '<div class="row"><button type="submit" class="primary">' + T('保存') + '</button></div></form>';
    tmdbShow(tmdbBox.querySelector('form'), cur);
  }
  tmdbBox.addEventListener('change', function (e) {
    var t = e.target;
    if (t.name === 'choice') tmdbShow(t.form, t.value);
  });
  tmdbBox.addEventListener('submit', function (e) {
    e.preventDefault();
    post('api/settings/tmdb-images', new FormData(e.target)).then(function (r) { toast(r.message); if (r.ok) load(); }).catch(fail);
  });
  // 切到电视前台 (见 TvRemoteControl.frontState): 默认关; 开了还要在电视上授权一次「显示在其他应用的上层」.
  // 授权后回到本标签会重新拉一次 (load), 状态跟着更新
  function renderFront(f) {
    if (!f) { frontBox.innerHTML = ''; return; }
    // 授过权的 (以前开过又关了) 不再说「首次开启时需要授权」
    var st = !f.needsPermission ? T('无需额外授权')
      : f.granted ? T('已授权')
      : !f.enabled ? T('首次开启时，需要在电视上允许 Izuko TV「显示在其他应用的上层」。')
      : T('尚未授权。Izuko 显示在电视上时会直接打开授权页；否则请在 30 分钟内回到 Izuko。') +
        T('也可在电视设置中为 Izuko TV 开启「显示在其他应用的上层」。');
    frontBox.innerHTML = '<div class="card set-card"><div class="set-title">' + T('从手机打开 Izuko') + '</div>' +
      '<label class="toggle"><input type="checkbox" data-front' + (f.enabled ? ' checked' : '') + '>' + T('允许从手机打开电视上的 Izuko') + '</label>' +
      '<p class="hint">' + T('开启后，在手机上搜索或点播时，电视会自动打开 Izuko；同时开启「后台保持连接」时，电视休眠也会先唤醒。') +
      T('关闭后，Izuko 仍在后台时，搜索和点播仍会发送到电视，但需要手动打开 Izuko 才能看到。') + '</p>' +
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
    proxyBox.innerHTML = '<form class="card set-card"><div class="set-title">' + T('代理') + '</div><div class="pills">' +
      MODES.map(function (m) {
        return '<label><input type="radio" name="mode" value="' + m[0] + '"' + (p.mode === m[0] ? ' checked' : '') + '><span>' + m[1] + '</span></label>';
      }).join('') + '</div>' +
      '<div class="set-custom"' + (p.mode === 'CUSTOM' ? '' : ' hidden') + '>' +
      '<label class="f"><span>' + T('代理地址') + '</span><input type="text" name="url" inputmode="url" autocomplete="off" spellcheck="false" value="' +
      esc(p.url) + '" placeholder="http://192.168.1.2:7890"><em>' + T('支持 http:// 与 socks5://') + '</em></label>' +
      '<label class="f"><span>' + T('用户名（可选）') + '</span><input type="text" name="username" autocomplete="off" value="' + esc(p.username) + '"></label>' +
      '<label class="f"><span>' + T('密码（可选）') + '</span><input type="password" name="password" autocomplete="new-password" placeholder="' +
      (p.hasPassword ? T('已设置，留空则不改') : '') + '"></label></div>' +
      '<p class="hint set-sys"' + (p.mode === 'SYSTEM' ? '' : ' hidden') + '>' + T('电视上通常取不到系统代理，这一档一般等于不使用代理；要走代理请选「自定义」。') + '</p>' +
      '<div class="row"><button type="button" class="ghost" data-set="test">' + T('测试连接') + '</button><button type="submit" class="primary">' + T('保存') + '</button></div>' +
      '<div class="set-test"></div></form>';
    trBox.innerHTML = '<form class="card set-card"><div class="set-title">' + T('BT 额外 Tracker') + '</div>' +
      '<p class="hint">' + T('每行一个，BT 下载开始前与内置 tracker 一起添加。') + '</p>' +
      '<textarea name="text" rows="6" spellcheck="false" placeholder="udp://tracker.example.com:1337/announce">' + esc(d.trackers || '') + '</textarea>' +
      '<div class="row"><button type="submit" class="primary">' + T('保存') + '</button></div></form>';
    renderBangumi(d.bangumi);
    renderTmdb(d.tmdbImages);
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
    dfBox.innerHTML = '<div class="card set-card"><div class="set-title">' + T('弹幕屏蔽词') + '</div>' +
      '<label class="toggle"><input type="checkbox" data-df="switch"' + (df.enabled ? ' checked' : '') + '>' + T('启用屏蔽（关掉后下面的规则都不生效）') + '</label>' +
      (df.items.length ? df.items.map(function (x) {
        return '<div class="df-item"><label class="src-sw"><input type="checkbox" data-df="toggle" data-id="' + esc(x.id) + '"' +
          (x.on ? ' checked' : '') + '></label><code>' + esc(x.regex) + '</code>' +
          '<button type="button" class="sub-del icb" data-df="delete" data-id="' + esc(x.id) + '" aria-label="' + T('删除') + '" title="' + T('删除') + '">' +
          window.ICONS.trash + '</button></div>';
      }).join('') : '<p class="hint">' + T('还没有屏蔽词') + '</p>') +
      '<form class="sub-add" id="df-add"><input type="text" name="regex" autocomplete="off" placeholder="' + T('要屏蔽的词，支持正则') + '">' +
      '<button type="submit" class="primary">' + T('添加') + '</button></form>' +
      '<p class="hint">' + T('改完立即生效，正在播放的弹幕会马上按新规则重新过滤。') + '</p></div>';
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
    out.innerHTML = '<p class="hint">' + T('正在测试，最多要十几秒…（按已保存的设置测）') + '</p>';
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
  function fmtShift(ms) { return (ms > 0 ? '+' : '') + T('{0} 秒', (ms / 1000).toFixed(1)); }
  /*
   * 时间偏移: 手上先变, 停手才发。
   *
   * 原来每按一下就 POST 一次、跟着还拉一次状态, 再把整块面板重画 —— 连按时按钮在手指底下被换掉,
   * 于是既容易误触又像没反应 (用户 2026-09-19)。现在按键只改本地值 + 就地改那个数字,
   * 停手 [SHIFT_SEND_DELAY] 毫秒才发一次; 电视确认后本地值让位给服务端的。
   */
  var SHIFT_SEND_DELAY = 350;
  var shiftLocal = {}, shiftTimer = {}, shiftEditing = null;
  function shiftNow(sv, serverMs) { return shiftLocal[sv] != null ? shiftLocal[sv] : (serverMs || 0); }
  function sendShift(sv) {
    var ms = shiftLocal[sv];
    if (ms == null) return;
    post('api/player/danmaku/shift', { service: sv, ms: String(ms) })
      .then(function (r) {
        if (r.message) toast(r.message);
        // 电视认下了才交还给服务端值; 这中间又按了的话以后来的那次为准
        if (shiftLocal[sv] === ms) delete shiftLocal[sv];
        poll(true);
      })
      .catch(fail);
  }
  // 只换那个数字, 不动整块 (整块重画正是"按钮在脚下消失"的来源)
  function paintShift(sv) {
    var b = dmBody.querySelector('b[data-sv="' + sv + '"]');
    if (b) b.textContent = fmtShift(shiftNow(sv, shifts[sv]));
  }
  function bumpShift(sv, delta) {
    shiftLocal[sv] = delta === 'reset' ? 0 : shiftNow(sv, shifts[sv]) + Number(delta);
    paintShift(sv);
    clearTimeout(shiftTimer[sv]);
    shiftTimer[sv] = setTimeout(function () { sendShift(sv); }, SHIFT_SEND_DELAY);
  }
  /*
   * 数字上按住左右拖 = 连续调偏移。连按那条路在手机上怎么优化都难受 (按钮小、双击还会放大页面),
   * 拖一下顶几十次点按, 而且看着数字走、松手才发一次 (用户 2026-09-19)。
   * 每 DRAG_PX_PER_STEP 像素动 0.1 秒; 没拖动就当轻点, 打开输入框。
   */
  var DRAG_PX_PER_STEP = 12;
  var dragSv = null, dragStartX = 0, dragBase = 0, dragMoved = false;
  dmBody.addEventListener('pointerdown', function (e) {
    var b = e.target.closest('b[data-sv]');
    if (!b || shiftEditing) return;
    dragSv = b.getAttribute('data-sv');
    dragStartX = e.clientX;
    dragBase = shiftNow(dragSv, shifts[dragSv]);
    dragMoved = false;
    b.classList.add('dragging');
    try { b.setPointerCapture(e.pointerId); } catch (x) {}
  });
  dmBody.addEventListener('pointermove', function (e) {
    if (dragSv == null) return;
    var dx = e.clientX - dragStartX;
    // 起手留一点余量: 手指落下时的轻微晃动不该算成调整
    if (!dragMoved && Math.abs(dx) < 5) return;
    dragMoved = true;
    var next = dragBase + Math.round(dx / DRAG_PX_PER_STEP) * 100;
    if (next === shiftLocal[dragSv]) return;
    shiftLocal[dragSv] = next;
    paintShift(dragSv);
  });
  function endDrag() {
    if (dragSv == null) return;
    var sv = dragSv;
    dragSv = null;
    var b = dmBody.querySelector('b[data-sv="' + sv + '"]');
    if (b) b.classList.remove('dragging');
    if (dragMoved) {
      clearTimeout(shiftTimer[sv]);
      sendShift(sv);
    } else {
      // 没拖动 = 轻点, 打开输入框 (click 那边的 edit 分支被 openShiftEdit 自己挡掉, 不会开两次)
      openShiftEdit(sv);
    }
  }
  dmBody.addEventListener('pointerup', endDrag);
  dmBody.addEventListener('pointercancel', endDrag);
  // 点数字: 就地变输入框 (同播放进度那里点时间直接改的做法)
  function openShiftEdit(sv) {
    if (shiftEditing === sv) return;
    var b = dmBody.querySelector('b[data-sv="' + sv + '"]');
    if (!b) return;
    shiftEditing = sv;
    var secs = (shiftNow(sv, shifts[sv]) / 1000).toFixed(1);
    var input = document.createElement('input');
    input.type = 'text';
    input.className = 'dm-shift-in';
    input.inputMode = 'decimal';
    input.value = secs;
    input.setAttribute('aria-label', T('时间偏移'));
    b.replaceWith(input);
    input.focus();
    input.select();
    function close(save) {
      if (shiftEditing !== sv) return;
      shiftEditing = null;
      if (save) {
        var v = parseFloat(input.value.replace('＋', '+').replace('。', '.'));
        if (!isNaN(v)) {
          clearTimeout(shiftTimer[sv]);
          shiftLocal[sv] = Math.round(v * 10) * 100;
          sendShift(sv);
        }
      }
      var nb = document.createElement('b');
      nb.setAttribute('data-dm', 'edit');
      nb.setAttribute('data-sv', sv);
      nb.textContent = fmtShift(shiftNow(sv, shifts[sv]));
      input.replaceWith(nb);
    }
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); close(true); }
      else if (e.key === 'Escape') close(false);
    });
    input.addEventListener('blur', function () { close(true); });
  }
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
      dmSum.textContent = d.enabled ? (d.loading && !d.sources.length ? T('加载中') : T('{0} 条', total)) : T('已关闭');
      var h = '<label class="toggle"><input type="checkbox" data-dm="all"' + (d.enabled ? ' checked' : '') + '>' + T('显示弹幕') + '</label>';
      if (!d.sources.length) h += '<p class="hint">' + (d.loading ? T('正在加载弹幕…') : T('这一集没有找到弹幕')) + '</p>';
      d.sources.forEach(function (x) {
        h += '<div class="dm-src' + (x.on ? '' : ' off') + '"><div class="dm-top">' +
          '<label class="src-sw"><input type="checkbox" data-dm="src" data-sv="' + esc(x.service) + '"' + (x.on ? ' checked' : '') + '></label>' +
          '<div class="dm-name">' + esc(x.name) + '<small>' + T('{0} 条', x.count) + '</small></div></div>' +
          '<div class="dm-how">' + esc(x.method) + (x.matched ? T('：') + esc(x.matched) : '') + '</div>' +
          // 只给 ±1: 更细的调整交给拖动 (0.1 秒一档), 这一行在 iPhone 上本来就挤到换行了
          '<div class="dm-shift"><span>' + T('时间偏移') + '</span>' + shiftBtn(x.service, -1000, '-1') +
          // 数字本身就是输入入口: 点一下就地变成输入框, 想调几秒直接打, 不用连按十几下
          '<b data-dm="edit" data-sv="' + esc(x.service) + '" title="' + T('点一下输入，按住左右拖可调') + '">' + fmtShift(x.shift) + '</b>' +
          shiftBtn(x.service, 1000, '+1') +
          // 这串 HTML **只反映电视上的值**: 掺进手上还没发出去的值, 每按一下 HTML 就变一次, 整块被重画,
          // 按钮又在手指底下被换掉了。手上的值走 paintShift 单独刷那个数字。
          (x.shift ? shiftBtn(x.service, 'reset', T('归零')) : '') + '</div></div>';
      });
      h += '<p class="hint">' + T('偏移为正数时弹幕晚出现。点数字可以直接输入，按住数字左右拖动能微调（0.1 秒一档）。各源开关与偏移只对这次播放有效。') + '</p>';
      if (d.canMatch) h += '<button type="button" class="ghost wide" data-dm="match">' + T('弹幕对不上？手动匹配（弹弹play）') + '</button>';
      // 正在就地输入 / 正拖着时不重画: 会把输入框连同光标、或拖动中的那个数字一起换掉
      if (h !== lastDm && !shiftEditing && dragSv == null) {
        dmBody.innerHTML = h;
        lastDm = h;
        // 重画用的是电视上的值, 手上还没落地的那些补回去
        Object.keys(shiftLocal).forEach(paintShift);
      }
    }
    var t = s.tracks || {}, th = '';
    if (t.audio && t.audio.items.length > 1) th += trackSelect('audio', T('音轨'), t.audio, T('自动'));
    if (t.subs && t.subs.items.length) th += trackSelect('sub', T('字幕'), t.subs, T('关闭'));
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
    var b = e.target.closest('[data-dm]');
    if (!b) return;
    var k = b.getAttribute('data-dm');
    if (k === 'shift') {
      bumpShift(b.getAttribute('data-sv'), b.getAttribute('data-d'));
    } else if (k === 'edit') {
      openShiftEdit(b.getAttribute('data-sv'));
    } else if (k === 'match') {
      dmMatch.innerHTML = '<form class="dm-form"><input type="text" name="q" autocomplete="off" placeholder="' + T('番剧名') + '" value="' +
        esc(title) + '"><button type="submit" class="primary">' + T('搜索') + '</button></form><div class="dm-list" id="dm-results"></div>' +
        '<div class="row"><button type="button" class="ghost" data-mt="cancel">' + T('取消手动匹配') + '</button></div>';
    }
  });
  function results() { return document.getElementById('dm-results'); }
  function hint(text) { results().innerHTML = '<p class="hint">' + text + '</p>'; }
  dmMatch.addEventListener('submit', function (e) {
    e.preventDefault();
    var q = e.target.elements.q.value.trim();
    if (!q) return;
    e.target.elements.q.blur();
    hint(T('正在搜索…'));
    post('api/player/danmaku/search', { q: q }).then(function (r) {
      if (!r.ok) { hint(esc(r.message)); return; }
      results().innerHTML = r.items.length ? '<p class="hint">' + T('选一个条目') + '</p>' + r.items.map(function (x) {
        return '<button type="button" data-mt="subject" data-id="' + esc(x.id) + '" data-name="' + esc(x.name) + '">' + esc(x.name) + '</button>';
      }).join('') : '<p class="hint">' + T('没有搜到，换个名字试试') + '</p>';
    }).catch(fail);
  });
  dmMatch.addEventListener('click', function (e) {
    var b = e.target.closest('[data-mt]');
    if (!b) return;
    var k = b.getAttribute('data-mt');
    if (k === 'cancel') { dmMatch.innerHTML = ''; return; }
    if (k === 'subject') {
      picked = { sid: b.getAttribute('data-id'), sname: b.getAttribute('data-name') };
      hint(T('正在加载剧集…'));
      post('api/player/danmaku/episodes', picked).then(function (r) {
        if (!r.ok) { hint(esc(r.message)); return; }
        results().innerHTML = '<p class="hint">' + esc(picked.sname) + T('：选一集') + '</p>' + r.items.map(function (x, i) {
          return '<button type="button" data-mt="episode" data-id="' + esc(x.id) + '" data-name="' + esc(x.name) + '"' +
            (i === r.suggested ? ' class="sug"' : '') + '>' + esc(x.name) + '</button>';
        }).join('');
        var sug = results().querySelector('.sug');
        if (sug) results().scrollTop = sug.offsetTop - results().offsetTop - 60;
      }).catch(fail);
    } else if (k === 'episode' && picked) {
      hint(T('正在加载弹幕…'));
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
  var subject = null, subjectTitle = '', view = 'eps', ep = null, timer = null, lastData = null, epFails = 0;
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
    epFails = 0;
    lastData = null;
    titleEl.textContent = T('缓存 · {0}', subjectTitle);
    setHtml(sb, '<p class="hint">' + T('正在读取剧集…') + '</p>');
    pollEpisodes();
  }
  function pollEpisodes() {
    stop();
    if (sheet.hidden || view !== 'eps') return;
    window.getJson('api/cache?subject=' + subject).then(function (d) {
      if (view !== 'eps') return;
      epFails = 0;
      renderEpisodes(d);
      timer = setTimeout(pollEpisodes, 2000);
    }).catch(function () {
      // 电视没回应时别一直停在「正在读取剧集…」: 连着两次都没读到就说一声, 后台照旧重试
      epFails++;
      if (epFails >= 2 && view === 'eps' && !lastData) setHtml(sb, '<p class="hint">' + T('电视没有响应，正在重试…') + '</p>');
      timer = setTimeout(pollEpisodes, 4000);
    });
  }
  function pickedIds() { return Object.keys(picked).filter(function (k) { return picked[k]; }); }
  function renderEpisodes(d) {
    lastData = d;
    if (!d.ok) { setHtml(sb, '<p class="hint">' + esc(d.message) + '</p>'); return; }
    if (d.title) { subjectTitle = d.title; titleEl.textContent = T('缓存 · {0}', d.title); }
    var b = d.batch, running = !!(b && b.running), h = '';
    // BT 服务冷启动要十几秒, 这期间每一集的状态都不会动 —— 不说一句会以为点了没反应
    if (d.btStarting) h += '<div class="now-status busy"><b>' + T('正在启动 BT 服务') + '</b><span>' + T('第一次要十几秒，之后会自动开始下载') + '</span></div>';
    // 电视上没打开 Ani 时 BT 服务根本不会起 (上游的省电策略), 缓存会一直排队 —— 必须说明白, 否则就是"点了没反应"
    // 顶上那条「不在前台」被这个全屏面板盖住了, 所以这里再给一个入口 (同 api/tv/front, 没开 / 没授权时电视会在回话里说清楚)
    else if (d.tvBackground) h += '<div class="now-status error"><b>' + T('电视上没有打开 Izuko') + '</b><span>' + T('已经记下了，要在电视上打开 Izuko 才会开始下载') + '</span></div>' +
      '<button type="button" class="ghost wide cache-front">' + T('打开 Izuko') + '</button>';
    if (running) {
      h += '<div class="now-status busy"><b>' + T('自动缓存中') + '</b><span>' + b.done + ' / ' + b.total + (b.current ? T('：') + esc(b.current) : '') + '</span></div>' +
        '<button type="button" class="ghost wide cache-cancel">' + T('取消自动缓存') + '</button>';
    } else if (b && b.failures.length) {
      h += '<div class="now-status error"><b>' + T('{0} 集没能自动缓存', b.failures.length) + '</b><span>' + T('原因写在对应那一集下面，可以点「选资源」自己挑') + '</span></div>';
    }
    // 已缓存的合集还覆盖着的集 (同电视缓存页: 点一集直接用合集, 不用再挑); 一次全部补上走自动批量 (它先找合集)
    var packIds = d.episodes.filter(function (x) { return x.status === 'none' && x.pack && inScope(x); }).map(function (x) { return x.id; });
    if (packIds.length && !running) {
      h += '<div class="now-status ready"><b>' + T('合集里还有 {0} 集没缓存', packIds.length) + '</b>' + (d.packTitle ? '<span>' + esc(d.packTitle) + '</span>' : '') + '</div>' +
        '<button type="button" class="ghost wide cache-packall" data-packall="' + packIds.join(',') + '">' + T('全部用合集缓存（{0} 集）', packIds.length) + '</button>';
    }
    h += '<p class="hint">' + (d.free ? T('电视剩余空间') + ' ' + esc(d.free) + T('。') : '') +
      T('勾选几集后点最下面的按钮自动缓存：有已缓存的合集先用合集，') + esc(d.autoHint || T('否则按你的数据源偏好自动挑')) +
      T('；也可以对某一集点「选资源」自己挑。') + '</p>';
    // 有没缓存的特别篇时才说全选的范围 (记在这台手机上, 见 inScope), 顺带说去哪改
    if (d.episodes.some(function (x) { return x.status === 'none' && x.sp; })) {
      h += '<p class="hint">' + (pickAllSp() ? T('「全选」会同时选择正片和特别篇。可在「设置 → 本机偏好」中更改。')
        : T('「全选」默认只选择正片。特别篇需要手动选择，可在「设置 → 本机偏好」中更改。')) + '</p>';
    }
    d.episodes.forEach(function (x) {
      var size = x.size ? ' · ' + x.size : '';
      var st = x.status === 'cached' ? ['ok', T('已缓存') + size] : x.status === 'caching'
        // 进度满了还是 caching = 文件已经下完, 在等做种达标 (或 10 分钟没有上传活动) 才会标成已完成,
        // 见 TorrentMediaCacheEngine.subscribeStats. 这时再显示「缓存中 100%」会让人以为卡住了.
        ? ['run', (x.progress >= 100 ? T('已下完 · 做种中') : T('缓存中 {0}%', x.progress)) + size]
        : x.error ? ['bad', x.error] : x.pack ? ['', T('未缓存 · 已缓存的合集里有这一集')] : ['', T('未缓存')];
      var free = x.status === 'none';
      if (!free) delete picked[x.id];
      h += '<div class="cache-ep"><label class="src-sw"><input type="checkbox" data-pick="' + x.id + '"' +
        (picked[x.id] ? ' checked' : '') + (free ? '' : ' disabled') + '></label>' +
        '<div class="n">' + (x.watched ? '✓ ' : '') + esc(x.label) + '<div class="st ' + st[0] + '">' + esc(st[1]) + '</div></div>' +
        (free && x.pack ? '<button type="button" class="cache-pack" data-pack="' + x.id + '">' + T('用合集') + '</button>' : '') +
        (free ? '<button type="button" data-ep="' + x.id + '" data-label="' + esc(x.label) + '">' + T('选资源') + '</button>' : '') + '</div>';
    });
    var n = pickedIds().length;
    // 全选只管还没缓存的集 (其余的勾选框本来就是灰的), 默认只管正片 (见 inScope, 有没选上的特别篇时写明「全选正片」);
    // 都勾上了就变「全不选」(连手动勾的特别篇一起清)
    var freeIds = d.episodes.filter(function (x) { return x.status === 'none' && inScope(x); }).map(function (x) { return String(x.id); });
    var spLeft = d.episodes.some(function (x) { return x.status === 'none' && !inScope(x); });
    var allOn = freeIds.length > 0 && freeIds.every(function (id) { return picked[id]; });
    h += '<div class="cache-bar"><button type="button" class="ghost cache-all" data-pickall="' + (allOn ? '0' : '1') + '"' +
      (freeIds.length && !running ? '' : ' disabled') + '>' + (allOn ? T('取消全选') : spLeft ? T('全选正片') : T('全选')) + '</button>' +
      '<button type="button" class="primary wide" id="cache-auto"' + (n && !running ? '' : ' disabled') + '>' +
      (running ? T('自动缓存进行中…') : n ? T('自动挑资源缓存选中的 {0} 集', n) : T('先勾选要缓存的剧集')) + '</button></div>';
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
    setHtml(sb, '<div id="cc-names"></div><div id="cc-chips"></div><div id="cc-filters"></div><div id="cc-list"><p class="hint">' + T('正在查找资源…') + '</p></div>');
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
    var h = '<label class="sel"><span>' + label + '</span><select data-cf="' + key + '"><option value="">' + T('全部') + '</option>';
    var has = false;
    (list || []).forEach(function (o) {
      if (o.value === cur) has = true;
      h += '<option value="' + esc(o.value) + '"' + (o.value === cur ? ' selected' : '') + '>' + esc(o.label) + T('（{0}）', o.count) + '</option>';
    });
    if (cur && !has) h += '<option value="' + esc(cur) + '" selected>' + esc(cur) + T('（{0}）', 0) + '</option>';
    return h + '</select></label>';
  }
  // 数据源胶囊 (同播放器标签): 点一个只看这个源, 再点一次或点「全部」取消; 纯本地筛选, 就地重画
  function ccChips(d) {
    var h = '<div class="chips"><button type="button" class="chip' + (ccSrc ? '' : ' on') + '" data-cc="">' + T('全部') + '</button>';
    d.sources.forEach(function (x) {
      var n = x.state === 'loading' ? '…' : x.state === 'captcha' ? T('需验证') : x.state === 'failed' ? T('失败') : x.state === 'limited' ? T('限流') : x.count;
      h += '<button type="button" class="chip ' + x.state + (ccSrc === x.id ? ' on' : '') + '" data-cc="' + esc(x.id) + '">' +
        esc(x.name) + ' ' + n + '</button>';
    });
    return h + '</div>';
  }
  // 「搜索名」(见 RemoteCache.names): 数据源按这些名字搜, 改了按番记住 (同播放器的编辑查询请求). 动过表单就不再跟着轮询重画,
  // 免得打字时被冲掉; 保存 / 恢复成功后清掉标记重画
  function ccNames(d) {
    var box = document.getElementById('cc-names');
    if (!box || d.primary == null || box.getAttribute('data-dirty') === '1') return;
    var key = JSON.stringify([d.primary, d.others, d.sort, d.ep, d.edited]);
    if (box.getAttribute('data-key') === key) return;
    box.setAttribute('data-key', key);
    var open = !!box.querySelector('details[open]');
    box.innerHTML = '<details class="card req"' + (open ? ' open' : '') + '><summary>' + T('搜索名与集数') + '<small>' + esc(d.primary) +
      (d.edited ? T('（已修改）') : '') + '</small></summary><form id="cc-names-form">' +
      '<label class="f"><span>' + T('主搜索名') + '</span><input type="text" name="primary" autocomplete="off" value="' + esc(d.primary) + '"></label>' +
      '<label class="f"><span>' + T('次要搜索名（每行一个）') + '</span><textarea name="others" rows="3">' + esc((d.others || []).join('\n')) + '</textarea></label>' +
      '<p class="hint">' + T('数据源按这些名字搜索。改过的名字会记住，这部番以后缓存和播放都用它。') + '</p>' +
      '<label class="f"><span>' + T('系列内剧集序号') + '</span><input type="text" name="sort" inputmode="decimal" autocomplete="off" value="' +
      esc(d.sort || '') + '"><em>' + T('假设有两季，分别有 12 集，则第二季的第一集为 13') + '</em></label>' +
      '<label class="f"><span>' + T('条目内序号') + '</span><input type="text" name="ep" inputmode="decimal" autocomplete="off" value="' +
      esc(d.ep || '') + '"><em>' + T('在当前季度内的序号，例如第二季的第一集为 01') + '</em></label>' +
      '<p class="hint">' + T('资源必须至少匹配以上两种集数中的一种。集数只影响这一集、不会记住；BT 合集里的文件编号和 Bangumi 不同时（比如第二季从 13 开始），要改这里才能下对文件。') + '</p>' +
      '<div class="row"><button type="button" class="ghost" data-names="reset"' + (d.edited ? '' : ' disabled') + '>' + T('恢复默认') + '</button>' +
      '<button type="submit" class="primary">' + T('保存并刷新') + '</button></div></form></details>';
    window.wireNameSwap(document.getElementById('cc-names-form'));
  }
  function saveNames(data, btn) {
    btn.disabled = true;
    data.subject = String(subject);
    data.episode = String(ep);
    post('api/cache/names', data).then(function (r) {
      btn.disabled = false;
      toast(r.message);
      var box = document.getElementById('cc-names');
      if (!r.ok || !box) return;
      box.removeAttribute('data-dirty');
      box.removeAttribute('data-key');
      pollCandidates();
    }).catch(function () { btn.disabled = false; window.fail(); });
  }
  sb.addEventListener('input', function (e) {
    var box = document.getElementById('cc-names');
    if (box && e.target.closest('#cc-names-form')) box.setAttribute('data-dirty', '1');
  });
  sb.addEventListener('submit', function (e) {
    var form = e.target.closest('#cc-names-form');
    if (!form) return;
    e.preventDefault();
    saveNames({ primary: form.elements.primary.value, others: form.elements.others.value, sort: form.elements.sort.value, ep: form.elements.ep.value },
      form.querySelector('button[type="submit"]'));
  });
  sb.addEventListener('click', function (e) {
    var b = e.target.closest('[data-names="reset"]');
    if (b && !b.disabled) saveNames({ reset: '1' }, b);
  });
  function renderCandidates(d) {
    var list = document.getElementById('cc-list'), fbox = document.getElementById('cc-filters'), cbox = document.getElementById('cc-chips');
    if (!list) return;
    if (!d.ok) { setHtml(list, '<p class="hint">' + esc(d.message) + '</p>'); return; }
    lastCands = d;
    ccNames(d);
    // 选中的源已经不在了 (比如被停用): 回到「全部」
    if (ccSrc && !d.sources.some(function (x) { return x.id === ccSrc; }) && !d.groups.some(function (g) { return g.id === ccSrc; })) {
      ccSrc = null;
      ccFull = false;
    }
    setHtml(cbox, ccChips(d));
    var f = d.filters || {};
    var one = ccSrc ? d.groups.filter(function (g) { return g.id === ccSrc; })[0] : null;
    var fh = '<div class="filters">' + dropdown('res', T('分辨率'), f.resolution, fRes) + dropdown('sub', T('字幕'), f.subtitle, fSub) +
      dropdown('all', T('字幕组'), f.alliance, fAll) + '</div>' +
      '<div class="toggles"><label class="toggle"><input type="checkbox" data-cf="ex"' + (fEx ? ' checked' : '') + '>' + T('显示被排除的资源') +
      (d.excludedCount ? T('（{0} 条）', d.excludedCount) : '') + '</label>' +
      // 「显示全部 N 条」: 只在点了某个胶囊、而且这个源确实没列全时出现 (勾着时一直显示, 好取消)
      (one && (ccFull || one.more > 0) ? '<label class="toggle"><input type="checkbox" data-cf="full"' + (ccFull ? ' checked' : '') +
        '>' + T('显示全部 {0} 条', one.total) + '</label>' : '') + '</div>';
    setHtml(fbox, fh);
    var groups = ccSrc ? d.groups.filter(function (g) { return g.id === ccSrc; }) : d.groups;
    var total = 0;
    groups.forEach(function (g) { total += g.total; });
    var bad = d.sources.filter(function (s) { return s.state === 'failed' || s.state === 'captcha' || s.state === 'limited'; }).length;
    var h = '<p class="hint">' + (d.loading ? T('正在查找资源… 已找到 {0} 条', total) : T('共 {0} 条', total)) +
      (bad && !ccSrc ? T('，{0} 个数据源没查到', bad) : '') + T('。点一条开始缓存。') + '</p>';
    if (!groups.length) {
      var src = ccSrc ? d.sources.filter(function (x) { return x.id === ccSrc; })[0] : null;
      var why = (fRes || fSub || fAll) ? T('没有符合筛选条件的资源，试试放宽筛选')
        : !ccSrc ? (d.loading ? '' : T('没有找到可以缓存的资源'))
        : !src || src.state === 'done' ? T('这个数据源没有匹配的资源')
        : src.state === 'loading' ? T('这个数据源还在搜索…')
        : src.state === 'captcha' ? T('这个数据源需要人机验证，请在电视上处理')
        : src.state === 'limited' ? T('这个数据源被限流了，稍后再试')
        : T('这个数据源搜索失败');
      if (why && !fEx && d.excludedCount) why += T('，也可以勾选「显示被排除的资源」看看');
      if (why) h += '<p class="hint">' + why + '</p>';
    }
    groups.forEach(function (g) {
      h += '<h2>' + (g.kind === 'cache' ? '' : window.srcIcon(g.id, g.name)) + esc(g.name) + ' <small>' + T('{0} 条', g.total) + '</small></h2><div class="list">';
      g.items.forEach(function (it) {
        var meta = [it.resolution, it.subtitles, it.alliance, it.size].filter(function (v, i, a) { return v && a.indexOf(v) === i; }).join(' · ');
        h += '<button type="button" class="item' + (it.excluded ? ' ex' : '') + (it.blocked ? ' blocked' : '') + '" data-mid="' + esc(it.id) +
          '" data-title="' + esc(it.title) + '"' + (it.blocked ? ' data-blocked="' + esc(it.reason || '') + '"' : '') + '>' +
          '<span class="t">' + esc(it.title) + '</span><span class="m">' + esc(meta) + '</span>' +
          (it.excluded ? '<span class="why">' + T('已排除：') + esc(it.reason || '') + '</span>' : '') + '</button>';
      });
      h += '</div>';
      if (g.more > 0) h += '<p class="hint">' + T('还有 {0} 条没列出，', g.more) +
        (ccSrc ? T('可以勾选上面的「显示全部」') : T('点上面这个数据源的胶囊后可以选择显示全部')) + '</p>';
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
    } else if (b.classList.contains('cache-front')) {
      b.disabled = true;
      post('api/tv/front', {}).then(function (r) {
        toast(r.message);
        b.disabled = false;
        pollEpisodes();
      }).catch(function () { b.disabled = false; fail(); });
    } else if (b.classList.contains('cache-cancel')) {
      // 误触了自动缓存: 停掉还没开始的; 已经建起来的那几集问一声要不要一起删 (否则只能一条条去缓存列表删)
      var bt = lastData && lastData.batch, made = bt && bt.created ? bt.created : 0;
      if (!confirm(made ? T('取消自动缓存？已经开始的 {0} 集会一并删除。', made) : T('取消自动缓存？'))) return;
      b.disabled = true;
      post('api/cache/auto-cancel', { remove: made ? '1' : '0' }).then(function (r) {
        toast(r.message);
        b.disabled = false;
        pollEpisodes();
        if (window.loadCaches) window.loadCaches();
      }).catch(function () { b.disabled = false; fail(); });
    } else if (b.hasAttribute('data-ep')) {
      showCandidates(+b.getAttribute('data-ep'), b.getAttribute('data-label'));
    } else if (b.hasAttribute('data-mid')) {
      if (b.hasAttribute('data-blocked')) { toast(T('不能选：') + b.getAttribute('data-blocked')); return; }
      if (!confirm(T('缓存这个资源？') + '\n' + b.getAttribute('data-title'))) return;
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
  var timer = null, busy = false, lastSum = '', lastList = '', fails = 0;
  // 任何全屏面板盖着 (缓存面板、挑番、使用说明) 都不刷新; 面板关上时 (sheetclose) 刷新一次
  function active() { return !tab.hidden && !window.sheets.any() && !document.hidden; }
  function load() {
    clearTimeout(timer);
    timer = null;
    if (!active() || busy) return;
    busy = true;
    fetch('api/caches').then(function (r) { return r.json(); }).then(function (d) {
      busy = false;
      fails = 0;
      render(d);
      if (active()) timer = setTimeout(load, 2000);
    }).catch(function (e) {
      busy = false;
      console.error(e);
      // 电视忙不过来 (服务端满负荷时直接回 503) 或连不上: 连着两次都没读到才说, 免得偶尔一次抖动就闪一下
      fails++;
      if (fails >= 2) setHtml(listBox, '<p class="hint">' + T('电视没有响应，正在重试…') + '</p>');
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
      s = '<div class="card"><div class="cl-free">' + T('电视剩余空间') + ' <b>' + esc(d.free || T('未知')) + '</b>' +
        (d.total ? '<small> ' + T('/ 共') + ' ' + esc(d.total) + '</small>' : '') + '</div>';
      if (d.count) s += '<div class="cl-line">' + T('{0} 集缓存，共 {1}', d.count, esc(d.used)) + '</div>';
      var run = [];
      if (d.downloading) run.push(T('{0} 集下载中', d.downloading) + (d.speed ? ' ↓ ' + esc(d.speed) : ''));
      if (d.pending) run.push(T('没下完的还差') + ' ' + esc(d.pending));
      if (run.length) s += '<div class="cl-line">' + run.join(' · ') + '</div>';
      if (d.btStarting) s += '<div class="cl-line">' + T('正在启动 BT 服务，第一次要十几秒…') + '</div>';
      else if (d.tvBackground) s += '<div class="cl-warn">' + T('电视上没有打开 Izuko，要打开后才会开始下载') + '</div>';
      if (d.lowSpace) s += '<div class="cl-warn">' + T('剩余空间不够把没下完的都下完') + '</div>';
      s += '</div>';
      if (!d.groups.length) {
        h = '<div class="empty"><p>' + T('电视上还没有缓存') + '</p><p class="hint">' + T('在「播放器」卡片右上角点「缓存」开始缓存') + '</p></div>';
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
              (g.id ? '<button type="button" class="res-play" data-sid="' + g.id + '" aria-label="' + T('播放') + '"><span class="play-glyph">' +
                window.ICONS.play + '</span></button>' : '') + '</div>';
            if (!g.id) return '<div class="sw flat cl-top-sw">' + top + '</div>';
            return window.swRow(
              '<button type="button" class="sw-btn cache" data-cache="' + g.id + '" data-title="' + esc(g.title) + '">' + window.ICONS.download + T('缓存') + '</button>',
              '<button type="button" class="sw-btn del" data-cdelall="' + g.id + '" data-title="' + esc(g.title) +
              '" data-count="' + g.items.length + '"' + (playingItem ? ' data-playing="' + esc(playingItem.label) + '"' : '') + '>' +
              window.ICONS.trash + T('全部删除') + '</button>', top, 'flat cl-top-sw');
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
    var act = x.st === 'run' ? '<button type="button" class="icb" data-cact="pause" data-cid="' + esc(x.cid) + '" aria-label="' + T('暂停下载') + '" title="' + T('暂停下载') + '">' + I.pause + '</button>'
      : x.st === 'paused' ? '<button type="button" class="icb" data-cact="resume" data-cid="' + esc(x.cid) + '" aria-label="' + T('继续下载') + '" title="' + T('继续下载') + '">' + I.play + '</button>' : '';
    var del = '<button type="button" class="sw-btn del" data-cact="delete" data-cid="' + esc(x.cid) + '" data-label="' + esc(g.title + ' ' + x.label) + '"' +
      (x.packShare ? ' data-share="' + x.packShare + '"' : '') + (x.playing ? ' data-playing="1"' : '') + '>' + I.trash + T('删除') + '</button>';
    return window.swRow('', del, '<div class="cl-ep sw-row" data-play="' + esc(x.cid) + '" data-lp="' + esc(x.cid) + '">' +
      '<span class="sel-mark" aria-hidden="true"></span><div class="n"><span class="cl-go">' + I.play + '</span>' + esc(x.label) +
      (x.playing ? '<span class="cl-tag play">' + T('正在播放') + '</span>' : '') + (x.pack ? '<span class="cl-tag">' + T('合集') + '</span>' : '') +
      '<div class="cl-st">' + bits.join(' · ') + '</div>' +
      (x.progress != null ? '<div class="cl-bar"><div style="width:' + x.progress + '%"></div></div>' : '') + '</div>' + act + '</div>', 'flat');
  }
  listBox.addEventListener('click', function (e) {
    // 多选中: 点一集只管勾选 (多选那边在捕获阶段接走), 番名那块等其余地方也不开详情 / 不删
    if (listBox.classList.contains('selecting')) return;
    var all = e.target.closest('[data-cdelall]');
    if (all) {
      if (all.disabled) return;
      var m = T('删除「{0}」的全部 {1} 集缓存？', all.getAttribute('data-title'), all.getAttribute('data-count'));
      var pl = all.getAttribute('data-playing');
      if (pl) m += '\n\n' + T('其中「{0}」正在播放，删除后需要重新选择数据源。', pl);
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
      var msg = T('删除「{0}」的缓存？', b.getAttribute('data-label'));
      // 同电视缓存页的删除确认: 正在播的那条多说一句
      if (b.getAttribute('data-playing')) msg += '\n\n' + T('这一集正在播放，删除后需要重新选择数据源。');
      // 合集的文件要等同一个种子的集都删了才一起回收 (见 TorrentMediaCacheEngine)
      var share = b.getAttribute('data-share');
      if (share) msg += '\n\n' + T('这一集来自合集，同一个种子还有 {0} 集缓存着：删掉它不会马上腾出空间，等这些集也都删了才一起回收。', share);
      // 不删了: 滑到底时行已经滑出去, 放回来
      if (!confirm(msg)) { window.swClose(b.closest('.sw')); return; }
    }
    b.disabled = true;
    post('api/caches/' + act, { id: b.getAttribute('data-cid') }).then(function (r) {
      toast(r.message);
      load();
    }).catch(function () { b.disabled = false; window.swClose(b.closest('.sw')); fail(); });
  });
  // 长按一集: 进多选 (那一集先勾上), 底部操作栏一次删几集 / 一次暂停或继续几集; 可以跨番勾
  listBox.addEventListener('longpress', function (e) {
    window.selStart({
      box: listBox,
      ask: function (n) { return T('删除选中的 {0} 集缓存？删除后需要重新缓存。', n); },
      del: function (ids) {
        return post('api/caches/delete', { ids: ids.join(',') }).then(function (r) {
          toast(r.message);
          load();
          return !!r.ok;
        });
      },
      setPaused: function (ids, paused) {
        return post('api/caches/' + (paused ? 'pause' : 'resume'), { ids: ids.join(',') }).then(function (r) {
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
 * 「播放器」标签里的「评论与评分」区 (见 RemotePlayerExtras): 收藏状态、我的评分 + 短评, 以及去 Bangumi 网页发表本集评论的入口.
 * 展开时才拉一次, 不随轮询重画 —— 表单正在填. 换了条目、电视刚登录上、或播放器状态里这部番的收藏与评分变了
 * (`collection` / `score`: 在电视上改了, 或登录后才取回真实状态) 时重读, 焦点在表单里时不打断. 评分的档位说法同 Bangumi.
 *
 * 版式参照 App 的评分弹窗 (居中的分数 + 评价词、一行十颗星、短评、仅自己可见) 和 Bangumi 的收藏盒 (五种状态横排):
 * 星星点一下定分、按住左右滑动改分. 没有「取消收藏」: Bangumi 没有这个操作, 收藏着的给一句提示改用「抛弃」.
 * 没收藏时评分区置灰 (服务端本来也拒), 没登录只放登录入口.
 * 本集评论不在这里写: Bangumi 发表评论要过人机验证, 只有它自己的网页过得去 —— 按钮在手机浏览器里打开这一集的页面,
 * 用户在那里登录着 Bangumi 就能直接写. 这一段不看电视登没登录; 地址随播放器状态轮询 (`commentLink`, 换集、改连接方式
 * 都会变), 变了就地换掉这一段, 不重读上面的表单.
 */
private val REVIEW_SCRIPT = """
(function () {
  var hooks = window.remoteHooks;
  var box = document.getElementById('cm-box'), body = document.getElementById('cm-body'), sum = document.getElementById('cm-sum');
  // link: 播放器状态里本集评论的地址 (见 commentSec); seen: 上次看到的电视上的收藏与评分
  var subject = null, loaded = false, link = null, seen = null;
  var TYPES = [['NOT_COLLECTED', T('未收藏')], ['WISH', T('想看')], ['DOING', T('在看')], ['DONE', T('看过')], ['ON_HOLD', T('搁置')], ['DROPPED', T('抛弃')]];
  // 评价词同 App 的评分弹窗; 1 分和 10 分带「请谨慎评价」, 也跟 App 一样标红
  var WORDS = ['', T('不忍直视（请谨慎评价）'), T('很差'), T('差'), T('较差'), T('不过不失'), T('还行'), T('推荐'), T('力荐'), T('神作'), T('超神作（请谨慎评价）')];
  // 星星同 App (Material 圆角星): .o 空心 / .f 实心, 由 svg 上的 on 切换
  var STAR = '<svg viewBox="0 0 24 24" aria-hidden="true">' +
    '<path class="o" d="M19.65 9.04l-4.84-.42-1.89-4.45c-.34-.81-1.5-.81-1.84 0L9.19 8.63l-4.83.41c-.88.07-1.24 1.17-.57 1.75l3.67 3.18-1.1 4.72c-.2.86.73 1.54 1.49 1.08l4.15-2.5 4.15 2.51c.76.46 1.69-.22 1.49-1.08l-1.1-4.73 3.67-3.18c.67-.58.32-1.68-.56-1.75zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z"/>' +
    '<path class="f" d="M12 17.27l4.15 2.51c.76.46 1.69-.22 1.49-1.08l-1.1-4.72 3.67-3.18c.67-.58.31-1.68-.57-1.75l-4.83-.41-1.89-4.46c-.34-.81-1.5-.81-1.84 0L9.19 8.63l-4.83.41c-.88.07-1.24 1.17-.57 1.75l3.67 3.18-1.1 4.72c-.2.86.73 1.54 1.49 1.08l4.15-2.5z"/></svg>';
  var drag = null;
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
    var mine = (s.collection || '') + '/' + (s.score || 0);
    if (s.subjectId !== subject) {
      subject = s.subjectId;
      seen = mine;
      loaded = false;
      sum.textContent = '';
      if (box.open) load(); else body.innerHTML = '';
    } else if (mine !== seen) {
      // 电视上这部番的收藏或评分变了 (在电视上改的, 或登录后取回了真实状态): 读过的就静默重读, 收着也重读 (标题行上的
      // 收藏状态要跟着变); 正在填表单 (焦点在里面) 时不打断
      seen = mine;
      if (loaded && !body.contains(document.activeElement)) load(true);
    }
    var c = s.commentLink || null;
    if (JSON.stringify(c) !== JSON.stringify(link)) {
      link = c;
      var sec = document.getElementById('cm-ep');
      if (sec) sec.outerHTML = commentSec();
    }
  });
  function typeLabel(t) {
    for (var i = 0; i < TYPES.length; i++) if (TYPES[i][0] === t) return TYPES[i][1];
    return t;
  }
  // quiet: 操作完重读时不先清成「正在读取」, 免得整块塌下去再撑开
  function load(quiet) {
    loaded = true;
    if (!quiet) body.innerHTML = '<p class="hint">' + T('正在读取…') + '</p>';
    window.getJson('api/player/review').then(render).catch(function () {
      // 不能停在「正在读取…」: 给出失败, 并允许收起再展开时重读
      loaded = false;
      if (quiet) failRead();
      else body.innerHTML = '<p class="hint">' + T('读取失败，收起再展开试试') + '</p>';
    });
  }
  function render(d) {
    if (!d.ok) { body.innerHTML = '<p class="hint">' + esc(d.message) + '</p>'; return; }
    sum.textContent = typeLabel(d.collection) + (d.score ? ' · ' + T('{0} 分', d.score) : '');
    // 没登录时收藏、评分做不了, 只放登录入口 (登录上以后 hooks.login 会重读); 去网页写评论不看电视登没登录
    if (!d.loggedIn) {
      body.innerHTML = '<div class="cm-login"><p class="hint cm-warn">' + T('还没登录：收藏和评分要先登录') + '</p>' +
        '<button type="button" class="primary wide" data-login="1">' + T('用手机登录 Bangumi') + '</button></div>' + commentSec();
      return;
    }
    var collected = d.collection !== 'NOT_COLLECTED', off = collected ? '' : ' disabled';
    var seg = '', stars = '';
    for (var i = 1; i < TYPES.length; i++) {
      seg += '<button type="button" data-ctype="' + TYPES[i][0] + '"' + (TYPES[i][0] === d.collection ? ' class="on"' : '') + '>' + TYPES[i][1] + '</button>';
    }
    for (var j = 0; j < 10; j++) stars += STAR;
    body.innerHTML =
      '<div class="cm-sec"><div class="cm-h"><span>' + T('收藏') + '</span></div>' +
        '<div class="seg cm-types">' + seg + '</div>' +
        (collected ? '<p class="hint cm-tip">' + T('Bangumi 不能取消收藏，不想看了就选「抛弃」') + '</p>' : '') + '</div>' +
      '<form id="cm-rate" class="cm-sec' + (collected ? '' : ' off') + '">' +
        '<div class="cm-h"><span>' + T('我的评分') + '</span><button type="button" class="cm-link" id="cm-clear">' + T('清除') + '</button></div>' +
        '<div class="cm-score" id="cm-score"><b></b><span></span></div>' +
        '<div class="cm-stars" id="cm-stars" role="slider" tabindex="0" aria-label="' + T('评分') + '" aria-valuemin="0" aria-valuemax="10">' + stars + '</div>' +
        '<input type="hidden" name="score" value="0">' +
        (collected ? '' : '<p class="hint cm-tip">' + T('先在上面选个收藏状态，才能评分') + '</p>') +
        '<textarea name="comment" rows="3" placeholder="' + T('写几句短评（可留空）') + '"' + off + '>' + esc(d.comment) + '</textarea>' +
        '<div class="cm-foot"><label class="toggle"><input type="checkbox" name="private" value="1"' + (d.private ? ' checked' : '') + off + '>' + T('仅自己可见') + '</label>' +
        '<button type="submit" class="primary"' + off + '>' + T('保存') + '</button></div></form>' +
      commentSec();
    setScore(collected ? d.score : 0);
  }
  // 本集评论: 在手机浏览器里打开这一集的 Bangumi 页面去写. 电视还不知道是哪一集时留一个空的占位, 地址到了原地换上
  function commentSec() {
    if (!link) return '<div id="cm-ep" hidden></div>';
    return '<div class="cm-sec" id="cm-ep"><div class="cm-h"><span>' + T('本集评论') + (link.episode ? '<small>' + esc(link.episode) + '</small>' : '') + '</span></div>' +
      '<p class="hint">' + T('评论在 Bangumi 网页上发表：打开这一集的页面，在手机浏览器里登录着 Bangumi 就能写。') +
      (link.viaMirror ? T('电视现在经镜像连接，手机可能要开代理才能打开。') : '') + '</p>' +
      '<a class="primary wide cm-web" href="' + esc(link.url) + '" target="_blank" rel="noopener noreferrer">' + T('去 Bangumi 发表评论') + '</a></div>';
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
    sc.lastChild.textContent = n ? WORDS[n] : (form.classList.contains('off') ? '' : T('点星星打分，也可以按住左右滑'));
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
    var type = b.getAttribute('data-ctype');
    if (!type || b.classList.contains('on')) return;
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
    post('api/player/review/rate', new FormData(form)).then(function (r) {
      btn.disabled = false;
      toast(r.message);
      if (r.ok) load(true);
    }).catch(function () { btn.disabled = false; fail(); });
  });
})();
""".trimIndent()

/**
 * 「设置」标签顶上的账号卡片 (见 RemoteAccount): 电视登录的是哪个 Bangumi 账号; 没登录时点一下发起登录 —— 默认在手机上授权
 * (授权完把浏览器跳到的网址粘回来), 也可以改在电视上登录; 另有「用个人令牌登录」, 不经过授权页 (中国大陆经镜像时授权页走不通).
 * 等授权期间每 2 秒问一次, 其余时候只在打开这个标签时读一次. 登录按钮 (`data-login`) 在评论与评分区也有一个, 点击统一在这里处理.
 */
private val ACCOUNT_SCRIPT = """
(function () {
  var box = document.getElementById('set-account');
  var hooks = window.remoteHooks;
  var last = '', timer = null, waiting = false, wasIn = null;
  // 点头像 / 名字展开的账号菜单 (退出登录)
  var menu = false, lastData = null;
  // 个人令牌登录的表单展开着没有 (没登录时). 授权页连不上 (中国大陆经镜像) 时只能走这条
  var tok = false;
  var TOKEN_DAYS = [7, 30, 90, 180, 365];
  function tokenForm(d, closable) {
    var pages = (d.tokenPages || []).map(function (u) {
      return '<a href="' + esc(u) + '" target="_blank" rel="noopener">' + esc(u.replace(/^https?:\/\//, '')) + '</a>';
    }).join(T('、'));
    return '<form class="acct-token" id="acct-token">' +
      '<p class="hint">' + (pages ? T('生成令牌的页面：{0}', pages) + T('。') : '') +
      '<a href="#" data-acct="token-guide">' + T('看步骤') + '</a></p>' +
      '<label class="f"><span>' + T('令牌') + '</span><input type="text" name="token" autocomplete="off" spellcheck="false"></label>' +
      '<label class="f"><span>' + T('有效期') + '</span><select name="days">' + TOKEN_DAYS.map(function (n) {
        return '<option value="' + n + '"' + (n === 365 ? ' selected' : '') + '>' + T('{0} 天', n) + '</option>';
      }).join('') + '</select><em>' + T('和生成令牌时选的一样。到期后电视会退出登录，再生成一个新的就行。') + '</em></label>' +
      '<div class="row">' + (closable ? '<button type="button" class="ghost" data-acct="token-close">' + T('取消') + '</button>' : '') +
      '<button type="submit" class="primary">' + T('登录') + '</button></div></form>';
  }
  function rerender() { if (lastData) render(lastData); }
  function load() {
    clearTimeout(timer);
    fetch('api/account').then(function (r) { return r.json(); }).then(render).catch(function () {});
  }
  window.loadAccount = load;
  function avatar(d) {
    // 候选依次试 (经电视转发 → 手机直连), 拉不到换下一张, 见 SCRIPT 里的 error 监听
    var list = (d.avatar || []).filter(Boolean);
    if (list.length) {
      return '<img class="alt-src" src="' + esc(list[0]) + '" data-alt="' + esc(list.slice(1).join(' ')) + '" alt="" referrerpolicy="no-referrer">';
    }
    return '<div class="acct-ph">' + esc((d.name || '?').charAt(0)) + '</div>';
  }
  function render(d) {
    if (!d.ok) return;
    lastData = d;
    if (!d.loggedIn) menu = false; else tok = false;
    var l = d.login || { state: 'idle' };
    // 刚登录上 (手机这边发起的, 或者电视上自己登的): 让评论与评分区重新读一次
    if (wasIn === false && d.loggedIn) window.runHooks('login', hooks.login, undefined);
    wasIn = !!d.loggedIn;
    waiting = l.state === 'waiting';
    var h = '<div class="card set-card"><div class="set-title">' + T('账号') + '</div>';
    if (d.loggedIn) {
      h += '<div class="acct" data-acct="menu">' + avatar(d) + '<div><div class="acct-name">' + esc(d.name || T('已登录')) + '</div><div class="acct-sub">' +
        T('已连接 Bangumi') + (d.bgmName ? T('（{0}）', esc(d.bgmName)) : '') +
        '</div></div><span class="acct-more">' + (menu ? T('收起') : T('管理')) + '</span></div>';
      if (menu) {
        h += '<div class="acct-menu">' +
          '<button type="button" class="ghost acct-danger ic" data-acct="logout">' + window.ICONS.logout + T('退出登录') + '</button></div>';
      }
    } else if (d.offline) {
      h += '<p class="hint">' + T('电视现在连不上 Bangumi，确认不了登录状态，稍后再看。') + '</p>';
    } else {
      h += '<p class="hint">' + T('电视还没登录。登录后收藏、看过的进度和评分都会同步到你的 Bangumi 账号。') + '</p>';
    }
    if (waiting) {
      // 手机授权: 授权完那一跳必然失败 (目标是电视本机的回环地址), 但地址栏里带着 code, 粘回来即可. 没有 url = 在电视上登录
      var paste = !!l.url;
      h += '<div class="acct-wait"><div class="now-status busy"><b>' + T('等待授权') + '</b><span>' +
        (paste ? T('授权完浏览器会跳到一个打不开的页面，这是正常的') : T('电视上已经打开 Bangumi 授权页，用遥控器完成登录')) + '</span></div>' +
        (paste ? '<p class="hint">' + T('把那个打不开的页面的网址整个复制，粘到下面。') + '</p>' +
          '<form class="acct-nick" id="acct-cb"><input type="text" name="u" inputmode="url" autocomplete="off" placeholder="' +
          T('粘贴那个网址') + '"><button type="submit">' + T('完成登录') + '</button></form>' +
          '<p class="hint">' + T('授权页没打开？') + '<a href="' + esc(l.url) + '" target="_blank" rel="noopener">' + T('点这里打开') + '</a></p>' : '') +
        '<div class="row"><button type="button" class="ghost" data-acct="cancel">' + T('取消登录') + '</button></div></div>';
    } else if (!d.loggedIn && !d.offline) {
      if (l.state === 'failed') h += '<div class="now-status error"><b>' + T('上次登录没有完成') + '</b><span>' + esc(l.message) + '</span></div>';
      if (d.viaMirror) {
        // 经第三方镜像: 授权页与换 token 在镜像上走不通, 只剩个人令牌; 而令牌要经镜像校验, 得先许凭证经过镜像
        // (不开的话校验请求被留在官方, 连不上, 只会等到超时)
        h += '<div class="now-status attention"><b>' + T('现在经镜像连接 Bangumi') + '</b><span>' +
          T('经镜像时授权登录走不通，只能用个人令牌登录。') + '</span></div>';
        h += d.mirrorCred && tok ? tokenForm(d, false)
          : '<div class="row"><button type="button" class="primary" data-acct="token-guide">' + T('用个人令牌登录') + '</button></div>';
      } else {
        h += '<div class="row"><button type="button" class="primary" data-login="1">' + T('用手机登录 Bangumi') + '</button>' +
          '<button type="button" class="ghost" data-login="tv">' + T('改在电视上登录') + '</button></div><p class="hint">' +
          T('在手机上授权，完成后把浏览器跳到的那个网址粘回来；电视上打字麻烦，所以默认走这条。') + '</p>';
        // 个人令牌: 不经过授权页
        h += tok ? tokenForm(d, true) : '<div class="row"><button type="button" class="ghost" data-acct="token-guide">' + T('用个人令牌登录') + '</button></div>';
      }
    }
    h += '</div>';
    if (h !== last) {
      // 重画保住正在填的 (回调网址 / 令牌) 与焦点
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
  // 用手机授权之前先讲清楚: 授权完浏览器会停在一个打不开的页面 (回调是电视本机的地址, 手机上当然打不开),
  // 不讲的话都以为登录失败了. 「知道了」那一下也是用户点的, 在里面开新页面不会被当成弹窗拦掉
  function loginGuide(btn) {
    var old = document.getElementById('login-dlg');
    if (old) old.remove();
    var d = document.createElement('div');
    d.id = 'login-dlg';
    d.innerHTML = '<div class="link-dlg-box"><div class="link-dlg-t">' + T('用手机登录 Bangumi') + '</div><ol class="login-steps">' +
      '<li>' + T('接下来会打开 Bangumi 的授权页：登录你的 Bangumi 账号，点「允许」。') + '</li>' +
      '<li>' + T('授权完，浏览器会跳到一个打不开的页面（提示无法访问、连接被拒绝之类）。这是正常的，不是登录失败。') + '</li>' +
      '<li>' + T('把那个打不开的页面的网址整个复制下来，回到这里粘贴，点「完成登录」。') + '</li></ol><div class="row">' +
      '<button type="button" class="ghost" data-ldlg="close">' + T('取消') + '</button>' +
      '<button type="button" class="primary" data-ldlg="go">' + T('知道了，去授权') + '</button></div></div>';
    d.addEventListener('click', function (e) {
      if (e.target.closest('[data-ldlg="go"]')) { d.remove(); startLogin(btn); return; }
      if (e.target === d || e.target.closest('[data-ldlg="close"]')) d.remove();
    });
    document.body.appendChild(d);
  }
  // 个人令牌登录的完整步骤. 经镜像时第一步是打开「登录与收藏同步也经过镜像」: 不开的话令牌校验被留在官方 (连不上),
  // 只会等到超时 (测试用户实测, 打开之后就登上了). 那一步的按钮就是同意 —— 风险写在步骤里, 不再另弹确认
  function tokenGuide(d) {
    var needCred = !!(d.viaMirror && !d.mirrorCred);
    var page = (d.tokenPages || [])[0] || 'https://next.bgm.tv/demo/access-token';
    var link = '<a href="' + esc(page) + '" target="_blank" rel="noopener">' + esc(page.replace(/^https?:\/\//, '')) + '</a>';
    var steps = [];
    if (needCred) {
      steps.push(T('打开「登录与收藏同步也经过镜像」，不然令牌校验发不出去。') +
        '<span class="risk">' + T('打开后，令牌、收藏和观看进度都会经过第三方镜像，对方能看到并使用你的账号。点下面的「我了解风险，打开并继续」会直接打开这个选项，即表示你接受这个风险。') + '</span>');
    }
    steps.push(d.viaMirror
      ? T('生成个人令牌：让手机临时开代理，或者换一个能打开 bgm.tv 的网络，打开 {0}，登录你的 Bangumi 账号，新建一个令牌，有效期建议选最长的。镜像网站上的登录页过不了人机验证，这一步只能在官网做。', link)
      : T('生成个人令牌：打开 {0}，登录你的 Bangumi 账号，新建一个令牌，有效期建议选最长的。', link));
    steps.push(T('复制生成的令牌，回到这里粘到「令牌」框，「有效期」选和刚才一样的天数，点「登录」。'));
    steps.push(T('令牌到期后电视会退出登录，到时再生成一个新的粘进来就行。'));
    var old = document.getElementById('login-dlg');
    if (old) old.remove();
    var dlg = document.createElement('div');
    dlg.id = 'login-dlg';
    dlg.innerHTML = '<div class="link-dlg-box"><div class="link-dlg-t">' + T('用个人令牌登录') + '</div><ol class="login-steps">' +
      steps.map(function (t) { return '<li>' + t + '</li>'; }).join('') + '</ol><div class="row">' +
      '<button type="button" class="ghost" data-ldlg="close">' + T('取消') + '</button>' +
      '<button type="button" class="primary" data-ldlg="go">' + (needCred ? T('我了解风险，打开并继续') : T('知道了')) + '</button></div></div>';
    dlg.addEventListener('click', function (e) {
      var go = e.target.closest('[data-ldlg="go"]');
      if (go) {
        if (!needCred) { dlg.remove(); openTokenForm(); return; }
        go.disabled = true;
        post('api/settings/bangumi/cred', { on: '1' }).then(function (r) {
          toast(r.message);
          dlg.remove();
          if (r.ok) openTokenForm(); else load();
        }).catch(function () { go.disabled = false; fail(); });
        return;
      }
      if (e.target === dlg || e.target.closest('[data-ldlg="close"]')) dlg.remove();
    });
    document.body.appendChild(dlg);
  }
  function openTokenForm() {
    tok = true;
    load();
  }
  // 登录按钮 (账号卡片、评论与评分区): 点下去当场先开一个空白页, 等电视要来链接再让它跳过去 ——
  // 等请求回来再开新页面会被浏览器当成弹窗拦掉. 开不了新页面 (有的内置浏览器) 就在本页跳, 授权完按返回回来
  function startLogin(btn) {
    // 点下去当场先开一个空白页, 等电视把授权链接回来再让它跳过去 —— 等请求回来再开会被当成弹窗拦掉.
    // 「改在电视上登录」那颗不开页面: 授权页弹在电视上, 手机这边只是等
    var onTv = btn.getAttribute('data-login') === 'tv', w = null;
    if (!onTv) { try { w = window.open('', '_blank'); } catch (e) {} }
    btn.disabled = true;
    post('api/account/login', onTv ? { where: 'tv' } : {}).then(function (r) {
      btn.disabled = false;
      if (!r.ok || !r.url) {
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
    if (b) {
      if (b.disabled) return;
      // 经镜像时授权登录走不通 (评论与评分区的登录按钮也走这里): 指到账号卡片的个人令牌
      if (lastData && lastData.viaMirror) { toast(T('现在经镜像连接 Bangumi，授权登录走不通。请在「设置 → 账号」里用个人令牌登录')); return; }
      if (b.getAttribute('data-login') === 'tv') startLogin(b); else loginGuide(b);
      return;
    }
    if (e.target.closest('[data-acct="token-guide"]')) {
      e.preventDefault();
      if (lastData) tokenGuide(lastData);
      return;
    }
    if (e.target.closest('[data-acct="cancel"]')) {
      post('api/account/login/cancel', {}).then(function (r) { toast(r.message); load(); }).catch(fail);
      return;
    }
    if (e.target.closest('#set-account [data-acct="menu"]')) { menu = !menu; rerender(); return; }
    if (e.target.closest('[data-acct="token-close"]')) { tok = false; rerender(); return; }
    if (e.target.closest('[data-acct="logout"]')) {
      if (!confirm(T('退出电视上的登录？\n\n退出后收藏同步和评分都要重新登录才能用。'))) return;
      post('api/account/logout', {}).then(function (r) { toast(r.message); menu = false; load(); }).catch(fail);
    }
  });
  box.addEventListener('submit', function (e) {
    var f = e.target;
    if (f.id === 'acct-cb') {
      e.preventDefault();
      var cb = f.querySelector('button');
      cb.disabled = true;
      post('api/account/login/callback', { url: f.elements.u.value.trim() }).then(function (r) {
        cb.disabled = false;
        toast(r.message);
        if (r.ok) { f.elements.u.value = ''; }
        load();
      }).catch(function () { cb.disabled = false; fail(); });
      return;
    }
    if (f.id === 'acct-token') {
      e.preventDefault();
      var tb = f.querySelector('button[type=submit]');
      tb.disabled = true;
      post('api/account/token', { token: f.elements.token.value.trim(), days: f.elements.days.value }).then(function (r) {
        tb.disabled = false;
        toast(r.message);
        if (r.ok) { tok = false; f.elements.token.value = ''; }
        load();
      }).catch(function () { tb.disabled = false; fail(); });
      return;
    }
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
  var SEARCH = sec(T('搜索'), [
    T('输入关键词，按需要选排序、最低评分和标签，点「在电视上搜索」，电视会跳到搜索结果。'),
    T('点搜索框会列出最近搜过的词：点一条直接搜，点 × 删掉这条记录。')
  ]) + sec(T('结果'), [
    T('列的是电视搜索页已经加载的结果，翻到底会自动加载更多。'),
    T('点右边的封面（没有封面的点 ▶）：电视直接开始播放；点标题或其他地方：电视打开详情页。'),
    T('右滑一行：缓存这部番；左滑：设置收藏状态。滑过一半松手直接执行。'),
    T('电视离开了搜索页时，底部可以让它回到原来的搜索结果。')
  ]);
  var PLAYER = sec(T('播放卡'), [
    T('点剧名：电视打开详情页（叠在播放器上，按返回回来）。'),
    T('「第几集」那一行可以点开选集，打 ✓ 的是看过的。'),
    T('点数据源胶囊：跳到下面正在播的那一条；右上角「缓存」：打开这部番的缓存面板。'),
    T('播放 / 暂停、后退 / 前进 10 秒；拖进度条跳转，点时间可以直接输入要跳到哪。'),
    T('点「接入锁屏 / 控制中心」后，可用手机的系统播放控件操作电视；网页只播放无声占位音轨，不会把电视声音传到手机。')
  ]) + sec(T('数据源'), [
    T('点一条就换成它播放；上面的胶囊可以只看某个源，下拉框按分辨率、字幕、字幕组筛。'),
    T('右滑一条：用它缓存这一集，这一集已在下载或已缓存时只提示；左滑：在手机上打开它的播放链接。滑过一半松手直接执行。'),
    T('弹幕、音轨与字幕、播放信息、评论与评分在下面可以展开的卡片里。'),
    T('电视退出了播放器（播放还在后台留着）时也能换源，播放控制要回到播放器才能用。')
  ]);
  var CACHE = sec(T('缓存'), [
    T('列出电视上的全部缓存，按番分组，下载中的会自动刷新；顶上是电视的剩余空间。'),
    T('点击番名：在电视上打开详情页。点击右侧封面或 ▶：按观看进度继续播放（同详情页的播放按钮）。'),
    T('点击某一集：在电视上播放这一集。'),
    T('选资源时可以展开「搜索名与集数」：改过的搜索名会记住，这部番以后缓存和播放都用它；集数只影响这一集，BT 合集编号和 Bangumi 不同时改这里才能下对文件。'),
    T('「全选」默认只选择正片，特别篇需要手动选择。可在「设置 → 本机偏好」中改为同时选择特别篇。'),
    T('左滑可删除该集缓存，删除前会再次确认。点击行尾按钮可暂停或继续。长按可进入多选，跨番批量删除。'),
    T('番名那一行右滑：缓存更多剧集；左滑：删除这部番的全部缓存（先确认）。滑过一半松手直接执行。'),
    T('最下面「挑番缓存」：从在看 / 想看里挑番缓存，在看里有新集的排在前面，并标出几集还没缓存；「新番时间表」按星期列出这一周每天更新的番，自己在看 / 想看的用主题色标出。')
  ]);
  var GENERAL = sec(T('账号'), [
    T('没登录时点「在电视上登录 Bangumi」，电视上会弹出授权页，用遥控器完成。'),
    T('点头像或名字：退出登录。')
  ]) + sec(T('播放记录'), [
    T('点右边的封面（或 ▶）：在电视上接着看，看完的播下一集；点其他地方：电视打开详情页。'),
    T('右滑缓存，左滑删除，滑过一半松手直接执行；长按一行可以多选，一起删除。')
  ]) + sec(T('其他'), [
    T('「本机偏好」只影响这台手机；代理、BT Tracker、弹幕屏蔽词改完立即生效；最底下可以下载电视的日志。')
  ]);
  var SOURCES = sec(T('数据源'), [
    T('订阅：粘贴订阅地址添加，在线数据源都来自订阅；长按订阅可以多选删除。'),
    T('数据源可以启用 / 停用、上下调整顺序、编辑、复制、导入导出；订阅来的源只能启用或停用。')
  ]);
  function content() {
    var s = document.querySelector('section.tab:not([hidden])'), tab = s ? s.id.replace('tab-', '') : 'search';
    if (tab === 'player') return [T('播放器'), PLAYER];
    if (tab === 'cache') return [T('缓存'), CACHE];
    if (tab === 'settings') {
      var src = document.getElementById('set-sources');
      return [T('设置'), src && !src.hidden ? SOURCES + GENERAL : GENERAL + SOURCES];
    }
    return [T('搜索'), SEARCH];
  }
  function open() {
    var c = content();
    title.textContent = T('使用说明 · {0}', c[0]);
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
 * 在看里有新集的排前面并标出几集还没缓存. 各段各读一次, 缓存面板关上时重读当前段 (刚缓存了, 「未缓存」的数跟着变).
 * 第三段「新番时间表」(见 RemoteSchedule): 分段下面一排星期, 一周一次拿回来、换一天就地换列表; 电视那边按天逐步补齐,
 * 没补完的时候隔一会儿再要一次.
 */
private val PICK_SCRIPT = """
(function () {
  var box = document.getElementById('cl-pick'), sheet = document.getElementById('pick-sheet');
  var body = document.getElementById('pick-body'), seg = document.getElementById('pick-seg');
  var days = document.getElementById('pick-days'), scroller = sheet.querySelector('.sheet-body');
  var type = 'DOING', data = {}, loading = {};
  box.innerHTML = '<button type="button" class="ghost wide ic" id="pick-open">' + window.ICONS.download + T('挑番缓存（在看 / 想看 / 新番时间表）') + '</button>';
  // 每段一个容器, 切换只是显示 / 隐藏: 画好的那段原样留着, 切回来不重画、封面不重新淡入
  body.innerHTML = '<div class="pick-pane" data-pt="DOING"></div><div class="pick-pane" data-pt="WISH" hidden></div>' +
    '<div class="pick-pane" data-pt="SCHEDULE" hidden></div>';
  // 时间表看哪一天 (1 = 周一 … 7 = 周日), 每次打开面板回到今天. 今天先按手机的算, 数据回来后按电视的
  var WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'];
  var today = (new Date().getDay() + 6) % 7 + 1, day = today, dayPicked = false, retry = null;
  /** 读过的一段这么久之内切回来不重新请求 (打开面板 / 改了收藏 / 缓存面板关上时照样强制重读). */
  var FRESH = 60000;
  function row(x) {
    return window.swRow(
      '<button type="button" class="sw-btn cache" data-cache="' + x.id + '" data-title="' + esc(x.title) + '">' + window.ICONS.download + T('缓存') + '</button>',
      '<button type="button" class="sw-btn coll" data-coll="' + x.id + '">' + window.ICONS.star + T('收藏') + '</button>',
      '<div class="item res-item pick-item' + (x.cover ? ' cv' : '') + (x.blur ? ' nsfw-blur' : '') + '" data-sid="' + x.id + '">' +
      (x.cover ? window.coverLayers(x.cover) : '') +
      '<span class="t">' + esc(x.title) + '</span>' +
      (x.line ? '<span class="m' + (x.fresh ? ' pick-new' : '') + '">' + esc(x.line) + '</span>' : '') +
      '<button type="button" class="res-play" aria-label="' + T('播放') + '"><span class="play-glyph">' + window.ICONS.play + '</span></button></div>');
  }
  function pane(t) { return body.querySelector('.pick-pane[data-pt="' + t + '"]'); }
  function paint(t) {
    var p = pane(t), d = data[t];
    if (!d) {
      if (!p.querySelector('.list')) p.innerHTML = '<p class="hint">' + T('正在读取…') + '</p>';
      return;
    }
    if (!d.ok) {
      p.innerHTML = '<div class="empty"><p>' + esc(d.message || T('读取失败')) + '</p>' +
        (d.needLogin ? '<p class="hint">' + T('在「设置」里登录后再来') + '</p>' : '') + '</div>';
      return;
    }
    if (t === 'SCHEDULE') { paintSchedule(p, d); return; }
    var items = d.items || [];
    if (!items.length) {
      p.innerHTML = '<div class="empty"><p>' + (t === 'DOING' ? T('没有在看的番') : T('没有想看的番')) + '</p></div>';
      return;
    }
    var list = p.querySelector(':scope > .list');
    if (!list) {
      p.innerHTML = '<p class="hint pick-stale" hidden>' + T('电视没连上服务器，下面是它上次同步的列表') + '</p><div class="list"></div>';
      list = p.querySelector(':scope > .list');
    }
    p.querySelector('.pick-stale').hidden = !d.stale;
    // 按行增量更新 (同搜索结果): 没变的行原样留着, 封面不重建、不闪
    window.patchList(list, items.map(row));
  }
  /** 一排星期: 选中的是 day, 今天标出来; 没变就不重画. */
  function paintDays() {
    var h = WEEKDAYS.map(function (w, i) {
      var n = i + 1;
      return '<button type="button" data-day="' + n + '"' + (n === day ? ' class="on"' : '') + (n === today ? ' data-today' : '') + '>' +
        T(w) + '</button>';
    }).join('');
    if (days._h !== h) { days.innerHTML = h; days._h = h; }
  }
  /** 时间表: 选中那一天的番, 行同在看 / 想看. 那天还没补完、又一部都没有时写「正在读取」而不是「没有新番」. */
  function paintSchedule(p, d) {
    if (d.today) {
      today = d.today;
      if (!dayPicked) day = today;
    }
    paintDays();
    var cur = (d.days || []).filter(function (x) { return x.weekday === day; })[0] || { items: [], pending: true };
    var items = cur.items || [];
    if (!items.length) {
      p.innerHTML = !cur.pending ? '<div class="empty"><p>' + T('这一天没有新番') + '</p></div>'
        : d.failed ? '<div class="empty"><p>' + esc(d.failed) + '</p></div>' : '<p class="hint">' + T('正在读取…') + '</p>';
      return;
    }
    var list = p.querySelector(':scope > .list');
    if (!list) {
      p.innerHTML = '<div class="list"></div>';
      list = p.querySelector(':scope > .list');
    }
    window.patchList(list, items.map(row));
  }
  // 电视那边按天逐步补齐: 还有没补完的那天就过一会儿再要 (面板还开着、还停在这一段才要)
  function retrySchedule() {
    clearTimeout(retry);
    if (!data.SCHEDULE || !data.SCHEDULE.partial) return;
    retry = setTimeout(function () { if (!sheet.hidden && type === 'SCHEDULE') load('SCHEDULE', true); }, 2000);
  }
  function load(t, force) {
    var old = data[t];
    // 没补完的时间表不算新鲜, 切回来接着要
    if (loading[t] || (!force && old && old.ok && !old.partial && Date.now() - old.at < FRESH)) return;
    loading[t] = true;
    // 时间表带上正在看的那天: 电视补完那一天就回
    fetch(t === 'SCHEDULE' ? 'api/schedule?day=' + day : 'api/collections?type=' + t).then(function (r) { return r.json(); }).then(function (d) {
      loading[t] = false;
      d.at = Date.now();
      d.partial = t === 'SCHEDULE' && d.ok && !d.failed && (d.days || []).some(function (x) { return x.pending; });
      data[t] = d;
      if (!sheet.hidden) paint(t);
      if (t === 'SCHEDULE') retrySchedule();
    }).catch(function () {
      loading[t] = false;
      if (!data[t]) data[t] = { ok: false, message: T('读取失败，关掉再打开试试') };
      if (!sheet.hidden) paint(t);
      if (t === 'SCHEDULE') retrySchedule();
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
    if (type === 'SCHEDULE' && day !== today) scroller.scrollTop = 0;
    day = today;
    dayPicked = false;
    days.hidden = type !== 'SCHEDULE';
    paintDays();
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
    days.hidden = type !== 'SCHEDULE';
    paintDays();
    paint(type);
    load(type, false);
  });
  // 换一天: 就地换列表 (一周的都在手上), 回到列表开头; 那天还没补完就马上要一次, 不等下一轮
  days.addEventListener('click', function (e) {
    var b = e.target.closest('[data-day]');
    if (!b) return;
    day = +b.getAttribute('data-day');
    dayPicked = true;
    paintDays();
    paint('SCHEDULE');
    scroller.scrollTop = 0;
    if (data.SCHEDULE && data.SCHEDULE.partial) load('SCHEDULE', true);
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
      if (!sheet.hidden && !items) hb.innerHTML = '<p class="hint">' + T('读取播放记录失败，关掉再打开试试') + '</p>';
    });
  }
  function paintEntry(list) {
    var n = list.length;
    entry.innerHTML = '<button type="button" class="card set-card hist-entry" data-hist="1">' + ICON +
      '<span class="hist-txt"><b>' + T('播放记录') + '</b><small>' + (n ? T('{0} 部 · 最近看了「{1}」', n, esc(list[0].title)) : T('还没有播放记录')) +
      '</small></span><span class="hist-go">›</span></button>';
  }
  function paintList() {
    if (!items) { hb.innerHTML = '<p class="hint">' + T('正在读取…') + '</p>'; return; }
    if (!items.length) {
      hb.innerHTML = '<div class="empty"><p>' + T('还没有播放记录') + '</p><p class="hint">' + T('在电视上看过的番会按最近看的顺序列在这里') + '</p></div>';
      return;
    }
    // 怎么用 (点封面接着看 / 滑动 / 长按多选) 在右上角「?」里, 第一行会自动滑开一次提示 (swPeek)
    hb.innerHTML = '<div class="list">' + items.map(function (x) {
      var imgs = x.imgs || [];
      // 右滑露出「缓存」(打开这部番的缓存面板), 左滑露出「删除」(删掉这部番的播放记录)
      return window.swRow(
        '<button type="button" class="sw-btn cache" data-cache="' + x.id + '" data-title="' + esc(x.title) + '">' +
          window.ICONS.download + T('缓存') + '</button>',
        '<button type="button" class="sw-btn del" data-hdel="' + x.id + '">' + window.ICONS.trash + T('删除') + '</button>',
        '<div class="item hist-item' + (imgs.length ? ' cv' : '') + '" data-sid="' + x.id + '" data-lp="' + x.id + '">' +
        (imgs.length ? window.coverLayers(imgs) : '') + '<span class="sel-mark" aria-hidden="true"></span>' +
        '<div class="hist-main"><span class="t">' + esc(x.title) + '</span><span class="m">' + esc(x.line) + '</span>' +
        (x.percent != null ? '<div class="hist-bar"><div style="width:' + x.percent + '%"></div></div>' : '') +
        (x.time ? '<span class="hist-time">' + esc(x.time) + '</span>' : '') + '</div>' +
        '<button type="button" class="res-play" aria-label="' + T('播放') + '"><span class="play-glyph">' + window.ICONS.play + '</span></button></div>');
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
      ask: function (n) { return T('删除选中的 {0} 部番的播放记录？电视上的播放历史也会一起删掉。', n); },
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
