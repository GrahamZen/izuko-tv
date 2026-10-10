/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.title

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「本季集号(系列内集号)」的集数
 */
class AbsoluteNumberedEpisodeTitleTest : PatternBasedTitleParserTestSuite() {
    @Test
    fun `season episode with absolute number`() {
        val r = parse("""【豌豆字幕组】[药屋少女的呢喃（药师少女的独语）/ Kusuriya no Hitorigoto S3][02(50)][简体][1080P][MP4]""")
        assertEquals("02..02", r.episodeRange.toString())
        assertEquals("CHS", r.subtitleLanguages.sortedBy { it.id }.joinToString { it.id })
        assertEquals("1080P", r.resolution.toString())
    }

    @Test
    fun `full-width parentheses`() {
        val r = parse("""[Kusuriya no Hitorigoto S3][01（49）][繁體][1080P]""")
        assertEquals("01..01", r.episodeRange.toString())
    }

    @Test
    fun `smaller number in parentheses is not an absolute number`() {
        val r = parse("""[Kusuriya no Hitorigoto S3][12(01)][简体][1080P]""")
        assertEquals("S3", r.episodeRange.toString())
    }
}
