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
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.effects.OnLifecycleEvent
import org.koin.mp.KoinPlatform

/**
 * 把「应用整体在不在前台」报给 [PlaybackActivity.appForeground]。
 *
 * 只在电视这类界面上报: 那里应用退到后台 (按 HOME、切信号源) 播放器就被按停, 别处要知道「播放页开着但人看不到」。
 * 与要不要保留播放会话的设置无关 —— 那个设置关着时播放器照样在退到后台时被按停。别的平台后台照常播, 不报, 恒为在前台。
 */
@Composable
internal fun ReportAppForegroundToPlaybackActivity() {
    if (!LocalAniUiBehavior.current.retainPlaybackSession) return
    val playbackActivity = remember { KoinPlatform.getKoin().getOrNull<PlaybackActivity>() } ?: return
    OnLifecycleEvent { event ->
        when (event) {
            Lifecycle.Event.ON_START -> playbackActivity.setAppForeground(true)
            Lifecycle.Event.ON_STOP -> playbackActivity.setAppForeground(false)
            else -> Unit
        }
    }
}
