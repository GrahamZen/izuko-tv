/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlin.test.Test
import kotlin.test.assertEquals

/** 每条线路只给一条登录路. */
class BangumiLoginPathTest {
    @Test
    fun `官方与自建镜像 — 浏览器授权`() {
        assertEquals(BangumiLoginPath.BROWSER, BangumiLoginPath.of(BangumiRoute.Origin))
        assertEquals(BangumiLoginPath.BROWSER, BangumiLoginPath.of(BangumiRoute.Custom("my.example")))
    }

    @Test
    fun `加密直连 — 电视登录网页`() {
        assertEquals(BangumiLoginPath.TV_WEB_LOGIN, BangumiLoginPath.of(BangumiRoute.Ech))
    }

    @Test
    fun `社区镜像 — 个人令牌`() {
        assertEquals(BangumiLoginPath.PERSONAL_TOKEN, BangumiLoginPath.of(BangumiRoute.Mirror("mirror.example")))
    }
}
