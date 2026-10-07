/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.HttpClient
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAddedShares
import me.him188.ani.app.data.models.preference.DriveAddedShares
import me.him188.ani.app.data.network.TmdbSubjectMapRepository
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepository
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.datasources.api.source.serializeArguments
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.CoroutineContext

/**
 * 以前版本按设置键存的数据 (见 [DriveLegacyData]): 读出原文、读完删掉.
 */
interface LegacyPreferences {
    suspend fun read(key: String): String?
    suspend fun remove(key: String)
}

/**
 * 已配置的网盘: 「网盘」数据源 ([CloudDriveMediaSource]) 的参数里带着协议, 这里从保存的数据源实例里读出全部协议,
 * 每个网盘 (按 [CloudDriveProtocol.id]) 一个 [CloudDriveService], 三种数据源、播放解析、设置页、Web 控制台共用.
 *
 * 数据源停用了网盘照样在: 分享搜索与添加的分享播放时要转存到这个网盘, 设置页也要能登录.
 *
 * 协议声明了旧数据 ([CloudDriveProtocol.legacy]) 时, 第一次看到它就认领: 账号、添加的分享搬进新的设置, 旧的数据源换成新的类型.
 */
class CloudDriveRegistry(
    private val instances: MediaSourceInstanceRepository,
    private val accounts: Settings<CloudDriveAccounts>,
    private val addedShares: Settings<CloudDriveAddedShares>,
    private val legacyPreferences: LegacyPreferences,
    tmdbSubjectMap: TmdbSubjectMapRepository?,
    parentCoroutineContext: CoroutineContext,
    private val httpClient: () -> HttpClient = CloudDriveApi::createHttpClient,
) {
    private val numbering = tmdbSubjectMap?.let { TmdbEpisodeNumbering.of(it) } ?: TmdbEpisodeNumbering.None

    private val lock = SynchronizedObject()
    private val services = LinkedHashMap<String, CloudDriveService>()
    private val addedShareServices = HashMap<String, CloudDriveAddedShareService>()
    private val claimed = HashSet<String>()

    private val _drives = MutableStateFlow<List<CloudDriveService>?>(null)

    /** 已配置的网盘 (按数据源的顺序). 还没读出保存的数据源时为 null. */
    val drives: StateFlow<List<CloudDriveService>?> = _drives.asStateFlow()

    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[kotlinx.coroutines.Job]))

    init {
        scope.launch {
            instances.flow.collect { saves ->
                // 一个数据源的参数坏了只跳过它: 抛出去的话这个协程就停了, 所有网盘都一直等不到
                val protocols = saves.filter { it.factoryId == CloudDriveMediaSource.FactoryId }
                    .mapNotNull { save ->
                        runCatching { save.config.deserializeArgumentsOrNull(CloudDriveArguments.serializer())?.protocol }
                            .onFailure { logger.error(it) { "Invalid cloud drive source ${save.instanceId}" } }
                            .getOrNull()
                    }
                    .distinctBy { it.id }
                _drives.value = protocols.map { serviceFor(it) }
                for (protocol in protocols) {
                    val legacy = protocol.legacy ?: continue
                    try {
                        claimLegacy(protocol, legacy, saves)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.error(e) { "Failed to claim legacy data for cloud drive ${protocol.id}" }
                    }
                }
            }
        }
    }

    /** 网盘 [protocol] 的服务, 没有就新建; 已有时换上这份协议 (订阅更新). */
    fun serviceFor(protocol: CloudDriveProtocol): CloudDriveService = synchronized(lock) {
        services[protocol.id]?.also { it.updateProtocol(protocol) }
            ?: CloudDriveService(protocol, accounts, httpClient(), numbering).also { services[protocol.id] = it }
    }

    /** 已配置的网盘 [driveId]; 没有时为 null. */
    fun service(driveId: String): CloudDriveService? = drives.value?.firstOrNull { it.driveId == driveId }

    /** 同 [service], 还没读出保存的数据源时先等. */
    suspend fun awaitService(driveId: String): CloudDriveService? =
        drives.filterNotNull().first().firstOrNull { it.driveId == driveId }

    /** 网盘 [driveId] 的「我添加的分享」; 网盘没有配置时为 null. */
    fun addedShareService(driveId: String): CloudDriveAddedShareService? {
        val drive = service(driveId) ?: return null
        return synchronized(lock) {
            addedShareServices[driveId]?.takeIf { it.drive === drive }
                ?: CloudDriveAddedShareService(addedShares, drive).also { addedShareServices[driveId] = it }
        }
    }

    /** 同 [addedShareService], 还没读出保存的数据源时先等. */
    suspend fun awaitAddedShareService(driveId: String): CloudDriveAddedShareService? {
        awaitService(driveId) ?: return null
        return addedShareService(driveId)
    }

    /** 播放地址 [uri] 是哪个网盘的资源 (自己网盘的文件或分享里的文件); 都不是时为 null. */
    fun driveOf(uri: String): CloudDriveService? = drives.value?.firstOrNull {
        it.placeholders.parseShareFile(uri) != null || it.placeholders.fileIdOf(uri) != null
    }

    private suspend fun claimLegacy(protocol: CloudDriveProtocol, legacy: DriveLegacyData, saves: List<MediaSourceSave>) {
        val driveId = protocol.id
        val firstTime = synchronized(lock) { claimed.add(driveId) }
        if (firstTime) {
            claimAccount(driveId, legacy)
            claimAddedShares(driveId, legacy)
        }
        claimSources(driveId, legacy, saves)
    }

    private suspend fun claimAccount(driveId: String, legacy: DriveLegacyData) {
        val key = legacy.accountKey.ifBlank { return }
        val raw = legacyPreferences.read(key) ?: return
        val account = runCatching { DataStoreJson.decodeFromString(CloudDriveAccount.serializer(), raw) }.getOrNull()
        if (account != null && account != CloudDriveAccount.Default) {
            var claimedAccount = false
            accounts.update {
                if (of(driveId) != CloudDriveAccount.Default) {
                    this
                } else {
                    claimedAccount = true
                    with(driveId, account)
                }
            }
            if (claimedAccount) logger.info { "Claimed legacy account of cloud drive $driveId: $account" }
        }
        legacyPreferences.remove(key)
    }

    private suspend fun claimAddedShares(driveId: String, legacy: DriveLegacyData) {
        val key = legacy.addedSharesKey.ifBlank { return }
        val raw = legacyPreferences.read(key) ?: return
        val shares = runCatching { DataStoreJson.decodeFromString(DriveAddedShares.serializer(), raw) }.getOrNull()
        if (shares != null && shares.subjects.isNotEmpty()) {
            addedShares.update {
                val current = of(driveId)
                // 已经有的条目以新的为准
                with(driveId, current.copy(subjects = shares.subjects + current.subjects))
            }
            logger.info { "Claimed legacy added shares of cloud drive $driveId: ${shares.subjects.size} subjects" }
        }
        legacyPreferences.remove(key)
    }

    /** 旧的「自己网盘」数据源删掉 (新的就是带协议的那个), 旧的「添加的分享」「分享搜索」换成新的类型, 实例 id 与启用状态不变. */
    private suspend fun claimSources(driveId: String, legacy: DriveLegacyData, saves: List<MediaSourceSave>) {
        for (save in saves) {
            when (save.factoryId.value) {
                legacy.driveFactoryId.ifBlank { null } -> {
                    instances.remove(save.instanceId)
                    logger.info { "Removed legacy drive source ${save.instanceId} of cloud drive $driveId" }
                }

                legacy.addedSharesFactoryId.ifBlank { null } -> {
                    val arguments = MediaSourceConfig.serializeArguments(
                        CloudDriveAddedShareArguments.serializer(),
                        CloudDriveAddedShareArguments(driveId),
                    )
                    replace(save, CloudDriveAddedShareMediaSource.FactoryId, save.config.copy(serializedArguments = arguments))
                    logger.info { "Converted legacy added-share source ${save.instanceId} to cloud drive $driveId" }
                }

                legacy.shareSearchFactoryId.ifBlank { null } -> {
                    // 参数字段与分享搜索相同, 加上网盘 id
                    val old = save.config.serializedArguments as? JsonObject ?: JsonObject(emptyMap())
                    val arguments = JsonObject(old + ("drive" to JsonPrimitive(driveId)))
                    replace(save, CloudDriveShareSearchMediaSource.FactoryId, save.config.copy(serializedArguments = arguments))
                    logger.info { "Converted legacy share search source ${save.instanceId} to cloud drive $driveId" }
                }
            }
        }
    }

    private suspend fun replace(save: MediaSourceSave, factoryId: me.him188.ani.datasources.api.source.FactoryId, config: MediaSourceConfig) {
        instances.updateSave(save.instanceId) {
            MediaSourceSave(instanceId, mediaSourceId, factoryId, isEnabled, config)
        }
    }

    private companion object {
        private val logger = logger<CloudDriveRegistry>()
    }
}
