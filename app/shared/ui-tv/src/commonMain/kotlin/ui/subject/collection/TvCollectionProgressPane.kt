/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import me.him188.ani.app.ui.foundation.tv.nativeview.TvRowWallSurface
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.preferredDisplayName
import me.him188.ani.app.tools.WeekFormatter
import me.him188.ani.app.ui.foundation.focus.TV_TRANSIT_ANCHOR_SIZE
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.FillTvTmdbTextsFromCache
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
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
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageRows
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeRowWallListener
import me.him188.ani.app.ui.foundation.tv.nativeview.withNsfwCards
import me.him188.ani.app.ui.foundation.tv.rememberTvScrollActivityReporter
import me.him188.ani.app.ui.foundation.tv.rememberTvTmdbTitles
import me.him188.ani.app.ui.foundation.tv.tvGridPageWallContentWidth
import me.him188.ani.app.ui.foundation.tv.tvPosterWallEndMargin
import me.him188.ani.app.ui.foundation.tv.tvPosterWallGrid
import me.him188.ani.app.ui.foundation.tv.tvPosterWallLabelHeight
import me.him188.ani.app.ui.foundation.tv.tvPosterWallSecondaryLabelAlpha
import me.him188.ani.app.ui.foundation.tv.tvUseOriginalTitle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.collection_tv_progress_empty
import me.him188.ani.app.ui.search.LoadErrorCard
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/*
 * 追番页「追更」页: 标签行下面一组一行的海报墙 (组见 TvCollectionProgressModel.kt). 画面与焦点在网格页的一份内容里 (TvNativeGridPageView.rows:
 * 行式海报墙的宾客模式, 与各分类的网格换着显示、换标签时一起滑), hero 态、背景图与文字、点开进详情页都是网格页那一套, 同各分类的网格.
 * 卡片墙上按确定进 hero 态 (「海报上按确定」不是先看简介时同网格: 直接播放 / 进详情页), hero 态里按确定进详情页, 长按播下一集.
 * 本文件管: 卡片与组标题、几何、焦点簿记 (返回本页 / 换回本标签时回到上次那张)、返回键回首卡、空页与出错提示.
 */

/**
 * 追更页在页面这一侧的状态 (跨导航保存): 接着线的那份内容、列表停位、各行行首与上次停的那张、上次聚焦的那张、回来时回不回卡片区.
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
    /** 接着线的那份内容 (网格页建的, 见 TvNativeGridPageState.rows); 「追更」没选中时 null. */
    var view: TvRowWallSurface? by mutableStateOf(null)
        internal set

    /** 已按本状态恢复过的那份内容: 换回本标签时它还停在原处, 不再恢复; 网格页重建换了新的才再恢复一次. */
    internal var restoredView: TvRowWallSurface? = null
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

    /**
     * 把焦点送进卡片区: 上次那张 (没进过就是第一行). 从旁边标签的网格行缘过来时: [row] 给了 (hero 态) 落在第几行、[nearY] 给了 (卡片墙)
     * 落在纵向离它 (窗口坐标) 最近的那一行, 都是那一行屏上的行缘那张 ([fromLeft] = 从左边过来, 落最左边那张; 否则最右边那张;
     * 见 [TvNativeRowWallView.enterRow] / [TvNativeRowWallView.enterNearRow]).
     */
    fun focusCards(nearY: Int? = null, row: Int? = null, fromLeft: Boolean = false): Boolean {
        val v = view ?: return false
        if (row != null) v.enterRow(row, fromLeft) else if (nearY != null) v.enterNearRow(nearY, fromLeft)
        return v.requestCardsFocus()
    }

    /** 空页时把焦点送进出错卡片 (登录 / 重试). */
    fun focusError(): Boolean = runCatching { errorFocusRequester.requestFocus() }.isSuccess

    /** 记下内容此刻的停位与各行位置 (页面重建 / 换走标签时). */
    internal fun capture() {
        val v = view ?: return
        savedScrollPx = v.scrolledPx
        savedRowLeft.clear()
        savedRowLeft.putAll(v.rowLeftIndex)
        savedRowFocused.clear()
        savedRowFocused.putAll(v.rowFocusedIndexMap)
    }
}

/** 焦点此刻所在的那部 (播放键播它的下一集); 焦点不在卡片区时 null. */
internal fun TvCollectionProgressPaneState.focusedInfo(sections: List<TvProgressSection>?): SubjectCollectionInfo? {
    val rowKey = focusedRowKey?.takeIf { cardsFocused } ?: return null
    return sections?.firstOrNull { tvProgressRowKey(it.group) == rowKey }?.entries?.getOrNull(focusedCardIndex)?.info
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
 * 「追更」在网格页里那份内容的卡片、几何与组标题 (见 [TvNativeGridPageRows]), 占网格页的 [key];
 * 没有「追更」的端 (见 tvCollectionProgressAvailable) 给 null.
 */
@Composable
internal expect fun rememberTvCollectionProgressRows(key: Int): TvNativeGridPageRows?

/**
 * 「追更」对网格页里那份内容 ([view], 见 TvNativeGridPageState.rows) 的接线: 数据、事件、焦点簿记、返回键回首卡, 外加空页与出错提示.
 * [heroActive] = 网格页在不在 hero 态 (在的话返回键先退 hero 态, 不回首卡); [onPlay] = 播下一集 (长按), [onDetails] = 进详情页 (hero 态里按确定),
 * [onFocused] = 卡片拿到焦点 (页面据此算 hero 内容), [onTopRowUp] = 首行按上 (回标签行), [onExitLeft] / [onExitRight] = 行首按左 / 行尾按右
 * (换到旁边的标签, 左边没有时进侧边栏), [onCardAreaFocusChanged] = 焦点进出卡片区.
 */
@Composable
internal fun TvCollectionProgressPane(
    progress: TvCollectionProgressState,
    state: TvCollectionProgressPaneState,
    view: TvRowWallSurface?,
    heroActive: () -> Boolean,
    playHistories: () -> List<EpisodeHistory>,
    onPlay: (SubjectCollectionInfo) -> Unit,
    onDetails: (SubjectCollectionInfo) -> Unit,
    onFocused: (SubjectCollectionInfo) -> Unit,
    onTopRowUp: () -> Boolean,
    onExitLeft: () -> Unit,
    onExitRight: () -> Unit,
    onCardAreaFocusChanged: (Boolean) -> Unit,
) {
    val layout = tvCollectionProgressLayout()
    val headerIdleAlpha = tvPosterWallSecondaryLabelAlpha()
    val scrollReporter = rememberTvScrollActivityReporter()
    val touchInput = LocalTvTouchInputEnabled.current
    // 「海报上按确定」(见 TvPosterConfirmAction): 先看简介这一档才有 hero 态, 别的档卡片墙上按确定直接播放 / 进详情页 (同网格)
    val posterConfirm = LocalThemeSettings.current.tvPosterConfirm

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
                            count = entry.badgeCount,
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
    val currentOnDetails by rememberUpdatedState(onDetails)
    val currentOnFocused by rememberUpdatedState(onFocused)
    val currentOnTopRowUp by rememberUpdatedState(onTopRowUp)
    val currentOnExitLeft by rememberUpdatedState(onExitLeft)
    val currentOnExitRight by rememberUpdatedState(onExitRight)
    val currentOnFocusChanged by rememberUpdatedState(onCardAreaFocusChanged)
    val currentPosterConfirm by rememberUpdatedState(posterConfirm)
    val listener = remember(state) {
        object : TvNativeRowWallListener {
            override fun onCardFocused(rowKey: String, index: Int, column: Int) {
                state.focusedRowKey = rowKey
                state.focusedCardIndex = index
                state.returnToCards = true
                entryAt(rowKey, index)?.let { currentOnFocused(it.info) }
            }

            // hero 态里的确定 = 进详情页 (卡片墙上的确定由视图进 hero 态, 不回调); 没有 hero 态时照「海报上按确定」(同网格)
            override fun onCardClick(rowKey: String, index: Int) {
                val info = entryAt(rowKey, index)?.info ?: return
                val play = state.view?.heroActive != true && currentPosterConfirm == TvPosterConfirmAction.Play
                if (play) currentOnPlay(info) else currentOnDetails(info)
            }

            // 长按 = 播下一集 (卡片墙上与 hero 态里一样)
            override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) {
                val info = entryAt(rowKey, index)?.info ?: return
                currentOnPlay(info)
            }

            override fun onBindCard(rowKey: String, index: Int) = Unit

            override fun onTopRowUp(): Boolean {
                val handled = currentOnTopRowUp()
                if (handled) state.returnToCards = false
                return handled
            }

            override fun onExitLeft() {
                state.returnToCards = false
                currentOnExitLeft()
            }

            override fun onExitRight() {
                state.returnToCards = false
                currentOnExitRight()
            }

            // hero 态、整屏黑度由网格页报 (宾客模式不回调这两样)
            override fun onHeroActiveChanged(active: Boolean) = Unit

            override fun onToneChanged(tone: Float, splitY: Float) = Unit

            override fun onCardAreaFocusChanged(hasFocus: Boolean) {
                state.cardsFocused = hasFocus
                currentOnFocusChanged(hasFocus)
            }

            override fun onScrollingChanged(scrolling: Boolean) {
                scrollReporter?.setScrolling(scrolling)
            }
        }
    }

    // 接上一份内容 (网格页新建的): 按存下的位置恢复一次; 换走标签 (本页撤出组合) 前记下停位, 换回来照样恢复
    DisposableEffect(view) {
        if (view != null && state.restoredView !== view) {
            view.restore(
                state.savedScrollPx, state.savedRowLeft, state.savedRowFocused, state.focusedRowKey, state.focusedCardIndex,
                holdLook = state.returnToCards,
            )
            state.restoredView = view
        }
        state.view = view
        onDispose {
            state.capture()
            if (state.view === view) state.view = null
        }
    }
    SideEffect {
        view?.let { v ->
            v.rowListener = listener
            v.heroEnabled = posterConfirm == TvPosterConfirmAction.Hero
            v.hideHeadersInHero = true
            v.headerIdleAlpha = headerIdleAlpha
            v.touchInput = touchInput
            v.setItems(items)
        }
    }

    // 不在首卡时按返回先回第一行第一张 (远跳; 还在滚又按返回 = 当场落地), 已在首卡交给上层. hero 态里返回先退 hero 态 (网格页那一条, 同网格)
    val backToFirstCard by remember(state) {
        derivedStateOf {
            val first = state.view?.firstRowKey()
            !heroActive() && state.cardsFocused && first != null && (state.focusedRowKey != first || state.focusedCardIndex > 0)
        }
    }
    BackHandler(enabled = backToFirstCard) {
        val v = state.view ?: return@BackHandler
        if (v.landFarJump()) return@BackHandler
        v.firstRowKey()?.let { v.focusCard(it, 0, far = true) }
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

/** 追更页的几何: 列表顶线同追番页网格的顶线 (标签行下面), 卡片尺寸同网格 (随海报墙大小), 组标题与行距同探索页, hero 态同网格. */
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
    // hero 态照网格 (同一页的几类一个样): 背景图、简介块、聚焦行停的那条线都取网格的
    val wall = tvCollectionWallLayout().metrics
    val metrics = with(density) {
        val listTopPx = listTop.roundToPx()
        val headerPx = headerBlock.roundToPx()
        TvNativeExploreMetrics(
            pageWidthPx = pageWidth.roundToPx(),
            pageHeightPx = pageHeight.roundToPx(),
            bleedLeftPx = railWidth.roundToPx(),
            columns = grid.columns,
            listTopPx = listTopPx,
            listTopBleedPx = TV_PROGRESS_LIST_TOP_BLEED.roundToPx(),
            listBottomBleedPx = (TV_POSTER_WALL_BOTTOM_BLEED + headerBlock).roundToPx(),
            spacerPx = 0,
            headerPx = headerPx,
            rowPx = (cardHeight + labelHeight + rowSpacing).roundToPx(),
            rowGapPx = rowSpacing.roundToPx(),
            viewportPx = (pageHeight - listTop).roundToPx(),
            endMarginPx = tvPosterWallEndMargin(cardHeight, TV_POSTER_WALL_CARD_FOCUS_STYLE.focusScale).roundToPx(),
            // hero 态聚焦行的组标题停在哪 (从列表顶线算): 行顶落在网格 hero 态那一行的位置 (网格顶线 + 网格的 hero 线), 组标题在它上面
            heroHeaderTopPx = wall.gridTopPx + wall.grid.heroLinePx - listTopPx - headerPx,
            // 行首停靠线 = 网格第一列的左缘 (原生视图从屏幕左缘画起, 越过停靠线的卡从侧边栏底下滑出屏)
            rowStartPx = (railWidth + TV_GRID_START_BLEED).roundToPx(),
            endPadPx = TV_PAGE_END_PAD.roundToPx(),
            fadeDistancePx = TV_CARD_FADE_DISTANCE.toPx(),
            // 没有轮播; 背景图与 hero 文字画在网格页里 (几何取网格的), 下面几项不用
            carouselBottomPx = 0f,
            overhangPx = 0,
            backdropWidthPx = wall.backdropWidthPx,
            backdropHeightPx = wall.backdropHeightPx,
            cardBackdropScale = 1f,
            heroStartPx = wall.heroLeftPx,
            heroTopPx = wall.heroTopPx,
            heroEndPadPx = wall.pageWidthPx - wall.heroLeftPx - wall.heroWidthPx,
            heroBlockPx = wall.heroHeightPx,
            heroBlockExpandedPx = wall.heroHeightPx,
            titleWidthPx = wall.titleWidthPx,
            carouselSummaryWidthPx = wall.summaryWidthPx,
            cardSummaryWidthPx = wall.summaryWidthPx,
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
