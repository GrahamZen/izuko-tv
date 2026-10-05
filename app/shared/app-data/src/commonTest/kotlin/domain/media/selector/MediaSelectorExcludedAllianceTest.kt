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
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.test.DisabledOnNative
import me.him188.ani.test.TestContainer
import kotlin.test.Test

/**
 * 排除的字幕组 ([me.him188.ani.app.data.models.preference.MediaPreference.excludedAlliancePatterns]).
 */
@TestContainer
@DisabledOnNative // TODO: ContextParameters crashes on Native
class MediaSelectorExcludedAllianceTest {
    @Test
    fun `excluded alliances are filtered out with their own reason`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("孤独摇滚")
            preferenceApi.savedDefaultPreference.value =
                preferenceApi.savedDefaultPreference.value.copy(excludedAlliancePatterns = listOf("喵萌", "^北宇治$"))
            mediaApi.addMedia(
                media(kind = MediaSourceKind.WEB, subjectName = "孤独摇滚", alliance = "喵萌奶茶屋", mediaId = "dmhy.miaomeng"),
                media(kind = MediaSourceKind.WEB, subjectName = "孤独摇滚", alliance = "北宇治字幕组", mediaId = "dmhy.kitauji"),
                media(kind = MediaSourceKind.WEB, subjectName = "孤独摇滚", alliance = "桜都字幕组", mediaId = "dmhy.sakura"),
            )
        },
    ) {
        assertMedias {
            onSingle(mediaId = "dmhy.miaomeng").assert(
                included = false,
                exclusionReason = MediaExclusionReason.ExcludedAlliance,
            )
            // 正则按用户写的匹配: ^北宇治$ 不匹配 "北宇治字幕组"
            onSingle(mediaId = "dmhy.kitauji").assert(included = true)
            onSingle(mediaId = "dmhy.sakura").assert(included = true)
        }
    }

    @Test
    fun `cached media of an excluded alliance stays available`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("孤独摇滚")
            preferenceApi.savedDefaultPreference.value =
                preferenceApi.savedDefaultPreference.value.copy(excludedAlliancePatterns = listOf("喵萌"))
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.LocalCache,
                    location = MediaSourceLocation.Local,
                    subjectName = "孤独摇滚",
                    alliance = "喵萌奶茶屋",
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = true)
        }
    }

    @Test
    fun `blank patterns are ignored and invalid regexes match literally`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("孤独摇滚")
            preferenceApi.savedDefaultPreference.value =
                preferenceApi.savedDefaultPreference.value.copy(excludedAlliancePatterns = listOf("", "  ", "[某组"))
            mediaApi.addMedia(
                media(kind = MediaSourceKind.WEB, subjectName = "孤独摇滚", alliance = "[某组]", mediaId = "dmhy.bracket"),
                media(kind = MediaSourceKind.WEB, subjectName = "孤独摇滚", alliance = "桜都字幕组", mediaId = "dmhy.sakura"),
            )
        },
    ) {
        assertMedias {
            onSingle(mediaId = "dmhy.bracket").assert(
                included = false,
                exclusionReason = MediaExclusionReason.ExcludedAlliance,
            )
            onSingle(mediaId = "dmhy.sakura").assert(included = true)
        }
    }
}
