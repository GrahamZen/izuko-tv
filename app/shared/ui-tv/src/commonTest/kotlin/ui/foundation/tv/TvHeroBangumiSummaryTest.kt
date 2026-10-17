/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.test.Test
import kotlin.test.assertEquals

class TvHeroBangumiSummaryTest {
    private val japanese = "クソ真面目な神官のパーティーに新しく加入してきたのは、悪魔の力を使う美しきダークサモナー。"
    private val chinese = "为了交朋友，他决定加入学生会，却发现会员都是怪人。"

    @Test
    fun `own bangumi summary comes first`() {
        assertEquals("bgm", tvHeroBangumiSummary(" bgm ", bgmTvSummary = "bgm.tv", tmdbFallback = "tmdb", tmdbPending = false))
    }

    @Test
    fun `bgm tv summary fills an empty bangumi summary`() {
        assertEquals("bgm.tv", tvHeroBangumiSummary("", bgmTvSummary = "bgm.tv", tmdbFallback = "tmdb", tmdbPending = false))
    }

    @Test
    fun `tmdb is used only when bgm tv confirmed there is none`() {
        assertEquals("tmdb", tvHeroBangumiSummary("", bgmTvSummary = "", tmdbFallback = "tmdb", tmdbPending = false))
        assertEquals("", tvHeroBangumiSummary("", bgmTvSummary = "", tmdbFallback = null, tmdbPending = false))
    }

    @Test
    fun `nothing is shown while bgm tv is still loading`() {
        assertEquals("", tvHeroBangumiSummary("  ", bgmTvSummary = null, tmdbFallback = "tmdb", tmdbPending = false))
    }

    @Test
    fun `leading japanese is dropped from the bangumi summary`() {
        assertEquals(chinese, tvHeroBangumiSummary("$japanese\n\n$chinese", bgmTvSummary = null, tmdbFallback = "tmdb", tmdbPending = false))
    }

    @Test
    fun `japanese only summary is replaced by tmdb`() {
        assertEquals("tmdb", tvHeroBangumiSummary(japanese, bgmTvSummary = null, tmdbFallback = "tmdb", tmdbPending = false))
    }

    @Test
    fun `japanese only summary waits for tmdb and then shows as is`() {
        assertEquals("", tvHeroBangumiSummary(japanese, bgmTvSummary = null, tmdbFallback = null, tmdbPending = true))
        assertEquals(japanese, tvHeroBangumiSummary(japanese, bgmTvSummary = null, tmdbFallback = null, tmdbPending = false))
    }

    @Test
    fun `chinese summary does not wait for tmdb`() {
        assertEquals(chinese, tvHeroBangumiSummary(chinese, bgmTvSummary = null, tmdbFallback = "tmdb", tmdbPending = true))
        assertEquals("$chinese\n\n$japanese", tvHeroBangumiSummary("$chinese\n\n$japanese", bgmTvSummary = null, tmdbFallback = "tmdb", tmdbPending = true))
    }

    @Test
    fun `english ui always fetches tmdb text`() {
        assertEquals(true, tvHeroNeedsTmdbText(prefersTmdb = true, bangumiSummary = chinese))
        assertEquals(true, tvHeroNeedsTmdbText(prefersTmdb = true, bangumiSummary = null))
    }

    @Test
    fun `chinese ui fetches tmdb text only for empty or japanese only summaries`() {
        assertEquals(true, tvHeroNeedsTmdbText(prefersTmdb = false, bangumiSummary = ""))
        assertEquals(true, tvHeroNeedsTmdbText(prefersTmdb = false, bangumiSummary = japanese))
        assertEquals(false, tvHeroNeedsTmdbText(prefersTmdb = false, bangumiSummary = chinese))
        assertEquals(false, tvHeroNeedsTmdbText(prefersTmdb = false, bangumiSummary = "$japanese\n\n$chinese"))
        assertEquals(false, tvHeroNeedsTmdbText(prefersTmdb = false, bangumiSummary = null))
    }
}
