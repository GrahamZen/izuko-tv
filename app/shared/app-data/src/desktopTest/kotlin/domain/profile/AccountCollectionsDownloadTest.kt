/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DOING
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DONE
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DROPPED
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.ON_HOLD
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.WISH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Bangumi 用户改成本地用户前把账号里的收藏取下来 (AccountCollectionsDownload): 收藏列表没翻完就停下;
 * 想看以外的每一部取分集写库, 一部取不到重试一次、还不行就跳过.
 */
class AccountCollectionsDownloadTest {
    /** 库里收藏了的: 每类一部. */
    private val collected = mapOf(10 to DOING, 20 to DONE, 30 to ON_HOLD, 40 to DROPPED, 50 to WISH)
    private var listComplete = true
    private var listRefreshed = 0
    private val fetchAttempts = mutableMapOf<Int, Int>()
    private val failTimes = mutableMapOf<Int, Int>()
    private val saved = mutableListOf<EpisodeCollectionEntity>()
    private val progress = mutableListOf<AccountCollectionsDownload.Progress>()

    private val download = AccountCollectionsDownload(
        refreshCollectionList = {
            listRefreshed++
            listComplete
        },
        collectedSubjectIds = { types -> collected.filterValues { it in types }.keys.sorted() },
        fetchEpisodes = { subjectId ->
            val attempt = fetchAttempts.merge(subjectId, 1, Int::plus)!!
            if (attempt <= (failTimes[subjectId] ?: 0)) error("network")
            listOf(episode(subjectId, subjectId * 100 + 1, DONE))
        },
        saveEpisodes = { synchronized(saved) { saved += it } },
        parallelism = 2,
    )

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType) = EpisodeCollectionEntity(
        subjectId = subjectId, episodeId = episodeId, episodeType = null, name = "", nameCn = "", airDate = PackedDate.Invalid,
        comment = 0, desc = "", sort = EpisodeSort(1), sortNumber = 1f, selfCollectionType = type, lastFetched = 0,
    )

    @Test
    fun `列表翻完后 想看以外的每一部取分集写库`() = realTimeTest {
        val skipped = download.download { synchronized(progress) { progress += it } }

        assertEquals(0, skipped)
        assertEquals(1, listRefreshed)
        assertEquals(listOf(10, 20, 30, 40), fetchAttempts.keys.sorted())
        assertEquals(listOf(10, 20, 30, 40), saved.map { it.subjectId }.sorted())
        assertEquals(AccountCollectionsDownload.Progress(0, 0), progress.first())
        assertEquals(AccountCollectionsDownload.Progress(4, 4), progress.last())
    }

    @Test
    fun `收藏列表没翻完就停下 不取分集`() = realTimeTest {
        listComplete = false

        assertFailsWith<IllegalStateException> { download.download() }

        assertTrue(fetchAttempts.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `一部取不到重试一次 还不行就跳过 不拦下其余的`() = realTimeTest {
        failTimes[10] = 1
        failTimes[20] = 5

        val skipped = download.download()

        assertEquals(1, skipped)
        assertEquals(2, fetchAttempts[10])
        assertEquals(2, fetchAttempts[20])
        assertEquals(listOf(10, 30, 40), saved.map { it.subjectId }.sorted())
    }
}
