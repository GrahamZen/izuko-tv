/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.schedule

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import me.him188.ani.app.data.models.subject.CanonicalTagKind
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.kind
import me.him188.ani.app.data.network.BangumiSummaryService
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbMatchHints
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.ui.external.placeholder.PlaceholderHighlight
import me.him188.ani.app.ui.external.placeholder.fade
import me.him188.ani.app.ui.external.placeholder.placeholder
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.TvPageRefreshHandler
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.focus.TvFocusTransitAnchor
import me.him188.ani.app.ui.foundation.focus.TvScrollAnimator
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvGridFocus
import me.him188.ani.app.ui.foundation.focus.tvFocusMoveRateLimit
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.focus.tvGridFocusItem
import me.him188.ani.app.ui.foundation.navigation.OnReturnToForeground
import me.him188.ani.app.ui.foundation.rememberAsyncImageRetryState
import me.him188.ani.app.ui.foundation.rememberImageCompletionGrace
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.ReportTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.TV_HERO_MEDIA_DEBOUNCE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_PORTRAIT_CARD_COVER_RATIO
import me.him188.ani.app.ui.foundation.tv.TvFocusRing
import me.him188.ani.app.ui.foundation.tv.TvFullScreenBackdropLayer
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaCache
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaSpec
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbor
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbors
import me.him188.ani.app.ui.foundation.tv.prefetchTvBackdrop
import me.him188.ani.app.ui.foundation.tv.prefetchTvSummaryFallback
import me.him188.ani.app.ui.foundation.tv.rememberTvHeroMediaPipeline
import me.him188.ani.app.ui.foundation.tv.rememberTvSettledHeroProvider
import me.him188.ani.app.ui.foundation.tv.tvFocusRingBorder
import me.him188.ani.app.ui.foundation.tv.tvGridNeighborsOf
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropUrl
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.tv.tvPlayKeyShortPress
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
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
import me.him188.ani.app.ui.lang.exploration_tv_schedule_aired
import me.him188.ani.app.ui.lang.exploration_tv_schedule_empty
import me.him188.ani.app.ui.lang.exploration_tv_schedule_following
import me.him188.ani.app.ui.lang.exploration_tv_schedule_no_rating
import me.him188.ani.app.ui.lang.exploration_tv_schedule_no_tags
import me.him188.ani.app.ui.lang.exploration_tv_schedule_not_aired
import me.him188.ani.app.ui.lang.exploration_tv_schedule_now
import me.him188.ani.app.ui.lang.exploration_tv_schedule_starting
import me.him188.ani.app.ui.lang.exploration_tv_schedule_starts_in_hours
import me.him188.ani.app.ui.lang.exploration_tv_schedule_starts_in_minutes
import me.him188.ani.app.ui.lang.exploration_tv_schedule_today
import me.him188.ani.app.ui.lang.exploration_tv_schedule_total
import me.him188.ani.app.ui.lang.exploration_tv_schedule_total_episodes
import me.him188.ani.app.ui.lang.exploration_tv_schedule_total_episodes_unknown
import me.him188.ani.app.ui.lang.exploration_tv_schedule_upcoming
import me.him188.ani.app.ui.lang.subject_collection_uncollected
import me.him188.ani.app.ui.search.LoadErrorCard
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.app.ui.subject.collection.components.renderCollectionTypeAsCurrent
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.analytics.Analytics
import me.him188.ani.utils.analytics.AnalyticsEvent.Companion.SubjectEnter
import me.him188.ani.utils.analytics.recordEvent
import org.jetbrains.compose.resources.stringResource

/**
 * TV 新番时间表: 左栏 (整列归选中那一部: 竖版封面 + 封面右边一条短信息 + 下面中日两行番名与简介) + 右栏 (15 天接成的
 * 一条时间线), 背后是选中那一部的全屏 TMDB 横版图.
 *
 * 2026-09-13 三轮改版 (用户: "字太小, 图也看不出什么, 得一点点盯着看才能找到自己关心的动画在什么时间";
 * 第二轮: "背景改成选中动画的横屏图、左栏只留竖屏图信息全放下面并占满整个左边、焦点框固定、条目左边的图压成圆角方形";
 * 第三轮: 左栏重排 (见 [TvScheduleDetailPanel]), 并去掉右栏顶上的日期胶囊行 —— 时间线里每天开头有日期标签、左右键直接
 * 换天、左栏顶上一直写着选中那部是哪天, 那一行只剩重复 (用户: "直接左右翻日期就可以吧"); 时间线因此多露一格):
 *  - 时间线把 15 天接成一条, 每天开头一行日期标签跟着条目滚; 焦点框**钉在第二格不动**, 条目从框下滑过
 *    (列表首尾补白到任意一格都能停进框里, `scrollToItem(i)` 就是"把第 i 格送进框");
 *  - "选中的那一部" = 焦点在时间线里时聚焦那部, 还没进过时间线 (首屏载入中) 时是进时间线要落的那部.
 *    全屏背景与左栏都跟它走.
 *
 * 本页是**独立目的地** (探索页 hero 的"新番时间表"入口进来, 见 NavRoutes.Schedule), 整屏归自己,
 * 没有侧边栏也没有标签行 —— 出口只有返回键.
 *
 * 焦点动线: 数据到之前焦点停在隐形锚点上, 到了落在今天待播里你追的下一部 (没有就下一部待播). 上下逐部走 (跨天接着走),
 * 左右跳到前一天 / 后一天的同一时刻附近 (空天跳过); 最早那部按上、最后那部按下都不动. 返回键直接退出本页.
 */
@Composable
fun TvSchedulePage(
    presentation: SchedulePagePresentation,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val setCollectionTypeUseCase = remember { GlobalKoin.get<SetSubjectCollectionTypeOrDeleteUseCase>() }
    val bangumiSummaryService = remember { GlobalKoin.get<BangumiSummaryService>() }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    val days = presentation.days
    val todayIndex = remember(days) {
        days.indexOfFirst { it.kind == ScheduleDay.Kind.TODAY }.coerceAtLeast(0)
    }
    val timeline = remember(presentation, todayIndex) { buildTvScheduleTimeline(presentation, todayIndex) }
    // 给"只取首帧那个 lambda"的回调读 (焦点预取流水线的 spec 就是这样), 直接捕获 timeline 会永远停在首帧
    val currentTimeline by rememberUpdatedState(timeline)
    val currentTodayIndex by rememberUpdatedState(todayIndex)
    val latestPresentation by rememberUpdatedState(presentation)

    // 收藏状态 (本地库, 无网络请求): 一个类型一条轻量 id 查询, 合成 subjectId -> 类型. null = 还没读完 —— 进时间线的落点
    // ("待播里你追的下一部") 要等它: 时间表数据有缓存时首帧就到, 比这几条查询还早, 不等就落成"待播第一部".
    // "你追的" 只认在看/想看 (时间表最实际的用法是"我追的番更没更"); 完整类型给长按菜单与左栏标签用.
    val collectionTypes by remember(collectionRepo) {
        flow<Map<Int, UnifiedCollectionType>?> {
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
    }.collectAsStateWithLifecycle(null)
    val isFollowed: (Int) -> Boolean = { subjectId -> collectionTypes?.get(subjectId) in TV_SCHEDULE_FOLLOWED_TYPES }
    // 每天有几部你追的 (日期标签里的"在追 N 部": 时间线每天开头那行、左栏顶上那块)
    val followedPerDay = remember(timeline, collectionTypes) {
        timeline.days.map { day ->
            day.cards.count { card -> card.item?.subjectId?.let(isFollowed) == true }
        }
    }

    // ---- 焦点 ----
    // 事件驱动的焦点域. 时间线是单列网格: 复用网格那套送焦 (按格下标) 与过渡锚点;
    // 上下左右自己路由 (要跳过日期标签、跨天找同一时刻), 滚动交给固定焦点框 (见下方滚动效应)
    val focus = rememberTvFocusScope()
    val gridFocus = rememberTvGridFocus(focus)
    // 隐形焦点驻留点 (见 TvFocusTransitAnchor), 两种时候用: 跳天时旧那格滚出视口被销毁、新那格还没组合出来的空档;
    // 时间线里还没有可落的那一部 (首屏载入中) —— 页面上没有别的可聚焦, 焦点不能悬空等数据
    val transitAnchor = remember { FocusRequester() }
    var anchorFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyGridState()
    // 时间线滚动登记进页面级信号: 低特效档下背景图等停稳才换, 见 TvScrollActivity
    ReportTvScrollActivity(listState)
    val errorCardFocusRequester = remember { FocusRequester() }
    // 焦点落到过时间线里的某一部 (本次进页以来)
    var anyFocusObtained by remember { mutableStateOf(false) }
    // 当前聚焦那一部在时间线里的格下标 (跨导航保存: 从详情页返回本页时恢复)
    var lastFocusedEntry by rememberSaveable { mutableIntStateOf(-1) }
    var listHasFocus by remember { mutableStateOf(false) }

    // ---- 选中的那一部 (全屏背景与左栏) ----
    // 焦点在时间线里 (含跳天途中停在隐形锚点上) 时是聚焦那部; 还没进过时间线 (首屏载入中) 时是进时间线要落的那部 ——
    // 落进去时背景与左栏不必再换一次. 只在回调里读 (焦点下标是热状态, 不进页面 body)
    val selectedEntry: () -> Int = {
        val t = currentTimeline
        val focused = lastFocusedEntry
        when {
            t.isItem(focused) -> focused
            // 收藏状态还没读完, 落点定不下来 (见 collectionTypes): 先不选, 免得背景与左栏先按"待播第一部"出一次再换
            collectionTypes == null -> -1
            else -> t.entryItemFrom(currentTodayIndex, isFollowed) ?: -1
        }
    }
    val selectedTarget: () -> TvScheduleBackdropTarget? = {
        val t = currentTimeline
        val entry = selectedEntry()
        t.cardAt(entry)?.item?.let { item ->
            TvScheduleBackdropTarget(
                subjectId = item.subjectId,
                coverUrl = item.imageUrl,
                // 时间线上下两部 (日期标签那格取不到条目, 自动跳过)
                neighbors = tvGridNeighborsOf(entry, 1) { i -> t.cardAt(i)?.item?.let { TvHeroNeighbor(it.subjectId) } },
            )
        }
    }
    // 背景的**展示**目标: 低特效档下连发导航期间不换图, 停下来才换一次 (完整特效档原样直通)
    val backdropTarget = rememberTvSettledHeroProvider { selectedTarget() }

    // 预取要的两样东西 (subjectId 之外): TMDB 只认**原名**, 负缓存限期失效要**播出日期**; 中文名也带上
    // (TMDB 只有罗马字标题或用了不同汉字写法时靠它匹配, 见 TmdbMatchHints). 整条时间线一张表, 邻居只带 id 过来
    val cardNames = remember(timeline) {
        buildMap {
            for ((d, day) in timeline.days.withIndex()) {
                val date = days.getOrNull(d)?.date?.toString()
                for (card in day.cards) {
                    val item = card.item ?: continue
                    put(item.subjectId, Triple(item.subjectName.ifBlank { item.subjectTitle }, item.subjectTitle, date))
                }
            }
        }
    }
    val prefetchBackdrop: suspend (Int) -> Unit = { subjectId ->
        cardNames[subjectId]?.let { (name, titleCn, date) ->
            tmdb.prefetchTvBackdrop(subjectId, name, activeAsOfDate = date, hints = TmdbMatchHints(nameCn = titleCn))
        }
    }
    // 左栏的评分 / 标签 / 简介 / 总集数: 时间表数据里没有, 按选中那一部取条目信息 (与探索页 hero 同一条取数、同一张
    // 进程缓存, 见 prefetchTvScheduleSubjectInfo). 这张快照表只写**选中**那一部: 预取邻居写的是进程级普通表 —— 写进这里的话,
    // 用户停着不动时后台每落一条就把左栏重组一遍
    val infoCache = remember { mutableStateMapOf<Int, SubjectCollectionInfo>() }
    // 接进四页共用的 hero 媒体流水线: 连发合并、在途去重、前台优先调度, 以及上下两部的预取与图片预热
    rememberTvHeroMediaPipeline(
        tmdb = tmdb,
        // 与展示端同一个取值 (见全屏背景层): 预热的必须正是要显示的那张
        fullVisualEffects = false,
        restartKey = Unit,
        spec = { selectedTarget()?.let { TvHeroMediaSpec(it.subjectId, coverUrl = "", neighbors = it.neighbors) } },
        // 背景图与条目信息并行: 背景图按时间表自带的原名取, 不必排在条目信息那一跳后面
        resolve = { spec ->
            coroutineScope {
                launch { prefetchBackdrop(spec.subjectId) }
                prefetchTvScheduleSubjectInfo(spec.subjectId, collectionRepo)
                // 条目信息一到就给左栏, 不陪背景图地址等 (那一跳在上面并行跑着): TMDB 没缓存时左栏的评分 / 标签 / 简介会空着
                // 白等 1.2~1.4s (两台电视实测), 大陆连 TMDB 更慢. 只为选中的这一部跑, 写的也只是它
                TvHeroMediaCache.peekSubjectInfo(spec.subjectId)?.let { infoCache[spec.subjectId] = it }
            }
        },
        // 上下两部连条目信息一起预取: 走过去时左栏的评分 / 标签 / 简介直接出
        resolveNeighbor = { _, neighbor ->
            coroutineScope {
                launch { prefetchBackdrop(neighbor.subjectId) }
                prefetchTvScheduleSubjectInfo(neighbor.subjectId, collectionRepo)
            }
        },
        // 连发合并之前: 进程缓存里有就先直出, 左栏的字不陪着合并与取图等
        beforeResolve = { s -> TvHeroMediaCache.peekSubjectInfo(s.subjectId)?.let { infoCache[s.subjectId] = it } },
        afterResolve = { s ->
            val info = infoCache[s.subjectId]
                ?: TvHeroMediaCache.peekSubjectInfo(s.subjectId)?.also { infoCache[s.subjectId] = it }
            // 过期缓存自刷新 (同探索页): 仓库的 flow 先给本地缓存 (可能过期, 如收藏时还没人评分), 过期时拉服务器再给一次;
            // 解析链的 first() 拿到旧值就不收了, 这里接着收. 这次没取到的也借它再试一次. 换一部时随流水线取消
            launch {
                delay(TV_HERO_MEDIA_DEBOUNCE_MILLIS)
                runCatching {
                    collectionRepo.subjectCollectionFlow(s.subjectId).collect { fresh -> infoCache[s.subjectId] = fresh }
                }
            }
            // 简介兜底: Ani 服务器部分条目简介为空, 直连 bgm.tv 补
            if (info != null && info.subjectInfo.summary.isBlank()) {
                launch { bangumiSummaryService.prefetchTvSummaryFallback(s.subjectId) }
            }
            // 条目信息没拿到也照常预取邻居: 本页的背景图不靠它
            true
        },
    )

    // 把第 [entry] 格送进固定焦点框并聚焦它. 滚动与送焦在同一次按键分发里定: 先 requestScrollToItem (下一帧直接按新位置
    // 组合, 不会先按旧位置画一屏再跳), 送焦跑起来时目标已在框里. [viaTransit]: 目标远 (跳天), 旧那格会随滚动被销毁,
    // 先把焦点钉到隐形锚点 —— 顺序不能反, 锚点只在有挂起落点时可聚焦
    val focusEntry: (entry: Int, viaTransit: Boolean) -> Unit = { entry, viaTransit ->
        gridFocus.focusItem(entry)
        if (viaTransit) runCatching { transitAnchor.requestFocus() }
        listState.requestScrollToItem(entry)
    }
    // 进时间线 (进页 / 返回本页没有可恢复的那一部 / 驻留锚点搁浅): 落到今天的默认那部 (待播里你追的第一部 → 待播第一部 →
    // 你追的第一部 → 第一部), 今天一部都没有就落最近有番的那天. 载入失败时落到错误卡的重试钮
    val enterList: () -> Boolean = {
        val target = if (presentation.isPlaceholder) null else timeline.entryItemFrom(todayIndex, isFollowed)
        if (target != null) {
            focusEntry(target, false)
            true
        } else {
            presentation.error != null && runCatching { errorCardFocusRequester.requestFocus() }.getOrDefault(false)
        }
    }
    val latestEnterList by rememberUpdatedState(enterList)
    // 时间线上下左右 (只在焦点落在某一部上时处理; 挂在限流之后)
    val listKeys: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        val t = timeline
        val focused = lastFocusedEntry
        if (!t.isItem(focused)) return@handler false
        when (event.key) {
            // 上: 前一部 (跨过日期标签接到前一天最后一部); 已是最早那部就消费掉不动
            Key.DirectionUp -> {
                t.prevItem(focused)?.let { gridFocus.focusItem(it) }
                true
            }
            // 下: 后一部; 已是最后一部就消费掉不动 (交回默认方向搜索会乱跳)
            Key.DirectionDown -> {
                t.nextItem(focused)?.let { gridFocus.focusItem(it) }
                true
            }
            // 左右: 前一天 / 后一天的同一时刻附近 (电视节目单的走法; 空天跳过). 首尾两天也消费掉
            Key.DirectionLeft, Key.DirectionRight -> {
                val delta = if (event.key == Key.DirectionLeft) -1 else 1
                t.nonEmptyDayFrom(t.dayOf(focused), delta)
                    ?.let { day -> t.nearestItemIn(day, t.cardAt(focused)?.item?.time) }
                    ?.let { focusEntry(it, true) }
                true
            }

            else -> false
        }
    }

    // 进页焦点: 曾聚焦过某一部则恢复到它 (借统一落点解析等数据/滚动/到位确认); 否则等数据 (列表先摆在哪一格见下面的预摆).
    // 两条路都先把焦点钉在隐形锚点上 (恢复那一格要等数据与滚动, 新进页要等数据, 这期间页面上没有别的可聚焦)
    LaunchedEffect(Unit) {
        val restore = lastFocusedEntry
        if (restore >= 0) gridFocus.focusItem(restore)
        runCatching { transitAnchor.requestFocus() }
    }
    // 预摆: 还没进过时间线 (也不是回来恢复) 时, 列表一直摆在进时间线要落的那一格 —— 骨架期是骨架里今天那格 (别让一周前那天
    // 的日期标签先在顶上露一下), 数据到了是真落点, 收藏状态读完再对一次 (你追的那部). 在组合提交之后、同一帧量布局之前摆
    // (SideEffect): 数据到的那一帧就按真落点画. 以前等焦点落进去才滚, 那之前先按骨架的下标画一屏别的天 (索尼上闪 ~100ms,
    // 那一屏的封面请求刚发就被取消)
    val preEntryTarget = timeline.entryItemFrom(todayIndex, isFollowed)
    val preEntryScrolled = remember { intArrayOf(-1) }
    SideEffect {
        // 焦点状态在这里读而不是组合里读: 焦点下标是热状态, 组合里读会让整页跟着每一次按键重组
        if (preEntryTarget != null && preEntryTarget != preEntryScrolled[0] && !anyFocusObtained && lastFocusedEntry < 0) {
            preEntryScrolled[0] = preEntryTarget
            listState.requestScrollToItem(preEntryTarget)
        }
    }
    // 焦点停在隐形锚点上、还没进过时间线、也不在送焦途中, 而时间线已经有真数据、收藏状态也读完了 (落点要看你追的哪部):
    // 进时间线落到今天的默认那部. 进页、恢复没成 (超时)、载入失败后重试都走这一条. 时间线里一部都没有时 enterList 什么都不做,
    // 焦点留在锚点上
    LaunchedEffect(Unit) {
        snapshotFlow {
            anchorFocused && !anyFocusObtained && !gridFocus.switching && !latestPresentation.isPlaceholder &&
                collectionTypes != null
        }.collect { ready ->
            if (!ready) return@collect
            // 等载入后的那一帧组合完: timeline 与进时间线的回调都要是真数据那一版
            withFrameNanos { }
            latestEnterList()
        }
    }
    // 从详情页/播放器返回本页时重新落点: 本页子树可能一直没被销毁 (上面的 LaunchedEffect(Unit) 不会重跑),
    // 但时间线的行在离开期间被销毁, 焦点随之悬空 —— 表现为返回后看不到焦点框
    OnReturnToForeground("schedule") {
        // 焦点从来没进过时间线就不补: 那是抢焦点, 不是恢复 (那种时候焦点在隐形锚点上, 上面那条效应会接手)
        if (!anyFocusObtained) return@OnReturnToForeground
        if (lastFocusedEntry >= 0) gridFocus.focusItem(lastFocusedEntry) else latestEnterList()
    }
    // 固定焦点框: 焦点落到哪一格就把它滚进框 (TvScrollAnimator: 连发按键取消进行中的滚动并继承速度).
    // 有在途送焦且目标不是它: 它是旧下标 (重组后首次取值), 按它滚会把 focusEntry 摆好的位置滚走, 落点一到再滚
    LaunchedEffect(listState) {
        val scrollAnimator = TvScrollAnimator()
        snapshotFlow { lastFocusedEntry }.collectLatest { focused ->
            if (focused < 0) return@collectLatest
            val pending = gridFocus.pendingIndex
            if (pending != null && pending != focused) return@collectLatest
            runCatching { scrollAnimator.animateScrollToItem(listState, focused) }
        }
    }

    val navigateToSubject: (AiringScheduleItemPresentation) -> Unit = { item ->
        Analytics.recordEvent(SubjectEnter) {
            put("source", "schedule_card")
            put("subject_id", item.subjectId)
        }
        navigator.navigateSubjectDetails(
            subjectId = item.subjectId,
            placeholder = SubjectDetailPlaceholder(
                id = item.subjectId,
                name = item.subjectTitle,
                coverUrl = item.imageUrl,
            ),
        )
    }
    // 播放键: 直接播这一集 (时间表给的就是某一集, 不用猜"下一集")
    val navigateToPlay: (AiringScheduleItemPresentation) -> Unit = { item ->
        Analytics.recordEvent(SubjectEnter) {
            put("source", "schedule_play")
            put("subject_id", item.subjectId)
        }
        navigator.navigateEpisodeDetails(item.subjectId, item.episodeId)
    }
    // 长按弹出的收藏下拉 (同探索页/追番页); 打开后短暂吞掉长按残余的确认键, 避免误触第一项.
    // remember 无 key: 工厂被时间线 items 内容 lambda 捕获, 每次新实例都会让所有可见行重组
    val collectionMenuFor: (Int) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit = remember {
        { subjectId ->
            { expanded, onDismiss ->
                EditCollectionTypeDropDown(
                    currentType = collectionTypes?.get(subjectId) ?: UnifiedCollectionType.NOT_COLLECTED,
                    expanded = expanded,
                    onDismissRequest = onDismiss,
                    onClick = { action ->
                        scope.launch {
                            runCatching { setCollectionTypeUseCase(subjectId, action.type) }
                                .onFailure { toaster.showLoadError(LoadError.fromException(it)) }
                        }
                    },
                    // 行的菜单只有长按一个入口, 恒吞掉那次长按残余的确认键
                    modifier = Modifier.consumeHeldConfirmKey(),
                )
            }
        }
    }

    // 动作面板「刷新本页」= 重拉整张时间表 (数据默认一小时才刷一次)
    TvPageRefreshHandler(onRetry)
    // 播放键: 短按播选中的那一部 (还没进时间线时就是今天的默认那部)
    val playKeyModifier = tvPlayKeyShortPress(
        onPlay = {
            timeline.cardAt(selectedEntry())?.item?.let { navigateToPlay(it); true } ?: false
        },
    )

    Box(
        modifier.fillMaxSize()
            // 方向/确认键即取消在途送焦 —— 框架不与用户抢焦点 (外层 onPreviewKeyEvent, 先于时间线的路由收到)
            .tvFocusNavSignal(focus)
            .then(playKeyModifier),
    ) {
        // 全屏背景: 选中那一部的 TMDB 横版图 (没有回退它自己的竖版封面, 见 tvHeroBackdropUrl). 地址用 lambda 传入,
        // 选中换一部只重组这一层; 图登记给放大转场, 点进详情页时图原地不动
        TvFullScreenBackdropLayer(
            backdropUrl = {
                backdropTarget()?.let {
                    tmdb.tvHeroBackdropUrl(it.subjectId, fullVisualEffects = false, coverUrl = it.coverUrl)
                }
            },
            themeSeedSubjectId = { backdropTarget()?.subjectId },
        )
        // 再压一层: 左栏的大字与时间线都直接铺在图上
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = TV_SCHEDULE_BACKDROP_EXTRA_DIM_ALPHA)),
        )
        // 四边同一个留白 (用户: "上下左右边距都应该比较一致, 除了时间表下面的一直伸长的项不需要管"): 左上角那行日期离左边和
        // 离上边一样远; 时间线上下出血穿过它铺到屏幕边 (见 tvScheduleBleedToScreenEdges). 左栏宽度不变, 边距从右栏匀
        Row(Modifier.fillMaxSize().padding(TV_SCHEDULE_EDGE_PAD)) {
            // 左栏: 整列归选中那一部 (封面 + 封面右边的短信息, 下面番名与简介)
            TvScheduleDetailPanel(
                card = { timeline.cardAt(selectedEntry()) },
                // 选中那部所在的那一天 (还没有选中的那部时是今天): 左栏顶上那块日期标签
                day = {
                    val d = timeline.dayOfOrNull(selectedEntry()) ?: todayIndex
                    val shown = days.getOrNull(d)
                    val items = timeline.days.getOrNull(d)
                    if (shown == null || items == null) null else TvSchedulePanelDay(shown, items, followedPerDay.getOrElse(d) { 0 })
                },
                currentTime = { timeline.dayOfOrNull(selectedEntry())?.let { timeline.days.getOrNull(it)?.currentTime } },
                collectionType = { subjectId -> collectionTypes?.get(subjectId) },
                subjectInfo = { subjectId -> infoCache[subjectId] },
                placeholder = presentation.isPlaceholder,
                modifier = Modifier.weight(TV_SCHEDULE_LEFT_WEIGHT).fillMaxHeight(),
            )
            Spacer(Modifier.width(TV_SCHEDULE_COLUMN_GAP))
            // 右栏: 时间线 (15 天接成一条, 每天开头一行日期标签)
            Column(Modifier.weight(1f - TV_SCHEDULE_LEFT_WEIGHT).fillMaxHeight()) {
                val error = presentation.error
                if (error != null) {
                    LoadErrorCard(
                        error,
                        onRetry = onRetry,
                        // 请求器挂容器上, requestFocus 委托给子树第一个焦点目标 (重试按钮)
                        Modifier.padding(bottom = 8.dp).focusRequester(errorCardFocusRequester),
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // 隐形焦点驻留点 (与追番页跨 tab 共用同一实现). 摞在时间线底下而不是排在它上面: 它有 1dp 高, 排在上面会把
                    // 时间线挤下去 1dp, 左栏封面顶边就对不上固定焦点框了. 仍在网格之外 (放进网格里会成为行内方向键的候选);
                    // 也不放进下面的 BoxWithConstraints —— 那里面是测量时才组合的, 进页那一下的 requestFocus 会扑空
                    TvFocusTransitAnchor(
                        requester = transitAnchor,
                        switching = { gridFocus.switching },
                        // 跳天途中以外, 还没进过时间线 / 时间线里没有可落的那一部 (载入中、15 天都空) 时也可聚焦 —— 那种时候
                        // 页面上只有它能接焦点. 载入失败时让给错误卡 (不然它会成为错误卡下键的落点)
                        extraCanFocus = {
                            val p = latestPresentation
                            p.error == null && (p.isPlaceholder || !anyFocusObtained || !currentTimeline.hasItems)
                        },
                        // 它也是本页的系统兜底落点 (全局兜底把焦点塞进本页时落到它): 拿到焦点时让此前被导航转场拒掉的送焦重试
                        modifier = Modifier.onFocusChanged {
                            anchorFocused = it.isFocused
                            if (it.isFocused) focus.notifyFocusFallbackSettled()
                        },
                        // 驻留期间的按键被锚点吞掉, 不算用户接管 (否则跳天送焦被取消)
                        scope = focus,
                        // 焦点还停在锚点上时它不再可聚焦 (跳天送焦被取消 / 超时, 或载入完成 / 失败): 回到原来那部,
                        // 没有就进时间线 (载入失败时进的是错误卡)
                        onStranded = {
                            val back = lastFocusedEntry
                            if (!latestPresentation.isPlaceholder && currentTimeline.isItem(back)) {
                                gridFocus.focusItem(back)
                            } else {
                                latestEnterList()
                            }
                        },
                    )
                    // 时间线上下都穿过页面留白铺到屏幕边: 条目只在屏幕边上被切, 不在页面里一条看不见的线上 (用户: "底部有个
                    // 看不见的边界会裁剪时间表的项" —— 上一版视口取整数格, 停下来每格都整格露出, 可一滚动条目就在离屏幕底边
                    // 五十来 dp 的地方凭空消失). 底下停下来时最后那部可能只露一截, 由屏幕边切着, 读作"下面还有"; 顶上第一格
                    // 离屏幕顶边隔一整段页面留白 (用户: "最上面的表项应该跟上边界距离再大点"), 越过它往上走的那部在留白里边走边淡
                    // (tvScheduleTopFade)
                    BoxWithConstraints(Modifier.tvScheduleBleedToScreenEdges().fillMaxSize()) {
                        val listHeight = this@BoxWithConstraints.maxHeight
                        // 固定焦点框的顶边, 从出血后的顶边 (屏幕顶边) 量: 页面留白 + 第一格
                        val frameTop = TV_SCHEDULE_EDGE_PAD + TV_SCHEDULE_FOCUS_ANCHOR
                        // 首尾补白: 上面补到固定焦点框 (第二格), 下面补到"最后一格也能停进框里"
                        val bottomPad = (listHeight - frameTop - TV_SCHEDULE_ROW_HEIGHT).coerceAtLeast(0.dp)
                        // 第一格里是哪一部 (那一行总写时刻): 出血之后更早的那几部也在 visibleItemsInfo 里 (越过第一格顶线
                        // 正在淡出), 所以按"中线在第一格顶线以下"取, 不能直接用 firstVisibleItemIndex
                        val firstSlotLinePx = with(LocalDensity.current) { TV_SCHEDULE_FOCUS_ANCHOR.toPx() }
                        val firstSlotIndex by remember(listState, firstSlotLinePx) {
                            derivedStateOf {
                                listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.offset.y + it.size.height / 2 >= -firstSlotLinePx }?.index ?: -1
                            }
                        }
                        val entries = timeline.entries
                        // 占位期间对送焦报"还没有数据" (0 条): 骨架行不可聚焦, 送焦要等真实数据到达.
                        // 目标不在视口时它会 scrollToItem(目标) —— 有了上面的补白, 那正好就是"送进框里"
                        gridFocus.SendFocusEffect(listState) { if (presentation.isPlaceholder) 0 else entries.size }
                        // 固定焦点框的底色 (在列表后面, 行本身不画底色)
                        TvScheduleFocusFrame(top = frameTop, visible = { listHasFocus || gridFocus.switching }, ring = false)
                        CompositionLocalProvider(LocalBringIntoViewSpec provides TvScheduleNoBringIntoView) {
                            LazyVerticalGrid(
                                // 单列网格而非 LazyColumn: 直接复用网格那套送焦 / 焦点锚点 (都按格下标)
                                columns = GridCells.Fixed(1),
                                modifier = Modifier.fillMaxWidth().height(listHeight).clipToBounds()
                                    // 焦点在不在哪一部上 (固定焦点框的显隐). 挂在网格上而不是外层: 焦点停在隐形锚点上不算
                                    .onFocusChanged { listHasFocus = it.hasFocus }
                                    // 长按方向键的移动频率上限 (用户调过). 必须挂在按键路由之前
                                    .tvFocusMoveRateLimit()
                                    .onPreviewKeyEvent(listKeys),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(TV_SCHEDULE_ROW_SPACING),
                                contentPadding = PaddingValues(top = frameTop, bottom = bottomPad),
                            ) {
                                items(
                                    count = entries.size,
                                    key = { index ->
                                        when (val entry = entries[index]) {
                                            is TvScheduleEntry.Header -> "TvScheduleDay-${entry.dayIndex}"
                                            is TvScheduleEntry.Empty -> "TvScheduleEmpty-${entry.dayIndex}"
                                            is TvScheduleEntry.Item -> entry.card.item
                                                ?.let { "TvSchedule-${entry.dayIndex}-${it.subjectId}-${it.episodeId}" }
                                                ?: "TvSchedule-placeholder-$index"
                                        }
                                    },
                                    contentType = { index -> entries[index]::class },
                                ) { index ->
                                    when (val entry = entries[index]) {
                                        is TvScheduleEntry.Header -> TvScheduleDayHeader(
                                            day = days.getOrNull(entry.dayIndex),
                                            items = entry.items,
                                            followedCount = followedPerDay.getOrElse(entry.dayIndex) { 0 },
                                            placeholder = presentation.isPlaceholder,
                                            modifier = Modifier.tvScheduleTopFade(listState, index),
                                        )

                                        is TvScheduleEntry.Empty -> TvScheduleEmptyDay(Modifier.tvScheduleTopFade(listState, index))

                                        is TvScheduleEntry.Item -> {
                                            val item = entry.card.item
                                            // 第一格那一行总写时刻: 它若与上一行 (已淡出) 同一时刻,
                                            // 按"同一时刻只写一次"就看不出它几点
                                            val isTopVisible by remember(index) {
                                                derivedStateOf { firstSlotIndex == index }
                                            }
                                            TvScheduleRow(
                                                card = entry.card,
                                                showTime = entry.startsTimeGroup || isTopVisible,
                                                isNext = entry.isNext,
                                                // 当天第一部就是待播的话 (一部都还没播) 不画那条线
                                                nowLineAbove = entry.isNext && !entry.firstOfDay,
                                                followed = item?.subjectId?.let(isFollowed) == true,
                                                onClick = { item?.let(navigateToSubject) },
                                                onFocused = {
                                                    lastFocusedEntry = index
                                                    anyFocusObtained = true
                                                },
                                                menu = item?.let { collectionMenuFor(it.subjectId) },
                                                modifier = Modifier.tvScheduleTopFade(listState, index)
                                                    .tvGridFocusItem(gridFocus, index = index, itemCount = entries.size),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 固定焦点框的描边 (在列表前面)
                        TvScheduleFocusFrame(top = frameTop, visible = { listHasFocus || gridFocus.switching }, ring = true)
                    }
                }
            }
        }
    }
}

/**
 * 时间线的固定焦点框 (第二格, 时刻列右边那一段): 条目从框下滑过, 框不动. [ring] = false 画底色 (放在列表后面),
 * true 画描边 (放在列表前面). [visible] 在本小组件里读, 焦点进出时间线不牵动外面.
 * [top] 是那一格的顶线; 框比格子上下各多出 [TV_SCHEDULE_FOCUS_FRAME_OUTSET], 上下边正好落在两部之间的空当正中.
 */
@Composable
private fun TvScheduleFocusFrame(top: Dp, visible: () -> Boolean, ring: Boolean) {
    if (!visible()) return
    val frame = Modifier
        .padding(top = top - TV_SCHEDULE_FOCUS_FRAME_OUTSET, start = TV_SCHEDULE_TIME_COLUMN_WIDTH)
        .fillMaxWidth()
        .height(TV_SCHEDULE_ROW_HEIGHT + TV_SCHEDULE_FOCUS_FRAME_OUTSET * 2)
    if (ring) {
        Box(frame.tvFocusRingBorder(TV_SCHEDULE_ROW_CORNER, TvFocusRing.defaultBrush))
    } else {
        Box(
            frame.background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TV_SCHEDULE_ROW_FOCUSED_ALPHA),
                RoundedCornerShape(TV_SCHEDULE_ROW_CORNER),
            ),
        )
    }
}

/**
 * 上下各多量一段页面留白 ([TV_SCHEDULE_EDGE_PAD]) 的高度, 放置时上移同样的距离, 汇报给父布局的仍是原高度 —— 多出来
 * 那两段画进上下留白, 一直到屏幕边 (外面几层布局都不裁剪). 时间线靠它把裁切线放到屏幕边上. 同 tvGridTopBleed 的做法.
 */
private fun Modifier.tvScheduleBleedToScreenEdges(): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val pad = TV_SCHEDULE_EDGE_PAD.roundToPx()
    val height = constraints.maxHeight + pad * 2
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, constraints.maxHeight) { placeable.place(0, -pad) }
}

/**
 * 越过第一格顶线往上走的那几部边走边淡 (同追番 / 搜索网格的 tvGridItemTopFade, 只是线不在内边距之后 —— 那里是固定
 * 焦点框 —— 而在第一格的顶上): 越线多远就多淡, 越过"一部的一半 + 行距"时完全看不见. 停下来时第一格上面那部正好整个
 * 淡掉, 顶上不露半截; 滚动时往上走的那部也不会在哪条看不见的线上被硬切. 全读在 graphicsLayer 里, 滚动时每帧只失效图层,
 * 零重组; ModulateAlpha 理由同 tvGridItemTopFade (默认 Auto 在 alpha < 1 时整行先画进离屏缓冲).
 */
private fun Modifier.tvScheduleTopFade(state: LazyGridState, index: Int): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.ModulateAlpha
    val top = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.y ?: return@graphicsLayer
    val over = -TV_SCHEDULE_FOCUS_ANCHOR.toPx() - top
    if (over > 0f) alpha = (1f - over / (size.height / 2f + TV_SCHEDULE_ROW_SPACING.toPx())).coerceIn(0f, 1f)
}

/**
 * 关闭"聚焦项自动滚进视野": 时间线由固定框决定落位, 默认那套最小滚动量会与之打架.
 */
private val TvScheduleNoBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * 左栏 (整列归选中那一部), 自上而下三段:
 *  - 顶上一块日期标签, 与时间线里每天开头那行同一套字 ("9/13 今天 · 共 14 部" / "现在 16:15 · 待播 1 部 · 在追 2 部"),
 *    高度随字走, 默认字号下连同到封面的空当正好到右边固定焦点框的顶边 —— 封面顶边于是与框对齐;
 *  - 竖版封面 + 封面右边的侧栏 (见 [TvSchedulePanelSide]): 还有多久、第几话、共几话、集名、评分、标签、收藏这些短信息都在
 *    这里 (播出时刻右边时间线上就有, 不重复);
 *  - 封面下面: 中文番名 (最多两行) + 原名, 再往下剩多少地方放多少行简介.
 *
 * 2026-09-13 第三轮 (用户: "横屏封面和右边的条目之间有很大一片空间, 调整信息分布让重心平衡"; 选定的样稿又改成
 * "短一点的信息全放封面右边, 下面就留中日标题, 再下面有空间就放截断的动画简介, 或者放评分、标签"): 上一版封面按剩下的
 * 高度算宽, 右边空出约 160dp 一直空到时间线, 整页的分量压在左缘.
 *
 * 封面行的高度是左栏高度的定比例 ([TV_SCHEDULE_PANEL_COVER_FRACTION]); 封面与侧栏每个位置都是定高, 状态没有也写一个
 * (灰色的 "未播出" / "未收藏" / "暂无评分"…), 换一部时这些位置一格都不动 (用户: "有的有文字有的没有 … 这些不一致导致排版
 * 乱动"). 番名紧跟封面, 一行两行由下面简介的行数吸收.
 * 各参数都是 lambda / 窄值: 选中换一部只重组本栏, 不连带整页.
 */
@Composable
private fun TvScheduleDetailPanel(
    card: () -> TvScheduleCardData?,
    /** 选中那部所在的那一天 (顶上那块日期标签要的). */
    day: () -> TvSchedulePanelDay?,
    /** 选中那部所在那天的"现在几点" (只有今天有). */
    currentTime: () -> LocalTime?,
    collectionType: (subjectId: Int) -> UnifiedCollectionType?,
    /** 条目信息 (评分 / 标签 / 简介 / 总集数); null = 还没取到, 这几样先空着. */
    subjectInfo: (subjectId: Int) -> SubjectCollectionInfo?,
    placeholder: Boolean,
    modifier: Modifier = Modifier,
) {
    val current by remember(card) { derivedStateOf(card) }
    val c = current
    val item = c?.item
    val info = item?.let { subjectInfo(it.subjectId) }
    Column(modifier) {
        // 两行字按自己的高度排, 下面空一段再接封面: 默认字号下正好到右边固定焦点框的顶边 (一格减去框往上多出的那截), 封面
        // 顶边与框对齐; 系统字号放大时这块跟着变高, 把封面往下推 —— 不再把字塞进定高的块里被裁 (用户报过"文字最下面给裁切了")
        TvSchedulePanelDayLine(
            day,
            placeholder,
            Modifier.fillMaxWidth().padding(bottom = TV_SCHEDULE_PANEL_DAY_TO_COVER_GAP - TV_SCHEDULE_FOCUS_FRAME_OUTSET),
        )
        // 封面行. Column 按顺序量, 这里拿到的最大高度就是日期标签那块以下的全部, 取其定比例
        Row(Modifier.fillMaxWidth().fillMaxHeight(TV_SCHEDULE_PANEL_COVER_FRACTION)) {
            val coverShape = RoundedCornerShape(TV_SCHEDULE_PANEL_COVER_CORNER)
            Box(
                Modifier.fillMaxHeight()
                    .aspectRatio(TV_PORTRAIT_CARD_COVER_RATIO, matchHeightConstraintsFirst = true)
                    .clip(coverShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .then(if (item == null) Modifier.placeholder(placeholder, shape = coverShape, highlight = null) else Modifier),
            ) {
                if (item != null) {
                    TvScheduleCoverImage(item.imageUrl, contentDescription = item.subjectTitle)
                }
            }
            if (c != null && item != null) {
                TvSchedulePanelSide(
                    card = c,
                    item = item,
                    currentTime = currentTime,
                    collectionType = collectionType(item.subjectId),
                    info = info,
                    modifier = Modifier.padding(start = TV_SCHEDULE_PANEL_SIDE_GAP).weight(1f).fillMaxHeight(),
                )
            }
        }
        if (item != null) {
            // 番名紧跟封面, 一行两行都贴着封面排 (用户: "竖屏封面和下面的标题之间似乎有不少空间, 是不是可以调紧凑点" ——
            // 上一版给番名留死两行高、字贴底, 一行的番名上面空出一整行). 番名一行时下面的简介多放一行
            Text(
                item.subjectTitle,
                Modifier.padding(top = TV_SCHEDULE_PANEL_COVER_TO_TITLE_GAP),
                color = tvHeroContentColor(),
                style = TvSchedulePanelText.headline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 原名 (与显示名不同时): 日文名常常才是认得出的那个. 没有也留着这一行, 简介的起点不跟着跳
            val original = item.subjectName.takeIf { it.isNotBlank() && it != item.subjectTitle }
            Text(
                original.orEmpty(),
                Modifier.padding(top = 2.dp),
                color = tvHeroSecondaryContentColor(),
                style = TvSchedulePanelText.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TvSchedulePanelSummary(
                subjectId = item.subjectId,
                summary = info?.subjectInfo?.summary.orEmpty(),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = TV_SCHEDULE_PANEL_SUMMARY_GAP),
            )
        }
    }
}

/**
 * 左栏顶上那块: 选中那部所在的那一天, 与时间线里每天开头的日期标签同一套字 (用户: "跟右边的日期文字一致"、
 * "在左上角标出这一天追了多少部") —— "9/13 今天 · 共 14 部", 下一行 "现在 16:15 · 待播 1 部 · 在追 2 部".
 * 时间线那边写成一行, 这里宽度不够, 分两行. 日期是一档 (与番名、第几话同级), "共 14 部" 与下一行是正文, "在追 N 部" 用主题色
 * (0 部灰色). 高度随字走, 不定死 (见调用处).
 */
@Composable
private fun TvSchedulePanelDayLine(day: () -> TvSchedulePanelDay?, placeholder: Boolean, modifier: Modifier = Modifier) {
    val current by remember(day) { derivedStateOf(day) }
    Column(modifier) {
        val shown = current ?: return@Column
        val secondary = tvHeroSecondaryContentColor()
        val body = TvSchedulePanelText.body
        val label = tvScheduleDayLabel(shown.day)
        val total = if (placeholder) null else stringResource(Lang.exploration_tv_schedule_total, shown.items.cards.size)
        Text(
            buildAnnotatedString {
                append(label)
                if (total != null) {
                    withStyle(SpanStyle(color = secondary, fontSize = body.fontSize, fontWeight = FontWeight.Normal)) {
                        append(" · $total")
                    }
                }
            },
            color = if (shown.day.kind == ScheduleDay.Kind.TODAY) MaterialTheme.colorScheme.primary else tvHeroContentColor(),
            style = TvSchedulePanelText.headline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 第二行每一天都有 (0 部也写"在追 0 部"), 首屏占位期间留空占位: 有的天有有的天没有, 封面就跟着上下跳
        Text(
            tvScheduleDayDetails(
                shown.items,
                shown.followedCount,
                placeholder,
                followedColor = MaterialTheme.colorScheme.primary,
                alwaysShowFollowed = true,
            ) ?: AnnotatedString(""),
            Modifier.padding(top = 2.dp),
            color = secondary,
            style = body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 封面右边的侧栏 (与封面等高). 上半截: 播出状态 (还有多久 / 已播出 / 未播出) / 第几话 / 共几话 / 集名; 下半截贴着封面
 * 底边: 评分 / 标签 / 收藏. 播出时刻不放这里 —— 右边时间线上就有 (用户: "时间点是不是可以去掉, 右边就有").
 * 集名占两截之间剩下的地方, 放不下就省略号 —— 两截永不重叠. 每一行都定高、每一部都有字 (条目信息 [info] 还没到时
 * 共几话、评分与标签留空占位), 换一部时排版一格都不动. 字档见 [TvSchedulePanelText].
 */
@Composable
private fun TvSchedulePanelSide(
    card: TvScheduleCardData,
    item: AiringScheduleItemPresentation,
    currentTime: () -> LocalTime?,
    collectionType: UnifiedCollectionType?,
    info: SubjectCollectionInfo?,
    modifier: Modifier = Modifier,
) {
    val now by remember(currentTime) { derivedStateOf(currentTime) }
    val secondary = tvHeroSecondaryContentColor()
    val body = TvSchedulePanelText.body
    Column(modifier) {
        // 播出状态每一部都有 (还有多久 / 已播出 / 未播出; 有的有字有的没有, 下面几行就跟着上下跳). 只有今天待播、知道
        // 时刻的 (倒计时) 用主题色, 其余次要色
        val counting = !card.aired && item.time != null && now != null
        Text(
            tvScheduleStatusText(card, now),
            color = if (counting) MaterialTheme.colorScheme.primary else secondary,
            style = body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 第几话、共几话各占一行 (用户: "第 x 话这行分开成两行, 经常出现放不下的情况"). 第几话是一档, 侧栏里最大的字
        Text(
            rememberEpisodeLabel(item),
            Modifier.padding(top = 2.dp),
            color = tvHeroContentColor(),
            style = TvSchedulePanelText.headline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 共几话: 条目信息还没到时留空占位, 到了不知道总集数 (0) 就写"集数未定"
        val totalEpisodes = info?.airingInfo?.mainEpisodeCount
        Text(
            when {
                totalEpisodes == null -> ""
                totalEpisodes > 0 -> stringResource(Lang.exploration_tv_schedule_total_episodes, totalEpisodes)
                else -> stringResource(Lang.exploration_tv_schedule_total_episodes_unknown)
            },
            color = secondary,
            style = body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
            val name = item.episodeName?.let(::decodeBasicHtmlEntities)?.takeIf { it.isNotBlank() }
            if (name != null) {
                Text(
                    name,
                    color = tvHeroContentColor(),
                    style = body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 评分 / 标签 / 收藏三行每一部都在: 条目信息还没到时评分与标签先空着占住位置, 到了没有就写"暂无评分" / "暂无标签",
        // 没收藏写"未收藏" (用户: "有已想看, 那就应该其他是灰色的未收藏之类的")
        TvSchedulePanelRating(info?.subjectInfo?.ratingInfo, loaded = info != null)
        TvScheduleTagLine(info?.let { tvScheduleTags(it.subjectInfo) }, Modifier.padding(top = 4.dp))
        TvSchedulePanelCollectionChip(collectionType ?: UnifiedCollectionType.NOT_COLLECTED, Modifier.padding(top = 10.dp))
    }
}

/**
 * 侧栏评分、标签两行的定高: 有没有内容都占这么高, 换一部时排版不跳. 取这两行字的行高并**按 sp 换算** —— 系统字号放大时
 * 跟着变高; 写死 dp 的话字比格子高, 底边会被裁掉.
 */
@Composable
private fun tvSchedulePanelMetaRowHeight(): Dp = with(LocalDensity.current) { TvSchedulePanelText.subhead.lineHeight.toDp() }

/**
 * 评分一行, 定高, 每一部都有: ★ 7.9 (二档, 主题色, 同探索 / 搜索页头图的用色) + 排名 "#327" (小字), 两段字对齐基线;
 * 没人评分写灰色"暂无评分"; 条目信息还没到 ([loaded] = false) 时留空, 只占位置.
 */
@Composable
private fun TvSchedulePanelRating(rating: RatingInfo?, loaded: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.height(tvSchedulePanelMetaRowHeight()), verticalAlignment = Alignment.CenterVertically) {
        if (!loaded) return@Row
        val secondary = tvHeroSecondaryContentColor()
        val rated = rating != null && rating.scoreFloat > 0f
        Icon(
            Icons.Rounded.Star,
            contentDescription = null,
            Modifier.size(18.dp),
            tint = if (rated) MaterialTheme.colorScheme.primary else secondary,
        )
        if (rating != null && rated) {
            Text(
                rating.score,
                Modifier.padding(start = 4.dp).alignByBaseline(),
                color = MaterialTheme.colorScheme.primary,
                style = TvSchedulePanelText.subhead,
                maxLines = 1,
            )
            if (rating.rank > 0) {
                Text(
                    "#${rating.rank}",
                    Modifier.padding(start = 10.dp).alignByBaseline(),
                    color = secondary,
                    style = TvSchedulePanelText.small,
                    maxLines = 1,
                )
            }
        } else {
            Text(
                stringResource(Lang.exploration_tv_schedule_no_rating),
                Modifier.padding(start = 4.dp),
                color = secondary,
                style = TvSchedulePanelText.body,
                maxLines = 1,
            )
        }
    }
}

/** 收藏标签, 每一部都有: 在看 / 想看 主题色描边带心形, 其余收藏类型与"未收藏"次要色不带心形. */
@Composable
private fun TvSchedulePanelCollectionChip(type: UnifiedCollectionType, modifier: Modifier = Modifier) {
    val followed = type in TV_SCHEDULE_FOLLOWED_TYPES
    val chipColor = if (followed) MaterialTheme.colorScheme.primary else tvHeroSecondaryContentColor()
    Row(
        modifier
            .border(1.dp, chipColor, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (followed) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                Modifier.padding(end = 4.dp).size(14.dp),
                tint = chipColor,
            )
        }
        Text(
            if (type == UnifiedCollectionType.NOT_COLLECTED) {
                stringResource(Lang.subject_collection_uncollected)
            } else {
                renderCollectionTypeAsCurrent(type)
            },
            color = chipColor,
            style = TvSchedulePanelText.small,
            maxLines = 1,
        )
    }
}

/**
 * 一行标签 "小说改 / 奇幻 / 冒险": 放得下几个放几个, 不折行也不露半个 (第一个总放, 它自己都放不下才截断) ——
 * 侧栏只有一百五十来 dp 宽, 三个长标签放不下时退成两个 (用户: "标签太长可以放 2 个之类的").
 * 定高一行: [tags] 为 null (条目信息还没到) 时留空占位, 空列表写灰色"暂无标签".
 */
@Composable
private fun TvScheduleTagLine(tags: List<String>?, modifier: Modifier = Modifier) {
    val color = tvHeroSecondaryContentColor()
    val style = TvSchedulePanelText.body
    val rowHeight = tvSchedulePanelMetaRowHeight()
    if (tags.isNullOrEmpty()) {
        Box(modifier.height(rowHeight), contentAlignment = Alignment.CenterStart) {
            if (tags != null) {
                Text(stringResource(Lang.exploration_tv_schedule_no_tags), color = color, style = style, maxLines = 1)
            }
        }
        return
    }
    Layout(
        content = {
            tags.forEachIndexed { index, tag ->
                Text(
                    if (index == 0) tag else " / $tag",
                    color = color,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = modifier.height(rowHeight),
    ) { measurables, constraints ->
        val placeables = ArrayList<Placeable>(measurables.size)
        var width = 0
        for ((index, measurable) in measurables.withIndex()) {
            // 第一个按可用宽度量 (太长就省略号); 其后的按原长量, 整个放不下就停, 后面的也不再量
            val placeable = measurable.measure(
                if (index == 0) constraints.copy(minWidth = 0, minHeight = 0) else Constraints(),
            )
            if (index > 0 && width + placeable.width > constraints.maxWidth) break
            placeables += placeable
            width += placeable.width
        }
        val height = placeables.maxOf { it.height }
        layout(
            width.coerceIn(constraints.minWidth, constraints.maxWidth),
            height.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            var x = 0
            for (placeable in placeables) {
                placeable.placeRelative(x, 0)
                x += placeable.width
            }
        }
    }
}

/**
 * 左栏最下面的简介: 占原名以下剩下的整段, 放不下就在最后一行省略 —— 按高度截而不是固定行数 (番名一行两行、有没有原名,
 * 能放的行数都不同). 条目自己的简介为空时用 bgm.tv 兜底 (见 prefetchTvSummaryFallback). 多段接成一段: 这里只放得下
 * 几行, 段落之间的换行会白占掉行数.
 */
@Composable
private fun TvSchedulePanelSummary(subjectId: Int, summary: String, modifier: Modifier = Modifier) {
    // 兜底表是进程级共享的快照表, 没有按键订阅粒度: derived 之后别的条目写入不牵动这里
    val fallback by remember(subjectId) { derivedStateOf { TvHeroMediaCache.summaryFallbacks[subjectId] } }
    val text = remember(summary, fallback) { tvScheduleSummaryText(summary.ifBlank { fallback.orEmpty() }) }
    Text(
        text,
        modifier,
        color = tvHeroContentColor(),
        style = TvSchedulePanelText.small,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 左栏的标签, 与搜索页头图同一个挑法 (见 SubjectPreviewItemInfo.compute): 1 个来源标签 (原创 / 漫画改 / 小说改…) +
 * 按标注人数排的类型标签, 最多 [TV_SCHEDULE_PANEL_TAG_LIMIT] 个. 年月、TV、制作公司、系列名都不属于这两类, 自然进不来;
 * 「异世界」这类在 Bangumi 公共标签里算"设定", 也不挑.
 */
private fun tvScheduleTags(info: SubjectInfo): List<String> {
    val source = info.tags.firstOrNull { it.kind == CanonicalTagKind.Source }
    val genres = info.tags.filter { it.kind == CanonicalTagKind.Genre }.sortedByDescending { it.count }
    return (listOfNotNull(source) + genres).take(TV_SCHEDULE_PANEL_TAG_LIMIT).map { it.name }
}

/** 多段简介接成一段: 去掉每段首尾的空白 (含全角空格缩进) 与空段; 前一段以中日文标点收尾时直接接上, 否则补一个空格. */
private fun tvScheduleSummaryText(raw: String): String {
    val out = StringBuilder(raw.length)
    for (line in raw.lineSequence()) {
        val part = line.trim()
        if (part.isEmpty()) continue
        if (out.isNotEmpty() && !out.last().isCjkPunctuation()) out.append(' ')
        out.append(part)
    }
    return out.toString()
}

/** 中日文 (全角) 标点: 句号、引号、括号、感叹号这类. */
private fun Char.isCjkPunctuation(): Boolean = code >= 0x2E80 && !isLetterOrDigit()

/**
 * 选中那一部 (与上下两部) 的条目信息: 进程缓存 ([TvHeroMediaCache]) 命中就不走网络, 没有就取一次落进去 ——
 * 与探索 / 追番页 hero 解析链 (`resolveTvHeroMedia`) 的第一跳同一张表, 那边聚焦过的条目这里直接命中, 反之亦然.
 * 本页的背景图用不着它 (时间表自带原名), 只有左栏的评分 / 标签 / 简介 / 总集数要. 网络错误不写缓存, 下次选中重试.
 */
private suspend fun prefetchTvScheduleSubjectInfo(subjectId: Int, collectionRepo: SubjectCollectionRepository) {
    if (TvHeroMediaCache.peekSubjectInfo(subjectId) != null) return
    // 取消必须重抛: 吞掉它会让已取消的流水线接着往下跑 (见 prefetchTvBackdrop)
    val info = try {
        collectionRepo.subjectCollectionFlow(subjectId).first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return
    }
    TvHeroMediaCache.putSubjectInfo(subjectId, info)
}

/**
 * 时间线里每天开头那一行日期标签 (跟着条目一起滚): "9/13 今天 · 共 14 部" + "现在 16:15 · 待播 1 部 · 在追 2 部".
 * 字贴着行底, 紧挨着当天第一部. 首屏占位期间只写日期 (骨架的部数不是真数). 左栏顶上那块用的是同一套字.
 */
@Composable
private fun TvScheduleDayHeader(
    day: ScheduleDay?,
    items: TvScheduleDayItems,
    followedCount: Int,
    placeholder: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(TV_SCHEDULE_HEADER_HEIGHT).padding(bottom = TV_SCHEDULE_HEADER_TEXT_BOTTOM_PAD),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (day == null) return@Row
        Text(
            tvScheduleDayTitle(day, items.cards.size, placeholder),
            color = if (day.kind == ScheduleDay.Kind.TODAY) MaterialTheme.colorScheme.primary else tvHeroContentColor(),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TV_SCHEDULE_TABULAR_NUMS),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        tvScheduleDayDetails(items, followedCount, placeholder)?.let { details ->
            Text(
                details,
                Modifier.padding(start = 14.dp),
                color = tvHeroSecondaryContentColor(),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TV_SCHEDULE_TABULAR_NUMS),
                maxLines = 1,
            )
        }
    }
}

/** 日期: "9/13 今天" / "9/14 下周一". */
@Composable
private fun tvScheduleDayLabel(day: ScheduleDay): String =
    "${day.date.month.number}/${day.date.day} " + renderTvScheduleWeekday(day)

/** 日期标签的标题: "9/13 今天 · 共 14 部" (首屏占位期间只写日期: 骨架的部数不是真数). */
@Composable
private fun tvScheduleDayTitle(day: ScheduleDay, total: Int, placeholder: Boolean): String {
    val dayLabel = tvScheduleDayLabel(day)
    return if (placeholder) dayLabel else dayLabel + " · " + stringResource(Lang.exploration_tv_schedule_total, total)
}

/**
 * 日期标签的细节: 今天写 "现在 16:15 · 待播 1 部", 有你追的再加 "在追 2 部"; 都没有 (或首屏占位期间) 为 null.
 * 左栏用两个开关: [followedColor] 给"在追 N 部"那一段单独上色 (0 部不上色), [alwaysShowFollowed] 0 部也写 —— 那一行每天都有.
 */
@Composable
private fun tvScheduleDayDetails(
    items: TvScheduleDayItems,
    followedCount: Int,
    placeholder: Boolean,
    followedColor: Color? = null,
    alwaysShowFollowed: Boolean = false,
): AnnotatedString? {
    if (placeholder) return null
    val currentTime = items.currentTime
    val parts = buildList {
        if (currentTime != null) {
            add(stringResource(Lang.exploration_tv_schedule_now, ScheduleItemDefaults.renderTime(null, currentTime)))
            add(stringResource(Lang.exploration_tv_schedule_upcoming, items.cards.size - items.firstUpcomingIndex))
        }
    }
    val followed = if (followedCount > 0 || alwaysShowFollowed) {
        stringResource(Lang.exploration_tv_schedule_following, followedCount)
    } else {
        null
    }
    if (parts.isEmpty() && followed == null) return null
    return buildAnnotatedString {
        append(parts.joinToString(" · "))
        if (followed != null) {
            if (parts.isNotEmpty()) append(" · ")
            if (followedColor == null || followedCount == 0) {
                append(followed)
            } else {
                withStyle(SpanStyle(color = followedColor)) { append(followed) }
            }
        }
    }
}

/** 某天一部都没有时, 日期标签下面的那一行提示 (不可聚焦). */
@Composable
private fun TvScheduleEmptyDay(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(TV_SCHEDULE_ROW_HEIGHT).padding(start = TV_SCHEDULE_TIME_COLUMN_WIDTH + 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            stringResource(Lang.exploration_tv_schedule_empty),
            color = tvHeroSecondaryContentColor(),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 时间线的一行: [时刻] [圆角方形小图 · 番名 (18sp) + 集数与集名] —— 时刻只在同一时刻的第一行写 ([showTime]),
 * 你追的在番名后标心形, 已播出的文字转次要色, 今天下一部待播 ([isNext]) 的时刻用主题色、上方画"现在"那条线.
 *
 * 行本身不画聚焦样式: 焦点框固定在第二格, 由时间线统一画 (见 [TvScheduleFocusFrame]).
 * 长按弹 [menu] (收藏下拉), 以行尾为锚点. [item] 为 null 时是占位骨架 (不可聚焦).
 */
@Composable
private fun TvScheduleRow(
    card: TvScheduleCardData,
    showTime: Boolean,
    isNext: Boolean,
    nowLineAbove: Boolean,
    followed: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    menu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val item = card.item
    val nowLineColor = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .height(TV_SCHEDULE_ROW_HEIGHT)
            // "现在"那条线画在与上一行之间的空隙里 (行间距的正中), 不占布局高度
            .drawBehind {
                if (nowLineAbove) {
                    val y = -TV_SCHEDULE_ROW_SPACING.toPx() / 2
                    val stroke = TV_SCHEDULE_NOW_LINE_WIDTH.toPx()
                    drawCircle(nowLineColor, radius = stroke * 2, center = Offset(stroke * 2, y))
                    drawLine(nowLineColor, Offset(stroke * 2, y), Offset(size.width, y), strokeWidth = stroke)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(TV_SCHEDULE_TIME_COLUMN_WIDTH)) {
            if (showTime && item != null) {
                Text(
                    ScheduleItemDefaults.renderTime(
                        null,
                        item.time,
                        timeUnknownText = stringResource(Lang.exploration_schedule_time_unknown),
                    ),
                    color = when {
                        card.aired -> tvHeroSecondaryContentColor()
                        isNext -> MaterialTheme.colorScheme.primary
                        else -> tvHeroContentColor()
                    },
                    // "时间未定" 是四个字, 按时刻的字号放不进时刻列
                    style = if (item.time != null) {
                        MaterialTheme.typography.titleLarge.copy(
                            fontSize = TV_SCHEDULE_ROW_TIME_SIZE,
                            fontFeatureSettings = TV_SCHEDULE_TABULAR_NUMS,
                        )
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (item == null) {
            TvScheduleRowSkeleton(Modifier.weight(1f).fillMaxHeight())
            return@Row
        }
        val interactionSource = remember { MutableInteractionSource() }
        var menuExpanded by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { if (it.isFocused) onFocused() }
                    .then(
                        // 有菜单才接管确认键 (按住途中到阈值立即弹菜单, 短按仍是点击)
                        if (menu == null) {
                            Modifier
                        } else {
                            Modifier.tvLongPressKey(onLongPress = { menuExpanded = true }, onShortPress = onClick)
                        },
                    )
                    .tvTouchFocusOnTap()
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onClick,
                        onLongClick = menu?.let { { menuExpanded = true } },
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(TV_SCHEDULE_ROW_THUMB_SIZE)
                        .clip(RoundedCornerShape(TV_SCHEDULE_ROW_THUMB_CORNER))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    TvScheduleCoverImage(item.imageUrl, contentDescription = null)
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.subjectTitle,
                            Modifier.weight(1f, fill = false),
                            color = if (card.aired) tvHeroSecondaryContentColor() else tvHeroContentColor(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = TV_SCHEDULE_ROW_TITLE_SIZE,
                                lineHeight = TV_SCHEDULE_ROW_TITLE_LINE_HEIGHT,
                            ),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (followed) {
                            Icon(
                                Icons.Rounded.Favorite,
                                contentDescription = null,
                                Modifier.padding(start = 8.dp).size(TV_SCHEDULE_ROW_FOLLOWED_ICON_SIZE),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        tvScheduleEpisodeLine(item),
                        Modifier.padding(top = 2.dp),
                        color = tvHeroSecondaryContentColor(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = TV_SCHEDULE_ROW_SUBTITLE_SIZE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 菜单以行尾为锚点弹出: 放一个对齐到行尾的零尺寸锚点
            if (menu != null) {
                Box(Modifier.align(Alignment.BottomEnd)) {
                    menu(menuExpanded) { menuExpanded = false }
                }
            }
        }
    }
}

/** 占位骨架行 (首屏加载中): 方形小图 + 两条横杠. 完整视觉效果档才脉动 (理由同 TvPortraitCard 的骨架). */
@Composable
private fun TvScheduleRowSkeleton(modifier: Modifier = Modifier) {
    val highlight: (@Composable () -> PlaceholderHighlight)? =
        if (LocalThemeSettings.current.visualEffects.ambient) ({ PlaceholderHighlight.fade() }) else null
    Row(modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(TV_SCHEDULE_ROW_THUMB_SIZE)
                .placeholder(true, shape = RoundedCornerShape(TV_SCHEDULE_ROW_THUMB_CORNER), highlight = highlight),
        )
        Column(Modifier.padding(start = 14.dp)) {
            Box(Modifier.width(180.dp).height(16.dp).placeholder(true, highlight = highlight))
            Box(Modifier.padding(top = 8.dp).width(120.dp).height(12.dp).placeholder(true, highlight = highlight))
        }
    }
}

/**
 * 封面图 (时间线小图与左栏大图共用): 并发洪峰里失败的请求自动重试, 被丢弃时没下完的交给后台跑完写进磁盘缓存
 * (同 TvPortraitCard, 见 rememberAsyncImageRetryState / rememberImageCompletionGrace).
 */
@Composable
private fun TvScheduleCoverImage(url: String, contentDescription: String?) {
    val retry = rememberAsyncImageRetryState(url)
    val loaded = rememberImageCompletionGrace(url)
    AsyncImage(
        if (retry.suppressed) null else url,
        contentDescription = contentDescription,
        Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        onSuccess = { loaded.value = true },
        onError = { retry.onError() },
    )
}

/**
 * 左栏的状态, 每一部都有: "已播出" / "还有 2 小时 10 分" / "还有 25 分钟" / "即将播出" (今天待播、知道时刻的) /
 * "未播出" (未来的日子、时间未定的). 以前后两种不写, 那一行时有时无, 下面几行跟着上下跳.
 */
@Composable
private fun tvScheduleStatusText(card: TvScheduleCardData, currentTime: LocalTime?): String {
    if (card.aired) return stringResource(Lang.exploration_tv_schedule_aired)
    val time = card.item?.time
    if (time == null || currentTime == null) return stringResource(Lang.exploration_tv_schedule_not_aired)
    val minutes = (time.toSecondOfDay() - currentTime.toSecondOfDay()) / 60
    return when {
        minutes <= 0 -> stringResource(Lang.exploration_tv_schedule_starting)
        minutes < 60 -> stringResource(Lang.exploration_tv_schedule_starts_in_minutes, minutes)
        else -> stringResource(Lang.exploration_tv_schedule_starts_in_hours, minutes / 60, minutes % 60)
    }
}

/** "第 11 话 · 第十一怪": 集号 + 集名 (有的话). 集名里偶尔带着没解码的 HTML 实体 (上游数据, 如 `&amp;`), 顺手解掉. */
@Composable
private fun tvScheduleEpisodeLine(item: AiringScheduleItemPresentation): String {
    val label = rememberEpisodeLabel(item)
    val name = item.episodeName?.let(::decodeBasicHtmlEntities)?.takeIf { it.isNotBlank() }
    return if (name == null) label else "$label · $name"
}

private fun decodeBasicHtmlEntities(text: String): String {
    if ('&' !in text) return text
    return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&")
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
        kotlinx.datetime.DayOfWeek.MONDAY -> stringResource(Lang.exploration_schedule_weekday_monday)
        kotlinx.datetime.DayOfWeek.TUESDAY -> stringResource(Lang.exploration_schedule_weekday_tuesday)
        kotlinx.datetime.DayOfWeek.WEDNESDAY -> stringResource(Lang.exploration_schedule_weekday_wednesday)
        kotlinx.datetime.DayOfWeek.THURSDAY -> stringResource(Lang.exploration_schedule_weekday_thursday)
        kotlinx.datetime.DayOfWeek.FRIDAY -> stringResource(Lang.exploration_schedule_weekday_friday)
        kotlinx.datetime.DayOfWeek.SATURDAY -> stringResource(Lang.exploration_schedule_weekday_saturday)
        else -> stringResource(Lang.exploration_schedule_weekday_sunday)
    }
    return when (day.kind) {
        ScheduleDay.Kind.LAST_WEEK -> stringResource(Lang.exploration_schedule_last_weekday, weekday)
        ScheduleDay.Kind.NEXT_WEEK -> stringResource(Lang.exploration_schedule_next_weekday, weekday)
        else -> stringResource(Lang.exploration_schedule_this_weekday, weekday)
    }
}

/** 时间线一行的数据; [item] 为 null 表示占位骨架 (首屏加载中). [aired] = 该集已播出. */
private data class TvScheduleCardData(
    val item: AiringScheduleItemPresentation?,
    val aired: Boolean,
)

/** 左栏顶上那块日期标签要的: 选中那部所在的那一天、那天的内容、那天有几部你追的. */
private data class TvSchedulePanelDay(
    val day: ScheduleDay,
    val items: TvScheduleDayItems,
    val followedCount: Int,
)

/** 某一天的内容; [currentTime] 非 null 表示这天是今天 (上游插了当前时刻指示器). */
private data class TvScheduleDayItems(
    val cards: List<TvScheduleCardData>,
    val currentTime: LocalTime?,
) {
    /** 第一部尚未播出的下标; 全部播完时等于 [cards] 大小 (即"待播 0 部"). */
    val firstUpcomingIndex: Int
        get() = cards.indexOfFirst { !it.aired }.let { if (it < 0) cards.size else it }

    /** 默认落点: 待播里你追的第一部 → 待播第一部 → 你追的第一部 → 第一部. */
    fun defaultIndex(followed: (subjectId: Int) -> Boolean): Int {
        val followedAt = { i: Int -> cards[i].item?.subjectId?.let(followed) == true }
        val upcoming = cards.indices.filter { !cards[it].aired }
        return upcoming.firstOrNull { followedAt(it) }
            ?: upcoming.firstOrNull()
            ?: cards.indices.firstOrNull { followedAt(it) }
            ?: 0
    }

    /**
     * 跳天时的落点: 时刻不早于 [time] 的第一部, 都早于它就落最后一部有时刻的. [time] 为 null (时间未定) 时
     * 落到时间未定那一组的第一部, 没有那一组就落最后一部.
     */
    fun indexNearest(time: LocalTime?): Int {
        if (cards.isEmpty()) return 0
        if (time == null) {
            return cards.indexOfFirst { it.item != null && it.item.time == null }.takeIf { it >= 0 } ?: cards.lastIndex
        }
        val atOrAfter = cards.indexOfFirst { card -> card.item?.time?.let { it >= time } == true }
        if (atOrAfter >= 0) return atOrAfter
        return cards.indexOfLast { it.item?.time != null }.takeIf { it >= 0 } ?: cards.lastIndex
    }
}

/** 时间线上的一格: 某天开头的日期标签 / 一部 / 空天提示. 只有 [Item] 可聚焦. */
private sealed interface TvScheduleEntry {
    val dayIndex: Int

    data class Header(override val dayIndex: Int, val items: TvScheduleDayItems) : TvScheduleEntry

    data class Item(
        override val dayIndex: Int,
        val card: TvScheduleCardData,
        /** 同一天里同一时刻的第一部 (时刻只写一次; 时间未定的那一组也算一组). */
        val startsTimeGroup: Boolean,
        /** 今天下一部待播 (时刻用主题色; 不是当天第一部时上方画"现在"那条线). */
        val isNext: Boolean,
        val firstOfDay: Boolean,
    ) : TvScheduleEntry

    data class Empty(override val dayIndex: Int) : TvScheduleEntry
}

/**
 * 15 天接成的一条时间线: 每天 = 一行日期标签 + 当天各部 (没有就一行空天提示). 下标就是 LazyGrid 的 item 下标,
 * 送焦 / 滚动 / 按键都按它算; [itemEntries] 记着每天第 i 部落在哪一格.
 */
private class TvScheduleTimeline(
    val days: List<TvScheduleDayItems>,
    val entries: List<TvScheduleEntry>,
    private val itemEntries: List<IntArray>,
) {
    /** 有没有可落的那一部 (首屏占位的骨架也算, 调用方另看 isPlaceholder). */
    val hasItems: Boolean get() = itemEntries.any { it.isNotEmpty() }

    fun dayOf(entry: Int): Int = entries.getOrNull(entry)?.dayIndex ?: 0
    fun dayOfOrNull(entry: Int): Int? = entries.getOrNull(entry)?.dayIndex
    fun cardAt(entry: Int): TvScheduleCardData? = (entries.getOrNull(entry) as? TvScheduleEntry.Item)?.card
    fun isItem(entry: Int): Boolean = entries.getOrNull(entry) is TvScheduleEntry.Item
    fun prevItem(entry: Int): Int? = (entry - 1 downTo 0).firstOrNull { isItem(it) }
    fun nextItem(entry: Int): Int? = (entry + 1 until entries.size).firstOrNull { isItem(it) }

    fun defaultItemOf(day: Int, followed: (Int) -> Boolean): Int? {
        val items = days.getOrNull(day)?.takeIf { it.cards.isNotEmpty() } ?: return null
        return itemEntries[day].getOrNull(items.defaultIndex(followed))
    }

    /** 进时间线的落点: [day] 的默认那部; 那天一部都没有就落最近有番的一天 (先往后找, 再往前找). */
    fun entryItemFrom(day: Int, followed: (Int) -> Boolean): Int? =
        defaultItemOf(day, followed)
            ?: nonEmptyDayFrom(day, 1)?.let { defaultItemOf(it, followed) }
            ?: nonEmptyDayFrom(day, -1)?.let { defaultItemOf(it, followed) }

    fun nearestItemIn(day: Int, time: LocalTime?): Int? {
        val items = days.getOrNull(day)?.takeIf { it.cards.isNotEmpty() } ?: return null
        return itemEntries[day].getOrNull(items.indexNearest(time))
    }

    /** 从 [day] 往 [delta] 方向找第一个有内容的天 (空天跳过). */
    fun nonEmptyDayFrom(day: Int, delta: Int): Int? {
        var d = day + delta
        while (d in days.indices) {
            if (days[d].cards.isNotEmpty()) return d
            d += delta
        }
        return null
    }
}

private fun buildTvScheduleTimeline(presentation: SchedulePagePresentation, todayIndex: Int): TvScheduleTimeline {
    val days = presentation.days.indices.map { buildTvScheduleDayItems(presentation, it, todayIndex) }
    val entries = ArrayList<TvScheduleEntry>()
    val itemEntries = ArrayList<IntArray>(days.size)
    for ((d, day) in days.withIndex()) {
        entries += TvScheduleEntry.Header(d, day)
        if (day.cards.isEmpty()) entries += TvScheduleEntry.Empty(d)
        val next = if (day.currentTime != null) day.firstUpcomingIndex else -1
        itemEntries += IntArray(day.cards.size) { i ->
            val card = day.cards[i]
            entries += TvScheduleEntry.Item(
                dayIndex = d,
                card = card,
                startsTimeGroup = i == 0 || card.item?.time != day.cards[i - 1].item?.time,
                isNext = i == next,
                firstOfDay = i == 0,
            )
            entries.lastIndex
        }
    }
    return TvScheduleTimeline(days, entries, itemEntries)
}

/**
 * 第 [dayIndex] 天的内容. 上游已把"当前时刻指示器"插在正确位置 (仅今天有), 它之前的即已播出;
 * 指示器不占行, 只用来划已播/未播的界与取"现在几点". 过去的日子整天都已播出, 未来的日子一部都还没播.
 */
private fun buildTvScheduleDayItems(
    presentation: SchedulePagePresentation,
    dayIndex: Int,
    todayIndex: Int,
): TvScheduleDayItems {
    val days = presentation.days
    val day = days.getOrNull(dayIndex.coerceIn(0, days.lastIndex.coerceAtLeast(0)))
    val columnItems = day
        ?.let { d -> presentation.airingSchedules.firstOrNull { it.date == d.date }?.episodes }
        .orEmpty()
    val indicatorPos = columnItems.indexOfFirst { it is AiringScheduleColumnItem.CurrentTimeIndicator }
    val isPast = day != null && dayIndex < todayIndex
    val cards = columnItems.mapIndexedNotNull { pos, columnItem ->
        when (columnItem) {
            is AiringScheduleColumnItem.Data -> TvScheduleCardData(
                columnItem.item,
                aired = isPast || (indicatorPos >= 0 && pos < indicatorPos),
            )

            is AiringScheduleColumnItem.PlaceholderData -> TvScheduleCardData(null, aired = false)
            is AiringScheduleColumnItem.CurrentTimeIndicator -> null
        }
    }
    return TvScheduleDayItems(
        cards = cards,
        currentTime = (columnItems.getOrNull(indicatorPos) as? AiringScheduleColumnItem.CurrentTimeIndicator)
            ?.takeIf { !it.isPlaceholder }?.currentTime,
    )
}

/**
 * 背景 / 左栏那一部 -> 底图取图请求.
 *
 * 原名与播出日期不在这里: 邻居也要这两样, 而邻居只有 id —— 统一按 subjectId 查整条时间线那张表
 * (见 `cardNames`), 免得同一份信息在两处各带一遍.
 */
private data class TvScheduleBackdropTarget(
    val subjectId: Int,
    /** 该条目的竖版封面: 没有 TMDB 横版图时拿它当底图 (居中裁切), 见 [tvHeroBackdropUrl]. */
    val coverUrl: String = "",
    /** 时间线上下两部, 供流水线预取, 见 [tvGridNeighborsOf]. */
    val neighbors: TvHeroNeighbors = TvHeroNeighbors(),
)

/** 需要查询本地收藏类型的全部类型 (供长按菜单与左栏标签显示当前状态). */
private val TV_SCHEDULE_COLLECTION_TYPES = listOf(
    UnifiedCollectionType.WISH,
    UnifiedCollectionType.DOING,
    UnifiedCollectionType.ON_HOLD,
    UnifiedCollectionType.DONE,
    UnifiedCollectionType.DROPPED,
)

/** "你追的" (行尾心形、日期标签"在追 N 部"、默认落点) 计入的收藏类型. */
private val TV_SCHEDULE_FOLLOWED_TYPES = setOf(UnifiedCollectionType.DOING, UnifiedCollectionType.WISH)

/** 等宽数字: 时刻与集号是数字列, 行与行之间要竖着对齐. */
private const val TV_SCHEDULE_TABULAR_NUMS = "tnum"

// ---- 页面骨架 ----

/**
 * 屏幕四边的留白, 上下左右一样 (用户: "上下左右边距都应该比较一致, 除了时间表下面的一直伸长的项不需要管"; 本页是独立
 * 目的地, 没有侧边栏). 时间线上下出血穿过它铺到屏幕边, 越过第一格往上走的那部就在上面这段留白里淡完 —— 所以它不能小于
 * 淡出距离 (一部的一半 + 行距, 约 37dp), 不然没淡完就碰到屏幕顶边被切.
 */
private val TV_SCHEDULE_EDGE_PAD = 40.dp

/**
 * 左栏 (封面 + 信息) 占两栏总宽的比例, 其余归右栏 (时间线). 按字放不放得下来定 (用户: "左右比例你按字放得下来定"):
 * 左栏约 360dp 时封面右边的侧栏约 170dp, "还有 2 小时 10 分"、"第 12 话 / 共 14 话" 都是一行. 四边留白加大之后左栏保持
 * 这个宽度, 多出来的边距从右栏匀 (用户: "你可以适当调整右边的时间表的宽度").
 */
private const val TV_SCHEDULE_LEFT_WEIGHT = 0.42f

/** 左右两栏之间的间距. */
private val TV_SCHEDULE_COLUMN_GAP = 20.dp

/** 全屏背景之上再压的一层: 左栏文字与时间线都压在图上, 得读得清. */
private const val TV_SCHEDULE_BACKDROP_EXTRA_DIM_ALPHA = 0.35f

// ---- 左栏 ----

/**
 * 左栏顶上日期那块到右边第二格顶线的空当. 默认字号下那两行字高 56dp, 加上它正好是时间线一格 (68dp), 两边的日期字落在
 * 同一条底线上; 用处再减去焦点框往上多出的那截 ([TV_SCHEDULE_FOCUS_FRAME_OUTSET]), 封面顶边于是对齐右边固定焦点框的顶边.
 * 系统字号放大时那块变高, 封面跟着往下让.
 */
private val TV_SCHEDULE_PANEL_DAY_TO_COVER_GAP = 12.dp

/**
 * 封面行 (封面 + 右边侧栏) 占顶上日期标签那块以下高度的比例. 定比例而不是"剩下的都给封面": 封面大小不随番名、简介变.
 * 电视上 (540dp 高) 封面约 243×175dp, 侧栏的集名还能放两行; 下面是番名 (一两行) + 原名 + 简介 (两三行).
 */
private const val TV_SCHEDULE_PANEL_COVER_FRACTION = 0.62f

/** 左栏封面圆角. */
private val TV_SCHEDULE_PANEL_COVER_CORNER = 12.dp

/** 封面与右边侧栏的间距. */
private val TV_SCHEDULE_PANEL_SIDE_GAP = 16.dp

/** 封面到下方番名的间距. */
private val TV_SCHEDULE_PANEL_COVER_TO_TITLE_GAP = 12.dp

/** 原名到简介的间距. */
private val TV_SCHEDULE_PANEL_SUMMARY_GAP = 6.dp

/** 侧栏最多几个标签 (同搜索页); 一行放不下时 [TvScheduleTagLine] 自己少放. */
private const val TV_SCHEDULE_PANEL_TAG_LIMIT = 3

/**
 * 左栏的字只分四档, 按重要程度排, 同一档同一样式 (用户: "左边这些字体样式都太乱了, 你根据他们的重要程度重新调整,
 * 至少日期不应该这么小" —— 当时十来种字号 / 字重 / 行高各写各的, 日期只有 16sp; 后来侧栏的播出时刻去掉, 原来最大的
 * 那一档随之取消). 颜色另按角色取: 主要 = 正文色, 次要 = 次要色, 强调 (今天、倒计时、评分、在追数) = 主题色.
 */
private object TvSchedulePanelText {
    /** 一档 · 标题: 日期 ("9/13 今天")、番名、第几话. */
    val headline: TextStyle @Composable get() = tvSchedulePanelTextStyle(24.sp, 30.sp, FontWeight.SemiBold)

    /** 二档 · 评分. */
    val subhead: TextStyle @Composable get() = tvSchedulePanelTextStyle(18.sp, 24.sp, FontWeight.SemiBold)

    /** 三档 · 正文: 播出状态、共几话、集名、标签、日期细节、原名、"共 N 部". */
    val body: TextStyle @Composable get() = tvSchedulePanelTextStyle(16.sp, 24.sp, FontWeight.Normal)

    /** 四档 · 小字: 简介、排名、收藏标签. */
    val small: TextStyle @Composable get() = tvSchedulePanelTextStyle(14.sp, 20.sp, FontWeight.Normal)
}

@Composable
private fun tvSchedulePanelTextStyle(size: TextUnit, lineHeight: TextUnit, weight: FontWeight): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(
        fontSize = size,
        lineHeight = lineHeight,
        fontWeight = weight,
        fontFeatureSettings = TV_SCHEDULE_TABULAR_NUMS,
    )

// ---- 时间线 ----

/** 时间线一行 (一部) 的**定高**. */
private val TV_SCHEDULE_ROW_HEIGHT = 62.dp

/**
 * 行间距 ("现在"那条线画在它的正中).
 *
 * 6 -> 10dp: 焦点框上下各外扩 [TV_SCHEDULE_FOCUS_FRAME_OUTSET], 原来那个值是"行距的一半", 于是**框的边缘与线落在同一处**
 * (都在间隙正中), 看着就是线被框顶住、不在两部正中间 (用户 2026-09-16). 光加大行距没用 —— 外扩量是从行距派生的,
 * 两者会一起往外走继续重合; 必须同时把外扩量与行距**解耦** (见下).
 */
private val TV_SCHEDULE_ROW_SPACING = 10.dp

/**
 * 日期标签行的高度: **与一部同高** (字靠下贴着当天第一部). 整条时间线每一格一样高, 格线整齐; 左栏顶上那块日期标签
 * 连同到封面的空当默认也是这一格 (见 [TV_SCHEDULE_PANEL_DAY_TO_COVER_GAP]). 必须写在 [TV_SCHEDULE_ROW_HEIGHT] 之后:
 * 顶层 val 按文本顺序初始化, 前向引用拿到的是 0.
 */
private val TV_SCHEDULE_HEADER_HEIGHT = TV_SCHEDULE_ROW_HEIGHT

/** 时间线里日期标签的字离格底的距离. 左栏顶上那块默认字号下也落在这条底线上 (见 TV_SCHEDULE_PANEL_DAY_TO_COVER_GAP). */
private val TV_SCHEDULE_HEADER_TEXT_BOTTOM_PAD = 6.dp

/**
 * 固定焦点框离时间线顶边的距离: 第二格 (上面留一格看得见前一部 / 日期标签). 列表首尾各补白到能让任意一格停进框里,
 * 于是 `scrollToItem(i)` 就是"把第 i 格送进框", 送焦与滚动都按这一条算.
 */
private val TV_SCHEDULE_FOCUS_ANCHOR = TV_SCHEDULE_ROW_HEIGHT + TV_SCHEDULE_ROW_SPACING

/**
 * 固定焦点框比一格上下各多出的距离.
 *
 * 原来是 `行距 / 2` —— 框的上下边正好落在两部之间的空当正中 (用户当时: "焦点框有点挤, 上下边是不是可以往外扩一点,
 * 比如框的上边改成正好放在两个表项中间"). 但"现在"那条线也画在同一处, 两者必然重合, 于是线看着不在两部正中间.
 * 现在**写成绝对值、不再从行距派生**: 行距 10dp 的空当里, 框各占 3dp, 线在 5dp 处 (2dp 粗, 占 4~6dp), 中间留得开.
 * 框比原来还宽松一点 (原来也是 3dp), 只是空当变大了. 必须写在 [TV_SCHEDULE_ROW_SPACING] 之后 (前向引用拿到 0).
 */
private val TV_SCHEDULE_FOCUS_FRAME_OUTSET = 3.dp

/** 行 (与固定焦点框) 的圆角. */
private val TV_SCHEDULE_ROW_CORNER = 10.dp

/** 固定焦点框的底色不透明度. */
private const val TV_SCHEDULE_ROW_FOCUSED_ALPHA = 0.85f

/** 时刻列宽 ("23:30" 20sp 等宽数字 + 与小图之间的空当). */
private val TV_SCHEDULE_TIME_COLUMN_WIDTH = 68.dp

/**
 * 行内小图: 圆角正方形, 边长约等于行高去掉上下边距. 用户: "单个条目左边的图压成圆角方形, 不然看不清里面的信息" ——
 * 上一版是 36dp 宽的竖版小封面, 太窄; 正方形居中裁切, 面积大了一半, 画面主体看得清.
 */
private val TV_SCHEDULE_ROW_THUMB_SIZE = 50.dp

/** 行内小图圆角. */
private val TV_SCHEDULE_ROW_THUMB_CORNER = 8.dp

/** 时刻字号. */
private val TV_SCHEDULE_ROW_TIME_SIZE = 20.sp

/** 番名字号与行高 (单行): 上一版卡片下的番名是 11sp. */
private val TV_SCHEDULE_ROW_TITLE_SIZE = 18.sp
private val TV_SCHEDULE_ROW_TITLE_LINE_HEIGHT = 24.sp

/** 集数与集名字号. */
private val TV_SCHEDULE_ROW_SUBTITLE_SIZE = 14.sp

/** 番名后"你追的"心形的尺寸. */
private val TV_SCHEDULE_ROW_FOLLOWED_ICON_SIZE = 16.dp

/** "现在"那条线的粗细. */
private val TV_SCHEDULE_NOW_LINE_WIDTH = 2.dp
