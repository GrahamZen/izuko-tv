/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.download.DownloadSpaceGuard
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_paused_low_space
import me.him188.ani.app.ui.lang.cache_paused_low_space_drive
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.getString

/**
 * 存储快满、缓存下载被全部暂停时弹提示 (见 [DownloadSpaceGuard.lowSpacePauses]). 下载可能在任何一页 (或 Web 控制台) 开始,
 * 所以挂在 TV 根组合上.
 */
@Composable
fun TvLowSpaceToasts() {
    val toaster = LocalToaster.current
    LaunchedEffect(toaster) {
        DownloadSpaceGuard.lowSpacePauses.collect { pause ->
            val free = pause.freeBytes.bytes.toString()
            val message = when (val location = pause.location) {
                null -> getString(Lang.cache_paused_low_space, free, pause.paused)
                // 外接盘满了: 说是哪个盘, 内部存储上的下载照常
                else -> getString(Lang.cache_paused_low_space_drive, driveLabel(location), free, pause.paused)
            }
            toaster.toast(message)
        }
    }
}

/** 盘的名字 (系统给的), 列表里找不到时用卷 UUID */
private fun driveLabel(location: String): String =
    GlobalKoin.getOrNull<MediaCacheLocations>()?.locations?.value?.firstOrNull { it.id == location }?.label
        ?.takeIf { it.isNotBlank() } ?: location
