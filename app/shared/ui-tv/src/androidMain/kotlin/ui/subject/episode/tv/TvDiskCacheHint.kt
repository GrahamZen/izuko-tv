/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.player_disk_cache_hint
import me.him188.ani.app.videoplayer.media.LibassExoPlayerMediampPlayer
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.MediampPlayer

/**
 * 「边下边播」关着、往回跳或拖动预览到播过的位置要联网重新下载时 (见 [LibassExoPlayerMediampPlayer.diskCacheHintEvents]) 弹气泡,
 * 提醒去设置里开. 每次启动应用最多弹一次: 开不开由用户定, 不反复催. 不画任何东西.
 */
@Composable
internal fun TvDiskCacheHint(player: MediampPlayer) {
    val events = (player as? LibassExoPlayerMediampPlayer)?.diskCacheHintEvents ?: return
    val toaster = LocalToaster.current
    val text = stringResource(Lang.player_disk_cache_hint)
    LaunchedEffect(events, toaster, text) {
        events.collect {
            if (diskCacheHintShown) return@collect
            diskCacheHintShown = true
            toaster.toast(text)
        }
    }
}

/** 这次启动已经提醒过了. 只在主线程读写. */
private var diskCacheHintShown = false
