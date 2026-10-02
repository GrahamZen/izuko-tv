/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceTier
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.datasources.api.source.serializeArguments
import me.him188.ani.utils.platform.Uuid
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * 用户自己加的 WebDAV 服务器 (NAS): 每台是一个「网盘」数据源 ([CloudDriveMediaSource]), 协议 ([CloudDriveProtocol.type] = [WebDav.TYPE])
 * 里只有名字、地址与媒体文件夹, 可以随数据源导出; 账号密码存在网盘账号里 ([CloudDriveService.loginWithPassword]), 不随数据源导出.
 *
 * 默认层级 T0: 和自己网盘一样是自己的原文件, 画质最好.
 */
object WebDavSources {
    /** 一台新服务器的协议 (id 随机, 之后改名字、地址都不变). 地址不是 http(s) 时抛 [IllegalArgumentException]. */
    fun newProtocol(name: String, url: String): CloudDriveProtocol {
        val id = "webdav-" + (1..8).map { ID_CHARS[Random.nextInt(ID_CHARS.length)] }.joinToString("")
        return withServer(CloudDriveProtocol(id = id, name = name.trim(), type = WebDav.TYPE, http = DriveHttpConfig(requestTimeoutMillis = 30_000)), url)
    }

    /** 换成服务器地址 [url] (去掉首尾空白). 地址不是 http(s) 时抛 [IllegalArgumentException]. */
    fun withServer(protocol: CloudDriveProtocol, url: String): CloudDriveProtocol {
        val server = WebDav.parseServerUrl(url) ?: throw IllegalArgumentException("WebDAV 地址要以 http:// 或 https:// 开头")
        val text = server.toString()
        return protocol.copy(http = protocol.http.copy(hosts = listOf(text)), websiteUrl = text)
    }

    /** 加一个 [protocol] 的数据源 (启用, 层级 T0), 等到数据源列表里看得见它才返回. */
    suspend fun add(manager: MediaSourceManager, protocol: CloudDriveProtocol): String {
        val instanceId = Uuid.randomString()
        val arguments = CloudDriveArguments(protocol = protocol, tier = MediaSourceTier(0u))
        val config = MediaSourceConfig(serializedArguments = MediaSourceConfig.serializeArguments(CloudDriveArguments.serializer(), arguments))
        manager.addInstance(instanceId, instanceId, CloudDriveMediaSource.FactoryId, config)
        withTimeoutOrNull(INSTANCE_VISIBLE_TIMEOUT) { manager.allInstances.first { list -> list.any { it.instanceId == instanceId } } }
        return instanceId
    }

    /** 改网盘 [driveId] 的协议 (改名、换地址、改媒体文件夹). 没有这个网盘的数据源时返回 false. */
    suspend fun update(manager: MediaSourceManager, driveId: String, transform: (CloudDriveProtocol) -> CloudDriveProtocol): Boolean {
        val instance = instanceOf(manager, driveId) ?: return false
        val arguments = instance.config.deserializeArgumentsOrNull(CloudDriveArguments.serializer()) ?: return false
        val updated = arguments.copy(protocol = transform(arguments.protocol))
        if (updated == arguments) return true
        val serialized = MediaSourceConfig.serializeArguments(CloudDriveArguments.serializer(), updated)
        return manager.updateConfig(instance.instanceId, instance.config.copy(serializedArguments = serialized))
    }

    /** 删掉网盘 [driveId] 的数据源 (只用于用户自己加的 WebDAV; 订阅来的会随订阅更新回来). */
    suspend fun remove(manager: MediaSourceManager, driveId: String) {
        instanceOf(manager, driveId)?.let { manager.removeInstance(it.instanceId) }
    }

    private suspend fun instanceOf(manager: MediaSourceManager, driveId: String): MediaSourceInstance? =
        manager.allInstances.first().firstOrNull {
            it.factoryId == CloudDriveMediaSource.FactoryId &&
                    runCatching { it.config.deserializeArgumentsOrNull(CloudDriveArguments.serializer())?.protocol?.id }.getOrNull() == driveId
        }

    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    private val INSTANCE_VISIBLE_TIMEOUT = 5.seconds
}
