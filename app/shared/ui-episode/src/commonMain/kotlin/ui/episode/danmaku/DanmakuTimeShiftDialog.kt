/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.episode.danmaku

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.aniDialogContainerColor
import me.him188.ani.app.ui.foundation.widgets.dismissDialogButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_danmaku_cancel
import me.him188.ani.app.ui.lang.settings_danmaku_confirm
import me.him188.ani.app.ui.lang.subject_episode_danmaku_time_shift_current_offset
import me.him188.ani.app.ui.lang.subject_episode_danmaku_time_shift_description
import me.him188.ani.app.ui.lang.subject_episode_danmaku_time_shift_reset
import me.him188.ani.app.ui.lang.subject_episode_danmaku_time_shift_restore
import me.him188.ani.app.ui.lang.subject_episode_danmaku_time_shift_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

@Composable
fun DanmakuTimeShiftDialog(
    serviceName: String,
    currentShiftMillis: Long,
    onDismissRequest: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val sliderRange = -30_000f..30_000f
    var shift by remember {
        mutableFloatStateOf(currentShiftMillis.toFloat().coerceIn(sliderRange.start, sliderRange.endInclusive))
    }
    LaunchedEffect(currentShiftMillis) {
        shift = currentShiftMillis.toFloat().coerceIn(sliderRange.start, sliderRange.endInclusive)
    }
    fun adjust(amount: Float) {
        shift = (shift + amount).coerceIn(sliderRange.start, sliderRange.endInclusive)
    }

    val shiftLabel = remember(shift) { formatDanmakuShiftMillis(shift.roundToLong()) }
    val confirmText = stringResource(Lang.settings_danmaku_confirm)
    val cancelText = stringResource(Lang.settings_danmaku_cancel)
    val titleText = stringResource(Lang.subject_episode_danmaku_time_shift_title, serviceName)
    val descriptionText = stringResource(Lang.subject_episode_danmaku_time_shift_description)
    val currentOffsetText = stringResource(Lang.subject_episode_danmaku_time_shift_current_offset, shiftLabel)
    val resetText = stringResource(Lang.subject_episode_danmaku_time_shift_reset)
    val restoreText = stringResource(Lang.subject_episode_danmaku_time_shift_restore)

    AlertDialog(
        // 对话框是独立窗口, 按键到不了 TV 播放页的根按键路由 —— 它从播放器的弹幕面板开出来,
        // 画面就在后面放着, 遥控器播放暂停键仍该管用. 播放页之外为空操作
        modifier = Modifier.testTag("danmaku-time-shift-dialog").tvOverlayWindowKeys(onDismissRequest),
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(shift.roundToLong()) },
                modifier = Modifier.testTag("danmaku-shift-confirm")
            ) {
                Text(confirmText)
            }
        },
        dismissButton = dismissDialogButton(cancelText, onDismissRequest, Modifier.testTag("danmaku-shift-cancel")),
        title = { Text(titleText) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(descriptionText)
                Text(currentOffsetText)
                // M3 Slider 自己会把方向键 (含上下) 当作调值消费, 焦点会被困在
                // slider 上. 在 preview 阶段把上下键改成焦点移动, 左右仍归 slider 调值
                val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation
                val focusManager = LocalFocusManager.current
                Slider(
                    value = shift,
                    onValueChange = { shift = it.coerceIn(sliderRange.start, sliderRange.endInclusive) },
                    valueRange = sliderRange,
                    modifier = Modifier.ifThen(focusDriven) {
                        onPreviewKeyEvent { event ->
                            when (event.key) {
                                Key.DirectionUp, Key.DirectionDown -> {
                                    if (event.type == KeyEventType.KeyDown) {
                                        focusManager.moveFocus(
                                            if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down,
                                        )
                                    }
                                    // KeyUp 也吞掉, 避免 slider 收到不成对的按键事件
                                    true
                                }

                                else -> false
                            }
                        }
                    },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { adjust(-500f) }) { Text("-0.5 s") }
                    TextButton(onClick = { adjust(-100f) }) { Text("-0.1 s") }
                    TextButton(onClick = { adjust(100f) }) { Text("+0.1 s") }
                    TextButton(
                        onClick = { adjust(500f) },
                        modifier = Modifier.testTag("danmaku-shift-increase")
                    ) { Text("+0.5 s") }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { shift = 0f }) {
                        Text(resetText)
                    }
                    OutlinedButton(
                        onClick = {
                            shift = currentShiftMillis.toFloat().coerceIn(sliderRange.start, sliderRange.endInclusive)
                        },
                    ) {
                        Text(restoreText)
                    }
                }
            }
        },
        containerColor = aniDialogContainerColor(),
    )
}
