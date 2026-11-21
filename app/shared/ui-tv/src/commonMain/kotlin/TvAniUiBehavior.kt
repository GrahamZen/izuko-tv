/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.tv

import me.him188.ani.app.ui.foundation.AniUiBehavior

/**
 * 遥控器设备 (Android TV / Google TV) 的界面行为.
 *
 * 由 TV 应用入口传给 `AniApp`. 共享界面代码不认识它, 只读 `LocalAniUiBehavior` 里的开关.
 */
val TvAniUiBehavior = AniUiBehavior(
    focusDrivenNavigation = true,
    showBackNavigationButton = false,
    showDismissButtons = false,
    showNavigationTopAppBar = false,
    pinTopAppBar = true,
    sheetMaxWidthFraction = 0.9f,
    immersiveShell = true,
    supportsWindowedPlayback = false,
    crossfadeNavigation = true,
    blackRootBackground = true,
    panelsAsCenteredDialogs = true,
    autoInstallUpdates = true,
    forceDarkInPlayer = true,
    strongSelectionColors = true,
    retainPlaybackSession = true,
    supportsFileSharing = false,
)

/**
 * 平板模式 (触屏上跑电视界面): 二级页面要有带返回按钮的顶栏 (同上游的平板界面), 打开上游页面的顶栏与返回按钮;
 * 弹窗与面板带「关闭」「取消」按钮 (没有返回键, 不然只能点外面关).
 * 电视界面自己的二级页面 (详情页、新番时间表) 按 LocalTvTouchInputEnabled 自己画.
 * 播放页同上游平板: 视频外 (视频下面、右栏) 跟用户主题, 设置里「播放页始终使用深色」打开时才整页深色; 视频本身恒深色.
 */
fun AniUiBehavior.withTouchNavigation(): AniUiBehavior =
    copy(showBackNavigationButton = true, showNavigationTopAppBar = true, showDismissButtons = true, forceDarkInPlayer = false)
