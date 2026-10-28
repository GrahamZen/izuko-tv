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
import me.him188.ani.app.domain.media.cache.library.MediaStorageSetup
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationProblem
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_storage_cache_location_disconnected
import me.him188.ani.app.ui.lang.settings_storage_cache_location_fat32
import me.him188.ani.app.ui.lang.settings_storage_cache_location_internal
import me.him188.ani.app.ui.lang.settings_storage_cache_location_read_only
import me.him188.ani.app.ui.lang.settings_storage_where
import me.him188.ani.app.ui.lang.settings_storage_where_description
import me.him188.ani.app.ui.lang.settings_storage_where_description_app_only
import me.him188.ani.app.ui.lang.settings_storage_where_value
import me.him188.ani.app.ui.lang.storage_guide_busy
import me.him188.ani.app.ui.lang.storage_guide_mode_app
import me.him188.ani.app.ui.lang.storage_guide_mode_library

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
 * 「缓存放在哪」: 哪块盘、下完的放进片库还是应用目录 (系统不支持片库时只说哪块盘). 值写现在的设置, 按下去进引导 ([StorageGuideDialog]) 改;
 * 正在搬时先等它搬完. 下面两行有事时才出现: 别的地方还有缓存 ([cacheMoveItem]), 片库的权限被关掉了 ([revokedLibraryItem]).
 */
internal fun TvSettingsItemsBuilder.cacheLocationItem() {
    val mediaCache = TvNetworkSettingKeys.mediaCache
    val locations = mediaCacheLocationsKey
    val librarySupported = mediaLibrarySupported()
    action(
        Lang.settings_storage_where, keys = listOf(mediaCache, locations),
        value = { v ->
            val settings = v[mediaCache] ?: return@action null
            val drive = v[locations].orEmpty().firstOrNull { it.id == settings.defaultLocation }
            val driveName = drive?.nameText() ?: tvText(Lang.settings_storage_cache_location_disconnected)
            if (librarySupported) {
                tvText(
                    Lang.settings_storage_where_value,
                    driveName,
                    tvText(if (settings.libraryEnabled) Lang.storage_guide_mode_library else Lang.storage_guide_mode_app),
                )
            } else {
                driveName
            }
        },
        details = { v ->
            val unusable = v[locations].orEmpty().mapNotNull { it.unusableText() }
            val description = if (librarySupported) Lang.settings_storage_where_description else Lang.settings_storage_where_description_app_only
            TvText.Join(listOf(tvText(description)) + unusable, separator = "\n")
        },
        chevron = true,
    ) {
        listOf(
            TvSettingsEdit.Run { env ->
                if (GlobalKoin.get<MediaStorageSetup>().isMoving) {
                    env.toast(getStringOffMain(Lang.storage_guide_busy))
                } else {
                    env.show(TvSettingsOverlay.Dialog { dismiss -> StorageGuideDialog(dismiss) })
                }
            },
        )
    }
    cacheMoveItem()
    revokedLibraryItem()
}

internal fun MediaCacheLocation.nameText(): TvText =
    if (id == null || label.isBlank()) tvText(Lang.settings_storage_cache_location_internal) else tvText(label)

private fun MediaCacheLocation.unusableText(): TvText? = when (unusable) {
    null -> null
    MediaCacheLocationProblem.FAT32 -> tvText(Lang.settings_storage_cache_location_fat32, nameText())
    MediaCacheLocationProblem.READ_ONLY -> tvText(Lang.settings_storage_cache_location_read_only, nameText())
}
