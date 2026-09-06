/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.data.models.preference.DISPLAY_MODE_FOLLOW_CONTENT
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.app.videoplayer.ui.ContentFrameRateEffect
import org.koin.mp.KoinPlatform

/**
 * 「播放时屏幕刷新率」选了「跟随片源」时按视频帧率切刷新率 (见 [ContentFrameRateEffect]).
 * 播放页不在眼前时换回去 —— TV 退出播放页后会话还保留着、导航去设置页时播放页都还在组合里, 只看组合存活会一直停在电影的刷新率上;
 * 回到播放页再按帧率切.
 */
@Composable
internal fun PlayerFrameRateMatching(vm: EpisodeViewModel) {
    val playbackActivity = remember { KoinPlatform.getKoin().get<PlaybackActivity>() }
    val onScreen by playbackActivity.onScreen.collectAsStateWithLifecycle()
    ContentFrameRateEffect(vm.player, vm.videoScaffoldConfig.displayModeId == DISPLAY_MODE_FOLLOW_CONTENT && onScreen)
}
