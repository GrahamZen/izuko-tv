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
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.FollowedSubjectInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.subjectInfo
import me.him188.ani.app.data.recommendation.RecommendationGroup
import me.him188.ani.app.tools.WeekFormatter
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.stateOf
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.LocalTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaPipelineState
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.TvNextEpisodeMedia
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
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvMenuTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeHeroButtonStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeHeroTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeWallStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeWriteSnapshot
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.tv.tvPageBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvPageBackdropGeometry
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

/*
 * 探索页海报墙的原生视图: 页面 (TvExplorationPage) 管数据、hero 媒体流水线、换挡规则、焦点簿记、返回键分层、导航;
 * 画面与焦点在 TvNativeExploreView. 本文件是两边的接线: 列表项 / 两路 hero 内容 / 程序化落点 → 原生, 原生的焦点与 hero 态 →
 * 页面 (TvNativeExploreListener 由页面实现), 以及返回本页 (页面重建) 时的恢复.
 */

/**
 * 原生海报墙在页面这一侧的状态: 视图引用、hero 态 (跨导航保存, 返回本页时停在 hero 态)、交给主壳画整屏底色的黑度与分界线, 以及页面
 * 重建时恢复用的列表停位 / 各行行首与上次停的那张.
 */
@Stable
internal class TvExplorationNativeState(
    heroActive: Boolean,
    internal var savedScrollPx: Int,
    internal val savedRowLeft: HashMap<String, Int>,
    internal val savedRowFocused: HashMap<String, Int>,
) {
    var view: TvNativeExploreView? by mutableStateOf(null)
        internal set
    val focusRequester = FocusRequester()

    /** hero 态 (页面的返回键按它开关). */
    var heroActive: Boolean by mutableStateOf(heroActive)
        internal set

    /** 整屏黑度 (hero 态进度) 与轮播分界线 (px, 从页面顶算), 主壳画底色时读. */
    var tone: Float by mutableFloatStateOf(0f)
        internal set
    var splitY: Float by mutableFloatStateOf(Float.NaN)
        internal set

    /** 标题此刻显示的条目 (观察放大转场的缩回用). */
    internal var titleSubjectId: Int? by mutableStateOf(null)

    /** 菜单锚点 (长按卡片): 条目, 封面在窗口里的框. */
    internal var menu: Pair<Int, AndroidRect>? by mutableStateOf(null)

    /** 退出 hero 态 (返回键). */
    fun exitHero() {
        view?.setHeroActive(false)
        heroActive = false
    }

    /** 记下视图此刻的停位与各行位置 (页面重建时恢复). */
    internal fun capture() {
        val v = view ?: return
        savedScrollPx = v.scrolledPx
        savedRowLeft.clear()
        savedRowLeft.putAll(v.rowLeftIndex)
        savedRowFocused.clear()
        savedRowFocused.putAll(v.rowFocusedIndexMap)
        heroActive = v.heroActive
    }
}

@Composable
internal fun rememberTvExplorationNativeState(): TvExplorationNativeState =
    rememberSaveable(saver = TvExplorationNativeStateSaver) {
        TvExplorationNativeState(heroActive = false, savedScrollPx = 0, savedRowLeft = HashMap(), savedRowFocused = HashMap())
    }

private val TvExplorationNativeStateSaver = Saver<TvExplorationNativeState, ArrayList<Any>>(
    save = { state ->
        state.capture()
        arrayListOf(
            state.heroActive,
            state.savedScrollPx,
            ArrayList(state.savedRowLeft.keys),
            ArrayList(state.savedRowLeft.values),
            ArrayList(state.savedRowFocused.keys),
            ArrayList(state.savedRowFocused.values),
        )
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        fun <T> at(i: Int) = list[i] as T
        val leftKeys: List<String> = at(2)
        val leftValues: List<Int> = at(3)
        val focusedKeys: List<String> = at(4)
        val focusedValues: List<Int> = at(5)
        TvExplorationNativeState(
            heroActive = at(0),
            savedScrollPx = at(1),
            savedRowLeft = HashMap(leftKeys.zip(leftValues).toMap()),
            savedRowFocused = HashMap(focusedKeys.zip(focusedValues).toMap()),
        )
    },
)

/**
 * 原生海报墙的列表项 (结构见 TvNativeExploreList.kt): hero 占位 + 继续观看 (标题 + 一行) + 各组推荐 (标题 + 行).
 * 继续观看行按分页快照给 (没到的是占位), 卡上带集数观看进度条 (见 followedCardProgress).
 * 还能接着推荐的组 ([RecommendationGroup.extendable]) 行尾多一张「更多」卡, [extending] 里的组画成加载中.
 */
@Composable
internal fun rememberTvExplorationNativeItems(
    hasFollowed: Boolean,
    followedItems: LazyPagingItems<FollowedSubjectInfo>,
    recRows: List<TvRecRow>,
    recFlat: List<RecommendedSubjectInfo>,
    playHistories: List<EpisodeHistory>,
    extending: Set<String>,
): List<TvNativeExploreItem> {
    val followedTitle = stringResource(Lang.exploration_continue_watching)
    val moreTitle = stringResource(Lang.exploration_rec_more)
    val moreGenericTitle = stringResource(Lang.exploration_rec_more_generic)
    val moreLoadingTitle = stringResource(Lang.exploration_rec_more_loading)
    val groupTitles = recRows.map { if (it.header) tvRecGroupTitle(it.group) else "" }
    val followed: List<FollowedSubjectInfo?>? = if (hasFollowed) followedItems.itemSnapshotList else null
    return remember(
        hasFollowed, followed, recRows, recFlat, playHistories, followedTitle, groupTitles, extending,
        moreTitle, moreGenericTitle, moreLoadingTitle,
    ) {
        val result = ArrayList<TvNativeExploreItem>()
        result.add(TvNativeExploreItem.Spacer(TV_WALL_HERO_SPACER_KEY))
        if (followed != null) {
            val followedCards: List<TvNativeCard?> = followed.map { item: FollowedSubjectInfo? ->
                item?.subjectInfo?.let { subject ->
                    TvNativeCard(
                        imageUrl = subject.imageLarge,
                        title = subject.displayName,
                        progress = followedCardProgress(item, playHistories),
                    )
                }
            }
            result.add(TvNativeExploreItem.Header(TV_FOLLOWED_HEADER_KEY, followedTitle))
            result.add(TvNativeExploreItem.Row(TV_FOLLOWED_ROW_KEY, followedCards))
        }
        recRows.forEachIndexed { recRow, row ->
            if (row.header) result.add(TvNativeExploreItem.Header(tvRecHeaderKey(recRow), groupTitles[recRow]))
            val cards: List<TvNativeCard?> = List(row.size) { i ->
                recFlat.getOrNull(row.start + i)?.let { TvNativeCard(imageUrl = it.imageLarge, title = it.nameCn) }
            }
            // 「更多」卡接在一组的最后一行末尾 (能接着推荐的组都是一组一行; 切成多行的只有匿名推荐, 它不接)
            val lastRowOfGroup = recRows.getOrNull(recRow + 1)?.group !== row.group
            val more = if (row.group.extendable && lastRowOfGroup) {
                val loading = row.group.key in extending
                TvNativeCard(
                    // 玻璃底铺一张竖版封面的模糊小图 (见 TvNativeCard.more): 种子行是种子, 其余是下次接出来的第一部; 没有就用这一行最后一张
                    imageUrl = row.group.moreImageUrl ?: cards.lastOrNull()?.imageUrl,
                    title = when {
                        loading -> moreLoadingTitle
                        row.group.seedSubjectId != null -> moreTitle
                        else -> moreGenericTitle
                    },
                    more = if (loading) TvNativeMore.Loading else TvNativeMore.Idle,
                )
            } else {
                null
            }
            result.add(TvNativeExploreItem.Row(tvRecRowKey(recRow), if (more != null) cards + more else cards))
        }
        result
    }
}

/**
 * 原生海报墙本体: 建视图、把列表项 / 轮播 / 主题尺寸交给它, 把页面的程序化落点 ([cardFocusRequest] / [heroFocusRequest]) 转给它送焦, 观察放大
 * 转场的缩回 (标题让位 / 停跑马灯). [listener] 由页面实现 (焦点簿记、hero 媒体、导航都在那边).
 */
@Composable
internal fun TvExplorationNativeWall(
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
    modifier: Modifier = Modifier,
) {
    val sketch = LocalSketch.current
    val scope = rememberCoroutineScope()
    val composeRoot = LocalView.current
    val density = LocalDensity.current
    val style = rememberTvNativeWallStyle(cardWidth, metrics.columns)
    val textStyle = rememberTvNativeHeroTextStyle(titleMaxLines = 1, lineSpacing = 10.dp)
    val buttonStyle = rememberTvNativeHeroButtonStyle()
    val headerStyle = MaterialTheme.typography.titleMedium.toTvNativeTextStyle(density, LocalContentColor.current)
    val visualEffects = LocalThemeSettings.current.visualEffects
    // 卡片区在滚动 / 方向键按住: 背景图的剧照升档等它们都停了才去取原图 (见 TvNativeBackdropView.navigating)
    val scrollActivity = LocalTvScrollActivity.current
    val navKeys = LocalTvNavKeyTracker.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
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
                    view.composeRoot = composeRoot
                    view.restore(
                        state.savedScrollPx, state.savedRowLeft, state.savedRowFocused,
                        focusedRowKey(), focusedCardIndex(), state.heroActive,
                    )
                    view.heroText.onShownSubjectChanged = { state.titleSubjectId = it }
                    state.view = view
                }
            },
            update = { view ->
                view.listener = object : TvNativeExploreListener by currentListener {
                    override fun onToneChanged(tone: Float, splitY: Float) {
                        // 主壳的整屏底色 (分界线跟着轮播背景图的下缘走) 与原生视图同一帧挪: 写完当场派发
                        if (state.tone != tone || state.splitY != splitY) {
                            tvNativeWriteSnapshot {
                                state.tone = tone
                                state.splitY = splitY
                            }
                        }
                        currentListener.onToneChanged(tone, splitY)
                    }

                    override fun onHeroActiveChanged(active: Boolean) {
                        state.heroActive = active
                        currentListener.onHeroActiveChanged(active)
                    }
                }
                view.transitions = visualEffects.transitions
                view.animatedScroll = visualEffects.animatedScroll
                view.backdropNavigating = { scrollActivity?.isScrolling == true || navKeys?.held == true }
                view.dark = dark
                view.fadeColor = fadeColor.toArgb()
                view.treatmentFor = { mode -> tvPageBackdropTreatment(mode, topScrim = false, fadeColor = fadeColor, geometry = geometry) }
                view.update(style, metrics, textStyle, buttonStyle)
                view.setButtons(watchNow, playIcon, schedule, scheduleIcon)
                view.setItems(items)
                view.setCarousel(carouselCount, selected, dotColor)
            },
            modifier = Modifier.focusRequester(state.focusRequester),
        )
        // 长按卡片的收藏菜单: 锚在那张卡的封面上 (原生视图报上来的封面框, 窗口坐标)
        // 收起时照样组合着上一次的目标, 菜单淡完才撤 (见 rememberTvMenuTarget)
        rememberTvMenuTarget(state.menu)?.let { (subjectId, rect) ->
            val menu = remember(subjectId) { menuFor(subjectId) }
            Box(
                Modifier
                    .offset { IntOffset((rect.left - pagePosition.x).toInt(), (rect.top - pagePosition.y).toInt()) }
                    .size(with(density) { rect.width().toDp() }, with(density) { rect.height().toDp() }),
            ) {
                menu(state.menu != null) { state.menu = null }
            }
        }
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
 * 两路 hero 内容 (热门轮播 / 聚焦卡) 交给原生: 背景图 (停稳后的展示目标, 见 rememberTvSettledHeroProvider)、真实目标 (按下即压暗)、
 * 文字 (滚动 / 连发中为 null, 见 rememberTvScrollHiddenProvider). 单独一个小组合: 这些都是每换一张卡就变的热状态, 只让这一块重组.
 */
@Composable
internal fun TvExplorationNativeSources(
    state: TvExplorationNativeState,
    heroPipeline: TvHeroMediaPipelineState,
    carouselRaw: () -> TvHeroTarget?,
    carouselDisplay: () -> TvHeroTarget?,
    carouselText: () -> TvHeroTarget?,
    cardRaw: () -> TvHeroTarget?,
    cardDisplay: () -> TvHeroTarget?,
    cardText: () -> TvHeroTarget?,
    autoAdvanced: () -> Boolean,
    infoCache: Map<Int, SubjectCollectionInfo>,
    episodeStillCache: Map<Int, TvNextEpisodeMedia>,
    summaryFallbackCache: Map<Int, String>,
    playHistories: () -> List<EpisodeHistory>,
) {
    val carousel = tvExplorationNativeSource(
        heroPipeline, carouselRaw(), carouselDisplay(), carouselText(), infoCache, episodeStillCache, summaryFallbackCache, playHistories,
    )
    val card = tvExplorationNativeSource(
        heroPipeline, cardRaw(), cardDisplay(), cardText(), infoCache, episodeStillCache, summaryFallbackCache, playHistories,
    )
    val view = state.view
    SideEffect {
        view?.setSources(carousel.copy(autoAdvanced = autoAdvanced()), card)
    }
}

@Composable
private fun tvExplorationNativeSource(
    heroPipeline: TvHeroMediaPipelineState,
    raw: TvHeroTarget?,
    display: TvHeroTarget?,
    textTarget: TvHeroTarget?,
    infoCache: Map<Int, SubjectCollectionInfo>,
    episodeStillCache: Map<Int, TvNextEpisodeMedia>,
    summaryFallbackCache: Map<Int, String>,
    playHistories: () -> List<EpisodeHistory>,
): TvNativeHeroSource {
    val spec = display?.toHeroMediaSpec()
    val url = heroPipeline.backdropUrl(spec)
    val underlay = heroPipeline.underlayUrl(spec)
    val backdrop = if (url != null && display != null) {
        // 完整档 + 4K 界面的下一集剧照: 停稳后原地升到原图
        TvNativeBackdropTarget(url, display.subjectId, underlay, upgradeUrl = heroPipeline.upgradeUrl(spec))
    } else {
        null
    }
    val text = textTarget?.let { tvExplorationNativeHeroText(it, infoCache, episodeStillCache, summaryFallbackCache, playHistories) }
    return TvNativeHeroSource(
        backdrop = backdrop,
        dimming = raw?.subjectId != display?.subjectId,
        rawSubjectId = raw?.subjectId,
        text = text,
    )
}

/**
 * hero 文字: 标题; 条目信息到了才有 ★评分、开播状态 · 总集数 (总集数用正文色)、开播年月;
 * 继续观看的条目多一行下一集 (集号 · 集名 · 剩余分钟 / 已看完); 简介优先下一集的 TMDB 单集简介, 再整部简介, 再 Bangumi 兜底.
 * 焦点在「更多」卡上 ([TvHeroTarget.more]) 时下一集那一行的位置写「按确定，推荐更多…」(种子行嵌种子名, 放不下跑马灯; 别的组嵌组标题, 放不下折行),
 * 简介只留第一句、最多两行.
 */
@Composable
private fun tvExplorationNativeHeroText(
    target: TvHeroTarget,
    infoCache: Map<Int, SubjectCollectionInfo>,
    episodeStillCache: Map<Int, TvNextEpisodeMedia>,
    summaryFallbackCache: Map<Int, String>,
    playHistories: () -> List<EpisodeHistory>,
): TvNativeHeroText {
    val info = infoCache[target.subjectId]
        ?: return TvNativeHeroText(target.subjectId, target.title, infoReady = false)
    val secondary = tvHeroSecondaryContentColor().toArgb()
    val onSurface = LocalContentColor.current.toArgb()
    val strings = rememberSubjectStatusStrings()
    val airing = remember(info) { AiringLabelState(stateOf(info.airingInfo), stateOf(info.progressInfo)) }
    val meta = buildList {
        airing.progressText(strings)?.let { add(TvNativeTextSpan(it, secondary)) }
        airing.totalEpisodesText(strings)?.let {
            add(TvNativeTextSpan(" · ", onSurface))
            add(TvNativeTextSpan(it, onSurface))
        }
        val airDate = info.subjectInfo.airDate
        if (airDate.isValid) {
            add(TvNativeTextSpan("    " + stringResource(Lang.exploration_tv_air_date, airDate.year, airDate.month), secondary))
        }
    }
    val score = info.subjectInfo.ratingInfo.score
    val more = target.more
    val status = when {
        more != null -> tvExplorationNativeMoreStatus(more, onSurface)
        target.fromFollowed -> tvExplorationNativeStatus(info, playHistories, secondary)
        else -> null
    }
    // 两张表是进程级共享的 (邻居预取会写进来): 收进 derivedStateOf, 写入别的条目时不重组
    val summary by remember(target, info) {
        derivedStateOf {
            val nextEpOverview = target.takeIf { it.fromFollowed }
                ?.let { episodeStillCache[it.subjectId]?.overview }
                ?.takeIf { it.isNotBlank() }
            nextEpOverview ?: info.subjectInfo.summary.trim().ifBlank { summaryFallbackCache[target.subjectId].orEmpty() }
        }
    }
    return TvNativeHeroText(
        subjectId = target.subjectId,
        title = target.title,
        infoReady = true,
        rating = score.takeIf { (it.toFloatOrNull() ?: 0f) > 0f },
        meta = meta,
        status = status,
        summary = if (more != null) tvSummaryFirstSentence(summary) else summary,
        summaryMaxLines = if (more != null) TV_MORE_HERO_SUMMARY_LINES else 0,
    )
}

/**
 * 「更多」卡的 hero 那一行. 种子行: 一句话里嵌着种子名, 名字长短不定, 单独一段 (放不下跑马灯), 前后两段不截断;
 * 别的组: 组标题是固定的那几个, 整句一段字, 放不下就折行.
 */
@Composable
private fun tvExplorationNativeMoreStatus(group: RecommendationGroup, color: Int): TvNativeHeroStatus {
    if (group.seedSubjectId == null) {
        return TvNativeHeroStatus(
            lead = stringResource(Lang.exploration_rec_more_hero_peek, tvRecGroupTitle(group)),
            name = null,
            tail = null,
            color = color,
            wrap = true,
        )
    }
    val name = group.titleArg.orEmpty()
    val sentence = stringResource(Lang.exploration_rec_more_hero_seed, TV_MORE_HERO_NAME_SLOT)
    return TvNativeHeroStatus(
        lead = sentence.substringBefore(TV_MORE_HERO_NAME_SLOT),
        name = name,
        tail = sentence.substringAfter(TV_MORE_HERO_NAME_SLOT, ""),
        color = color,
        nameSeparator = "",
    )
}

/**
 * 简介的第一句 (「更多」卡的 hero 只提一句): 第一段里到第一个句末标点 (连同紧跟的右引号 / 右括号) 为止; 不到 [minChars] 字就接着带下一句;
 * 没有句末标点就整段 (显示时限行数, 多的省略).
 */
internal fun tvSummaryFirstSentence(summary: String, minChars: Int = 12): String {
    // trim() 连段首的全角空格一起去 (按 Unicode 判空白)
    val paragraph = summary.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    var from = 0
    while (true) {
        val stop = paragraph.indexOfAny(TV_SENTENCE_ENDS, from)
        if (stop < 0) return paragraph
        var end = stop + 1
        while (end < paragraph.length && paragraph[end] in TV_SENTENCE_CLOSERS) end++
        if (end >= minChars || end >= paragraph.length) return paragraph.substring(0, end)
        from = end
    }
}

private val TV_SENTENCE_ENDS = charArrayOf('。', '！', '？', '!', '?')
private const val TV_SENTENCE_CLOSERS = "」』”’）)】"

/** 「更多」卡的 hero 简介最多几行. */
private const val TV_MORE_HERO_SUMMARY_LINES = 2

/** 文案里名字的占位 (格式化后按它切成前后两段). */
private const val TV_MORE_HERO_NAME_SLOT = "\uE000"

/** 继续观看的下一集行, 三态: 已看完最新一集 / 看到一半剩几分钟 / 下一集. */
@Composable
private fun tvExplorationNativeStatus(
    info: SubjectCollectionInfo,
    playHistories: () -> List<EpisodeHistory>,
    color: Int,
): TvNativeHeroStatus? {
    val nextEp = info.progressInfo.nextEpisodeIdToPlay?.let { nextId -> info.episodes.firstOrNull { it.episodeId == nextId } }
        ?: return null
    val epLabel = stringResource(Lang.playback_history_episode_label, nextEp.episodeInfo.sort.toString())
    val epName = nextEp.episodeInfo.nameCn.ifBlank { nextEp.episodeInfo.name }
    val caughtUp = info.progressInfo.continueWatchingStatus.let {
        it is ContinueWatchingStatus.Watched || it is ContinueWatchingStatus.Done
    }
    val remainingMinutes = if (caughtUp) {
        null
    } else {
        playHistories().firstOrNull { it.episodeId == nextEp.episodeId }?.let { history ->
            val duration = history.durationMillis
            if (duration != null && duration > 0 && history.positionMillis > 0) {
                // 向上取整: 剩 30 秒也显示 1 分钟
                (((duration - history.positionMillis).coerceAtLeast(0L) + 59_999) / 60_000).toInt().coerceAtLeast(1)
            } else {
                null
            }
        }
    }
    val lead = if (caughtUp || remainingMinutes != null) epLabel else stringResource(Lang.exploration_tv_next_episode, epLabel)
    val tail = when {
        caughtUp -> {
            val watchedStatus = info.progressInfo.continueWatchingStatus as? ContinueWatchingStatus.Watched
            val updatesOn = watchedStatus?.nextEpisodeAirDate?.toLocalDateOrNull()?.let { date ->
                stringResource(Lang.subject_progress_updates_on, WeekFormatter.System.format(date))
            }
            val caught = if (watchedStatus != null) {
                stringResource(Lang.exploration_tv_watched_latest)
            } else {
                stringResource(Lang.exploration_tv_all_caught_up)
            }
            " · " + caught + (updatesOn?.let { " · $it" } ?: "")
        }

        remainingMinutes != null -> " · " + stringResource(Lang.exploration_tv_minutes_left, remainingMinutes)
        else -> null
    }
    return TvNativeHeroStatus(lead = lead, name = epName.ifBlank { null }, tail = tail, color = color)
}
