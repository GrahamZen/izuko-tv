/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.media.selector.testFramework.assertMedias
import me.him188.ani.app.domain.media.selector.testFramework.runSimpleMediaSelectorTestSuite
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.test.DisabledOnNative
import me.him188.ani.test.TestContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 站点把整个系列放在一个不带季号的条目下, 按系列内序号连续编集时的匹配.
 *
 * 例如站内条目 "凡人修仙传" 的第 159 集就是 "凡人修仙传 第四季" 的第 35 集: 条目名比不出季度,
 * 但系列内序号在整个系列中唯一, 与条目内序号不同时足以认领这一集.
 */
@TestContainer
@DisabledOnNative // TODO: ContextParameters crashes on Native
class MediaSelectorSeasonlessSeriesTest {
    @Test
    fun `系列内序号命中时保留不带季号的站内条目`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("凡人修仙传 第四季") {
                aliases("凡人修仙传 外海风云篇", "凡人修仙传 重返天南篇")
                episodeSort = EpisodeSort(159)
                episodeEp = EpisodeSort(35)
            }
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "凡人修仙传",
                    originalTitle = "凡人修仙传 第159集",
                    episodeRange = EpisodeRange.single(EpisodeSort(159)),
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = true)
        }
        // 名字与本条目的名字不同, 但指向的就是本条目的这一集: 按精确匹配对待, 简单模式的列表与快速选择才收它
        val candidate = selector.filteredCandidates.first().single()
        assertIs<MaybeExcludedMedia.Included>(candidate)
        assertEquals(MatchMetadata.SubjectMatchKind.EXACT, candidate.metadata.subjectMatchKind)
        assertTrue(candidate.isPerfectMatch())
    }

    @Test
    fun `只有条目内序号命中时排除不带季号的站内条目`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("凡人修仙传 第四季") {
                aliases("凡人修仙传 外海风云篇", "凡人修仙传 重返天南篇")
                episodeSort = EpisodeSort(159)
                episodeEp = EpisodeSort(35)
            }
            // 连续编集的站内条目下, 第 35 集是第一季的那一集
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "凡人修仙传",
                    originalTitle = "凡人修仙传 第35集",
                    episodeRange = EpisodeRange.single(EpisodeSort(35)),
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = false, exclusionReason = MediaExclusionReason.SubjectNameMismatch)
        }
    }

    @Test
    fun `条目内外序号一致时不放宽`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("凡人修仙传 第四季") {
                episodeSort = EpisodeSort(35)
                episodeEp = EpisodeSort(35)
            }
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "凡人修仙传",
                    originalTitle = "凡人修仙传 第35集",
                    episodeRange = EpisodeRange.single(EpisodeSort(35)),
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = false, exclusionReason = MediaExclusionReason.SubjectNameMismatch)
        }
    }

    @Test
    fun `名字对不上的长篇不因序号命中而保留`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("凡人修仙传 第四季") {
                episodeSort = EpisodeSort(159)
                episodeEp = EpisodeSort(35)
            }
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "海贼王",
                    originalTitle = "海贼王 第159集",
                    episodeRange = EpisodeRange.single(EpisodeSort(159)),
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = false, exclusionReason = MediaExclusionReason.SubjectNameMismatch)
        }
    }

    @Test
    fun `系列信息已加载时系列内序号命中仍然保留`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("凡人修仙传 第四季") {
                episodeSort = EpisodeSort(159)
                episodeEp = EpisodeSort(35)
                seriesInfo(seasonSort = 4) {
                    series("凡人修仙传")
                }
            }
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "凡人修仙传",
                    originalTitle = "凡人修仙传 第159集",
                    episodeRange = EpisodeRange.single(EpisodeSort(159)),
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = true)
        }
        // 名字与本条目的名字不同, 但指向的就是本条目的这一集: 按精确匹配对待, 简单模式的列表与快速选择才收它
        val candidate = selector.filteredCandidates.first().single()
        assertIs<MaybeExcludedMedia.Included>(candidate)
        assertEquals(MatchMetadata.SubjectMatchKind.EXACT, candidate.metadata.subjectMatchKind)
        assertTrue(candidate.isPerfectMatch())
    }

    @Test
    fun `系列信息已加载时只有条目内序号命中的仍按其他季度排除`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("凡人修仙传 第四季") {
                episodeSort = EpisodeSort(159)
                episodeEp = EpisodeSort(35)
                seriesInfo(seasonSort = 4) {
                    series("凡人修仙传")
                }
            }
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "凡人修仙传",
                    originalTitle = "凡人修仙传 第35集",
                    episodeRange = EpisodeRange.single(EpisodeSort(35)),
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = false, exclusionReason = MediaExclusionReason.FromSeriesSeason)
        }
    }
}
