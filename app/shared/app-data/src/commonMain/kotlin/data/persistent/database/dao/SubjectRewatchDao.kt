/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import me.him188.ani.datasources.api.topic.UnifiedCollectionType

/**
 * 重温 (见 `SubjectRewatch`) 开始与结束时改库里这一部的收藏类型与各集看过. 只有查询与事务, 不加表.
 */
@Dao
interface SubjectRewatchDao {
    /** 本地还是 [expected] 才改成 [replacement] (连同收藏更新时间). @return 改了几行 */
    @Query(
        """UPDATE subject_collection SET collectionType = :replacement, lastUpdated = :lastUpdated
        WHERE subjectId = :subjectId AND collectionType = :expected""",
    )
    suspend fun replaceType(
        subjectId: Int,
        expected: UnifiedCollectionType,
        replacement: UnifiedCollectionType,
        lastUpdated: Long,
    ): Int

    @Query("""UPDATE episode_collection SET selfCollectionType = :type WHERE subjectId = :subjectId""")
    suspend fun setAllEpisodeTypes(subjectId: Int, type: UnifiedCollectionType)

    @Query("""UPDATE episode_collection SET selfCollectionType = :type WHERE subjectId = :subjectId AND episodeId = :episodeId""")
    suspend fun setEpisodeType(subjectId: Int, episodeId: Int, type: UnifiedCollectionType)

    /** 标成过期: 下次用到时按 Bangumi 现在的重取. */
    @Query("""UPDATE subject_collection SET lastFetched = 0 WHERE subjectId = :subjectId""")
    suspend fun expireSubject(subjectId: Int)

    @Query("""UPDATE episode_collection SET lastFetched = 0 WHERE subjectId = :subjectId""")
    suspend fun expireEpisodes(subjectId: Int)

    /**
     * 开始重温: 本地还是看过才改成在看 (收藏更新时间记成 [now], 排到「继续观看」前面), 各集看过清空.
     * @return 是否改了 (收藏类型在这之前被改成了别的就不动)
     */
    @Transaction
    suspend fun beginRewatch(subjectId: Int, now: Long): Boolean {
        if (replaceType(subjectId, UnifiedCollectionType.DONE, UnifiedCollectionType.DOING, now) == 0) return false
        setAllEpisodeTypes(subjectId, UnifiedCollectionType.NOT_COLLECTED)
        return true
    }

    /**
     * 结束重温: 本地还是在看才还原成看过与开始时的收藏更新时间, 各集看过照 [episodes] 还原 (不在里面的清空).
     * 本地已经不是在看 (bgm 的数据写进来过) 就不动, 那份就是现在的.
     *
     * 还原的是开始时的样子, 重温期间 Bangumi 上可能改过分集: 条目与分集一起标成过期, 开着的页面马上按 Bangumi 现在的重取.
     */
    @Transaction
    suspend fun endRewatch(subjectId: Int, lastUpdated: Long, episodes: Map<Int, UnifiedCollectionType>) {
        if (replaceType(subjectId, UnifiedCollectionType.DOING, UnifiedCollectionType.DONE, lastUpdated) == 0) return
        setAllEpisodeTypes(subjectId, UnifiedCollectionType.NOT_COLLECTED)
        for ((episodeId, type) in episodes) setEpisodeType(subjectId, episodeId, type)
        expireSubject(subjectId)
        expireEpisodes(subjectId)
    }

    /**
     * 结束重温并直接改成 [type] (不先还原成看过: 按收藏类型分标签的页面上, 中间那一下看过会让卡片离开当前标签又回来).
     * 本地还是在看才改 (收藏更新时间记成 [now]); 各集看过照 [episodes] 还原并标成过期, 用到时按 Bangumi 重取.
     * 条目不标过期: 调用方紧接着写 Bangumi, 这时重取到的还是旧的收藏类型.
     *
     * @return 改了没有 (本地已经不是在看就没改)
     */
    @Transaction
    suspend fun endRewatchInto(subjectId: Int, type: UnifiedCollectionType, now: Long, episodes: Map<Int, UnifiedCollectionType>): Boolean {
        if (replaceType(subjectId, UnifiedCollectionType.DOING, type, now) == 0) return false
        setAllEpisodeTypes(subjectId, UnifiedCollectionType.NOT_COLLECTED)
        for ((episodeId, episodeType) in episodes) setEpisodeType(subjectId, episodeId, episodeType)
        expireEpisodes(subjectId)
        return true
    }

    /**
     * Bangumi 上在别处改了收藏类型、重温作废时: 各集看过先还原成开始时的 (别停在这一轮的), 再标成过期, 用到时按 Bangumi 重取.
     * 收藏类型由调用方照取到的写.
     */
    @Transaction
    suspend fun dropRewatch(subjectId: Int, episodes: Map<Int, UnifiedCollectionType>) {
        setAllEpisodeTypes(subjectId, UnifiedCollectionType.NOT_COLLECTED)
        for ((episodeId, type) in episodes) setEpisodeType(subjectId, episodeId, type)
        expireEpisodes(subjectId)
    }
}
