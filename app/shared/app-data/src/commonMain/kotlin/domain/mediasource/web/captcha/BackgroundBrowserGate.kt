/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web.captcha

import me.him188.ani.app.platform.PlaybackActivity
import kotlin.concurrent.Volatile

/**
 * 后台搜索 (自动解验证码、用已有浏览器重载被挡的页) 能不能用浏览器.
 *
 * WebView 的创建与加载都在主线程上, 一次能卡 2～3 秒, 字幕、弹幕、控制层跟着停; 电视上自动解图片验证码又几乎解不出来
 * (截不到刷新后的验证码图). 所以:
 * - 电视版启动时关掉 [autoSolveWithBrowser]: 自动解验证码只用纯 HTTP 的策略;
 * - 有视频在播或缓冲时 (见 [PlaybackActivity]) 哪个平台都不用浏览器, 包括用手动解出来的暖会话重载被挡的页.
 * 被挡住的源照常报「要验证码」, 用户想看再手动解. 用户点开的手动解验证码、解析选中资源的播放地址不受影响.
 */
object BackgroundBrowserGate {
    @Volatile
    var autoSolveWithBrowser: Boolean = true

    internal fun allowed(): Boolean = !PlaybackActivity.isActive

    /** 现在不能用浏览器时去掉要建浏览器的策略 (只剩纯 HTTP 的). */
    internal fun usable(solvers: List<CaptchaSolver>): List<CaptchaSolver> =
        if (autoSolveWithBrowser && allowed()) solvers else solvers.filterNot { it is BrowserImageCaptchaSolver }
}
