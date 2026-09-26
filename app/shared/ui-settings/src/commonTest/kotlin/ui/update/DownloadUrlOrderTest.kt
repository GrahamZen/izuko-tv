/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadUrlOrderTest {
    private val arm64 = "https://github.com/o/r/releases/download/v1.0.2/izuko-1.0.2-arm64-v8a.apk"
    private val universal = "https://github.com/o/r/releases/download/v1.0.2/izuko-1.0.2-universal.apk"

    @Test
    fun `mirror urls go first and both groups keep their order`() {
        val urls = listOf(arm64, ghfastUrl(arm64), universal, ghfastUrl(universal))
        assertEquals(listOf(ghfastUrl(arm64), ghfastUrl(universal), arm64, universal), urls.mirrorFirst())
    }

    @Test
    fun `already mirror first stays the same`() {
        val urls = listOf(ghfastUrl(arm64), arm64)
        assertEquals(urls, urls.mirrorFirst())
    }
}
