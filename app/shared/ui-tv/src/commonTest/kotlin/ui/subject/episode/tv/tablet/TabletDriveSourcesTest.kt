/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「添加网盘分享」「从网盘挑」报认出了哪几集时的写法 ([formatDriveEpisodes]): 连着的整数集并成一段, 别的照写.
 */
class TabletDriveSourcesTest {
    private fun eps(vararg values: String) = values.map { EpisodeSort(it) }

    @Test
    fun `consecutive episodes collapse into ranges`() {
        assertEquals("1~12", formatDriveEpisodes(eps("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"), "、"))
        assertEquals("1~3、5、7~8", formatDriveEpisodes(eps("1", "2", "3", "5", "7", "8"), "、"))
    }

    @Test
    fun `a single episode stays as is`() {
        assertEquals("9", formatDriveEpisodes(eps("9"), "、"))
        assertEquals("", formatDriveEpisodes(emptyList(), "、"))
    }

    @Test
    fun `non integer episodes break the range and are written as they are`() {
        assertEquals("1~2, 2.5, 3", formatDriveEpisodes(eps("1", "2", "2.5", "3"), ", "))
    }
}
