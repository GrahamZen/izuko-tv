/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.tv.TV_CARD_HERO_BACKDROP_GEOMETRY
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaPipelineState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeBackdropTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageCallbacks
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageHost
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroSource
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroText
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextSpan
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallBackdropTarget
import me.him188.ani.app.ui.foundation.tv.rememberTvTitleLogoLookup
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.tv.tvPageBackdropTreatment
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.search_tv_empty
import me.him188.ani.app.ui.search.isLoadingFirstPageOrRefreshing
import org.jetbrains.compose.resources.stringResource

/*
 * 搜索页结果态海报墙的原生视图: 页面管顶部行 / 筛选行 / 错误横幅、数据、hero 媒体流水线、网格送焦框架、返回键分层;
 * 背景图 / hero 文字 / 网格在 TvNativeGridPageView (接线见 TvNativeGridPageHost). 本文件只放搜索页自己的那部分: 卡片 (隐藏条目不出封面、
 * NSFW 打码)、hero 文字 (评分 + 标签行)、空结果提示与首屏加载.
 */

@Composable
internal fun TvSearchNativeGrid(
    state: TvNativeGridPageState,
    metrics: TvNativeGridPageMetrics,
    cardWidth: Dp,
    items: LazyPagingItems<SubjectPreviewItemInfo>,
    heroRaw: () -> SubjectPreviewItemInfo?,
    heroDisplay: () -> SubjectPreviewItemInfo?,
    heroText: () -> SubjectPreviewItemInfo?,
    heroPipeline: TvHeroMediaPipelineState,
    summaryCache: Map<Int, String>,
    fadeColor: Color,
    gridFocus: TvGridFocusState,
    farJump: () -> Boolean,
    onFarJumpConsumed: () -> Unit,
    callbacks: TvNativeGridPageCallbacks<SubjectPreviewItemInfo>,
    menuFor: (subjectId: Int) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    landingIndex: Int = -1,
) {
    val density = LocalDensity.current
    TvNativeGridPageHost(
        state = state,
        metrics = metrics,
        cardWidth = cardWidth,
        gridKey = 0,
        slideDirection = { _, _ -> 0 },
        items = items,
        cardsKey = null,
        cardOf = { info ->
            TvNativeCard(
                // 隐藏条目不显示封面 (占位); NSFW 模糊模式降采样打码
                imageUrl = info.takeIf { !it.hide }?.imageUrl,
                title = info.title,
                obscure = info.nsfwMode == NsfwMode.BLUR,
                subjectId = info.subjectId,
            )
        },
        source = null,
        fadeColor = fadeColor,
        treatment = remember(fadeColor) {
            tvPageBackdropTreatment(1f, topScrim = true, fadeColor = fadeColor, geometry = TV_CARD_HERO_BACKDROP_GEOMETRY)
        },
        gridFocus = gridFocus,
        farJump = farJump,
        onFarJumpConsumed = onFarJumpConsumed,
        callbacks = callbacks,
        menuFor = { info -> menuFor(info.subjectId) },
        modifier = modifier,
        landingIndex = landingIndex,
    ) {
        // 空结果提示 / 首屏加载指示, 在网格可见的那一段里居中
        if (items.itemCount == 0 && !items.loadState.hasError) {
            Box(
                Modifier.fillMaxSize().padding(
                    top = with(density) { metrics.gridTopPx.toDp() },
                    bottom = with(density) { metrics.grid.bottomBleedPx.toDp() },
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (items.isLoadingFirstPageOrRefreshing) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        stringResource(Lang.search_tv_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
    TvSearchNativeSource(state, heroPipeline, heroRaw, heroDisplay, heroText, summaryCache)
}

/** hero 内容交给原生 (停稳后的背景图 / 文字, 真实目标用来按下即压暗). 单独一个小组合: 只让这一块随换卡重组. */
@Composable
private fun TvSearchNativeSource(
    state: TvNativeGridPageState,
    heroPipeline: TvHeroMediaPipelineState,
    heroRaw: () -> SubjectPreviewItemInfo?,
    heroDisplay: () -> SubjectPreviewItemInfo?,
    heroText: () -> SubjectPreviewItemInfo?,
    summaryCache: Map<Int, String>,
) {
    val display = heroDisplay()
    val raw = heroRaw()
    val spec = display?.toHeroMediaSpec()
    val url = heroPipeline.backdropUrl(spec)
    val underlay = heroPipeline.underlayUrl(spec)
    val backdrop = if (url != null && display != null) {
        // NSFW 模糊模式: 同卡片降采样打码 (TMDB 图与封面兜底都算)
        TvNativeBackdropTarget(url, display.subjectId, underlay, obscure = display.nsfwMode == NsfwMode.BLUR)
    } else {
        null
    }
    // hero 态铺在整页底下的模糊背景 (设置里开了才用): 整部的横版背景图, 没有横版图时是竖版封面; 封面与打码的条目只铺模糊版, 不变清晰
    val wall = if (display != null && spec != null) {
        heroPipeline.seriesBackdropUrl(spec)?.let {
            TvNativeWallBackdropTarget(it, display.subjectId, sharp = it != spec.coverUrl && display.nsfwMode != NsfwMode.BLUR)
        }
    } else {
        null
    }
    val text = heroText()?.let { tvSearchNativeHeroText(it, summaryCache) }
    val source = TvNativeHeroSource(
        backdrop = backdrop,
        dimming = raw?.subjectId != display?.subjectId,
        rawSubjectId = raw?.subjectId,
        text = text,
        wall = wall,
    )
    val view = state.view
    SideEffect { view?.setSource(source) }
}

/** hero 文字: 标题 (至多两行); ★评分 + 标签行 (开播季度 · 话数 · 类型); 简介 (Bangumi 兜底表). */
@Composable
private fun tvSearchNativeHeroText(hero: SubjectPreviewItemInfo, summaryCache: Map<Int, String>): TvNativeHeroText {
    val secondary = tvHeroSecondaryContentColor().toArgb()
    // 进程级共享表, 邻居预取会写进来: 收进 derivedStateOf, 写入别的条目时不重组
    val summary by remember(hero.subjectId) { derivedStateOf { summaryCache[hero.subjectId].orEmpty() } }
    val logo = rememberTvTitleLogoLookup(hero.subjectId, hero.originalName)
    return TvNativeHeroText(
        subjectId = hero.subjectId,
        title = hero.title,
        infoReady = true,
        rating = hero.rating.score.takeIf { (it.toFloatOrNull() ?: 0f) > 0f },
        meta = if (hero.tags.isBlank()) emptyList() else listOf(TvNativeTextSpan(hero.tags, secondary)),
        summary = summary,
        logo = logo.logo,
        logoPending = logo.pending,
    )
}
