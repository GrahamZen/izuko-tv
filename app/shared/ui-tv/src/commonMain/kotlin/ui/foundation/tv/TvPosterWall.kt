/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.TvCardFocusStyle
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings

/*
 * 海报墙: 探索 / 搜索 / 追番页的「海报 + 番名」卡片形态, 详情页的关联条目也是这种卡. 画面是原生 View (见 nativeview 包);
 * 这里是各页与原生视图共用的尺寸、配色与整屏底色.
 *
 * 尺寸照 Apple TV: 1080p 一屏 6 张 (TV App 海报行、资料库网格、官方 tvOS 示例都是一屏 6 张; 设计规范 6 列网格每张
 * 260 pt = 130 dp, 列距 40 pt = 20 dp). 本应用左边有 48 dp 侧边栏, 内容区 848 dp 排 6 列约 124.7 dp.
 *
 * 底色: 卡片墙铺 Apple 系统灰阶里的 Gray 5 ([tvPosterWallBackground]) —— 深色是 Apple TV App 那种深灰, 不是纯黑; 浅色是同一档的
 * 浅灰, 不是纯白. 深色下 hero 态与探索页的热门轮播换成近黑 (hero 背景图按黑底羽化), 浅色下不换色 (见 [TvPosterWallTone]).
 */

/**
 * 卡片的**最小**宽度: 按它定列数 (同 GridCells.Adaptive), 实际宽度由铺满整行决定.
 * 1080p 内容区 848 dp: (848 + 20) / (118 + 20) ≈ 6.3 → 6 列, 实宽约 124.7 dp; 「界面缩放」调大 (dp 宽度变窄) 时
 * 列数跟着变少, 同 Apple 示例里辅助功能大字号改 4 列的做法.
 * 取 118 而不是刚好够的 124: 列数算式卡在边界上时, 某台机器可用宽度少几 dp 就会掉成 5 列.
 */
val TV_POSTER_WALL_CARD_MIN_WIDTH: Dp = 118.dp

/**
 * 海报墙卡片的聚焦样式: 照 Apple TV 只放大 ([TV_CARD_FOCUS_SCALE_WITHOUT_RING] 倍) 加投影, 不画框. 「卡片聚焦样式」设置只管
 * 时间表网格 (见 TvGridFocusSlot), 海报墙不看它.
 */
val TV_POSTER_WALL_CARD_FOCUS_STYLE: TvCardFocusStyle = TvCardFocusStyle.Scale

/** 列距 (Apple 设计规范 40 pt). 聚焦放大 1.12 倍后卡片左右各伸出约 7.5 dp, 与邻卡之间仍有一道缝. */
val TV_POSTER_WALL_COLUMN_SPACING: Dp = 20.dp

/** 行距: 上一行番名的底到下一行海报的顶. 聚焦放大后海报顶边上伸约 10.4 dp, 仍碰不到上一行的番名. */
val TV_POSTER_WALL_ROW_SPACING: Dp = 20.dp

/** 组标题到海报的间距: 卡聚焦时海报往上放大约 10 dp, 12 dp 看着挤 (用户 09-26). */
val TV_POSTER_WALL_HEADER_GAP: Dp = 18.dp

/**
 * 卡片区往屏幕底边外多排的一截 (网格的下出血): 屏上最下面那行之下的一行只要有一部分落进这一截就已经排好,
 * 焦点走过去时按位置跑 spring, 送焦也不必先瞬移 (那就是"闪现").
 * 最坏情况是屏上末行底下正好只剩一个行距 (20 dp) 的空, 下一行整个在屏外 —— 这一截要比行距大出一段, 保证它露进来.
 */
val TV_POSTER_WALL_BOTTOM_BLEED: Dp = 64.dp

/**
 * 滚到底时末行离视口底边留的空 (不含聚焦放大往下伸的那截, 见 [tvPosterWallEndMargin]): tvOS 的屏幕安全边距 60 pt ≈ 30 dp.
 */
val TV_POSTER_WALL_END_MARGIN: Dp = 30.dp

/**
 * 深色主题下海报墙页面的底色: Android TV 上 Apple TV App 的页面底色 (44, 44, 46) —— 它界面包里 `BackgroundWidget` 默认
 * 用的 `Color.Background`, 与 Apple 深色系统灰 Gray 5 同值. 不是纯黑; 网页版 tv.apple.com 深色的 #1F1F1F 在电视上跟纯黑
 * 分不出来.
 */
val TV_POSTER_WALL_BACKGROUND_DARK: Color = Color(0xFF2C2C2E)

/**
 * 浅色主题下海报墙页面的底色 (191, 196, 199): Apple TV App 关掉透明效果时浅色主题的实色页面底 (app.js 的
 * `AccessibilityReduceTransparencyBackground`), 同一张表里深色那一档正是 [TV_POSTER_WALL_BACKGROUND_DARK]. tvOS 平时的页面是半透明
 * 材质叠在画面上, 这是它的实色等价. 不用纯白或更浅的灰: 电视上大片亮底刺眼 (Android TV 设计指南: 除非必要不要用白底).
 */
val TV_POSTER_WALL_BACKGROUND_LIGHT: Color = Color(0xFFBFC4C7)

/**
 * 海报墙卡片墙的底色: 深色主题铺 [TV_POSTER_WALL_BACKGROUND_DARK], 浅色主题铺 [TV_POSTER_WALL_BACKGROUND_LIGHT].
 * hero 态的底色与两者之间的过渡见 [TvPosterWallTone].
 */
@Composable
fun tvPosterWallBackground(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) TV_POSTER_WALL_BACKGROUND_DARK else TV_POSTER_WALL_BACKGROUND_LIGHT

/**
 * 列表页 hero 的底色, 海报墙的 hero 态与探索页热门轮播共用: 深色是页面换海报墙配色之前的近黑底色 [default] ——
 * hero 图是按黑底羽化的; 浅色是海报墙那档浅灰 [TV_POSTER_WALL_BACKGROUND_LIGHT], 与卡片墙同色, 海报墙进出 hero 态整屏不换色 ——
 * 浅灰压成近黑是几十倍的亮度跳变, 线性过渡拉多长都像闪一下 (tvOS、Plex、Jellyfin 的浅色主题里 hero 也都是浅底).
 */
@Composable
fun tvPosterWallHeroBackground(default: Color): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) default else TV_POSTER_WALL_BACKGROUND_LIGHT

/*
 * 深色海报墙页面上比底色亮的几档灰, 取自 Android TV 版 Apple TV App 的深色主题 (app.js 的 SystemGray 表; 按钮 / 卡片底板
 * 未聚焦是"白 10%"叠在页面底上, 聚焦加到 20%). 页面上的块一律从底色往上亮, 不出现比底色更黑的.
 */
/** SystemGray4 (58, 58, 60). */
val TV_POSTER_WALL_GRAY4: Color = Color(0xFF3A3A3C)

/** 白 10% 叠在 [TV_POSTER_WALL_BACKGROUND_DARK] 上: Apple 按钮 / 卡片底板未聚焦的那一档. */
val TV_POSTER_WALL_PLATTER: Color = Color(0xFF414143)

/** SystemGray3 (72, 72, 74). */
val TV_POSTER_WALL_GRAY3: Color = Color(0xFF48484A)

/** 白 20% 叠在 [TV_POSTER_WALL_BACKGROUND_DARK] 上: Apple 底板聚焦那一档. */
val TV_POSTER_WALL_PLATTER_HIGH: Color = Color(0xFF565658)

/*
 * 浅色海报墙页面上的几档, 都是白 / 黑按比例叠在 [TV_POSTER_WALL_BACKGROUND_LIGHT] 上 (Apple TV App 浅色表里按钮底板是"白 20%"叠在页面底上):
 * 底板这几档从底色往白走, 同深色那套从底色往上亮. 聚焦时换上的两档见 [tvPosterWallColorScheme].
 */
/** 白 10% 叠在 [TV_POSTER_WALL_BACKGROUND_LIGHT] 上. */
val TV_POSTER_WALL_PLATTER_LIGHT: Color = Color(0xFFC5CACD)

/** 白 20% 叠在 [TV_POSTER_WALL_BACKGROUND_LIGHT] 上: Apple 浅色按钮底板. */
val TV_POSTER_WALL_GRAY6_LIGHT: Color = Color(0xFFCCD0D2)

/** 白 5% 叠在 [TV_POSTER_WALL_BACKGROUND_LIGHT] 上: 聚焦时换上的底 (surfaceContainerHigh), 只比页面亮一丝. */
private val TV_POSTER_WALL_FOCUS_LIGHT: Color = Color(0xFFC2C7CA)

/** 黑 3% 叠在 [TV_POSTER_WALL_BACKGROUND_LIGHT] 上 (surfaceContainerHighest). */
private val TV_POSTER_WALL_SUNKEN_LIGHT: Color = Color(0xFFB9BEC1)

/** 浅色次要文字: Apple 浅色表的 LabelSecondary (黑 60%) 叠在 [TV_POSTER_WALL_BACKGROUND_LIGHT] 上的实色. */
val TV_POSTER_WALL_SECONDARY_LABEL_LIGHT: Color = Color(0xFF4C4E50)

/**
 * 海报墙页面的配色: 把 Material 的各档底色 (background / surface / surfaceContainer* / surfaceVariant) 换成上面那几档灰 —— 默认深色
 * 主题里这些是近黑, 搜索框、扫码面板、卡片底、菜单与弹窗铺在深灰页面上就是一块块黑的; 浅色同理换成 Apple 浅色那几档.
 * 主题色 (聚焦时的主色等) 不动; 浅色的文字色照 Apple 浅色表: 正文纯黑 (LabelPrimary), 次要文字黑 60% (LabelSecondary).
 *
 * 浅色的 surfaceContainerHigh 只比页面亮一丝、surfaceContainerHighest 比页面暗一丝: High 是搜索候选行聚焦时换上的底色, 焦点态层
 * (浅色下是一层深色) 叠上去整体仍比页面暗, 与底色变化同向; 聚焦底明显比周围亮的话, 就成了先变亮、焦点态层晚约 0.1 秒再压暗,
 * 每挪一次焦点新旧两块都闪一下.
 */
fun tvPosterWallColorScheme(base: ColorScheme): ColorScheme =
    if (base.surface.luminance() >= 0.5f) {
        base.copy(
            background = TV_POSTER_WALL_BACKGROUND_LIGHT,
            surface = TV_POSTER_WALL_BACKGROUND_LIGHT,
            surfaceDim = TV_POSTER_WALL_BACKGROUND_LIGHT,
            surfaceContainerLowest = TV_POSTER_WALL_BACKGROUND_LIGHT,
            surfaceContainerLow = TV_POSTER_WALL_PLATTER_LIGHT,
            surfaceContainer = TV_POSTER_WALL_GRAY6_LIGHT,
            surfaceContainerHigh = TV_POSTER_WALL_FOCUS_LIGHT,
            surfaceContainerHighest = TV_POSTER_WALL_SUNKEN_LIGHT,
            surfaceBright = TV_POSTER_WALL_GRAY6_LIGHT,
            surfaceVariant = TV_POSTER_WALL_PLATTER_LIGHT,
            onBackground = Color.Black,
            onSurface = Color.Black,
            onSurfaceVariant = TV_POSTER_WALL_SECONDARY_LABEL_LIGHT,
        )
    } else {
        base.copy(
            background = TV_POSTER_WALL_BACKGROUND_DARK,
            surface = TV_POSTER_WALL_BACKGROUND_DARK,
            surfaceDim = TV_POSTER_WALL_BACKGROUND_DARK,
            surfaceContainerLowest = TV_POSTER_WALL_BACKGROUND_DARK,
            surfaceContainerLow = TV_POSTER_WALL_GRAY4,
            surfaceContainer = TV_POSTER_WALL_PLATTER,
            surfaceContainerHigh = TV_POSTER_WALL_GRAY3,
            surfaceContainerHighest = TV_POSTER_WALL_PLATTER_HIGH,
            surfaceBright = TV_POSTER_WALL_GRAY3,
            surfaceVariant = TV_POSTER_WALL_GRAY4,
        )
    }

/**
 * [TvPosterWallTheme] 换配色之前的配色 (应用主题本身的配色): 搜索页 hero 态的底色取它的 background (见 [TvPosterWallTone]).
 * 不在 [TvPosterWallTheme] 里时为 null.
 */
val LocalTvPosterWallBaseColorScheme: ProvidableCompositionLocal<ColorScheme?> = staticCompositionLocalOf { null }

/**
 * 页面开着海报墙配色 ([TvPosterWallTheme]) 时为 true: 底色写死、不走 Material 配色的共享组件 (如 [TvHeroButton]) 据此改用海报墙
 * 配色里的那几档.
 */
val LocalTvPosterWallTheme: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * 海报墙页面 (探索 / 搜索 / 追番) 换上 [tvPosterWallColorScheme]. 配色对象按底色方案记住, 不每次重组都换新实例 —— 配色是静态的
 * CompositionLocal, 换实例会让整页重组.
 */
@Composable
fun TvPosterWallTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(base) { tvPosterWallColorScheme(base) }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalTvPosterWallTheme provides true,
            LocalTvPosterWallBaseColorScheme provides base,
            content = content,
        )
    }
}

/** 海报到番名的间距. */
internal val TV_POSTER_WALL_TITLE_TOP_GAP: Dp = 6.dp

/** 番名字号: 落在 Apple TV 卡片标题 (25–29 pt ≈ 12.5–14.5 dp) 的中间. */
internal val TV_POSTER_WALL_TITLE_FONT_SIZE = 13.sp

/** 番名行高. 两行定高 = 块高的输入 (见 [tvPosterWallLabelHeight]). */
internal val TV_POSTER_WALL_TITLE_LINE_HEIGHT = 18.sp

/** 列数. [availableWidth] = 卡片区的内容宽度 (已减掉出血与两侧留白). */
fun Density.tvPosterWallColumns(availableWidth: Dp): Int {
    val spacing = TV_POSTER_WALL_COLUMN_SPACING.roundToPx()
    return maxOf(1, (availableWidth.roundToPx() + spacing) / (TV_POSTER_WALL_CARD_MIN_WIDTH.roundToPx() + spacing))
}

/** 卡宽: [columns] 张铺满 [availableWidth], 行尾不留余量. */
fun tvPosterWallCardWidth(availableWidth: Dp, columns: Int): Dp =
    (availableWidth - TV_POSTER_WALL_COLUMN_SPACING * (columns - 1)) / columns

/**
 * 番名块的定高 (上间距 + 两行). 按 sp 换算: 电视上字高跟着系统字号走, 写死 dp 会把第二行底边裁掉.
 * 定高是为了行高一致 —— 一行的番名也占两行高, 换焦点时排版不跳.
 */
@Composable
fun tvPosterWallLabelHeight(): Dp =
    TV_POSTER_WALL_TITLE_TOP_GAP + with(LocalDensity.current) { TV_POSTER_WALL_TITLE_LINE_HEIGHT.toDp() } * 2

/**
 * 滚到底时末行番名底边离视口底边留多少: 聚焦放大后海报底边往下伸 (倍数 − 1) × 封面高 / 2, 番名跟着下移同样多 ——
 * 这一截加上安全边距 [TV_POSTER_WALL_END_MARGIN], 聚焦末行时番名才不会贴着屏幕底边.
 */
fun tvPosterWallEndMargin(coverHeight: Dp, focusScale: Float): Dp =
    coverHeight * ((focusScale - 1f) / 2f) + TV_POSTER_WALL_END_MARGIN

/**
 * 海报墙页面的整屏底色: 卡片墙是深灰 ([tvPosterWallBackground]), hero 态与探索页的热门轮播是
 * 近黑 ([heroColor]) —— hero 背景图是按黑底羽化的, 铺在深灰上发闷, 图边也压不住. 由画整屏底色的那一层 (主壳 / 搜索页根) 持有
 * ([rememberTvPosterWallTone]), 页面用 [TvPosterWallToneSource] 把自己此刻的黑度登记进来. 整屏的底与侧边栏展开面板都用
 * [drawBackground] 在绘制阶段画, 同一帧同一个值.
 *
 * 除了整屏的黑度, 页面还可以登记一条**分界线** (探索页的热门轮播): 线以上是 hero 的底, 往下 [TV_POSTER_WALL_HERO_SPLIT_BAND] 内渐变到
 * 整屏底色. 分界线跟着轮播背景图的下缘走、随列表滚动 —— 焦点在第一行时线停在它的组标题上沿, 上面黑、下面灰; 再往下翻线随轮播滚出屏,
 * 不用整屏换色.
 *
 * 浅色主题下两者同色 (见 [tvPosterWallHeroBackground]), 整屏不换色.
 *
 * 背景图只在底色黑透之后露面: hero 态靠页面自己的过渡 (见 TvNativeHeroTimeline), 热门轮播的图整个在分界线以上. 换页时底色与分界线都从
 * 上一页的值交接到这一页的 —— 等上一页淡出之后才起步; 交接途中还没黑透, 这一页的背景图按 [tvPosterWallToneGate] / [splitGate] 先挡着.
 */
@Stable
class TvPosterWallTone internal constructor(
    private val scope: CoroutineScope,
    wall: Color,
    hero: Color,
    wallPage: Boolean,
) {
    private var wallColor by mutableStateOf(wall)

    /** hero 的底色 (见 [tvPosterWallHeroBackground]): 海报墙 hero 态、探索页热门轮播与非海报墙页的整屏底色, 背景图的渐隐色也用它. */
    var heroColor: Color by mutableStateOf(hero)
        private set

    // 当前页是不是海报墙页: 不是的话恒为 hero 色 (页面本来的底色)
    private var wallPage by mutableStateOf(wallPage)

    // 登记的页面, 同一时刻只认最后登记的那个: 主壳换页时新页先组合, 旧页淡出之后才撤
    private var owner: Any? = null
    private var source by mutableStateOf<(() -> Float)?>(null)
    private var animated = true

    // 页面登记的分界线 (px, 从屏顶算; 见类说明), NaN = 这一帧没有
    private var splitSource by mutableStateOf<(() -> Float)?>(null)

    // 交接途中新页没登记分界线时, 沿用旧页最后画的位置淡掉. 只在绘制里读写, 不进快照
    private var lastSplitY = Float.NaN

    // 交接: 从换之前画着的黑度 handoffFrom / 分界线那层的浓度 splitFrom 渐变到新值, handoff 0 → 1
    private var handoffFrom by mutableFloatStateOf(1f)
    private var splitFrom by mutableFloatStateOf(0f)
    private var handoff by mutableFloatStateOf(1f)
    private var handoffJob: Job? = null

    /** 此刻整屏的黑度: 0 = 卡片墙的深灰, 1 = hero 的近黑. 绘制阶段读. */
    fun amount(): Float {
        val target = if (wallPage) source?.invoke()?.coerceIn(0f, 1f) ?: 0f else 1f
        val h = handoff
        return if (h >= 1f) target else lerp(handoffFrom, target, h)
    }

    /** 此刻的整屏底色 (不含分界线那层, 见 [drawBackground]). 绘制阶段读. */
    fun color(): Color = lerp(wallColor, heroColor, amount())

    /** 分界线那层的浓度: 登记了分界线的页是 1, 换页时随交接渐变. 绘制阶段读. */
    private fun splitStrength(): Float {
        val target = if (wallPage && splitSource != null) 1f else 0f
        val h = handoff
        return if (h >= 1f) target else lerp(splitFrom, target, h)
    }

    /**
     * 画整屏的底: [color] 铺满, 登记了分界线就在它以上叠 hero 的底、往下 [TV_POSTER_WALL_HERO_SPLIT_BAND] 内渐变掉. 主壳 / 搜索页根的底色层
     * 与侧边栏展开面板都用它, 同一帧同一个值.
     */
    fun drawBackground(scope: DrawScope) = with(scope) {
        drawRect(color())
        val strength = splitStrength()
        if (strength <= 0f || wallColor == heroColor) return@with
        val y = splitSource?.invoke()?.takeIf { !it.isNaN() }?.also { lastSplitY = it } ?: lastSplitY
        if (y.isNaN()) return@with
        val band = TV_POSTER_WALL_HERO_SPLIT_BAND.toPx()
        val bottom = (y + band).coerceAtMost(size.height)
        if (bottom <= 0f) return@with
        // smoothstep 采样, 无折点 (同背景图的羽化); 线以上按第一个色标铺满
        val stops = Array(TV_POSTER_WALL_HERO_SPLIT_STOPS + 1) { i ->
            val f = i / TV_POSTER_WALL_HERO_SPLIT_STOPS.toFloat()
            f to heroColor.copy(alpha = strength * (1f - f * f * (3f - 2f * f)))
        }
        drawRect(Brush.verticalGradient(*stops, startY = y, endY = y + band), size = Size(size.width, bottom))
    }


    /** 分界线以上那张图 (探索页的热门轮播) 的放行: 分界线那层到位了才是 1 (换页交接途中挡着). 绘制阶段读. */
    fun splitGate(): Float = if (wallColor == heroColor) 1f else splitStrength()

    internal fun update(wall: Color, hero: Color, wallPage: Boolean, animated: Boolean) {
        this.animated = animated
        wallColor = wall
        heroColor = hero
        if (this.wallPage != wallPage) {
            beginHandoff()
            this.wallPage = wallPage
        }
    }

    internal fun attach(owner: Any, amount: () -> Float, split: (() -> Float)?) {
        beginHandoff()
        this.owner = owner
        source = amount
        splitSource = split
    }

    internal fun detach(owner: Any) {
        if (this.owner !== owner) return
        beginHandoff()
        this.owner = null
        source = null
        splitSource = null
    }

    private fun beginHandoff() {
        handoffJob?.cancel()
        // 流畅档直接到位, 同其它过渡
        if (!animated) {
            handoff = 1f
            return
        }
        handoffFrom = amount()
        splitFrom = splitStrength()
        handoff = 0f
        handoffJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(TV_POSTER_WALL_TONE_HANDOFF_MILLIS, delayMillis = TV_POSTER_WALL_TONE_HANDOFF_DELAY_MILLIS),
            ) { value, _ -> handoff = value }
        }
    }
}

/**
 * 画整屏底色的那一层 (主壳 / 搜索页根) 持有的 [TvPosterWallTone]: [wall] / [hero] 是卡片墙与 hero 态的底色, [wallPage] = 当前页是海报墙页.
 */
@Composable
fun rememberTvPosterWallTone(wall: Color, hero: Color, wallPage: Boolean): TvPosterWallTone {
    val scope = rememberCoroutineScope()
    val animated = LocalThemeSettings.current.visualEffects.transitions
    val tone = remember { TvPosterWallTone(scope, wall, hero, wallPage) }
    SideEffect { tone.update(wall, hero, wallPage, animated) }
    return tone
}

/** 主壳里的页面拿 [TvPosterWallTone] 用. 搜索页自己画底色, 直接往下传. */
val LocalTvPosterWallTone: ProvidableCompositionLocal<TvPosterWallTone?> = staticCompositionLocalOf { null }

/**
 * 把本页此刻的黑度登记进 [tone] (null 时什么都不做): [amount] 在绘制阶段读, 0 = 卡片墙, 1 = hero 态. [heroBottom] 非 null 时再登记一条分界线
 * (px, 从屏顶算, 绘制阶段读; NaN = 这一帧没有): 线以上是 hero 的底, 见 [TvPosterWallTone]. 离开组合时撤掉.
 */
@Composable
fun TvPosterWallToneSource(tone: TvPosterWallTone?, heroBottom: (() -> Float)? = null, amount: () -> Float) {
    if (tone == null) return
    val latest = rememberUpdatedState(amount)
    val latestSplit = rememberUpdatedState(heroBottom)
    val hasSplit = heroBottom != null
    DisposableEffect(tone, hasSplit) {
        val owner = Any()
        tone.attach(owner, { latest.value() }, if (hasSplit) ({ latestSplit.value?.invoke() ?: Float.NaN }) else null)
        onDispose { tone.detach(owner) }
    }
}

/** 换页交接等上一页淡出再起步: 主壳换页先用 50ms 淡出旧页 (见 AniMotionScheme.topLevelTransition). */
private const val TV_POSTER_WALL_TONE_HANDOFF_DELAY_MILLIS = 50

/** 换页交接的时长: 比新页淡入 (150ms) 稍长, 整屏换色不显得突兀. */
private const val TV_POSTER_WALL_TONE_HANDOFF_MILLIS = 250

/**
 * 整屏黑度 [tone] (0..1) 下 hero 背景图的放行: 过了 [TV_POSTER_WALL_TONE_GATE_FROM] 才开始放, 黑透时全放. 原生页面按同一条线放行
 * (浅色主题不换色, 调用方直接放行).
 */
internal fun tvPosterWallToneGate(tone: Float): Float =
    ((tone - TV_POSTER_WALL_TONE_GATE_FROM) / (1f - TV_POSTER_WALL_TONE_GATE_FROM)).coerceIn(0f, 1f)

/** 整屏黑度过了这里背景图才开始放行, 黑透时全放. */
private const val TV_POSTER_WALL_TONE_GATE_FROM = 0.85f

/** 分界线以下渐变到整屏底色的那一段 (见 [TvPosterWallTone.drawBackground]): 与背景图下缘的羽化带差不多长. */
private val TV_POSTER_WALL_HERO_SPLIT_BAND = 96.dp

/** 分界线渐变的采样数. */
private const val TV_POSTER_WALL_HERO_SPLIT_STOPS = 8

/**
 * 卡片静止时的投影, 照 tvOS 18 设计套件的海报 lockup (未聚焦: 黑 40%、下移 4 pt、模糊 12 pt; 1 pt = 0.5 dp): 每张卡底下都有一圈
 * 看得出的影, 聚焦的那张换成系统阴影的一大片软影 ([TV_POSTER_WALL_FOCUSED_ELEVATION]). 系统阴影的浓度被主题限死 (投射阴影约 19%),
 * 深色底上几乎看不出, 所以静止这层按这组值预先模糊好贴在海报底下 (见 TvNativeCardShadow). 浅色档透明度减半 (同 [TV_POSTER_WALL_SHADOW_COLOR_LIGHT]).
 */
internal val TV_POSTER_WALL_IDLE_SHADOW = Color.Black.copy(alpha = 0.4f)
internal val TV_POSTER_WALL_IDLE_SHADOW_LIGHT = Color.Black.copy(alpha = 0.2f)
internal val TV_POSTER_WALL_IDLE_SHADOW_OFFSET_Y = 2.dp
internal val TV_POSTER_WALL_IDLE_SHADOW_BLUR = 6.dp

/** 卡片聚焦时的高度: 抬起来的一大片软影 (Apple TV App 聚焦阴影下移 40 pt、模糊 50 pt; 系统阴影 20 dp 时模糊约 28 dp、下移约 10 dp). */
internal val TV_POSTER_WALL_FOCUSED_ELEVATION = 20.dp

/** 浅色档的阴影色: 浅底上投影显得重, 透明度减半 (Apple 浅色表里文字阴影也比深色轻一半以上). */
internal val TV_POSTER_WALL_SHADOW_COLOR_LIGHT = Color.Black.copy(alpha = 0.5f)

/** 玻璃边的不透明度: Apple TV App 卡片描边用的 hairline 色, 深色下是 8% 白. */
internal const val TV_POSTER_WALL_OUTLINE_ALPHA = 0.08f

/** 玻璃边的浅色档: Apple 浅色 hairline 是 10% 黑. */
internal const val TV_POSTER_WALL_OUTLINE_ALPHA_LIGHT = 0.1f

/** 没聚焦时番名的不透明度: Apple TV App 卡片标题静止用 LabelSecondary, 深色下是 50% 白 (聚焦时 LabelPrimary, 全亮). */
internal const val TV_POSTER_WALL_TITLE_IDLE_ALPHA = 0.5f

/** 没聚焦时番名的浅色档: Apple 浅色 LabelSecondary 是 60% 黑. */
internal const val TV_POSTER_WALL_TITLE_IDLE_ALPHA_LIGHT = 0.6f

/**
 * 浮在海报墙卡片上的顶栏控件 (追番页的标签行、搜索页的搜索词 / 筛选钮 / 已选筛选项) 的配色, 照 tvOS 顶部标签栏 (HIG Tab bars 与
 * tvOS 18 设计套件的取值): 整条是中性色的半透明玻璃胶囊加一圈细亮边 ([fill] / [edge]), 卡片从底下透出来; 没选中的标签字降到七成
 * ([idleContent]); 选中而没聚焦的垫一块半透明浅灰片、字全亮 ([selectedPlatter] / [selectedContent]); 聚焦的换成浅色实底配黑字
 * ([focusedPlatter] / [focusedContent]), 抬起一层投影、略放大 (见 [tvGlassFocusLift]). Compose 模糊不了底下的原生网格, 玻璃底取的是
 * 没有模糊时的那一档. 顶栏控件不叠 M3 焦点态层 (见 ProvideRingOnlyFocus): 浅色实底上晚到的一层叠色会闪.
 */
@Immutable
internal class TvGlassColors(
    val fill: Color,
    val edge: Color,
    val idleContent: Color,
    val selectedPlatter: Color,
    val selectedContent: Color,
    val focusedPlatter: Color,
    val focusedContent: Color,
)

/** 当前主题的顶栏玻璃配色 (见 [TvGlassColors]). */
@Composable
internal fun tvGlassColors(): TvGlassColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) TV_GLASS_COLORS_DARK else TV_GLASS_COLORS_LIGHT

/** 顶栏控件的玻璃底 (见 [TvGlassColors]). 只管常态: 聚焦时各控件自己换成 [TvGlassColors.focusedPlatter]. */
internal fun Modifier.tvGlassBackground(shape: Shape): Modifier = composed {
    val colors = tvGlassColors()
    background(colors.fill, shape).border(TV_GLASS_EDGE_WIDTH, colors.edge, shape)
}

/**
 * 顶栏玻璃控件聚焦时抬起: 略放大 ([TV_GLASS_FOCUS_SCALE]) 并投下一层影 (照 tvOS 聚焦的标签). 挂在控件底色之前, 投影画在底色下面;
 * 只动图层属性, 不重排. 失焦时投影当场撤掉, 放大按更短的时长缩回 (见 [tvGlassFocusSpec]): 系统阴影要降到 0 才完全没有, 跟着缩回的
 * 曲线拖尾, 看着就是焦点走了、原地还留着一圈影子. [snap] 见 [tvGlassFocusSpec].
 */
@Composable
internal fun Modifier.tvGlassFocusLift(focused: Boolean, shape: Shape, snap: Boolean = false): Modifier {
    val lift by animateFloatAsState(if (focused) 1f else 0f, tvGlassFocusSpec(focused, snap), label = "glassLift")
    return graphicsLayer {
        val scale = 1f + (TV_GLASS_FOCUS_SCALE - 1f) * lift
        scaleX = scale
        scaleY = scale
        shadowElevation = if (focused) TV_GLASS_FOCUS_ELEVATION.toPx() * lift else 0f
        this.shape = shape
        clip = false
    }
}

/**
 * 顶栏控件聚焦态过渡 (换底色、字色、抬起) 的规格: 得焦按 [TV_GLASS_FOCUS_MILLIS], 失焦按更短的 [TV_GLASS_UNFOCUS_MILLIS] ——
 * 照 tvOS 的焦点引擎, 焦点离开的那个比新拿到焦点的那个先落回去. 流畅档直接到位; [snap] 也直接到位 (进页 / 返回本页的落点, 见
 * [rememberTvFocusLandingWindow]).
 */
@Composable
internal fun <T> tvGlassFocusSpec(focused: Boolean, snap: Boolean = false): FiniteAnimationSpec<T> =
    if (snap) snap() else tvSwapSpec(tween(if (focused) TV_GLASS_FOCUS_MILLIS else TV_GLASS_UNFOCUS_MILLIS))

/** 顶栏控件得焦时换底色、抬起的时长. */
internal const val TV_GLASS_FOCUS_MILLIS = 150

/** 顶栏控件失焦时落回去的时长 (见 [tvGlassFocusSpec]). */
internal const val TV_GLASS_UNFOCUS_MILLIS = 90

/** 聚焦抬起伸出控件外的余量 (放大多出来的那截 + 投影): 装在会裁切的容器 (LazyRow 按主轴边界裁) 里时, 两头要让出这么多. */
internal val TV_GLASS_FOCUS_BLEED = 16.dp

private val TV_GLASS_EDGE_WIDTH = 1.dp
private const val TV_GLASS_FOCUS_SCALE = 1.08f
private val TV_GLASS_FOCUS_ELEVATION = 10.dp

private val TV_GLASS_COLORS_DARK = TvGlassColors(
    fill = Color(0xFF1E1E1E).copy(alpha = 0.5f),
    edge = Color.White.copy(alpha = 0.1f),
    idleContent = Color.White.copy(alpha = 0.7f),
    selectedPlatter = Color(0xFFD0D1D3).copy(alpha = 0.5f),
    selectedContent = Color.White,
    focusedPlatter = Color(0xFFD0D1D3),
    focusedContent = Color.Black,
)

private val TV_GLASS_COLORS_LIGHT = TvGlassColors(
    fill = Color(0xFF828282).copy(alpha = 0.5f),
    edge = Color.White.copy(alpha = 0.2f),
    idleContent = Color.Black.copy(alpha = 0.7f),
    selectedPlatter = Color.Black.copy(alpha = 0.37f),
    selectedContent = Color.White,
    focusedPlatter = Color.White,
    focusedContent = Color.Black,
)

@Composable
internal fun tvPosterWallTitleStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontSize = TV_POSTER_WALL_TITLE_FONT_SIZE,
    lineHeight = TV_POSTER_WALL_TITLE_LINE_HEIGHT,
)
