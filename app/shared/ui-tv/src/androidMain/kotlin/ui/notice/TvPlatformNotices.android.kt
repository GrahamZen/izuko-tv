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
import me.him188.ani.app.ui.whatsnew.TvWhatsNew

internal actual val tvPlatformNoticeKinds: List<TvNoticeKind>
    get() = listOf(TvPendingCollectionsNotice, TvReleaseNotice)

internal actual fun tvPlatformLayerCovering(): Boolean = TvWhatsNew.launchContent.value != null

@Composable
internal actual fun TvNoticePlatformBody(
    items: List<TvShownNotice>,
    state: TvNoticeDialogState,
    tabs: List<TvNoticeTab>,
    onAction: (index: Int, item: TvNoticeColumnItem, action: TvNoticeAction) -> Unit,
    onRead: (key: String) -> Unit,
    onSelectTab: (id: String, intoList: Boolean, focusKey: String?) -> Unit,
    onDismiss: () -> Unit,
    refreshUi: @Composable () -> TvNoticeRefreshUi,
    onRefresh: () -> Unit,
) {
    TvNoticeNativeBody(items, state, tabs, onAction, onRead, onSelectTab, refreshUi = refreshUi, onRefresh = onRefresh)
}

@Composable
internal actual fun PrewarmTvNoticePlatform(canPrewarmNow: () -> Boolean) {
    PrewarmTvNoticeView(canPrewarmNow)
}

internal actual val tvNoticeHasConsole: Boolean get() = true
