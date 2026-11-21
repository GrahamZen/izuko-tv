/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.runtime.Composable

/**
 * 平板播放页的系统栏: 全屏时主屏指示条藏起来, 状态栏跟着画面上的控件走 (控件唤出时显示, 收起时藏, 同上游播放器);
 * 分栏时都在. 离开播放页或缩成小窗时恢复.
 *
 * 只 iPad 上做; 安卓平板上系统栏由沉浸式外壳管 (状态栏一直藏着).
 *
 * @param controlsShown 画面上的控件此刻显不显示 (手势锁住时算不显示)
 */
@Composable
internal expect fun TabletSystemBarsEffect(fullScreen: Boolean, controlsShown: () -> Boolean)
