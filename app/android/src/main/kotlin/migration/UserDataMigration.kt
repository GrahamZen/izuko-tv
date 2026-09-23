/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.migration

import android.content.Context
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.dao.SearchHistoryEntity
import me.him188.ani.app.data.persistent.dataStores
import me.him188.ani.app.data.persistent.migration.LegacyUserData
import me.him188.ani.app.data.persistent.migration.PreferenceEntries
import me.him188.ani.app.ui.foundation.lan.TvRemoteSettingsBridge
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin

/**
 * 换分发包名时设置备份与缓存之外的用户数据, 见 [LegacyUserData].
 *
 * 旧包 (跳板包) 由 [SettingsMigrationProvider] 调 [export] 交出去, 新包在接管设置之后调 [import] 放进自己的库.
 */
internal object UserDataMigration {
    private val logger = logger<UserDataMigration>()

    /** 搜索记录只带最近这么多条, 再早的没什么用. */
    private const val SEARCH_HISTORY_LIMIT = 500

    private fun remotePrefs(context: Context) =
        context.getSharedPreferences(TvRemoteSettingsBridge.TOKEN_PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun export(context: Context, koin: Koin): LegacyUserData {
        val stores = context.dataStores
        val db = koin.get<AniDatabase>()
        return LegacyUserData(
            preferences = PreferenceEntries.of(stores.preferencesStore.data.first()),
            episodePreferences = PreferenceEntries.of(stores.preferredAllianceStore.data.first()),
            mediaSources = stores.mediaSourceSaveStore.data.first(),
            mediaSourceSubscriptions = stores.mediaSourceSubscriptionStore.data.first(),
            peerFilterSubscriptions = stores.peerFilterSubscriptionStore.data.first(),
            episodeHistories = stores.episodeHistoryStore.data.first(),
            playbackHistory = db.playbackHistoryDao().allRecordsFlow().first(),
            playbackHistoryPendingOps = db.playbackHistoryDao().getPendingOps(),
            searchHistory = db.searchHistory().recent(SEARCH_HISTORY_LIMIT).asReversed(),
            remoteControlToken = remotePrefs(context).getString(TvRemoteSettingsBridge.TOKEN_KEY, null),
        )
    }

    /**
     * 整份替换本包的对应数据. 新包这时刚装好, 这些库里只有默认值或第一次启动时顺手写下的东西.
     *
     * 设置被整份替换, 缓存目录里的旧包名要由调用方随后改写 (见 SettingsMigration).
     */
    suspend fun import(context: Context, koin: Koin, data: LegacyUserData) {
        val stores = context.dataStores
        val db = koin.get<AniDatabase>()
        stores.preferencesStore.updateData { PreferenceEntries.toPreferences(data.preferences) }
        stores.preferredAllianceStore.updateData { PreferenceEntries.toPreferences(data.episodePreferences) }
        stores.mediaSourceSaveStore.updateData { data.mediaSources }
        stores.mediaSourceSubscriptionStore.updateData { data.mediaSourceSubscriptions }
        stores.peerFilterSubscriptionStore.updateData { data.peerFilterSubscriptions }
        stores.episodeHistoryStore.updateData { data.episodeHistories }

        val playback = db.playbackHistoryDao()
        playback.upsertRecords(data.playbackHistory)
        // 自增 id 由本库重新分配, 顺序照旧
        playback.insertPendingOps(data.playbackHistoryPendingOps.map { it.copy(id = 0) })
        val search = db.searchHistory()
        data.searchHistory.forEach { search.insert(SearchHistoryEntity(content = it)) }
        data.remoteControlToken?.let { token ->
            remotePrefs(context).edit().putString(TvRemoteSettingsBridge.TOKEN_KEY, token).commit()
            // 首次启动时服务已经按新生成的 token 起来了, 要换成旧包的
            TvRemoteSettingsBridge.reloadToken?.invoke()
        }

        logger.info {
            "migration: 接管数据源 ${data.mediaSources.instances.size} 个、订阅 ${data.mediaSourceSubscriptions.list.size} 个、" +
                    "播放记录 ${data.playbackHistory.size} 条 (待同步 ${data.playbackHistoryPendingOps.size})、" +
                    "设置 ${data.preferences.size} 项、每部番的偏好 ${data.episodePreferences.size} 项"
        }
    }
}
