/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.devicemigration

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.AniDatabaseConstructor
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.repository.subject.SubjectRewatchEntry
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DOING
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DONE
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.NOT_COLLECTED
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 换电视: 用户库的快照里, 重温中的番按开始时的看过与各集看过算 (重温不跟去新电视), 旧电视自己的库不动.
 */
class DeviceMigrationRewatchTest {
    private val dir = createTempDirectory("migration-rewatch").toFile()

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    private fun open(name: String): AniDatabase =
        Room.databaseBuilder<AniDatabase>(File(dir, name).path) { AniDatabaseConstructor.initialize() }
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType) = EpisodeCollectionEntity(
        subjectId = subjectId, episodeId = episodeId, episodeType = null, name = "", nameCn = "",
        airDate = PackedDate.Invalid, comment = 0, desc = "", sort = EpisodeSort(episodeId % 100),
        sortNumber = (episodeId % 100).toFloat(), selfCollectionType = type, lastFetched = 1,
    )

    private suspend fun AniDatabase.types(subjectId: Int) =
        episodeCollection().filterBySubjectId(subjectId).first().map { it.selfCollectionType }

    @Test
    fun `snapshot restores rewatching subjects and leaves the source alone`() = runBlocking {
        val source = open("source.db")
        try {
            source.subjectCollection().upsert(testSubjectCollectionEntity(7, DOING, lastUpdated = 9_000))
            source.subjectCollection().upsert(testSubjectCollectionEntity(8, DOING, lastUpdated = 100))
            source.episodeCollection().upsert(
                listOf(episode(7, 701, NOT_COLLECTED), episode(7, 702, NOT_COLLECTED), episode(7, 703, DONE), episode(8, 801, DONE)),
            )
            val rewatches = mapOf(
                7 to SubjectRewatchEntry(7, startedAt = 9_000, lastUpdated = 500, episodes = mapOf(701 to DONE, 702 to DONE)),
                // 记录还在、库里那一行已经不是在看了的不动
                8 to SubjectRewatchEntry(8, startedAt = 50, lastUpdated = 10, episodes = emptyMap()),
            )
            source.subjectCollection().updateType(8, UnifiedCollectionType.ON_HOLD, 100)

            DeviceMigrationDatabases.snapshot(source, File(dir, "snapshot.db").toKtPath().inSystem, rewatches)

            val copy = open("snapshot.db")
            try {
                val rewatched = copy.subjectCollection().findById(7).first()!!
                assertEquals(DONE, rewatched.collectionType)
                assertEquals(500, rewatched.lastUpdated)
                assertEquals(listOf(DONE, DONE, NOT_COLLECTED), copy.types(7))
                assertEquals(UnifiedCollectionType.ON_HOLD, copy.subjectCollection().findById(8).first()!!.collectionType)
                assertEquals(listOf(DONE), copy.types(8))
            } finally {
                copy.close()
            }
            assertEquals(DOING, source.subjectCollection().findById(7).first()!!.collectionType)
            assertEquals(listOf(NOT_COLLECTED, NOT_COLLECTED, DONE), source.types(7))
        } finally {
            source.close()
        }
    }
}
