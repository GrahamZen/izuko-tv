/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.theme.AniTheme
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_tv_skip_segment
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults
import org.jetbrains.compose.resources.stringResource

/**
 * 播放器左下角的跳过 OP/ED 提示 ([PlayerSkipOpEdState.currentTip]), 两副面孔:
 * - 自动跳过正在倒计时: 「即将跳过 OP 或 ED · 取消」([PlayerControllerDefaults.LeftBottomTips]);
 * - 人已经在 OP/ED 里 (「手动」档, 或「自动, 取消后手动」档里取消过): 「跳过 OP」/「跳过 ED」, 按下跳到这一段的结尾,
 *   出了这一段自己消失. 顶栏那颗固定快进 80/85/90 秒的按钮不看章节, 跟它不是一回事.
 */
@Composable
fun EpisodeSkipOpEdTip(state: PlayerSkipOpEdState, modifier: Modifier = Modifier) {
    val tip = state.currentTip
    // 淡出途中 currentTip 已经是 null: 照最后一次的样子画完淡出, 不让字先没了
    val last = remember { LastTip() }
    if (tip != null) last.value = tip
    AniAnimatedVisibility(visible = tip != null, modifier) {
        val shown = last.value ?: return@AniAnimatedVisibility
        if (shown.canCancel) {
            PlayerControllerDefaults.LeftBottomTips(onClick = { state.cancelSkipOpEd() })
        } else {
            SkipNowTip(shown.kind, onClick = { state.skipOpEd() })
        }
    }
}

/** 上一个非空的提示 (只给淡出用, 不触发重组). */
private class LastTip {
    var value: SkipOpEdTip? = null
}

/** 「跳过 OP」/「跳过 ED」: 同倒计时那颗提示的样子 (深色胶囊), 整颗可按. */
@Composable
private fun SkipNowTip(kind: SkipOpEdKind, onClick: () -> Unit) {
    AniTheme(darkModeOverride = DarkMode.DARK) {
        Surface(
            onClick = onClick,
            modifier = Modifier.testTag(TAG_SKIP_OP_ED_NOW),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.FastForward, contentDescription = null, Modifier.size(18.dp))
                Text(
                    // "OP"/"ED" 是原文照抄的行话, 各语言一样, 不进资源
                    stringResource(
                        Lang.video_player_tv_skip_segment,
                        when (kind) {
                            SkipOpEdKind.OP -> "OP"
                            SkipOpEdKind.ED -> "ED"
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

const val TAG_SKIP_OP_ED_NOW = "skipOpEdNow"
