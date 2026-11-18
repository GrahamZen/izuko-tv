/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.Notices
import me.him188.ani.app.ui.settings.tv.TvSettingsEntryGroup
import me.him188.ani.app.ui.whatsnew.TvWhatsNew
import me.him188.ani.app.ui.whatsnew.TvWhatsNewSettingsPage

/**
 * 发版后补的通知 (加密的应用内通知里给当前版本的几句, 见 [TvWhatsNew.releaseNotice]): 一句一条, 跟着「这一版有这几句通知」走 ——
 * 换了版本就没了, 内容改了 (补了一句、改了一句) 是新的一条. 时间是这个用户档第一次看到它的时刻.
 * 只在通知里显示 (「本版新功能」页不放). 写法见 [parseNoticeText]: 冒号前半句是标题, 后半句是列表里的摘要与右栏的正文 (列表里截掉的整句在右栏看得全);
 * 应用内链接是操作, https 链接画成二维码. 一个链接都没有的只是告知.
 */
internal object TvReleaseNotice : TvNoticeKind {
    override val id: String = "release.notice"
    override val category: TvNoticeCategory = TvNoticeCategory.Announcement

    override fun source(): Flow<List<Notice>> = TvWhatsNew.releaseNotice.map { release ->
        release?.lines.orEmpty().map { line ->
            Notice(
                kind = id,
                key = keyOf(release!!.version, line),
                atMillis = Notices.UNKNOWN_TIME,
                args = mapOf(ARG_LINE to line),
            )
        }
    }

    private fun keyOf(version: String, line: String): String = "release:$version:" + line.hashCode()

    override suspend fun title(notice: Notice): String? = parse(notice)?.title

    override suspend fun summary(notice: Notice): String? = parse(notice)?.plainDetail

    override fun destination(notice: Notice): TvNoticeDestination? = null

    override suspend fun detail(notice: Notice): TvNoticeDetail? {
        val parsed = parse(notice) ?: return null
        return TvNoticeDetail(
            body = parsed.paragraphs.map { TvNoticeParagraph(it) },
            actions = parsed.links.mapNotNull { link -> destinationOf(link.target)?.let { TvNoticeAction(link.label, it) } }.take(MAX_ACTIONS),
            qr = parsed.links.firstNotNullOfOrNull { link -> (link.target as? TvNoticeLinkTarget.Web)?.let { TvNoticeQr(it.url, link.label) } },
        )
    }

    private fun parse(notice: Notice): TvNoticeText? = notice.args[ARG_LINE]?.let(::parseNoticeText)

    /** 应用内的去处 (外部网页是二维码, 不是操作) */
    private fun destinationOf(target: TvNoticeLinkTarget): TvNoticeDestination? = when (target) {
        is TvNoticeLinkTarget.Web -> null
        is TvNoticeLinkTarget.Subject -> TvNoticeDestination.Page { navigator -> navigator.navigateSubjectDetails(target.subjectId, placeholder = null) }
        is TvNoticeLinkTarget.Settings -> TvNoticeDestination.Page { navigator ->
            target.row?.let(TvSettingsEntryGroup::focusRow)
            navigator.navigateSettings(target.tab)
        }

        TvNoticeLinkTarget.WhatsNew -> TvNoticeDestination.Dialog { onDismiss -> TvWhatsNewSettingsPage(onDismiss) }
    }

    private const val ARG_LINE = "line"

    /** 一条通知里最多几颗操作按钮 */
    private const val MAX_ACTIONS = 3
}
