/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import me.him188.ani.app.ui.foundation.isAutoRepeat
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallTone
import me.him188.ani.app.ui.foundation.tv.TV_WALL_PREVIEW_GROUPS
import me.him188.ani.app.ui.foundation.tv.TvPosterWallToneSource
import me.him188.ani.app.ui.foundation.tv.TvWallPreviewEntry
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreItem
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreListener
import me.him188.ani.app.ui.foundation.tv.rememberTvWallPreviewSamples
import me.him188.ani.app.ui.foundation.tv.tvWallPreviewSubjectId
import me.him188.ani.app.ui.foundation.tv.tvWallPreviewTextColors
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_continue_watching
import org.jetbrains.compose.resources.stringResource

/**
 * 「海报墙大小」里探索页的假页面: 与 [TvExplorationPage] 同一个原生海报墙、同一份几何 ([tvExplorationWallLayout]), 数据换成示例 (有字没图):
 * 热门轮播几页、「继续观看」一行 (带观看进度)、几组推荐各一行. 焦点照真页面走 (轮播按钮 ↔ 各行、按确定进 hero 态、返回退回卡片墙),
 * 只是 hero 态里再按确定不进详情页, 轮播按钮也不跳转.
 *
 * [entry] 见 [TvWallPreviewEntry]; 按左出页面 (轮播第一页的按钮 / 行首) 与卡片墙上按返回都交还左边的滑块 ([onExitToRail]).
 */
@Composable
internal fun TvExplorationWallPreview(
    entry: TvWallPreviewEntry,
    onExitToRail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nativeState = rememberTvExplorationNativeState()
    val wallLayout = tvExplorationWallLayout()
    val samples = rememberTvWallPreviewSamples()
    val (secondary, onSurface) = tvWallPreviewTextColors()
    val followedTitle = stringResource(Lang.exploration_continue_watching)
    val columns = wallLayout.columns

    // 各行的示例卡: 条目号按全页的序号排, 行与行之间不重复 (hero 内容按条目号认)
    val rows: List<Pair<String, Int>> = remember(columns) {
        buildList {
            add(TV_FOLLOWED_ROW_KEY to TV_WALL_PREVIEW_FOLLOWED_CARDS)
            repeat(TV_WALL_PREVIEW_GROUPS) { add(tvRecRowKey(it) to columns * 2) }
        }
    }
    val rowStart: Map<String, Int> = remember(rows) {
        var next = TV_WALL_PREVIEW_CAROUSEL_SIZE
        rows.associate { (key, count) -> (key to next).also { next += count } }
    }
    val items: List<TvNativeExploreItem> = remember(rows, rowStart, samples, followedTitle) {
        buildList {
            add(TvNativeExploreItem.Spacer(TV_WALL_HERO_SPACER_KEY))
            rows.forEachIndexed { rowIndex, (key, count) ->
                val start = rowStart.getValue(key)
                val followed = key == TV_FOLLOWED_ROW_KEY
                add(TvNativeExploreItem.Header(if (followed) TV_FOLLOWED_HEADER_KEY else tvRecHeaderKey(rowIndex - 1), if (followed) followedTitle else samples.groupTitle(rowIndex - 1)))
                add(
                    TvNativeExploreItem.Row(
                        key,
                        List(count) { i ->
                            // 继续观看的卡带一条观看进度, 长短不一
                            samples.card(start + i, progress = if (followed) TV_WALL_PREVIEW_PROGRESS[i % TV_WALL_PREVIEW_PROGRESS.size] else null)
                        },
                    ),
                )
            }
        }
    }

    // 焦点簿记 (同真页面, 见 TvExplorationPage): 聚焦的行与卡、上次停的 hero 按钮, 两个显式落点请求
    var focusedRowKey by remember { mutableStateOf<String?>(null) }
    var focusedCardIndex by remember { mutableIntStateOf(0) }
    var lastHeroButton by remember { mutableStateOf(TvHeroFocusButton.PRIMARY) }
    var cardFocusRequest by remember { mutableStateOf<TvCardFocusRequest?>(null) }
    var heroFocusRequest by remember { mutableStateOf<TvHeroFocusRequest?>(null) }
    var carouselIndex by remember { mutableIntStateOf(0) }
    // 焦点在本页里 (卡片 / 轮播按钮): 返回键据此分层
    var inPage by remember { mutableStateOf(false) }
    val currentOnExitToRail by rememberUpdatedState(onExitToRail)

    // 两路 hero 内容: 轮播那一页 / 聚焦的卡. 都没有背景图
    val carouselId = tvWallPreviewSubjectId(carouselIndex)
    val carouselSource = samples.heroSource(carouselId, samples.titleAt(carouselIndex), secondary, onSurface)
    val cardSource = focusedRowKey?.let { key ->
        val index = rowStart.getValue(key) + focusedCardIndex
        samples.heroSource(tvWallPreviewSubjectId(index), samples.titleAt(index), secondary, onSurface)
    } ?: carouselSource
    val view = nativeState.view
    SideEffect { view?.setSources(carouselSource, cardSource) }

    // 整屏底色 (预览页根画, 见 TvPosterWallScalePage): 黑度与轮播分界线由原生视图逐帧写进来, 同真页面
    val wallTone = LocalTvPosterWallTone.current
    TvPosterWallToneSource(wallTone, amount = { nativeState.tone }, heroBottom = { nativeState.splitY })

    val listener = remember {
        object : TvNativeExploreListener {
            override fun onCardFocused(rowKey: String, index: Int, column: Int) {
                focusedRowKey = rowKey
                focusedCardIndex = index
                if (cardFocusRequest?.let { it.rowKey == rowKey } == true) cardFocusRequest = null
            }

            // 预览: hero 态里按确定不进详情页
            override fun onCardClick(rowKey: String, index: Int) = Unit

            override fun onCardLongPress(rowKey: String, index: Int, anchor: AndroidRect) = Unit

            override fun onBindCard(rowKey: String, index: Int) = Unit

            override fun onHeroButtonFocused(button: Int) {
                focusedRowKey = null
                lastHeroButton = if (button == 1) TvHeroFocusButton.SCHEDULE else TvHeroFocusButton.PRIMARY
                if (heroFocusRequest?.button == lastHeroButton) heroFocusRequest = null
            }

            override fun onHeroButtonClick(button: Int) = Unit

            override fun onSwitchCarousel(delta: Int): Boolean {
                // 第一页按左不翻: 交给左边的滑块 (真页面是交给侧边栏)
                if (delta < 0 && carouselIndex <= 0) return false
                carouselIndex = (carouselIndex + delta).mod(TV_WALL_PREVIEW_CAROUSEL_SIZE)
                return true
            }

            override fun onExitLeft() = currentOnExitToRail()

            override fun onHeroActiveChanged(active: Boolean) = Unit

            override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit

            override fun onToneChanged(tone: Float, splitY: Float) = Unit

            override fun onScrollingChanged(scrolling: Boolean) = Unit
        }
    }

    Box(
        modifier.fillMaxSize()
            .onFocusChanged { inPage = it.hasFocus }
            // 用户的方向 / 确认键先取消还没送到的程序化落点 (同真页面)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isAutoRepeat != true && event.key in TV_EXPLORATION_NAV_KEYS) {
                    cardFocusRequest = null
                    heroFocusRequest = null
                }
                false
            },
    ) {
        TvExplorationNativeWall(
            state = nativeState,
            metrics = wallLayout.metrics,
            cardWidth = wallLayout.cardWidth,
            items = items,
            carouselCount = TV_WALL_PREVIEW_CAROUSEL_SIZE,
            carouselIndex = { carouselIndex },
            fadeColor = wallTone?.heroColor ?: AniThemeDefaults.shellBackgroundColor,
            geometry = TV_WALL_BACKDROP_GEOMETRY,
            splitGate = { wallTone?.splitGate() ?: 1f },
            listener = listener,
            cardFocusRequest = { cardFocusRequest },
            heroFocusRequest = { heroFocusRequest },
            farJumpRow = { null },
            focusedRowKey = { focusedRowKey },
            focusedCardIndex = { focusedCardIndex },
            menuFor = { { _, _ -> } },
        )
    }

    // 返回键: hero 态里先回卡片墙, 否则交还滑块 (登记在后, 优先于预览页离开本页的那一层)
    BackHandler(enabled = inPage && !nativeState.heroActive) { currentOnExitToRail() }
    BackHandler(enabled = inPage && nativeState.heroActive) { nativeState.exitHero() }

    // 从滑块进来: 回上次停的那张卡, 没停过卡就回上次那颗轮播按钮 (首次 = 立即观看)
    val scale = LocalTvPosterWallScale.current
    LaunchedEffect(entry, scale) {
        entry.awaitFor(scale) {
            val row = focusedRowKey
            if (row != null) {
                cardFocusRequest = TvCardFocusRequest(row, focusedCardIndex)
            } else {
                heroFocusRequest = TvHeroFocusRequest(lastHeroButton)
            }
        }
    }
}

/** 热门轮播的页数. */
private const val TV_WALL_PREVIEW_CAROUSEL_SIZE = 5

/** 「继续观看」那一行有几张. */
private const val TV_WALL_PREVIEW_FOLLOWED_CARDS = 10

/** 「继续观看」卡上的观看进度, 轮着用. */
private val TV_WALL_PREVIEW_PROGRESS = floatArrayOf(0.3f, 0.75f, 0.1f, 0.5f, 0.9f)
