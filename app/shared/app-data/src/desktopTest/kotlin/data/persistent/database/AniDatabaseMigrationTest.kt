/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AniDatabase 的升级: 已发布的 Izuko TV 库 (26 起) 按 AutoMigration 升到当前版本, 数据不丢;
 * 更老的库与比当前新的库清空重建 (见 [withAniUpgradePolicy]).
 *
 * [MigrationTestHelper] 从 `schemas/<db fqn>/<version>.json` 建旧版本库, runMigrationsAndValidate 把迁移后的实际
 * schema 与目标版本 json 逐表逐列校验. 升级策略的几条用与应用相同的建库配置真的打开一次.
 */
class AniDatabaseMigrationTest {
    private val databasePath: Path = Files.createTempDirectory("ani-migration-test").resolve("test.db")

    private fun createHelper(): MigrationTestHelper = MigrationTestHelper(
        schemaDirectoryPath = resolveSchemaDirectory(),
        databasePath = databasePath,
        driver = BundledSQLiteDriver(),
        databaseClass = AniDatabase::class,
        databaseFactory = { AniDatabaseConstructor.initialize() },
    )

    /** 与应用相同的升级配置打开 [databasePath], 读一次让 Room 完成打开 (升级或清空) */
    private fun <R> openLikeTheApp(block: (SQLiteConnection) -> R): R = runBlocking {
        val db = Room.databaseBuilder<AniDatabase>(name = databasePath.toString()) { AniDatabaseConstructor.initialize() }
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .withAniUpgradePolicy()
            .build()
        try {
            db.useReaderConnection { connection ->
                connection.usePrepared("PRAGMA user_version") { it.step() }
            }
        } finally {
            db.close()
        }
        BundledSQLiteDriver().open(databasePath.toString()).use(block)
    }

    @Test
    fun `MIG-09 v26 to v27 adds the pending op table and two subject columns and keeps rows`() {
        val helper = createHelper()
        helper.createDatabase(26).use { connection ->
            connection.execSQL(SUBJECT_COLLECTION_INSERT)
        }
        helper.runMigrationsAndValidate(27, emptyList()).use { connection ->
            assertContains(connection.tableNames(), "episode_collection_pending_op")
            val columns = connection.columnNames("subject_collection")
            assertContains(columns, "imageThumb")
            assertContains(columns, "tmdbArt")
            assertContains(columns, "screeningYear")
            assertContains(columns, "theatrical")
            connection.prepare(
                "SELECT `nameCn`, `imageThumb`, `tmdbArt` FROM `subject_collection` WHERE `subjectId` = 1",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("cn", statement.getText(0))
                assertEquals("", statement.getText(1))
                assertTrue(statement.isNull(2))
            }
        }
    }

    @Test
    fun `MIG-11 v27 to v28 adds the split season column and keeps rows`() {
        val helper = createHelper()
        helper.createDatabase(27).use { connection ->
            connection.execSQL(SUBJECT_COLLECTION_INSERT)
        }
        helper.runMigrationsAndValidate(28, emptyList()).use { connection ->
            assertContains(connection.columnNames("subject_collection"), "relations_splitSeason")
            connection.prepare("SELECT `nameCn` FROM `subject_collection` WHERE `subjectId` = 1").use { statement ->
                assertTrue(statement.step())
                assertEquals("cn", statement.getText(0))
            }
        }
    }

    @Test
    fun `MIG-12 v28 to v29 adds the episode duration column and keeps rows`() {
        val helper = createHelper()
        helper.createDatabase(28).use { connection ->
            connection.execSQL(SUBJECT_COLLECTION_INSERT)
            connection.execSQL(EPISODE_COLLECTION_INSERT)
        }
        helper.runMigrationsAndValidate(29, emptyList()).use { connection ->
            assertContains(connection.columnNames("episode_collection"), "durationSeconds")
            connection.prepare("SELECT `nameCn`, `durationSeconds` FROM `episode_collection` WHERE `episodeId` = 10").use { statement ->
                assertTrue(statement.step())
                assertEquals("ep", statement.getText(0))
                assertTrue(statement.isNull(1))
            }
        }
    }

    @Test
    fun `MIG-13 v29 to v30 adds the cache location columns and keeps rows`() {
        val helper = createHelper()
        helper.createDatabase(29).use { connection ->
            connection.execSQL("INSERT INTO `torrent_cache` (`mediaId`, `torrentData`, `relativeDir`) VALUES ('m1', X'01', 'pieces/1')")
        }
        helper.runMigrationsAndValidate(30, emptyList()).use { connection ->
            assertContains(connection.columnNames("torrent_cache"), "location")
            assertContains(connection.columnNames("http_cache_download_state"), "location")
            connection.prepare("SELECT `relativeDir`, `location` FROM `torrent_cache` WHERE `mediaId` = 'm1'").use { statement ->
                assertTrue(statement.step())
                assertEquals("pieces/1", statement.getText(0))
                assertTrue(statement.isNull(1)) // 旧缓存都在内部存储
            }
        }
    }

    @Test
    fun `shipped v26 to v29 databases open with every row kept`() {
        for (version in listOf(26, 27, 28, 29)) {
            Files.deleteIfExists(databasePath)
            createHelper().createDatabase(version).use { it.insertUserData() }
            openLikeTheApp { connection ->
                assertEquals(30, connection.userVersion(), "from v$version")
                for ((table, count) in USER_DATA_ROWS) {
                    assertEquals(count, connection.count(table), "$table from v$version")
                }
                connection.prepare(
                    "SELECT `pathInTorrent`, `completed` FROM `torrent_cache_episode` WHERE `mediaId` = 'm1' AND `episodeId` = '10'",
                ).use { statement ->
                    assertTrue(statement.step(), "from v$version")
                    assertEquals("S02E10.mkv", statement.getText(0))
                    assertEquals(1, statement.getLong(1))
                }
            }
        }
    }

    @Test
    fun `databases older than v26 are recreated instead of failing to open`() {
        for (version in listOf(1, 16, 21, 22, 24, 25)) {
            Files.deleteIfExists(databasePath)
            createHelper().createDatabase(version).use { connection ->
                connection.execSQL("INSERT INTO `search_history` (`content`) VALUES ('old')")
            }
            openLikeTheApp { connection ->
                assertEquals(30, connection.userVersion(), "from v$version")
                assertEquals(0, connection.count("search_history"), "from v$version")
                assertContains(connection.tableNames(), "torrent_cache_episode")
            }
        }
    }

    @Test
    fun `a database from a newer version is recreated`() {
        createHelper().createDatabase(30).use { connection ->
            connection.insertUserData()
            connection.execSQL("PRAGMA user_version = 31")
        }
        openLikeTheApp { connection ->
            assertEquals(30, connection.userVersion())
            assertEquals(0, connection.count("search_history"))
        }
    }

    /** 收藏、搜索记录、播放进度与一集 BT 缓存记录, 26 起各表结构相同 */
    private fun SQLiteConnection.insertUserData() {
        execSQL(SUBJECT_COLLECTION_INSERT)
        execSQL("INSERT INTO `search_history` (`content`) VALUES ('bocchi')")
        execSQL("INSERT INTO `playback_history_record` (`episodeId`, `positionMillis`, `updatedAtMillis`) VALUES (1532459, 60000, 1)")
        execSQL("INSERT INTO `torrent_cache` (`mediaId`, `torrentData`, `relativeDir`) VALUES ('m1', X'01', 'pieces/1')")
        execSQL(
            "INSERT INTO `torrent_cache_episode` (`mediaId`, `episodeId`, `completed`, `pathInTorrent`, `downloadSize`, `uploadSize`) " +
                    "VALUES ('m1', '10', 1, 'S02E10.mkv', 123, 0)",
        )
    }

    private val USER_DATA_ROWS = mapOf(
        "subject_collection" to 1L,
        "search_history" to 1L,
        "playback_history_record" to 1L,
        "torrent_cache" to 1L,
        "torrent_cache_episode" to 1L,
    )

    /** v26 起 episode_collection 必填的列 (属于 [SUBJECT_COLLECTION_INSERT] 的条目) */
    private val EPISODE_COLLECTION_INSERT =
        "INSERT INTO `episode_collection` (`subjectId`, `episodeId`, `episodeType`, `name`, `nameCn`, `airDate`, `comment`, " +
                "`desc`, `sort`, `ep`, `selfCollectionType`, `lastFetched`) VALUES (1, 10, 'MainStory', 'n', 'ep', 0, 0, '', '1', '1', 'WISH', 0)"

    /** v26 起 subject_collection 必填的列; 其余列都有默认值或可空 */
    private val SUBJECT_COLLECTION_INSERT =
        "INSERT INTO `subject_collection` (`subjectId`, `name`, `nameCn`, `summary`, `nsfw`, `imageLarge`, " +
                "`totalEpisodes`, `airDate`, `aliases`, `tags`, `completeDate`, `collectionType`, " +
                "`collection_stats_wish`, `collection_stats_doing`, `collection_stats_done`, `collection_stats_onHold`, " +
                "`collection_stats_dropped`, `rating_rank`, `rating_total`, `rating_score`, `rating_count_s1`, " +
                "`rating_count_s2`, `rating_count_s3`, `rating_count_s4`, `rating_count_s5`, `rating_count_s6`, " +
                "`rating_count_s7`, `rating_count_s8`, `rating_count_s9`, `rating_count_s10`, `self_rating_score`, " +
                "`self_rating_tags`, `self_rating_isPrivate`) VALUES (1, 'n', 'cn', '', 0, '', 12, 0, X'5B5D', X'5B5D', 0, " +
                "'DOING', 0, 0, 0, 0, 0, 0, 0, '0', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, X'5B5D', 0)"

    private fun SQLiteConnection.userVersion(): Int =
        prepare("PRAGMA user_version").use { it.step(); it.getInt(0) }

    private fun SQLiteConnection.count(table: String): Long =
        prepare("SELECT COUNT(*) FROM `$table`").use { it.step(); it.getLong(0) }

    private fun SQLiteConnection.columnNames(table: String): Set<String> =
        prepare("PRAGMA table_info(`$table`)").use { statement ->
            buildSet {
                while (statement.step()) {
                    add(statement.getText(1))
                }
            }
        }

    private fun SQLiteConnection.tableNames(): Set<String> =
        prepare("SELECT `name` FROM sqlite_master WHERE `type` = 'table'").use { statement ->
            buildSet {
                while (statement.step()) {
                    add(statement.getText(0))
                }
            }
        }

    private fun resolveSchemaDirectory(): Path {
        val candidates = listOf(
            Paths.get("schemas"),
            Paths.get("app/shared/app-data/schemas"),
        )
        return candidates.firstOrNull {
            Files.isDirectory(it.resolve(AniDatabase::class.qualifiedName!!))
        }?.toAbsolutePath()
            ?: error(
                "Cannot locate Room schema directory. Tried ${candidates.map { it.toAbsolutePath() }} " +
                        "from working directory ${Paths.get("").toAbsolutePath()}",
            )
    }
}
