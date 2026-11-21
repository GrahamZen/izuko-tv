/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.widgets.aniFocusContainerColor
import me.him188.ani.app.ui.foundation.widgets.aniFocusContentColor
import me.him188.ani.app.ui.foundation.widgets.aniPanelItemColor

/**
 * 弹窗面板里原生视图共用的配色 (ARGB) 与触屏, 由 [rememberTvNativePanelStyle] 按主题取; 面板自己的样式带着它, 尺寸与字另记.
 * - 字: 正文色 [onSurface]、淡一档 [onSurfaceVariant]、主色 [primary]、出错的红 [error]、描边两档 [outline] / [outlineVariant];
 * - 示焦: 聚焦的实底 [focusBg] 与字 [focusFg] ([touch] 时不画聚焦); 行与按钮平时的淡底 [itemBg] (随面板: 玻璃面板是玻璃的格子色);
 *   选中 (右栏说的是它) 的底 [selectedBg] 与字 [selectedFg]; 触屏按下时罩的一层 [pressedOverlay];
 * - 二维码的底与码 [qr]、进度条的轨道 [progressTrack].
 */
@Immutable
internal data class TvNativePanelStyle(
    val touch: Boolean,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val error: Int,
    val outline: Int,
    val outlineVariant: Int,
    val focusBg: Int,
    val focusFg: Int,
    val itemBg: Int,
    val selectedBg: Int,
    val selectedFg: Int,
    val pressedOverlay: Int,
    val qr: TvNativeQrColors,
    val progressTrack: Int,
)

/** 面板里的配色与触屏 (见 [TvNativePanelStyle]). 要在面板里取: 行与按钮平时的淡底随面板. */
@Composable
internal fun rememberTvNativePanelStyle(): TvNativePanelStyle {
    val colors = MaterialTheme.colorScheme
    val touch = LocalTvTouchInputEnabled.current
    val focusBg = aniFocusContainerColor()
    val focusFg = aniFocusContentColor()
    val itemBg = aniPanelItemColor(colors.surfaceContainerHighest)
    val track = ProgressIndicatorDefaults.linearTrackColor
    return remember(colors, touch, focusBg, focusFg, itemBg, track) {
        TvNativePanelStyle(
            touch = touch,
            onSurface = colors.onSurface.toArgb(),
            onSurfaceVariant = colors.onSurfaceVariant.toArgb(),
            primary = colors.primary.toArgb(),
            error = colors.error.toArgb(),
            outline = colors.outline.toArgb(),
            outlineVariant = colors.outlineVariant.toArgb(),
            focusBg = focusBg.toArgb(),
            focusFg = focusFg.toArgb(),
            itemBg = itemBg.toArgb(),
            selectedBg = colors.secondaryContainer.toArgb(),
            selectedFg = colors.onSecondaryContainer.toArgb(),
            pressedOverlay = colors.onSurface.copy(alpha = TV_NATIVE_PANEL_PRESSED_ALPHA).toArgb(),
            qr = tvNativeQrColors(colors),
            progressTrack = track.toArgb(),
        )
    }
}

/** 触屏上按住行与按钮时罩的一层 (相对正文色). */
private const val TV_NATIVE_PANEL_PRESSED_ALPHA = 0.10f
