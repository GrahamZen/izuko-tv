/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AniDatabase 迁移测试 (infra#10, P0#18).
 *
 * 生产迁移链 (CommonKoinModule): 1..15 destructive, 16 起走
 * AutoMigration 16→17→18→19, 手动 [MIGRATION_19_20], AutoMigration 20→21,
 * 手写 [MIGRATION_21_22] 与 [MIGRATION_24_25], AutoMigration 22→23→24.
 *
 * **版本号语义与上游不同**: fork 先用掉了 22/23/24 三个号, 上游同期也用掉了这三个
 * 号但内容完全不同, 于是上游那三步在这边合并成 24→25 一步. 跟上游 rebase 后别把
 * 这组测试里的版本号按上游的语义改回去.
 *
 * [MigrationTestHelper] 从 `schemas/<db fqn>/<version>.json` 建旧版本库,
 * runMigrationsAndValidate 会把迁移后的实际 schema 与目标版本 json 逐表逐列校验.
 */
class AniDatabaseMigrationTest {
    private fun createHelper(): MigrationTestHelper = MigrationTestHelper(
        schemaDirectoryPath = resolveSchemaDirectory(),
        databasePath = Files.createTempDirectory("ani-migration-test").resolve("test.db"),
        driver = BundledSQLiteDriver(),
        databaseClass = AniDatabase::class,
        databaseFactory = { AniDatabaseConstructor.initialize() },
    )

    @Test
    fun `MIG-01 v16建库经AutoMigration与手动19-20迁移到v21通过schema校验且关键表存在`() {
        val helper = createHelper()
        helper.createDatabase(16).use { connection ->
            connection.execSQL("INSERT INTO `search_history` (`content`) VALUES ('bocchi')")
        }
        helper.runMigrationsAndValidate(21, listOf(MIGRATION_19_20)).use { connection ->
            val tables = connection.tableNames()
            assertContains(tables, "subject_collection")
            assertContains(tables, "episode_collection")
            assertContains(tables, "episode_comment")
            assertContains(tables, "preferred_web_media_source")
            assertContains(tables, "playback_history_record")
            assertContains(tables, "playback_history_pending_op")

            connection.prepare("SELECT `content` FROM `search_history`").use { statement ->
                assertTrue(statement.step())
                assertEquals("bocchi", statement.getText(0))
                assertFalse(statement.step())
            }
        }
    }

    @Test
    fun `MIG-02 v19已有preferred_web_media_source行经手动19-20与AutoMigration到v21保留`() {
        val helper = createHelper()
        helper.createDatabase(19).use { connection ->
            connection.execSQL(
                "INSERT INTO `preferred_web_media_source` (`subjectId`, `mediaSourceId`) VALUES (42, 'web2')",
            )
            connection.execSQL(
                """
                INSERT INTO `episode_comment`
                    (`commentId`, `episodeId`, `parentCommentId`, `authorId`, `authorNickname`, `authorAvatarUrl`, `createdAt`, `content`)
                VALUES (1, 1, NULL, 1, 'nick', NULL, 0, 'stale')
                """.trimIndent(),
            )
        }
        helper.runMigrationsAndValidate(21, listOf(MIGRATION_19_20)).use { connection ->
            connection.prepare("SELECT `subjectId`, `mediaSourceId` FROM `preferred_web_media_source`").use { statement ->
                assertTrue(statement.step())
                assertEquals(42, statement.getInt(0))
                assertEquals("web2", statement.getText(1))
                assertFalse(statement.step())
            }
            // PINNED: MIG-02 手动迁移 19→20 DROP 重建 episode_comment, 旧评论数据全部丢弃
            connection.prepare("SELECT COUNT(*) FROM `episode_comment`").use { statement ->
                assertTrue(statement.step())
                assertEquals(0, statement.getInt(0))
            }
        }
    }

    @Test
    fun `MIG-03 v20到v21的AutoMigration增加播放记录表`() {
        val helper = createHelper()
        helper.createDatabase(20).use { connection ->
            assertFalse(connection.tableNames().contains("playback_history_record"))
        }
        helper.runMigrationsAndValidate(21, emptyList()).use { connection ->
            val tables = connection.tableNames()
            assertContains(tables, "playback_history_record")
            assertContains(tables, "playback_history_pending_op")
        }
    }

    @Test
    fun `MIG-05 v21到v22的手写迁移把torrent_cache拆成种子级表并新建按集文件表`() {
        val helper = createHelper()
        helper.createDatabase(21).use { connection ->
            // v21 的 torrent_cache 是"按集"的: 完成状态与文件路径都在这张表上
            connection.execSQL(
                "INSERT INTO `torrent_cache` (`mediaId`, `torrentData`, `relativeDir`, `completed`, `pathInTorrent`, " +
                        "`downloadSize`, `uploadSize`) VALUES ('dmhy.1', X'00', 'dir', 1, 'a.mkv', 100, 20)",
            )
        }
        helper.runMigrationsAndValidate(22, listOf(MIGRATION_21_22)).use { connection ->
            // PINNED: MIG-05 torrent_cache 重建为种子级 (只剩三列), 种子本身与落盘目录必须保留 ——
            // 丢了这两列等于认不回已下好的文件
            assertEquals(setOf("mediaId", "torrentData", "relativeDir"), connection.columnNames("torrent_cache"))
            connection.prepare("SELECT `relativeDir` FROM `torrent_cache` WHERE `mediaId` = 'dmhy.1'").use { statement ->
                assertTrue(statement.step())
                assertEquals("dir", statement.getText(0))
            }
            // 按集文件表新建但留空 (自愈: 下次校验缓存时按种子内容重新登记)
            assertContains(connection.tableNames(), "torrent_cache_file")
            connection.prepare("SELECT COUNT(*) FROM `torrent_cache_file`").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
    }

    @Test
    fun `MIG-06 v22到v23的AutoMigration删除旧web搜索缓存表并新建session缓存表`() {
        val helper = createHelper()
        helper.createDatabase(22).use { connection ->
            val tables = connection.tableNames()
            assertContains(tables, "web_search_subject")
            assertContains(tables, "web_search_episode")
        }
        helper.runMigrationsAndValidate(23, emptyList()).use { connection ->
            val tables = connection.tableNames()
            // PINNED: MIG-06 旧的两张表被 @DeleteTable 删除, 其中的数据 (会话级缓存) 全部丢弃
            assertFalse(tables.contains("web_search_subject"))
            assertFalse(tables.contains("web_search_episode"))
            assertContains(tables, "web_search_session_cache")
        }
    }

    @Test
    fun `MIG-07 v23到v24的AutoMigration为subject_collection增加上映年份与影院列且旧行保留`() {
        val helper = createHelper()
        helper.createDatabase(23).use { connection ->
            connection.execSQL(SUBJECT_COLLECTION_INSERT)
        }
        helper.runMigrationsAndValidate(24, emptyList()).use { connection ->
            val columns = connection.columnNames("subject_collection")
            assertContains(columns, "screeningYear")
            assertContains(columns, "theatrical")
            connection.prepare("SELECT `nameCn` FROM `subject_collection` WHERE `subjectId` = 1").use { statement ->
                assertTrue(statement.step())
                assertEquals("cn", statement.getText(0))
            }
        }
    }

    /**
     * 这一步把上游的 22→23 与 23→24 合并了 (见 Migrations.Migration_24_25), 一次动四处,
     * 是 fork 迁移链上最重的一步, 所以逐项钉住.
     */
    @Test
    fun `MIG-08 v24到v25合并上游两步加剧照列与按集缓存表并删掉fork的torrent_cache_file`() {
        val helper = createHelper()
        helper.createDatabase(24).use { connection ->
            connection.execSQL(SUBJECT_COLLECTION_INSERT)
            connection.execSQL(
                "INSERT INTO `episode_collection` (`subjectId`, `episodeId`, `episodeType`, `name`, `nameCn`, `airDate`, " +
                        "`comment`, `desc`, `sort`, `sortNumber`, `ep`, `selfCollectionType`, `lastFetched`) " +
                        "VALUES (1, 10, NULL, 'ep', '第1集', 0, 0, '', '1', 1.0, NULL, 'WISH', 0)",
            )
            connection.execSQL(
                "INSERT INTO `torrent_cache` (`mediaId`, `torrentData`, `relativeDir`) VALUES ('dmhy.1', X'00', 'dir')",
            )
            // fork 在 22..24 期间把"哪一集下完了、文件在种子里的哪个路径"记在这张表上
            connection.execSQL(
                "INSERT INTO `torrent_cache_file` (`mediaId`, `subjectId`, `episodeId`, `pathInTorrent`, `completed`, " +
                        "`downloadSize`, `uploadSize`) VALUES ('dmhy.1', '1', '10', 'a.mkv', 1, 100, 20)",
            )
            // 没下完的那一条也要照原样搬 (不能一律当成已完成)
            connection.execSQL(
                "INSERT INTO `torrent_cache_file` (`mediaId`, `subjectId`, `episodeId`, `pathInTorrent`, `completed`, " +
                        "`downloadSize`, `uploadSize`) VALUES ('dmhy.1', '1', '11', 'b.mkv', 0, 30, 0)",
            )
            // 旧表主键带 subjectId, 新表没有: 同一 (资源, 集) 撞上两条时取 completed 大的那条
            connection.execSQL(
                "INSERT INTO `torrent_cache_file` (`mediaId`, `subjectId`, `episodeId`, `pathInTorrent`, `completed`, " +
                        "`downloadSize`, `uploadSize`) VALUES ('dmhy.1', '2', '10', 'a.mkv', 0, 1, 0)",
            )
        }
        helper.runMigrationsAndValidate(25, listOf(MIGRATION_24_25)).use { connection ->
            val tables = connection.tableNames()
            // PINNED: MIG-08 fork 那张按集表被 @DeleteTable 删除, 换成上游的 torrent_cache_episode
            assertFalse(tables.contains("torrent_cache_file"))
            assertContains(tables, "torrent_cache_episode")

            /*
             * **按集的完成状态必须原样搬过来**。
             *
             * 这里原先钉的是反的 —— 断言搬完之后新表是空的, 理由写着"上游 #3442 的回退逻辑会按种子内容
             * 重新认领文件"。那个前提是错的, 而且**下面这两行断言自己就能证伪**: 回退的入口条件是
             * `if (!torrent.completed || torrent.pathInTorrent.isEmpty()) return null`, 而 torrent_cache
             * 上这两列正是本步新加的、取的就是默认值 `0` / `""` —— 回退永远不会触发。
             * 于是升级后不是"稍后认领", 而是所有 BT 缓存的已完成记录全丢, 整包番被当成没缓存重下
             * (2026-09-20 真机实证)。修法见 [MIGRATION_24_25]。
             */
            connection.prepare(
                "SELECT `episodeId`, `completed`, `pathInTorrent`, `downloadSize`, `uploadSize` " +
                        "FROM `torrent_cache_episode` WHERE `mediaId` = 'dmhy.1' ORDER BY `episodeId`",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("10", statement.getText(0))
                // 撞上的那两条取 completed 大的, 连同它那一行的其余列
                assertEquals(1L, statement.getLong(1))
                assertEquals("a.mkv", statement.getText(2))
                assertEquals(100L, statement.getLong(3))
                assertEquals(20L, statement.getLong(4))

                assertTrue(statement.step())
                assertEquals("11", statement.getText(0))
                // 没下完的照原样搬, 不能一律当成已完成
                assertEquals(0L, statement.getLong(1))
                assertEquals("b.mkv", statement.getText(2))
                assertEquals(30L, statement.getLong(3))

                assertFalse(statement.step())
            }
            connection.prepare(
                "SELECT `relativeDir`, `completed`, `pathInTorrent` FROM `torrent_cache` WHERE `mediaId` = 'dmhy.1'",
            ).use { statement ->
                assertTrue(statement.step())
                // 种子本身与落盘目录保留
                assertEquals("dir", statement.getText(0))
                // PINNED: torrent_cache 上这两列是本步新加的, 取默认值 —— 上游的回退逻辑正是读它们,
                // 所以对 fork 用户永远不成立, 数据只能靠上面那次搬运保住.
                assertEquals(0L, statement.getLong(1))
                assertEquals("", statement.getText(2))
            }

            // 剧照直链: 新列加上, 旧行为 NULL, 其余数据保留
            val columns = connection.columnNames("episode_collection")
            assertContains(columns, "imageMedium")
            assertContains(columns, "imageLarge")
            connection.prepare(
                "SELECT `imageMedium`, `imageLarge`, `nameCn` FROM `episode_collection` WHERE `episodeId` = 10",
            ).use { statement ->
                assertTrue(statement.step())
                assertTrue(statement.isNull(0))
                assertTrue(statement.isNull(1))
                assertEquals("第1集", statement.getText(2))
            }
        }
    }

    @Test
    fun `MIG-04 缺失手动19-20迁移时从v16迁移到v21失败`() {
        val helper = createHelper()
        helper.createDatabase(16).use {}
        val exception = assertFails {
            helper.runMigrationsAndValidate(21, emptyList())
        }
        assertContains(exception.message.orEmpty(), "A migration from 16 to 21 was required but not found")
    }

    /** v22..v24 的 subject_collection 列相同; 其余列都有默认值或可空, 只填必要的. */
    private val SUBJECT_COLLECTION_INSERT =
        "INSERT INTO `subject_collection` (`subjectId`, `name`, `nameCn`, `summary`, `nsfw`, `imageLarge`, " +
                "`totalEpisodes`, `airDate`, `aliases`, `tags`, `completeDate`, `collectionType`, " +
                "`collection_stats_wish`, `collection_stats_doing`, `collection_stats_done`, `collection_stats_onHold`, " +
                "`collection_stats_dropped`, `rating_rank`, `rating_total`, `rating_score`, `rating_count_s1`, " +
                "`rating_count_s2`, `rating_count_s3`, `rating_count_s4`, `rating_count_s5`, `rating_count_s6`, " +
                "`rating_count_s7`, `rating_count_s8`, `rating_count_s9`, `rating_count_s10`, `self_rating_score`, " +
                "`self_rating_tags`, `self_rating_isPrivate`) VALUES (1, 'n', 'cn', '', 0, '', 12, 0, X'5B5D', X'5B5D', 0, " +
                "'DOING', 0, 0, 0, 0, 0, 0, 0, '0', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, X'5B5D', 0)"

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
