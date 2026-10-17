/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import kotlin.test.Test
import kotlin.test.assertEquals

class MergedEpisodeDescTest {
    @Test
    fun `tmdb text comes before bangumi text`() {
        assertEquals("tmdb\n\nbgm", mergedEpisodeDesc("tmdb", "bgm"))
    }

    @Test
    fun `tmdb only drops bangumi text when tmdb has some`() {
        assertEquals("tmdb", mergedEpisodeDesc("tmdb", "bgm", tmdbOnly = true))
    }

    @Test
    fun `tmdb only falls back to bangumi text`() {
        assertEquals("bgm", mergedEpisodeDesc(null, "bgm", tmdbOnly = true))
        assertEquals("bgm", mergedEpisodeDesc("  ", "bgm", tmdbOnly = true))
    }

    @Test
    fun `nothing gives an empty string`() {
        assertEquals("", mergedEpisodeDesc(null, "", tmdbOnly = true))
        assertEquals("", mergedEpisodeDesc(null, ""))
    }
}
