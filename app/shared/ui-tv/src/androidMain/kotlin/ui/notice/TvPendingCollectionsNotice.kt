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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.Notices
import me.him188.ani.app.domain.profile.BangumiProfileConversion
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_action_open
import me.him188.ani.app.ui.lang.tv_notice_pending_collections
import me.him188.ani.app.ui.lang.tv_notice_pending_collections_body
import me.him188.ani.app.ui.lang.tv_notice_pending_collections_count
import me.him188.ani.app.ui.profile.TvPendingCollectionsDialog
import org.koin.mp.KoinPlatform

/**
 * 本地用户改成 Bangumi 用户、登录以后留下的本地收藏 (见 [BangumiProfileConversion]): 有就一条「有 N 部本地收藏待处理」,
 * 合并或删掉了 (电视上、手机控制台上) 就消失. 还没登录时合并不了, 不列 (收藏页、账号页上另有一句提示).
 * 去处是合并 / 删除的弹窗; 控制台上的「用户」页也会问.
 */
internal object TvPendingCollectionsNotice : TvNoticeKind {
    override val id: String = "profile.pending-collections"
    override val category: TvNoticeCategory = TvNoticeCategory.Collection

    override fun source(): Flow<List<Notice>> {
        if (UserProfiles.current.isLocal) return flowOf(emptyList())
        val koin = KoinPlatform.getKoin()
        val conversion = koin.getOrNull<BangumiProfileConversion>() ?: return flowOf(emptyList())
        return combine(conversion.pendingCollections, koin.get<SessionStateProvider>().stateFlow) { pending, session ->
            if (session !is SessionState.Valid || pending.isEmpty()) emptyList()
            // 什么时候开始等着的不知道: 用第一次看到的时刻
            else listOf(Notice(id, id, Notices.UNKNOWN_TIME, mapOf(ARG_COUNT to pending.size.toString())))
        }
    }

    override suspend fun title(notice: Notice): String = getStringOffMain(Lang.tv_notice_pending_collections)

    override suspend fun summary(notice: Notice): String = getStringOffMain(Lang.tv_notice_pending_collections_count, countOf(notice))

    override fun destination(notice: Notice): TvNoticeDestination =
        TvNoticeDestination.Dialog { onDismiss -> TvPendingCollectionsDialog(onDismiss) }

    override suspend fun detail(notice: Notice): TvNoticeDetail = TvNoticeDetail(
        body = listOf(TvNoticeParagraph(getStringOffMain(Lang.tv_notice_pending_collections_body, countOf(notice)))),
        // 只是打开一个列表看看, 横幅上短按直接开
        actions = listOf(getStringOffMain(Lang.tv_notice_action_open).let { open -> TvNoticeAction(open, destination(notice), quick = open) }),
    )

    private fun countOf(notice: Notice): Int = notice.args[ARG_COUNT]?.toIntOrNull() ?: 0

    private const val ARG_COUNT = "count"
}
