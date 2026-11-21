/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackReply
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.domain.feedback.FeedbackNotices
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_feedback_action_cancel
import me.him188.ani.app.ui.lang.tv_feedback_action_install
import me.him188.ani.app.ui.lang.tv_feedback_action_supplement
import me.him188.ani.app.ui.lang.tv_feedback_apk
import me.him188.ani.app.ui.lang.tv_feedback_apk_closed
import me.him188.ani.app.ui.lang.tv_feedback_apk_downloading
import me.him188.ani.app.ui.lang.tv_feedback_apk_downloading_unknown
import me.him188.ani.app.ui.lang.tv_feedback_apk_failed
import me.him188.ani.app.ui.lang.tv_feedback_apk_hint
import me.him188.ani.app.ui.lang.tv_feedback_apk_installing
import me.him188.ani.app.ui.lang.tv_feedback_apk_none
import me.him188.ani.app.ui.lang.tv_feedback_apk_older
import me.him188.ani.app.ui.lang.tv_feedback_apk_verifying
import me.him188.ani.app.ui.lang.tv_feedback_new_hint_after
import me.him188.ani.app.ui.lang.tv_feedback_new_hint_record
import me.him188.ani.app.ui.lang.tv_feedback_new_hint_write
import me.him188.ani.app.ui.lang.tv_feedback_new_scan
import me.him188.ani.app.ui.lang.tv_feedback_new_scan_target
import me.him188.ani.app.ui.lang.tv_feedback_new_summary
import me.him188.ani.app.ui.lang.tv_feedback_new_title
import me.him188.ani.app.ui.lang.tv_feedback_supplement_hint
import me.him188.ani.app.ui.lang.tv_feedback_supplement_title
import me.him188.ani.app.ui.lang.tv_notice_feedback_result
import me.him188.ani.app.ui.lang.tv_remote_control_close
import me.him188.ani.app.ui.lang.tv_remote_qr_connected
import me.him188.ani.app.ui.lang.tv_remote_qr_ip_changed
import me.him188.ani.app.ui.lang.tv_remote_qr_waiting
import me.him188.ani.app.ui.lang.tv_settings_feedback
import me.him188.ani.app.ui.lang.tv_settings_feedback_empty
import me.him188.ani.app.ui.lang.tv_settings_feedback_files_only
import me.him188.ani.app.ui.lang.tv_settings_feedback_from_maintainer
import me.him188.ani.app.ui.lang.tv_settings_feedback_from_me
import me.him188.ani.app.ui.lang.tv_settings_feedback_header
import me.him188.ani.app.ui.lang.tv_settings_feedback_joined
import me.him188.ani.app.ui.lang.tv_settings_feedback_kind_entry
import me.him188.ani.app.ui.lang.tv_settings_feedback_kind_logo
import me.him188.ani.app.ui.lang.tv_settings_feedback_kind_ticket
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_closed
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_open
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_entry
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_entry_id
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_logo
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_logo_id
import me.him188.ani.app.ui.remote.AppInstallKind
import me.him188.ani.app.ui.remote.AppInstallPhase
import me.him188.ani.app.ui.remote.AppInstallStatus
import me.him188.ani.app.ui.remote.RemoteAppUpdate
import me.him188.ani.app.ui.remote.RemoteConnectionStatus
import me.him188.ani.app.ui.remote.RemoteQrCode
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.update.pickTestPackageUrl
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * 通知中心的「我的反馈」签 (见 docs/contributing/code/notices.md): 最上面一行「写新反馈」(右栏是控制台「设置 → 反馈」的码, 只写故障工单),
 * 下面是这台电视提交过的反馈, 一张单一行, 分「处理中」「已结束」两段,
 * 段内按最近的回复或结果新的在前. 右栏是来往记录 (旧的在上, 打开时停在最新一条; 焦点在按钮上时上下键翻看) 与操作:
 * - 故障工单还开着、维护者的回复里给了测试包: 「安装测试包」, 挑这台设备装得上的那个 ([pickTestPackageUrl]) 交给
 *   [RemoteAppUpdate.installTestPackage] (同控制台的「装到电视」, 进度在动作面板顶上); 正在装这张单的包时换成「取消下载」;
 * - 故障工单: 「在手机上补充」, 弹控制台码, 扫码直接打开控制台里的这张单 (`#settings/feedback/<种类>-<单号>`).
 * 修正请求 (标题 logo、对应作品) 只看结果, 没有操作.
 *
 * 提醒: 维护者的回复与处理结果是通知 ([TvFeedbackNotice], 只在这个签里看, 不在「通知」签里列). 签上的红点 = 其中有没看过的,
 * 焦点到这个签时全算看过 ([onTabShown]); 每一行的红点是这张单的未读, 右栏写过哪张只清那张 ([onShown]).
 */
internal object TvFeedbackTab : TvNoticeTab {
    const val ID: String = "feedback"

    override val id: String = ID
    override val label: StringResource = Lang.tv_settings_feedback
    override val bodyStartsAtEnd: Boolean = true
    override val buttonsForSingleAction: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val repository: FeedbackRecordRepository?
        get() = runCatching { KoinPlatform.getKoin().get<FeedbackRecordRepository>() }.getOrNull()

    override val hasFresh: Flow<Boolean>
        get() = TvNotices.shown.map { list -> list.orEmpty().any { it.isFeedback() && !it.entry.read } }.distinctUntilChanged()

    @Composable
    override fun rememberItems(): List<TvNoticeColumnItem>? {
        val records by remember { repository?.records ?: flowOf(emptyList()) }.collectAsState(null)
        val install by RemoteAppUpdate.status.collectAsState()
        // 触屏上最上面那行在应用里写 (见 TvFeedbackTabTouch), 电视上扫码去控制台写
        val newItem = touchFeedbackWriteItem() ?: newFeedbackItem()
        return records?.let { list -> listOf(newItem) + feedbackOrder(list).map { feedbackItem(it, install) } }
    }

    @Composable
    override fun emptyText(): String = stringResource(Lang.tv_settings_feedback_empty)

    /** 焦点到了这个签: 反馈的回复全算看过 (侧边栏「通知」上的数字不再为它们算, 也不再为它们弹气泡); 签上的点亮到那张单在右栏写过为止. */
    override fun onTabShown() {
        TvNotices.markSeen(TvNotices.shown.value.orEmpty().filter { it.isFeedback() })
    }

    override fun onShown(key: String) {
        if (key == NEW_KEY) return
        // 这张单的那条通知也算读过 (键相同)
        TvNotices.markReadKeys(listOf(key))
        val tickets = runCatching { KoinPlatform.getKoin().get<FeedbackTickets>() }.getOrNull() ?: return
        scope.launch {
            val record = repository?.current()?.items?.let { FeedbackNotices.recordOf(key, it) } ?: return@launch
            if (record.unread) tickets.markRead(record.kind, record.issue)
        }
    }

    /** 在电视上打开 [key] 这张单 (键同 [FeedbackNotices.keyOf]; 通知里反馈那条的「查看」). */
    fun open(key: String) = TvNotices.open(focusKey = key, tab = ID)

    /** 「写新反馈」那一行的键 (不与 [FeedbackNotices.keyOf] 的「feedback:种类-单号」重). */
    const val NEW_KEY: String = "feedback:new"

    private fun TvShownNotice.isFeedback(): Boolean = notice.kind == FeedbackNotices.KIND
}

/**
 * 列表最上面「写新反馈」: 只写故障工单 (标题 logo、对应作品照旧在详情页里报). 焦点在这一行时右栏就是控制台「设置 → 反馈」的码与连接状态,
 * 不用再按; 在这一行上按确定键不做事 ([TvNoticeColumnItem.rowAction] 留给触屏上直接开写反馈的弹窗).
 */
@Composable
private fun newFeedbackItem(): TvNoticeColumnItem {
    val kind = stringResource(Lang.tv_settings_feedback_kind_ticket)
    return TvNoticeColumnItem(
        key = TvFeedbackTab.NEW_KEY,
        icon = Icons.Outlined.EditNote,
        iconLabel = kind,
        title = stringResource(Lang.tv_feedback_new_title),
        atMillis = null,
        summary = stringResource(Lang.tv_feedback_new_summary),
        chips = listOf(TvNoticeChip(kind)),
        blocks = newFeedbackBlocks(),
        // 码在最上面: 正文比右栏高时也从顶上开始 (签里别的行停在最新一条)
        bodyStartsAtEnd = false,
    )
}

/**
 * 「写新反馈」的右栏: 码 (扫码打开控制台「设置 → 反馈」) 与连接状态, 下面写怎么写、要带现场录制、提交后去哪看.
 * 签里的行在通知弹窗开着时一直备着, 所以弹窗一打开就取一次控制台地址.
 */
@Composable
private fun newFeedbackBlocks(): List<TvNoticeBlock> {
    val base by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    val status = consoleConnectionStatus(base, hostChanged, phoneConnected)
    return listOf(
        TvNoticeQr(
            url = base?.let { it + NEW_FEEDBACK_CONSOLE_FRAGMENT },
            caption = stringResource(Lang.tv_feedback_new_scan_target),
            title = stringResource(Lang.tv_feedback_new_scan),
            status = status?.first,
            statusTone = status?.second ?: TvNoticeTone.Normal,
            console = true,
        ),
        TvNoticeParagraph(AnnotatedString(stringResource(Lang.tv_feedback_new_hint_write))),
        TvNoticeParagraph(AnnotatedString(stringResource(Lang.tv_feedback_new_hint_record))),
        TvNoticeParagraph(AnnotatedString(stringResource(Lang.tv_feedback_new_hint_after)), tone = TvNoticeTone.Secondary),
    )
}

/** 控制台码旁的连接状态 (文字同 RemoteConnectionStatus): 还没有地址时为 null. */
@Composable
private fun consoleConnectionStatus(url: String?, hostChanged: Boolean, phoneConnected: Boolean): Pair<String, TvNoticeTone>? = when {
    url == null -> null
    hostChanged -> stringResource(Lang.tv_remote_qr_ip_changed) to TvNoticeTone.Error
    phoneConnected -> stringResource(Lang.tv_remote_qr_connected) to TvNoticeTone.Ok
    else -> stringResource(Lang.tv_remote_qr_waiting) to TvNoticeTone.Normal
}

/** 控制台「设置 → 反馈」页 (写新反馈在这一页). */
private const val NEW_FEEDBACK_CONSOLE_FRAGMENT = "#settings/feedback"

/** 「处理中」在前, 段内按最近的回复或结果新的在前. */
internal fun feedbackOrder(records: List<FeedbackRecord>): List<FeedbackRecord> =
    records.sortedWith(compareBy<FeedbackRecord> { it.state != FeedbackState.OPEN }.thenByDescending { FeedbackNotices.timeOf(it) })

/** 维护者最近一次给的测试包 (那条回复里的几个链接); 没给过为空. */
internal fun latestTestPackages(record: FeedbackRecord): List<String> {
    val reply = record.replies.lastOrNull { r -> r.fromMaintainer && record.apks.any { it in r.body } } ?: return emptyList()
    return record.apks.filter { it in reply.body }
}

@Composable
private fun feedbackItem(record: FeedbackRecord, install: AppInstallStatus): TvNoticeColumnItem {
    val open = record.state == FeedbackState.OPEN
    val kind = stringResource(kindLabel(record.kind))
    val state = stringResource(feedbackStateLabel(record))
    val packages = if (record.kind == FeedbackKind.TICKET && open) latestTestPackages(record) else emptyList()
    val apk = packages.takeIf { it.isNotEmpty() }?.let { pickTestPackageUrl(it) }
    // 电视上正在装的是这张单的测试包
    val mine = install.kind == AppInstallKind.TestPackage && install.issue == record.issue
    val installLabel = stringResource(Lang.tv_feedback_action_install)
    val cancelLabel = stringResource(Lang.tv_feedback_action_cancel)
    val supplementLabel = stringResource(Lang.tv_feedback_action_supplement)
    val actions = buildList {
        if (apk != null) {
            add(
                if (mine && install.phase.busy) {
                    TvNoticeAction(cancelLabel, TvNoticeDestination.Run { RemoteAppUpdate.cancel() })
                } else {
                    TvNoticeAction(
                        installLabel,
                        TvNoticeDestination.Run { toaster -> RemoteAppUpdate.installTestPackage(apk, record.apkSha256[apk], record.issue, toaster) },
                    )
                },
            )
        }
        if (record.kind == FeedbackKind.TICKET) {
            add(
                touchFeedbackSupplementAction(record)
                    ?: TvNoticeAction(supplementLabel, TvNoticeDestination.Dialog { onDismiss -> TvFeedbackSupplementDialog(record, onDismiss) }),
            )
        }
    }
    return TvNoticeColumnItem(
        key = FeedbackNotices.keyOf(record),
        icon = kindIcon(record.kind),
        iconLabel = kind,
        title = feedbackTitle(record),
        atMillis = FeedbackNotices.timeOf(record),
        summary = stringResource(Lang.tv_settings_feedback_header, kind, record.issue, state),
        fresh = record.unread,
        section = stringResource(if (open) Lang.tv_settings_feedback_state_open else Lang.tv_settings_feedback_state_closed),
        chips = listOf(TvNoticeChip(kind), TvNoticeChip(state, accent = open)),
        subject = if (record.joinedExisting) stringResource(Lang.tv_settings_feedback_joined) else null,
        actions = actions,
        blocks = feedbackBlocks(record, latest = packages, apk = apk, install = install.takeIf { mine }),
    )
}

private fun kindLabel(kind: FeedbackKind): StringResource = when (kind) {
    FeedbackKind.TICKET -> Lang.tv_settings_feedback_kind_ticket
    FeedbackKind.LOGO -> Lang.tv_settings_feedback_kind_logo
    FeedbackKind.ENTRY -> Lang.tv_settings_feedback_kind_entry
}

private fun kindIcon(kind: FeedbackKind): ImageVector = when (kind) {
    FeedbackKind.TICKET -> Icons.Outlined.Feedback
    FeedbackKind.LOGO -> Icons.Outlined.Image
    FeedbackKind.ENTRY -> Icons.Outlined.Info
}

/** 故障工单写那一句话, 修正请求写「《作品》的标题 logo」「《作品》对应的 TMDB 作品」. */
@Composable
private fun feedbackTitle(record: FeedbackRecord): String = when (record.kind) {
    FeedbackKind.TICKET -> record.summary?.takeIf { it.isNotBlank() } ?: stringResource(Lang.tv_settings_feedback_kind_ticket)
    FeedbackKind.LOGO -> record.subjectName?.takeIf { it.isNotBlank() }?.let { stringResource(Lang.tv_settings_feedback_title_logo, it) }
        ?: stringResource(Lang.tv_settings_feedback_title_logo_id, record.subjectId ?: 0)

    FeedbackKind.ENTRY -> record.subjectName?.takeIf { it.isNotBlank() }?.let { stringResource(Lang.tv_settings_feedback_title_entry, it) }
        ?: stringResource(Lang.tv_settings_feedback_title_entry_id, record.subjectId ?: 0)
}

/**
 * 右栏正文: 提交时写的, 再是回复 (旧的在上), 给了测试包的回复下面一张测试包卡片, 有结果的最后写处理结果.
 * [latest] 是维护者最近一次给的测试包 ([apk] 是其中这台设备装得上的那个, [install] 是它正在装的进度).
 */
@Composable
private fun feedbackBlocks(record: FeedbackRecord, latest: List<String>, apk: String?, install: AppInstallStatus?): List<TvNoticeBlock> {
    val zone = remember { TimeZone.currentSystemDefault() }
    val open = record.state == FeedbackState.OPEN
    val blocks = mutableListOf<TvNoticeBlock>()
    val first = if (record.kind == FeedbackKind.TICKET) record.description?.let { feedbackPlainText(it) }.orEmpty() else feedbackTitle(record)
    feedbackMessage(false, monthDayTime(record.submittedAt, zone), first, hasFiles = record.kind == FeedbackKind.TICKET && record.files.isNotEmpty())
        ?.let(blocks::add)
    for (reply in record.replies) {
        feedbackMessage(reply.fromMaintainer, replyTime(reply, zone), feedbackPlainText(reply.body, record.apks), reply.files.isNotEmpty())
            ?.let(blocks::add)
        val given = record.apks.filter { it in reply.body }
        if (reply.fromMaintainer && given.isNotEmpty()) {
            blocks += testPackageCard(given, isLatest = given == latest, apk = apk, install = install, open = open)
        }
    }
    if (!open) {
        blocks += TvNoticeParagraph(
            AnnotatedString(stringResource(feedbackStateLabel(record))),
            title = stringResource(Lang.tv_notice_feedback_result),
            tone = TvNoticeTone.Emphasis,
        )
    }
    return blocks
}

/** 来往记录里的一条: 抬头「我 / 维护者 · 时间」; 只带了附件的写「(只有附件)」那句, 什么都没有的不列. */
@Composable
private fun feedbackMessage(fromMaintainer: Boolean, time: String, text: String, hasFiles: Boolean): TvNoticeMessage? {
    val meta = stringResource(if (fromMaintainer) Lang.tv_settings_feedback_from_maintainer else Lang.tv_settings_feedback_from_me, time)
    val filesOnly = stringResource(Lang.tv_settings_feedback_files_only)
    val body = text.ifEmpty { if (hasFiles) filesOnly else "" }
    if (body.isEmpty()) return null
    return TvNoticeMessage(meta, body, mine = !fromMaintainer)
}

/**
 * 一次回复里给的测试包: 写这台设备会装的那个文件名; 下面一行是装到哪一步了 (只有最近一次给的、单子还开着才写进度与提示).
 * 正在下载时带进度 (不知道总长时画不定式的).
 */
@Composable
private fun testPackageCard(given: List<String>, isLatest: Boolean, apk: String?, install: AppInstallStatus?, open: Boolean): TvNoticeCard {
    val name = (apk?.takeIf { it in given } ?: given.first()).substringBefore('?').substringAfterLast('/')
    val downloading = isLatest && install?.phase == AppInstallPhase.Downloading
    return TvNoticeCard(
        icon = Icons.Outlined.Android,
        title = stringResource(Lang.tv_feedback_apk, name),
        status = testPackageStatus(isLatest, apk, install, open),
        statusTone = if (isLatest && install?.phase == AppInstallPhase.Failed) TvNoticeTone.Error else TvNoticeTone.Normal,
        progress = install?.progress?.takeIf { downloading },
        indeterminate = downloading && install?.progress == null,
    )
}

@Composable
private fun testPackageStatus(isLatest: Boolean, apk: String?, install: AppInstallStatus?, open: Boolean): String {
    if (!open) return stringResource(Lang.tv_feedback_apk_closed)
    if (!isLatest) return stringResource(Lang.tv_feedback_apk_older)
    if (apk == null) return stringResource(Lang.tv_feedback_apk_none)
    return when (install?.phase) {
        AppInstallPhase.Downloading -> {
            val done = install.downloaded
            val total = install.total
            if (done != null && total != null) {
                stringResource(Lang.tv_feedback_apk_downloading, sizeText(done), sizeText(total))
            } else {
                stringResource(Lang.tv_feedback_apk_downloading_unknown)
            }
        }

        AppInstallPhase.Verifying -> stringResource(Lang.tv_feedback_apk_verifying)
        AppInstallPhase.WaitingPlayer, AppInstallPhase.Preparing, AppInstallPhase.Confirm, AppInstallPhase.WaitingFront ->
            stringResource(Lang.tv_feedback_apk_installing)
        AppInstallPhase.Failed -> stringResource(Lang.tv_feedback_apk_failed, install.error.orEmpty())
        else -> stringResource(Lang.tv_feedback_apk_hint)
    }
}

/** 「在手机上补充」: 控制台码, 扫码直接打开控制台里的这张单 (见 RemoteFeedback 的 `#settings/feedback/<种类>-<单号>`). */
@Composable
private fun TvFeedbackSupplementDialog(record: FeedbackRecord, onDismiss: () -> Unit) {
    val base by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    val url = base?.let { it + feedbackConsoleFragment(record) }
    AniCenteredPanelDialog(onDismissRequest = onDismiss, heightFraction = null, maxWidth = SUPPLEMENT_DIALOG_WIDTH) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteQrCode(url, SUPPLEMENT_QR_SIZE, SUPPLEMENT_QR_QUIET_ZONE)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Lang.tv_feedback_supplement_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                RemoteConnectionStatus(base, hostChanged, phoneConnected, MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Lang.tv_feedback_supplement_hint, record.issue), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                AniFocusActionButton(onDismiss, Modifier.tvWindowInitialFocus()) {
                    Text(stringResource(Lang.tv_remote_control_close), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** 控制台里这张单的地址 (`#settings/feedback/TICKET-61`). */
internal fun feedbackConsoleFragment(record: FeedbackRecord): String = "#settings/feedback/${record.kind.name}-${record.issue}"

private fun replyTime(reply: FeedbackReply, zone: TimeZone): String =
    runCatching { monthDayTime(Instant.parse(reply.at).toEpochMilliseconds(), zone) }.getOrDefault(reply.at)

/** 「月-日 时:分」, 按 [zone] 的当地时间. */
private fun monthDayTime(millis: Long, zone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    fun Int.pad() = toString().padStart(2, '0')
    return "${t.month.number.pad()}-${t.day.pad()} ${t.hour.pad()}:${t.minute.pad()}"
}

private fun sizeText(bytes: Long): String =
    if (bytes >= 1_048_576) "${(bytes / 1_048_576.0).roundToInt()} MB" else "${(bytes / 1024.0).roundToInt().coerceAtLeast(1)} KB"

private val SUPPLEMENT_DIALOG_WIDTH = 640.dp
private val SUPPLEMENT_QR_SIZE = 160.dp
private val SUPPLEMENT_QR_QUIET_ZONE = 12.dp
