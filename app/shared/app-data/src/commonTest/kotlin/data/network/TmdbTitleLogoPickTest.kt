/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 标题 logo 按语言挑哪一张 ([pickTmdbTitleLogo]). */
class TmdbTitleLogoPickTest {
    private fun logo(path: String, language: String?, vote: Float = 5f, aspect: Float = 3f, votes: Int = 1, region: String? = null) =
        TmdbLogoCandidate(path, language, region = region, aspectRatio = aspect, voteAverage = vote, voteCount = votes)

    private val sample = listOf(
        logo("/ja-low.png", "ja", vote = 3f),
        logo("/ja.png", "ja", vote = 9f),
        logo("/en.png", "en", vote = 8f),
        logo("/zh.png", "zh", vote = 10f),
        logo("/none.png", null, vote = 10f),
    )

    @Test
    fun `japanese anime picks the best voted japanese logo`() {
        assertEquals("/ja.png", pickTmdbTitleLogo(sample, "ja")?.filePath)
    }

    @Test
    fun `an american show picks the english logo`() {
        assertEquals("/en.png", pickTmdbTitleLogo(sample, "en")?.filePath)
    }

    @Test
    fun `chinese prefers simplified regions before traditional ones`() {
        val logos = listOf(
            logo("/tw.png", "zh", vote = 9f, region = "TW"),
            logo("/cn.png", "zh", vote = 1f, region = "CN"),
            logo("/hk.png", "zh", vote = 8f, region = "HK"),
        )
        assertEquals("/cn.png", pickTmdbTitleLogo(logos, "zh")?.filePath)
        assertEquals("/tw.png", pickTmdbTitleLogo(logos.filter { it.region != "CN" }, "zh")?.filePath)
    }

    @Test
    fun `other languages and language neutral logos are never used`() {
        val noJapanese = listOf(logo("/zh.png", "zh"), logo("/none.png", null), logo("/xx.png", "xx"))
        assertNull(pickTmdbTitleLogo(noJapanese, "ja"))
    }

    @Test
    fun `equal votes fall back to the vote count`() {
        val logos = listOf(logo("/few.png", "ja", vote = 5f, votes = 1), logo("/many.png", "ja", vote = 5f, votes = 4))
        assertEquals("/many.png", pickTmdbTitleLogo(logos, "ja")?.filePath)
    }

    @Test
    fun `svg and logos without aspect ratio are skipped`() {
        val logos = listOf(logo("/ja.svg", "ja", vote = 9f), logo("/ja-zero.png", "ja", aspect = 0f), logo("/ja.png", "ja", vote = 1f))
        assertEquals("/ja.png", pickTmdbTitleLogo(logos, "ja")?.filePath)
    }
}
