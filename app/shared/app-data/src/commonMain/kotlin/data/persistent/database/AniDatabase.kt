/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.RenameColumn
import androidx.room.RenameTable
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import me.him188.ani.app.data.persistent.database.converters.DurationConverter
import me.him188.ani.app.data.persistent.database.converters.InstantConverter
import me.him188.ani.app.data.persistent.database.converters.PackedDateConverter
import me.him188.ani.app.data.persistent.database.dao.DanmakuDao
import me.him188.ani.app.data.persistent.database.dao.DanmakuEntity
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionDao
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.EpisodeCommentDao
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryDao
import me.him188.ani.app.data.persistent.database.dao.RecommendationFeedDao
import me.him188.ani.app.data.persistent.database.dao.RecommendationFeedEntity
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryPendingOpEntity
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryRecordEntity
import me.him188.ani.app.data.persistent.database.dao.PreferredWebMediaSource
import me.him188.ani.app.data.persistent.database.dao.PreferredWebMediaSourceDao
import me.him188.ani.app.data.persistent.database.dao.SearchHistoryDao
import me.him188.ani.app.data.persistent.database.dao.SearchHistoryEntity
import me.him188.ani.app.data.persistent.database.dao.SearchTagDao
import me.him188.ani.app.data.persistent.database.dao.SearchTagEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectRelationsDao
import me.him188.ani.app.data.persistent.database.dao.SubjectReviewDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheFileEntity
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.data.persistent.database.dao.WebSearchSessionCacheDao
import me.him188.ani.app.data.persistent.database.dao.WebSearchSessionCacheEntity
import me.him188.ani.app.data.persistent.database.entity.CharacterActorEntity
import me.him188.ani.app.data.persistent.database.entity.CharacterEntity
import me.him188.ani.app.data.persistent.database.entity.EpisodeCommentEntity
import me.him188.ani.app.data.persistent.database.entity.PersonEntity
import me.him188.ani.app.data.persistent.database.entity.SubjectCharacterRelationEntity
import me.him188.ani.app.data.persistent.database.entity.SubjectPersonRelationEntity
import me.him188.ani.app.data.persistent.database.entity.SubjectReviewEntity
import me.him188.ani.utils.httpdownloader.DownloadState

@Database(
    entities = [
        SearchHistoryEntity::class,
        SearchTagEntity::class,
        SubjectCollectionEntity::class,
        EpisodeCollectionEntity::class,

        PersonEntity::class, // 4.0.0-alpha04
        SubjectPersonRelationEntity::class, // 4.0.0-alpha04

        CharacterEntity::class, // 4.0.0-alpha04
        SubjectCharacterRelationEntity::class, // 4.0.0-alpha04
        CharacterActorEntity::class, // 4.0.0-alpha04

        SubjectReviewEntity::class,
        EpisodeCommentEntity::class,

        WebSearchSessionCacheEntity::class,

        TorrentCacheInfoEntity::class,
        TorrentCacheFileEntity::class, // 5.7.1: 按集存储种子内文件, 修复全集种子多集共用串台
        DownloadState::class,
        DanmakuEntity::class,

        PreferredWebMediaSource::class,
        PlaybackHistoryRecordEntity::class,
        PlaybackHistoryPendingOpEntity::class,
        RecommendationFeedEntity::class, // 6.1.x: 探索页推荐的结果缓存
    ],
    version = 25,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = Migrations.Migration_1_2::class),
        AutoMigration(from = 2, to = 3, spec = Migrations.Migration_2_3::class),
        AutoMigration(from = 3, to = 4, spec = Migrations.Migration_3_4::class),
        AutoMigration(from = 4, to = 5, spec = Migrations.Migration_4_5::class),
        AutoMigration(from = 5, to = 6, spec = Migrations.Migration_5_6::class),
        AutoMigration(from = 6, to = 7, spec = Migrations.Migration_6_7::class),
        AutoMigration(from = 7, to = 8, spec = Migrations.Migration_7_8::class),
        AutoMigration(from = 8, to = 9, spec = Migrations.Migration_8_9::class),
        AutoMigration(from = 9, to = 10, spec = Migrations.Migration_9_10::class),
        AutoMigration(from = 10, to = 11, spec = Migrations.Migration_10_11::class),
        AutoMigration(from = 11, to = 12, spec = Migrations.Migration_11_12::class),
        AutoMigration(from = 12, to = 13, spec = Migrations.Migration_12_13::class),
        AutoMigration(from = 13, to = 14, spec = Migrations.Migration_13_14::class),
        AutoMigration(from = 14, to = 15, spec = Migrations.Migration_14_15::class),
        // 15 to 16 is destructive
        AutoMigration(from = 16, to = 17, spec = Migrations.Migration_16_17::class),
        AutoMigration(from = 17, to = 18, spec = Migrations.Migration_17_18::class),
        AutoMigration(from = 18, to = 19, spec = Migrations.Migration_18_19::class),
        AutoMigration(from = 20, to = 21, spec = Migrations.Migration_20_21::class),
        AutoMigration(from = 22, to = 23, spec = Migrations.Migration_22_23::class),
        // 23 -> 24: subject_collection 加 screeningYear / theatrical 两列 (纯加列, 有默认值,
        // 不需要 spec). 供 TMDB 匹配判断"是不是只在影院放映"与"真正的上映年份", 见
        // [SubjectCollectionEntity.screeningYear].
        AutoMigration(from = 23, to = 24),
        // 24 -> 25: 新增 recommendation_feed 表 (纯加表, 不需要 spec). 探索页推荐改成
        // "缓存住 + 后台重算", 见 [RecommendationFeedEntity].
        AutoMigration(from = 24, to = 25),
    ],
    exportSchema = true,
)
@ConstructedBy(AniDatabaseConstructor::class)
@TypeConverters(
    PackedDateConverter::class,
    DurationConverter::class,
    InstantConverter::class,
    EpisodeSortConverter::class,
    DanmakuServiceIdConverter::class,
)
abstract class AniDatabase : RoomDatabase() {
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun searchTag(): SearchTagDao
    abstract fun subjectCollection(): SubjectCollectionDao
    abstract fun episodeCollection(): EpisodeCollectionDao

    /**
     * @since 4.0.0-alpha04
     */
    abstract fun subjectRelations(): SubjectRelationsDao

    /**
     * @since 4.1.0-alpha02
     */
    abstract fun subjectReviews(): SubjectReviewDao

    /**
     * @since 4.1.0-alpha02
     */
    abstract fun episodeCommentDao(): EpisodeCommentDao

    /**
     * @since 6.1.0
     */
    abstract fun webSearchSessionCacheDao(): WebSearchSessionCacheDao

    /**
     * @since 5.1.0
     */
    abstract fun torrentCacheInfoDao(): TorrentCacheInfoDao
    abstract fun httpCacheDownloadStateDao(): HttpCacheDownloadStateDao

    /**
     * @since 5.3.0
     */
    abstract fun danmakuDao(): DanmakuDao
    abstract fun preferredWebMediaSourceDao(): PreferredWebMediaSourceDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao

    /**
     * @since 6.1.x
     */
    abstract fun recommendationFeedDao(): RecommendationFeedDao
}

expect object AniDatabaseConstructor : RoomDatabaseConstructor<AniDatabase> {
    override fun initialize(): AniDatabase
}

val MIGRATION_19_20 = object : Migration(startVersion = 19, endVersion = 20) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `episode_comment`")
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `episode_comment` (
                `episodeId` INTEGER NOT NULL,
                `commentId` TEXT NOT NULL,
                `authorId` TEXT NOT NULL,
                `parentCommentId` TEXT,
                `authorNickname` TEXT NOT NULL,
                `authorAvatarUrl` TEXT,
                `createdAt` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                PRIMARY KEY(`commentId`),
                FOREIGN KEY(`episodeId`) REFERENCES `episode_collection`(`episodeId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`parentCommentId`) REFERENCES `episode_comment`(`commentId`) ON UPDATE NO ACTION ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_episode_comment_episodeId` ON `episode_comment` (`episodeId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_episode_comment_parentCommentId` ON `episode_comment` (`parentCommentId`)",
        )
    }
}

/**
 * 5.7.1: 把 torrent 缓存的"按集字段" (pathInTorrent/completed/下载量) 从种子级 [torrent_cache]
 * 拆到按集表 [torrent_cache_file] (主键 mediaId+subjectId+episodeId).
 *
 * 修复: 全集种子被多集共用时, 各集共享同一行导致 pathInTorrent 互相覆盖、播放/索引到错误集数.
 *
 * 迁移策略: 保留种子级数据 (torrentData/relativeDir) 以复用已下载文件; 按集表留空,
 * 下次播放每集时通过 selectVideoFileEntry 重新按集解析 + libtorrent 校验已有文件 (不重下) 自愈.
 */
val MIGRATION_21_22 = object : Migration(startVersion = 21, endVersion = 22) {
    override fun migrate(connection: SQLiteConnection) {
        // 1. 把 torrent_cache 重建为"种子级"表 (去掉按集字段), 保留 torrentData / relativeDir
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `torrent_cache_new` (
                `mediaId` TEXT NOT NULL,
                `torrentData` BLOB NOT NULL,
                `relativeDir` TEXT NOT NULL,
                PRIMARY KEY(`mediaId`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """INSERT OR IGNORE INTO `torrent_cache_new` (`mediaId`, `torrentData`, `relativeDir`)
                SELECT `mediaId`, `torrentData`, `relativeDir` FROM `torrent_cache`""",
        )
        connection.execSQL("DROP TABLE `torrent_cache`")
        connection.execSQL("ALTER TABLE `torrent_cache_new` RENAME TO `torrent_cache`")
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_torrent_cache_mediaId` ON `torrent_cache` (`mediaId`)",
        )

        // 2. 新建按集文件表 (留空, 自愈)
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `torrent_cache_file` (
                `mediaId` TEXT NOT NULL,
                `subjectId` TEXT NOT NULL,
                `episodeId` TEXT NOT NULL,
                `pathInTorrent` TEXT NOT NULL,
                `completed` INTEGER NOT NULL,
                `downloadSize` INTEGER NOT NULL,
                `uploadSize` INTEGER NOT NULL,
                PRIMARY KEY(`mediaId`, `subjectId`, `episodeId`)
            )
            """.trimIndent(),
        )
    }
}

@Suppress("ClassName")
internal object Migrations {

    /**
     * 只增加了新的表
     *
     * @since 4.0.0-alpha03
     */
    class Migration_1_2 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * @since 4.0.0-alpha03
     */
    class Migration_2_3 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `distinct_content` ON `search_history`(`content`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `sequence_desc` ON `search_history`(`sequence` DESC)")
        }
    }

    /**
     * 增加了以下表:
     *
     * - [PersonEntity]
     * - [SubjectPersonRelationEntity]
     *
     * - [CharacterEntity]
     * - [SubjectCharacterRelationEntity]
     * - [CharacterActorEntity]
     *
     * DAO:
     * - [SubjectRelationsDao]
     *
     * @since 4.0.0-alpha04
     */
    class Migration_3_4 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * - [CharacterActorEntity] 改名
     *
     * @since 4.0.0-alpha04
     */
    @RenameTable("related_character", "character_actor")
    class Migration_4_5 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * - Added [SubjectCollectionEntity.cachedCharactersUpdated]
     * - Added [SubjectCollectionEntity.cachedStaffUpdated]
     *
     * @since 4.0.0-alpha04
     */
    class Migration_5_6 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * - Removed [SubjectCollectionEntity].`_index`. Primary key changed to [SubjectCollectionEntity.subjectId].
     * - Removed [SubjectCollectionEntity] index `Index(value = ["subjectId"], unique = true),`
     * - [SubjectCollectionDao.filterByCollectionTypePaging] 使用 [SubjectCollectionEntity.lastUpdated] 排序.
     * @since 4.0.0-beta01
     */
    @DeleteColumn("subject_collection", "_index")
    class Migration_6_7 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Added [SubjectCollectionEntity.lastFetched]
     * @since 4.0.0-beta03
     */
    class Migration_7_8 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Renamed [EpisodeCollectionEntity].`lastUpdated` to [EpisodeCollectionEntity.lastFetched]
     * @since 4.0.0-beta04
     */
    @RenameColumn("episode_collection", "lastUpdated", "lastFetched")
    class Migration_8_9 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Added [SubjectCollectionEntity.recurrence]
     * @since 4.1.0-alpha01
     */
    class Migration_9_10 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Added [SubjectReviewEntity], [SubjectReviewDao],
     * [EpisodeCommentDao], [EpisodeCommentEntity]
     *
     * @since 4.1.0-alpha02
     */
    class Migration_10_11 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Added [EpisodeCollectionEntity.sortNumber]
     * @since 4.1.0-alpha02
     */
    class Migration_11_12 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Added [WebSearchSubjectInfoEntity], [WebSearchEpisodeInfoEntity],
     * [WebSearchSubjectInfoDao], [WebSearchEpisodeInfoDao]
     * @since 4.1.0-alpha03
     */
    class Migration_12_13 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * [SubjectCollectionEntity.lastUpdated] now defaults to `0` instead of `CURRENT_TIMESTAMP`.
     *
     * @since 4.9.0-alpha03
     */
    class Migration_13_14 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Added [SubjectCollectionEntity.relations].
     *
     * @since 5.0.0
     */
    class Migration_14_15 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Added [TorrentCacheInfoEntity] and [DownloadState].
     *
     * @since 5.2.0
     */
    class Migration_16_17 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Added [DanmakuEntity].
     *
     * @since 5.3.0
     */
    class Migration_17_18 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Added [PreferredWebMediaSource].
     *
     * @since 5.3.0
     */
    class Migration_18_19 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * 增加了播放记录和待同步操作表.
     *
     * @since 5.3.0
     */
    class Migration_20_21 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }

    /**
     * Web 源搜索缓存改用新表 [WebSearchSessionCacheEntity] (播放 session 级缓存, 完整复合唯一键),
     * 删除旧的 `web_search_subject` / `web_search_episode`.
     *
     * **上游把这一步放在 21 -> 22, fork 挪到了 22 -> 23**: fork 的 22 已经发出去了 (那一版是
     * 按集拆分 torrent 缓存, 见 [MIGRATION_21_22]), 两边的 22 内容不同. 若把上游这步也塞进 22,
     * 已装 fork 的用户升级后库版本没变而 schema 变了, Room 校验 identityHash 直接抛
     * "Room cannot verify the data integrity" —— 开都开不起来. 推到 23 就都对得上:
     * 21 的用户走 fork 的手写 21->22 再走这条, 22 的用户直接走这条.
     */
    @DeleteTable("web_search_episode")
    @DeleteTable("web_search_subject")
    class Migration_22_23 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
        }
    }
}
