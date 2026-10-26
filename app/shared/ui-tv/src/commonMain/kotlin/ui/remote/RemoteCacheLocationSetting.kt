/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationChoice
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationProblem
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.mediaCacheLocationChoices
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_storage_cache_location
import me.him188.ani.app.ui.lang.settings_storage_cache_location_description
import me.him188.ani.app.ui.lang.settings_storage_cache_location_disconnected
import me.him188.ani.app.ui.lang.settings_storage_cache_location_fat32
import me.him188.ani.app.ui.lang.settings_storage_cache_location_internal
import me.him188.ani.app.ui.lang.settings_storage_cache_location_read_only
import me.him188.ani.app.ui.lang.settings_storage_cache_location_space
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform

/**
 * 「缓存位置」卡片, 同电视设置页「存储」里那一项: 下拉现列此刻接着、能用的盘 (带剩余空间), 不能用的盘在卡片状态里说为什么.
 * 文案都用设置页的, 不用补网页译文.
 */
internal object RemoteCacheLocationSetting {
    val spec = RemoteSettingSpec(
        key = "cacheLocation",
        settings = { mediaCacheSettings },
        serializer = MediaCacheSettings.serializer(),
        fieldName = "defaultLocation",
        title = Lang.settings_storage_cache_location,
        description = Lang.settings_storage_cache_location_description,
        editor = RemoteSettingEditor.Options { options() },
        status = { status() },
    )

    /** 没有这项实现的平台只有内部存储 */
    private fun locations(): MediaCacheLocations? = KoinPlatform.getKoin().getOrNull()

    private suspend fun options(): List<RemoteSettingEditor.Options.Option> {
        val locations = locations()
            ?: return listOf(RemoteSettingEditor.Options.Option(null, getString(Lang.settings_storage_cache_location_internal)))
        locations.refresh()
        val selected = KoinPlatform.getKoin().get<SettingsRepository>().mediaCacheSettings.flow.first().defaultLocation
        return mediaCacheLocationChoices(locations.locations.value, selected).map { choice ->
            RemoteSettingEditor.Options.Option(choice.id, labelOf(choice))
        }
    }

    private suspend fun status(): String? {
        val unusable = locations()?.locations?.value.orEmpty().mapNotNull { location ->
            when (location.unusable) {
                null -> null
                MediaCacheLocationProblem.FAT32 -> getString(Lang.settings_storage_cache_location_fat32, nameOf(location))
                MediaCacheLocationProblem.READ_ONLY -> getString(Lang.settings_storage_cache_location_read_only, nameOf(location))
            }
        }
        return unusable.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private suspend fun labelOf(choice: MediaCacheLocationChoice): String {
        val location = choice.location ?: return getString(Lang.settings_storage_cache_location_disconnected)
        val free = location.freeBytes
        val total = location.totalBytes
        if (free == null || total == null) return nameOf(location)
        return nameOf(location) + " · " + getString(Lang.settings_storage_cache_location_space, free.bytes.toString(), total.bytes.toString())
    }

    private suspend fun nameOf(location: MediaCacheLocation): String =
        if (location.id == null || location.label.isBlank()) getString(Lang.settings_storage_cache_location_internal) else location.label
}
