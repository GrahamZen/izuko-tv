/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.navigation.AniNavigator

/**
 * 一种通知: 那句话与去处 (见 docs/contributing/code/notices.md). 一个种类一个文件, 在 [TvNotices] 的种类表里登记一行.
 *
 * 发通知的一方只碰数据层: 消息在事件发生处 `Notices.post`, 跟着状态的由这里的 [source] 给出.
 */
interface TvNoticeKind {
    /** 种类 id, 同 [Notice.kind]. */
    val id: String

    /** 跟着状态的来源 (启动时接到 `Notices` 上, 一个进程一次); 只发消息的种类为 null. */
    fun source(): Flow<List<Notice>>? = null

    /** 那句话; 切换语言后照样对. null = 去处已经不在了, 这条不列. */
    suspend fun text(notice: Notice): String?

    /** 电视上的去处; null = 没有 (只是告知). */
    fun destination(notice: Notice): TvNoticeDestination?

    /** 控制台上的去处 (控制台页面的地址, 如 `#settings/feedback`); null = 控制台上只能「在电视上打开」. */
    fun consoleHref(notice: Notice): String? = null
}

/** 按确定后去哪. */
sealed interface TvNoticeDestination {
    /** 跳到一个页面; 在那页按返回回到首页时通知弹窗重新打开. */
    class Page(val navigate: (AniNavigator) -> Unit) : TvNoticeDestination

    /** 在通知弹窗上叠一个弹窗, 关掉回到列表. */
    class Dialog(val content: @Composable (onDismiss: () -> Unit) -> Unit) : TvNoticeDestination
}
