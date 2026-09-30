/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvGridFocus
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

/**
 * 「海报墙大小」里搜索页的假页面: 结果态 —— 与 [TvSearchPage] 同一个顶部行、同一个原生海报墙、同一份几何 ([tvSearchWallLayout]), 数据换成示例
 * (有字没图). 焦点照真页面走 (顶部行 ↔ 网格、按确定进 hero 态、返回退回卡片墙), 只是顶部行不回输入态也不开筛选, hero 态里再按确定不进详情页.
 *
 * [entry] 见 [TvWallPreviewEntry]; 行首往左、卡片墙上按返回都交还左边的滑块 ([onExitToRail]).
 */
@Composable
internal fun TvSearchWallPreview(
    entry: TvWallPreviewEntry,
    onExitToRail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nativeState = rememberTvNativeGridPageState()
    val wallLayout = tvSearchWallLayout(hasFilters = false)
    val samples = rememberTvWallPreviewSamples()
    val (secondary, onSurface) = tvWallPreviewTextColors()
    val titleFocusRequester = remember { FocusRequester() }
    val focus = rememberTvFocusScope()
    val gridFocus = rememberTvGridFocus(focus)
    var focusedIndex by remember { mutableIntStateOf(-1) }
    // 焦点在本页里 (顶部行 / 卡片): 返回键据此分层
    var inPage by remember { mutableStateOf(false) }
    val currentOnExitToRail by rememberUpdatedState(onExitToRail)

    // 列表项全是 null: 长按弹不出菜单、hero 态里按确定不进详情页 (见 TvNativeGridPageHost)
    val cards = remember(samples) { List(TV_WALL_PREVIEW_RESULTS) { samples.card(it) } }
    val items = remember { List<Int?>(TV_WALL_PREVIEW_RESULTS) { null } }

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
            .tvFocusNavSignal(focus),
    ) {
        TvNativeGridPageHost(
            state = nativeState,
            metrics = wallLayout.metrics,
            cardWidth = wallLayout.cardWidth,
            gridKey = 0,
            slideDirection = { _, _ -> 0 },
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
                onTopRowUp = { runCatching { titleFocusRequester.requestFocus() }.isSuccess },
                // 行首按左交还滑块 (真页面是进侧边栏); 行尾按右由网格吞掉
                onRowEdge = { direction, _ ->
                    if (direction < 0) {
                        currentOnExitToRail()
                        true
                    } else {
                        false
                    }
                },
                onGridFocusChanged = {},
                onScrollingChanged = {},
            ),
            menuFor = { { _, _ -> } },
        )

        // 页面从屏幕左缘铺起, 左边让开 (换成滑块的) 侧边栏, 同真页面
        Column(Modifier.fillMaxSize().padding(start = TvNavigationRailDefaults.CollapsedWidth + TV_SEARCH_START_PAD, top = TV_SEARCH_TOP_PAD)) {
            TvSearchTopRow(
                modifier = Modifier.height(TV_SEARCH_TOP_ROW_HEIGHT).graphicsLayer { alpha = 1f - nativeState.wallFade },
                keywords = samples.query,
                hasFilters = false,
                titleFocusRequester = titleFocusRequester,
                onEditQuery = {},
                onOpenFilter = {},
                onNavigateDown = {
                    gridFocus.focusItem(0)
                    true
                },
                onFallbackFocused = {},
            )
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

/** 示例搜索结果的张数. */
private const val TV_WALL_PREVIEW_RESULTS = 30
