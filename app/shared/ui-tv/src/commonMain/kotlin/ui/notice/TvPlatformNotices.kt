/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

/**
 * 只有安卓有的几种通知, 在种类表 ([TvNotices]) 里排在反馈回复与新版本之间:
 * 待处理的本地收藏 (弹窗只在安卓上)、发版后补的通知 (跟着本版新功能页, 只在安卓上). iPad 上没有.
 */
internal expect val tvPlatformNoticeKinds: List<TvNoticeKind>

/** 只有安卓有的、盖在首页上的层 (本版新功能页) 开着: 这时不弹通知气泡 (见 [TvNoticeHost]) */
internal expect fun tvPlatformLayerCovering(): Boolean
