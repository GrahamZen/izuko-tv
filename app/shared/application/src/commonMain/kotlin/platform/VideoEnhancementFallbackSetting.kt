/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.VideoEnhancementDefaultMode
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.settings.SettingAdjustment
import me.him188.ani.app.domain.settings.SettingAdjustments
import me.him188.ani.app.videoplayer.videoenhancement.VideoEnhancementFallback

/**
 * 画质增强因为设备算不过来被播放器当场关掉时 (见 [VideoEnhancementFallback]): 把「默认画质增强」写回关闭 (下次进播放页就不建效果图),
 * 再经 [SettingAdjustments] 告知用户. 应用启动就开始收, 哪个页面在播都一样.
 */
class VideoEnhancementFallbackSetting(
    private val settings: SettingsRepository,
    private val adjustments: SettingAdjustments,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            VideoEnhancementFallback.events.collect {
                settings.videoScaffoldConfig.update { copy(videoEnhancementDefaultMode = VideoEnhancementDefaultMode.OFF) }
                adjustments.notify(SettingAdjustment.VideoEnhancementTooSlow)
            }
        }
    }
}
