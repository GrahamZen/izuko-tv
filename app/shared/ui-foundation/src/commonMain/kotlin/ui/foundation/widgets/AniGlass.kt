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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalTvPlayPauseHandler

/**
 * 「轻透玻璃」: 居中面板弹窗 ([AniCenteredPanelDialog]) 与里面按钮 ([AniFocusActionButton]) 的样子 ——
 * 背后整屏模糊 (设备支持时, 见 `dialogWindowBlurBehind`) + 轻透的底色 + 上亮下暗的一圈细高光边 + 更圆的角.
 * 液态感只放在面板边缘与按钮上, 内容区还是平的底色, 读字不受干扰.
 */
val ANI_GLASS_PANEL_CORNER: Dp = 24.dp

/** [ANI_GLASS_PANEL_CORNER] 的形状. */
val ANI_GLASS_PANEL_SHAPE: Shape = RoundedCornerShape(ANI_GLASS_PANEL_CORNER)

/** 弹窗背后整屏模糊的半径. */
val ANI_GLASS_BLUR_RADIUS: Dp = 28.dp

/** 背后模糊了时窗口外那层系统压暗 (模糊本身已经把背后压平, 不必再压那么暗; 没模糊时用 [CENTERED_PANEL_WINDOW_DIM]). */
const val ANI_GLASS_WINDOW_DIM: Float = 0.2f

/**
 * 玻璃面板的底色: 面板那一档配色 (`surfaceContainerHigh`) 加透明度. 背后模糊了才透得多 —— 透出来的是糊开的画面, 看着是玻璃;
 * 没模糊 (设备不支持) 时透多了后面的字会直接透上来, 退回接近实底; 盖在播放器上时同 [centeredPanelColor] 那一档.
 */
@Composable
fun aniGlassPanelColor(blurred: Boolean): Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
    alpha = when {
        blurred -> GLASS_PANEL_ALPHA_BLURRED
        LocalTvPlayPauseHandler.current != null -> GLASS_PANEL_ALPHA_OVER_PLAYER
        else -> GLASS_PANEL_ALPHA
    },
)

/** 玻璃的那圈细高光边: 白色, 上沿亮、下沿淡 (光从上面来). 浅色主题下亮一些, 深色下压着, 免得一圈白线抢眼. */
@Composable
fun aniGlassEdge(): BorderStroke {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return BorderStroke(
        1.dp,
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (light) 0.75f else 0.26f),
                Color.White.copy(alpha = if (light) 0.2f else 0.05f),
            ),
        ),
    )
}

/**
 * 内容是否画在玻璃面板里 (由玻璃面板提供: [AniCenteredPanelDialog] 与各自绘的玻璃弹窗). 面板里的项目 (卡片、胶囊、选项格) 据此
 * 把常态底色换成 [aniGlassItemColor], 不再是一块块实心灰; 页面上的同一组件照旧.
 */
val LocalAniGlassPanel = staticCompositionLocalOf { false }

/**
 * 玻璃面板里项目的常态底: 很淡的一层白, 只为圈出这一项的范围 —— 不加高光边 (液态感只放在面板边缘与按钮上, 内容区保持平静).
 */
@Composable
fun aniGlassItemColor(): Color {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return Color.White.copy(alpha = if (light) 0.45f else 0.07f)
}

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
 * 面板弹窗标题的字: 比正文大两档、加粗 (同设计稿的「选集」), 所有面板弹窗的标题统一用它 ([AniCenteredPanelDialog] 的 title 默认就是);
 * 标题下面的一行说明用 [aniPanelSubtitleStyle].
 */
val aniPanelTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)

/** 面板标题下面那一行说明 (如所属条目名): 正文小一档, 颜色淡一档由调用方给 (onSurfaceVariant). */
val aniPanelSubtitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium

/** 玻璃按钮 (没聚焦时) 的底: 一层淡白, 叠在玻璃面板或剧照上都是「更亮一点的玻璃」. */
@Composable
fun aniGlassButtonColor(): Color {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return Color.White.copy(alpha = if (light) 0.55f else 0.1f)
}

private const val GLASS_PANEL_ALPHA_BLURRED = 0.6f
private const val GLASS_PANEL_ALPHA_OVER_PLAYER = 0.85f
private const val GLASS_PANEL_ALPHA = 0.95f
