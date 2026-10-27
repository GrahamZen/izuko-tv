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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.cache.library.MediaLibraryAccess
import me.him188.ani.app.domain.media.cache.library.MediaStorageSetup
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_move_started
import me.him188.ani.app.ui.lang.settings_storage_media_library
import me.him188.ani.app.ui.lang.settings_storage_media_library_delete_when_done_off
import me.him188.ani.app.ui.lang.settings_storage_media_library_description
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_adb_text
import me.him188.ani.app.ui.lang.settings_storage_media_library_revoked
import me.him188.ani.app.ui.lang.settings_storage_media_library_unsupported
import me.him188.ani.app.ui.lang.storage_guide_busy
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform

/**
 * 「片库」卡片, 同电视设置「存储 → 缓存放在哪」引导里的片库那一项: 没有「所有文件访问」时打不开, 状态里给 adb 命令
 * (控制台这边多半就在电脑上); 开着但权限被关掉时状态里说片库暂时不可用. 去系统授权页的流程只在电视上的引导里有.
 * 开关之后照引导的默认做法把已有的搬过去 (见 [changed]).
 * 平台没有片库实现时打不开 (控制台只在安卓电视上有, 那里总有实现).
 */
internal object RemoteMediaLibrarySetting {
    val spec = RemoteSettingSpec(
        key = "mediaLibrary",
        settings = { mediaCacheSettings },
        serializer = MediaCacheSettings.serializer(),
        fieldName = "libraryEnabled",
        title = Lang.settings_storage_media_library,
        description = Lang.settings_storage_media_library_description,
        editor = RemoteSettingEditor.Toggle,
        status = { status() },
        check = { value -> if (value.jsonPrimitive.boolean) cannotTurnOn() else null },
        onSaved = { value -> changed(value.jsonPrimitive.boolean) },
    )

    private fun access(): MediaLibraryAccess? = KoinPlatform.getKoin().getOrNull()

    private suspend fun status(): String? {
        val access = access() ?: return null
        if (!access.isSupported) return getString(Lang.settings_storage_media_library_unsupported)
        if (access.isGranted()) return null
        val enabled = KoinPlatform.getKoin().get<SettingsRepository>().mediaCacheSettings.flow.first().libraryEnabled
        return listOfNotNull(
            if (enabled) getString(Lang.settings_storage_media_library_revoked) else null,
            access.adbGrantCommand?.let { getString(Lang.settings_storage_media_library_grant_adb_text, it) },
        ).joinToString("\n").ifEmpty { null }
    }

    /** 打不开的原因; 能打开时为 `null` */
    private suspend fun cannotTurnOn(): String? {
        val access = access() ?: return getString(Lang.settings_storage_media_library_unsupported)
        if (!access.isSupported) return getString(Lang.settings_storage_media_library_unsupported)
        if (access.isGranted()) return null
        return access.adbGrantCommand?.let { getString(Lang.settings_storage_media_library_grant_adb_text, it) }
            ?: getString(Lang.settings_storage_media_library_unsupported)
    }

    /**
     * 开关之后照电视设置引导的默认做法把已有的搬过去 (见 [MediaStorageSetup]): 打开 = 下完的放进片库、片库里的跟着到缓存位置那块盘,
     * 一并关掉「标记看过后删除缓存」; 关掉 = 片库里的移回应用目录. 提示里说搬了多少.
     */
    private suspend fun changed(on: Boolean): String? {
        val setup = KoinPlatform.getKoin().getOrNull<MediaStorageSetup>() ?: return null
        val deleteWhenDoneWasOn = KoinPlatform.getKoin().get<SettingsRepository>().mediaCacheSettings.flow.first().deleteWhenMarkedDone
        val choice = setup.current()
        val total = setup.plan(choice).total
        val started = setup.apply(choice, moveExisting = true)
        return listOfNotNull(
            when {
                total.isEmpty -> null
                started -> getString(Lang.cache_move_started, total.units, total.bytes.bytes.toString())
                else -> getString(Lang.storage_guide_busy)
            },
            if (on && deleteWhenDoneWasOn) getString(Lang.settings_storage_media_library_delete_when_done_off) else null,
        ).joinToString("\n").ifEmpty { null }
    }
}
