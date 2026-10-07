/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceData
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceDataList
import me.him188.ani.app.domain.mediasource.subscription.SubscriptionUpdateData
import java.io.File

/**
 * 真实网盘测试的配置, 都来自环境变量; 网盘的协议与站点地址只放在外部文件里.
 *
 * - `ANI_CLOUD_DRIVE_SUBSCRIPTION_FILE`: 数据源订阅的 JSON (或导出的数据源 JSON), 里面要有一个「网盘」(`cloud-drive`) 数据源
 * - `ANI_CLOUD_DRIVE_ID`: 可选, 订阅里有几个网盘时用哪一个 (网盘 id), 默认第一个
 * - `ANI_CLOUD_DRIVE_COOKIE_FILE`: 存着登录 Cookie 的文件 (要登录的测试用), 测完写回轮换后的 Cookie
 * - `ANI_CLOUD_DRIVE_SUBJECT`: 可选, 条目名, 多个用 `|` 分隔
 */
internal object CloudDriveLiveConfig {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fileOf(name: String): File? = System.getenv(name)?.let(::File)?.takeIf { it.isFile }

    val cookieFile: File? get() = fileOf("ANI_CLOUD_DRIVE_COOKIE_FILE")

    fun subjectNames(default: List<String>): List<String> = System.getenv("ANI_CLOUD_DRIVE_SUBJECT")?.split('|') ?: default

    /** 订阅里的全部数据源; 没配置时为 null. */
    private fun sources(): List<ExportedMediaSourceData>? {
        val text = fileOf("ANI_CLOUD_DRIVE_SUBSCRIPTION_FILE")?.readText() ?: return null
        val root = json.parseToJsonElement(text).jsonObject
        val list = if ("exportedMediaSourceDataList" in root) {
            json.decodeFromJsonElement(SubscriptionUpdateData.serializer(), root).exportedMediaSourceDataList
        } else {
            json.decodeFromJsonElement(ExportedMediaSourceDataList.serializer(), root)
        }
        return list.mediaSources
    }

    /** 订阅里的网盘 (见 `ANI_CLOUD_DRIVE_ID`) 与它的分享搜索数据源; 没配置时为 null. */
    fun load(): Loaded? {
        val sources = sources() ?: return null
        val drives = sources.filter { it.factoryId == CloudDriveMediaSource.FactoryId }
            .map { json.decodeFromJsonElement(CloudDriveArguments.serializer(), it.arguments) }
        val wanted = System.getenv("ANI_CLOUD_DRIVE_ID")
        val drive = drives.firstOrNull { wanted == null || it.protocol.id == wanted }
            ?: error("No cloud-drive source${wanted?.let { " for $it" }.orEmpty()} in the subscription")
        val shareSearches = sources.filter { it.factoryId == CloudDriveShareSearchMediaSource.FactoryId }
            .map { json.decodeFromJsonElement(CloudDriveShareSearchArguments.serializer(), it.arguments) }
            .filter { it.drive == drive.protocol.id }
        return Loaded(drive, shareSearches)
    }

    class Loaded(val drive: CloudDriveArguments, val shareSearches: List<CloudDriveShareSearchArguments>)
}
