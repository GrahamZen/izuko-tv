/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogram
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeRowSpec

/** 选集轮播的卡片行 (见 FocusEpisodeCarousel 的 rowContent): 安卓是原生行; null = 用轮播自带的 Compose 行. */
internal expect val tvEpisodeRowContent: (@Composable (FocusEpisodeRowSpec) -> Unit)?

/**
 * 详情页演职人员的圆头像横滑行 (标题另画): [items] 为 null = 数据还没到 (一排占位格), 列表里的 null 是分页还没到的那一格.
 * 行为见安卓的原生行 (TvNativeMonogramStrip): 左右两头吞掉, 上下键交给页面; [restoreFocus] = 返回本页时进页落点回这一行.
 */
@Composable
internal expect fun TvDetailsPeopleStrip(
    items: List<TvNativeMonogram?>?,
    onClick: (index: Int) -> Unit,
    onLongPress: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    horizontalPadding: Dp,
    restoreFocus: Boolean,
    modifier: Modifier = Modifier,
)
