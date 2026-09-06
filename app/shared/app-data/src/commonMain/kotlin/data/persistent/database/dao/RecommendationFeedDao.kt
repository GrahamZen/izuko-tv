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
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * 探索页推荐的**结果缓存**.
 *
 * 存的是算好的条目快照 (名字 + 封面), 不是只存 id —— 进页要能**立刻画出来**, 再拿 id 去查条目
 * 就又回到"进页发请求"了, 而那正是这张表要解决的问题 (改之前推荐是翻页时现算的, 一次会话
 * 实测发了 946 个 `/p1/subjects/{id}/recs`).
 *
 * [groupKey] 为将来分组留的: 第一版只有一组 [RecommendationFeedEntity.GROUP_DEFAULT].
 */
@Entity(tableName = "recommendation_feed", primaryKeys = ["groupKey", "orderIndex"])
data class RecommendationFeedEntity(
    val groupKey: String,
    /** 组内次序. 排序结果就固化在这里, 读的时候不再排. */
    val orderIndex: Int,
    val subjectId: Int,
    val nameCn: String,
    val imageLarge: String,
    /** 这一组是什么时候算出来的; 判过期用. 同组各行相同. */
    val computedAt: Long,
) {
    companion object {
        /**
         * 组名带**算法版本**: 改了召回或排序就把版本号 +1, 旧结果当场作废并重算.
         *
         * 不这么做的话, 用户升级完还要对着上一版算法的结果看满一个 TTL (12 小时) —— 表是升级
         * 保留的, 而"新鲜"只看时间戳, 不知道算法换了.
         */
        const val GROUP_DEFAULT = "for_you_v2"
    }
}

@Dao
interface RecommendationFeedDao {
    @Query("""select * from recommendation_feed where groupKey = :groupKey order by orderIndex""")
    fun pagingSource(groupKey: String): PagingSource<Int, RecommendationFeedEntity>

    @Query("""select max(computedAt) from recommendation_feed where groupKey = :groupKey""")
    suspend fun computedAtOf(groupKey: String): Long?

    @Query("""delete from recommendation_feed where groupKey = :groupKey""")
    suspend fun clear(groupKey: String)

    /** 清掉已经不用的组 (换了算法版本之后的旧组). */
    @Query("""delete from recommendation_feed where groupKey not in (:keep)""")
    suspend fun deleteGroupsExcept(keep: List<String>)

    @Upsert
    suspend fun upsert(items: List<RecommendationFeedEntity>)

    /**
     * 整组替换. 必须在一个事务里: 分两步做的话, 中间那一瞬 [pagingSource] 会推一个空列表出去,
     * 页面就闪一下空白.
     */
    @Transaction
    suspend fun replace(groupKey: String, items: List<RecommendationFeedEntity>) {
        clear(groupKey)
        upsert(items)
    }
}
