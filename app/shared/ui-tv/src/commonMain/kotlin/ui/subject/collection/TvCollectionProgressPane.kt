/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.preferredDisplayName
import me.him188.ani.app.tools.WeekFormatter
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.focus.TV_TRANSIT_ANCHOR_SIZE
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.session.LocalTvRailEnter
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.FillTvTmdbTextsFromCache
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FADE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.TV_GRID_START_BLEED
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_END_PAD
import me.him188.ani.app.ui.foundation.tv.TV_PORTRAIT_CARD_COVER_RATIO
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_BOTTOM_BLEED
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_CARD_FOCUS_STYLE
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_HEADER_GAP
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_ROW_SPACING
import me.him188.ani.app.ui.foundation.tv.focusScale
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreItem
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHost
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeShelfListener
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeShelfView
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeWallStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.withNsfwCards
import me.him188.ani.app.ui.foundation.tv.rememberTvScrollActivityReporter
import me.him188.ani.app.ui.foundation.tv.rememberTvTmdbTitles
import me.him188.ani.app.ui.foundation.tv.tvGridPageWallContentWidth
import me.him188.ani.app.ui.foundation.tv.tvPlayKeyShortPress
import me.him188.ani.app.ui.foundation.tv.tvPosterWallEndMargin
import me.him188.ani.app.ui.foundation.tv.tvPosterWallGrid
import me.him188.ani.app.ui.foundation.tv.tvPosterWallHeaderStyle
import me.him188.ani.app.ui.foundation.tv.tvPosterWallLabelHeight
import me.him188.ani.app.ui.foundation.tv.tvPosterWallSecondaryLabelAlpha
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.collection_tv_progress_empty
import me.him188.ani.app.ui.search.LoadErrorCard
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/*
 * 追番页「追更」页: 标签行下面一组一行的海报墙 (组见 TvCollectionProgressModel.kt), 画面与焦点在原生的 TvNativeShelfView. 没有 hero 态:
 * 卡片上按确定直接播下一集 (同播放键); 长按交给页面 (跳到这部所在的标签、落在它的卡上).
 * 本文件管: 卡片与组标题、几何、焦点簿记 (返回本页 / 换回本标签时回到上次那张)、返回键回首卡、空页与出错提示.
 */

/**
 * 追更页在页面这一侧的状态 (跨导航保存): 视图引用、列表停位、各行行首与上次停的那张、上次聚焦的那张、回来时回不回卡片区.
 */
@Stable
internal class TvCollectionProgressPaneState(
    internal var savedScrollPx: Int,
    internal val savedRowLeft: HashMap<String, Int>,
    internal val savedRowFocused: HashMap<String, Int>,
    focusedRowKey: String?,
    focusedCardIndex: Int,
    returnToCards: Boolean,
) {
    var view: TvNativeShelfView? by mutableStateOf(null)
        internal set
    internal val focusRequester = FocusRequester()
    internal val errorFocusRequester = FocusRequester()

    var focusedRowKey: String? by mutableStateOf(focusedRowKey)
        internal set
    var focusedCardIndex: Int by mutableIntStateOf(focusedCardIndex)
        internal set

    /**
     * 返回本页 / 回到前台时回卡片区 (上次那张) 还是回标签行: 卡拿到焦点时为 true, 首行按上回标签行、行首按左进侧边栏时为 false;
     * 进播放器等焦点离开本页时不变.
     */
    var returnToCards: Boolean by mutableStateOf(returnToCards)
        internal set

    /** 焦点此刻在卡片区 (返回键回首卡、播放键按它判). */
    var cardsFocused: Boolean by mutableStateOf(false)
        internal set

    /** 把焦点送进卡片区 (上次那张, 没进过就是第一行). */
    fun focusCards(): Boolean = runCatching { focusRequester.requestFocus() }.isSuccess

    /** 空页时把焦点送进出错卡片 (登录 / 重试). */
    fun focusError(): Boolean = runCatching { errorFocusRequester.requestFocus() }.isSuccess

    /** 记下视图此刻的停位与各行位置 (页面重建 / 换走标签时). */
    internal fun capture() {
        val v = view ?: return
        savedScrollPx = v.scrolledPx
        savedRowLeft.clear()
        savedRowLeft.putAll(v.rowLeftIndex)
        savedRowFocused.clear()
        savedRowFocused.putAll(v.rowFocusedIndexMap)
    }
}

@Composable
internal fun rememberTvCollectionProgressPaneState(): TvCollectionProgressPaneState =
    rememberSaveable(saver = TvCollectionProgressPaneStateSaver) {
        TvCollectionProgressPaneState(0, HashMap(), HashMap(), null, 0, returnToCards = false)
    }

private val TvCollectionProgressPaneStateSaver = Saver<TvCollectionProgressPaneState, ArrayList<Any?>>(
    save = { state ->
        state.capture()
        arrayListOf(
            state.savedScrollPx,
            ArrayList(state.savedRowLeft.keys), ArrayList(state.savedRowLeft.values),
            ArrayList(state.savedRowFocused.keys), ArrayList(state.savedRowFocused.values),
            state.focusedRowKey, state.focusedCardIndex, state.returnToCards,
        )
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        fun <T> at(i: Int) = list[i] as T
        val leftKeys: List<String> = at(1)
        val leftValues: List<Int> = at(2)
        val focusedKeys: List<String> = at(3)
        val focusedValues: List<Int> = at(4)
        TvCollectionProgressPaneState(
            savedScrollPx = at(0),
            savedRowLeft = HashMap(leftKeys.zip(leftValues).toMap()),
            savedRowFocused = HashMap(focusedKeys.zip(focusedValues).toMap()),
            focusedRowKey = at(5),
            focusedCardIndex = at(6),
            returnToCards = at(7),
        )
    },
)

/**
 * 追更页本体. [onPlay] = 播下一集 (确定键与播放键), [onLongPress] = 长按卡片, [onTopRowUp] = 首行按上 (回标签行),
 * [onCardAreaFocusChanged] = 焦点进出卡片区.
 */
@Composable
internal fun TvCollectionProgressPane(
    progress: TvCollectionProgressState,
    state: TvCollectionProgressPaneState,
    playHistories: () -> List<EpisodeHistory>,
    onPlay: (SubjectCollectionInfo) -> Unit,
    onLongPress: (SubjectCollectionInfo) -> Unit,
    onTopRowUp: () -> Boolean,
    onCardAreaFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sketch = LocalSketch.current
    val density = LocalDensity.current
    val layout = tvCollectionProgressLayout()
    val metrics = layout.metrics
    val style = rememberTvNativeWallStyle(layout.cardWidth, metrics.columns)
    val headerStyle = tvPosterWallHeaderStyle().toTvNativeTextStyle(density, LocalContentColor.current)
    val headerIdleAlpha = tvPosterWallSecondaryLabelAlpha()
    val visualEffects = LocalThemeSettings.current.visualEffects
    val railEnter = LocalTvRailEnter.current
    val scrollReporter = rememberTvScrollActivityReporter()

    val sections = progress.sections
    val strings = rememberTvProgressStrings()
    val histories = playHistories()
    val nsfw = rememberNsfwPolicy()
    val useOriginalTitle = tvUseOriginalTitle()
    val tmdbTitles = rememberTvTmdbTitles()
    FillTvTmdbTextsFromCache(remember(sections) { sections.orEmpty().flatMap { s -> s.entries.map { it.subjectId } } })
    // 列表项: 每组一个标题 + 一行. 组每分钟按当前时刻重算一次 (sections 换新实例), 小字的「今天」跟着换
    val items = remember(sections, strings, histories, nsfw.snapshot, useOriginalTitle, tmdbTitles) {
        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()
        sections.orEmpty().flatMap { section ->
            listOf(
                TvNativeExploreItem.Header(tvProgressHeaderKey(section.group), strings.headerOf(section)),
                TvNativeExploreItem.Row(
                    tvProgressRowKey(section.group),
                    section.entries.map { entry ->
                        val info = entry.info
                        TvNativeCard(
                            imageUrl = info.subjectInfo.imageLarge,
                            title = tmdbTitles[info.subjectId] ?: info.subjectInfo.preferredDisplayName(useOriginalTitle),
                            subtitle = entry.cardSubtitle(strings, now, timeZone) { WeekFormatter.System.format(it) },
                            progress = tvCollectionCardProgress(info, histories),
                            subjectId = info.subjectId,
                        )
                    },
                ),
            )
        }.withNsfwCards(nsfw)
    }
    val currentSections by rememberUpdatedState(sections.orEmpty())
    fun entryAt(rowKey: String, index: Int): TvProgressEntry? =
        currentSections.firstOrNull { tvProgressRowKey(it.group) == rowKey }?.entries?.getOrNull(index)

    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnTopRowUp by rememberUpdatedState(onTopRowUp)
    val currentOnFocusChanged by rememberUpdatedState(onCardAreaFocusChanged)
    val listener = remember(state) {
        object : TvNativeShelfListener {
            override fun onCardFocused(rowKey: String, index: Int) {
                state.focusedRowKey = rowKey
                state.focusedCardIndex = index
                state.returnToCards = true
            }

            override fun onCardClick(rowKey: String, index: Int) {
                entryAt(rowKey, index)?.let { currentOnPlay(it.info) }
            }

            override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) {
                entryAt(rowKey, index)?.let { currentOnLongPress(it.info) }
            }

            override fun onTopRowUp(): Boolean {
                val handled = currentOnTopRowUp()
                if (handled) state.returnToCards = false
                return handled
            }

            override fun onExitLeft() {
                state.returnToCards = false
                railEnter?.requestFocus()
            }

            override fun onCardAreaFocusChanged(hasFocus: Boolean) {
                state.cardsFocused = hasFocus
                currentOnFocusChanged(hasFocus)
            }

            override fun onScrollingChanged(scrolling: Boolean) {
                scrollReporter?.setScrolling(scrolling)
            }
        }
    }

    // 不在首卡时按返回先回第一行第一张 (远跳; 还在滚又按返回 = 当场落地), 已在首卡交给上层
    val backToFirstCard by remember(state) {
        derivedStateOf {
            val first = state.view?.firstRowKey
            state.cardsFocused && first != null && (state.focusedRowKey != first || state.focusedCardIndex > 0)
        }
    }
    BackHandler(enabled = backToFirstCard) {
        val view = state.view ?: return@BackHandler
        if (view.landFarJump()) return@BackHandler
        view.firstRowKey?.let { view.focusCard(it, 0, far = true) }
    }

    Box(
        modifier.fillMaxSize().then(
            tvPlayKeyShortPress(
                onPlay = {
                    val entry = state.focusedRowKey?.takeIf { state.cardsFocused }?.let { entryAt(it, state.focusedCardIndex) }
                    if (entry != null) {
                        currentOnPlay(entry.info)
                        true
                    } else {
                        false
                    }
                },
            ),
        ),
    ) {
        TvNativeHost(
            factory = { context ->
                TvNativeShelfView(context, sketch, style, metrics, headerStyle).also { view ->
                    view.restore(
                        state.savedScrollPx, state.savedRowLeft, state.savedRowFocused,
                        state.focusedRowKey, state.focusedCardIndex, holdLook = state.returnToCards,
                    )
                    state.view = view
                }
            },
            update = { view ->
                view.listener = listener
                view.transitions = visualEffects.transitions
                view.animatedScroll = visualEffects.animatedScroll
                view.headerIdleAlpha = headerIdleAlpha
                view.update(style, metrics)
                view.setItems(items)
            },
            modifier = Modifier.focusRequester(state.focusRequester),
            // 海报墙大小一变整块重建 (卡片视图的尺寸建好就定了); 停位与各行停在哪张存在 state 里, 新视图照返回本页那样恢复
            rebuildKey = LocalTvPosterWallScale.current,
        )
        // 换走标签 (本页撤出组合) 前记下停位, 换回来照样恢复
        DisposableEffect(state) {
            onDispose {
                state.capture()
                state.view = null
            }
        }

        if (sections != null && sections.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(
                    start = TvNavigationRailDefaults.CollapsedWidth + TV_COLLECTION_START_PAD,
                    top = layout.listTop,
                    end = TV_PAGE_END_PAD,
                    bottom = 48.dp,
                ),
                contentAlignment = Alignment.Center,
            ) {
                val error = progress.error
                if (error != null) {
                    LoadErrorCard(
                        error,
                        onRetry = { progress.syncInBackground(force = true) },
                        Modifier.focusRequester(state.errorFocusRequester),
                    )
                } else {
                    Text(
                        stringResource(Lang.collection_tv_progress_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 追更页的几何: 列表顶线同追番页网格的顶线 (标签行下面), 卡片尺寸同网格 (随海报墙大小), 组标题与行距同探索页. */
internal class TvCollectionProgressLayout(val metrics: TvNativeExploreMetrics, val cardWidth: Dp, val listTop: Dp)

@Composable
internal fun tvCollectionProgressLayout(): TvCollectionProgressLayout {
    val density = LocalDensity.current
    val railWidth = TvNavigationRailDefaults.CollapsedWidth
    val windowSize = LocalWindowInfo.current.containerSize
    val pageWidth = with(density) { if (windowSize.width > 0) windowSize.width.toDp() else 960.dp } - railWidth
    val pageHeight = with(density) { if (windowSize.height > 0) windowSize.height.toDp() else 540.dp }
    val scale = LocalTvPosterWallScale.current
    val grid = with(density) { tvPosterWallGrid(tvGridPageWallContentWidth(), scale) }
    val cardHeight = grid.cardWidth / TV_PORTRAIT_CARD_COVER_RATIO
    val labelHeight = tvPosterWallLabelHeight() * scale
    val rowSpacing = TV_POSTER_WALL_ROW_SPACING * scale
    val listTop = TV_COLLECTION_TOP_PAD + TV_COLLECTION_TAB_ROW_HEIGHT + TV_TRANSIT_ANCHOR_SIZE + TV_PROGRESS_TABS_TO_LIST_GAP
    val headerBlock = TV_PROGRESS_HEADER_LINE + TV_POSTER_WALL_HEADER_GAP
    val metrics = with(density) {
        TvNativeExploreMetrics(
            pageWidthPx = pageWidth.roundToPx(),
            pageHeightPx = pageHeight.roundToPx(),
            bleedLeftPx = railWidth.roundToPx(),
            columns = grid.columns,
            listTopPx = listTop.roundToPx(),
            listTopBleedPx = TV_PROGRESS_LIST_TOP_BLEED.roundToPx(),
            listBottomBleedPx = (TV_POSTER_WALL_BOTTOM_BLEED + headerBlock).roundToPx(),
            spacerPx = 0,
            headerPx = headerBlock.roundToPx(),
            rowPx = (cardHeight + labelHeight + rowSpacing).roundToPx(),
            rowGapPx = rowSpacing.roundToPx(),
            viewportPx = (pageHeight - listTop).roundToPx(),
            endMarginPx = tvPosterWallEndMargin(cardHeight, TV_POSTER_WALL_CARD_FOCUS_STYLE.focusScale).roundToPx(),
            heroHeaderTopPx = 0,
            // 行首停靠线 = 网格第一列的左缘 (原生视图从屏幕左缘画起, 越过停靠线的卡从侧边栏底下滑出屏)
            rowStartPx = (railWidth + TV_GRID_START_BLEED).roundToPx(),
            endPadPx = TV_PAGE_END_PAD.roundToPx(),
            fadeDistancePx = TV_CARD_FADE_DISTANCE.toPx(),
            // 轮播与 hero 那几项本页不用
            carouselBottomPx = 0f,
            overhangPx = 0,
            backdropWidthPx = 0,
            backdropHeightPx = 0,
            cardBackdropScale = 1f,
            heroStartPx = 0,
            heroTopPx = 0,
            heroEndPadPx = 0,
            heroBlockPx = 0,
            heroBlockExpandedPx = 0,
            titleWidthPx = 0,
            carouselSummaryWidthPx = 0,
            cardSummaryWidthPx = 0,
            buttonsTopGapPx = 0,
            buttonGapPx = 0,
            dotsCenterYPx = 0,
            dotPx = 0f,
            dotSelectedWidthPx = 0f,
            dotGapPx = 0f,
            dotInactiveAlpha = 0f,
        )
    }
    return TvCollectionProgressLayout(metrics, grid.cardWidth, listTop)
}

internal fun tvProgressRowKey(group: TvProgressGroup): String = "progress-row-${group.name}"

private fun tvProgressHeaderKey(group: TvProgressGroup): String = "progress-header-${group.name}"

/** 标签行到列表顶线的间距, 同追番页网格 (聚焦卡放大时往上伸出的那截要盖得住). */
private val TV_PROGRESS_TABS_TO_LIST_GAP = 16.dp

/** 组标题那一行 (字的行高 23sp, 见 tvPosterWallHeaderStyle), 同探索页. */
private val TV_PROGRESS_HEADER_LINE = 24.dp

/** 列表向上出血: 离场的行越过顶线继续上移, 从标签行底下滑过 (同网格). */
private val TV_PROGRESS_LIST_TOP_BLEED = 120.dp
