/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.DeviceAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 换人之前, 给对方的库垫上几部条目的公开信息 (条目与分集), 他进来时首页轮播直接有字, 不用现取
 * (大陆经镜像取一部要十几秒). 见 [UserProfileManager.switchTo].
 *
 * 只补对方库里没有的; 收藏、看过、评分这些个人状态一律不带过去 (一律未收藏). 垫进去的都标成已过期,
 * 对方显示过之后照常按自己的登录状态重取.
 */
class UserProfileSeeder(
    private val currentDatabase: AniDatabase,
    private val deviceDatabase: DeviceAniDatabase,
    /** 打开某个用户的库文件 (1 号用户除外: 它就是 [deviceDatabase], 已经开着). */
    private val openDatabase: (fileName: String) -> AniDatabase,
    /** 要垫哪些条目 (首页轮播那几部). */
    private val subjectIds: suspend () -> List<Int>,
) {
    suspend fun seed(target: UserProfile) = withContext(Dispatchers.IO_) {
        val ids = subjectIds()
        if (ids.isEmpty()) return@withContext
        // 1 号用户的库在本进程里已经作为整机库开着, 同一个文件不能再开第二个实例
        val targetDatabase = if (target.isPrimary) deviceDatabase.database else openDatabase(target.databaseFileName)
        try {
            val targetSubjects = targetDatabase.subjectCollection()
            val targetEpisodes = targetDatabase.episodeCollection()
            var seeded = 0
            for (id in ids) {
                if (targetSubjects.findById(id).first() != null) continue
                val subject = currentDatabase.subjectCollection().findById(id).first() ?: continue
                val episodes = currentDatabase.episodeCollection().filterBySubjectId(id).first()
                // 先条目后分集: 分集有外键指向条目
                targetSubjects.insertIfAbsent(subject.withoutSelfState())
                if (episodes.isNotEmpty()) targetEpisodes.upsert(episodes.map { it.withoutSelfState() })
                seeded++
            }
            logger.info { "Seeded $seeded of ${ids.size} subjects into user profile ${target.id}" }
        } finally {
            if (!target.isPrimary) targetDatabase.close()
        }
    }

    private companion object {
        private val logger = logger<UserProfileSeeder>()
    }
}

/** 去掉个人状态 (收藏类型、评分、更新时间), 并标成已过期. 关联人物/角色另存在别的表, 也标成没取过. */
internal fun SubjectCollectionEntity.withoutSelfState(): SubjectCollectionEntity = copy(
    collectionType = UnifiedCollectionType.NOT_COLLECTED,
    selfRatingInfo = SelfRatingInfo.Empty,
    lastUpdated = 0,
    lastFetched = 0,
    cachedStaffUpdated = 0,
    cachedCharactersUpdated = 0,
)

/** 去掉看过状态, 并标成已过期. */
internal fun EpisodeCollectionEntity.withoutSelfState(): EpisodeCollectionEntity = copy(
    selfCollectionType = UnifiedCollectionType.NOT_COLLECTED,
    lastFetched = 0,
)
