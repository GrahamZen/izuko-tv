/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.schedule.grid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.number
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbMatchHints
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.ui.exploration.schedule.AiringScheduleColumnItem
import me.him188.ani.app.ui.exploration.schedule.AiringScheduleItemPresentation
import me.him188.ani.app.ui.exploration.schedule.ScheduleDay
import me.him188.ani.app.ui.exploration.schedule.ScheduleItemDefaults
import me.him188.ani.app.ui.exploration.schedule.SchedulePagePresentation
import me.him188.ani.app.ui.foundation.TvPageRefreshHandler
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusRail
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvGridFocus
import me.him188.ani.app.ui.foundation.focus.tvFocusMoveRateLimit
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.focus.tvFocusRailItem
import me.him188.ani.app.ui.foundation.focus.tvFocusRailKeys
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.navigation.OnReturnToForeground
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FADE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.TV_CARD_HERO_BACKDROP_GEOMETRY
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_FOCUS_BLEED
import me.him188.ani.app.ui.foundation.tv.TV_GRID_TOP_BLEED
import me.him188.ani.app.ui.foundation.tv.TV_NAV_LOCK_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_BOTTOM_BLEED
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_CARD_FOCUS_STYLE
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_ROW_SPACING
import me.him188.ani.app.ui.foundation.tv.TV_TAB_CONTENT_SLIDE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvFocusRing
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaSpec
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbor
import me.him188.ani.app.ui.foundation.tv.focusScale
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCardBadgeStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageCallbacks
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageHost
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallBackdropSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallBackdropTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeGridPageState
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.prefetchTvBackdrop
import me.him188.ani.app.ui.foundation.tv.rememberTvHeroMediaPipeline
import me.him188.ani.app.ui.foundation.tv.rememberTvSettledHeroProvider
import me.him188.ani.app.ui.foundation.tv.tvGlassBackground
import me.him188.ani.app.ui.foundation.tv.tvGridBleed
import me.him188.ani.app.ui.foundation.tv.tvGridNeighborsOf
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropUrl
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.tv.tvPageBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.tvPlayKeyShortPress
import me.him188.ani.app.ui.foundation.tv.tvPosterWallBackground
import me.him188.ani.app.ui.foundation.tv.tvPosterWallCardWidth
import me.him188.ani.app.ui.foundation.tv.tvPosterWallColumns
import me.him188.ani.app.ui.foundation.tv.tvPosterWallEndMargin
import me.him188.ani.app.ui.foundation.tv.tvPosterWallLabelHeight
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.rememberTvBesideAnchorPositionProvider
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_schedule_episode
import me.him188.ani.app.ui.lang.exploration_schedule_episode_ep_and_sort
import me.him188.ani.app.ui.lang.exploration_schedule_last_weekday
import me.him188.ani.app.ui.lang.exploration_schedule_next_weekday
import me.him188.ani.app.ui.lang.exploration_schedule_this_weekday
import me.him188.ani.app.ui.lang.exploration_schedule_time_unknown
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_friday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_monday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_saturday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_sunday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_thursday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_tuesday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_wednesday
import me.him188.ani.app.ui.lang.exploration_tv_schedule_empty
import me.him188.ani.app.ui.lang.exploration_tv_schedule_today
import me.him188.ani.app.ui.search.LoadErrorCard
import me.him188.ani.app.ui.subject.collection.TvCollectionGlassTab
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.analytics.Analytics
import me.him188.ani.utils.analytics.AnalyticsEvent.Companion.SubjectEnter
import me.him188.ani.utils.analytics.recordEvent
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * TV 新番时间表: 顶上一排日期 (玻璃胶囊, 15 天: 上周同日 ~ 下周同日) + 选中那天从早到晚的海报墙 (原生, 同追番页的卡片墙: 聚焦只放大加投影,
 * 不画框). 没有 hero 态: 卡片上按确定直接进详情页, 播放键直接播这一集. 本页是独立目的地 (探索页 hero 的「新番时间表」进来), 没有侧边栏,
 * 两侧留白同宽.
 *
 * 日期行照 tvOS 标签栏 (高 68pt、顶边离屏幕顶 46pt), 焦点进了海报墙就跟着内容 1:1 往上滚走, 回到第一行随滚动回来 (tvOS 的标签栏在内容只有
 * 一个主视图时也是随内容滚出屏幕).
 *
 * 焦点动线: 进页落在选中的日期 (今天), 下键进海报墙首卡, 首行上键回日期行. 日期行**聚焦即切换**, 左右键停下来
 * ([TV_SCHEDULE_DAY_SETTLE_MILLIS]) 才换下面的海报墙 (长按方向键一秒过好几天, 不每一天都换一整屏卡片). 海报墙里左右键按时间线性移动:
 * 行末按右接下一行行首, 行首按左接上一行行末, 走到全天两端再跨天 (全天最后一张按右 → 下一天第一张, 第一张按左 → 上一天最后一张),
 * 跨天当场换, 新旧两天水平滑过 (同追番页换标签).
 * 返回键逐层往回: 海报墙非首卡 → 首卡 (远跳) → 日期行 → 退出本页.
 *
 * 卡片: 海报 + 番名 + 一行「10:26 · 第 16 话」, 已播出的这一行是次要色, 还没播的是主题色 (深夜时当天几乎全播完, 压暗海报会让整屏发灰);
 * 在看 / 想看的番封面右上角有角标 (时间表最实际的用法是"我追的番更没更"). 长按弹收藏菜单.
 *
 * 整屏背景: 海报墙底下铺聚焦那部背景图的模糊版 (同 tvOS 的模糊底, 停下来才换). 点开时卡片与日期行淡没、背景 400ms 慢慢变清晰, 到位再进详情页
 * (放大转场全屏对全屏, 图原地不动; 这段时间顺手把详情页首屏的条目信息拉进缓存), 返回后倒放; 长按时别的卡淡没、背景变清晰, 菜单关了倒放.
 * 见 TvNativeGridPageView 的对焦一节.
 */
@Composable
fun TvScheduleGridPage(
    presentation: SchedulePagePresentation,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val setCollectionTypeUseCase = remember { GlobalKoin.get<SetSubjectCollectionTypeOrDeleteUseCase>() }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    val days = presentation.days
    val todayIndex = remember(days) {
        days.indexOfFirst { it.kind == ScheduleDay.Kind.TODAY }.coerceAtLeast(0)
    }
    var selectedDayIndex by rememberSaveable { mutableIntStateOf(todayIndex) }
    // 海报墙**实际显示**的那一天: 从日期行换天时它比 [selectedDayIndex] 落后一小段 (等按键停下来), 从海报墙跨天当场跟上.
    // 初值只读不订阅 (页面本身不因换天重组, 见下方日期行换天那条)
    var displayedDayIndex by remember { mutableIntStateOf(Snapshot.withoutReadObservation { selectedDayIndex }) }

    // 第 [dayIndex] 天的卡片. 上游已把"当前时刻指示器"插在正确位置 (仅今天有), 它之前的即已播出; 指示器不占卡片位.
    // 过去的日子整天都已播出, 未来的日子一部都还没播
    val dayCardsOf: (Int) -> List<TvScheduleCardData> = { dayIndex ->
        val columnItems = days.getOrNull(dayIndex)
            ?.let { day -> presentation.airingSchedules.firstOrNull { it.date == day.date }?.episodes }
            .orEmpty()
        val indicatorPos = columnItems.indexOfFirst { it is AiringScheduleColumnItem.CurrentTimeIndicator }
        val isPast = dayIndex < todayIndex
        columnItems.mapIndexedNotNull { pos, columnItem ->
            when (columnItem) {
                is AiringScheduleColumnItem.Data -> TvScheduleCardData(
                    columnItem.item,
                    aired = isPast || (indicatorPos >= 0 && pos < indicatorPos),
                )

                is AiringScheduleColumnItem.PlaceholderData -> TvScheduleCardData(null, aired = false)
                is AiringScheduleColumnItem.CurrentTimeIndicator -> null
            }
        }
    }
    val dayCards = remember(presentation, displayedDayIndex, todayIndex) { dayCardsOf(displayedDayIndex) }
    val dayItems = remember(dayCards) { dayCards.map { it.item } }
    // 第 [dayIndex] 天有几张能收落点的卡 (占位期间 0)
    val cardCountOf: (Int) -> Int = { dayIndex ->
        if (presentation.isPlaceholder) 0 else dayCardsOf(dayIndex).count { it.item != null }
    }

    // 收藏状态 (本地库, 无网络请求): 一个类型一条轻量 id 查询, 合成 subjectId -> 类型. 角标只认在看 / 想看; 完整类型给长按菜单用
    val collectionTypes by remember(collectionRepo) {
        flow<Map<Int, UnifiedCollectionType>> {
            val flows = TV_SCHEDULE_COLLECTION_TYPES.map { type ->
                collectionRepo.getSubjectIdsByCollectionType(listOf(type)).map { ids -> type to ids }
            }
            emitAll(
                combine(flows) { pairs ->
                    buildMap {
                        for ((type, ids) in pairs) for (id in ids) put(id, type)
                    }
                },
            )
        }
    }.collectAsStateWithLifecycle(emptyMap())

    // 聚焦那张的 TMDB 背景图 (与邻居的) 预取: 本页的整屏背景与详情页用的是同一张 —— 换到它 / 点进去时图多半已经在缓存里.
    // 连发合并、在途去重、邻居调度都在共用的 hero 媒体流水线里, 这里只借机械部分
    var focusedTarget by remember { mutableStateOf<TvHeroMediaSpec?>(null) }
    val cardNames = remember(dayCards) {
        dayCards.mapNotNull { it.item }
            .associate { it.subjectId to (it.subjectName.ifBlank { it.subjectTitle } to it.subjectTitle) }
    }
    val displayedDate = days.getOrNull(displayedDayIndex)?.date?.toString()
    val prefetchBackdrop: suspend (Int) -> Unit = { subjectId ->
        cardNames[subjectId]?.let { (name, titleCn) ->
            // 传播出日期: 新番刚播时 TMDB 往往还没有 backdrop, 负缓存据此限期失效
            tmdb.prefetchTvBackdrop(subjectId, name, activeAsOfDate = displayedDate, hints = TmdbMatchHints(nameCn = titleCn))
        }
    }
    rememberTvHeroMediaPipeline(
        tmdb = tmdb,
        fullVisualEffects = false,
        restartKey = Unit,
        spec = { focusedTarget },
        resolve = { spec -> prefetchBackdrop(spec.subjectId) },
        resolveNeighbor = { _, neighbor -> prefetchBackdrop(neighbor.subjectId) },
    )

    // ---- 焦点 ----
    val focus = rememberTvFocusScope()
    val gridFocus = rememberTvGridFocus(focus)
    val nativeState = rememberTvNativeGridPageState()
    val dateListState = rememberLazyListState()
    val errorCardFocusRequester = remember { FocusRequester() }
    var anyFocusObtained by remember { mutableStateOf(false) }
    // 当前聚焦的卡片下标 (跨导航保存: 从详情页返回本页时恢复); 日期行上换天时清掉
    var lastFocusedCard by rememberSaveable { mutableIntStateOf(-1) }
    var gridHasFocus by remember { mutableStateOf(false) }
    val focusSelectedDate: () -> Unit = {
        val index = selectedDayIndex
        focus.request(ScheduleDateFocus(index))
        // 胶囊还没组合出来: 先滚过去, 附着后请求自己送达 (聚焦时再居中, 见 TvScheduleCenterBringIntoView)
        if (dateListState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            scope.launch { runCatching { dateListState.scrollToItem(index) } }
        }
    }
    // 进页恢复期间 (组合 → 送焦有结果): 返回键按"在海报墙里"算, 见下方返回分层
    val restoreCardIndex = remember { lastFocusedCard }
    var restorePending by remember { mutableStateOf(restoreCardIndex >= 0) }

    // ---- 整屏背景 (见类说明): 聚焦那部的图, 停下来才换 (同其余页面的 hero 背景, 连发期间一次都不换) ----
    var focusedBackdrop by remember { mutableStateOf<TvScheduleBackdropFocus?>(null) }
    val backdropFocus = rememberTvSettledHeroProvider { focusedBackdrop }
    // 还没聚焦过卡片时 (焦点停在日期行) 的默认背景: 从今天的卡片里依次试, 第一张有横版图的顶上. 它不算那部自己的图: 不对焦、不登记放大
    var defaultBackdrop by remember { mutableStateOf<String?>(null) }
    val todayItems = remember(presentation, days, todayIndex) {
        days.getOrNull(todayIndex)
            ?.let { day -> presentation.airingSchedules.firstOrNull { it.date == day.date } }
            ?.episodes.orEmpty()
            .filterIsInstance<AiringScheduleColumnItem.Data>()
            .map { it.item }
    }
    LaunchedEffect(todayItems) {
        if (defaultBackdrop != null) return@LaunchedEffect
        val airDate = days.getOrNull(todayIndex)?.date?.toString()
        for (item in todayItems) {
            // 已经进了卡片区, 不必再找默认图
            if (focusedBackdrop != null) return@LaunchedEffect
            // 聚焦过 (或别的页面预取过) 的条目已经在服务层热表里, prefetch 会直接返回, 不重复请求
            tmdb.prefetchTvBackdrop(
                item.subjectId, item.subjectName.ifBlank { item.subjectTitle }, airDate,
                hints = TmdbMatchHints(nameCn = item.subjectTitle),
            )
            val url = tmdb.peekBackdropUrl(item.subjectId)
            if (url != null) {
                defaultBackdrop = url
                return@LaunchedEffect
            }
        }
    }
    // 恢复到某张卡期间不摆默认图: 返回时组合重建、聚焦目标回到初值, 先铺别人的图再跳成正确那张
    var restoreBlocksDefaultBackdrop by remember { mutableStateOf(restoreCardIndex >= 0) }
    val backdropTarget: () -> TvNativeWallBackdropTarget? = remember {
        var last: TvNativeWallBackdropTarget? = null
        {
            val focus = backdropFocus()
            val resolved = when {
                // 没有横版图时是它自己的竖版封面 (只铺模糊版, 裁成整屏太糊不变清晰)
                focus != null -> tmdb.tvHeroBackdropUrl(focus.subjectId, fullVisualEffects = false, coverUrl = focus.coverUrl)
                    ?.let { url -> TvNativeWallBackdropTarget(url, focus.subjectId, sharp = url != focus.coverUrl) }

                restoreBlocksDefaultBackdrop -> null
                else -> defaultBackdrop?.let { TvNativeWallBackdropTarget(it, subjectId = null, sharp = false) }
            }
            // 聚焦的那部还在解析: 先留着上一张 (模糊的底不误导, 淡出再淡入反倒晃)
            if (resolved == null && focus != null) last else resolved.also { last = it }
        }
    }

    LaunchedEffect(Unit) {
        if (restoreCardIndex >= 0) {
            gridFocus.focusItem(restoreCardIndex)
            // 等这次送焦有结果 (送达 / 用户接手 / 空列表被下面那条 effect 取消) —— 快照事件, 不轮询
            snapshotFlow { gridFocus.switching }.first { !it }
            restorePending = false
            // 送焦没成 (数据为空 / 出错): 焦点不能悬空, 否则全局兜底会乱塞
            if (!anyFocusObtained) focusSelectedDate()
        } else {
            focusSelectedDate()
        }
        // 落点有结果了才放开默认图: 送达时聚焦目标已是正确那张, 放开只为兜住"恢复失败" (那时该退回今天那张默认图)
        restoreBlocksDefaultBackdrop = false
    }
    // 从详情页 / 播放器返回本页时重新落点 (本页组合可能一直活着, 上面那条不会重跑)
    OnReturnToForeground("schedule") {
        if (!anyFocusObtained) return@OnReturnToForeground
        if (lastFocusedCard >= 0) gridFocus.focusItem(lastFocusedCard) else focusSelectedDate()
    }
    // 日期行上换天: 按键停下来才换海报墙 (见类说明). 海报墙跨天在按键处理里已经把两者对齐, 这里直接返回.
    // 换过去的那天从第一行排起 (不接着上回看到的位置): 焦点在日期行上, 下面的墙要是停在中间, 海报就顶到日期胶囊底下.
    // 选中的那天只在这个协程与日期行的胶囊里读, 页面本身不读: 长按日期行一秒换二十来天, 每换一天只重组选中态变了的两枚胶囊
    LaunchedEffect(Unit) {
        snapshotFlow { selectedDayIndex }.collectLatest { day ->
            if (displayedDayIndex == day) return@collectLatest
            delay(TV_SCHEDULE_DAY_SETTLE_MILLIS)
            nativeState.forgetPosition(day)
            displayedDayIndex = day
        }
    }
    // 日期行初始位置: 选中的那天居中 (从详情页返回时可能是跨天走到的某一天). 等一帧再算: 首帧 layoutInfo 还是空的
    LaunchedEffect(days.size) {
        withFrameNanos { }
        runCatching { dateListState.scrollToCenter(selectedDayIndex) }
    }
    // 数据到了但这一天是空的, 目标卡永远不会出现: 取消在途的送焦请求 (焦点停放在海报墙上时随之交回日期行, 见 TvNativeGridPageHost)
    LaunchedEffect(dayCards, presentation.isPlaceholder) {
        if (!presentation.isPlaceholder && dayCards.isEmpty() && gridFocus.switching) gridFocus.cancel()
    }

    // 前进导航的转场闸门 (同追番页): 导航发出之后本页还在转场里收按键, 不锁的话再按一次确认键连进两层
    var navLocked by remember { mutableStateOf(false) }
    val pageForeground = LocalPageIsForeground.current
    LaunchedEffect(pageForeground) {
        snapshotFlow { pageForeground.value }.collect { if (it) navLocked = false }
    }

    // 日期行往上滚走多少: 焦点在海报墙里时跟着网格报上来的内容滚动 (1:1), 不在时回原位. 焦点从海报墙交回日期行 (跨到空的一天、停放超时) 时
    // 从交回那一刻的位置滑回原位, 同网格滑动的时长, 不瞬移; 日期行正淡没 / 本页被盖着时直接回 (看不见, 返回本页时也不该动)
    val railReturn = remember { Animatable(0f) }
    var railReturnFrom by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(nativeState) {
        var had = gridHasFocus
        snapshotFlow { gridHasFocus }.collectLatest { has ->
            val from = nativeState.contentScroll.toFloat()
            val left = had && !has
            had = has
            if (left && from > 0f && nativeState.wallFade == 0f && pageForeground.value) {
                railReturnFrom = from
                railReturn.snapTo(1f)
                railReturn.animateTo(0f, tween(TV_TAB_CONTENT_SLIDE_MILLIS, easing = FastOutSlowInEasing))
            } else {
                railReturn.snapTo(0f)
            }
        }
    }
    val lockNavigation: () -> Unit = {
        navLocked = true
        scope.launch {
            delay(TV_NAV_LOCK_MILLIS)
            navLocked = false
        }
    }
    val navigateToSubject: (AiringScheduleItemPresentation) -> Unit = { item ->
        if (!navLocked) {
            Analytics.recordEvent(SubjectEnter) {
                put("source", "schedule_card")
                put("subject_id", item.subjectId)
            }
            lockNavigation()
            navigator.navigateSubjectDetails(
                subjectId = item.subjectId,
                placeholder = SubjectDetailPlaceholder(id = item.subjectId, name = item.subjectTitle, coverUrl = item.imageUrl),
            )
        }
    }
    // 点开 (整屏背景对焦) 一起手就把详情页首屏的条目信息拉进仓库缓存 (同探索页进详情前那道预取): 对焦那 400ms 正好盖住这次请求,
    // 详情页打开时本地命中, 不先转圈. 本地已有的条目当场返回, 不发请求
    val currentDayItems by rememberUpdatedState(dayItems)
    LaunchedEffect(nativeState) {
        snapshotFlow { nativeState.wallOpening }.collect { opening ->
            val item = if (opening) currentDayItems.getOrNull(lastFocusedCard) else null
            if (item != null) {
                scope.launch {
                    try {
                        collectionRepo.subjectCollectionFlow(item.subjectId).first()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 拉不到: 详情页照常自己拉
                    }
                }
            }
        }
    }
    // 播放键: 直接播这一集 (时间表给的就是某一集, 不用猜"下一集")
    val navigateToPlay: (AiringScheduleItemPresentation) -> Unit = { item ->
        if (!navLocked) {
            Analytics.recordEvent(SubjectEnter) {
                put("source", "schedule_play")
                put("subject_id", item.subjectId)
            }
            lockNavigation()
            navigator.navigateEpisodeDetails(item.subjectId, item.episodeId)
        }
    }

    // 海报墙跨天: 当场换 (日期行不等按键停下来), 焦点先停放在海报墙上, 新一天的卡一到就落过去 (见 TvNativeGridPageView)
    val switchDay: (delta: Int, toLastCard: Boolean) -> Unit = { delta, toLastCard ->
        val target = selectedDayIndex + delta
        if (target in days.indices) {
            gridFocus.focusItem(if (toLastCard) (cardCountOf(target) - 1).coerceAtLeast(0) else 0)
            selectedDayIndex = target
            displayedDayIndex = target
            // 日期行不含焦点, 不会自己跟着滚: 把新选中的那天带回视野, 之后按上 / 返回回日期行才落得上
            scope.launch { runCatching { dateListState.scrollToCenter(target) } }
        }
    }
    // 下键从日期行进海报墙: 换过天 (或这一天从没进过) 落首卡, 否则回到刚才看的那一行
    val enterGrid: () -> Boolean = {
        // 日期行上的换天还在等按键停下: 当场换 (从第一行排起, 同上), 焦点要进的是这一天
        if (displayedDayIndex != selectedDayIndex) {
            nativeState.forgetPosition(selectedDayIndex)
            displayedDayIndex = selectedDayIndex
        }
        if (cardCountOf(selectedDayIndex) > 0) {
            val view = nativeState.view
            val firstVisibleRow = view?.firstIndexBelowTopLine()?.let { it / (view.grid?.metrics?.columns ?: 1).coerceAtLeast(1) }
            gridFocus.focusRowEdge(if (lastFocusedCard >= 0 && firstVisibleRow != null) firstVisibleRow else 0, direction = 1)
        } else if (presentation.error != null) {
            runCatching { errorCardFocusRequester.requestFocus() }
        }
        // 没有卡也吃掉这一下: 交给默认方向搜索的落点不可预测
        true
    }

    // 返回键逐层往回: 海报墙非首卡 → 首卡 (远跳, 见 wallFarJump) → 日期行 → 退出本页 (最后一步不启用, 交给导航返回).
    // 三段接力判"在海报墙里": 组合 → 派出落点 (restorePending) → 焦点落位 (switching) → 之后 (gridHasFocus). derivedStateOf 收窄成布尔
    var wallFarJump by remember { mutableStateOf(false) }
    LaunchedEffect(gridFocus) {
        snapshotFlow { gridFocus.switching }.collect { if (!it) wallFarJump = false }
    }
    val inGrid by remember { derivedStateOf { gridHasFocus || gridFocus.switching || restorePending } }
    val backToFirstCard by remember { derivedStateOf { inGrid && lastFocusedCard > 0 } }
    val backToDates by remember { derivedStateOf { inGrid && lastFocusedCard <= 0 } }
    BackHandler(enabled = !navLocked && backToFirstCard) {
        wallFarJump = true
        gridFocus.focusItem(0)
    }
    BackHandler(enabled = !navLocked && backToDates) {
        gridFocus.cancel()
        focusSelectedDate()
    }

    // 长按卡片的收藏菜单 (锚在原生网格报上来的封面框旁边); 打开后吞掉长按残余的确认键, 避免误触第一项.
    // remember 无 key: 工厂交给原生网格的接线, 每次新实例都会让它跟着重组. 收藏类型在菜单里现读
    val collectionMenuFor: (AiringScheduleItemPresentation) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit = remember {
        { item ->
            { expanded, onDismiss ->
                EditCollectionTypeDropDown(
                    currentType = collectionTypes[item.subjectId] ?: UnifiedCollectionType.NOT_COLLECTED,
                    expanded = expanded,
                    onDismissRequest = onDismiss,
                    onClick = { action ->
                        scope.launch {
                            runCatching { setCollectionTypeUseCase(item.subjectId, action.type) }
                                .onFailure { toaster.showLoadError(LoadError.fromException(it)) }
                        }
                    },
                    modifier = Modifier.consumeHeldConfirmKey(),
                    positionProvider = rememberTvBesideAnchorPositionProvider(),
                )
            }
        }
    }

    // 动作面板「刷新本页」= 重拉整张时间表; 播放键短按播聚焦那一集 (挂在页面根上: 焦点在日期行时也能刷)
    TvPageRefreshHandler(onRetry)
    val playKeyModifier = tvPlayKeyShortPress(
        onPlay = {
            dayItems.getOrNull(lastFocusedCard)?.let { navigateToPlay(it); true } ?: false
        },
    )

    // ---- 卡片 ----
    val secondary = tvHeroSecondaryContentColor().toArgb()
    val upcoming = MaterialTheme.colorScheme.primary.toArgb()
    val timeUnknown = stringResource(Lang.exploration_schedule_time_unknown)
    val cards = dayCards.map { card ->
        card.item?.let { item ->
            TvNativeCard(
                imageUrl = item.imageUrl,
                title = item.subjectTitle,
                subtitle = ScheduleItemDefaults.renderTime(null, item.time, timeUnknownText = timeUnknown) + " · " + rememberEpisodeLabel(item),
                subtitleColor = if (card.aired) secondary else upcoming,
                subjectId = item.subjectId,
                badge = collectionTypes[item.subjectId] in TV_SCHEDULE_FOLLOWED_TYPES,
            )
        }
    }
    val density = LocalDensity.current
    val badgeIcon = rememberTvNativeIcon(Icons.Rounded.Favorite, TV_SCHEDULE_BADGE_ICON_SIZE, MaterialTheme.colorScheme.primary)
    val badgeBackground = MaterialTheme.colorScheme.surface.copy(alpha = TV_SCHEDULE_BADGE_BACKGROUND_ALPHA).toArgb()
    val badge = remember(badgeIcon, badgeBackground, density) {
        with(density) {
            TvNativeCardBadgeStyle(badgeIcon, TV_SCHEDULE_BADGE_SIZE.toPx(), TV_SCHEDULE_BADGE_INSET.toPx(), badgeBackground)
        }
    }

    // ---- 几何: 整屏是本页 (没有侧边栏), 照 tvOS 网格 (HIG Layout): 左右安全区 80pt、列距 40pt, 6 列时封面 260pt (1080p 下 40dp / 20dp / 130dp),
    // 顶上的日期行不影响横向排版. 列数的规则同追番页 (tvPosterWallColumns). 这几个量的都是封面: 卡格四周另有聚焦框空隙 (TvFocusRing.Gap),
    // 所以卡格比封面宽两份空隙、格距比列距窄两份空隙、网格起点往外让一份空隙 ----
    val windowSize = LocalWindowInfo.current.containerSize
    val pageWidth = with(density) { if (windowSize.width > 0) windowSize.width.toDp() else 960.dp }
    val pageHeight = with(density) { if (windowSize.height > 0) windowSize.height.toDp() else 540.dp }
    val coverContentWidth = pageWidth - TV_SCHEDULE_SIDE_PAD * 2
    val columns = with(density) { tvPosterWallColumns(coverContentWidth) }
    val coverWidth = tvPosterWallCardWidth(coverContentWidth, columns)
    val cardWidth = coverWidth + TvFocusRing.Gap * 2
    // 卡格高: 首屏上两行卡块加一个行距正好铺满网格顶线以下, 第二行番名的第二行底边落在屏幕底、整个露出来 (露出下一行, 但不把一行字切成两半).
    // 番名块定高随系统字号, 现算; 封面宽高比夹在 [TV_SCHEDULE_COVER_RATIO_NARROWEST] 与 [TV_SCHEDULE_COVER_RATIO_WIDEST] 之间 (界面缩放调小时
    // 页面变高, 不让封面拉得太长; 调大时放不下两行就照这个上限)
    val labelHeight = tvPosterWallLabelHeight()
    val cardHeight = with(density) {
        val gridTopPx = (TV_SCHEDULE_TOP_PAD + TV_SCHEDULE_DATE_RAIL_HEIGHT + TV_SCHEDULE_DATES_TO_GRID_GAP).roundToPx()
        val fitPx = (pageHeight.roundToPx() - gridTopPx - TV_POSTER_WALL_ROW_SPACING.roundToPx()) / 2 - labelHeight.roundToPx()
        val gapPx = (TvFocusRing.Gap * 2).roundToPx()
        val coverPx = coverWidth.toPx()
        fitPx.coerceIn(
            (coverPx / TV_SCHEDULE_COVER_RATIO_WIDEST).roundToInt() + gapPx,
            (coverPx / TV_SCHEDULE_COVER_RATIO_NARROWEST).roundToInt() + gapPx,
        ).toDp()
    }
    // 错误横幅的高度 (含上间距): 海报墙的顶线跟着它往下让
    var errorCardHeightPx by remember { mutableIntStateOf(0) }
    val metrics = with(density) {
        TvNativeGridPageMetrics(
            pageWidthPx = pageWidth.roundToPx(),
            pageHeightPx = pageHeight.roundToPx(),
            gridTopPx = (TV_SCHEDULE_TOP_PAD + TV_SCHEDULE_DATE_RAIL_HEIGHT + TV_SCHEDULE_DATES_TO_GRID_GAP).roundToPx() +
                if (presentation.error != null) errorCardHeightPx else 0,
            grid = TvNativeGridMetrics(
                columns = columns,
                startPx = (TV_SCHEDULE_SIDE_PAD - TvFocusRing.Gap).roundToPx(),
                endPx = (TV_SCHEDULE_SIDE_PAD - TvFocusRing.Gap).roundToPx(),
                topBleedPx = TV_GRID_TOP_BLEED.roundToPx(),
                bottomBleedPx = TV_POSTER_WALL_BOTTOM_BLEED.roundToPx(),
                endMarginPx = tvPosterWallEndMargin(cardHeight, TV_POSTER_WALL_CARD_FOCUS_STYLE.focusScale).roundToPx(),
                heroLinePx = 0,
                fadeDistancePx = TV_CARD_FADE_DISTANCE.toPx(),
            ),
            // 没有 hero 态: 背景图与 hero 文字不画
            backdropWidthPx = 0,
            backdropHeightPx = 0,
            heroLeftPx = 0,
            heroTopPx = 0,
            heroWidthPx = 0,
            heroHeightPx = 0,
            titleWidthPx = 0,
            summaryWidthPx = 0,
        )
    }
    val background = tvPosterWallBackground()
    // 番名的颜色 (同海报墙卡片): 背景按它压到看得清
    val titleColor = tvHeroContentColor()
    val wallBackdrop = remember(background, titleColor) {
        TvNativeWallBackdropSpec(backdropTarget, maskColor = background.copy(alpha = TV_FULLSCREEN_BACKDROP_DIM_ALPHA), textColor = titleColor)
    }

    Box(
        modifier.fillMaxSize()
            .background(background)
            // 方向 / 确认键即取消在途送焦; 全页只在这一处上报
            .tvFocusNavSignal(focus)
            .then(playKeyModifier),
    ) {
        TvNativeGridPageHost(
            state = nativeState,
            metrics = metrics,
            cardWidth = cardWidth,
            gridKey = displayedDayIndex,
            slideDirection = { from, to -> if (to > from) 1 else -1 },
            items = dayItems,
            cards = cards,
            focusableCount = { if (presentation.isPlaceholder) 0 else dayItems.size },
            heroEnabled = false,
            badge = badge,
            fadeColor = background,
            treatment = remember(background) {
                tvPageBackdropTreatment(1f, topScrim = true, fadeColor = background, geometry = TV_CARD_HERO_BACKDROP_GEOMETRY)
            },
            gridFocus = gridFocus,
            farJump = { wallFarJump },
            onFarJumpConsumed = { wallFarJump = false },
            callbacks = TvNativeGridPageCallbacks(
                onCardFocused = { index, item ->
                    lastFocusedCard = index
                    anyFocusObtained = true
                    if (item != null) {
                        focusedBackdrop = TvScheduleBackdropFocus(item.subjectId, item.imageUrl)
                        focusedTarget = TvHeroMediaSpec(
                            item.subjectId,
                            coverUrl = "",
                            neighbors = tvGridNeighborsOf(index, columns) { i ->
                                dayItems.getOrNull(i)?.let { TvHeroNeighbor(it.subjectId) }
                            },
                        )
                    }
                },
                onCardClick = { _, item -> navigateToSubject(item) },
                onTopRowUp = {
                    focusSelectedDate()
                    true
                },
                onRowEdge = { direction, row ->
                    // 时间是一条线: 行末按右接下一行行首, 行首按左接上一行行末, 走到全天两端再跨天. 已是第一天 / 最后一天也吃掉这一下
                    val count = dayItems.size
                    if (direction > 0) {
                        if (row < (count - 1) / columns) nativeState.view?.focusItem((row + 1) * columns)
                        else switchDay(1, false)
                    } else {
                        if (row > 0) nativeState.view?.focusItem(row * columns - 1)
                        else switchDay(-1, true)
                    }
                    true
                },
                onGridFocusChanged = { gridHasFocus = it },
                onScrollingChanged = {},
            ),
            menuFor = collectionMenuFor,
            wallBackdrop = wallBackdrop,
            // 返回本页恢复的那张 (见上方进页恢复): 建网格时就按住聚焦态
            landingIndex = restoreCardIndex,
            // 日期行 (连同错误横幅) 跟着海报墙一起滚走, 整块滚出屏幕为止
            topBarScrollAwayPx = metrics.gridTopPx,
            columnSpacing = TV_POSTER_WALL_COLUMN_SPACING - TvFocusRing.Gap * 2,
            cardHeight = cardHeight,
            // 番名压在整屏模糊背景上: 照 tvOS 的 vibrancy 画
            labelVibrancy = true,
        ) {
            // 空态: 这一天确实没有新番 (占位 / 出错各有自己的表现)
            if (cards.isEmpty() && !presentation.isPlaceholder && presentation.error == null) {
                Box(
                    Modifier.fillMaxSize().padding(top = with(density) { metrics.gridTopPx.toDp() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(Lang.exploration_tv_schedule_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxWidth()
                // 点开时日期行跟着卡片淡没; 焦点在海报墙里时跟着内容 1:1 往上滚走 (照 tvOS 标签栏: 内容只有一个主视图时标签栏随内容滚出屏幕),
                // 回第一行随滚动回来. 焦点在日期行上时不挪 (聚焦的胶囊不能滚出屏; 刚交回来时滑回原位, 见 railReturn). 都在绘制里读
                .graphicsLayer {
                    alpha = 1f - nativeState.wallFade
                    translationY = -(if (gridHasFocus) nativeState.contentScroll.toFloat() else railReturnFrom * railReturn.value)
                }
                .padding(start = TV_SCHEDULE_SIDE_PAD, top = TV_SCHEDULE_TOP_PAD),
        ) {
            TvScheduleDateRail(
                days = days,
                selectedIndex = { selectedDayIndex },
                onSelect = { index ->
                    // 日期行上的真实选择才清掉卡片位置 (海报墙跨天直接改 selectedDayIndex, 不经这里)
                    lastFocusedCard = -1
                    selectedDayIndex = index
                },
                focus = focus,
                listState = dateListState,
                onAnyFocused = {
                    anyFocusObtained = true
                    // 日期胶囊是本页的系统兜底落点 (同追番页的标签)
                    focus.notifyFocusFallbackSettled()
                },
                onNavigateDown = enterGrid,
                modifier = Modifier.fillMaxWidth().height(TV_SCHEDULE_DATE_RAIL_HEIGHT),
            )
            val error = presentation.error
            if (error != null) {
                LoadErrorCard(
                    error,
                    onRetry = onRetry,
                    Modifier.onSizeChanged { errorCardHeightPx = it.height }
                        .padding(top = TV_SCHEDULE_DATES_TO_GRID_GAP, end = TV_SCHEDULE_SIDE_PAD)
                        // 请求器挂容器上, requestFocus 委托给子树第一个焦点目标 (重试按钮); 按上键显式送回日期行
                        .focusRequester(errorCardFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                focusSelectedDate()
                                true
                            } else {
                                false
                            }
                        },
                )
            }
        }
    }
}

/**
 * 日期行: 15 天各一枚玻璃胶囊 (同追番页的标签: 选中垫浅灰片、聚焦浅色实底配黑字并抬起), 写「今天 / 周三 / 上周三」加日期; 放不下时横向滚,
 * 聚焦的那枚滚到居中 (两端夹住). **聚焦即切换**. 左右键显式在胶囊间移动, 两端与上键都吃掉 (本行已是最上面一层, 左边没有侧边栏).
 *
 * [selectedIndex] 传读数的函数, 各胶囊自己判断选没选中: 换天时只有选中态变了的两枚重组, 调用方与整行都不重组.
 */
@Composable
private fun TvScheduleDateRail(
    days: List<ScheduleDay>,
    selectedIndex: () -> Int,
    onSelect: (Int) -> Unit,
    focus: TvFocusScope,
    listState: LazyListState,
    onAnyFocused: () -> Unit,
    onNavigateDown: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // 胶囊可能还没组合出来 (那时 requestFocus 是静默 no-op): 走 scope 请求 (悬挂到附着再送), 不在屏上时先滚过去
    val rail = rememberTvFocusRail(
        scope = focus,
        keyAt = { index -> ScheduleDateFocus(index) },
        onMove = { target ->
            focus.request(ScheduleDateFocus(target))
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                scope.launch { runCatching { listState.scrollToItem(target) } }
            }
        },
    )
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvScheduleCenterBringIntoView) {
        LazyRow(
            modifier
                // 往左出血到屏幕左缘 (行首位置不变): 滑过去的胶囊切在屏幕边上, 不切在留白线上; 聚焦的那枚放大、投影伸出胶囊外也不被裁
                .tvGridBleed(start = TV_SCHEDULE_SIDE_PAD)
                // 长按方向键的移动频率上限 (全局上限, 高于系统连发). 聚焦即切换, 海报墙等按键停下来才换 (见 TV_SCHEDULE_DAY_SETTLE_MILLIS)
                .tvFocusMoveRateLimit()
                .tvFocusRailKeys(
                    state = rail,
                    itemCount = { days.size },
                    onNavigateDown = onNavigateDown,
                    onNavigateUp = { true },
                    consumeLeftEdge = true,
                ),
            state = listState,
            contentPadding = PaddingValues(start = TV_SCHEDULE_SIDE_PAD, end = TV_GLASS_FOCUS_BLEED),
            horizontalArrangement = Arrangement.spacedBy(TV_SCHEDULE_DATE_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(days, key = { _, day -> day.date.toString() }) { index, day ->
                val interactionSource = remember { MutableInteractionSource() }
                var focused by remember { mutableStateOf(false) }
                val selected by remember(index, selectedIndex) { derivedStateOf { index == selectedIndex() } }
                TvCollectionGlassTab(
                    label = renderTvScheduleWeekday(day),
                    selected = selected,
                    focused = focused,
                    detail = "${day.date.month.number}/${day.date.day}",
                    modifier = Modifier
                        // 胶囊照 tvOS 标签栏的高度 (见 TV_SCHEDULE_DATE_RAIL_HEIGHT), 字竖向居中
                        .height(TV_SCHEDULE_DATE_RAIL_HEIGHT)
                        .tvGlassBackground(CircleShape)
                        // 无条件挂: 链上元素个数恒定, 选中态变化不会重建其后的焦点节点
                        .tvFocusRailItem(
                            state = rail,
                            index = index,
                            onFocusChanged = { f ->
                                focused = f
                                if (f) onAnyFocused()
                            },
                            onSelectByFocus = { onSelect(index) },
                        )
                        .clickable(interactionSource, indication = null) { onSelect(index) },
                )
            }
        }
    }
}

private data class ScheduleDateFocus(val index: Int) : TvFocusKey

/** 日期行: 聚焦的胶囊滚到居中 (LazyRow 在两端自己夹住), 不按默认的"最小滚动到可见" —— 那样落位取决于上一次滚到哪, 观感随机. */
private val TvScheduleCenterBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2f - containerSize / 2f
}

/** 把第 [index] 项滚到居中 (不在屏上先滚到它, 下一帧按实际宽度对中; 两端夹住). */
private suspend fun LazyListState.scrollToCenter(index: Int) {
    if (index < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
        withFrameNanos { }
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    scrollBy((item.offset + item.size / 2f - center).roundToInt().toFloat())
}

/** "第 16 话" / "第 16 (28) 话" (语义同 [ScheduleItemDefaults.Episode], 但只要集号不要集名). */
@Composable
private fun rememberEpisodeLabel(item: AiringScheduleItemPresentation): String {
    val sortText = item.episodeSort.toString().removePrefix("0")
    val epText = item.episodeEp?.toString()?.removePrefix("0")
    return if (item.episodeEp == null || item.episodeEp == item.episodeSort) {
        stringResource(Lang.exploration_schedule_episode, sortText)
    } else {
        stringResource(Lang.exploration_schedule_episode_ep_and_sort, epText!!, sortText)
    }
}

/** "上周三" / "今天" / "周三" / "下周三". */
@Composable
private fun renderTvScheduleWeekday(day: ScheduleDay): String {
    if (day.kind == ScheduleDay.Kind.TODAY) return stringResource(Lang.exploration_tv_schedule_today)
    val weekday = when (day.dayOfWeek) {
        DayOfWeek.MONDAY -> stringResource(Lang.exploration_schedule_weekday_monday)
        DayOfWeek.TUESDAY -> stringResource(Lang.exploration_schedule_weekday_tuesday)
        DayOfWeek.WEDNESDAY -> stringResource(Lang.exploration_schedule_weekday_wednesday)
        DayOfWeek.THURSDAY -> stringResource(Lang.exploration_schedule_weekday_thursday)
        DayOfWeek.FRIDAY -> stringResource(Lang.exploration_schedule_weekday_friday)
        DayOfWeek.SATURDAY -> stringResource(Lang.exploration_schedule_weekday_saturday)
        else -> stringResource(Lang.exploration_schedule_weekday_sunday)
    }
    return when (day.kind) {
        ScheduleDay.Kind.LAST_WEEK -> stringResource(Lang.exploration_schedule_last_weekday, weekday)
        ScheduleDay.Kind.NEXT_WEEK -> stringResource(Lang.exploration_schedule_next_weekday, weekday)
        else -> stringResource(Lang.exploration_schedule_this_weekday, weekday)
    }
}

/** 整屏背景跟着的聚焦条目: [coverUrl] = 它的竖版封面 (没有横版图时铺它的模糊版, 见 tvHeroBackdropUrl). */
private data class TvScheduleBackdropFocus(
    val subjectId: Int,
    val coverUrl: String,
)

/** 海报墙一格的数据; [item] 为 null 表示占位 (首屏加载中). [aired] = 该集已播出. */
private data class TvScheduleCardData(
    val item: AiringScheduleItemPresentation?,
    val aired: Boolean,
)

/** 需要查询本地收藏类型的全部类型 (供长按菜单显示当前状态). */
private val TV_SCHEDULE_COLLECTION_TYPES = listOf(
    UnifiedCollectionType.WISH,
    UnifiedCollectionType.DOING,
    UnifiedCollectionType.ON_HOLD,
    UnifiedCollectionType.DONE,
    UnifiedCollectionType.DROPPED,
)

/** 卡片角标计入的收藏类型. */
private val TV_SCHEDULE_FOLLOWED_TYPES = setOf(UnifiedCollectionType.DOING, UnifiedCollectionType.WISH)

/**
 * 页面两侧留白 (到封面边): 本页没有侧边栏, 照 tvOS 安全区左右 80pt (HIG Layout; 1080p 下 1pt = 0.5dp). 日期行从这里排起, 与封面左缘对齐;
 * 滚动时两头都铺到屏幕边缘.
 */
private val TV_SCHEDULE_SIDE_PAD = 40.dp

/** 封面宽高比的上下限 (卡格高按首屏放下两行现算, 见几何那一段): 最窄 2:3 (tvOS 海报), 最宽 3:4. */
private const val TV_SCHEDULE_COVER_RATIO_NARROWEST = 2f / 3f
private const val TV_SCHEDULE_COVER_RATIO_WIDEST = 0.75f

/** 页顶到日期行: 照 tvOS 标签栏, 顶边离屏幕顶 46pt (HIG Tab bars 的 tvOS 一节; 1080p 下 1pt = 0.5dp). */
private val TV_SCHEDULE_TOP_PAD = 23.dp

/** 日期行 (胶囊) 的高度: 照 tvOS 标签栏高 68pt. */
private val TV_SCHEDULE_DATE_RAIL_HEIGHT = 34.dp

/** 日期行 (及错误横幅) 到海报墙顶线. 聚焦卡放大时顶边向上伸出 7.6~9dp, 这段要盖得住. */
private val TV_SCHEDULE_DATES_TO_GRID_GAP = 16.dp

/** 日期胶囊之间的间距. */
private val TV_SCHEDULE_DATE_SPACING = 8.dp

/**
 * 日期行上换天要等按键停下来多久才换海报墙: 远长于长按方向键的连发间隔 (系统约 50ms 一发), 长按期间一次都不换; 松手后这么久海报墙跟上.
 */
private const val TV_SCHEDULE_DAY_SETTLE_MILLIS = 200L

/** 在追角标: 直径、离封面上缘与右缘、图标、底色不透明度 (压在海报上要托得住图标). */
private val TV_SCHEDULE_BADGE_SIZE = 18.dp
private val TV_SCHEDULE_BADGE_INSET = 5.dp
private val TV_SCHEDULE_BADGE_ICON_SIZE = 11.dp
private const val TV_SCHEDULE_BADGE_BACKGROUND_ALPHA = 0.75f
