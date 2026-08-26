/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.compose.runtime.Immutable
import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.schedule.AnimeRecurrence
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.persistent.database.ProtoConverters
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.hours

/**
 * @see SubjectInfo
 */
@Entity(
    tableName = "subject_collection",
    indices = [
        Index(value = ["lastUpdated"], unique = false, orders = [Index.Order.DESC]),
    ],
)
data class SubjectCollectionEntity(
    @PrimaryKey val subjectId: Int,

    // SubjectInfo
    val name: String,
    val nameCn: String,
    val summary: String,
    val nsfw: Boolean,
    val imageLarge: String,
    /**
     * 会在获取剧集列表时使用, 用于验证缓存的剧集数目是否正确
     */
    val totalEpisodes: Int,
    val airDate: PackedDate,
    @field:TypeConverters(ProtoConverters.StringList::class)
    val aliases: List<String>,
    @field:TypeConverters(ProtoConverters.TagList::class)
    val tags: List<Tag>,
    @Embedded(prefix = "collection_stats_")
    val collectionStats: SubjectCollectionStats,
    @Embedded(prefix = "rating_")
    val ratingInfo: RatingInfo,
    val completeDate: PackedDate,
    // SubjectCollectionInfo

    @Embedded(prefix = "self_rating_")
    val selfRatingInfo: SelfRatingInfo,
    val collectionType: UnifiedCollectionType,

    /**
     * @since 4.1.0-alpha01
     */
    @Embedded(prefix = "recurrence_")
    val recurrence: AnimeRecurrence?,

    /**
     * @since 5.0.0
     */
    @Embedded(prefix = "relations_")
    val relations: SubjectRelations = SubjectRelations.Empty,

    /**
     * infobox 「上映年度」里最早的那个年份; `null` 表示条目没有这个字段.
     *
     * [airDate] 对**剧场上映的 OVA** 常常记的是「发售日」而非首映日 (如「彼女と彼女の猫」
     * 发售 2002-04-19 / 上映 2000-04-23, 而 TMDB 记首映 1999-10-01), TMDB 匹配的年份判据
     * 因此会误伤. 只取最早那个: 老片的 infobox 常把 4K 重映年也列进去 (攻殻機動隊 是
     * `[1995, 2025]`), 全盘接受会把年份判据放宽到没用.
     *
     * @since 6.0.4
     */
    @ColumnInfo(defaultValue = "NULL")
    val screeningYear: Int? = null,
    /**
     * 是否为**只在影院放映**的条目 (infobox 有「上映年度」而没有「放送开始」).
     *
     * 用于 TMDB 匹配时决定要不要先搜 movie. 不用服务端的 `platform` 字段是因为它是个
     * 含义未标注的整数; 而这个 infobox 判据实测 196 个剧场版条目判对 195 个.
     *
     * @since 6.0.4
     */
    @ColumnInfo(defaultValue = "0")
    val theatrical: Boolean = false,

    /**
     * 此条目最后被修改的时间 (如修改收藏状态). 与服务器同步.
     */
    @ColumnInfo(defaultValue = "0")
    val lastUpdated: Long,
    /**
     * 此条目从 bangumi 服务器上查询到的时间. 用于判断是否需要自动刷新
     */
    @ColumnInfo(defaultValue = "0")
    val lastFetched: Long,
    @ColumnInfo(defaultValue = "0")
    val cachedStaffUpdated: Long,
    @ColumnInfo(defaultValue = "0")
    val cachedCharactersUpdated: Long,
)

@Immutable // don't change field name, stored in database
data class SubjectRelations(
    @ColumnInfo(defaultValue = "'[]'")
    @field:TypeConverters(ProtoConverters.IntList::class)
    val seriesMainSubjectIds: List<Int>,
    @ColumnInfo(defaultValue = "'[]'")
    @field:TypeConverters(ProtoConverters.StringList::class)
    val seriesMainSubjectNames: List<String>,
    @ColumnInfo(defaultValue = "'[]'")
    @field:TypeConverters(ProtoConverters.IntList::class)
    val sequelSubjects: List<Int>,
    @ColumnInfo(defaultValue = "'[]'")
    @field:TypeConverters(ProtoConverters.StringList::class)
    val sequelSubjectNames: List<String>,
) {
    companion object {
        val Empty = SubjectRelations(
            seriesMainSubjectIds = emptyList(),
            seriesMainSubjectNames = emptyList(),
            sequelSubjects = emptyList(),
            sequelSubjectNames = emptyList(),
        )
    }
}

@Dao
interface SubjectCollectionDao {
    @Upsert
    suspend fun upsert(item: SubjectCollectionEntity)

    @Upsert
    @Transaction
    suspend fun upsert(item: List<SubjectCollectionEntity>)

    // ==== 条目 + 分集的单事务落库 (episode 表的操作定义在本 DAO: @Transaction 的默认实现
    //      只能调本 DAO 的方法, 而这两张表必须同事务) ====

    @Upsert
    suspend fun upsertEpisodesInternal(items: List<EpisodeCollectionEntity>)

    @Query("""SELECT episodeId FROM episode_collection WHERE subjectId = :subjectId""")
    suspend fun episodeIdsOf(subjectId: Int): List<Int>

    @Query("""DELETE FROM episode_collection WHERE subjectId = :subjectId AND episodeId IN (:episodeIds)""")
    suspend fun deleteEpisodesByIds(subjectId: Int, episodeIds: List<Int>)

    @Query("""SELECT subjectId, cachedStaffUpdated, cachedCharactersUpdated FROM subject_collection WHERE subjectId IN (:subjectIds)""")
    suspend fun relationsFreshnessOf(subjectIds: List<Int>): List<RelationsFreshness>

    /**
     * **保留 relations 的"盖章"**: 网络来的条目数据里没有 cachedStaff/CharactersUpdated,
     * toEntity 只能填 0 —— 整行 @Upsert 会把 SubjectRelationsRepository 刚写的时间戳抹掉,
     * 详情页开着时角色/制作人员被判"过期"强制重取一遍 (真机日志: 条目主体落库 400ms 后
     * 同一个 /characters 又打了一遍).
     */
    private suspend fun List<SubjectCollectionEntity>.preservingRelationsFreshness(): List<SubjectCollectionEntity> {
        val freshness = relationsFreshnessOf(map { it.subjectId }).associateBy { it.subjectId }
        return map { entity ->
            val f = freshness[entity.subjectId] ?: return@map entity
            entity.copy(
                cachedStaffUpdated = f.cachedStaffUpdated,
                cachedCharactersUpdated = f.cachedCharactersUpdated,
            )
        }
    }

    /**
     * 单条目 + 其分集的完整落库, **单个事务**: 中途取消/失败就整体回滚, 不会留下
     * "subject 已盖新 lastFetched 但分集残缺"的中间态 (那会让下一次判"新鲜"跳过刷新,
     * 选集残缺一整个缓存周期). 差集删除 (服务器已删的集) 也在同一事务里.
     */
    @Transaction
    suspend fun upsertSubjectWithEpisodes(
        subject: SubjectCollectionEntity,
        episodes: List<EpisodeCollectionEntity>,
    ) {
        upsert(listOf(subject).preservingRelationsFreshness().single())
        val newIds = episodes.mapTo(HashSet()) { it.episodeId }
        val staleIds = episodeIdsOf(subject.subjectId).filter { it !in newIds }
        upsertEpisodesInternal(episodes)
        if (staleIds.isNotEmpty()) deleteEpisodesByIds(subject.subjectId, staleIds)
    }

    /** 批量版 (收藏列表分页): 同样保留盖章 + 条目与分集同事务; 不做差集删除 (与原行为一致). */
    @Transaction
    suspend fun upsertSubjectsWithEpisodes(
        subjects: List<SubjectCollectionEntity>,
        episodes: List<EpisodeCollectionEntity>,
    ) {
        upsert(subjects.preservingRelationsFreshness())
        upsertEpisodesInternal(episodes)
    }

    @Query("""UPDATE subject_collection SET collectionType = :collectionType, lastUpdated = :lastUpdated WHERE subjectId = :subjectId""")
    suspend fun updateType(
        subjectId: Int,
        collectionType: UnifiedCollectionType,
        lastUpdated: Long = currentTimeMillis(),
    )

    @Query("""DELETE FROM subject_collection WHERE subjectId = :subjectId""")
    suspend fun delete(subjectId: Int)

    /**
     * 删除多个条目的本地缓存. 剧集缓存 ([EpisodeCollectionEntity]) 会级联删除.
     */
    @Query("""DELETE FROM subject_collection WHERE subjectId IN (:subjectIds)""")
    suspend fun deleteByIds(subjectIds: List<Int>)

    /**
     * 将所有条目的 [SubjectCollectionEntity.lastFetched] 置 0, 使所有本地缓存视为已过期,
     * 下次进入收藏页 (分页刷新) 或条目页时会从服务端重新拉取. 不删除本地数据.
     */
    @Query("""UPDATE subject_collection SET lastFetched = 0""")
    suspend fun resetAllLastFetched()

    @Query("""DELETE FROM subject_collection WHERE collectionType = :type""")
    suspend fun deleteAll(type: UnifiedCollectionType)

    @Query("""DELETE FROM subject_collection""")
    suspend fun deleteAll()

    /**
     * Retrieves a paginated list of `SubjectCollectionEntity` items, optionally filtered by type.
     *
     * @param collectionTypes Optional filter for the `type` of items. If `null`, all items are retrieved.
     * @param limit Specifies the maximum number of items to retrieve.
     * @param offset Defines the starting position within the result set, allowing for pagination.
     * @return A `Flow` of a list of `SubjectCollectionEntity` items.
     */
    @Query(
        """
    SELECT * FROM subject_collection 
    WHERE collectionType IS NOT NULL 
    AND (collectionType IN (:collectionTypes))
    ORDER BY lastUpdated DESC
    LIMIT :limit
    OFFSET :offset
    """,
    )
    fun filterMostRecentUpdated(
        collectionTypes: List<UnifiedCollectionType>,
        limit: Int,
        offset: Int = 0,
    ): Flow<List<SubjectCollectionEntity>>

    @Query(
        """
    SELECT * FROM subject_collection
    WHERE collectionType IS NOT NULL
    ORDER BY lastUpdated DESC
    LIMIT :limit
    OFFSET :offset
    """,
    )
    fun mostRecentUpdated(
        limit: Int,
        offset: Int = 0,
    ): Flow<List<SubjectCollectionEntity>>

    /**
     * 同 [filterMostRecentUpdated], 但一次查询就把每个条目的剧集一起取出.
     *
     * 调用方**不要**改回"先查条目列表, 再为每个条目单独订阅一条剧集 flow"的写法: 那会变成 N 条 flow 的
     * `combine`, 其中任意一条不发射整个列表就卡住, 任意一条抛异常整条链就死 (探索页"继续观看"栏因此
     * 永久冻结过, 只能重启应用恢复).
     */
    @Query(
        """
    SELECT * FROM subject_collection
    WHERE collectionType IS NOT NULL
    AND (collectionType IN (:collectionTypes))
    ORDER BY lastUpdated DESC
    LIMIT :limit
    OFFSET :offset
    """,
    )
    @Transaction
    fun filterMostRecentUpdatedWithEpisodes(
        collectionTypes: List<UnifiedCollectionType>,
        limit: Int,
        offset: Int = 0,
    ): Flow<List<SubjectCollectionAndEpisodes>>

    /**
     * @see filterMostRecentUpdatedWithEpisodes
     */
    @Query(
        """
    SELECT * FROM subject_collection
    WHERE collectionType IS NOT NULL
    ORDER BY lastUpdated DESC
    LIMIT :limit
    OFFSET :offset
    """,
    )
    @Transaction
    fun mostRecentUpdatedWithEpisodes(
        limit: Int,
        offset: Int = 0,
    ): Flow<List<SubjectCollectionAndEpisodes>>

    /**
     * Retrieves a paginated list of `SubjectCollectionEntity` items, optionally filtered by type.
     *
     * @param collectionType Optional filter for the `type` of items. If `null`, all items are retrieved. If empty, no item will be returned.
     * @return A `Flow` of a list of `SubjectCollectionEntity` items.
     */
    @Query(
        """
        select * from subject_collection 
        where (collectionType is NOT NULL AND (:collectionType IS NULL OR collectionType = :collectionType))
        AND (:includeNsfw OR NOT nsfw)
        order by lastUpdated DESC, subjectId DESC
        """,
    )
    @Transaction
    fun filterByCollectionTypePaging(
        collectionType: UnifiedCollectionType? = null,
        includeNsfw: Boolean,
    ): PagingSource<Int, SubjectCollectionAndEpisodes>

    @Query("""SELECT * FROM subject_collection WHERE subjectId = :subjectId""")
    fun findById(subjectId: Int): Flow<SubjectCollectionEntity?>

    @Query("""SELECT * FROM subject_collection WHERE subjectId IN (:subjectIds)""")
    fun filterByIds(subjectIds: IntArray): Flow<List<SubjectCollectionEntity>>

    @Query(
        """
        SELECT sc.subjectId FROM subject_collection sc WHERE NOT EXISTS (
            SELECT ec.lastFetched FROM episode_collection ec 
            WHERE (ec.subjectId = sc.subjectId) 
                AND (CAST(unixepoch('now', 'subsecond') * 1000 AS int) - ec.lastFetched > :cacheExpiry)
        )
        """,
    )
    fun subjectIdsWithValidEpisodeCollection(cacheExpiry: Long = 1.hours.inWholeMilliseconds): Flow<List<Int>>

    @Query(
        """
        SELECT lastFetched FROM subject_collection 
        WHERE (:type IS NULL) OR (collectionType = :type)
        ORDER BY lastFetched DESC LIMIT 1
        """,
    )
    suspend fun lastFetched(type: UnifiedCollectionType?): Long

    @Query(
        """
    UPDATE subject_collection 
    SET 
        self_rating_score = COALESCE(:score, self_rating_score), 
        self_rating_comment = COALESCE(:comment, self_rating_comment), 
        self_rating_tags = COALESCE(:tags, self_rating_tags), 
        self_rating_isPrivate = COALESCE(:private, self_rating_isPrivate)
    WHERE subjectId = :subjectId
""",
    )
    suspend fun updateRating(subjectId: Int, score: Int?, comment: String?, tags: List<String>?, private: Boolean?)

    /**
     * 只包含保存在数据库的, 可能不完整
     */
    @Query("""SELECT COUNT(*) FROM subject_collection WHERE (collectionType is NOT NULL AND (:collectionType IS NULL OR collectionType = :collectionType))""")
    fun countCollected(collectionType: UnifiedCollectionType?): Flow<Int>

    @Query("""UPDATE subject_collection SET cachedStaffUpdated = :time, cachedCharactersUpdated = :time WHERE subjectId = :subjectId""")
    suspend fun updateCachedRelationsUpdated(subjectId: Int, time: Long = currentTimeMillis())

    @Query(
        """
        SELECT sc.subjectId FROM subject_collection sc
        WHERE collectionType IS NOT NULL
        AND (collectionType IN (:collectionTypes))
        """,
    )
    fun subjectIdsByCollectionType(collectionTypes: List<UnifiedCollectionType>): Flow<List<Int>>

    @Query(
        """
        SELECT sc.nameCn FROM subject_collection sc
        WHERE collectionType IS NOT NULL
        AND (collectionType IN (:collectionTypes))
        """,
    )
    fun subjectNamesCnByCollectionType(collectionTypes: List<UnifiedCollectionType>): Flow<List<String>>
}

suspend inline fun SubjectCollectionDao.deleteAll(type: UnifiedCollectionType?) {
    if (type == null) {
        deleteAll()
    } else {
        deleteAll(type)
    }
}

data class SubjectCollectionAndEpisodes(
    @Embedded
    val collection: SubjectCollectionEntity,
    @Relation(
        entity = EpisodeCollectionEntity::class,
        parentColumn = "subjectId",
        entityColumn = "subjectId",
    )
    val episodesOfAnyType: List<EpisodeCollectionEntity>,
) {
    override fun toString(): String {
        return "SubjectCollectionAndEpisodes(collection.nameCn=${collection.nameCn}, episodes.size=${episodesOfAnyType.size})"
    }
}

fun SubjectCollectionDao.filterMostRecentUpdated(
    collectionTypes: List<UnifiedCollectionType>?,
    limit: Int,
    offset: Int = 0,
): Flow<List<SubjectCollectionEntity>> = if (collectionTypes == null) {
    mostRecentUpdated(limit, offset)
} else {
    filterMostRecentUpdated(collectionTypes, limit, offset)
}

fun SubjectCollectionDao.filterMostRecentUpdated(
    collectionType: UnifiedCollectionType? = null,
    limit: Int,
): Flow<List<SubjectCollectionEntity>> = filterMostRecentUpdated(listOfNotNull(collectionType), limit)

/**
 * @param collectionTypes `null` 表示不限类型
 * @see SubjectCollectionDao.filterMostRecentUpdatedWithEpisodes
 */
fun SubjectCollectionDao.filterMostRecentUpdatedWithEpisodes(
    collectionTypes: List<UnifiedCollectionType>?,
    limit: Int,
    offset: Int = 0,
): Flow<List<SubjectCollectionAndEpisodes>> = if (collectionTypes == null) {
    mostRecentUpdatedWithEpisodes(limit, offset)
} else {
    filterMostRecentUpdatedWithEpisodes(collectionTypes, limit, offset)
}

/** [SubjectCollectionDao.relationsFreshnessOf] 的投影: relations "盖章"时间戳, upsert 前保留旧值用. */
data class RelationsFreshness(
    val subjectId: Int,
    val cachedStaffUpdated: Long,
    val cachedCharactersUpdated: Long,
)
