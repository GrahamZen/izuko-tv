/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubjectSourceTableTest {
    private class FakeLegacy(val lineages: MutableMap<Int, String>) : SubjectSourceTable.LegacyLineages {
        override suspend fun read(): Map<Int, String> = lineages.toMap()
        override suspend fun remove(subjectIds: Collection<Int>) {
            subjectIds.forEach { lineages.remove(it) }
        }
    }

    private val web = WebSourceLineage("source", "冰之城墙", "线路1", "https://site/detail/1")
    private val torrent = TorrentLineage("bt", null, "喵萌", "1080P", listOf("CHS"))

    private var now = 1_000_000_000_000L
    private val store = MemorySubjectSourceStore()
    private val table = SubjectSourceTable(store, clock = { now })

    private fun encoded(lineage: PlaybackLineage) = DataStoreJson.encodeToString(PlaybackLineage.serializer(), lineage)

    @Test
    fun `a lineage comes back as it was with its page`() = runTest {
        table.setPlayed(1, web)
        table.setPlayed(2, torrent)

        assertEquals(web, table.state().lineageOf(1))
        assertEquals(torrent, table.state().lineageOf(2))
    }

    @Test
    fun `a lineage that cannot be read counts as none and leaves others alone`() = runTest {
        table.setPlayed(2, torrent)
        table.updateRow(1) { it.copy(played = """{"type":"from-a-later-version"}""") }

        assertNull(table.state().lineageOf(1))
        assertEquals(torrent, table.state().lineageOf(2))
    }

    @Test
    fun `an empty row is removed`() = runTest {
        table.setPlayed(1, web)
        table.setPlayed(1, null)

        assertEquals(emptyMap(), table.state().rows)
    }

    @Test
    fun `the key of a lineage ignores its page`() {
        assertEquals(web.encodedKey(), web.copy(subjectUrl = null).encodedKey())
        assertEquals(web.encodedKey(), web.copy(subjectUrl = "https://elsewhere").encodedKey())
        assertNotEquals(web.encodedKey(), web.copy(channel = "线路2").encodedKey())
    }

    @Test
    fun `legacy lineages move into the table and are removed`() = runTest {
        val legacy = FakeLegacy(mutableMapOf(1 to encoded(web), 2 to encoded(torrent), 3 to "not json"))
        val table = SubjectSourceTable(store, legacy, clock = { now })

        assertEquals(web, table.state().lineageOf(1))
        assertEquals(torrent, table.state().lineageOf(2))
        assertNull(table.state().lineageOf(3))
        assertEquals(emptyMap(), legacy.lineages)

        // 重启后还在
        assertEquals(web, SubjectSourceTable(store, FakeLegacy(mutableMapOf())).state().lineageOf(1))
    }

    @Test
    fun `legacy lineages stay where they are when the table cannot be written`() = runTest {
        val dir = SystemPaths.createTempDirectory("subject-source-table-test")
        try {
            val file = dir.resolve("subject_sources.json")
            // 写表先写临时文件再挪过去: 临时文件的位置被占着, 写不成 (盘满、IO 错同理)
            file.resolveSibling(file.name + ".tmp").createDirectories()
            val legacy = FakeLegacy(mutableMapOf(1 to encoded(web)))
            val table = SubjectSourceTable(FileSubjectSourceStore(file), legacy, clock = { now })

            assertEquals(web, table.state().lineageOf(1))
            assertEquals(mapOf(1 to encoded(web)), legacy.lineages, "表没写进去, 旧线索不能删")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a legacy lineage wins over the table`() = runTest {
        table.setPlayed(1, torrent)

        val table = SubjectSourceTable(store, FakeLegacy(mutableMapOf(1 to encoded(web))))

        assertEquals(web, table.state().lineageOf(1))
    }

    @Test
    fun `a row with fields this version does not know still reads`() = runTest {
        val dir = SystemPaths.createTempDirectory("subject-source-table-test")
        try {
            val file = dir.resolve("subject_sources.json")
            val played = encoded(web).replace("\"", "\\\"")
            file.writeText("""{"rows":{"1":{"played":"$played","lastPlayedMillis":5,"addedLater":{"x":1}},"2":{"addedLater":true}}}""")

            val state = SubjectSourceTable(FileSubjectSourceStore(file), clock = { now }).state()

            assertEquals(web, state.lineageOf(1))
            assertEquals(5, state.rows.getValue(1).lastPlayedMillis)
            assertNull(state.lineageOf(2))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an update that changes nothing does not write`() = runTest {
        table.setPlayed(1, web)
        val writes = store.writes

        table.updateRow(1) { it }

        assertEquals(writes, store.writes)
    }

    @Test
    fun `playing along a lineage is noted at most once a day`() = runTest {
        table.setPlayed(1, web)
        val writes = store.writes

        now += 60_000
        table.markPlayed(1)
        assertEquals(writes, store.writes)

        now += 86_400_000
        table.markPlayed(1)
        assertEquals(writes + 1, store.writes)
        assertEquals(now, table.state().rows.getValue(1).lastPlayedMillis)
    }

    // 上限

    private fun played(at: Long = 0) = SubjectSourceRow(played = "{}", lastPlayedMillis = at)

    @Test
    fun `nothing is dropped within the limit`() {
        val state = SubjectSourcesState(mapOf(1 to played()))

        assertTrue(state === state.trimmedTo(1))
    }

    @Test
    fun `the least recently played goes first`() {
        val state = SubjectSourcesState(mapOf(7 to played(at = 3), 8 to played(at = 1), 9 to played(at = 2)))

        assertEquals(setOf(7, 9), state.trimmedTo(2).rows.keys)
        assertEquals(setOf(7), state.trimmedTo(1).rows.keys)
    }

    @Test
    fun `an update over the limit keeps the most recently played`() = runTest {
        val table = SubjectSourceTable(store, maxRows = 3, trimTo = 2, clock = { now })

        for (id in 1..4) {
            now += 1_000
            table.setPlayed(id, torrent)
        }

        assertEquals(setOf(3, 4), table.state().rows.keys)
        assertEquals(setOf(3, 4), store.saved?.rows?.keys)
    }
}
