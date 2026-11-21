/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.foundation.widgets.aniGlassEdge
import me.him188.ani.app.ui.foundation.widgets.aniGlassPanelColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.touch_feedback_discard
import me.him188.ani.app.ui.lang.touch_feedback_recording
import me.him188.ani.app.ui.lang.touch_feedback_stop
import me.him188.ani.utils.platform.currentTimeMillis
import org.jetbrains.compose.resources.stringResource
import me.him188.ani.utils.logging.info

/**
 * 现场录制中: 屏幕上方居中浮一条 (录了多久、「结束」「放弃」), 盖在页面上、切页面也在, 只有这一条接触摸, 别处照常操作.
 * 「结束」或录满 [FeedbackTickets.MAX_RECORDING] (按到点那一刻算) 后回到录给的那一条 (见 [TouchFeedback.screenOf]).
 */
@Composable
internal fun TouchFeedbackRecordingBar() {
    val repository = remember { GlobalKoin.get<FeedbackRecordRepository>() }
    val tickets = remember { GlobalKoin.get<FeedbackTickets>() }
    val recordingFlow = remember(repository) { repository.all.map { it.recording }.distinctUntilChanged() }
    val recording by recordingFlow.collectAsState(initial = null)
    val rec = recording ?: return
    val max = FeedbackTickets.MAX_RECORDING.inWholeMilliseconds
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(rec) {
        while (true) {
            now = currentTimeMillis()
            if (now - rec.since >= max) {
                touchFeedbackLogger.info { "[TouchFeedback] 现场录制到 ${max / 1000} 秒, 自动结束 (给 ${rec.target})" }
                tickets.expireRecording()
                TouchFeedback.open(TouchFeedback.screenOf(rec.target))
                break
            }
            delay(500)
        }
    }
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = CircleShape,
            color = aniGlassPanelColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = aniGlassEdge(),
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                Text(
                    stringResource(Lang.touch_feedback_recording, feedbackDuration((now - rec.since).coerceAtMost(max)), feedbackDuration(max)),
                    style = MaterialTheme.typography.labelLarge,
                )
                AniFocusActionButton(
                    onClick = {
                        TouchFeedback.scope.launch {
                            touchFeedbackLogger.info { "[TouchFeedback] 现场录制点了结束 (给 ${rec.target}, 录了 ${(currentTimeMillis() - rec.since) / 1000} 秒)" }
                            tickets.stopRecording()
                            TouchFeedback.open(TouchFeedback.screenOf(rec.target))
                        }
                    },
                ) {
                    Text(stringResource(Lang.touch_feedback_stop), style = MaterialTheme.typography.labelLarge)
                }
                AniFocusActionButton(
                    onClick = {
                        TouchFeedback.scope.launch {
                            touchFeedbackLogger.info { "[TouchFeedback] 现场录制点了放弃 (给 ${rec.target})" }
                            tickets.cancelRecording()
                        }
                    },
                ) {
                    Text(stringResource(Lang.touch_feedback_discard), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
