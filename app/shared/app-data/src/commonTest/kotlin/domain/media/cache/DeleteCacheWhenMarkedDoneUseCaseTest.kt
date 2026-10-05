/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.media.download.DownloadTestStorage
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.download.testDownload
import me.him188.ani.app.domain.media.player.ActivePlayback
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteCacheWhenMarkedDoneUseCaseTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        override val flow = MutableStateFlow(initial)
        override suspend fun set(value: T) {
            flow.value = value
        }
    }

    private class Fixture(scope: TestScope, enabled: Boolean) {
        val calls = mutableListOf<Pair<Int, UnifiedCollectionType?>>()
        val storage = DownloadTestStorage().apply {
            // 第 1 部番两集, 第 2 部番一集
            listFlow.value = listOf(testDownload(1, subjectId = 1), testDownload(2, subjectId = 1), testDownload(3, subjectId = 2))
        }
        val activity = PlaybackActivity()
        val settings = MemorySettings(MediaCacheSettings(deleteWhenMarkedDone = enabled))
        val useCase = DeleteCacheWhenMarkedDoneUseCase(
            delegate = object : SetSubjectCollectionTypeOrDeleteUseCase {
                override suspend fun invoke(subjectId: Int, collectionType: UnifiedCollectionType?) {
                    calls += subjectId to collectionType
                }
            },
            settings = settings,
            downloadManager = MediaDownloadManager(listOf(storage), scope.backgroundScope),
            playbackActivity = activity,
            scope = scope.backgroundScope,
        )

        fun remainingEpisodes(): List<String> = storage.listFlow.value.map { it.metadata.episodeId }
    }

    @Test
    fun `marking done deletes the caches of that subject when enabled`() = runTest {
        val f = Fixture(this, enabled = true)
        runCurrent()
        DeleteCacheWhenMarkedDoneUseCase.deletions.test {
            f.useCase(1, UnifiedCollectionType.DONE)
            runCurrent()
            val deleted = awaitItem()
            assertEquals(2, deleted.count)
            assertEquals(0, deleted.waitingForPlayback)
            assertEquals("Subject", deleted.subjectName)
        }
        assertEquals(listOf<Pair<Int, UnifiedCollectionType?>>(1 to UnifiedCollectionType.DONE), f.calls)
        assertEquals(listOf("3"), f.remainingEpisodes())
    }

    @Test
    fun `other collection types or the setting off keep the caches`() = runTest {
        val on = Fixture(this, enabled = true)
        runCurrent()
        on.useCase(1, UnifiedCollectionType.DOING)
        on.useCase(1, UnifiedCollectionType.DROPPED)
        runCurrent()
        assertEquals(listOf("1", "2", "3"), on.remainingEpisodes())

        val off = Fixture(this, enabled = false)
        runCurrent()
        off.useCase(1, UnifiedCollectionType.DONE)
        runCurrent()
        assertEquals(listOf("1", "2", "3"), off.remainingEpisodes())
        assertEquals(listOf<Pair<Int, UnifiedCollectionType?>>(1 to UnifiedCollectionType.DONE), off.calls)
    }

    /** 各集一起发出去: 第 1 集卡在存储里 (锁被别处的新建占着) 时, 第 2 集已经在排队, 不等第 1 集删完才发 */
    @Test
    fun `all episodes are requested at once`() = runTest {
        val f = Fixture(this, enabled = true)
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        f.storage.onDelete = {
            started += it.metadata.episodeId
            gate.await()
        }
        runCurrent()
        f.useCase(1, UnifiedCollectionType.DONE)
        runCurrent()
        assertEquals(listOf("1", "2"), started.sorted())

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("3"), f.remainingEpisodes())
    }

    /** 播放页正开着这部番的第 2 集: 它的缓存等不在放这一集了再删 */
    @Test
    fun `the episode open in the player is deleted after playback leaves it`() = runTest {
        val f = Fixture(this, enabled = true)
        val owner = Any()
        f.activity.update(owner, ActivePlayback(subjectId = 1, episodeId = 2, bufferedAheadMillis = null, stalled = false))
        runCurrent()
        DeleteCacheWhenMarkedDoneUseCase.deletions.test {
            f.useCase(1, UnifiedCollectionType.DONE)
            runCurrent()
            val deleted = awaitItem()
            assertEquals(1, deleted.count)
            assertEquals(1, deleted.waitingForPlayback)
        }
        assertEquals(listOf("2", "3"), f.remainingEpisodes())

        f.activity.update(owner, null)
        runCurrent()
        assertEquals(listOf("3"), f.remainingEpisodes())
    }

    /** TV 保留播放会话: 退出播放页后会话还开着第 2 集 (暂停), 不在眼前了就删 */
    @Test
    fun `the episode is deleted once the player page leaves the screen`() = runTest {
        val f = Fixture(this, enabled = true)
        f.activity.update(Any(), ActivePlayback(subjectId = 1, episodeId = 2, bufferedAheadMillis = null, stalled = false))
        runCurrent()
        f.useCase(1, UnifiedCollectionType.DONE)
        runCurrent()
        assertEquals(listOf("2", "3"), f.remainingEpisodes())

        f.activity.setOnScreen(false)
        runCurrent()
        assertEquals(listOf("3"), f.remainingEpisodes())
    }

    /** 保留着的会话已经不在眼前时标成看过: 不用等, 全删 */
    @Test
    fun `a retained session off screen does not defer deletion`() = runTest {
        val f = Fixture(this, enabled = true)
        f.activity.update(Any(), ActivePlayback(subjectId = 1, episodeId = 2, bufferedAheadMillis = null, stalled = false))
        f.activity.setOnScreen(false)
        runCurrent()
        DeleteCacheWhenMarkedDoneUseCase.deletions.test {
            f.useCase(1, UnifiedCollectionType.DONE)
            runCurrent()
            val deleted = awaitItem()
            assertEquals(2, deleted.count)
            assertEquals(0, deleted.waitingForPlayback)
        }
        assertEquals(listOf("3"), f.remainingEpisodes())
    }
}
