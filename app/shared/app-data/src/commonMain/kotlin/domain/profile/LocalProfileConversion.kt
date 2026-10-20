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
 * 把当前的 Bangumi 用户改成本地用户 (见 [UserProfileKind.LOCAL]), 登录着的先退出登录. 任何 Bangumi 用户都可以改;
 * 以后想用 Bangumi, 再改回 Bangumi 用户 (见 [BangumiProfileConversion]).
 *
 * 给一个人用、又登录不了 Bangumi 的电视, 1 号直接改成本地用户就行, 不用另建一个 —— 那样就有两个用户, 每次打开都要先选人, 而 1 号删不掉.
 *
 * 库里可能还留着登录时的收藏记录 (退出登录不清本地缓存), 由用户决定留下当作本地收藏还是清掉 ([SelfCollectionRecords]).
 * 库里只缓存用到过的收藏, 所以登录着、选了留下时先把账号里的收藏整个取下来 ([AccountCollectionsDownload]), 取不全就不改;
 * 没登录时拿不到账号里的, 只能留下库里有的.
 * 本地用户改成 Bangumi 用户后没登录就改回来的, 留下的本地收藏还在文件里 ([PendingLocalCollections]), 一并算在里面:
 * 留下 = 下次启动放回库里 (见 [BangumiProfileConversion.restoreIntoLocal]), 清除 = 文件一起删.
 * 改完重启应用: 各仓库的本地模式在进程启动时按当前用户定下.
 */
class LocalProfileConversion(
    private val manager: UserProfileManager,
    private val records: SelfCollectionRecords,
    private val pending: PendingLocalCollections,
    private val currentProfile: () -> UserProfile = { UserProfiles.current },
    /** 当前用户现在登录着 Bangumi (能取账号里的收藏). */
    private val isLoggedIn: suspend () -> Boolean = { false },
    /** 把账号里的收藏整个取下来写进库, 见 [AccountCollectionsDownload.download]; 没取全时抛出. */
    private val downloadAccountCollections: suspend (onProgress: (AccountCollectionsDownload.Progress) -> Unit) -> Unit = {},
    /** 退出当前用户的 Bangumi 登录 (令牌、个人资料; 没登录时清掉残留的). */
    private val clearSession: suspend () -> Unit,
) {
    /** 当前用户可以改成本地用户: 这台设备能重启应用, 当前是 Bangumi 用户 (登录着也行, 改的时候先退出). */
    val isOffered: Boolean
        get() = manager.isSupported && !currentProfile().isLocal

    /** 登录时留下的记录 (连同文件里等着处理的本地收藏), 改之前给人看. */
    suspend fun leftoverRecords(): SelfCollectionRecords.Counts {
        val inDatabase = records.counts()
        val inFile = pending.load()
        return SelfCollectionRecords.Counts(
            collections = inDatabase.collections + inFile.size,
            watchedEpisodes = inDatabase.watchedEpisodes + inFile.sumOf { it.watchedEpisodes.size },
        )
    }

    /**
     * 退出登录, 改成本地用户, 然后重启应用. 登录着、留下记录时先取账号里的收藏 ([onProgress] 报进度), 取不全时抛出, 什么都不改.
     * @param clearRecords 先清掉登录时留下的收藏记录 (连同文件里等着处理的本地收藏); `false` = 留下当作本地收藏
     * @param defaultName 没起过名字时存下的默认名, 见 [UserProfileManager.convertCurrentToLocal]
     */
    suspend fun convert(
        clearRecords: Boolean,
        defaultName: String,
        onProgress: (AccountCollectionsDownload.Progress) -> Unit = {},
    ) {
        check(isOffered) { "User profile ${currentProfile().id} cannot be converted to local" }
        if (!clearRecords && isLoggedIn()) downloadAccountCollections(onProgress)
        clearSession()
        if (clearRecords) {
            records.clear()
            pending.discard()
        }
        logger.info { "Converting user profile ${currentProfile().id} to local (clearRecords=$clearRecords)" }
        manager.convertCurrentToLocal(defaultName)
    }

    private companion object {
        private val logger = logger<LocalProfileConversion>()
    }
}
