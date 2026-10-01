/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvGridFocus
import me.him188.ani.app.ui.foundation.TV_CONFIRM_KEYS
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallTone
import me.him188.ani.app.ui.foundation.tv.TV_CARD_HERO_BACKDROP_GEOMETRY
import me.him188.ani.app.ui.foundation.tv.TvPosterWallToneSource
import me.him188.ani.app.ui.foundation.tv.TvWallPreviewEntry
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageCallbacks
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageHost
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeGridPageState
import me.him188.ani.app.ui.foundation.tv.rememberTvWallPreviewSamples
import me.him188.ani.app.ui.foundation.tv.tvPageBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.tvWallPreviewSubjectId
import me.him188.ani.app.ui.foundation.tv.tvWallPreviewTextColors
import me.him188.ani.datasources.api.topic.UnifiedCollectionType

/**
 * 「海报墙大小」里追番页的假页面: 与 [TvCollectionPage] 同一套玻璃标签栏、同一个原生海报墙、同一份几何 ([tvCollectionWallLayout]), 数据换成示例
 * (有字没图, 每个标签都是同一批卡). 焦点照真页面走 (标签 ↔ 网格、行缘换标签、按确定进 hero 态、返回退回卡片墙), 只是 hero 态里再按确定不进详情页,
 * 长按也不弹收藏菜单.
 *
 * [entry] 见 [TvWallPreviewEntry]; 第一个标签往左、卡片墙上按返回都交还左边的滑块 ([onExitToRail]).
 */
@Composable
internal fun TvCollectionWallPreview(
    entry: TvWallPreviewEntry,
    onExitToRail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nativeState = rememberTvNativeGridPageState()
    val wallLayout = tvCollectionWallLayout()
    val samples = rememberTvWallPreviewSamples()
    val (secondary, onSurface) = tvWallPreviewTextColors()
    val tabs = rememberTvCollectionTabOrder()
    var selectedTab by remember { mutableStateOf(tabs.firstOrNull { it == UnifiedCollectionType.DOING } ?: tabs.first()) }
    val tabRequesters = remember(tabs) { tabs.associateWith { FocusRequester() } }
    val focus = rememberTvFocusScope()
    val gridFocus = rememberTvGridFocus(focus)
    var focusedIndex by remember { mutableIntStateOf(-1) }
    // 焦点在本页里 (标签 / 卡片): 返回键据此分层
    var inPage by remember { mutableStateOf(false) }
    val currentOnExitToRail by rememberUpdatedState(onExitToRail)

    // 示例卡 (带观看进度, 同追番页); 列表项全是 null: 长按弹不出菜单、hero 态里按确定不进详情页 (见 TvNativeGridPageHost)
    val cards = remember(samples) {
        List(TV_WALL_PREVIEW_GRID_CARDS) { samples.card(it, progress = TV_WALL_PREVIEW_PROGRESS[it % TV_WALL_PREVIEW_PROGRESS.size]) }
    }
    val items = remember { List<Int?>(TV_WALL_PREVIEW_GRID_CARDS) { null } }

    // hero 内容: 聚焦的那张卡, 没有背景图
    val source = focusedIndex.takeIf { it >= 0 }?.let {
        samples.heroSource(tvWallPreviewSubjectId(it), samples.titleAt(it), secondary, onSurface)
    }
    val view = nativeState.view
    SideEffect { if (source != null) view?.setSource(source) }

    // 整屏底色 (预览页根画, 见 TvPosterWallScalePage): 黑度由原生视图逐帧写进来, 同真页面
    val wallTone = LocalTvPosterWallTone.current
    TvPosterWallToneSource(wallTone) { nativeState.tone }
    val fadeColor = wallTone?.heroColor ?: AniThemeDefaults.shellBackgroundColor

    Box(
        modifier.fillMaxSize()
            .onFocusChanged { inPage = it.hasFocus }
            // 方向 / 确认键即取消在途送焦 (同真页面)
            .tvFocusNavSignal(focus)
            // hero 态里按确定不进详情页: 确认键当场吞掉. 交给原生视图的话, 开着模糊背景时它先放「点开」(卡片淡没、等背景对焦), 这里没有图
            // 也没有详情页可进, 就停在那一半
            .onPreviewKeyEvent { event -> nativeState.heroActive && event.key in TV_CONFIRM_KEYS },
    ) {
        TvNativeGridPageHost(
            state = nativeState,
            metrics = wallLayout.metrics,
            cardWidth = wallLayout.cardWidth,
            gridKey = tabs.indexOf(selectedTab),
            slideDirection = { from, to -> if (to > from) 1 else -1 },
            items = items,
            cards = cards,
            focusableCount = { cards.size },
            heroEnabled = true,
            badge = null,
            fadeColor = fadeColor,
            treatment = remember(fadeColor) {
                tvPageBackdropTreatment(1f, topScrim = true, fadeColor = fadeColor, geometry = TV_CARD_HERO_BACKDROP_GEOMETRY)
            },
            gridFocus = gridFocus,
            farJump = { false },
            onFarJumpConsumed = {},
            callbacks = TvNativeGridPageCallbacks(
                onCardFocused = { index, _ -> focusedIndex = index },
                onCardClick = { _, _ -> },
                onTopRowUp = { runCatching { tabRequesters.getValue(selectedTab).requestFocus() }.isSuccess },
                onRowEdge = { direction, row ->
                    // 行缘换标签 (同真页面): 行末按右 → 右边标签同一行行首, 行首按左对称; 第一个标签行首按左交还滑块
                    val i = tabs.indexOf(selectedTab)
                    when {
                        direction > 0 -> {
                            if (i < tabs.lastIndex) {
                                gridFocus.focusRowEdge(row, direction = 1)
                                selectedTab = tabs[i + 1]
                            }
                        }

                        i > 0 -> {
                            gridFocus.focusRowEdge(row, direction = -1)
                            selectedTab = tabs[i - 1]
                        }

                        else -> currentOnExitToRail()
                    }
                    true
                },
                onGridFocusChanged = {},
                onScrollingChanged = {},
            ),
            menuFor = { { _, _ -> } },
        )

        // 页面从屏幕左缘铺起, 左边让开 (换成滑块的) 侧边栏, 同真页面
        Column(Modifier.fillMaxSize().padding(start = TvNavigationRailDefaults.CollapsedWidth + TV_COLLECTION_START_PAD, top = TV_COLLECTION_TOP_PAD)) {
            TvCollectionGlassTabBar(Modifier.height(TV_COLLECTION_TAB_ROW_HEIGHT).graphicsLayer { alpha = 1f - nativeState.wallFade }) {
                tabs.forEachIndexed { index, type ->
                    key(type) {
                        val interactionSource = remember { MutableInteractionSource() }
                        var focused by remember { mutableStateOf(false) }
                        TvCollectionGlassTab(
                            label = type.displayTextTv(),
                            selected = type == selectedTab,
                            focused = focused,
                            count = TV_WALL_PREVIEW_TAB_COUNTS[index % TV_WALL_PREVIEW_TAB_COUNTS.size],
                            modifier = Modifier
                                .focusRequester(tabRequesters.getValue(type))
                                .onFocusChanged {
                                    focused = it.isFocused
                                    // 聚焦即选中, 同真页面
                                    if (it.isFocused) selectedTab = type
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyDown) gridFocus.focusRowEdge(0, direction = 1)
                                    true
                                }
                                .clickable(interactionSource, indication = null) {},
                        )
                    }
                }
            }
        }
    }

    // 返回键: hero 态里先回卡片墙, 否则交还滑块 (登记在后, 优先于预览页离开本页的那一层)
    BackHandler(enabled = inPage && !nativeState.heroActive) { currentOnExitToRail() }
    BackHandler(enabled = inPage && nativeState.heroActive) { nativeState.exitHero() }

    // 从滑块进来: 回上次停的那张卡 (没停过是第一张)
    val scale = LocalTvPosterWallScale.current
    LaunchedEffect(entry, scale) {
        entry.awaitFor(scale) { gridFocus.focusItem(focusedIndex.coerceAtLeast(0)) }
    }
}

/** 网格里有几张示例卡 (一个标签). */
private const val TV_WALL_PREVIEW_GRID_CARDS = 30

/** 示例卡上的观看进度, 轮着用. */
private val TV_WALL_PREVIEW_PROGRESS = floatArrayOf(0.3f, 0.75f, 0.1f, 0.5f, 0.9f)

/** 各标签后面的示例数字, 按标签的显示顺序轮着用. */
private val TV_WALL_PREVIEW_TAB_COUNTS = intArrayOf(12, 30, 3, 86, 5)
