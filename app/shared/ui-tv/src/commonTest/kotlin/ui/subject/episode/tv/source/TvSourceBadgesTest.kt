/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import me.him188.ani.app.domain.media.MediaAvFeatures
import me.him188.ani.app.domain.media.MediaDynamicRange
import me.him188.ani.app.domain.media.MediaSurroundAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSourceBadgesTest {
    @Test
    fun `picture badge comes before the audio badge`() {
        assertEquals(
            listOf(TvSourceBadge.DolbyVision, TvSourceBadge.DolbyAtmos),
            tvSourceBadgesOf(MediaAvFeatures(MediaDynamicRange.DolbyVision, MediaSurroundAudio.DolbyAtmos)),
        )
    }

    @Test
    fun `only the known parts become badges`() {
        assertEquals(listOf(TvSourceBadge.Hdr10), tvSourceBadgesOf(MediaAvFeatures(dynamicRange = MediaDynamicRange.Hdr10)))
        assertEquals(listOf(TvSourceBadge.TrueHd), tvSourceBadgesOf(MediaAvFeatures(audio = MediaSurroundAudio.TrueHd)))
        assertTrue(tvSourceBadgesOf(MediaAvFeatures.NONE).isEmpty())
    }

    @Test
    fun `a title with several dynamic ranges shows the best one`() {
        // 杜比视界的片子一般同时带 HDR10 底层
        assertEquals(
            listOf(TvSourceBadge.DolbyVision, TvSourceBadge.DolbyAtmos),
            tvSourceBadgesOf(MediaAvFeatures.fromTitle("Movie 2160p UHD BluRay REMUX HDR10 DV HEVC TrueHD 7 1 Atmos")),
        )
    }

    @Test
    fun `dolby vision profile 5 is marked unsupported only without a decoder`() {
        fun badge(profile: Int?, decodes: Boolean) =
            tvSourceBadgesOf(MediaAvFeatures(MediaDynamicRange.DolbyVision, dolbyVisionProfile = profile), decodesDolbyVision = decodes).single()
        assertEquals(TvSourceBadge.DolbyVisionUnsupported, badge(5, decodes = false))
        assertEquals(TvSourceBadge.DolbyVision, badge(5, decodes = true))
        // profile 8 有 HDR10 底层, 不支持杜比视界的设备照样能放
        assertEquals(TvSourceBadge.DolbyVision, badge(8, decodes = false))
        // 标题里看不出 profile
        assertEquals(TvSourceBadge.DolbyVision, badge(null, decodes = false))
    }

    @Test
    fun `only picture badges are drawn as picture badges`() {
        val picture = TvSourceBadge.entries.filter { it.picture }
        assertEquals(
            listOf(TvSourceBadge.DolbyVision, TvSourceBadge.Hdr10Plus, TvSourceBadge.Hdr10, TvSourceBadge.Hlg, TvSourceBadge.Hdr),
            picture,
        )
    }
}
