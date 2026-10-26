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
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import me.him188.ani.app.data.persistent.database.converters.DurationConverter
import me.him188.ani.app.data.persistent.database.converters.InstantConverter
import me.him188.ani.app.data.persistent.database.converters.PackedDateConverter
import me.him188.ani.app.data.persistent.database.dao.DanmakuDao
import me.him188.ani.app.data.persistent.database.dao.DanmakuEntity
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionDao
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionPendingOpDao
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionPendingOpEntity
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
import me.him188.ani.app.data.persistent.database.dao.SubjectRewatchDao
import me.him188.ani.app.data.persistent.database.dao.SubjectRelations
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectRelationsDao
import me.him188.ani.app.data.persistent.database.dao.SubjectReviewDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheEpisodeEntity
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
        TorrentCacheEpisodeEntity::class,
        DownloadState::class,
        DanmakuEntity::class,

        PreferredWebMediaSource::class,
        PlaybackHistoryRecordEntity::class,
        PlaybackHistoryPendingOpEntity::class,
        EpisodeCollectionPendingOpEntity::class,
        RecommendationFeedEntity::class, // 6.1.x: 探索页推荐的结果缓存
    ],
    version = 30,
    autoMigrations = [
        // 26 以前的库不升级, 打开时清空重建, 见 [OLDEST_UPGRADABLE_DATABASE_VERSION].
        // 26 -> 27: 新增 episode_collection_pending_op 表 (剧集看过状态的待同步操作, 见 [EpisodeCollectionPendingOpEntity]),
        // subject_collection 加 imageThumb (默认空字符串) / tmdbArt (可空) 两列. 都是纯加, 不需要 spec.
        // 这三项来自上游, 上游分三步 (24 -> 25 -> 26 -> 27) 加的; fork 的 24/25/26 已发布, 号不能复用, 合成这一步.
        AutoMigration(from = 26, to = 27),
        // 27 -> 28: subject_collection 加 relations_splitSeason (拆分季, 可空; 纯加列, 不需要 spec). 来自上游 27 -> 28;
        // 上游由服务端识别填它, fork 直连没有这份数据, 一直为空, 选源时照常按系列关系匹配.
        AutoMigration(from = 27, to = 28),
        // 28 -> 29: episode_collection 加 durationSeconds (Bangumi 记的这一集的时长, 可空; 纯加列). 旧行为空, 下次刷新分集时填上.
        AutoMigration(from = 28, to = 29),
        // 29 -> 30: torrent_cache 与 http_cache_download_state 各加 location (缓存在哪个盘, 可空 = 内部存储; 纯加列), 见 MediaCacheLocations.
        AutoMigration(from = 29, to = 30),
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
    abstract fun subjectRewatch(): SubjectRewatchDao

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
    abstract fun episodeCollectionPendingOpDao(): EpisodeCollectionPendingOpDao

    /**
     * @since 6.1.x
     */
    abstract fun recommendationFeedDao(): RecommendationFeedDao
}

expect object AniDatabaseConstructor : RoomDatabaseConstructor<AniDatabase> {
    override fun initialize(): AniDatabase
}
