/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.episode.preferredDisplayName
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo
import me.him188.ani.app.data.models.recommend.preferredDisplayName
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.FollowedSubjectInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.preferredDisplayName
import me.him188.ani.app.data.models.subject.subjectInfo
import me.him188.ani.app.data.recommendation.RecommendationGroup
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.app.tools.WeekFormatter
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.stateOf
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.LocalTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_HERO_SPLIT_BAND
import me.him188.ani.app.ui.foundation.tv.TvFocusRing
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaPipelineState
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.TvNextEpisodeMedia
import me.him188.ani.app.ui.foundation.tv.TvPageBackdropGeometry
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_HERO_BLUR_BRIGHT_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_HERO_BLUR_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeBackdropTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreItem
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreListener
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreView
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroSource
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroStatus
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroText
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHost
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMore
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextSpan
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallBackdropTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallOpenEffects
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvMenuTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeAnchorRingStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeHeroButtonStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeHeroTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeWallStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeWriteSnapshot
import me.him188.ani.app.ui.foundation.tv.nativeview.withNsfw
import me.him188.ani.app.ui.foundation.tv.nativeview.withNsfwCards
import me.him188.ani.app.ui.foundation.tv.rememberTvTitleLogoLookup
import me.him188.ani.app.ui.foundation.tv.rememberTvTmdbTitles
import me.him188.ani.app.ui.foundation.tv.tvHeroBangumiSummary
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvHeroNextEpisodeName
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.tv.tvHeroTmdbFallbackSummary
import me.him188.ani.app.ui.foundation.tv.tvHeroTmdbSummary
import me.him188.ani.app.ui.foundation.tv.tvHeroTmdbSummaryPending
import me.him188.ani.app.ui.foundation.tv.tvHeroTmdbTextPending
import me.him188.ani.app.ui.foundation.tv.tvPageBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.tvPosterWallBackground
import me.him188.ani.app.ui.foundation.tv.tvPosterWallHeaderStyle
import me.him188.ani.app.ui.foundation.tv.tvPosterWallSecondaryLabelAlpha
import me.him188.ani.app.ui.foundation.tv.tvTitle
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_continue_watching
import me.him188.ani.app.ui.lang.exploration_rec_more
import me.him188.ani.app.ui.lang.exploration_rec_more_generic
import me.him188.ani.app.ui.lang.exploration_rec_more_hero_peek
import me.him188.ani.app.ui.lang.exploration_rec_more_hero_seed
import me.him188.ani.app.ui.lang.exploration_rec_more_loading
import me.him188.ani.app.ui.lang.exploration_schedule
import me.him188.ani.app.ui.lang.exploration_tv_air_date
import me.him188.ani.app.ui.lang.exploration_tv_all_caught_up
import me.him188.ani.app.ui.lang.exploration_tv_minutes_left
import me.him188.ani.app.ui.lang.exploration_tv_next_episode
import me.him188.ani.app.ui.lang.exploration_tv_watch_now
import me.him188.ani.app.ui.lang.exploration_tv_watched_latest
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.subject_progress_updates_on
import me.him188.ani.app.ui.subject.AiringLabelState
import me.him188.ani.app.ui.subject.rememberSubjectStatusStrings
import me.him188.ani.datasources.api.toLocalDateOrNull
import org.jetbrains.compose.resources.stringResource

/** 安卓上画面就是原生视图. */
internal val TvExplorationNativeState.view: TvNativeExploreView? get() = surface as? TvNativeExploreView

@Composable
internal actual fun TvExplorationNativeWall(
    state: TvExplorationNativeState,
    metrics: TvNativeExploreMetrics,
    cardWidth: Dp,
    items: List<TvNativeExploreItem>,
    carouselCount: Int,
    carouselIndex: () -> Int,
    fadeColor: Color,
    geometry: TvPageBackdropGeometry,
    splitGate: () -> Float,
    listener: TvNativeExploreListener,
    cardFocusRequest: () -> TvCardFocusRequest?,
    heroFocusRequest: () -> TvHeroFocusRequest?,
    farJumpRow: () -> String?,
    focusedRowKey: () -> String?,
    focusedCardIndex: () -> Int,
    menuFor: (subjectId: Int) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier,
) {
    // 调试开关: 换成 Compose 版 (iOS 上画的那一套) 对照
    if (DevSwitches.tvComposeWalls.value) {
        TvExplorationComposeWall(
            state, metrics, cardWidth, items, carouselCount, carouselIndex, fadeColor, geometry, splitGate, listener,
            cardFocusRequest, heroFocusRequest, farJumpRow, focusedRowKey, focusedCardIndex, menuFor, modifier,
        )
        return
    }
    val sketch = LocalSketch.current
    val scope = rememberCoroutineScope()
    val composeRoot = LocalView.current
    val density = LocalDensity.current
    val splitFloorPx = with(density) { -TV_POSTER_WALL_HERO_SPLIT_BAND.toPx() }
    // hero 态在整页底下铺模糊背景, 按确定对焦变清晰再进详情页 (设置里的开关, 见 TvNativeExploreView.heroBlur)
    // 「海报上按确定」只有先看简介这一档有 hero 态 (见 TvPosterConfirmAction); 模糊背景也只对它有用. 钉死锚点版式一直有 hero 态 (焦点在卡上就显示)
    val fixedAnchor = tvExploreFixedAnchor()
    val posterConfirm = LocalThemeSettings.current.tvPosterConfirm
    val heroEnabled = fixedAnchor || posterConfirm == TvPosterConfirmAction.Hero
    val wallBlur = LocalThemeSettings.current.tvWallBackdropBlur
    val heroBlur = heroEnabled && wallBlur != TvBackdropBlurLevel.None
    val blurSpec = tvBackdropBlurSpec(wallBlur)
    // 铺着模糊背景时卡片的字照 vibrancy 画 (深色主题, 见 TvNativeWallStyle.labelVibrancy); 继续观看的卡画没看几集的角标 (见 tvProgressCardLine)
    val style = rememberTvNativeWallStyle(
        cardWidth,
        metrics.columns,
        columnSpacing = if (fixedAnchor) TV_EXPLORE_FIXED_ANCHOR_COLUMN_SPACING else TV_POSTER_WALL_COLUMN_SPACING,
        labelVibrancy = heroBlur,
        countBadge = true,
        fixedAnchor = fixedAnchor,
    )
    val ringStyle = rememberTvNativeAnchorRingStyle(style)
    val textStyle = rememberTvNativeHeroTextStyle(titleMaxLines = 1, lineSpacing = 10.dp)
    val buttonStyle = rememberTvNativeHeroButtonStyle()
    val headerStyle = tvPosterWallHeaderStyle().toTvNativeTextStyle(density, LocalContentColor.current)
    val headerIdleAlpha = tvPosterWallSecondaryLabelAlpha()
    val visualEffects = LocalThemeSettings.current.visualEffects
    // 卡片墙的底色: 铺着模糊背景时 hero 态不压黑, 整屏底色一直是它 (见 TvNativeExploreView.heroBlur / wallColor)
    val wallColor = tvPosterWallBackground()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // 模糊背景的压暗: 深色主题照 Apple TV 分两档 (卡片墙底色 20%, 主色很亮的图 55%, 见 TV_HERO_BLUR_DIM_ALPHA); 浅色主题是卡片墙的底色 +
    // 起步的透明度, 按 hero 标题的颜色压到看得清 (同新番时间表按页面底色压)
    val heroBlurMask = wallColor.copy(alpha = if (dark) TV_HERO_BLUR_DIM_ALPHA else TV_FULLSCREEN_BACKDROP_DIM_ALPHA)
    val heroBlurText = tvHeroContentColor()
    // 卡片区在滚动 / 方向键按住: 背景图的剧照升档等它们都停了才去取原图 (见 TvNativeBackdropView.navigating)
    val scrollActivity = LocalTvScrollActivity.current
    val navKeys = LocalTvNavKeyTracker.current
    val touchInput = LocalTvTouchInputEnabled.current
    val dotColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val playIcon = rememberTvNativeIcon(Icons.Rounded.PlayArrow, 18.dp)
    val scheduleIcon = rememberTvNativeIcon(Icons.Rounded.CalendarMonth, 18.dp)
    val watchNow = stringResource(Lang.exploration_tv_watch_now)
    val schedule = stringResource(Lang.exploration_schedule)
    val currentListener by rememberUpdatedState(listener)
    val selected = carouselIndex()

    var pagePosition by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { pagePosition = it.positionInWindow() }) {
        TvNativeHost(
            factory = { context ->
                TvNativeExploreView(context, sketch, scope, style, metrics, textStyle, buttonStyle, headerStyle).also { view ->
                    // 建行之前定: 横滑行建出来就是钉死锚点的
                    view.fixedAnchor = fixedAnchor
                    view.composeRoot = composeRoot
                    view.restore(
                        state.savedScrollPx, state.savedRowLeft, state.savedRowFocused,
                        focusedRowKey(), focusedCardIndex(), state.heroActive && heroEnabled,
                    )
                    view.heroText.onShownSubjectChanged = { state.titleSubjectId = it }
                    state.surface = view
                }
            },
            update = { view ->
                view.listener = object : TvNativeExploreListener by currentListener {
                    override fun onToneChanged(tone: Float, splitY: Float) {
                        // 分界线连同渐变带整段滚出屏顶后画面不再变 (见 TvPosterWallTone.drawBackground): 封在那里, 卡片区往下滚时不再每帧写快照、重画整屏底色
                        val y = splitY.coerceAtLeast(splitFloorPx)
                        // 主壳的整屏底色 (分界线跟着轮播背景图的下缘走) 与原生视图同一帧挪: 写完当场派发
                        if (state.tone != tone || state.splitY != y) {
                            tvNativeWriteSnapshot {
                                state.tone = tone
                                state.splitY = y
                            }
                        }
                        currentListener.onToneChanged(tone, y)
                    }

                    override fun onHeroActiveChanged(active: Boolean) {
                        state.heroActive = active
                        currentListener.onHeroActiveChanged(active)
                    }

                    override fun onWallOpeningChanged(opening: Boolean) {
                        state.wallOpening = opening
                    }

                    override fun onWallOpened() {
                        state.wallOpened = true
                        state.wallOpenLeft = false
                    }
                }
                view.transitions = visualEffects.transitions
                view.animatedScroll = visualEffects.animatedScroll
                view.backdropNavigating = { scrollActivity?.isScrolling == true || navKeys?.held == true }
                view.dark = dark
                view.headerIdleAlpha = headerIdleAlpha
                view.fadeColor = fadeColor.toArgb()
                view.treatmentFor = { mode -> tvPageBackdropTreatment(mode, topScrim = false, fadeColor = fadeColor, geometry = geometry) }
                view.heroEnabled = heroEnabled
                // 钉死锚点版式选了直接播放: 按确定直接播, 不放进详情页的那段对焦
                view.openFocusesWall = !(fixedAnchor && posterConfirm == TvPosterConfirmAction.Play)
                view.touchInput = touchInput
                view.heroBlur = heroBlur
                view.anchorRingStyle = ringStyle
                view.wallColor = wallColor.toArgb()
                view.wallBackdrop?.let { wb ->
                    wb.maskColor = heroBlurMask.toArgb()
                    wb.textColor = heroBlurText.toArgb()
                    wb.brightMaskAlpha = if (dark) TV_HERO_BLUR_BRIGHT_DIM_ALPHA else Float.NaN
                    wb.blur = blurSpec
                    wb.coverWidthPx = style.coverWidthPx
                    wb.coverHeightPx = style.coverHeightPx
                    wb.crossfade = visualEffects.transitions
                }
                view.update(style, metrics, textStyle, buttonStyle)
                view.setButtons(watchNow, playIcon, schedule, scheduleIcon)
                view.setItems(items)
                view.setCarousel(carouselCount, selected, dotColor)
            },
            modifier = Modifier.focusRequester(state.focusRequester),
            // 海报墙大小、钉死锚点一变整块重建: 卡片视图的尺寸与横滑行的滚法建好就定了. 停位与各行停在哪张存在 state 里, 新视图照返回本页那样恢复 (见 factory)
            rebuildKey = LocalTvPosterWallScale.current to fixedAnchor,
        )
        // 长按卡片的收藏菜单: 锚在那张卡的封面上 (原生视图报上来的封面框, 窗口坐标)
        // 收起时照样组合着上一次的目标, 菜单淡完才撤 (见 rememberTvMenuTarget)
        rememberTvMenuTarget(state.menu)?.let { (subjectId, rect) ->
            val menu = remember(subjectId) { menuFor(subjectId) }
            Box(
                Modifier
                    .offset { IntOffset((rect.left - pagePosition.x).toInt(), (rect.top - pagePosition.y).toInt()) }
                    .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }),
            ) {
                menu(state.menu != null) { state.menu = null }
            }
        }
    }

    // 没有 hero 态 (「海报上按确定」改成了别的档) 时撤掉存着的 hero 态: 返回键不再先回卡片墙, 返回本页也不恢复
    LaunchedEffect(heroEnabled) {
        if (!heroEnabled) state.heroActive = false
    }
    // hero 态模糊背景的点开: 进了详情页、回到本页之后倒放, 点开途中按返回取消
    if (heroBlur) {
        TvNativeWallOpenEffects(
            view = state.view,
            opened = { state.wallOpened },
            opening = { state.wallOpening },
            left = { state.wallOpenLeft },
            markLeft = { state.wallOpenLeft = true },
            clear = {
                state.wallOpened = false
                state.wallOpenLeft = false
            },
        )
    }
    // 换页交接途中分界线那层的浓度 (TvPosterWallTone.splitGate) 乘在轮播背景图上
    LaunchedEffect(state) {
        snapshotFlow { state.view to splitGate() }.collect { (view, gate) -> view?.splitGate = gate }
    }
    // 页面的程序化落点 (进页恢复 / 返回键分层 / 回到主界面) 交给原生送焦; 请求按实例区分, 同参重发也会再送一次
    LaunchedEffect(state) {
        snapshotFlow { state.view to cardFocusRequest() }.collect { (view, req) ->
            if (view != null && req != null) {
                view.focusCard(req.rowKey, req.cardIndex, far = req.rowKey == farJumpRow())
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.view to heroFocusRequest() }.collect { (view, req) ->
            if (view != null && req != null) view.focusHeroButton(req.button.ordinal)
        }
    }
    // 放大 / 缩回期间的标题: 绘制权在详情页那份 (放大) 或转场层 (缩回) 手里时隐藏, 否则按缩回让位平移; 缩回途中停跑马灯
    // (见 TvNativeHeroTextView.setTitleHandoff)
    LaunchedEffect(state) {
        snapshotFlow {
            val id = state.titleSubjectId
            val view = state.view
            if (id == null || view == null) {
                null
            } else {
                TvNativeTitleHandoff(
                    view,
                    TvHeroZoomHandoff.titleOwnedByOverlay(id) || TvHeroZoomHandoff.titleOwnedByDetails(id),
                    TvHeroZoomHandoff.shrinkTitleOffset(id),
                    TvHeroZoomHandoff.titleSettling(id),
                )
            }
        }.collect { h ->
            h?.view?.heroText?.setTitleHandoff(h.hidden, h.offset?.x ?: 0f, h.offset?.y ?: 0f, h.settling)
        }
    }
}

private data class TvNativeTitleHandoff(
    val view: TvNativeExploreView,
    val hidden: Boolean,
    val offset: Offset?,
    val settling: Boolean,
)

/**
 * 固定焦点版式的列距: 卡片之间露出 10dp (同 9 月那版固定焦点). 卡片不放大, 用不着海报墙给放大留的那段距离;
 * 卡格两边各含一道聚焦框空隙, 这里扣掉. 与其他卡片尺寸一样跟着海报墙大小缩放.
 */
private val TV_EXPLORE_FIXED_ANCHOR_COLUMN_SPACING = 10.dp - TvFocusRing.Gap * 2
