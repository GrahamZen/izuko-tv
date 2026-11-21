/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.theme.AniTheme
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.subject.details.layout.TvDetailsTheme
import me.him188.ani.app.ui.subject.details.layout.TvPadDetailsBackdrop
import me.him188.ani.app.ui.subject.details.layout.tvPadPlayerBackdropBlur
import kotlin.math.min
import kotlin.math.roundToInt

/** 侧边那一块此刻是哪种形态. */
internal enum class TabletSideMode {
    /** 横屏分栏: 右栏, 默认推荐, 面板盖住它. */
    COLUMN,

    /** 全屏: 侧边槽铺满整屏, 内容 (抽屉) 只占右边 [TabletPlayerGeometry.panelWidth], 左边是点了就关抽屉的空白. */
    DRAWER,

    /** 竖屏分栏: 面板盖住视频下面那一块 (从视频下沿到屏幕底). */
    SHEET,
}

/**
 * 平板播放页三块的位置 (像素): 视频、视频下面、侧边. null = 这一块此刻不摆 (仍在组合里, 回来时状态还在).
 */
@Immutable
internal data class TabletPlayerGeometry(
    val player: IntRect,
    val below: IntRect?,
    val side: IntRect?,
    val sideMode: TabletSideMode,
    /** 右栏 / 抽屉本身的宽 (竖屏时是整屏宽). */
    val panelWidth: Int,
)

/** 横屏右栏占屏宽的比例 (YouTube 平板约 0.3, B站 iPad 约 0.37). */
internal const val TABLET_SIDE_FRACTION = 0.35f

/** 竖屏全屏时抽屉占屏宽的比例. */
internal const val TABLET_PORTRAIT_DRAWER_FRACTION = 0.7f

/** 横屏分栏时视频最多占屏高的比例: 再高的话视频下面就没地方了 (屏幕比 16:10 更宽时才会碰到). */
internal const val TABLET_PLAYER_MAX_HEIGHT_FRACTION = 0.7f

internal val TABLET_SIDE_MIN_WIDTH = 300.dp
internal val TABLET_SIDE_MAX_WIDTH = 460.dp

/**
 * 按屏幕大小与状态算三块的位置. 纯函数, 见 TabletPlayerGeometryTest.
 *
 * - 全屏: 视频铺满; 抽屉开着 ([sideOpen]) 时侧边槽铺满整屏 ([TabletSideMode.DRAWER]).
 * - 横屏分栏: 右栏宽 = 屏宽 × [TABLET_SIDE_FRACTION] (夹在 [sideMinPx]..[sideMaxPx], 且不超过半屏), 左边视频按 16:9 贴顶, 下面是这一部的信息.
 * - 竖屏分栏: 视频整屏宽贴顶, 下面是这一部的信息; 面板开着时盖住视频下面那一块.
 */
internal fun tabletPlayerGeometry(
    width: Int,
    height: Int,
    sideMinPx: Int,
    sideMaxPx: Int,
    fullScreen: Boolean,
    sideOpen: Boolean,
): TabletPlayerGeometry {
    val full = IntRect(0, 0, width, height)
    val landscape = width >= height
    fun clampSide(raw: Float): Int {
        val half = width / 2
        val lo = min(sideMinPx, half)
        val hi = min(sideMaxPx, half).coerceAtLeast(lo)
        return raw.roundToInt().coerceIn(lo, hi)
    }
    if (fullScreen) {
        val drawerWidth = if (landscape) {
            clampSide(width * TABLET_SIDE_FRACTION)
        } else {
            (width * TABLET_PORTRAIT_DRAWER_FRACTION).roundToInt().coerceAtMost(width)
        }
        return TabletPlayerGeometry(
            player = full,
            below = null,
            side = if (sideOpen) full else null,
            sideMode = TabletSideMode.DRAWER,
            panelWidth = drawerWidth,
        )
    }
    if (landscape) {
        val sideWidth = clampSide(width * TABLET_SIDE_FRACTION)
        val left = width - sideWidth
        val playerHeight = (left * 9 / 16).coerceAtMost((height * TABLET_PLAYER_MAX_HEIGHT_FRACTION).roundToInt())
        return TabletPlayerGeometry(
            player = IntRect(0, 0, left, playerHeight),
            below = IntRect(0, playerHeight, left, height),
            side = IntRect(left, 0, width, height),
            sideMode = TabletSideMode.COLUMN,
            panelWidth = sideWidth,
        )
    }
    val playerHeight = width * 9 / 16
    val belowRect = IntRect(0, playerHeight, width, height)
    return TabletPlayerGeometry(
        player = IntRect(0, 0, width, playerHeight),
        below = belowRect,
        side = if (sideOpen) belowRect else null,
        sideMode = TabletSideMode.SHEET,
        panelWidth = width,
    )
}

/**
 * 平板模式播放页的骨架: 视频、视频下面、侧边三块, 位置见 [tabletPlayerGeometry].
 *
 * **视频那一块的节点始终是同一个** (进出全屏只改它的位置和大小, 不换父节点): 视频面是独立的系统图层,
 * 换父节点会把它销毁重建, 画面黑一下、解码器重新挂.
 *
 * 配色同上游播放页: 视频下面与侧边跟 [pageTheme] (用户主题; 设置「播放页始终使用深色」打开时它就是深色;
 * 设置「条目详情页使用动态主题」打开时主色换成这一部封面取的色, 同详情页, 见 rememberTabletSubjectTheme);
 * 视频与全屏时盖在画面上的抽屉恒深色. 每块都按自己的配色给内容色 (LocalContentColor), 不吃外层的.
 * 分栏时的底同平板详情页: 页面底色上铺这一部的模糊底 ([backdropUrl], 同一张图、同样的压暗算法), 糊得比详情页重 (同海报墙那一档,
 * 见 tvPadPlayerBackdropBlur), 进出时在两档之间渐变 (见 TvTouchBlurredBackdrop); 全屏是黑底.
 */
@Composable
internal fun TabletPlayerFrame(
    state: TabletPlayerState,
    /** 应用窗口缩进了系统小窗 (安卓, 见 TabletPictureInPicture.occupiesWindow): 只摆视频 (按全屏摆, 侧边不开). */
    pictureInPicture: Boolean,
    pageTheme: ThemeSettings,
    /** 分栏时铺的模糊底用的图 (见 rememberTabletBackdropUrl); null = 还没有, 只铺页面底色. */
    backdropUrl: String?,
    player: @Composable () -> Unit,
    below: @Composable (TabletPlayerGeometry) -> Unit,
    side: @Composable (TabletPlayerGeometry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fullScreen = state.fullScreen || pictureInPicture
    CompositionLocalProvider(LocalThemeSettings provides pageTheme) {
        TabletRegionTheme(dark = fullScreen) {
            BoxWithConstraints(modifier.fillMaxSize().background(if (fullScreen) Color.Black else AniThemeDefaults.pageContentBackgroundColor)) {
                if (!fullScreen && backdropUrl != null) TvPadDetailsBackdrop(backdropUrl, tvPadPlayerBackdropBlur())
                val density = LocalDensity.current
                val geometry = tabletPlayerGeometry(
                    width = constraints.maxWidth,
                    height = constraints.maxHeight,
                    sideMinPx = with(density) { TABLET_SIDE_MIN_WIDTH.roundToPx() },
                    sideMaxPx = with(density) { TABLET_SIDE_MAX_WIDTH.roundToPx() },
                    fullScreen = fullScreen,
                    sideOpen = state.shownPanel != null && !pictureInPicture,
                )
                // 竖屏开着面板时面板占的就是这一块: 这一块藏起来 (还在组合里, 滚动位置不丢), 面板不铺底, 透出整页的模糊底 (同横屏右栏)
                val belowCovered = geometry.sideMode == TabletSideMode.SHEET && geometry.side != null
                Box(Modifier.placeAt(geometry.below).graphicsLayer { alpha = if (belowCovered) 0f else 1f }) {
                    TabletRegionTheme(dark = false) { below(geometry) }
                }
                Box(Modifier.placeAt(geometry.player)) {
                    TabletRegionTheme(dark = true) { player() }
                }
                // 分栏右栏、全屏抽屉贴着屏幕顶边: 顶上压着状态栏的那几样让开 (竖屏面板在视频下面, 不用)
                val topInset = if (geometry.sideMode == TabletSideMode.SHEET) 0.dp else tabletStatusBarInset()
                Box(Modifier.placeAt(geometry.side)) {
                    CompositionLocalProvider(LocalTabletTopInset provides topInset) {
                        TabletRegionTheme(dark = geometry.sideMode == TabletSideMode.DRAWER) { side(geometry) }
                    }
                }
            }
        }
    }
}

/**
 * 一块区域的配色: [dark] 恒深色, 否则跟外面给的用户主题, 浅色下换成详情页那套 (页面底与底板是电视的浅灰, 见 TvDetailsTheme);
 * 内容色取这套配色的 onSurface.
 */
@Composable
private fun TabletRegionTheme(dark: Boolean, content: @Composable () -> Unit) {
    AniTheme(darkModeOverride = if (dark) DarkMode.DARK else null) {
        TvDetailsTheme {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
        }
    }
}

/** 摆到 [rect]; null 时不摆 (大小 0, 子节点不测量也不画, 但留在组合里). */
private fun Modifier.placeAt(rect: IntRect?): Modifier =
    offset { if (rect == null) IntOffset.Zero else IntOffset(rect.left, rect.top) }
        .layout { measurable, _ ->
            if (rect == null) return@layout layout(0, 0) {}
            val placeable = measurable.measure(Constraints.fixed(rect.width, rect.height))
            layout(rect.width, rect.height) { placeable.place(0, 0) }
        }

/**
 * 平板播放页自己的那几层返回: 全屏抽屉 → 全屏退回分栏 → 右栏面板退一层 (见 [TabletPlayerState.back]).
 * 挂在骨架根上的 onPreviewKeyEvent: 比播放器原来的根路由先看到返回键.
 *
 * - [blocked] 为真时 (发弹幕输入框开着、拖进度条预览中、看大图、评论弹窗开着、侧边 sheet 开着) 一律放行给原来的路由, 由它按原来的规矩收.
 * - 平板这一层没东西可收时调 [onNothingLeft] 并放行: 那里把控件悄悄收掉, 原来的路由在纯视频态不消费返回, 于是直接退出播放页
 *   (设计: 没有「先收控件」那一步, 同 YouTube / B站).
 *
 * 动作在抬起时做 (同原来的路由); 按下就认领, 认领了的那一次抬起也吞掉, 免得落到原来的路由里再收一遍.
 */
internal class TabletPlayerBackKeys(
    private val state: TabletPlayerState,
    private val blocked: () -> Boolean,
    private val onNothingLeft: () -> Unit,
) {
    private var armed = false

    fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        if (event.key != Key.Back && event.key != Key.Escape) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                if (armed) return true // 按住的连发
                if (blocked()) return false
                armed = state.canGoBack
                if (!armed) onNothingLeft()
                armed
            }

            KeyEventType.KeyUp -> {
                if (!armed) return false
                armed = false
                state.back()
                true
            }

            else -> false
        }
    }
}
