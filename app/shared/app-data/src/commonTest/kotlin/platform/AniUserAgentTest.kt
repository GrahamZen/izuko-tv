/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * UA 用本 fork 的身份: 顶着上游的 `open-ani/ani/<版本>`, Bangumi 会把新包 1.x 当成有 bug 的老版 Animeko 拦掉.
 * 合并上游时这一行容易被冲突解决带回去.
 */
class AniUserAgentTest {
    @Test
    fun `UA 是本 fork 的身份`() {
        assertEquals(
            "GrahamZen/izuko-tv/1.0.3 (Android arm64-v8a) (https://github.com/GrahamZen/izuko-tv)",
            getAniUserAgent(version = "1.0.3", platform = "Android arm64-v8a", repository = "GrahamZen/izuko-tv"),
        )
    }

    @Test
    fun `UA 不带上游的身份`() {
        assertFalse(getAniUserAgent(version = "1.0.3", platform = "Android arm64-v8a").contains("open-ani"))
    }
}
