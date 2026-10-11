/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.repository.danmaku.SearchDanmakuRequest
import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes

class DanmakuRequestKeyTest {
    private val request = SearchDanmakuRequest(
        subjectInfo = SubjectInfo.Empty.copy(subjectId = 633836, nameCn = "Re：从零开始的异世界生活 第四季 夺还篇"),
        episodeInfo = EpisodeInfo.Empty.copy(episodeId = 1656864, sort = EpisodeSort(84), ep = EpisodeSort(7), name = "ラム", nameCn = "拉姆"),
        episodeId = 1656864,
        filename = "S04E07.mkv",
        videoDuration = 24.minutes,
    )

    /** 「继续观看」每小时刷新收藏, 评分、收藏人数、分集评论数常常变了: 同一集不重新查弹幕. */
    @Test
    fun `stats refreshed by the collection sync keep the key`() {
        val refreshed = request.copy(
            subjectInfo = request.subjectInfo.copy(
                ratingInfo = request.subjectInfo.ratingInfo.copy(total = 1234, score = "7.7"),
                collectionStats = request.subjectInfo.collectionStats.copy(doing = 5678),
            ),
            episodeInfo = request.episodeInfo.copy(comment = 321),
        )
        assertNotEquals(request, refreshed)
        assertEquals(request.fetchKey, refreshed.fetchKey)
    }

    @Test
    fun `another episode or file changes the key`() {
        assertNotEquals(request.fetchKey, request.copy(episodeId = 1656865, episodeInfo = request.episodeInfo.copy(episodeId = 1656865)).fetchKey)
        assertNotEquals(request.fetchKey, request.copy(filename = "other.mkv").fetchKey)
        assertNotEquals(request.fetchKey, request.copy(episodeInfo = request.episodeInfo.copy(name = "英雄")).fetchKey)
    }
}
