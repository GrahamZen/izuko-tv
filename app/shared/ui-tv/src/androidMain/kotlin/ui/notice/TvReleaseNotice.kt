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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.Notices
import me.him188.ani.app.ui.update.ReleaseNoteItem
import me.him188.ani.app.ui.whatsnew.TvWhatsNew
import me.him188.ani.app.ui.whatsnew.TvWhatsNewSettingsPage
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

/**
 * 发版后在 release 里补的「## 通知」(见 [TvWhatsNew]): 当前版本的通知一句一条, 跟着「这一版有这几句通知」走 —— 换了版本就没了,
 * 内容改了 (补了一句、改了一句) 是新的一条. 时间是这个用户档第一次看到它的时刻. 去处是「本版新功能」页 (设置里打开的那一页,
 * 通知卡片在最上面, 列表里截掉的整句在那里看得全).
 */
internal object TvReleaseNotice : TvNoticeKind {
    override val id: String = "release.notice"

    override fun source(): Flow<List<Notice>> = TvWhatsNew.releaseNotice.map { release ->
        release?.items.orEmpty().map { item ->
            Notice(
                kind = id,
                key = keyOf(release!!.version, item),
                atMillis = Notices.UNKNOWN_TIME,
                args = buildMap {
                    put(ARG_TITLE, item.title)
                    item.detail?.let { put(ARG_DETAIL, it) }
                },
            )
        }
    }

    private fun keyOf(version: String, item: ReleaseNoteItem): String =
        "release:$version:" + (item.title + "\u0000" + item.detail.orEmpty()).hashCode()

    override suspend fun text(notice: Notice): String? {
        val title = notice.args[ARG_TITLE] ?: return null
        val detail = notice.args[ARG_DETAIL]
        return if (detail.isNullOrBlank()) title else "$title：$detail"
    }

    override fun destination(notice: Notice): TvNoticeDestination =
        TvNoticeDestination.Dialog { onDismiss -> TvWhatsNewSettingsPage(onDismiss) }

    /** 「本版新功能」页上已经显示了这几句通知: 记成看过, 之后不为它们弹通知. */
    suspend fun markShownOnPage(version: String, items: List<ReleaseNoteItem>) {
        if (items.isEmpty()) return
        val notices = runCatching { KoinPlatform.getKoin().get<Notices>() }.getOrNull() ?: return
        val keys = items.map { keyOf(version, it) }.toSet()
        val entries = withTimeoutOrNull(MARK_WAIT) {
            notices.all.first { list -> keys.all { key -> list.any { it.notice.key == key } } }
        } ?: return
        notices.markSeen(entries.filter { it.notice.key in keys }.map { it.notice })
    }

    private const val ARG_TITLE = "title"
    private const val ARG_DETAIL = "detail"

    /** 等这几句出现在列表里 (来源接上、记下第一次看到的时刻) 最多这么久. */
    private val MARK_WAIT = 10.seconds
}
