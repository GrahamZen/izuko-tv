/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import me.him188.ani.app.domain.episode.EpisodeLink
import me.him188.ani.app.navigation.SettingsTab

/**
 * 发版后补的通知 (加密的应用内通知, 见 TvReleaseNotice) 里一条的写法, 一行一条, 支持:
 * - 「标题：说明」(冒号前面 [TITLE_MAX_LENGTH] 字以内时拆开, 同 release 说明);
 * - 说明里 `\n` 分段;
 * - `**粗体**`;
 * - 链接 `[文字](网址)`: 应用内地址是操作 (见 [parseNoticeLinkTarget]), https 地址电视上画成二维码; 其他的 (http、写错的) 只留文字.
 *   自己占一段的链接不再写成文字, 只出按钮或二维码.
 * 其他记号 (行内代码) 去掉. 标题里不分段, 链接只留文字.
 */
internal class TvNoticeText(
    val title: String,
    /** 说明的各段, 粗体已标出; 没有说明时为空 */
    val paragraphs: List<AnnotatedString>,
    /** 认得的链接, 照出现的先后 */
    val links: List<TvNoticeLink>,
) {
    /** 说明合成一句的纯文字 (通知列表、气泡里用); 没有说明时为 null */
    val plainDetail: String? get() = paragraphs.joinToString(" ") { it.text }.ifEmpty { null }
}

/** 通知里一个认得的链接: 按钮或二维码上的字 [label] 与去哪 [target] */
internal class TvNoticeLink(val label: String, val target: TvNoticeLinkTarget)

/** 通知里的链接去哪 */
internal sealed interface TvNoticeLinkTarget {
    /** 外部网页 (https): 电视上画二维码, 触屏上开浏览器 */
    data class Web(val url: String) : TvNoticeLinkTarget

    /** 条目详情 */
    data class Subject(val subjectId: Int) : TvNoticeLinkTarget

    /** 设置的某一类, [row] 是落在哪一行 (清单里的锚点, 见 TvSettingItem.anchor) */
    data class Settings(val tab: SettingsTab, val row: String?) : TvNoticeLinkTarget

    /** 本版新功能 */
    data object WhatsNew : TvNoticeLinkTarget
}

/**
 * 应用内地址 (`ani://`):
 * - `ani://subjects/条目id`: 条目详情;
 * - `ani://settings/分类[/行]`: 设置的一类 (分类名同 [SettingsTab], 账号写 `account`), 落在锚点为「行」的那一行 (如 `ani://settings/about/chat-groups`);
 * - `ani://whatsnew`: 本版新功能.
 * 网址只认 https. 不认识的为 null.
 */
internal fun parseNoticeLinkTarget(url: String): TvNoticeLinkTarget? {
    if (url.startsWith("https://")) return TvNoticeLinkTarget.Web(url)
    if (url == "ani://whatsnew") return TvNoticeLinkTarget.WhatsNew
    SETTINGS_LINK.matchEntire(url)?.let { match ->
        val (tab, row) = match.destructured
        val category = settingsTabOf(tab) ?: return null
        return TvNoticeLinkTarget.Settings(category, row.ifEmpty { null })
    }
    // 带集数的 (ep=)、单集的 (ani://episodes) 要查数据才能跳, 这里不认
    val subject = EpisodeLink.parse(url) as? EpisodeLink.Subject ?: return null
    return if (subject.ep == null) TvNoticeLinkTarget.Subject(subject.subjectId) else null
}

/** 一条通知的原文 [line] 拆成标题、说明的各段与链接. */
internal fun parseNoticeText(line: String): TvNoticeText {
    val links = mutableListOf<TvNoticeLink>()
    // 链接先换成占位字 (私用区, 一个链接一个字), 拆标题、分段时不碰到网址里的冒号与括号
    val labels = mutableListOf<String>()
    // 这个占位字是不是认得的链接 (下标同 labels)
    val known = mutableListOf<Boolean>()
    val text = LINK.replace(line.trim()) { match ->
        val (label, url) = match.destructured
        val target = parseNoticeLinkTarget(url.trim())
        labels += label
        known += target != null
        target?.let { links += TvNoticeLink(label.trim(), it) }
        (PLACEHOLDER_BASE + labels.lastIndex).toString()
    }
    fun expand(s: String): String = buildString {
        for (c in s) {
            val index = c - PLACEHOLDER_BASE
            if (index in labels.indices) append(labels[index]) else append(c)
        }
    }.replace(CODE) { it.groupValues[1] }

    fun annotated(s: String): AnnotatedString = buildAnnotatedString {
        var position = 0
        for (match in BOLD.findAll(s)) {
            append(expand(s.substring(position, match.range.first)))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(expand(match.groupValues[1].ifEmpty { match.groupValues[2] }))
            }
            position = match.range.last + 1
        }
        append(expand(s.substring(position)))
    }

    val colon = text.indexOfFirst { it == '：' || it == ':' }
    val splits = colon in 2..TITLE_MAX_LENGTH && (text[colon] == '：' || text.getOrNull(colon + 1) == ' ')
    val titlePart = if (splits) text.substring(0, colon) else text
    val detailPart = if (splits) text.substring(colon + 1) else ""
    val paragraphs = detailPart.split("\\n")
        .map { it.trim() }
        // 自己占一段的链接 (认得的) 只出按钮或二维码, 不写成文字
        .filterNot { it.length == 1 && known.getOrElse(it[0] - PLACEHOLDER_BASE) { false } }
        .filter { it.isNotEmpty() }
        .map(::annotated)
    return TvNoticeText(
        title = annotated(titlePart.trim()).text,
        paragraphs = paragraphs,
        links = links,
    )
}

private fun settingsTabOf(name: String): SettingsTab? =
    if (name == "account") SettingsTab.PROFILE else SettingsTab.entries.firstOrNull { it.name == name.uppercase().replace('-', '_') }

private val SETTINGS_LINK = Regex("""ani://settings/([a-z_-]+)(?:/([a-z0-9-]+))?/?""")
private val LINK = Regex("""\[([^]]+)]\(([^)]+)\)""")
private val BOLD = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
private val CODE = Regex("""`([^`]+)`""")

/** 冒号前面最多这么多字才当成标题拆开, 同 ReleaseHighlights */
private const val TITLE_MAX_LENGTH = 30

/** 私用区里的一段: 占位字从这里起, 一个链接一个 (一条通知不会有几百个链接) */
private const val PLACEHOLDER_BASE = ''
