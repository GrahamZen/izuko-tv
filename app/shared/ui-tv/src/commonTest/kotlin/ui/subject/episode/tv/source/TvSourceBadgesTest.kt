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
import me.him188.ani.app.domain.media.ProbedMediaAvFeatures
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.ResourceLocation
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
    fun `a pill shows the best picture badge among its entries`() {
        fun media(id: String, title: String) = createTestDefaultMedia(
            mediaId = id,
            mediaSourceId = "drive",
            originalUrl = "",
            download = ResourceLocation.HttpStreamingFile("https://drive.test/$id"),
            originalTitle = title,
            publishedTime = 0,
            properties = createTestMediaProperties(),
            episodeRange = null,
            location = MediaSourceLocation.Online,
            kind = MediaSourceKind.WEB,
        )
        val plain = media("badge-plain", "01.mkv")
        val hdr = media("badge-hdr", "02 2160p HDR10.mkv")
        // 标题里没写, 网盘读到的是杜比视界 profile 5: 胶囊上照常标杜比视界, 不标不支持
        val probed = media("badge-probed", "03.mkv")
        ProbedMediaAvFeatures.record(probed.mediaId, MediaAvFeatures(MediaDynamicRange.DolbyVision, dolbyVisionProfile = 5))
        assertTrue(tvSourceBestPictureBadges(listOf(plain)).isEmpty())
        assertEquals(listOf(TvSourceBadge.Hdr10), tvSourceBestPictureBadges(listOf(plain, hdr)))
        assertEquals(listOf(TvSourceBadge.DolbyVision), tvSourceBestPictureBadges(listOf(plain, hdr, probed)))
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
