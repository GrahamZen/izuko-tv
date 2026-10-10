/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.devicemigration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.DeviceAniDatabase
import me.him188.ani.app.data.repository.user.TokenSave
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileRegistry
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.readText

/**
 * 换电视的旧电视一侧: 交出清单、整机数据、每个用户的配置与库文件快照 (见 [DeviceMigrationShared] 前的说明).
 *
 * 任何一个用户都能交: 当前用户用开着的库, 1 号用户用 [deviceDatabase], 其余临时打开他的库文件、用完关掉
 * (同一个文件不能再开第二个 Room 实例, 同 `ProfileArchiver`).
 */
class DeviceMigrationExporter(
    private val registry: UserProfileRegistry,
    private val currentProfileId: () -> Int,
    /** 当前用户的库. */
    private val currentDatabase: AniDatabase,
    private val deviceDatabase: DeviceAniDatabase,
    /** 打开某个用户的库文件 (当前用户与 1 号用户除外: 它们已经开着). */
    private val openDatabase: (fileName: String) -> AniDatabase,
    private val stores: DeviceMigrationStores,
    private val appVersion: String,
) {
    suspend fun manifest(): DeviceMigrationManifest = withContext(Dispatchers.IO_) {
        DeviceMigrationManifest(
            appVersion = appVersion,
            databaseVersion = DeviceMigrationDatabases.userVersion(currentDatabase),
            profiles = registry.state.value.profiles.map { profile ->
                withDatabase(profile) { database ->
                    DeviceMigrationProfileSummary(
                        id = profile.id,
                        name = profile.name,
                        kind = profile.kind,
                        loggedIn = !profile.isLocal && readTokens(profile)?.let { it.refreshToken != null || it.accessTokens != null } == true,
                        collections = database.subjectCollection().countSelfCollected(),
                        playbackRecords = database.playbackHistoryDao().getActiveRecords().size,
                    )
                }
            },
            mediaSources = stores.mediaSources.data.first().instances.size,
            subscriptions = stores.subscriptions.data.first().list.size,
        )
    }

    suspend fun shared(): DeviceMigrationShared = withContext(Dispatchers.IO_) {
        DeviceMigrationShared(
            preferences = PreferenceEntries.of(stores.preferences.data.first()),
            subjectPreferences = PreferenceEntries.of(stores.subjectPreferences.data.first()),
            mediaSources = stores.mediaSources.data.first(),
            subscriptions = stores.subscriptions.data.first().list,
            peerFilterSubscriptions = stores.peerFilterSubscriptions.data.first().list,
            danmakuFilters = stores.danmakuFilters.data.first(),
            manualBrowseMemories = stores.manualBrowseMemories.data.first(),
            nsfwSubjects = stores.nsfwSubjects.data.first(),
        )
    }

    /** @return 没有这个用户时为 `null` */
    suspend fun profile(id: Int): DeviceMigrationProfile? = withContext(Dispatchers.IO_) {
        val profile = registry.find(id) ?: return@withContext null
        DeviceMigrationProfile(
            profile = profile,
            scopedStores = UserProfile.SCOPED_DATASTORE_NAMES.mapNotNull { name ->
                readScoped(profile, name)?.let { name to it }
            }.toMap(),
        )
    }

    /**
     * 把用户 [id] 的库拍一份快照写到 [target] (见 [DeviceMigrationDatabases.snapshot]).
     * @return 没有这个用户时为 `false`
     */
    suspend fun snapshotDatabase(id: Int, target: SystemPath): Boolean {
        val profile = registry.find(id) ?: return false
        withDatabase(profile) { DeviceMigrationDatabases.snapshot(it, target) }
        return true
    }

    private suspend fun <R> withDatabase(profile: UserProfile, block: suspend (AniDatabase) -> R): R {
        val isCurrent = profile.id == currentProfileId()
        val database = when {
            isCurrent -> currentDatabase
            profile.isPrimary -> deviceDatabase.database
            else -> openDatabase(profile.databaseFileName)
        }
        try {
            return block(database)
        } finally {
            if (!isCurrent && !profile.isPrimary) database.close()
        }
    }

    private fun readScoped(profile: UserProfile, name: String): String? {
        val file = stores.scopedFile(profile, name)
        return if (file.exists()) file.readText() else null
    }

    private fun readTokens(profile: UserProfile): TokenSave? = readScoped(profile, TOKENS)?.let { text ->
        runCatching { DataStoreJson.decodeFromString(TokenSave.serializer(), text) }.getOrNull()
    }

    internal companion object {
        /** 登录凭据那份按人配置的名字 (见 [UserProfile.SCOPED_DATASTORE_NAMES]). */
        const val TOKENS = "authSession"
        const val SELF_INFO = "selfInfo"
        const val EPISODE_HISTORIES = "episodeHistories"
    }
}
