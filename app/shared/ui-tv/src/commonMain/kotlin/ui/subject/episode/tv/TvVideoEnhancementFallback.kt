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
import androidx.compose.runtime.remember
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.preference.VideoEnhancementDefaultMode
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.player_video_enhancement_auto_off
import me.him188.ani.app.videoplayer.videoenhancement.VideoEnhancementFallback
import org.jetbrains.compose.resources.stringResource

/**
 * 画质增强因为设备算不过来被播放器自己关掉时 (见 [VideoEnhancementFallback]): 把「默认画质增强」写回关闭
 * (下次进播放页就不建效果图), 再提示一句. 不画任何东西.
 */
@Composable
internal fun TvVideoEnhancementFallback() {
    val toaster = LocalToaster.current
    val text = stringResource(Lang.player_video_enhancement_auto_off)
    val settings = remember { GlobalKoin.get<SettingsRepository>() }
    LaunchedEffect(toaster, text, settings) {
        VideoEnhancementFallback.events.collect {
            // 离开播放页也要写完
            withContext(NonCancellable) {
                settings.videoScaffoldConfig.update { copy(videoEnhancementDefaultMode = VideoEnhancementDefaultMode.OFF) }
            }
            toaster.toast(text)
        }
    }
}
