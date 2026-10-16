/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.domain.usecase.GlobalKoin

/**
 * 有没有还没看过的反馈回复或处理结果 (后台查到的, 见 FeedbackTickets): 主页侧栏头像上的红点. 点头像进账号页「我的反馈」,
 * 焦点在那条上停一会儿 (或在手机控制台打开「反馈」) 就清掉.
 */
@Composable
internal fun rememberTvFeedbackUnread(): Boolean {
    val unread = remember {
        // 没起 Koin 的界面测试里没有这份记录
        runCatching { GlobalKoin.get<FeedbackRecordRepository>() }.getOrNull()?.let(::anyUnread) ?: flowOf(false)
    }
    return unread.collectAsState(false).value
}

private fun anyUnread(repository: FeedbackRecordRepository): Flow<Boolean> =
    repository.records.map { records -> records.any { it.unread } }.distinctUntilChanged()
