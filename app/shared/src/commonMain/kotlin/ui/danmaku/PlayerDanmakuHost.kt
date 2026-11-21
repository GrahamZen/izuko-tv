/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.danmaku

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.him188.ani.danmaku.ui.DanmakuHost
import me.him188.ani.danmaku.ui.DanmakuHostState
import me.him188.ani.danmaku.ui.DanmakuPresentation
import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isAndroid
import org.openani.mediamp.MediampPlayer
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A [DanmakuHost] that is connected with the [player].
 */
@Composable
fun PlayerDanmakuHost(
    player: MediampPlayer,
    danmakuHostState: DanmakuHostState,
    danmakuEvent: Flow<UIDanmakuEvent>,
    modifier: Modifier = Modifier,
    /** false: 只把播放状态和弹幕事件喂给 [danmakuHostState], 由别处画 (电视播放页的 `DanmakuLayer`). */
    drawDanmaku: Boolean = true,
) {
    LaunchedEffect(player, danmakuHostState) {
        player.state.collect {
            danmakuHostState.setPaused(!it.isPlaying)
        }
    }
    // 安卓上在后台收: 每条弹幕的排版与生成图都在调用方线程做, 只有放进轨道那一步切回主线程 (见 DanmakuHostState.trySend).
    // iOS / 桌面的文字排版是 Skia 的, 它的段落样式缓存不能多线程同时用 (后台量弹幕与主线程排界面文字撞上就抛 ConcurrentModificationException), 留在主线程
    LaunchedEffect(danmakuEvent, danmakuHostState) {
        withContext(if (currentPlatform().isAndroid()) Dispatchers.Default else EmptyCoroutineContext) {
        danmakuEvent.collect { event ->
            when (event) {
                is UIDanmakuEvent.Add -> {
                    danmakuHostState.trySend(event.presentation)
                }

                is UIDanmakuEvent.Repopulate -> {
                    danmakuHostState.repopulate(event.list, event.currentPositionMillis)
                }
            }
        }
        }
    }

    if (drawDanmaku) DanmakuHost(danmakuHostState, modifier)
}

sealed class UIDanmakuEvent {
    data class Add(
        val presentation: DanmakuPresentation
    ) : UIDanmakuEvent()

    data class Repopulate(
        val list: List<DanmakuPresentation>,
        val currentPositionMillis: Long
    ) : UIDanmakuEvent()
}
