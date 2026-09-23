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
import kotlin.test.assertNull

class DistributionApplicationIdsTest {
    @Test
    fun `新旧包名按前缀互换，变体与 debug 后缀原样带过去`() {
        assertEquals("io.github.grahamzen.anime.tv.debug2", DistributionApplicationIds.currentOf("me.him188.ani.tv.debug2"))
        assertEquals("me.him188.ani.tv", DistributionApplicationIds.legacyOf("io.github.grahamzen.anime.tv"))
    }

    /** 旧包里选过的缓存目录是旧包的专属目录, 新包没有权限, 原样接过来就写不进去. */
    @Test
    fun `缓存目录里的旧包名换成新包名`() {
        assertEquals(
            "/storage/emulated/0/Android/data/io.github.grahamzen.anime.tv/files/Movies",
            DistributionApplicationIds.rewritePathForCurrent(
                "/storage/emulated/0/Android/data/me.him188.ani.tv/files/Movies",
                "me.him188.ani.tv", "io.github.grahamzen.anime.tv",
            ),
        )
        // 目录本身就是包目录 (末尾没有分隔符) 也要认
        assertEquals(
            "/data/user/0/io.github.grahamzen.anime.tv",
            DistributionApplicationIds.rewritePathForCurrent(
                "/data/user/0/me.him188.ani.tv",
                "me.him188.ani.tv", "io.github.grahamzen.anime.tv",
            ),
        )
    }

    @Test
    fun `只认整段包名`() {
        // debug 包的目录不是正式包的目录, 反之亦然
        assertNull(
            DistributionApplicationIds.rewritePathForCurrent(
                "/storage/emulated/0/Android/data/me.him188.ani.tv.debug2/files/Movies",
                "me.him188.ani.tv", "io.github.grahamzen.anime.tv",
            ),
        )
        assertNull(
            DistributionApplicationIds.rewritePathForCurrent(
                "/storage/1234-5678/Movies",
                "me.him188.ani.tv", "io.github.grahamzen.anime.tv",
            ),
        )
    }
}
