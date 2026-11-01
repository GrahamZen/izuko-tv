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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.feedback.FeedbackDevice
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackRecords
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.app.domain.feedback.FeedbackTickets.SubmitResult
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.tv.AniOutlinedTextField
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.foundation.widgets.AniFocusIconButton
import me.him188.ani.app.ui.foundation.widgets.AniFocusSelectableSurface
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.foundation.widgets.aniGlassItemColor
import me.him188.ani.app.ui.foundation.widgets.aniPanelSubtitleStyle
import me.him188.ani.app.ui.foundation.widgets.aniPanelTitleStyle
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.touch_feedback_apk_busy
import me.him188.ani.app.ui.lang.touch_feedback_apk_install
import me.him188.ani.app.ui.lang.touch_feedback_apk_permission
import me.him188.ani.app.ui.lang.touch_feedback_apk_unsupported
import me.him188.ani.app.ui.lang.touch_feedback_attach
import me.him188.ani.app.ui.lang.touch_feedback_attachments
import me.him188.ani.app.ui.lang.touch_feedback_back
import me.him188.ani.app.ui.lang.touch_feedback_cancel
import me.him188.ani.app.ui.lang.touch_feedback_closed
import me.him188.ani.app.ui.lang.touch_feedback_delete
import me.him188.ani.app.ui.lang.touch_feedback_delete_confirm
import me.him188.ani.app.ui.lang.touch_feedback_description
import me.him188.ani.app.ui.lang.touch_feedback_description_hint
import me.him188.ani.app.ui.lang.touch_feedback_device_id
import me.him188.ani.app.ui.lang.touch_feedback_empty
import me.him188.ani.app.ui.lang.touch_feedback_empty_description
import me.him188.ani.app.ui.lang.touch_feedback_err_description_short
import me.him188.ani.app.ui.lang.touch_feedback_err_summary_long
import me.him188.ani.app.ui.lang.touch_feedback_err_summary_short
import me.him188.ani.app.ui.lang.touch_feedback_err_text_long
import me.him188.ani.app.ui.lang.touch_feedback_github
import me.him188.ani.app.ui.lang.touch_feedback_new
import me.him188.ani.app.ui.lang.touch_feedback_new_badge
import me.him188.ani.app.ui.lang.touch_feedback_not_ready
import me.him188.ani.app.ui.lang.touch_feedback_pending
import me.him188.ani.app.ui.lang.touch_feedback_refresh
import me.him188.ani.app.ui.lang.touch_feedback_replied
import me.him188.ani.app.ui.lang.touch_feedback_reply_hint
import me.him188.ani.app.ui.lang.touch_feedback_retrying
import me.him188.ani.app.ui.lang.touch_feedback_send
import me.him188.ani.app.ui.lang.touch_feedback_submit
import me.him188.ani.app.ui.lang.touch_feedback_submitted
import me.him188.ani.app.ui.lang.touch_feedback_summary
import me.him188.ani.app.ui.lang.touch_feedback_summary_hint
import me.him188.ani.app.ui.lang.touch_feedback_sync_partial
import me.him188.ani.app.ui.lang.touch_feedback_test_builds
import me.him188.ani.app.ui.lang.touch_feedback_title
import me.him188.ani.app.ui.lang.touch_feedback_unavailable
import me.him188.ani.app.ui.lang.touch_feedback_upload_again
import me.him188.ani.app.ui.lang.touch_feedback_uploading
import me.him188.ani.app.ui.lang.touch_feedback_updated_at
import me.him188.ani.app.ui.lang.tv_settings_feedback_from_maintainer
import me.him188.ani.app.ui.lang.tv_settings_feedback_from_me
import me.him188.ani.app.ui.settings.tv.feedbackPlainText
import me.him188.ani.utils.platform.currentTimeMillis
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds
import me.him188.ani.utils.logging.info

/** 弹窗宽: 屏宽的这一份, 不超过 [PANEL_MAX_WIDTH] (会话一行字太长不好读). */
private const val PANEL_WIDTH_FRACTION = 0.62f
private val PANEL_MAX_WIDTH = 680.dp

/** 「刷新」点过后灰多久. */
private const val REFRESH_INTERVAL = 60_000L

/** 输入停下多久存一次草稿里的文字. */
private const val DRAFT_SAVE_DELAY = 800L

/**
 * 应用内的反馈弹窗 (触屏端, 见 [TouchFeedback]): 玻璃面板里三屏 ——「我的反馈」列表、写反馈、一条反馈的会话 (故障工单可以接着补充).
 * 写反馈与补充的逻辑都在 [FeedbackTickets] (同 Web 控制台), 附件的字节来自 [FeedbackSources]. 点面板外面关掉, 写到一半的文字留在草稿里.
 */
@Composable
internal fun TouchFeedbackDialog(screen: TouchFeedback.Screen) {
    val tickets = remember { GlobalKoin.get<FeedbackTickets>() }
    val repository = remember { GlobalKoin.get<FeedbackRecordRepository>() }
    val all by repository.all.collectAsState(initial = null)
    // 提示跟着它是哪一屏的: 换了一屏就不显示上一屏的 (提交成功后进会话, 提示带到会话那一屏)
    var note by remember { mutableStateOf<Pair<TouchFeedback.Screen, FeedbackNote>?>(null) }
    fun post(on: TouchFeedback.Screen, value: FeedbackNote?) {
        note = value?.let { on to it }
    }
    LaunchedEffect(Unit) {
        tickets.expireRecording()
        FeedbackSources.tidy(tickets) { repository.current() }
    }
    AniCenteredPanelDialog(
        onDismissRequest = TouchFeedback::close,
        widthFraction = PANEL_WIDTH_FRACTION,
        maxWidth = PANEL_MAX_WIDTH,
    ) {
        val records = all ?: return@AniCenteredPanelDialog
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val onNote: (FeedbackNote?) -> Unit = { post(screen, it) }
            when (screen) {
                TouchFeedback.Screen.List -> FeedbackList(records, tickets, onNote)
                TouchFeedback.Screen.New -> FeedbackNew(records, tickets, onNote, onSubmitted = { issue, text ->
                    val target = TouchFeedback.Screen.Conversation(FeedbackKind.TICKET, issue)
                    TouchFeedback.open(target)
                    post(target, FeedbackNote(text))
                })

                is TouchFeedback.Screen.Conversation -> FeedbackConversation(
                    records.items.firstOrNull { it.kind == screen.kind && it.issue == screen.issue },
                    records, tickets, onNote,
                )
            }
            note?.takeIf { it.first == screen }?.second?.let { n ->
                Text(
                    n.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (n.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一屏的标题行: 返回 (列表那一屏没有) + 标题 / 副标题 + 右边的按钮. */
@Composable
private fun FeedbackHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            AniFocusIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Lang.touch_feedback_back))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = aniPanelTitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle, style = aniPanelSubtitleStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

// ------------------------------------------------------------------ 列表

/** 「我的反馈」: 一条一行, 新的在前; 进来就算看过 (同设置里的「我的反馈」), 这一趟里原来没看的仍标「新回复」. */
@Composable
private fun ColumnScope.FeedbackList(records: FeedbackRecords, tickets: FeedbackTickets, onNote: (FeedbackNote?) -> Unit) {
    val fresh by TouchFeedback.freshOnOpen.collectAsState()
    val uploading by tickets.uploading.collectAsState()
    LaunchedEffect(Unit) {
        TouchFeedback.freshOnOpen.value = records.items.filter { it.unread }.map(TouchFeedback::key).toSet()
        tickets.markRead()
    }
    val lastRefresh by TouchFeedback.lastRefreshAt.collectAsState()
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(lastRefresh) {
        while (true) {
            now = currentTimeMillis()
            if (now - lastRefresh >= REFRESH_INTERVAL) break
            delay(1000)
        }
    }
    val canRefresh = now - lastRefresh >= REFRESH_INTERVAL
    val device = remember { GlobalKoin.get<FeedbackDevice>() }
    val subtitle = listOfNotNull(
        stringResource(Lang.touch_feedback_device_id, device.shortId),
        records.ticketsCheckedAt?.let { stringResource(Lang.touch_feedback_updated_at, feedbackClock(it)) },
    ).joinToString(" · ")
    FeedbackHeader(stringResource(Lang.touch_feedback_title), subtitle, onBack = null) {
        AniFocusActionButton(
            onClick = {
                if (!canRefresh) return@AniFocusActionButton
                TouchFeedback.lastRefreshAt.value = currentTimeMillis()
                TouchFeedback.scope.launch {
                    val synced = tickets.syncNow()
                    touchFeedbackLogger.info { "[TouchFeedback] 刷新: ${if (synced) "查全了" else "有的没查到"}" }
                    if (!synced) onNote(FeedbackNote(getString(Lang.touch_feedback_sync_partial), error = true))
                }
            },
            enabled = canRefresh,
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(18.dp))
            Text(stringResource(Lang.touch_feedback_refresh), style = MaterialTheme.typography.labelLarge)
        }
        AniFocusActionButton(onClick = { TouchFeedback.open(TouchFeedback.Screen.New) }, enabled = tickets.available) {
            Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(18.dp))
            Text(stringResource(Lang.touch_feedback_new), style = MaterialTheme.typography.labelLarge)
        }
    }
    if (!tickets.available) {
        Text(stringResource(Lang.touch_feedback_unavailable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (records.items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Lang.touch_feedback_empty), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Lang.touch_feedback_empty_description),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val state = rememberLazyListState()
    LazyColumn(
        Modifier.weight(1f, fill = false).aniVerticalScrollFade(state),
        state = state,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(records.items, key = TouchFeedback::key) { r ->
            FeedbackRow(r, fresh = r.unread || TouchFeedback.key(r) in fresh, uploading = r.issue in uploading && r.kind == FeedbackKind.TICKET)
        }
    }
}

@Composable
private fun FeedbackRow(r: FeedbackRecord, fresh: Boolean, uploading: Boolean) {
    AniFocusSelectableSurface(
        onClick = { TouchFeedback.open(r) },
        selected = false,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    feedbackTitle(r), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (fresh) NewBadge()
            }
            val status = when {
                uploading -> stringResource(Lang.touch_feedback_uploading)
                r.uploads.isNotEmpty() -> stringResource(Lang.touch_feedback_pending, r.uploads.size)
                else -> null
            }
            Text(
                listOfNotNull(feedbackHeader(r), status).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val last = r.replies.lastOrNull()?.let { feedbackPlainText(it.body, r.apks) } ?: r.description
            if (!last.isNullOrBlank()) {
                Text(last, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 「新回复」小标签 (主题色底). */
@Composable
private fun NewBadge() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Text(
            stringResource(Lang.touch_feedback_new_badge),
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ------------------------------------------------------------------ 写反馈

/** 写新反馈: 问题、描述、附件 (必须带现场录制, 见 [FeedbackTickets.submit]); 写到一半的文字存在草稿里. */
@Composable
private fun ColumnScope.FeedbackNew(
    records: FeedbackRecords,
    tickets: FeedbackTickets,
    onNote: (FeedbackNote?) -> Unit,
    onSubmitted: (issue: Int, message: String) -> Unit,
) {
    val draft = records.draft(FeedbackTickets.NEW)
    val summary = rememberDraftText(tickets, FeedbackTickets.NEW, "summary", draft.texts["summary"])
    val description = rememberDraftText(tickets, FeedbackTickets.NEW, "description", draft.texts["description"])
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    FeedbackHeader(stringResource(Lang.touch_feedback_new), subtitle = null, onBack = { TouchFeedback.open(TouchFeedback.Screen.List) })
    val scroll = rememberScrollState()
    Column(
        Modifier.weight(1f, fill = false).aniVerticalScrollFade(scroll).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AniOutlinedTextField(
            summary.value, { summary.change(it) }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(Lang.touch_feedback_summary)) },
            placeholder = { Text(stringResource(Lang.touch_feedback_summary_hint)) },
            singleLine = true,
        )
        AniOutlinedTextField(
            description.value, { description.change(it) }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text(stringResource(Lang.touch_feedback_description)) },
            placeholder = { Text(stringResource(Lang.touch_feedback_description_hint)) },
        )
        Text(stringResource(Lang.touch_feedback_attach), style = MaterialTheme.typography.labelLarge)
        TouchFeedbackAttachments(FeedbackTickets.NEW, draft, records.recording, tickets, onNote)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        AniFocusActionButton(
            onClick = {
                if (busy) return@AniFocusActionButton
                scope.launch {
                    val s = summary.value.trim()
                    val d = description.value.trim()
                    val invalid = when {
                        s.length < FeedbackTextLimits.MIN_SUMMARY -> getString(Lang.touch_feedback_err_summary_short, FeedbackTextLimits.MIN_SUMMARY)
                        s.length > FeedbackTextLimits.MAX_SUMMARY -> getString(Lang.touch_feedback_err_summary_long, FeedbackTextLimits.MAX_SUMMARY)
                        FeedbackTextLimits.length(d) < FeedbackTextLimits.MIN_DESCRIPTION ->
                            getString(Lang.touch_feedback_err_description_short, FeedbackTextLimits.MIN_DESCRIPTION)

                        d.length > FeedbackTextLimits.MAX_DESCRIPTION -> getString(Lang.touch_feedback_err_text_long, FeedbackTextLimits.MAX_DESCRIPTION)
                        else -> null
                    }
                    if (invalid != null) {
                        onNote(FeedbackNote(invalid, error = true))
                        return@launch
                    }
                    busy = true
                    onNote(null)
                    val result = withTimeoutOrNull(30.seconds) { tickets.submit(s, d, FeedbackSources) }
                    touchFeedbackLogger.info { "[TouchFeedback] 提交新反馈: ${result ?: "30 秒没回"}" }
                    busy = false
                    if (result is SubmitResult.Submitted) {
                        // 草稿已随提交清掉: 这两个框里的字别再存回去
                        summary.discard()
                        description.discard()
                        onSubmitted(result.issue, getString(Lang.touch_feedback_submitted, result.issue))
                    } else {
                        onNote(FeedbackNote(submitFailureText(result), error = true))
                    }
                }
            },
            loading = busy,
            enabled = tickets.available,
        ) {
            Text(stringResource(Lang.touch_feedback_submit), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ------------------------------------------------------------------ 会话

/**
 * 一条反馈的会话: 描述在前, 之后维护者的回复与用户的补充按时间排, 像聊天. 故障工单还开着时下面可以接着补充 (文字与附件);
 * 没传上去的附件可以重传; 维护者给的测试包在安卓上可以直接下载安装 (iOS 上只给链接); 已结束的可以删 (只删本机的记录).
 */
@Composable
private fun ColumnScope.FeedbackConversation(
    record: FeedbackRecord?,
    records: FeedbackRecords,
    tickets: FeedbackTickets,
    onNote: (FeedbackNote?) -> Unit,
) {
    if (record == null) {
        // 删掉了 (或换了工单服务): 回列表
        LaunchedEffect(Unit) { TouchFeedback.open(TouchFeedback.Screen.List) }
        return
    }
    LaunchedEffect(record.unread) { if (record.unread) tickets.markRead() }
    var confirmDelete by remember { mutableStateOf(false) }
    FeedbackHeader(feedbackTitle(record), feedbackHeader(record), onBack = { TouchFeedback.open(TouchFeedback.Screen.List) }) {
        if (record.state != FeedbackState.OPEN) {
            AniFocusActionButton(onClick = { confirmDelete = true }, accentColor = MaterialTheme.colorScheme.error) {
                Text(stringResource(Lang.touch_feedback_delete), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    val state = rememberLazyListState()
    val messages = remember(record) { conversationOf(record) }
    // 打开时停在最新的那条
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) state.scrollToItem(messages.lastIndex) }
    LazyColumn(
        Modifier.weight(1f, fill = false).aniVerticalScrollFade(state),
        state = state,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages.size) { i -> FeedbackBubble(messages[i], record.apks) }
        if (record.apks.isNotEmpty()) item { TestBuilds(record, onNote) }
        record.kind.publicRepository?.let { repo ->
            item {
                SelectionContainer {
                    Text(
                        stringResource(Lang.touch_feedback_github, "https://github.com/$repo/issues/${record.issue}"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (record.kind == FeedbackKind.TICKET) PendingUploads(record, tickets, onNote)
    if (record.kind == FeedbackKind.TICKET && record.state == FeedbackState.OPEN) {
        ReplyComposer(record.issue, records, tickets, onNote)
    } else if (record.kind == FeedbackKind.TICKET) {
        Text(stringResource(Lang.touch_feedback_closed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmDelete) {
        AniAlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Lang.touch_feedback_delete_confirm)) },
            confirmButton = {
                AniTextButton(
                    onClick = {
                        confirmDelete = false
                        TouchFeedback.scope.launch {
                            val deleted = tickets.delete(record.kind, record.issue)
                            touchFeedbackLogger.info { "[TouchFeedback] 删除 ${record.kind} #${record.issue}: $deleted" }
                            if (deleted) TouchFeedback.open(TouchFeedback.Screen.List)
                        }
                    },
                ) { Text(stringResource(Lang.touch_feedback_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                AniTextButton(onClick = { confirmDelete = false }) { Text(stringResource(Lang.touch_feedback_cancel)) }
            },
        )
    }
}

/** 会话里的一条: [mine] = 用户自己的 (描述、补充), 靠右. */
private class FeedbackMessage(val mine: Boolean, val time: String, val body: String, val files: List<String>)

private fun conversationOf(r: FeedbackRecord): List<FeedbackMessage> = buildList {
    if (r.kind == FeedbackKind.TICKET) add(FeedbackMessage(true, feedbackTime(r.submittedAt), r.description.orEmpty(), r.files))
    for (reply in r.replies) add(FeedbackMessage(!reply.fromMaintainer, feedbackTime(reply.at), reply.body, reply.files))
}

@Composable
private fun FeedbackBubble(message: FeedbackMessage, apks: List<String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
        Surface(
            Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (message.mine) MaterialTheme.colorScheme.secondaryContainer else aniGlassItemColor(),
            contentColor = if (message.mine) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (message.mine) Lang.tv_settings_feedback_from_me else Lang.tv_settings_feedback_from_maintainer, message.time),
                    style = MaterialTheme.typography.labelSmall,
                )
                val text = if (message.mine) message.body.trim() else feedbackPlainText(message.body, apks)
                if (text.isNotEmpty()) SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                if (message.files.isNotEmpty()) {
                    Text(stringResource(Lang.touch_feedback_attachments, message.files.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 维护者回复里给的测试包: 安卓上下载并安装 (同 Web 控制台「装到电视」), 装不了 APK 的端只给链接. */
@Composable
private fun TestBuilds(record: FeedbackRecord, onNote: (FeedbackNote?) -> Unit) {
    val installer = FeedbackPlatform.apkInstaller
    val status = installer?.status?.collectAsState()?.value
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Lang.touch_feedback_test_builds), style = MaterialTheme.typography.labelLarge)
        for (url in record.apks) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (installer == null) {
                    Text(stringResource(Lang.touch_feedback_apk_unsupported), style = MaterialTheme.typography.bodySmall)
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AniFocusActionButton(
                        onClick = {
                            TouchFeedback.scope.launch {
                                val outcome = installer.install(url, record.apkSha256[url], record.issue)
                                touchFeedbackLogger.info { "[TouchFeedback] 装测试包 (#${record.issue}): $outcome" }
                                val reason = when (outcome) {
                                    FeedbackApkInstall.STARTED -> null
                                    FeedbackApkInstall.NEED_PERMISSION -> FeedbackNote(getString(Lang.touch_feedback_apk_permission))
                                    FeedbackApkInstall.BUSY -> FeedbackNote(getString(Lang.touch_feedback_apk_busy), error = true)
                                    FeedbackApkInstall.NOT_READY -> FeedbackNote(getString(Lang.touch_feedback_not_ready), error = true)
                                }
                                onNote(reason)
                            }
                        },
                    ) {
                        Text(stringResource(Lang.touch_feedback_apk_install), style = MaterialTheme.typography.labelLarge)
                    }
                    if (status?.url == url) {
                        Text(status.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 没传上去的附件: 有几个、「重新上传」(正在传时只写「上传中」). */
@Composable
private fun PendingUploads(record: FeedbackRecord, tickets: FeedbackTickets, onNote: (FeedbackNote?) -> Unit) {
    val uploading by tickets.uploading.collectAsState()
    if (record.uploads.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (record.issue in uploading) {
            Text(stringResource(Lang.touch_feedback_uploading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Row
        }
        Text(
            stringResource(Lang.touch_feedback_pending, record.uploads.size), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        )
        AniFocusActionButton(
            onClick = {
                TouchFeedback.scope.launch {
                    val retrying = tickets.retryUploads(record.issue, FeedbackSources)
                    touchFeedbackLogger.info { "[TouchFeedback] 重传 #${record.issue} 的附件: $retrying" }
                    if (retrying) onNote(FeedbackNote(getString(Lang.touch_feedback_retrying)))
                }
            },
        ) {
            Text(stringResource(Lang.touch_feedback_upload_again), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 给开着的单补充: 一段文字加附件 (都可以没有, 但不能全没有). */
@Composable
private fun ReplyComposer(issue: Int, records: FeedbackRecords, tickets: FeedbackTickets, onNote: (FeedbackNote?) -> Unit) {
    val draft = records.draft(issue)
    val body = rememberDraftText(tickets, issue, "body", draft.texts["body"])
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TouchFeedbackAttachments(issue, draft, records.recording, tickets, onNote)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            AniOutlinedTextField(
                body.value, { body.change(it) }, Modifier.weight(1f).heightIn(min = 56.dp),
                placeholder = { Text(stringResource(Lang.touch_feedback_reply_hint)) },
                maxLines = 5,
            )
            AniFocusActionButton(
                onClick = {
                    if (busy) return@AniFocusActionButton
                    scope.launch {
                        val text = body.value.trim()
                        if (text.length > FeedbackTextLimits.MAX_DESCRIPTION) {
                            onNote(FeedbackNote(getString(Lang.touch_feedback_err_text_long, FeedbackTextLimits.MAX_DESCRIPTION), error = true))
                            return@launch
                        }
                        busy = true
                        onNote(null)
                        val result = withTimeoutOrNull(30.seconds) { tickets.reply(issue, text, FeedbackSources) }
                        touchFeedbackLogger.info { "[TouchFeedback] 补充 #$issue: ${result ?: "30 秒没回"}" }
                        busy = false
                        if (result is SubmitResult.Submitted) {
                            body.clear()
                            onNote(FeedbackNote(getString(Lang.touch_feedback_replied)))
                        } else {
                            onNote(FeedbackNote(submitFailureText(result), error = true))
                        }
                    }
                },
                loading = busy,
            ) {
                Text(stringResource(Lang.touch_feedback_send), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ------------------------------------------------------------------ 草稿里的文字

/** 一个输入框的字: 改了停一会儿存进草稿 ([FeedbackTickets.setText]), 离开时没存的立刻存; 发出去了 ([discard] / [clear]) 就不再存. */
@Stable
private class DraftText(initial: String) {
    var value by mutableStateOf(initial)
        private set

    /** 改过、还没存. */
    var dirty = false

    /** 发出去了: 草稿已随提交清掉, 框里剩下的不再存回去. */
    var discarded = false
        private set

    fun change(text: String) {
        value = text
        dirty = true
    }

    fun discard() {
        discarded = true
    }

    /** 发出去了, 框清空接着写 (补充). */
    fun clear() {
        value = ""
        dirty = false
    }
}

@Composable
private fun rememberDraftText(tickets: FeedbackTickets, target: Int, field: String, saved: String?): DraftText {
    val text = remember(target, field) { DraftText(saved.orEmpty()) }
    LaunchedEffect(text, text.value) {
        if (!text.dirty || text.discarded) return@LaunchedEffect
        delay(DRAFT_SAVE_DELAY)
        tickets.setText(target, field, text.value)
        text.dirty = false
    }
    DisposableEffect(text) {
        onDispose {
            if (text.dirty && !text.discarded) {
                val value = text.value
                TouchFeedback.scope.launch { tickets.setText(target, field, value) }
            }
        }
    }
    return text
}
