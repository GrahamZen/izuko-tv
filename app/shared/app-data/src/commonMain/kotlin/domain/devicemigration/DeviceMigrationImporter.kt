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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import me.him188.ani.app.data.models.user.SelfInfo
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.repository.player.EpisodeHistories
import me.him188.ani.app.data.repository.user.TokenSave
import me.him188.ani.app.domain.devicemigration.DeviceMigrationExporter.Companion.EPISODE_HISTORIES
import me.him188.ani.app.domain.devicemigration.DeviceMigrationExporter.Companion.SELF_INFO
import me.him188.ani.app.domain.devicemigration.DeviceMigrationExporter.Companion.TOKENS
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileRegistry
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.copyTo
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 换电视的新电视一侧: 把旧电视交来的数据写进这台 (见 [DeviceMigrationShared] 前的说明).
 *
 * - 整机共用的 ([applyShared]): 设置换成旧电视的 (缓存目录这类跟着设备走的除外), 其余并进来, 规则见 [DeviceMigrationMerge];
 * - 用户 ([applyProfile]): 这台是刚装好的 (只有一个没登录、没记录的 1 号用户, 见 [canTakeOverPrimary]) 时, 旧电视的 1 号直接放进
 *   这台的 1 号; 其余 (以及这台已经有人用时的全部) 作为新用户加进来, 这台原有的用户不动. 带着登录凭据, 搬来的 Bangumi 用户不用重新登录.
 *
 * 写完之后要重启进程: 1 号的库是在开着时逐表换的, Room 与各处的内存缓存不知道.
 */
class DeviceMigrationImporter(
    private val registry: UserProfileRegistry,
    private val currentProfileId: () -> Int,
    /** 当前用户的库. */
    private val currentDatabase: AniDatabase,
    /** 打开某个库文件 (按名字, 在库文件的目录里; 版本旧的在打开时升级). */
    private val openDatabase: (fileName: String) -> AniDatabase,
    /** 库文件的位置 (同 [openDatabase] 的名字). */
    private val databaseFile: (fileName: String) -> SystemPath,
    private val stores: DeviceMigrationStores,
) {
    /** 这台的库版本. 旧电视的库比它新时打不开 (见 [DeviceMigrationManifest.databaseVersion]). */
    suspend fun databaseVersion(): Int = DeviceMigrationDatabases.userVersion(currentDatabase)

    /**
     * 旧电视的 1 号用户能不能直接放进这台的 1 号: 这台只有 1 号一个用户、正用着他、他没登录 Bangumi、没有收藏与播放记录.
     */
    suspend fun canTakeOverPrimary(): Boolean = withContext(Dispatchers.IO_) {
        if (registry.state.value.profiles.size != 1 || currentProfileId() != UserProfile.PRIMARY_ID) return@withContext false
        val tokens = stores.tokens.data.first()
        if (tokens.refreshToken != null || tokens.accessTokens != null) return@withContext false
        val collections = currentDatabase.subjectCollection()
        collections.countSelfCollected() == 0 &&
                collections.countEpisodesBySelfType(UnifiedCollectionType.DONE) == 0 &&
                currentDatabase.playbackHistoryDao().getActiveRecords().isEmpty()
    }

    suspend fun applyShared(shared: DeviceMigrationShared) = withContext(Dispatchers.IO_) {
        stores.preferences.updateData { existing ->
            PreferenceEntries.toPreferences(DeviceMigrationMerge.preferences(shared.preferences, PreferenceEntries.of(existing)))
        }
        stores.subjectPreferences.updateData { existing ->
            PreferenceEntries.toPreferences(DeviceMigrationMerge.keyed(shared.subjectPreferences, PreferenceEntries.of(existing)))
        }
        var replacedSubscriptions = emptySet<String>()
        stores.subscriptions.updateData { existing ->
            val merged = DeviceMigrationMerge.subscriptions(shared.subscriptions, existing.list)
            replacedSubscriptions = merged.replacedIds
            existing.copy(list = merged.kept)
        }
        stores.mediaSources.updateData { existing ->
            DeviceMigrationMerge.mediaSources(shared.mediaSources, existing, replacedSubscriptions)
        }
        stores.peerFilterSubscriptions.updateData { existing ->
            existing.copy(list = DeviceMigrationMerge.peerFilterSubscriptions(shared.peerFilterSubscriptions, existing.list))
        }
        stores.danmakuFilters.updateData { DeviceMigrationMerge.danmakuFilters(shared.danmakuFilters, it) }
        stores.manualBrowseMemories.updateData { DeviceMigrationMerge.manualBrowseMemories(shared.manualBrowseMemories, it) }
        stores.nsfwSubjects.updateData { DeviceMigrationMerge.nsfwSubjects(shared.nsfwSubjects, it) }
        logger.info {
            "Device migration: applied ${shared.preferences.size} preferences, ${shared.mediaSources.instances.size} media sources, " +
                    "${shared.subscriptions.size} subscriptions"
        }
    }

    /**
     * 写进一个用户. [database] 是旧电视给的他的库文件快照, 调用后归这里处理 (挪走或删掉).
     *
     * @param takeOverPrimary 放进这台的 1 号用户 (要先确认 [canTakeOverPrimary]), 否则新建一个用户
     * @return 这台电视上的这个用户
     */
    suspend fun applyProfile(incoming: DeviceMigrationProfile, database: SystemPath, takeOverPrimary: Boolean): UserProfile {
        val target = if (takeOverPrimary) takeOverPrimary(incoming, database) else addProfile(incoming, database)
        logger.info { "Device migration: user profile ${incoming.profile.id} -> ${target.id} (${target.kind})" }
        return target
    }

    /**
     * 先用一个用完就关的连接看一眼: 是 SQLite 库, 版本不比这台新 (更新的库 Room 打开时会整个清掉).
     * 坏文件交给 Room 打开会留下没关的连接.
     */
    private suspend fun checkDatabase(database: SystemPath) {
        val version = try {
            DeviceMigrationDatabases.userVersion(database)
        } catch (e: Exception) {
            throw IllegalArgumentException("The transferred database is not readable", e)
        }
        require(version <= databaseVersion()) { "The transferred database is newer ($version) than this one" }
    }

    private suspend fun addProfile(incoming: DeviceMigrationProfile, database: SystemPath): UserProfile {
        checkDatabase(database)
        val id = registry.reserveId()
        val target = incoming.profile.copy(id = id)
        try {
            withContext(Dispatchers.IO_) {
                deleteDatabaseFiles(target.databaseFileName)
                database.moveOrCopyTo(databaseFile(target.databaseFileName))
            }
            // 打开一次: 旧电视的库版本旧时在这里升级, 文件坏了也在这里发现
            openOnce(target.databaseFileName)
            withContext(Dispatchers.IO_) {
                for ((name, text) in incoming.scopedStores) {
                    if (name in UserProfile.SCOPED_DATASTORE_NAMES) stores.scopedFile(target, name).writeText(text)
                }
            }
            registry.addReserved(target)
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO_) {
                deleteDatabaseFiles(target.databaseFileName)
                for (name in UserProfile.SCOPED_DATASTORE_NAMES) deleteQuietly(stores.scopedFile(target, name))
                deleteQuietly(database)
            }
            throw e
        }
        return target
    }

    private suspend fun takeOverPrimary(incoming: DeviceMigrationProfile, database: SystemPath): UserProfile {
        check(currentProfileId() == UserProfile.PRIMARY_ID) { "Only the primary profile can be taken over" }
        checkDatabase(database)
        try {
            withContext(Dispatchers.IO_) {
                deleteDatabaseFiles(STAGING_DATABASE)
                database.moveOrCopyTo(databaseFile(STAGING_DATABASE))
            }
            // 先升到与这台相同的版本, 两边的表与列才对得上
            openOnce(STAGING_DATABASE)
            DeviceMigrationDatabases.copyInto(currentDatabase, databaseFile(STAGING_DATABASE))
        } finally {
            withContext(NonCancellable + Dispatchers.IO_) {
                deleteDatabaseFiles(STAGING_DATABASE)
                deleteQuietly(database)
            }
        }
        stores.tokens.updateData { incoming.decode(TOKENS, TokenSave.serializer()) ?: TokenSave.Initial }
        stores.selfInfo.updateData { incoming.decode(SELF_INFO, SelfInfo.serializer().nullable) }
        stores.episodeHistories.updateData { incoming.decode(EPISODE_HISTORIES, EpisodeHistories.serializer()) ?: EpisodeHistories.Empty }
        val target = incoming.profile.copy(id = UserProfile.PRIMARY_ID)
        registry.update(UserProfile.PRIMARY_ID) { target }
        return target
    }

    private suspend fun openOnce(fileName: String) {
        val database = openDatabase(fileName)
        try {
            DeviceMigrationDatabases.userVersion(database)
        } finally {
            database.close()
        }
    }

    /** 库文件连同 SQLite 旁边的几个文件与 Room 的文件锁. */
    private fun deleteDatabaseFiles(fileName: String) {
        for (suffix in listOf("", "-wal", "-shm", "-journal", ".lck")) deleteQuietly(databaseFile(fileName + suffix))
    }

    /** 收尾时删不掉 (还被谁开着) 只记一笔, 不盖掉原来的异常. */
    private fun deleteQuietly(file: SystemPath) {
        try {
            file.delete()
        } catch (e: Exception) {
            logger.warn { "Device migration: failed to delete $file: ${e::class.simpleName}" }
        }
    }

    private fun SystemPath.moveOrCopyTo(target: SystemPath) {
        try {
            moveTo(target)
        } catch (e: Exception) {
            // 不在同一个文件系统上时挪不了
            copyTo(target)
            delete()
        }
    }

    private fun <T> DeviceMigrationProfile.decode(name: String, serializer: KSerializer<T>): T? {
        val text = scopedStores[name] ?: return null
        return try {
            DataStoreJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            // 内容里可能有登录凭据, 不进日志
            logger.warn { "Device migration: failed to read $name of user profile ${profile.id}: ${e::class.simpleName}" }
            null
        }
    }

    private companion object {
        private val logger = logger<DeviceMigrationImporter>()

        /** 接管 1 号用户时, 搬来的库先放在这里升级版本, 拷完就删. */
        const val STAGING_DATABASE = "device_migration_incoming.db"
    }
}
