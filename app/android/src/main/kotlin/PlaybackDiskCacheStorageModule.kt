/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.videoplayer.cache.PlaybackDiskCacheStorage
import me.him188.ani.app.videoplayer.media.AndroidPlaybackDiskCacheStorage
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module

/**
 * 「边下边播」存在本机的视频 ([PlaybackDiskCacheStorage], 设置页「存储」显示占用、能清除). 开关关着时把存下的删掉:
 * 关掉的那一刻, 以及启动时 (上次关掉后没删完、或别的途径留下的).
 */
internal fun Module.playbackDiskCacheStorage(coroutineScope: CoroutineScope) {
    single<PlaybackDiskCacheStorage>(createdAtStart = true) {
        val storage = AndroidPlaybackDiskCacheStorage(androidContext())
        val enabled = get<SettingsRepository>().videoScaffoldConfig.flow.map { it.enablePlaybackDiskCache }.distinctUntilChanged()
        coroutineScope.launch {
            enabled.collect { if (!it) storage.clear() }
        }
        storage
    }
}
