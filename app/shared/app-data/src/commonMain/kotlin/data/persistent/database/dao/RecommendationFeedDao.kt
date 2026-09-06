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
        const val CURRENT_ALGO_VERSION = 1
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

    /**
     * 上一批各行种子推荐用的作品名, 按行的先后; 给种子稳定与「换一批」轮换用.
     *
     * **只按 titleArg 非空取**, 不按 groupKey 过滤: 只有种子组才写 titleArg, 而它的 groupKey
     * 在同 kind 多行时带 `#序号` 后缀, 按等值匹配会漏.
     */
    @Query(
        """
        select titleArg from recommendation_feed
        where titleArg is not null group by titleArg order by min(orderIndex)
        """,
    )
    suspend fun seedTitleArgs(): List<String>

    /** 当前这一批的全部条目; 「换一批」时拿来躲开. */
    @Query("""select subjectId from recommendation_feed""")
    suspend fun subjectIds(): List<Int>

    @Query("""delete from recommendation_feed""")
    suspend fun clear()

    @Upsert
    suspend fun upsert(items: List<RecommendationFeedEntity>)

    /**
     * 原地换掉一格 (位置不变). **只认同一批**: [computedAt] 对不上 (这期间整表已经被新一批替换) 就什么都不改.
     *
     * @return 改了几行 (0 或 1)
     */
    @Query(
        """
        update recommendation_feed set subjectId = :subjectId, nameCn = :nameCn, imageLarge = :imageLarge
        where groupKey = :groupKey and orderIndex = :orderIndex and computedAt = :computedAt
        """,
    )
    suspend fun replaceItem(
        groupKey: String,
        orderIndex: Int,
        computedAt: Long,
        subjectId: Int,
        nameCn: String,
        imageLarge: String,
    ): Int

    /** 一次换掉若干格, 一个事务 = 页面只刷新一次. 规则见 [replaceItem]. */
    @Transaction
    suspend fun replaceItems(items: List<RecommendationFeedEntity>) {
        for (item in items) {
            replaceItem(item.groupKey, item.orderIndex, item.computedAt, item.subjectId, item.nameCn, item.imageLarge)
        }
    }

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
