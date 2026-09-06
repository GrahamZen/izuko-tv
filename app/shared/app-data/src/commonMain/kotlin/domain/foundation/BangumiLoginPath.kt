/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

/**
 * 用户怎么登录 Bangumi: 由请求实际落在哪条线路上决定 (见 [BangumiEndpointProvider.loginPath]), 每种情况只给这一条路.
 */
enum class BangumiLoginPath {
    /** 官方或自建镜像: 浏览器打得开授权页, 走 OAuth 授权 (手机扫码 / 电视上的浏览器). */
    BROWSER,

    /**
     * 加密直连: 浏览器做不了加密直连, 手机多半也打不开 Bangumi. 用户在 Web 控制台填账号密码, 电视替用户登录网页并生成个人令牌
     * (见 `BangumiWebLogin`).
     */
    TV_WEB_LOGIN,

    /** 第三方镜像: 主站挡在反爬验证后面, 只能粘贴在别处生成的个人令牌. */
    PERSONAL_TOKEN,
    ;

    companion object {
        fun of(route: BangumiRoute): BangumiLoginPath = when {
            route.thirdParty -> PERSONAL_TOKEN
            !route.browsable -> TV_WEB_LOGIN
            else -> BROWSER
        }
    }
}
