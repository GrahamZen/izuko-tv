/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount

/**
 * M3 [AlertDialog] 的统一入口, 参数与它一一对应. 焦点导航之外就是 [AlertDialog] 本身 (容器色 [aniDialogContainerColor]
 * 在手机 / 桌面上即 M3 默认).
 *
 * 焦点导航 (TV 与平板触屏) 上与 [AniCenteredPanelDialog] 同一套「轻透玻璃」(见 `AniGlass.kt`), 底色与形状固定, 不看
 * [shape] / [containerColor]:
 * - 玻璃底色 [aniGlassPanelColor] + 细描边 [aniGlassEdge] + [ANI_GLASS_PANEL_SHAPE];
 * - 窗外压暗 [CENTERED_PANEL_WINDOW_DIM] —— 系统默认 0.6, 盖在播放画面上会把四周压成一片黑;
 * - 标题 [aniPanelTitleStyle];
 * - 内容标为在玻璃面板里 ([ProvideAniGlassPanelContent]): 里面的项目换玻璃底, 对话框按钮 ([AniTextButton] 那一组) 画成弹窗动作按钮.
 */
@Composable
fun AniAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = aniDialogContainerColor(),
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    if (!LocalAniUiBehavior.current.focusDrivenNavigation) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties,
        )
        return
    }
    val panelColor = aniGlassPanelColor()
    ProvideAniGlassPanelContent {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                // 压暗只能在对话框自己的窗口里调 (见 DialogWindowDimAmount); confirmButton 是唯一必有的插槽
                DialogWindowDimAmount(CENTERED_PANEL_WINDOW_DIM)
                confirmButton()
            },
            // modifier 落在包住对话框面板、与它一样大的那层上: 底色画在面板后面, 描边叠在上面
            modifier = modifier
                .drawBehind {
                    drawOutline(ANI_GLASS_PANEL_SHAPE.createOutline(size, layoutDirection, this), panelColor)
                }
                .border(aniGlassEdge(), ANI_GLASS_PANEL_SHAPE),
            dismissButton = dismissButton,
            icon = icon,
            title = title?.let { content ->
                { ProvideTextStyle(aniPanelTitleStyle) { content() } }
            },
            // 内容里会滚的列表挂 aniVerticalScrollFade 上下渐隐, 遥控器滚焦点时避开渐隐那一截
            text = text?.let { content ->
                { ProvideScrollFadeFocusMargin { content() } }
            },
            shape = ANI_GLASS_PANEL_SHAPE,
            containerColor = Color.Transparent,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties,
        )
    }
}
