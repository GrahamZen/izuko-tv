/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.him188.ani.app.ui.foundation.TextWithBorder
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.player_buffer_wait_stall
import me.him188.ani.app.ui.lang.player_buffer_wait_stall_again
import me.him188.ani.app.ui.lang.player_buffer_wait_start
import me.him188.ani.app.videoplayer.media.LibassExoPlayerMediampPlayer
import me.him188.ani.app.videoplayer.media.ThrottledBufferWait
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.MediampPlayer
import kotlin.time.Duration.Companion.seconds

/**
 * 缓冲提示下面说明为什么这次要多等一会儿: 网盘直链开播前、卡住后要攒够几秒才播, 同一个视频越卡攒得越多 (见 `ThrottledSourceLoadControl`).
 * 已经缓冲了多少由上面的缓冲提示写着. 网速够快时几秒一下就攒够, 等了 [TV_BUFFER_WAIT_HINT_DELAY] 还在等才说, 免得一闪而过.
 */
@Composable
internal fun TvThrottledBufferHint(
    player: MediampPlayer,
    buffering: Boolean,
    modifier: Modifier = Modifier,
) {
    val waitFlow = (player as? LibassExoPlayerMediampPlayer)?.throttledBufferWait ?: return
    val wait by waitFlow.collectAsStateWithLifecycle()
    val waiting = buffering && wait != null
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        waited = false
        if (waiting) {
            delay(TV_BUFFER_WAIT_HINT_DELAY)
            waited = true
        }
    }
    // 收起的动画里还要画着最后那句
    var shown by remember { mutableStateOf<ThrottledBufferWait?>(null) }
    if (wait != null) shown = wait
    AniAnimatedVisibility(
        visible = waiting && waited,
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val current = shown ?: return@AniAnimatedVisibility
        val seconds = ((current.targetMillis + 999) / 1000).toInt()
        TextWithBorder(
            when {
                current.stall <= 0 -> stringResource(Lang.player_buffer_wait_start, seconds)
                current.stall == 1 -> stringResource(Lang.player_buffer_wait_stall, seconds)
                else -> stringResource(Lang.player_buffer_wait_stall_again, seconds, current.stall)
            },
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private val TV_BUFFER_WAIT_HINT_DELAY = 1.seconds
