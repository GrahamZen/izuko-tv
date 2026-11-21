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

/**
 * 只有安卓有的几种通知, 在种类表 ([TvNotices]) 里排在反馈回复与新版本之间:
 * 待处理的本地收藏 (弹窗只在安卓上)、发版后补的通知 (跟着本版新功能页, 只在安卓上). iPad 上没有.
 */
internal expect val tvPlatformNoticeKinds: List<TvNoticeKind>

/** 只有安卓有的、盖在首页上的层 (本版新功能页) 开着: 这时不弹通知气泡 (见 [TvNoticeHost]) */
internal expect fun tvPlatformLayerCovering(): Boolean

/**
 * 通知弹窗的内容 (见 [TvNoticeDialog]): 安卓 (电视与平板) 上是原生视图 (TvNoticeNativeBody), iOS 上是 Compose 画法 ([TvNoticeDialogBody]).
 * 两边照同一份数据画.
 */
@Composable
internal expect fun TvNoticePlatformBody(
    items: List<TvShownNotice>,
    state: TvNoticeDialogState,
    tabs: List<TvNoticeTab>,
    onAction: (index: Int, item: TvNoticeColumnItem, action: TvNoticeAction) -> Unit,
    onSeen: (key: String) -> Unit,
    onSelectTab: (id: String, intoList: Boolean, focusKey: String?) -> Unit,
    onDismiss: () -> Unit,
    refreshUi: @Composable () -> TvNoticeRefreshUi,
    onRefresh: () -> Unit,
)

/** 在首页先把通知弹窗的原生视图排一遍, 第一次打开不卡 (安卓, 见 PrewarmTvNoticeView); iOS 上没有原生视图, 不做. */
@Composable
internal expect fun PrewarmTvNoticePlatform(canPrewarmNow: () -> Boolean)

/** 这台设备有 Web 控制台: 没有的 (iOS) 不画控制台的码 ([TvNoticeQr.console]). */
internal expect val tvNoticeHasConsole: Boolean
