/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_settings_feedback
import me.him188.ani.app.ui.lang.tv_settings_feedback_apk
import me.him188.ani.app.ui.lang.tv_settings_feedback_description
import me.him188.ani.app.ui.lang.tv_settings_feedback_empty
import me.him188.ani.app.ui.lang.tv_settings_feedback_empty_description
import me.him188.ani.app.ui.lang.tv_settings_feedback_files_only
import me.him188.ani.app.ui.lang.tv_settings_feedback_from_maintainer
import me.him188.ani.app.ui.lang.tv_settings_feedback_from_me
import me.him188.ani.app.ui.lang.tv_settings_feedback_header
import me.him188.ani.app.ui.lang.tv_settings_feedback_joined
import me.him188.ani.app.ui.lang.tv_settings_feedback_kind_entry
import me.him188.ani.app.ui.lang.tv_settings_feedback_kind_logo
import me.him188.ani.app.ui.lang.tv_settings_feedback_kind_ticket
import me.him188.ani.app.ui.lang.tv_settings_feedback_new
import me.him188.ani.app.ui.lang.tv_settings_feedback_open_count
import me.him188.ani.app.ui.lang.tv_settings_feedback_section
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_accepted
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_closed
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_not_planned
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_open
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_rejected
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_resolved
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_entry
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_entry_id
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_logo
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_logo_id
import me.him188.ani.app.ui.lang.tv_settings_feedback_unread_count
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 「我的反馈」的数据: 这台电视提交过的反馈 (新的在前), 与这一趟进来时还没看过的那几条 ([fresh], 行 key, 见 [feedbackItems]). */
internal data class TvFeedbackList(val records: List<FeedbackRecord>, val fresh: Set<String> = emptySet()) {
    fun isFresh(r: FeedbackRecord): Boolean = r.unread || feedbackKey(r) in fresh
}

/** 这一趟进「我的反馈」时还没看过的那几条: 一进去就全标成看过 (头像上的红点消失), 这几条在里面照样标「新回复」, 下次进去重算. */
private val freshOnOpen = MutableStateFlow<Set<String>>(emptySet())

internal val TvFeedbackRecordsKey = TvSettingKey.Live("feedbackRecords") {
    GlobalKoin.get<FeedbackRecordRepository>().records.combine(freshOnOpen) { records, fresh -> TvFeedbackList(records, fresh) }
}

internal fun feedbackKey(r: FeedbackRecord): String = "${r.kind.name}-${r.issue}"

/**
 * 「我的反馈」(账号页最前面; 没登录时排在登录的常见问题后面, [showHeader] 给它加一行组标题): 这台电视提交过的故障工单与
 * 标题 logo / 对应作品修正, 一条一行 (新的在前, 行尾写状态). 焦点所在那条在说明栏写状态, 下面是会话气泡 (新的在前; 放不下的旧气泡不画).
 * 写反馈、补充、装测试包都在手机控制台, 组那一行画控制台的二维码. 进这一组就算看过了 (主页头像上的红点消失, 见 rememberTvFeedbackUnread).
 */
internal fun TvSettingsItemsBuilder.feedbackItems(showHeader: (TvSettingsValues) -> Boolean) {
    val records = TvFeedbackRecordsKey
    header(Lang.tv_settings_feedback_section, visible = showHeader)
    group(
        Lang.tv_settings_feedback, Lang.tv_settings_feedback_description,
        value = { v -> v[records]?.records?.let(::feedbackGroupValue) },
        qr = { consoleQr(it, "#settings/feedback") },
        onOpen = { markAllRead() },
    ) {
        action(
            Lang.tv_settings_feedback_empty, Lang.tv_settings_feedback_empty_description, keys = listOf(records),
            visible = { it[records]?.records.isNullOrEmpty() },
        ) { emptyList() }
        entries(
            keys = listOf(records),
            entries = { v -> v[records]?.records.orEmpty().sortedByDescending { it.submittedAt } },
            key = ::feedbackKey,
            title = ::feedbackTitle,
            value = { v, r -> feedbackRowValue(r, fresh = v[records]?.isFresh(r) == true) },
            description = { _, r -> feedbackHeader(r) },
            bubbles = { _, r -> feedbackBubbles(r) },
        ) { emptyList() }
    }
}

private fun markAllRead() {
    tvSettingsWriteScope.launch {
        val current = GlobalKoin.get<FeedbackRecordRepository>().current().items
        freshOnOpen.value = current.filter { it.unread }.map(::feedbackKey).toSet()
        GlobalKoin.get<FeedbackTickets>().markRead()
    }
}

/** 组那一行行尾: 有新回复写几条新的, 否则写几条处理中; 都没有不写. */
internal fun feedbackGroupValue(records: List<FeedbackRecord>): TvText? {
    val unread = records.count { it.unread }
    if (unread > 0) return tvText(Lang.tv_settings_feedback_unread_count, unread)
    val open = records.count { it.state == FeedbackState.OPEN }
    return if (open > 0) tvText(Lang.tv_settings_feedback_open_count, open) else null
}

internal fun feedbackTitle(r: FeedbackRecord): TvText = when (r.kind) {
    FeedbackKind.TICKET -> r.summary?.takeIf { it.isNotBlank() }?.let(::tvText) ?: tvText(Lang.tv_settings_feedback_kind_ticket)
    FeedbackKind.LOGO -> r.subjectName?.takeIf { it.isNotBlank() }?.let { tvText(Lang.tv_settings_feedback_title_logo, it) }
        ?: tvText(Lang.tv_settings_feedback_title_logo_id, r.subjectId ?: 0)

    FeedbackKind.ENTRY -> r.subjectName?.takeIf { it.isNotBlank() }?.let { tvText(Lang.tv_settings_feedback_title_entry, it) }
        ?: tvText(Lang.tv_settings_feedback_title_entry_id, r.subjectId ?: 0)
}

/** 行尾: 状态, 有没看过的回复或结果 ([fresh]) 时前面加「新回复」. */
internal fun feedbackRowValue(r: FeedbackRecord, fresh: Boolean): TvText {
    val state = feedbackStateText(r)
    return if (fresh) tvText(Lang.tv_settings_feedback_new, state) else state
}

/** 同控制台: 故障工单的采纳 / 没采纳叫「已解决 / 不处理」. */
internal fun feedbackStateText(r: FeedbackRecord): TvText = tvText(
    when (r.state) {
        FeedbackState.OPEN -> Lang.tv_settings_feedback_state_open
        FeedbackState.ACCEPTED -> if (r.kind == FeedbackKind.TICKET) Lang.tv_settings_feedback_state_resolved else Lang.tv_settings_feedback_state_accepted
        FeedbackState.REJECTED -> if (r.kind == FeedbackKind.TICKET) Lang.tv_settings_feedback_state_not_planned else Lang.tv_settings_feedback_state_rejected
        FeedbackState.CLOSED -> Lang.tv_settings_feedback_state_closed
    },
)

private fun feedbackKindText(kind: FeedbackKind): TvText = tvText(
    when (kind) {
        FeedbackKind.TICKET -> Lang.tv_settings_feedback_kind_ticket
        FeedbackKind.LOGO -> Lang.tv_settings_feedback_kind_logo
        FeedbackKind.ENTRY -> Lang.tv_settings_feedback_kind_entry
    },
)

/** 说明栏正文: 「类别 #编号 · 状态」, 跟着别人提的再加一句. */
internal fun feedbackHeader(r: FeedbackRecord): TvText {
    val header = tvText(Lang.tv_settings_feedback_header, feedbackKindText(r.kind), r.issue, feedbackStateText(r))
    return if (r.joinedExisting) TvText.Join(listOf(header, tvText(Lang.tv_settings_feedback_joined)), " · ") else header
}

/**
 * 会话气泡, 新的在前 (说明栏放不下时截掉的是最旧的; 全文在手机控制台看); 最后一个是提交时写的 (修正请求写它的标题).
 * 只留文字: 图片、网页标签去掉, 测试包链接换成一句「在手机控制台里装」, 附件不列 (只有附件的写一句).
 */
internal fun feedbackBubbles(r: FeedbackRecord, zone: ZoneId = ZoneId.systemDefault()): List<TvBubble> {
    val bubbles = ArrayList<TvBubble>()
    for (reply in r.replies.asReversed()) {
        bubbles += feedbackBubble(reply.fromMaintainer, feedbackTime(reply.at, zone), reply.body, reply.files.isNotEmpty(), r.apks)
    }
    bubbles += if (r.kind == FeedbackKind.TICKET) {
        feedbackBubble(false, feedbackTime(r.submittedAt, zone), r.description.orEmpty(), r.files.isNotEmpty(), emptyList())
    } else {
        TvBubble(tvText(Lang.tv_settings_feedback_from_me, feedbackTime(r.submittedAt, zone)), feedbackTitle(r), mine = true)
    }
    return bubbles
}

private fun feedbackBubble(fromMaintainer: Boolean, time: String, body: String, hasFiles: Boolean, apks: List<String>): TvBubble {
    val lines = ArrayList<TvText>()
    val text = feedbackPlainText(body, apks)
    if (text.isNotEmpty()) lines += tvText(text)
    val hasApk = apks.any { it in body }
    if (hasApk) lines += tvText(Lang.tv_settings_feedback_apk)
    if (text.isEmpty() && !hasApk && hasFiles) lines += tvText(Lang.tv_settings_feedback_files_only)
    return TvBubble(
        tvText(if (fromMaintainer) Lang.tv_settings_feedback_from_maintainer else Lang.tv_settings_feedback_from_me, time),
        lines.takeIf { it.isNotEmpty() }?.let { TvText.Join(it, "\n") },
        mine = !fromMaintainer,
    )
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

internal fun feedbackTime(millis: Long, zone: ZoneId): String = TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))

/** 工单服务给的 ISO 时刻; 认不出原样写. */
internal fun feedbackTime(iso: String, zone: ZoneId): String =
    runCatching { feedbackTime(Instant.parse(iso).toEpochMilli(), zone) }.getOrDefault(iso)

private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")
private val MD_IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
private val MD_LINK = Regex("""\[([^\]]*)]\(([^)\s]*)[^)]*\)""")
private val HTML_TAG = Regex("""</?[a-zA-Z][^>]*>""")
private val CODE_FENCE = Regex("""(?m)^\s*```.*$""")
private val LINE_MARK = Regex("""(?m)^[ \t]*(?:#{1,6}[ \t]+|>[ \t]?)""")
private val EMPHASIS = Regex("""\*\*|__|`""")
private val TRAILING_SPACE = Regex("""(?m)[ \t]+$""")
private val BLANK_LINES = Regex("""\n{3,}""")

/**
 * 回复的 Markdown 换成电视上能看的字: 去掉图片、注释与网页标签, 链接只留文字, 去掉粗体 / 代码 / 标题 / 引用的记号;
 * [drop] 里的链接 (测试包) 整个去掉.
 */
internal fun feedbackPlainText(markdown: String, drop: Collection<String> = emptyList()): String {
    var s = markdown.replace("\r\n", "\n")
    s = HTML_COMMENT.replace(s, "")
    s = MD_IMAGE.replace(s, "")
    s = MD_LINK.replace(s) { m -> if (m.groupValues[2] in drop) "" else m.groupValues[1] }
    for (url in drop) s = s.replace(url, "")
    s = HTML_TAG.replace(s, "")
    s = CODE_FENCE.replace(s, "")
    s = LINE_MARK.replace(s, "")
    s = EMPHASIS.replace(s, "")
    s = TRAILING_SPACE.replace(s, "")
    s = BLANK_LINES.replace(s, "\n\n")
    return s.trim()
}
