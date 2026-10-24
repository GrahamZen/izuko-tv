/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.him188.ani.app.ui.foundation.LocalTvPlayPauseHandler

/**
 * 「轻透玻璃」: 居中面板弹窗 ([AniCenteredPanelDialog]) 与里面按钮 ([AniFocusActionButton]) 的样子, 照 tvOS 的弹窗 (tvOS 18 设计模板的
 * Alert, 1080p 下 1pt = 0.5dp): 接近实底的中性底色 ([aniGlassPanelColor]) + 一圈白色细描边 + 24dp 圆角; 面板外面只压暗
 * ([CENTERED_PANEL_WINDOW_DIM]). 内容区是平的底色, 读字不受干扰.
 * 各端都不做 tvOS 那样的面板底下毛玻璃: 安卓上只模糊面板底下要把弹窗窗口收成面板大小, 窗口一收系统就挪它的位置, 弹窗出来时
 * 会从屏幕左边飞到正中; iOS 上要另叠一层 UIKit 的毛玻璃视图跟着面板对位置.
 */
val ANI_GLASS_PANEL_CORNER: Dp = 24.dp

/** [ANI_GLASS_PANEL_CORNER] 的形状. */
val ANI_GLASS_PANEL_SHAPE: Shape = RoundedCornerShape(ANI_GLASS_PANEL_CORNER)

/**
 * 玻璃面板的底色, 不取主题色 (种子色调出来的面板色偏紫). 面板底下不模糊 (见上), 透多了后面的字会直接透上来, 所以接近实底,
 * 颜色取 tvOS 中档材质 (深色 #1E1E1E、浅色 #FEFDFF 的一半透明) 在常见底上合出来的那个: 浅色是一半 #FEFDFF 叠在压暗 40% 的浅色底
 * (#F2F2F7) 上 ≈ #C8C7CA 的浅灰 —— 直接用 #FEFDFF 是一块白板, 比 tvOS 亮得多; 深色同理 ≈ #19191A. 盖在播放器上时同 [centeredPanelColor] 那一档.
 */
@Composable
fun aniGlassPanelColor(): Color {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return (if (light) GLASS_PANEL_LIGHT else GLASS_PANEL_DARK).copy(
        alpha = if (LocalTvPlayPauseHandler.current != null) GLASS_PANEL_ALPHA_OVER_PLAYER else GLASS_PANEL_ALPHA,
    )
}

/** 玻璃面板的那圈细描边: 照 tvOS 弹窗, 白色一圈画在面板里侧, 宽 1pt (0.5dp); 深色白 10%, 浅色白 16%. */
@Composable
fun aniGlassEdge(): BorderStroke {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return BorderStroke(0.5.dp, Color.White.copy(alpha = if (light) 0.16f else 0.1f))
}

/**
 * 内容是否画在玻璃面板里 (由玻璃面板提供: [AniCenteredPanelDialog] 与各自绘的玻璃弹窗). 面板里的项目 (卡片、胶囊、选项格) 据此
 * 把常态底色换成 [aniGlassItemColor], 不再是一块块实心灰; 页面上的同一组件照旧.
 */
val LocalAniGlassPanel = staticCompositionLocalOf { false }

/**
 * 玻璃面板里项目的常态底, 照 tvOS 弹窗里的选项行: 浅色面板上压暗一点 (tvOS 是「变暗」混合的 #F0F0F0, 约减 15),
 * 深色面板上提亮一点 (「变亮」混合的 #0F0F0F, 约加 15) —— 只为圈出这一项的范围, 不加高光边.
 */
@Composable
fun aniGlassItemColor(): Color = glassOverlay(onLightPanel = 0.08f, onDarkPanel = 0.07f, onPhotoInLightTheme = 0.45f)

/** 玻璃面板的内容: 标上 [LocalAniGlassPanel] (里面的项目换成玻璃底), 并照弹窗约定画按钮 ([ProvidePopupControlStyle]). */
@Composable
fun ProvideAniGlassPanelContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAniGlassPanel provides true) {
        ProvidePopupControlStyle(content)
    }
}

/** 只标上 [LocalAniGlassPanel], 不动按钮约定: 自绘的玻璃弹窗里只有部分区域套 [ProvidePopupControlStyle] 时用. */
@Composable
fun ProvideAniGlassPanel(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAniGlassPanel provides true, content = content)
}

/** 项目的常态底色: 在玻璃面板里 ([LocalAniGlassPanel]) 是 [aniGlassItemColor], 否则是 [fallback] (组件原来的那一档). */
@Composable
fun aniPanelItemColor(fallback: Color): Color = if (LocalAniGlassPanel.current) aniGlassItemColor() else fallback

/**
 * 面板弹窗标题的字: 照 tvOS 面板 (Sheet) 的标题, Bold 38pt (19sp, 行高 23); 所有面板弹窗的标题统一用它 ([AniCenteredPanelDialog] 的 title 默认就是);
 * 标题下面的一行说明用 [aniPanelSubtitleStyle].
 */
val aniPanelTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)

/** 面板标题下面那一行说明 (如所属条目名): tvOS 的 Caption1 (25pt, 12.5sp), 颜色淡一档由调用方给 (onSurfaceVariant). */
val aniPanelSubtitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp)

/**
 * 锚在按钮上弹出的菜单 (Popup, 如详情页的选集网格、标签) 的玻璃面板: 照 tvOS 的上下文菜单, 圆角 30pt ([ANI_GLASS_MENU_SHAPE]);
 * 底色、描边、内容约定同玻璃弹窗.
 */
@Composable
fun AniGlassPopupSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier,
        shape = ANI_GLASS_MENU_SHAPE,
        color = aniGlassPanelColor(),
        // 半透明底在配色表里查不到 "on" 色, 必须显式给
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = aniGlassEdge(),
    ) {
        ProvideAniGlassPanelContent(content)
    }
}

/**
 * 玻璃按钮 (没聚焦时) 的底, 照 tvOS 弹窗的按钮: 浅色面板上比面板暗一档 (「变暗」混合的 #E5E5EA, 约减 26),
 * 深色面板上亮一档 (「变亮」混合的 #2C2C2E, 约加 44).
 */
@Composable
fun aniGlassButtonColor(): Color = glassOverlay(onLightPanel = 0.14f, onDarkPanel = 0.2f, onPhotoInLightTheme = 0.55f)

/**
 * 叠在玻璃面板上的一层: 浅色面板上是黑 [onLightPanel] (压暗), 深色面板上是白 [onDarkPanel] (提亮) —— tvOS 弹窗里的按钮与选项
 * 都是这样跟着面板深浅反着来. 浅色主题下铺剧照的面板 (压了暗、内容色是白) 上用白 [onPhotoInLightTheme]: 那里的按钮字仍是
 * onSurface 的深色, 要靠一层够亮的淡白托住.
 */
@Composable
private fun glassOverlay(onLightPanel: Float, onDarkPanel: Float, onPhotoInLightTheme: Float): Color {
    val lightTheme = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return when {
        !lightTheme -> Color.White.copy(alpha = onDarkPanel)
        LocalContentColor.current.luminance() > 0.5f -> Color.White.copy(alpha = onPhotoInLightTheme)
        else -> Color.Black.copy(alpha = onLightPanel)
    }
}

/** 锚在按钮上弹出的菜单的形状 (tvOS 上下文菜单的圆角 30pt), 见 [AniGlassPopupSurface]. */
val ANI_GLASS_MENU_SHAPE: Shape = RoundedCornerShape(15.dp)

private val GLASS_PANEL_DARK = Color(0xFF19191A)
private val GLASS_PANEL_LIGHT = Color(0xFFC8C7CA)
private const val GLASS_PANEL_ALPHA_OVER_PLAYER = 0.85f
private const val GLASS_PANEL_ALPHA = 0.95f
