/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import me.him188.ani.app.data.models.preference.SeekPreviewDisplay
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.player.SeekPreviewDecoderFault
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.settings.SettingAdjustment
import me.him188.ani.app.domain.settings.SettingAdjustments
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import org.openani.mediamp.MediaStatus

/**
 * 认出拖动预览换输出弄坏了解码器 (见 [SeekPreviewDecoderFault]) 时, 把拖动预览改成直接画在全屏上 ([switchToFullScreen], 不再换输出),
 * 改了就经 [SettingAdjustments] 告知一次 ([notify]). 不受「播放失败时自动换源」开关管: 这是这台设备的毛病, 不是换不换源的事.
 * 上一次运行在小画面预览时原生崩溃的 ([SeekPreviewDecoderFault.recordCrashInPreviousRun]) 在打开播放页时同样改.
 *
 * 原地重载由 [SwitchMediaOnPlayerErrorExtension] 做 (见 [PlaybackFailureRecovery]), 那边同时不拉黑、不换源.
 *
 * @param switchToFullScreen 把设置改成 [SeekPreviewDisplay.FULL_SCREEN]; 返回是否真的改了 (原来就是全屏时为 false)
 * @param notify 真的改了之后告知用户 (见 [SettingAdjustments.notify])
 */
class SeekPreviewDecoderFaultExtension(
    private val context: PlayerExtensionContext,
    private val switchToFullScreen: suspend () -> Boolean,
    private val notify: (SettingAdjustment) -> Unit,
) : PlayerExtension("SeekPreviewDecoderFault") {
    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("SeekPreviewDecoderFault") {
            if (SeekPreviewDecoderFault.consumeCrashInPreviousRun()) {
                val switched = switchToFullScreen()
                logger.info { "The previous run crashed during a windowed seek preview, seek preview on full screen: switched=$switched" }
                if (switched) notify(SettingAdjustment.SeekPreviewOnFullScreen(afterCrash = true))
            }
            // 与换源那边看同样的两处: 打开时的失败在加载状态里, 播起来之后的在播放器状态里
            combine(context.videoLoadingStateFlow, context.player.state) { loading, state ->
                (loading as? VideoLoadingState.UnknownError)?.cause ?: (state.mediaStatus as? MediaStatus.Error)?.error
            }.distinctUntilChanged()
                .filterNotNull()
                .collect { error ->
                    if (!SeekPreviewDecoderFault.isFault(error)) return@collect
                    if (!SeekPreviewDecoderFault.recordFault()) return@collect // 这一轮已经处理过
                    val switched = switchToFullScreen()
                    logger.info { "Decoder failed after the seek preview moved the video output (${error.message}), seek preview on full screen: switched=$switched" }
                    if (switched) notify(SettingAdjustment.SeekPreviewOnFullScreen(afterCrash = false))
                }
        }
    }

    companion object : EpisodePlayerExtensionFactory<SeekPreviewDecoderFaultExtension> {
        private val logger = logger<SeekPreviewDecoderFaultExtension>()

        override fun create(context: PlayerExtensionContext, koin: Koin): SeekPreviewDecoderFaultExtension {
            val settings = koin.get<SettingsRepository>().videoScaffoldConfig
            val adjustments = koin.get<SettingAdjustments>()
            return SeekPreviewDecoderFaultExtension(context, notify = adjustments::notify, switchToFullScreen = {
                var switched = false
                settings.update {
                    if (seekPreviewDisplay == SeekPreviewDisplay.FULL_SCREEN) {
                        this
                    } else {
                        switched = true
                        copy(seekPreviewDisplay = SeekPreviewDisplay.FULL_SCREEN)
                    }
                }
                switched
            })
        }
    }
}
