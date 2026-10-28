/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import me.him188.ani.app.domain.media.cache.library.MediaLibraryAccess
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_storage_delete_cache_when_done
import me.him188.ani.app.ui.lang.settings_storage_delete_cache_when_done_description
import me.him188.ani.app.ui.lang.settings_storage_delete_cache_when_done_library_confirm
import me.him188.ani.app.ui.lang.settings_storage_delete_cache_when_done_library_text
import me.him188.ani.app.ui.lang.settings_storage_delete_cache_when_done_library_title
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_adb_ok
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_adb_text
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_adb_title
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_app
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_details
import me.him188.ani.app.ui.lang.settings_storage_media_library_grant_list
import me.him188.ani.app.ui.lang.settings_storage_media_library_regrant
import me.him188.ani.app.ui.lang.settings_storage_media_library_revoked
import me.him188.ani.app.ui.lang.settings_storage_media_library_revoked_title
import kotlin.time.Duration.Companion.seconds

/** 片库要的权限: 系统支不支持、此刻开没开 */
internal data class TvMediaLibraryAccess(val supported: Boolean, val granted: Boolean)

/**
 * 片库的权限状态, 页面开着时每秒看一次: 从系统设置给完权限回来, 跟着变.
 * 没有片库实现的平台 (只有安卓有) 一直没有值.
 */
internal val mediaLibraryAccessKey = TvSettingKey.Live<TvMediaLibraryAccess>("mediaLibraryAccess") {
    val access = GlobalKoin.getOrNull<MediaLibraryAccess>()
    if (access == null) {
        emptyFlow()
    } else {
        flow {
            while (true) {
                emit(TvMediaLibraryAccess(access.isSupported, access.isGranted()))
                delay(1.seconds)
            }
        }.distinctUntilChanged()
    }
}

/**
 * 「片库暂时不可用 · 重新授权」: 片库开着而「所有文件访问」被关掉时才有. 按下去去系统里给 ([MediaLibraryAccess.openGrantPage]),
 * 一个入口都没有时给 adb 命令; 给下来之后片库自己恢复 (发布那边每隔一阵看一次权限, 见 MediaLibraryPublisher).
 */
internal fun TvSettingsItemsBuilder.revokedLibraryItem() {
    val mediaCache = TvNetworkSettingKeys.mediaCache
    val access = mediaLibraryAccessKey
    action(
        Lang.settings_storage_media_library_revoked_title, Lang.settings_storage_media_library_revoked,
        keys = listOf(mediaCache, access),
        value = { tvText(Lang.settings_storage_media_library_regrant) },
        visible = { v -> v[mediaCache]?.libraryEnabled == true && v[access]?.let { it.supported && !it.granted } == true },
    ) {
        listOf(TvSettingsEdit.Run { env -> askForPermission(env, GlobalKoin.get()) })
    }
}

/**
 * 「标记看过后删除缓存」. 开着片库时打开它先问一句: 下完的缓存都在片库里, 它删的是片库里的视频 (打开片库时会把它关掉).
 */
internal fun TvSettingsItemsBuilder.deleteCacheWhenDoneItem() {
    val mediaCache = TvNetworkSettingKeys.mediaCache
    toggleOf(
        listOf(mediaCache), Lang.settings_storage_delete_cache_when_done,
        description = { tvText(Lang.settings_storage_delete_cache_when_done_description) },
        confirm = { v, target ->
            if (target && v[mediaCache]?.libraryEnabled == true) {
                TvSettingsConfirm(
                    title = tvText(Lang.settings_storage_delete_cache_when_done_library_title),
                    text = tvText(Lang.settings_storage_delete_cache_when_done_library_text),
                    confirmLabel = tvText(Lang.settings_storage_delete_cache_when_done_library_confirm),
                    destructive = true,
                )
            } else {
                null
            }
        },
        read = { v -> v[mediaCache]?.deleteWhenMarkedDone == true },
        write = { on -> listOf(TvSettingsEdit.Update(mediaCache) { it.copy(deleteWhenMarkedDone = on) }) },
    )
}

/**
 * 去系统页面给「所有文件访问」, 按打开的是哪种页面提示怎么点; 一个入口都没有时给 adb 命令.
 * 系统页面各家不同: 只管这个应用的 (Shield 是直接弹「是 / 否」, 有的电视给的是所有应用的列表)、所有应用的列表、应用信息页 (索尼这类).
 */
private suspend fun askForPermission(env: TvSettingsEnv, access: MediaLibraryAccess) {
    when (access.openGrantPage()) {
        MediaLibraryAccess.GrantPage.APP -> env.toast(getStringOffMain(Lang.settings_storage_media_library_grant_app))
        MediaLibraryAccess.GrantPage.LIST -> env.toast(getStringOffMain(Lang.settings_storage_media_library_grant_list))
        MediaLibraryAccess.GrantPage.APP_DETAILS -> env.toast(getStringOffMain(Lang.settings_storage_media_library_grant_details))
        null -> {
            val command = access.adbGrantCommand ?: return
            val info = TvSettingsConfirm(
                title = tvText(Lang.settings_storage_media_library_grant_adb_title),
                text = tvText(Lang.settings_storage_media_library_grant_adb_text, command),
                confirmLabel = tvText(Lang.settings_storage_media_library_grant_adb_ok),
            )
            env.ask(info) {}
        }
    }
}
