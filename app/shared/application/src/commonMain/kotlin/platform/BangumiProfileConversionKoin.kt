/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.network.EpisodeService
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.profile.AccountCollectionsDownload
import me.him188.ani.app.domain.profile.BangumiProfileConversion
import me.him188.ani.app.domain.profile.PendingLocalCollections
import me.him188.ani.app.domain.profile.ProfileArchiver
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.core.Koin
import org.koin.core.module.Module

/**
 * 改用户类型用到的: 本地用户改成 Bangumi 用户 ([BangumiProfileConversion]) 与改的时候留下的本地收藏 ([PendingLocalCollections]);
 * Bangumi 用户改成本地用户前把账号里的收藏取下来 ([AccountCollectionsDownload]).
 */
internal fun Module.bangumiProfileConversion(getContext: () -> Context) {
    single<AccountCollectionsDownload> {
        val database = get<AniDatabase>()
        AccountCollectionsDownload(
            refreshCollectionList = { get<SubjectCollectionRepository>().refreshCollectionList(null) },
            collectedSubjectIds = { types -> database.subjectCollection().subjectIdsByCollectionType(types).first() },
            fetchEpisodes = { subjectId -> get<EpisodeService>().getEpisodeCollectionEntities(subjectId, currentTimeMillis()) },
            saveEpisodes = { database.episodeCollection().upsert(it) },
        )
    }
    single<PendingLocalCollections> { PendingLocalCollections(pendingLocalCollectionsFile(getContext(), UserProfiles.current)) }
    single<BangumiProfileConversion> {
        BangumiProfileConversion(
            manager = get(),
            records = get(),
            pending = get(),
            importer = get(),
            exportCollections = { get<ProfileArchiver>().export(UserProfiles.current).collections },
            restoreCollection = { get<ProfileArchiver>().restoreCollection(it) },
        )
    }
}

/** 这个用户留下的本地收藏文件 (删用户时一起删). */
internal fun pendingLocalCollectionsFile(context: Context, profile: UserProfile): SystemPath =
    context.files.dataDir.resolve(profile.scopedFileName(PendingLocalCollections.FILE_NAME))

/** 启动时: 本地用户还有改成 Bangumi 用户时留下的收藏 (没登录又改回来了), 在后台放回库里. */
internal fun CoroutineScope.launchPendingLocalCollectionsRestore(koin: Koin) {
    if (!UserProfiles.current.isLocal) return
    launch { koin.get<BangumiProfileConversion>().restoreIntoLocal() }
}
