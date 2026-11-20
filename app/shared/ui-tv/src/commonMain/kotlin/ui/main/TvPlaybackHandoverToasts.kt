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
import me.him188.ani.app.domain.episode.PlaybackHandover
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_video_loading_handover

/**
 * 正在播的资源接不上了、在后台换源时 (见 [PlaybackHandover]) 弹提示: 画面还在放已缓冲的, 告诉用户过一会儿会换成别的资源.
 * 播放会话可能保留在后台, 所以挂在 TV 根组合上.
 */
@Composable
fun TvPlaybackHandoverToasts() {
    val toaster = LocalToaster.current
    LaunchedEffect(toaster) {
        PlaybackHandover.started.collect {
            toaster.toast(getStringOffMain(Lang.subject_episode_video_loading_handover))
        }
    }
}
