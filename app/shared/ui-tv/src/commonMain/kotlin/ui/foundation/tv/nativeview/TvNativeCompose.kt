/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_CORNER
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_ICON_GAP
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_ICON_SIZE
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_OUTLINE_WIDTH
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_PADDING_HORIZONTAL
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_PADDING_VERTICAL
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_SCALE
import me.him188.ani.app.ui.foundation.tv.TV_HERO_META_GAP
import me.him188.ani.app.ui.foundation.tv.TV_HERO_RATING_STAR_GAP
import me.him188.ani.app.ui.foundation.tv.TV_HERO_RATING_STAR_SIZE
import me.him188.ani.app.ui.foundation.tv.TV_REDUCED_MARQUEE_ITERATIONS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_SLIDE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.TvPolishFlags
import me.him188.ani.app.ui.foundation.tv.tvHeroButtonContainerColor
import me.him188.ani.app.ui.foundation.tv.tvHeroButtonOutlineColor
import me.him188.ani.app.ui.foundation.tv.tvDetailsTitleShadow
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor

/**
 * 原生页面里只为观感跑、不承载交互的循环动画 (跑马灯). 页面不在前台 ([LocalPageIsForeground]) 时由 [TvNativeHost] 暂停, 回前台恢复:
 * 放大进来的详情页盖着列表页时, 列表页的视图仍附着, 这类动画不停就每帧失效, 整个窗口跟着逐帧重画.
 */
interface TvNativeAmbientAnimations {
    fun setAmbientAnimationsPaused(paused: Boolean)
}

/**
 * 装原生页面的 AndroidView: 铺满. 页面从屏幕左缘铺起 (侧边栏是盖在上面的透明浮层), 原生视图自己把内容让开侧边栏,
 * 横滑行、最左一列的放大与投影照常画进侧边栏底下.
 * 单独一层 graphicsLayer: 原生树每次失效只重录这一层 (里面就是一条画原生 RenderNode 的指令).
 * 视图只建一次 ([factory]), 主题 / 尺寸 / 数据的变化都走 [update], 焦点与滚动位置不因重组丢. 唯一的例外是 [rebuildKey]: 它变了就把视图
 * 整个换掉重建 —— 给建好就改不了的东西用 (海报墙大小: 卡片视图的尺寸在建的时候就定了). 停位与焦点由调用方存在视图外面, 重建时恢复.
 * 本页不在前台时系统焦点进不来 (见 [TvNativeFocusGate]), 页面实现了 [TvNativeAmbientAnimations] 的话环境动画同时暂停.
 */
@Composable
fun <T : View> TvNativeHost(
    factory: (Context) -> T,
    update: (T) -> Unit,
    modifier: Modifier = Modifier,
    rebuildKey: Any? = null,
) {
    val foreground = LocalPageIsForeground.current
    val ambient = remember { arrayOfNulls<TvNativeAmbientAnimations>(1) }
    LaunchedEffect(foreground) {
        snapshotFlow { foreground.value }.collect { ambient[0]?.setAmbientAnimationsPaused(!it) }
    }
    key(rebuildKey) {
        AndroidView(
            factory = { context ->
                val content = factory(context)
                ambient[0] = content as? TvNativeAmbientAnimations
                TvNativeFocusGate(context, content, foreground)
            },
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer(),
            update = { gate ->
                gate.foreground = foreground
                update(gate.content)
            },
        )
    }
}

/**
 * 装一条原生卡片行的 AndroidView (详情页那种夹在 Compose 内容中间的行): 布局上只占 [height] 高, 视图本身上下各多出 [bleedVertical]
 * (聚焦卡放大、投影伸出行外, 装它的 AndroidView 会按自己的边界裁掉子视图), 宽度铺满. 本页不在前台时系统焦点进不来 (见 [TvNativeFocusGate]),
 * 行实现了 [TvNativeAmbientAnimations] 的话环境动画同时暂停 (同 [TvNativeHost]).
 */
@Composable
fun <T : View> TvNativeRowHost(
    factory: (Context) -> T,
    update: (T) -> Unit,
    height: Dp,
    bleedVertical: Dp,
    modifier: Modifier = Modifier,
) {
    val foreground = LocalPageIsForeground.current
    val ambient = remember { arrayOfNulls<TvNativeAmbientAnimations>(1) }
    LaunchedEffect(foreground) {
        snapshotFlow { foreground.value }.collect { ambient[0]?.setAmbientAnimationsPaused(!it) }
    }
    AndroidView(
        factory = { context ->
            val content = factory(context)
            ambient[0] = content as? TvNativeAmbientAnimations
            TvNativeFocusGate(context, content, foreground).also { gate ->
                // 装它的那层 (AndroidViewHolder) 默认按边界裁子视图; 出血靠布局给大, 这里只是保险
                gate.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        (v.parent as? ViewGroup)?.clipChildren = false
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val bleed = bleedVertical.roundToPx()
                val rowHeight = height.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = rowHeight + bleed * 2, maxHeight = rowHeight + bleed * 2),
                )
                layout(placeable.width, rowHeight) { placeable.place(0, -bleed) }
            }
            .graphicsLayer(),
        update = { gate ->
            gate.foreground = foreground
            update(gate.content)
        },
    )
}

/**
 * 包在原生页面 / 行外面的一层 (装在 AndroidView 里的就是它, [content] 铺满其中, 不裁子视图): 本页不在前台 ([LocalPageIsForeground])
 * 时, 系统焦点进不了里面的原生视图.
 *
 * Compose 那侧挡焦点 (被盖住的列表页焦点组 onEnter 拒绝, 见 TvZoomStackScene) 只管 Compose 自己的焦点移动. 方向键在上层页面没被
 * Compose 消费时, 系统按屏幕位置在整个窗口里找下一个焦点 (ViewRootImpl → FocusFinder), 垫在下面的列表页的原生卡片照样是候选;
 * 原生视图一拿到焦点, Compose 的互操作节点直接把自己的焦点也挪过去, 不经 onEnter. 于是列表页在看不见的地方换了聚焦卡 (hero 跟着换),
 * 返回缩回时落到那张卡上 (真机 2026-09-28: 详情页快速上下翻再连按返回, 列表页焦点落到下面一两行的首卡).
 *
 * 做法是被问到时现读 ([getDescendantFocusability]): 系统收集候选 (addFocusables)、子视图 requestFocus 查祖先、有新的可聚焦视图时
 * 上报 (focusableViewAvailable)、Compose 取互操作节点的可聚焦性 (hasFocusable) 都经过它. 状态翻转那一刻不改任何属性 —— 回到前台
 * 当帧就放开, 页面自己的焦点恢复照常成功. 两种情形照常放行:
 * - 里面已经持着焦点 (点卡片进详情页、详情页还没接走焦点的那一段): 持焦的节点不当场变不可聚焦, 否则 Compose 重新取这个互操作
 *   节点的可聚焦性时会把整棵树的焦点清掉;
 * - 返回缩回运动中 ([TvHeroZoomHandoff.shrinkMoving]): 这段按键两页一起吞, 漏不过来; 详情页在缩回层上屏那一刻就出栈的那条路径上,
 *   列表页的焦点正是在运动中落回原生卡片的.
 *
 * 读栈顶状态不登记快照观察: 这里会在 Compose 取可聚焦性时被调到, 登记上的话前台一翻转就会触发那次重新判定.
 */
private class TvNativeFocusGate<T : View>(
    context: Context,
    val content: T,
    foreground: State<Boolean>,
) : FrameLayout(context) {
    /** 本页此刻在不在前台; 可空: 父类构造期间就可能被问到, 那时还没赋值 (当作在前台). */
    var foreground: State<Boolean>? = foreground

    init {
        clipChildren = false
        clipToPadding = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun getDescendantFocusability(): Int =
        if (!hasFocus() && Snapshot.withoutReadObservation { blocked() }) FOCUS_BLOCK_DESCENDANTS else super.getDescendantFocusability()

    private fun blocked(): Boolean = foreground?.value == false && !TvHeroZoomHandoff.shrinkMoving
}

/**
 * 长按菜单的目标, 收起后还留着: 菜单收起时要淡出 (见 AniDropdownMenu 的定位菜单), 这期间照样组合着上一次的目标、只把展开置假.
 * [shown] 是此刻展开的那个 (null = 收起).
 */
@Composable
internal fun <T : Any> rememberTvMenuTarget(shown: T?): T? {
    val last = remember { TvMenuTargetHolder<T>() }
    if (shown != null) last.value = shown
    return shown ?: last.value
}

private class TvMenuTargetHolder<T : Any> {
    var value: T? = null
}

/** 把 Compose 的矢量图标按 [size] 画成白色位图 (原生侧用 colorFilter 着色); [tint] 给了就直接画成那个颜色. */
@Composable
fun rememberTvNativeIcon(icon: ImageVector, size: Dp, tint: Color = Color.White): Bitmap {
    val painter = rememberVectorPainter(icon)
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }.coerceAtLeast(1)
    return remember(icon, px, tint, density) {
        val image = ImageBitmap(px, px)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(px.toFloat(), px.toFloat())) {
            with(painter) { draw(this@draw.size, colorFilter = ColorFilter.tint(tint)) }
        }
        image.asAndroidBitmap()
    }
}

/**
 * hero 文字块的排版参数: 标题 headlineLarge、评分 titleMedium (同标题的主要文字色, 颜色只在星上)、信息行与下一集行 labelLarge、
 * 简介 bodyMedium; 颜色取 hero 前景 / 次要色.
 */
@Composable
fun rememberTvNativeHeroTextStyle(
    titleMaxLines: Int,
    lineSpacing: Dp,
    statusHeight: Dp = 0.dp,
): TvNativeHeroTextStyle {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val content = tvHeroContentColor()
    val secondary = tvHeroSecondaryContentColor()
    val visualEffects = LocalThemeSettings.current.visualEffects
    val star = rememberTvNativeIcon(Icons.Rounded.Star, TV_HERO_RATING_STAR_SIZE, colors.primary)
    val stagger = visualEffects.transitions && TvPolishFlags.textStagger
    return remember(density, typography, colors, content, secondary, visualEffects, star, stagger, titleMaxLines, lineSpacing, statusHeight) {
        with(density) {
            TvNativeHeroTextStyle(
                title = typography.headlineLarge.toTvNativeTextStyle(density, content),
                titleMaxLines = titleMaxLines,
                // 评分数字用主要文字色: 主题色是中等亮度的彩色, 压在 hero 图 / 模糊背景上与它撞色 (背景是每部番自己的图, 颜色不定) ——
                // 照 Apple TV 的评分, 颜色只留在旁边的星上 (图标形状认得出)
                rating = typography.titleMedium.toTvNativeTextStyle(density, content),
                meta = typography.labelLarge.toTvNativeTextStyle(density, secondary),
                // 下一集 / 开始观看那一行加粗: 它与简介同为主要信息, 靠字重 (tvOS 的 Emphasized) 分开, 简介才能留在最清楚的主要色上
                status = typography.labelLarge.copy(fontWeight = FontWeight.Bold).toTvNativeTextStyle(density, secondary),
                // 简介用主要色: 它是整屏最长的一段字, 淡一档读着累; 与上面的下一集行靠那一行的字重分开 (见 status)
                summary = typography.bodyMedium.toTvNativeTextStyle(density, content),
                star = star,
                starSizePx = TV_HERO_RATING_STAR_SIZE.roundToPx(),
                starGapPx = TV_HERO_RATING_STAR_GAP.roundToPx(),
                metaGapPx = TV_HERO_META_GAP.roundToPx(),
                lineSpacingPx = lineSpacing.roundToPx(),
                statusHeightPx = statusHeight.roundToPx(),
                slidePx = TV_SCROLL_HIDDEN_TEXT_SLIDE_DISTANCE.roundToPx(),
                stagger = stagger,
                animated = visualEffects.transitions,
                marqueeRepeat = when {
                    !visualEffects.marquee -> 0
                    visualEffects.ambient -> -1
                    else -> TV_REDUCED_MARQUEE_ITERATIONS
                },
                // 浅色主题: 详情页大标题是白字压黑影 (同 rememberTvDetailsHeroTextStyle), 列表页是黑字; 深色两边一个样子
                openTitle = if (colors.surface.luminance() >= 0.5f) {
                    val shadow = tvDetailsTitleShadow(density)
                    TvNativeTitleLook(
                        color = Color.White.toArgb(),
                        shadowColor = shadow.color.toArgb(),
                        shadowDyPx = shadow.offset.y,
                        shadowRadiusPx = shadow.blurRadius,
                    )
                } else {
                    null
                },
            )
        }
    }
}

/**
 * hero 操作按钮的外观: 取值同 TvHeroButton 在海报墙主题下 (按 [TV_HERO_BUTTON_SCALE] 缩放的内边距 / 图标 / titleSmall 字号, 底板与描边色).
 */
@Composable
fun rememberTvNativeHeroButtonStyle(): TvNativeHeroButtonStyle {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val dark = colors.surface.luminance() < 0.5f
    return remember(density, colors, typography, dark) {
        with(density) {
            val scale = TV_HERO_BUTTON_SCALE
            val text = typography.titleSmall.let { it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) }
            TvNativeHeroButtonStyle(
                text = text.toTvNativeTextStyle(density, colors.onSurface),
                iconSizePx = (TV_HERO_BUTTON_ICON_SIZE * scale).roundToPx(),
                iconGapPx = (TV_HERO_BUTTON_ICON_GAP * scale).roundToPx(),
                paddingHorizontalPx = (TV_HERO_BUTTON_PADDING_HORIZONTAL * scale).roundToPx(),
                paddingVerticalPx = (TV_HERO_BUTTON_PADDING_VERTICAL * scale).roundToPx(),
                cornerPx = TV_HERO_BUTTON_CORNER.toPx(),
                outlineWidthPx = TV_HERO_BUTTON_OUTLINE_WIDTH.toPx(),
                outlineColor = tvHeroButtonOutlineColor(dark).toArgb(),
                filledColor = tvHeroButtonContainerColor(colors, filled = true, posterWall = true).toArgb(),
                unfilledColor = tvHeroButtonContainerColor(colors, filled = false, posterWall = true).toArgb(),
                focusedColor = colors.primary.toArgb(),
                contentColor = colors.onSurface.toArgb(),
                focusedContentColor = colors.onPrimary.toArgb(),
            )
        }
    }
}


