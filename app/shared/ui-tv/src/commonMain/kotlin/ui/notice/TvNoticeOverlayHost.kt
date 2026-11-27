/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope

/**
 * 一条通知的操作开的弹窗 ([TvNoticeDestination.Dialog]) 里要交给 [TvNoticeHost] 做的事:
 * - [openPage]: 弹窗里再跳一个页面, 同操作本身是页面的 —— 弹窗与通知弹窗收起, 在那页按返回回到打开通知时的那一页时
 *   通知弹窗重新打开、停在这一条; 弹窗是横幅上短按直接开的, 只是关掉弹窗跳过去;
 * - [scope]: 比弹窗活得久的协程 (按下去弹窗当场关、写入还要做完的, 如标记看过).
 */
internal class TvNoticeOverlayHost(
    val scope: CoroutineScope,
    val openPage: (TvNoticeDestination.Page) -> Unit,
)

/** 通知的操作开的弹窗里拿到的 [TvNoticeOverlayHost]; 不在通知宿主里开的为 null. */
internal val LocalTvNoticeOverlayHost = staticCompositionLocalOf<TvNoticeOverlayHost?> { null }
