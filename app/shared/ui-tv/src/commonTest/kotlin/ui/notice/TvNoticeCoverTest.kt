/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import kotlin.test.Test
import kotlin.test.assertEquals

class TvNoticeCoverTest {
    private fun item(key: String, cover: TvNoticeCoverImage? = null) =
        TvNoticeColumnItem(key = key, icon = Icons.Outlined.Campaign, iconLabel = "", title = key, atMillis = null, cover = cover)

    @Test
    fun `rows keep a cover wide column when any row of the list has a cover`() {
        val cover = TvNoticeCoverImage("https://example.invalid/cover.jpg", obscure = false)
        // 没有带封面的行: 左边照旧是图标那么宽
        assertEquals(false, tvNoticeCoverColumn(listOf(item("a"), item("b"))))
        assertEquals(false, tvNoticeCoverColumn(emptyList()))
        // 有一行带封面: 整个列表左边都留封面那么宽的一栏
        assertEquals(true, tvNoticeCoverColumn(listOf(item("a"), item("b", cover), item("c"))))
    }
}
