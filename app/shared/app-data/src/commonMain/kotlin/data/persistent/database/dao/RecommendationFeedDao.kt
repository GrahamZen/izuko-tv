/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 探索页推荐的**结果缓存**.
 *
 * 存的是算好的条目快照 (名字 + 封面), 不是只存 id —— 进页要能**立刻画出来**, 再拿 id 去查条目
 * 就又回到"进页发请求"了, 而那正是这张表要解决的问题 (改之前推荐是翻页时现算的, 一次会话
 * 实测发了 946 个 `/p1/subjects/{id}/recs`).
 *
 * 整张表就是一次推荐的完整结果: [orderIndex] 是**全表次序** (不是组内的), 按它读出来就是页面
 * 从上到下的顺序; [groupKey] 说明这一条属于哪一组 (见 `RecommendationGroupKind`).
 */
@Entity(tableName = "recommendation_feed", primaryKeys = ["groupKey", "orderIndex"])
data class RecommendationFeedEntity(
    val groupKey: String,
    /** **全表**次序. 排序结果固化在这里, 读的时候不再排. */
    val orderIndex: Int,
    val subjectId: Int,
    val nameCn: String,
    val imageLarge: String,
    /** 什么时候算出来的; 判过期用. 全表相同. */
    val computedAt: Long,
    /** 组标题的填充参数, 目前只有"因为你喜欢《X》"用得上. 同组各行相同. */
    val titleArg: String? = null,
    /**
     * 算出这批结果的**算法版本**: 改了召回或排序就把 [CURRENT_ALGO_VERSION] +1, 旧结果当场
     * 作废并重算.
     *
     * 不这么做的话, 用户升级完还要对着上一版算法的结果看满一个 TTL (12 小时) —— 表是升级保留
     * 的, 而"新鲜"只看时间戳, 它不知道算法换了.
     */
    @ColumnInfo(defaultValue = "0")
    val algoVersion: Int = 0,
    /**
     * 算这批结果时的画像身份串 (见 `InterestProfile.key`). 它变了就作废 —— **登录**是最典型的
     * 情形: 收藏从 0 变成上百, 而 [computedAt] 与 [algoVersion] 都没变, 光靠 TTL 的话用户要
     * 对着登出时算的默认组等满 12 小时.
     */
    @ColumnInfo(defaultValue = "")
    val profileKey: String = "",
) {
    companion object {
        const val CURRENT_ALGO_VERSION = 8
    }
}

@Dao
interface RecommendationFeedDao {
    @Query("""select * from recommendation_feed order by orderIndex""")
    fun pagingSource(): PagingSource<Int, RecommendationFeedEntity>

    @Query("""select * from recommendation_feed order by orderIndex""")
    fun allFlow(): Flow<List<RecommendationFeedEntity>>

    @Query("""select max(computedAt) from recommendation_feed""")
    suspend fun computedAt(): Long?

    @Query("""select min(algoVersion) from recommendation_feed""")
    suspend fun algoVersion(): Int?

    @Query("""select profileKey from recommendation_feed limit 1""")
    suspend fun profileKey(): String?

    /** 上一批里"因为你喜欢/看过X的人还看了"用的那部作品名; 给种子稳定用. */
    @Query(
        """
        select titleArg from recommendation_feed
        where groupKey in (:groupKeys) and titleArg is not null limit 1
        """,
    )
    suspend fun seedTitleArg(groupKeys: List<String>): String?

    @Query("""delete from recommendation_feed""")
    suspend fun clear()

    @Upsert
    suspend fun upsert(items: List<RecommendationFeedEntity>)

    /**
     * 整表替换. 必须在一个事务里: 分两步做的话, 中间那一瞬 [pagingSource] 会推一个空列表出去,
     * 页面就闪一下空白.
     */
    @Transaction
    suspend fun replaceAll(items: List<RecommendationFeedEntity>) {
        clear()
        upsert(items)
    }
}
