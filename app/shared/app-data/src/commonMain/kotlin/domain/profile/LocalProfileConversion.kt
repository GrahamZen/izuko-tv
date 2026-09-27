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
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.ProtoConverters
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 当前用户库里自己的收藏记录: 收藏类型、评分与短评、每集的看过状态. 条目与分集的公开信息、播放进度不算在内.
 *
 * 本地用户在设置里「清除收藏记录」, 以及 1 号用户改成本地用户时决定留不留之前登录留下的记录 (见 [LocalProfileConversion]), 都用它.
 */
class SelfCollectionRecords(private val database: AniDatabase) {
    data class Counts(
        /** 收藏了的条目数. */
        val collections: Int,
        /** 标了看过的集数. */
        val watchedEpisodes: Int,
    ) {
        val isEmpty: Boolean get() = collections == 0 && watchedEpisodes == 0
    }

    suspend fun counts(): Counts = withContext(Dispatchers.IO_) {
        val dao = database.subjectCollection()
        Counts(
            collections = dao.countSelfCollected(),
            watchedEpisodes = dao.countEpisodesBySelfType(UnifiedCollectionType.DONE),
        )
    }

    /** 全部清掉 (清了找不回来). */
    suspend fun clear() = withContext(Dispatchers.IO_) {
        database.subjectCollection().clearAllSelfStates(ProtoConverters.StringList().fromList(emptyList()))
        logger.info { "Cleared self collection records of user profile ${UserProfiles.currentId}" }
    }

    private companion object {
        private val logger = logger<SelfCollectionRecords>()
    }
}

/**
 * 把还没登录的 1 号用户改成本地用户 (见 [UserProfileKind.LOCAL]).
 *
 * 给一个人用、又登录不了 Bangumi 的电视: 不用另建一个本地用户 —— 那样就有两个用户, 每次打开都要先选人, 而 1 号删不掉.
 * 只给 1 号: 别的用户没登录的, 删掉再建一个本地用户就行. 改完这个用户不能再登录 Bangumi (同所有本地用户);
 * 以后想用 Bangumi, 新建一个 Bangumi 用户, 再用 [LocalProfileImporter] 把收藏导过去.
 *
 * 库里可能还留着之前登录时的收藏记录 (退出登录不清本地缓存), 由用户决定留下当作本地收藏还是清掉 ([SelfCollectionRecords]).
 * 改完重启应用: 各仓库的本地模式在进程启动时按当前用户定下.
 */
class LocalProfileConversion(
    private val manager: UserProfileManager,
    private val records: SelfCollectionRecords,
    private val currentProfile: () -> UserProfile = { UserProfiles.current },
    /** 当前用户现在登录着 Bangumi. */
    private val isLoggedIn: suspend () -> Boolean,
    /** 清掉当前用户残留的登录信息 (过期的令牌、个人资料). */
    private val clearSession: suspend () -> Unit,
) {
    /** 当前用户可以改成本地用户 (登录着的要先退出, 另看 [isLoggedIn]): 这台设备能重启应用, 是 1 号, 还是 Bangumi 用户. */
    val isOffered: Boolean
        get() = manager.isSupported && currentProfile().let { it.isPrimary && !it.isLocal }

    /** 之前登录留下的记录, 改之前给人看. */
    suspend fun leftoverRecords(): SelfCollectionRecords.Counts = records.counts()

    /**
     * 改成本地用户, 然后重启应用.
     * @param clearRecords 先清掉库里之前登录留下的收藏记录; `false` = 留下当作本地收藏
     * @param defaultName 没起过名字时存下的默认名, 见 [UserProfileManager.convertCurrentToLocal]
     */
    suspend fun convert(clearRecords: Boolean, defaultName: String) {
        check(isOffered) { "User profile ${currentProfile().id} cannot be converted to local" }
        check(!isLoggedIn()) { "Log out of Bangumi before converting to a local profile" }
        clearSession()
        if (clearRecords) records.clear()
        logger.info { "Converting user profile ${currentProfile().id} to local (clearRecords=$clearRecords)" }
        manager.convertCurrentToLocal(defaultName)
    }

    private companion object {
        private val logger = logger<LocalProfileConversion>()
    }
}
