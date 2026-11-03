/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import me.him188.ani.app.domain.media.selector.testFramework.assertMedias
import me.him188.ani.app.domain.media.selector.testFramework.runSimpleMediaSelectorTestSuite
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.SubtitleKind
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.test.DisabledOnNative
import me.him188.ani.test.TestContainer
import me.him188.ani.utils.platform.Arch
import me.him188.ani.utils.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

@TestContainer
@DisabledOnNative // TODO: ContextParameters crashes on Native
class SidecarSubtitleSourcesTest {
    private val android = MediaSelectorSubtitlePreferences.forPlatform(Platform.Android(Arch.ARMV8A))

    /** 测试条目是完结番, 单集 BT 会被藏掉, 用合集 */
    private val batch = EpisodeRange.range(EpisodeSort(1), EpisodeSort(12))

    @Test
    fun `BT titled external subtitles is listed after the others and not auto selected`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("孤独摇滚")
            preferenceApi.setSubtitlePreferences(android)
            mediaApi.addMedia(
                media(kind = MediaSourceKind.BitTorrent, episodeRange = batch, subtitleKind = SubtitleKind.EXTERNAL_DISCOVER, mediaId = "dmhy.external"),
                media(kind = MediaSourceKind.BitTorrent, episodeRange = batch, mediaId = "dmhy.normal"),
            )
        },
    ) {
        assertMedias {
            next().assert(mediaId = "dmhy.normal", included = true)
            next().assert(mediaId = "dmhy.external", included = true)
        }
        assertEquals("dmhy.normal", selector.trySelectDefault()?.mediaId)
    }

    @Test
    fun `BT titled external subtitles is auto selected when it is the only one`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("孤独摇滚")
            preferenceApi.setSubtitlePreferences(android)
            mediaApi.addMedia(
                media(kind = MediaSourceKind.BitTorrent, episodeRange = batch, subtitleKind = SubtitleKind.EXTERNAL_DISCOVER, mediaId = "dmhy.external"),
            )
        },
    ) {
        assertEquals("dmhy.external", selector.trySelectDefault()?.mediaId)
    }

    @Test
    fun `online direct link titled external subtitles stays hidden`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("孤独摇滚")
            preferenceApi.setSubtitlePreferences(android)
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "孤独摇滚",
                    subtitleKind = SubtitleKind.EXTERNAL_DISCOVER,
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = false, exclusionReason = MediaExclusionReason.UnsupportedByPlatformPlayer)
        }
    }

    @Test
    fun `subtitle kinds the platform hides stay hidden for BT`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("孤独摇滚")
            preferenceApi.setSubtitlePreferences(MediaSelectorSubtitlePreferences.forPlatform(Platform.MacOS(Arch.AARCH64)))
            mediaApi.addMedia(
                media(kind = MediaSourceKind.BitTorrent, episodeRange = batch, subtitleKind = SubtitleKind.CLOSED),
            )
        },
    ) {
        assertMedias {
            single().assert(included = false, exclusionReason = MediaExclusionReason.UnsupportedByPlatformPlayer)
        }
    }
}
