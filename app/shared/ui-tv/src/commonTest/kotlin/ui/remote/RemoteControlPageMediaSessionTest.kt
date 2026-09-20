/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class RemoteControlPageMediaSessionTest {
    private val page = renderRemoteControlPage(
        initialTab = "player",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    @Test
    fun `system media controls are explicitly enabled with a silent local carrier`() {
        assertContains(page, "id=\"pb-system\"")
        assertContains(page, "function createCarrier()")
        assertContains(page, "new Blob([out], { type: 'video/mp4' })")
        assertContains(page, "mediaCarrier.play()")
        assertContains(page, "window.mediaSessionActive = true")
    }

    @Test
    fun `media session publishes metadata state and progress`() {
        assertContains(page, "navigator.mediaSession.metadata = new MediaMetadata")
        // 标题 / 集名的来路 (探封面那几秒里先用空 artwork 贴一次, 所以这里是先取局部变量再塞进 MediaMetadata)
        assertContains(page, "var title = mediaState.title || 'Animeko', artist = mediaState.episode || ''")
        assertContains(page, "title: title,")
        assertContains(page, "artist: artist,")
        assertContains(page, "navigator.mediaSession.playbackState")
        assertContains(page, "navigator.mediaSession.setPositionState")
        // 新封面还没探到时沿用上一张, **别空着上**: 候选图一条都没探到 (经电视转发的 TMDB 图常挂) 就交
        // 空 artwork 给系统, 锁屏上那块当场变白 (2026-09-20 用户报"换了两集之后封面变成全白")
        assertContains(page, "var use = artwork && artwork.length ? artwork : lastArtwork;")
    }

    @Test
    fun `native controls map to existing remote player endpoints`() {
        assertContains(page, "setMediaHandler('play'")
        assertContains(page, "setMediaHandler('pause'")
        assertContains(page, "setMediaHandler('seekbackward'")
        assertContains(page, "setMediaHandler('seekforward'")
        assertContains(page, "setMediaHandler('seekto'")
        assertContains(page, "setMediaHandler('previoustrack'")
        assertContains(page, "setMediaHandler('nexttrack'")
        assertContains(page, "post('api/player/control'")
        assertContains(page, "post('api/player/episode'")
    }

    /**
     * 载体必须是**带静音音轨的 video**, 不是 audio:
     * - 是 video 才进得了全屏 / 小窗, 而那两处的进度条与快进快退是系统原生的;
     * - **必须有音轨** —— iOS 只暂停"无音轨 / muted"的后台视频, 带音轨的才能像 audio 那样接着播。
     *   所以这里专门 assertFalse 掉 muted: 加上它, 页面一退到后台锁屏控件就没了。
     */
    @Test
    fun `carrier is a video with a real silent audio track`() {
        assertContains(page, "document.createElement('video')")
        // 视频轨只有 1 帧, 靠 sample duration 撑满整集; 音频轨是 N 个一模一样的静音 AAC 帧
        assertContains(page, "var SILENT_AAC = [0x01, 0x18, 0x20, 0x07]")
        assertContains(page, "function silentClipUrl(seconds)")
        assertContains(page, "var CARRIER_SPS = 'Z0LAHt")
        // 时长就是这一集的时长 —— 小窗 / 全屏的进度条读的是元素自己的时间轴
        assertContains(page, "function ensureCarrier(seconds)")
        assertContains(page, "function syncCarrier(seconds)")
        assertFalse(page.contains("v.muted = true"))
    }

    /**
     * 全屏按钮是唯一入口: iOS 原生全屏播放器的退出键旁边自带画中画按钮, 小窗从那里进。
     * 但用户从全屏转进小窗之后按键还得转发, 所以 pipActive 照样要盯着。
     */
    @Test
    fun `fullscreen button is the way in and native controls are forwarded`() {
        assertContains(page, "id=\"pb-fs\"")
        assertContains(page, "function enterCarrierFullscreen()")
        assertContains(page, "webkitEnterFullscreen")
        // 原生控件要开着, 全屏播放器才给进度条和那颗画中画按钮
        assertContains(page, "v.controls = true")
        assertContains(page, "webkitpresentationmodechanged")
        assertContains(page, "webkitbeginfullscreen")
        // 全屏 / 小窗里按的播放暂停与拖动, 走与锁屏控件同一条路
        assertContains(page, "sendMediaControl('pause')")
        assertContains(page, "sendMediaControl('play')")
        // 拖动 / ±15 秒 (具体怎么夹见 placeholder carrier can never reach fullscreen)
        assertContains(page, "v.addEventListener('seeked'")
        // 原生全屏里的倍速菜单改的是元素的 playbackRate, 要转给电视, 档位还得跟电视的一致
        assertContains(page, "v.addEventListener('ratechange'")
        assertContains(page, "pb.speedStep")
        assertContains(page, "sendSpeed(pct)")
    }

    /**
     * iOS 的全屏播放器**认死进去时的那一份载体**: 带着一秒的循环占位片进去, 之后换 src 也顶不上去 ——
     * 表现为总时长只有几秒、进度在 0 和 1 秒之间绕, 而占位片每绕回一次都引出 currentTime≈0 的 seeked,
     * 被当成"用户拖到了片头"发 seek(0) 给电视 (2026-09-20 真机)。三道都得在:
     */
    @Test
    fun `placeholder carrier can never reach fullscreen`() {
        // 1. 知道片长就直接按片长建, 不先上占位片再换 (点全屏是同步的, 等不到下一轮轮询)
        assertContains(page, "var known = pb && pb.duration > 0 ? pb.duration / 1000 : 0")
        assertContains(page, "v.loop = !(known > 0)")
        // 2. 载体还是占位片时按钮置灰
        assertContains(page, "function carrierFullscreenReady()")
        assertContains(page, "if (!carrierFullscreenReady()) return")
        // 3. 已经在全屏 / 小窗里时不换 src, 欠着等退出再补
        assertContains(page, "carrierPendingSeconds = seconds;")
        assertContains(page, "function applyPendingCarrier()")
        // 兜底: 时间轴对不上这一集就不把位置发回电视
        assertContains(page, "!carrierReady || v.loop || !(carrierSeconds > 0)")
        // 但"载体比电视长几秒"不算占位片: 在全屏里换集时重建是挂起的, 那时也得照样能拖
        // (2026-09-20: 按时长相等来挡, 换完集快进快退整个失灵)
        assertContains(page, "seek(Math.round(Math.max(0, Math.min(tvDur, v.currentTime)) * 1000));")
    }

    /**
     * 手机那侧的状态得能回到电视日志里: 电视只记慢请求, 不留这条通路的话网页里出的事在 logcat 里
     * 一点痕迹都没有 (2026-09-20 为此绕了两轮)。见 [RemoteClientLog]。
     */
    @Test
    fun `carrier state is reported back to the tv log`() {
        assertContains(page, "post('api/client-log'")
        // 判断"载体换过去没有"的硬证据: 想要的时长和元素真读出来的摆在一起
        assertContains(page, "clientLog('carrier ready: element '")
        assertContains(page, "clientLog('carrier rebuilt: '")
        // iOS 挑不挑这份手拼的 MP4, 只有这里看得出来
        assertContains(page, "clientLog('carrier LOAD ERROR code='")
        assertContains(page, "clientLog('fullscreen entered, carrier '")
        // 别把 logcat 刷爆
        assertContains(page, "if (text === lastClientLog && now - lastClientLogAt < 10000) return;")
    }

    @Test
    fun `enabled session keeps polling while page is hidden`() {
        assertContains(page, "!window.mediaSessionActive && (document.hidden || cur !== 'player' || window.sheets.any())")
        // 一次 available:false 不当场拆 (轮询瞬断时锁屏控件会变成空壳), 状态稳定一段时间才拆
        assertContains(page, "hooks.unavailable.push(function () { mediaState = null; scheduleDisableMediaSession(); })")
    }
}
