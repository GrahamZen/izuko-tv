/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationChoice
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationProblem
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.mediaCacheLocationChoices
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_storage_cache_location
import me.him188.ani.app.ui.lang.settings_storage_cache_location_description
import me.him188.ani.app.ui.lang.settings_storage_cache_location_disconnected
import me.him188.ani.app.ui.lang.settings_storage_cache_location_disconnected_description
import me.him188.ani.app.ui.lang.settings_storage_cache_location_fat32
import me.him188.ani.app.ui.lang.settings_storage_cache_location_internal
import me.him188.ani.app.ui.lang.settings_storage_cache_location_read_only
import me.him188.ani.app.ui.lang.settings_storage_cache_location_space
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes

/**
 * 缓存可以放的位置 (见 [MediaCacheLocations]), 页面开着时跟着插拔变; 打开页面时先重列一遍, 剩余空间是新的.
 * 没有这项实现的平台 (只有安卓有) 一直没有值, 「缓存位置」不显示.
 */
internal val mediaCacheLocationsKey = TvSettingKey.Live<List<MediaCacheLocation>>("mediaCacheLocations") {
    val locations = GlobalKoin.getOrNull<MediaCacheLocations>()
    if (locations == null) {
        emptyFlow()
    } else {
        flow {
            locations.refresh()
            emitAll(locations.locations)
        }
    }
}

/**
 * 「缓存位置」: 新缓存放在哪个盘. 只列能用的; 不能用的盘 (FAT32、只读) 在说明里说为什么.
 */
internal fun TvSettingsItemsBuilder.cacheLocationItem() {
    val mediaCache = TvNetworkSettingKeys.mediaCache
    val locations = mediaCacheLocationsKey
    fun choices(v: TvSettingsValues) = mediaCacheLocationChoices(v[locations].orEmpty(), v[mediaCache]?.defaultLocation)
    choiceOf(
        listOf(mediaCache, locations), Lang.settings_storage_cache_location,
        options = ::choices,
        label = { choice -> choice.labelText() },
        read = { v -> choices(v).firstOrNull { it.id == v[mediaCache]?.defaultLocation } },
        write = { choice -> listOf(TvSettingsEdit.Update(mediaCache) { it.copy(defaultLocation = choice.id) }) },
        optionDescription = { choice -> choice.descriptionText() },
        dynamicDescription = { v ->
            val unusable = v[locations].orEmpty().mapNotNull { it.unusableText() }
            TvText.Join(listOf(tvText(Lang.settings_storage_cache_location_description)) + unusable, separator = "\n")
        },
    )
}

private fun MediaCacheLocation.nameText(): TvText =
    if (id == null || label.isBlank()) tvText(Lang.settings_storage_cache_location_internal) else tvText(label)

private fun MediaCacheLocationChoice.labelText(): TvText =
    location?.nameText() ?: tvText(Lang.settings_storage_cache_location_disconnected)

private fun MediaCacheLocationChoice.descriptionText(): TvText? {
    val location = location ?: return tvText(Lang.settings_storage_cache_location_disconnected_description)
    location.unusableText()?.let { return it }
    val free = location.freeBytes ?: return null
    val total = location.totalBytes ?: return null
    return tvText(Lang.settings_storage_cache_location_space, free.bytes.toString(), total.bytes.toString())
}

private fun MediaCacheLocation.unusableText(): TvText? = when (unusable) {
    null -> null
    MediaCacheLocationProblem.FAT32 -> tvText(Lang.settings_storage_cache_location_fat32, nameText())
    MediaCacheLocationProblem.READ_ONLY -> tvText(Lang.settings_storage_cache_location_read_only, nameText())
}
