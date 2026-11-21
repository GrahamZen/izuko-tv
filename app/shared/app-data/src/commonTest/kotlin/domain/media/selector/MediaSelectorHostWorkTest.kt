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
import me.him188.ani.app.domain.media.selector.testFramework.MediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.assertMedias
import me.him188.ani.app.domain.media.selector.testFramework.runSimpleMediaSelectorTestSuite
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchHostWork
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.test.DisabledOnNative
import me.him188.ani.test.TestContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 接在本篇后面编号的 OVA (TV 13 集之后记成第 14 集): 数据源在本篇的页上只留这一集、集号换成 14 (见 `asEpisodesOf`),
 * 选择器按模糊匹配收下, 单独开的页优先.
 */
@TestContainer
@DisabledOnNative // TODO: ContextParameters crashes on Native
class MediaSelectorHostWorkTest {
    private val host = MediaFetchHostWork(
        names = listOf("全金属狂潮 The Second Raid", "全金属狂潮3", "Full Metal Panic! The Second Raid"),
        firstSort = 1,
        episodeCount = 13,
    )

    private fun MediaSelectorTestSuite.InitApi.ova() {
        aliases("フルメタル・パニック! The Second Raid 特別版OVA「わりとヒマな戦隊長の一日」")
        episodeSort = EpisodeSort(14)
        episodeEp = EpisodeSort(1)
        seriesInfo = seriesInfo.copy(hostWork = host)
    }

    @Test
    fun `本篇页上按本条目序号认领的一集按模糊匹配收下`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("全金属狂潮 The Second Raid OVA 战队长悠闲的一天") { ova() }
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "全金属狂潮3",
                    originalTitle = "OVA",
                    episodeRange = EpisodeRange.single(EpisodeSort(14)),
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = true)
        }
        val candidate = selector.filteredCandidates.first().single()
        assertIs<MaybeExcludedMedia.Included>(candidate)
        assertEquals(MatchMetadata.SubjectMatchKind.FUZZY, candidate.metadata.subjectMatchKind)
    }

    @Test
    fun `本篇页上同号的条目内序号不算`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("全金属狂潮 The Second Raid OVA 战队长悠闲的一天") { ova() }
            // 本篇第 1 集与本条目第 1 集同号
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "全金属狂潮3",
                    originalTitle = "01",
                    episodeRange = EpisodeRange.single(EpisodeSort(1)),
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = false)
        }
    }

    @Test
    fun `没有本篇的条目不收本篇页上的资源`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("全金属狂潮 The Second Raid OVA 战队长悠闲的一天") {
                episodeSort = EpisodeSort(14)
                episodeEp = EpisodeSort(1)
            }
            mediaApi.addMedia(
                media(
                    kind = MediaSourceKind.WEB,
                    subjectName = "全金属狂潮3",
                    originalTitle = "OVA",
                    episodeRange = EpisodeRange.single(EpisodeSort(14)),
                ),
            )
        },
    ) {
        assertMedias {
            single().assert(included = false)
        }
    }
}
