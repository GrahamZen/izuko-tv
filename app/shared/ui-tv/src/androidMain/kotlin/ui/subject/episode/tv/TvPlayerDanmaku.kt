/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.ui.danmaku.PlayerDanmakuHost
import me.him188.ani.app.ui.danmaku.UIDanmakuEvent
import me.him188.ani.app.ui.foundation.animation.StandardAccelerateEasing
import me.him188.ani.app.ui.foundation.animation.StandardDecelerateEasing
import me.him188.ani.app.ui.foundation.theme.EasingDurations
import me.him188.ani.danmaku.ui.DanmakuHostState
import me.him188.ani.danmaku.ui.DanmakuLayer
import me.him188.ani.danmaku.ui.DanmakuLayerSnapshots
import me.him188.ani.danmaku.ui.danmakuLayerHasOwnSurface
import org.openani.mediamp.MediampPlayer

/**
 * 播放页的弹幕: [PlayerDanmakuHost] 把播放状态和弹幕事件喂给 [state], 画在单独的 [DanmakuLayer] 里, 并登记给 Web 控制台截图.
 * 与视频面谁先组合见 [TvDanmakuAroundVideo].
 *
 * [enabled] 切换时整层淡入淡出 (时长同 `AniAnimatedVisibility`), 淡出完从组合里摘掉, 关着时不收弹幕.
 * [hidden] 为 true 时整层透明但照常收弹幕, 在画的时候读, 不引起重组.
 */
@Composable
internal fun TvPlayerDanmaku(
    player: MediampPlayer,
    state: DanmakuHostState,
    events: Flow<UIDanmakuEvent>,
    enabled: Boolean,
    hidden: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val visibility = remember { Animatable(if (enabled) 1f else 0f) }
    LaunchedEffect(enabled) {
        if (enabled) {
            visibility.animateTo(1f, tween(EasingDurations.standardDecelerate, easing = StandardDecelerateEasing))
        } else {
            visibility.animateTo(0f, tween(EasingDurations.standardAccelerate, easing = StandardAccelerateEasing))
        }
    }
    val fadedOut by remember { derivedStateOf { visibility.value == 0f } }
    if (!enabled && fadedOut) return

    PlayerDanmakuHost(player, state, events, drawDanmaku = false)

    val currentHidden by rememberUpdatedState(hidden)
    val snapshots = remember { DanmakuLayerSnapshots() }
    DanmakuLayer(
        state,
        modifier.then(rememberTvScreenshotDanmakuLayer(snapshots)),
        alpha = remember(visibility) { { if (currentHidden()) 0f else visibility.value } },
        snapshots = snapshots,
        // 与上面 PlayerDanmakuHost 转告的暂停同一判据; 播放器状态的值在哪个线程都能读
        playing = remember(player) { { player.state.value.isPlaying } },
    )
}

/**
 * 弹幕层与视频面的组合先后, 看弹幕此刻画在哪 ([danmakuLayerHasOwnSurface]): 画在单独表面上时弹幕在前, 退回画在界面里时视频面在前 ——
 * 两种表面都在窗口上挖洞, 把画在它之前的窗口内容清成透明, 摆反了不是字幕被清掉就是弹幕整层看不见.
 * 换画法时只有弹幕挪位置 (重新组合, 同关了再开), 视频面的槽位不变.
 */
@Composable
internal fun TvDanmakuAroundVideo(
    danmaku: @Composable () -> Unit,
    video: @Composable () -> Unit,
    danmakuOnOwnSurface: Boolean = danmakuLayerHasOwnSurface,
) {
    if (danmakuOnOwnSurface) danmaku()
    video()
    if (!danmakuOnOwnSurface) danmaku()
}
