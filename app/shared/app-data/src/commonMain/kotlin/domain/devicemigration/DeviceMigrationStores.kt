/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.devicemigration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import me.him188.ani.app.data.models.danmaku.DanmakuRegexFilter
import me.him188.ani.app.data.models.user.SelfInfo
import me.him188.ani.app.data.persistent.PlatformDataStoreManager
import me.him188.ani.app.data.repository.media.ManualBrowseMemories
import me.him188.ani.app.data.repository.media.MediaSourceSaves
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionsSaveData
import me.him188.ani.app.data.repository.player.EpisodeHistories
import me.him188.ani.app.data.repository.torrent.peer.PeerFilterSubscriptionsSaveData
import me.him188.ani.app.data.repository.user.TokenSave
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.utils.io.SystemPath

/**
 * 换电视读写的那些 DataStore (见 [DeviceMigrationShared] 与 [DeviceMigrationProfile]).
 */
class DeviceMigrationStores(
    val preferences: DataStore<Preferences>,
    val subjectPreferences: DataStore<Preferences>,
    val mediaSources: DataStore<MediaSourceSaves>,
    val subscriptions: DataStore<MediaSourceSubscriptionsSaveData>,
    val peerFilterSubscriptions: DataStore<PeerFilterSubscriptionsSaveData>,
    val danmakuFilters: DataStore<List<DanmakuRegexFilter>>,
    val manualBrowseMemories: DataStore<ManualBrowseMemories>,
    val nsfwSubjects: DataStore<List<Int>>,
    /** 本进程用户的登录凭据 (接管 1 号用户时直接写进开着的这份). */
    val tokens: DataStore<TokenSave>,
    val selfInfo: DataStore<SelfInfo?>,
    val episodeHistories: DataStore<EpisodeHistories>,
    /** [profile] 的那份按人配置 [name] 的文件 (见 [UserProfile.scopedFileName]). */
    val scopedFile: (profile: UserProfile, name: String) -> SystemPath,
) {
    companion object {
        fun of(stores: PlatformDataStoreManager) = DeviceMigrationStores(
            preferences = stores.preferencesStore,
            subjectPreferences = stores.preferredAllianceStore,
            mediaSources = stores.mediaSourceSaveStore,
            subscriptions = stores.mediaSourceSubscriptionStore,
            peerFilterSubscriptions = stores.peerFilterSubscriptionStore,
            danmakuFilters = stores.danmakuFilterStore,
            manualBrowseMemories = stores.manualBrowseMemoryStore,
            nsfwSubjects = stores.subjectNsfwStore,
            tokens = stores.tokenStore,
            selfInfo = stores.selfInfoStore,
            episodeHistories = stores.episodeHistoryStore,
            scopedFile = { profile, name -> stores.resolveDataStoreFile(profile.scopedFileName(name)) },
        )
    }
}
