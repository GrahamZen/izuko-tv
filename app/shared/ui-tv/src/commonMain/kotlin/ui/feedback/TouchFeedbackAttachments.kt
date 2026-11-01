/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.ImageFormat
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.compressImage
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.feedback.FeedbackDraft
import me.him188.ani.app.data.models.feedback.FeedbackRecording
import me.him188.ani.app.data.models.feedback.FeedbackWindow
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.foundation.widgets.AniFocusChip
import me.him188.ani.app.ui.foundation.widgets.AniFocusIconButton
import me.him188.ani.app.ui.foundation.widgets.aniGlassItemColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.touch_feedback_attach_photo
import me.him188.ani.app.ui.lang.touch_feedback_attach_record
import me.him188.ani.app.ui.lang.touch_feedback_attach_record_description
import me.him188.ani.app.ui.lang.touch_feedback_attach_report
import me.him188.ani.app.ui.lang.touch_feedback_err_another_recording
import me.him188.ani.app.ui.lang.touch_feedback_err_photo_count
import me.him188.ani.app.ui.lang.touch_feedback_err_photo_large
import me.him188.ani.app.ui.lang.touch_feedback_err_photo_open
import me.him188.ani.app.ui.lang.touch_feedback_log_lead
import me.him188.ani.app.ui.lang.touch_feedback_log_only
import me.him188.ani.app.ui.lang.touch_feedback_photo_item
import me.him188.ani.app.ui.lang.touch_feedback_record_again
import me.him188.ani.app.ui.lang.touch_feedback_recorded
import me.him188.ani.app.ui.lang.touch_feedback_recording
import me.him188.ani.app.ui.lang.touch_feedback_remove
import me.him188.ani.app.ui.lang.touch_feedback_report_health
import me.him188.ani.app.ui.lang.touch_feedback_report_recording
import me.him188.ani.app.ui.lang.touch_feedback_stop
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.platform.currentTimeMillis
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn

/** 「性能报告」里列出的报告最多几份 (新的在前, 同 Web 控制台). */
private const val LISTED_REPORTS = 12

/** 日志往前多截几分钟的几档 (同 Web 控制台). */
private val LOG_LEADS = listOf(0, 1, 3, 5).filter { it <= FeedbackTickets.MAX_LOG_LEAD_MINUTES }

/**
 * 一条反馈 (新工单, 或给一张单的补充; 草稿 [target]) 的附件: 「附上」现场录制 / 照片 / 性能报告 (安卓上已有报告时才有这一项),
 * 下面列出已附上的, 都可以去掉. 现场录制开始后弹窗关掉, 去别的页面复现, 点屏幕上方录制条的「结束」回来 (见 [TouchFeedbackRecordingBar]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TouchFeedbackAttachments(
    target: Int,
    draft: FeedbackDraft,
    recording: FeedbackRecording?,
    tickets: FeedbackTickets,
    onNote: (FeedbackNote?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val reports = remember { FeedbackPlatform.perfReports(LISTED_REPORTS) }
    var showReports by remember { mutableStateOf(false) }
    val photoPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) scope.launch { onNote(attachPhoto(file, target, tickets)) }
    }
    val tooManyPhotos = stringResource(Lang.touch_feedback_err_photo_count, FeedbackTickets.MAX_PHOTOS)
    val anotherRecording = stringResource(Lang.touch_feedback_err_another_recording)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AniFocusChip(
                text = stringResource(if (draft.recorded != null) Lang.touch_feedback_record_again else Lang.touch_feedback_attach_record),
                selected = false,
                onClick = {
                    scope.launch {
                        // 弹窗关掉, 去别的页面复现; 录制条在屏幕上方
                        val started = tickets.startRecording(target)
                        touchFeedbackLogger.info { "[TouchFeedback] 开始现场录制 (给 $target): ${if (started) "开始" else "已有别的录制"}" }
                        if (started) TouchFeedback.close() else onNote(FeedbackNote(anotherRecording, error = true))
                    }
                },
            )
            AniFocusChip(
                text = stringResource(Lang.touch_feedback_attach_photo),
                selected = false,
                onClick = {
                    if (draft.photos.size >= FeedbackTickets.MAX_PHOTOS) onNote(FeedbackNote(tooManyPhotos, error = true)) else photoPicker.launch()
                },
            )
            if (reports.isNotEmpty()) {
                AniFocusChip(stringResource(Lang.touch_feedback_attach_report), selected = showReports, onClick = { showReports = !showReports })
            }
        }
        if (draft.recorded == null && recording?.target != target) {
            Text(
                stringResource(Lang.touch_feedback_attach_record_description, FeedbackTickets.MAX_RECORDING.inWholeMinutes.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showReports) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in reports) {
                    val attached = r.id in draft.perfReports
                    AniFocusChip(reportLabel(r), selected = attached, onClick = { scope.launch { tickets.attachPerf(target, r.id, !attached) } })
                }
            }
        }
        if (recording?.target == target) RecordingNow(recording, tickets)
        draft.recorded?.let { w -> Recorded(target, w, draft.logLeadMinutes, tickets) }
        if (!showReports) {
            val attached = remember(draft.perfReports) { draft.perfReports.mapNotNull(FeedbackPlatform::perfReport) }
            for (report in attached) {
                AttachedChip(reportLabel(report)) { scope.launch { tickets.attachPerf(target, report.id, false) } }
            }
        }
        draft.photos.forEachIndexed { i, photo ->
            AttachedChip(stringResource(Lang.touch_feedback_photo_item, i + 1, feedbackSize(photo.size))) {
                scope.launch {
                    tickets.removePhoto(target, photo.id)
                    FeedbackPhotoStore.delete(photo.id)
                }
            }
        }
    }
}

/** 弹窗是在录的时候打开的: 录了多久 + 「结束」(同录制条). */
@Composable
private fun RecordingNow(recording: FeedbackRecording, tickets: FeedbackTickets) {
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(recording) {
        while (true) {
            now = currentTimeMillis()
            delay(1000)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(
                Lang.touch_feedback_recording,
                feedbackDuration(now - recording.since), feedbackDuration(FeedbackTickets.MAX_RECORDING.inWholeMilliseconds),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
        AniFocusActionButton(onClick = { scope.launch { tickets.stopRecording() } }) {
            Text(stringResource(Lang.touch_feedback_stop), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 录好的那段 + 日志截多少 (只要录制期间 / 往前多截几分钟). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Recorded(target: Int, window: FeedbackWindow, lead: Int, tickets: FeedbackTickets) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AttachedChip(stringResource(Lang.touch_feedback_recorded, feedbackDuration(window.to - window.from))) {
            scope.launch { tickets.removeRecorded(target) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (minutes in LOG_LEADS) {
                AniFocusChip(
                    text = if (minutes == 0) stringResource(Lang.touch_feedback_log_only) else stringResource(Lang.touch_feedback_log_lead, minutes),
                    selected = lead == minutes,
                    onClick = { scope.launch { tickets.setLogLead(target, minutes) } },
                )
            }
        }
    }
}

/** 已附上的一项: 字 + 去掉. */
@Composable
private fun AttachedChip(text: String, onRemove: () -> Unit) {
    Surface(shape = CircleShape, color = aniGlassItemColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(start = 16.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            AniFocusIconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(Lang.touch_feedback_remove), Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun reportLabel(r: FeedbackPerfReport): String =
    if (r.health) stringResource(Lang.touch_feedback_report_health, r.time) else stringResource(Lang.touch_feedback_report_recording, r.seconds, r.time)

/**
 * 从相册选的一张照片附到 [target]: 同 Web 控制台, 压成长边 2048 的 JPEG, 还超过 [FeedbackPhotoStore.MAX_BYTES] 再压一次 (1600 / 0.7),
 * 还超就不附. 附上了回 null, 否则回给用户看的原因.
 */
private suspend fun attachPhoto(file: PlatformFile, target: Int, tickets: FeedbackTickets): FeedbackNote? = withContext(Dispatchers.IO_) {
    val first = runCatching {
        FileKit.compressImage(file, imageFormat = ImageFormat.JPEG, quality = 85, maxWidth = 2048, maxHeight = 2048)
    }.onFailure { e -> touchFeedbackLogger.warn(e) { "[TouchFeedback] 照片解不开 (给 $target)" } }
        .getOrNull() ?: return@withContext FeedbackNote(getString(Lang.touch_feedback_err_photo_open), error = true)
    val bytes = if (first.size <= FeedbackPhotoStore.MAX_BYTES) first else runCatching {
        FileKit.compressImage(first, imageFormat = ImageFormat.JPEG, quality = 70, maxWidth = 1600, maxHeight = 1600)
    }.getOrDefault(first)
    touchFeedbackLogger.info { "[TouchFeedback] 照片 (给 $target): 压成 ${first.size} 字节${if (bytes !== first) ", 再压 ${bytes.size}" else ""}" }
    if (bytes.size > FeedbackPhotoStore.MAX_BYTES) {
        return@withContext FeedbackNote(
            getString(Lang.touch_feedback_err_photo_large, feedbackSize(bytes.size.toLong()), feedbackSize(FeedbackPhotoStore.MAX_BYTES.toLong())),
            error = true,
        )
    }
    val photo = FeedbackPhotoStore.save(bytes) ?: return@withContext FeedbackNote(getString(Lang.touch_feedback_err_photo_open), error = true)
    if (!tickets.addPhoto(target, photo)) {
        touchFeedbackLogger.info { "[TouchFeedback] 照片已满 ${FeedbackTickets.MAX_PHOTOS} 张, 没附上" }
        FeedbackPhotoStore.delete(photo.id)
        return@withContext FeedbackNote(getString(Lang.touch_feedback_err_photo_count, FeedbackTickets.MAX_PHOTOS), error = true)
    }
    null
}
