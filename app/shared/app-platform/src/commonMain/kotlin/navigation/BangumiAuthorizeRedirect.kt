/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.navigation

/**
 * 「去登录 Bangumi」([AniNavigator.navigateBangumiAuthorize]) 改去哪: 不为 null 时由它导航, 不开单独的授权页.
 * TV 包启动时装上 (登录与须知都在设置页的账号那一类里, 见 TvPageVariants).
 */
object BangumiAuthorizeRedirect {
    var target: ((AniNavigator) -> Unit)? = null
}
