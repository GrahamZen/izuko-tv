/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.exploration.TvExplorationWallPreview
import me.him188.ani.app.ui.exploration.search.TvSearchWallPreview
import me.him188.ani.app.ui.exploration.tvExplorationWallContentWidth
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.session.LocalTvRailEnter
import me.him188.ani.app.ui.foundation.session.TV_RAIL_ITEM_SIZE
import me.him188.ani.app.ui.foundation.session.tvRailScrimFeather
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallTone
import me.him188.ani.app.ui.foundation.tv.ProvideTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.TvPosterWallScaled
import me.him188.ani.app.ui.foundation.tv.TvPosterWallTheme
import me.him188.ani.app.ui.foundation.tv.TvWallPreviewEntry
import me.him188.ani.app.ui.foundation.tv.rememberTvPosterWallTone
import me.him188.ani.app.ui.foundation.tv.tvGridPageWallContentWidth
import me.him188.ani.app.ui.foundation.tv.tvPosterWallBackground
import me.him188.ani.app.ui.foundation.tv.tvPosterWallScaleStep
import me.him188.ani.app.ui.foundation.tv.tvPosterWallScaleStops
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_wall_scale_hint_adjust
import me.him188.ani.app.ui.lang.tv_wall_scale_hint_back
import me.him188.ani.app.ui.lang.tv_wall_scale_hint_preview
import me.him188.ani.app.ui.lang.tv_wall_scale_hint_try
import me.him188.ani.app.ui.lang.tv_wall_scale_title
import me.him188.ani.app.ui.subject.collection.TvCollectionWallPreview
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** 「海报墙大小」预览的是哪一页 (见 [TvPosterWallScalePage]). */
enum class TvPosterWallPreviewPage {
    EXPLORATION,
    COLLECTION,
    SEARCH,
}

/**
 * 「海报墙大小」页: [page] 那一页 (探索 / 追番 / 搜索) 的**假页面**, 左边的侧边栏换成一根竖着的滑块, 边调边看.
 *
 * 假页面与真页面同一套原生海报墙、同一个几何函数, 卡片按调出来的值缩放 (见 [TvPosterWallScaled]), 所以这里是什么样, 进那一页就是什么样;
 * 数据是示例 (有字没图). 焦点能进去走一走 (按确定进 hero 态、返回退回卡片墙), 只是进不了详情页.
 *
 * 按键: 滑块上 上 / 下 调大小 (一格 = 换一种每排张数, 见 [tvPosterWallScaleStops]), 右 / 确认 进页面; 页面里行首往左、卡片墙上按返回
 * 回到滑块; 滑块上按返回离开本页. 三页调的是同一个值 ([ThemeSettings.tvPosterWallScale]).
 *
 * 入口是三页动作面板里的「海报墙大小」: 本页作为窗口盖在那一页上 (见 [TvAdjustWindows]). 调的时候只改草稿,
 * **离开本页那一刻才写一次** ([onCommit] 在写之前报出要写的值) —— 一格一写会让底下的真页面跟着一格一格重建.
 */
@Composable
fun TvPosterWallScalePage(
    page: TvPosterWallPreviewPage,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCommit: (scale: Float) -> Unit = {},
) {
    val settings = remember { GlobalKoin.get<SettingsRepository>() }
    val scope = rememberCoroutineScope()

    // 进页读一次, 在组合里同步读 (第一帧就按它把假页面画全, 不先空一帧): 本页是这份设置的唯一写入方, 再订阅回来只会跟自己的编辑打架.
    // 按百分数存着, 免得算出 0.8500001
    val initialPercent = (LocalThemeSettings.current.effectivePosterWallScale * 100).roundToInt()
    var percent by remember { mutableIntStateOf(initialPercent) }
    // 设置里此刻存着的那个值: 离开时与它不同才写
    var savedPercent by remember { mutableIntStateOf(initialPercent) }
    fun commit() {
        val value = percent
        if (value == savedPercent) return
        savedPercent = value
        onCommit(value / 100f)
        // 不随本页的组合一起取消: 写入正发生在离开的那一刻
        scope.launch(NonCancellable) { settings.themeSettings.update { copy(tvPosterWallScale = value / 100f) } }
    }
    // 不是按返回离开的 (窗口被 Web 控制台跳页撤掉、Activity 重建) 也照样写
    DisposableEffect(Unit) { onDispose { commit() } }
    // 假页面按这个值画: 缩放一变假页面要整个重建 (原生视图的卡片尺寸建好就定了), 按住上 / 下连着调时等停一下再重建
    var previewPercent by remember { mutableIntStateOf(initialPercent) }
    LaunchedEffect(Unit) {
        snapshotFlow { percent }.collectLatest { p ->
            if (previewPercent != p) delay(TV_WALL_SCALE_PREVIEW_SETTLE_MILLIS)
            previewPercent = p
        }
    }

    // 档位按这一页卡片区的宽度算 (三页一样宽, 列数的分界也就一样)
    val density = LocalDensity.current
    val contentWidth = when (page) {
        TvPosterWallPreviewPage.EXPLORATION -> tvExplorationWallContentWidth()
        TvPosterWallPreviewPage.COLLECTION, TvPosterWallPreviewPage.SEARCH -> tvGridPageWallContentWidth()
    }
    val stops = remember(density, contentWidth) {
        density.tvPosterWallScaleStops(contentWidth, TV_WALL_SCALE_MIN_PERCENT, TV_WALL_SCALE_MAX_PERCENT, TV_WALL_SCALE_STEP_PERCENT)
    }
    fun step(delta: Int) {
        percent = density.tvPosterWallScaleStep(contentWidth, stops, percent, delta) ?: return
    }

    val sliderFocus = remember { FocusRequester() }
    val entry = remember { TvWallPreviewEntry() }
    fun enterPage() {
        val target = percent
        // 进之前先让假页面按滑块上的值画好 (还在等停下再重建的那一截直接跳过), 请求只交给那一份
        previewPercent = target
        entry.request(target / 100f)
    }

    // 最先登记, 优先级最低: 焦点在假页面里时由假页面自己的返回规则先接 (hero 态回卡片墙, 否则回滑块)
    BackHandler {
        commit()
        onNavigateBack()
    }

    // 整屏底色: 同真页面由页面登记黑度 (见 TvPosterWallTone), 但 hero 的底也取卡片墙那档灰 —— 假页面没有图, 真页面上被图盖住的那几样
    // (探索页轮播那块近黑的底、hero 态压黑、背景图的遮罩与按下即压暗, 都画成 hero 的底色) 在这里只会是一块突然出现的黑, 同色就都看不见了
    val wallColor = tvPosterWallBackground()
    val tone = rememberTvPosterWallTone(wall = wallColor, hero = wallColor, wallPage = true)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().graphicsLayer {}.drawBehind { tone.drawBackground(this) })
        val scalePercent = previewPercent
        CompositionLocalProvider(
            LocalTvPosterWallTone provides tone,
            LocalTvRailEnter provides sliderFocus,
        ) {
            // 同真页面外面那两层 (见 TvPageVariants): 滚动信号、海报墙配色
            ProvideTvScrollActivity {
                TvPosterWallTheme {
                    key(scalePercent) {
                        TvPosterWallScaled(scalePercent / 100f) {
                            val exitToRail: () -> Unit = { runCatching { sliderFocus.requestFocus() } }
                            when (page) {
                                TvPosterWallPreviewPage.EXPLORATION -> TvExplorationWallPreview(entry, exitToRail)
                                TvPosterWallPreviewPage.COLLECTION -> TvCollectionWallPreview(entry, exitToRail)
                                TvPosterWallPreviewPage.SEARCH -> TvSearchWallPreview(entry, exitToRail)
                            }
                        }
                    }
                }
            }
        }
        TvPosterWallScaleRail(
            percent = percent,
            onStep = ::step,
            onEnterPage = ::enterPage,
            focusRequester = sliderFocus,
            scrimPainter = { tone.drawBackground(this) },
        )
        // 进页焦点在滑块上
        LaunchedEffect(Unit) { runCatching { sliderFocus.requestFocus() } }
    }
}

/**
 * 替代侧边栏的那一列: 竖着的滑块 (与侧边栏图标同一条中线, 收起时同样只占 48dp), 上面写着当前百分比. 聚焦时像侧边栏那样展开一块底板,
 * 写上标题与按键说明. 越往上越大; 100% 那一格画一道短刻度.
 */
@Composable
private fun TvPosterWallScaleRail(
    percent: Int,
    onStep: (Int) -> Unit,
    onEnterPage: () -> Unit,
    focusRequester: FocusRequester,
    scrimPainter: DrawScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
        // 展开底板: 同侧边栏 (整屏底色 + 右缘羽化), 盖住页面左边一截, 字才看得清
        AnimatedVisibility(focused, Modifier.fillMaxHeight(), enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxHeight().width(TV_WALL_SCALE_PANEL_WIDTH)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawBehind {
                        scrimPainter()
                        drawRect(tvRailScrimFeather(Color.Black), blendMode = BlendMode.DstIn)
                    },
            )
        }
        Row(
            Modifier.padding(start = TV_WALL_SCALE_RAIL_START),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TV_WALL_SCALE_RAIL_START),
        ) {
            Column(Modifier.width(TV_RAIL_ITEM_SIZE), horizontalAlignment = Alignment.CenterHorizontally) {
                val labelColor by animateColorAsState(
                    if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "$percent%",
                    color = labelColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.height(8.dp))
                TvPosterWallScaleTrack(
                    percent = percent,
                    focused = focused,
                    modifier = Modifier
                        .width(TV_RAIL_ITEM_SIZE)
                        .height(TV_WALL_SCALE_TRACK_HEIGHT)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            when (event.key) {
                                // 连发也照走: 按住一路调
                                Key.DirectionUp -> {
                                    if (event.type == KeyEventType.KeyDown) onStep(1)
                                    true
                                }

                                Key.DirectionDown -> {
                                    if (event.type == KeyEventType.KeyDown) onStep(-1)
                                    true
                                }

                                Key.DirectionRight -> {
                                    if (event.type == KeyEventType.KeyDown) onEnterPage()
                                    true
                                }

                                // 确认键在抬起时进: 按下时就把焦点送进页面的话, 抬起会落到页面里那颗按钮 / 那张卡上
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    if (event.type == KeyEventType.KeyUp) onEnterPage()
                                    true
                                }

                                // 左边没有东西了
                                Key.DirectionLeft -> true
                                else -> false
                            }
                        }
                        .focusable(),
                )
            }
            if (focused) {
                Column(Modifier.widthIn(max = TV_WALL_SCALE_TEXT_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(Lang.tv_wall_scale_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "$percent%",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    val hintColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    for (hint in listOf(Lang.tv_wall_scale_hint_adjust, Lang.tv_wall_scale_hint_try, Lang.tv_wall_scale_hint_back)) {
                        Text(stringResource(hint), color = hintColor, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(Lang.tv_wall_scale_hint_preview),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** 竖着的滑块本体: 一条细轨, 下面到滑块那一截是实的, 100% 处一道短刻度, 滑块是个圆点 (聚焦时大一圈、换主题色). */
@Composable
private fun TvPosterWallScaleTrack(
    percent: Int,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val active by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val tick = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val thumbRadius by animateDpAsState(if (focused) 9.dp else 6.dp)
    Box(
        modifier.drawBehind {
            val x = size.width / 2
            val pad = 9.dp.toPx()
            val top = pad
            val bottom = size.height - pad
            fun yOf(p: Int): Float {
                val f = (p - TV_WALL_SCALE_MIN_PERCENT).toFloat() / (TV_WALL_SCALE_MAX_PERCENT - TV_WALL_SCALE_MIN_PERCENT)
                return bottom - f * (bottom - top)
            }
            val y = yOf(percent)
            val stroke = 4.dp.toPx()
            drawLine(inactive, Offset(x, top), Offset(x, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(active, Offset(x, y), Offset(x, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
            val tickY = yOf(100)
            val tickHalf = 7.dp.toPx()
            drawLine(tick, Offset(x - tickHalf, tickY), Offset(x + tickHalf, tickY), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(active, radius = thumbRadius.toPx(), center = Offset(x, y))
        },
    )
}

/** 百分数表示的缩放范围与步进 (与 [ThemeSettings] 的 POSTER_WALL_SCALE_* 一致). */
private val TV_WALL_SCALE_MIN_PERCENT = (ThemeSettings.POSTER_WALL_SCALE_MIN * 100).roundToInt()
private val TV_WALL_SCALE_MAX_PERCENT = (ThemeSettings.POSTER_WALL_SCALE_MAX * 100).roundToInt()
private val TV_WALL_SCALE_STEP_PERCENT = (ThemeSettings.POSTER_WALL_SCALE_STEP * 100).roundToInt()

/** 连着调时, 停下这么久才按新值重建假页面. */
private const val TV_WALL_SCALE_PREVIEW_SETTLE_MILLIS = 150L

/** 滑块那一列离屏幕左缘 (同侧边栏图标: 16dp 起, 32dp 宽, 中线在 32dp). */
private val TV_WALL_SCALE_RAIL_START = 16.dp

/** 滑块的高度 (不含上面的百分比). */
private val TV_WALL_SCALE_TRACK_HEIGHT = 240.dp

/** 展开底板的宽度 (含右缘羽化). */
private val TV_WALL_SCALE_PANEL_WIDTH = 340.dp

/** 展开时说明文字的最大宽度. */
private val TV_WALL_SCALE_TEXT_MAX_WIDTH = 220.dp
