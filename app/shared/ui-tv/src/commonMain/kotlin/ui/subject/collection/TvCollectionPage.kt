/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectWithLifecycle
import androidx.paging.compose.itemKey
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.toNavPlaceholder
import me.him188.ani.app.data.network.BangumiSummaryService
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.newestAiredDateStringOrNull
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.navigation.OnReturnToForeground
import me.him188.ani.app.tools.WeekFormatter
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.isAutoRepeat
import me.him188.ani.app.ui.foundation.stateOf
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.focus.TvScrollAnimator
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.tv.TvPageBackdropLayer
import me.him188.ani.app.ui.foundation.tv.TvPortraitCard
import me.him188.ani.app.ui.foundation.TvPageRefreshHandler
import me.him188.ani.app.ui.foundation.tv.tvPlayKeyShortPress
import me.him188.ani.app.ui.foundation.focus.tvFocusMoveRateLimit
import me.him188.ani.app.ui.foundation.tv.rememberTvSettledHeroProvider
import me.him188.ani.app.ui.foundation.tv.TV_HERO_MEDIA_DEBOUNCE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_NAV_LOCK_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvNavigationSettle
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaCache
import me.him188.ani.app.ui.foundation.tv.TvNextEpisodeMedia
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaSpec
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbor
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbors
import me.him188.ani.app.ui.foundation.tv.rememberTvHeroMediaPipeline
import me.him188.ani.app.ui.foundation.tv.resolveTvHeroMedia
import me.him188.ani.app.ui.foundation.tv.tvGridNeighborsOf
import me.him188.ani.app.ui.foundation.tv.prefetchTvSummaryFallback
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropUrl
import me.him188.ani.app.ui.foundation.tv.TV_HERO_TEXT_FADE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_TITLE_WIDTH_FRACTION
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_BOTTOM_SCRIM_HEIGHT
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_BOTTOM_SCRIM_MAX_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_CARD_SPACING
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_CARD_WIDTH
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_END_PAD
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_HINT_BOTTOM_PAD
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_HINT_ICON_SIZE
import me.him188.ani.app.ui.foundation.tv.TV_PORTRAIT_CARD_COVER_RATIO
import me.him188.ani.app.ui.foundation.tv.TV_HERO_SUMMARY_WIDTH_FRACTION
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvHeroMarqueeIterations
import me.him188.ani.app.ui.foundation.focus.TvFocusTransitAnchor
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvGridFocus
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusRail
import me.him188.ani.app.ui.foundation.focus.tvFocusRailItem
import me.him188.ani.app.ui.foundation.focus.tvFocusRailKeys
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.focus.tvGridFocusItem
import me.him188.ani.app.ui.foundation.focus.tvGridKeyNavigation
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.collection_tv_empty
import me.him188.ani.app.ui.lang.exploration_tv_air_date
import me.him188.ani.app.ui.lang.exploration_tv_all_caught_up
import me.him188.ani.app.ui.lang.exploration_tv_minutes_left
import me.him188.ani.app.ui.lang.exploration_tv_next_episode
import me.him188.ani.app.ui.lang.exploration_tv_watched_latest
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.subject_collection_doing
import me.him188.ani.app.ui.lang.subject_collection_done
import me.him188.ani.app.ui.lang.subject_collection_dropped
import me.him188.ani.app.ui.lang.subject_collection_on_hold
import me.him188.ani.app.ui.lang.subject_collection_uncollected
import me.him188.ani.app.ui.lang.subject_collection_wish
import me.him188.ani.app.ui.lang.subject_progress_continue_watching
import me.him188.ani.app.ui.lang.subject_progress_start_watching
import me.him188.ani.app.ui.lang.subject_progress_updates_on
import me.him188.ani.app.ui.lang.tv_card_remote_hint
import me.him188.ani.app.ui.search.LoadErrorCard
import me.him188.ani.app.ui.search.isLoadingFirstPageOrRefreshing
import me.him188.ani.app.ui.subject.AiringLabel
import me.him188.ani.app.ui.subject.AiringLabelState
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.datasources.api.toLocalDateOrNull
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.analytics.Analytics
import me.him188.ani.utils.analytics.AnalyticsEvent.Companion.SubjectEnter
import me.him188.ani.utils.analytics.recordEvent
import org.jetbrains.compose.resources.stringResource

/**
 * TV 沉浸式追番页 (布局骨架参考 Prime Video 收藏页):
 * - 顶部悬浮收藏分类 Tab (透明底, 未选中降透明度, 选中高亮 + 平滑滑动指示条), 聚焦即切换;
 * - 上半区为 Hero 展示区: 全屏背景为聚焦条目的 TMDB backdrop (在看条目优先下一集单集剧照),
 *   显示标题 / 评分 / 连载信息 / 个人观看状态 (高亮) / 简介, 及动态主操作按钮
 *   (继续观看 第 X 集 / 开始观看 / 重温 / 更多详细内容);
 * - 下半区为 2:3 竖版海报网格, 卡片带播放进度条, 聚焦驱动 Hero, 短按进详情, 长按弹收藏菜单.
 *
 * 焦点动线: Tab 行 ↓ 主按钮 ↓ 网格; 网格首行 ↑ 回主按钮, 主按钮 ↑ 回选中 Tab.
 * 数据全部来自收藏分页列表自身 (条目信息完整, 无需二次请求); 仅 backdrop/单集剧照/简介兜底异步.
 */
@Composable
fun TvCollectionPage(
    state: UserCollectionsState,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val bangumiSummaryService = remember { GlobalKoin.get<BangumiSummaryService>() }
    val settingsRepository = remember { GlobalKoin.get<SettingsRepository>() }
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val playHistoryRepository = remember { GlobalKoin.get<EpisodePlayHistoryRepository>() }
    val setCollectionTypeUseCase = remember { GlobalKoin.get<SetSubjectCollectionTypeOrDeleteUseCase>() }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    // 重新进入本页 (从主页其它 tab 切回来) 一律回到"第一次进入"的样子: 落到本页显示顺序里
    // 第一个有条目的分类 (全空则第一个) + 网格回顶.
    //
    // 不复位的话选中分类留在 [UserCollectionsState] (ViewModel 级, 跨页面存活) 里带着上次的
    // 值回来, 而本页的焦点簿记随组合销毁清零 —— 进页焦点由焦点系统落在标签行第一个标签上,
    // 选中项与网格内容却还是上次那个分类, 二者对不上 (标签的"聚焦即选中"刻意不认系统塞来的
    // 焦点, 见 selectByFocusArmed), 表现为"焦点在最左标签、内容是别的标签、往左直接进侧边栏".
    //
    // 判据用 rememberSaveable 的存活: 主页三个 tab 的 AnimatedContent 不带 SaveableStateHolder,
    // 切走本页保存态就丢了 = 重新进入; 而进详情页/播放器时本页随 NavHost 目的地被
    // SaveableStateProvider 保存, 返回时原样回来 —— 那条路要保留上次的分类与落点
    // (见下方 restoreCardIndex), 不能复位.
    var enteredBefore by rememberSaveable { mutableStateOf(false) }
    val freshEntry = remember { !enteredBefore }
    // 收藏计数是异步来的, 冷启动进页时往往还是 null: 全按 0 算 -> 落到第一个标签, 计数到达后
    // 由下方效应再定一次
    val firstNonEmptyTabIndex: () -> Int = {
        val counts = state.collectionCounts
        val type = TV_COLLECTION_TABS.firstOrNull { (counts?.getCount(it) ?: 0) > 0 }
            ?: TV_COLLECTION_TABS.first()
        COLLECTION_TABS_SORTED.indexOf(type)
    }
    if (freshEntry && !enteredBefore) {
        enteredBefore = true
        // 写在组合体里而不是效应里: 效应要等一帧, 那一帧会先用上次的分类渲染一次
        // (标签指示条与网格内容闪一下再跳走)
        val target = firstNonEmptyTabIndex()
        if (state.selectedTypeIndex != target) state.selectTypeIndex(target)
    }

    // 只取实例不再收集: 选中 tab 的网格 (下方 AnimatedContent 内) 已对同一缓存实例
    // collectWithLifecycle, 页面级再收集会有两个协程并发把每个分页 generation 灌进
    // 同一个 presenter —— 整表 diff 白做两遍, 还会互相竞争
    val items = remember(state.selectedTypeIndex) {
        state.getCollectionLazyPagingItems(state.selectedTypeIndex)
    }

    // 本页 tab 显示顺序与 state 的存储顺序不同 (见 TV_COLLECTION_TABS), 界面一律用类型换算下标
    val selectedType = COLLECTION_TABS_SORTED[state.selectedTypeIndex]
    val selectType: (UnifiedCollectionType) -> Unit = { type ->
        state.selectTypeIndex(COLLECTION_TABS_SORTED.indexOf(type))
    }
    // 统一网格落点协调器 (跨 tab 行对齐 / 网格内同列导航 / 返回回首卡 / 进页恢复焦点共用,
    // 机制见 [TvGridFocusState]); 声明在 hero 默认值效应之前, 后者要在解析期间让路
    val focus = rememberTvFocusScope()
    val gridFocus = rememberTvGridFocus(focus)
    // 跨 tab / 等条目消失 这两段过渡期的隐形焦点驻留点 (理由见 TvFocusTransitAnchor)
    val transitAnchor = remember { FocusRequester() }
    // 焦点当前是否在网格卡片上. 卡片获焦置 true / 正常失焦 (去 tab/hero/侧边栏) 置 false;
    // 分页替换**销毁**聚焦卡时不会有失焦回调 —— 于是保持 true, 恰好是"焦点被销毁夺走而非
    // 用户离开"的判据 (下方塌缩恢复效应用)
    var gridRegionFocused by remember { mutableStateOf(false) }

    // 重新进入本页的收尾 (承上方 freshEntry): 等收藏计数到达后再定一次落哪个分类, 并把网格
    // 拉回顶部 —— hero 在还没聚焦卡片时展示的是列表第一项, 网格却停在上次滚动的位置的话,
    // 就是"信息块讲第一部、网格里看不到它".
    // 声明在这里而不是 freshEntry 那段旁边: 要用下方才声明的 gridFocus 判断用户是否已接手.
    LaunchedEffect(Unit) {
        if (!freshEntry) return@LaunchedEffect
        if (state.collectionCounts == null) {
            val keysAtStart = focus.userNavGeneration
            withTimeoutOrNull(TV_COLLECTION_COUNTS_WAIT_MILLIS) {
                snapshotFlow { state.collectionCounts }.filterNotNull().first()
            }
            // 等待期间用户自己切了标签/进了网格: 他说了算, 不再改选中项
            if (focus.userNavGeneration == keysAtStart && !gridRegionFocused) {
                val target = firstNonEmptyTabIndex()
                if (state.selectedTypeIndex != target) state.selectTypeIndex(target)
            }
        }
        state.getGridState(state.selectedTypeIndex).scrollToItem(0)
    }

    // Hero 数据源: 聚焦卡片时记录该条目快照; 展示时再按 subjectId 对回最新列表数据
    // (看完一集返回本页后分页已刷新, 快照里的进度是旧的)
    var heroItem by remember { mutableStateOf<SubjectCollectionInfo?>(null) }
    // 聚焦卡的邻居 (subjectId -> 邻居), 在 onFocused 里按网格几何算好; 记 subjectId 是为了
    // 默认 hero (列表第一项, 没被聚焦过) 时不错用上一次聚焦位置的邻居
    var heroNeighbors by remember { mutableStateOf<Pair<Int, TvHeroNeighbors>?>(null) }
    // 进页恢复期间的闸门: 这段时间不许设默认 hero。组合那刻武装, 由下方进页恢复效应
    // (LaunchedEffect(Unit)) 的两个分支清掉 —— 恢复分支等落点有结果后清, 新进页分支立即清。
    //
    // 为什么 `!gridFocus.switching` 不够: 它只覆盖"落点已派出"之后。而从详情页返回时本页是**新
    // 组合**的 —— heroItem 归零、列表数据又已在分页缓存里立刻可用, 于是"组合 → 派出落点"那一小段
    // 里 switching 还是 false, 默认 hero 当场被设成列表第一项: 用户看到返回瞬间先闪一下第一张卡
    // 的 backdrop 与信息块, 随后才跳回真正聚焦的那张 (2026-08-23 实测, 与 issue #2 三段接力里
    // 漏掉的那段同源 —— 都是"数据已到但焦点请求还没派出"这个缝)。
    var heroDefaultBlockedByRestore by remember { mutableStateOf(true) }
    // 刚进页 / 切 tab 后还没聚焦过卡片: 默认展示当前列表第一项. 切 tab 数据加载期间保留
    // 旧 hero (信息块与主按钮不闪没, 新信息到了随渐隐换入); 确认新 tab 为空才清掉.
    // 落点解析期间不设默认 (否则先闪一下第一张卡的状态): 目标卡聚焦后由 onFocused
    // 设置 hero; 解析结束 (gridTarget 清空) 后本效应重跑, 只兜底解析失败的情况.
    // 观察值收进 snapshotFlow, 不当 effect key: pending 每次落点请求 设置→清除 变两次,
    // itemCount/加载态也是热读, 当 key 会让页面 body 作用域每键重组 (见 TvGridFocusState.SendFocusEffect)
    LaunchedEffect(state.selectedTypeIndex, items) {
        snapshotFlow {
            Triple(
                items.itemCount > 0,
                items.isLoadingFirstPageOrRefreshing,
                !gridFocus.switching && !heroDefaultBlockedByRestore,
            )
        }.collect { (hasItems, loadingFirstPage, pendingIdle) ->
            val cur = heroItem
            if (hasItems) {
                // 仅当当前 hero 不属于本 tab 列表时设默认, 不抢用户已聚焦的卡
                val curInList = cur != null && items.itemSnapshotList.items.any { it.subjectId == cur.subjectId }
                if (pendingIdle && !curInList) {
                    heroItem = items.peek(0)
                }
            } else if (!loadingFirstPage) {
                heroItem = null
            }
        }
    }
    // 状态化 (derivedStateOf) 而非普通局部值: 下方 LaunchedEffect 的 snapshotFlow 要能观察到变化
    val heroInfo by remember(items) {
        derivedStateOf {
            heroItem?.let { snapshot ->
                items.itemSnapshotList.items.firstOrNull { it.subjectId == snapshot.subjectId } ?: snapshot
            }
        }
    }

    // hero 的**展示**目标: 低特效档下连发导航期间不换背景图/文字, 停下来才换一次 (完整特效档
    // 原样直通). 下面的数据预取仍读真实的 heroInfo —— 停下来时数据已在缓存里, 换挡不等网络.
    // 用 provider 版: 值版本会把 heroInfo 的读记到本页 body 上, 每换一格整页重组, 正是
    // backdrop 层与 hero 信息块收 lambda 想避免的事. 机理与实测数据见 [rememberTvSettledHero]
    val heroDisplay = rememberTvSettledHeroProvider { heroInfo }

    // hero 媒体全部走 TvHeroMediaCache (进程级, 四个 TV 页共用): 原先本页各存一份 remember 表,
    // 于是同一部作品从探索页进详情页有图、从本页进没图 —— 见那里的 KDoc
    val episodeStillCache = TvHeroMediaCache.nextEpisodeMedia
    val summaryFallbackCache = TvHeroMediaCache.summaryFallbacks
    // 播放历史 (响应式): 卡片进度条与 hero 剩余分钟, 退出播放器回本页自动更新
    val playHistories by playHistoryRepository.flow.collectAsStateWithLifecycle(emptyList())

    // hero 媒体流水线 (连发合并/调度器/邻居预取/图片预热/封面兜底), 与探索页同一 host ——
    // 见 rememberTvHeroMediaPipeline. 本页条目信息来自列表 (种进进程缓存后解析链第一跳
    // 直接命中, 不发请求), 解析链实际只剩剧照 + backdrop 两跳.
    // 剧照那跳的语义与从前一致: 观看途中 (Continue/Watched) 优先"下一集"单集剧照;
    // backdrop **有剧照也照拉** —— 它同时是详情页的预取, 见 resolveTvHeroMedia 的 KDoc.
    val fullVisualEffects = LocalThemeSettings.current.tvFullVisualEffects
    val heroPipeline = rememberTvHeroMediaPipeline(
        tmdb = tmdb,
        fullVisualEffects = fullVisualEffects,
        // 键到 items 上: 切 tab 换分页实例时重启, 不然闭包里捕获的是旧 tab 的状态
        restartKey = items,
        spec = {
            heroInfo?.let { info ->
                info.toHeroMediaSpec(
                    heroNeighbors?.takeIf { it.first == info.subjectId }?.second ?: TvHeroNeighbors(),
                )
            }
        },
        resolve = { s ->
            resolveTvHeroMedia(
                s.subjectId, collectionRepo, tmdb,
                preferNextEpisodeStill = s.preferNextEpisodeStill,
                settingsRepository = settingsRepository,
            )
        },
        resolveNeighbor = { _, neighbor ->
            // 邻居的信息就在列表里: 种进进程缓存让第一跳直接命中.
            // 剧照偏好用邻居自带的 (算邻居时就按它自己的状态定好了, 与图片预热同一份判据)
            items.itemSnapshotList.items.firstOrNull { it.subjectId == neighbor.subjectId }
                ?.let { TvHeroMediaCache.putSubjectInfo(neighbor.subjectId, it) }
            resolveTvHeroMedia(
                neighbor.subjectId, collectionRepo, tmdb,
                preferNextEpisodeStill = neighbor.preferNextEpisodeStill,
                settingsRepository = settingsRepository,
            )
        },
        beforeResolve = { s ->
            // 列表自带完整信息 (含分集), 种进进程缓存 —— hero 文字本来就直读列表, 不受媒体链影响
            heroInfo?.takeIf { it.subjectId == s.subjectId }
                ?.let { TvHeroMediaCache.putSubjectInfo(s.subjectId, it) }
        },
        afterResolve = { s ->
            val info = heroInfo
            if (info?.subjectId == s.subjectId && info.subjectInfo.summary.isBlank()) {
                launch { bangumiSummaryService.prefetchTvSummaryFallback(s.subjectId) }
            }
            true
        },
    )

    // 前进导航的转场闸门 (与探索页同款, 见 TvExplorationPage 里那段长注释): 导航发出之后本页
    // 还要在转场动画里活一小会儿, 期间它**仍在组合、仍在收按键**. 不锁的话:
    // - 再按一次确认键 -> 连进两层, 返回要按两下;
    // - 按返回 -> 被本页或主壳的 BackHandler 吃掉 (它们此刻都还注册着), 而主壳那条是
    //   `enabled = page != Exploration -> 切到探索页`, 于是详情页退出后落在探索页顶部,
    //   不是打开时的追番页. 真机复现 (2026-08-22): 点卡片进详情页时快速按返回, 现象正是
    //   "返回默默生效 / 像返回了两次 / 有概率弹出侧边栏".
    // 定时解锁而非永不解锁: 本页正常随导航退出组合, remember 一并丢弃; 万一没退出 (导航被拒)
    // 也能自愈.
    var navLocked by remember { mutableStateOf(false) }
    fun lockNavigationForTransition() {
        navLocked = true
        scope.launch {
            delay(TV_NAV_LOCK_MILLIS)
            navLocked = false
        }
    }

    val navigateToSubject: (SubjectCollectionInfo) -> Unit = { info ->
        if (!navLocked) {
            Analytics.recordEvent(SubjectEnter) {
                put("source", "collection_card")
                put("subject_id", info.subjectId)
            }
            lockNavigationForTransition()
            navigator.navigateSubjectDetails(
                subjectId = info.subjectId,
                placeholder = info.subjectInfo.toNavPlaceholder(),
            )
        }
    }
    // 主按钮: 直接进播放页 —— 看完全部则从第一集重温, 其余接着播 nextEpisodeIdToPlay
    // (追平连载时它指回已看完的最新一集, 即重温最新一集); 无分集信息退化为进详情页
    val navigateToPlay: (SubjectCollectionInfo) -> Unit = { info ->
        val episodeId = when (info.progressInfo.continueWatchingStatus) {
            is ContinueWatchingStatus.Done -> info.episodes.firstOrNull()?.episodeId
            else -> info.progressInfo.nextEpisodeIdToPlay ?: info.episodes.firstOrNull()?.episodeId
        }
        if (episodeId != null) {
            if (!navLocked) {
                Analytics.recordEvent(SubjectEnter) {
                    put("source", "collection_play")
                    put("subject_id", info.subjectId)
                }
                lockNavigationForTransition()
                navigator.navigateEpisodeDetails(info.subjectId, episodeId)
            }
        } else {
            navigateToSubject(info)
        }
    }

    // 焦点动线锚点: 每个 tab 标签一个请求器 (按 TV 显示顺序).
    //
    // 不给"选中的那个 tab"单独共享一个请求器: 那需要 `.then(if (selected) focusRequester(..))`
    // 这样的条件 modifier, 而条件元素位于 clickable (内含 focus target) 之前 —— 选中态一变,
    // 该 tab 后面的焦点节点就会被重建; 焦点恰好在这个 tab 上时会被丢掉, 焦点系统随即把默认
    // 焦点发回第一个可聚焦元素 (第一个 tab). 而"聚焦即选中"意味着每次焦点落到新 tab 都会触发
    // 一次选中态变化, 于是按住方向键快速移动时偶发被拉回最左标签.
    val tabFocusKeys = remember { List(TV_COLLECTION_TABS.size) { CollectionTabFocusKey(it) } }
    // 选中标签在本页显示顺序里的下标. 用函数而非捕获值: 效应/按键回调里调用时要读到最新选中项
    val selectedTabTvIndex: () -> Int = { TV_COLLECTION_TABS.indexOf(COLLECTION_TABS_SORTED[state.selectedTypeIndex]) }
    // 当前持有焦点的标签下标 (按本页显示顺序); -1 = 焦点不在标签行上
    var focusedTabTvIndex by remember { mutableIntStateOf(-1) }
    // 空 tab 的网格落点放弃后, 必须等“选中的标签真实获焦”才能重新开放标签行导航. 真机上
    // requestFocus 返回 accepted 到 onFocusChanged 之间仍有一小段窗口; 长按右键的下一发连发若在
    // 这时交给默认方向搜索, 会从无确定落点的标签行绕回第一项, 但内容仍停在末 tab.
    var selectedTabFocusPending by remember { mutableStateOf(false) }
    // 聚焦当前选中的 tab
    val focusSelectedTab: () -> Boolean = {
        val tvIndex = selectedTabTvIndex()
        if (tvIndex >= 0) {
            selectedTabFocusPending = true
            // 标签也走事件驱动锚点: 请求只由目标标签的真实 onFocusChanged 完成. 系统先把焦点
            // 塞给第一标签时, 它会上报 fallback 触发重送, 不再把 requestFocus(true) 当到位.
            focus.request(tabFocusKeys[tvIndex])
            true
        } else {
            false
        }
    }
    // 列表加载出错 (如未登录) 时的错误横幅: 挂请求器让 tab 下键能落到横幅里的按钮 (登录/重试)
    val errorCardFocusRequester = remember { FocusRequester() }
    // 当前 tab 内最后聚焦的卡片下标 (跨导航保存, 返回本页恢复焦点); 切 tab 重置
    var lastFocusedCard by rememberSaveable { mutableIntStateOf(-1) }
    // 收藏状态刚被改掉、正等着离开本 tab 的条目 (见下方等待效应); null = 没有
    var awaitingRemovalSubjectId by remember { mutableStateOf<Int?>(null) }
    var prevTabIndex by rememberSaveable { mutableIntStateOf(state.selectedTypeIndex) }
    if (prevTabIndex != state.selectedTypeIndex) {
        prevTabIndex = state.selectedTypeIndex
        lastFocusedCard = -1
        // 用户自己切了 tab: 旧 tab 的卡去哪已无关紧要, 别让等待效应在新 tab 里安排落点
        awaitingRemovalSubjectId = null
    }
    // 进入本页要恢复的目标卡片下标 (进页那一刻的快照; -1 = 聚焦选中 tab)
    val restoreCardIndex = remember { lastFocusedCard }
    // 恢复期间抑制标签的"聚焦即选中": 返回本页瞬间系统会把默认焦点塞给第一个可聚焦元素
    // (第一个 tab 标签), 若不抑制, 其"聚焦即选中"会把选中 tab 改掉, 恢复目标卡随之落进错误的 tab
    var restorePending by remember { mutableStateOf(restoreCardIndex >= 0) }
    LaunchedEffect(Unit) {
        // 初始焦点: 返回本页恢复此前卡片; 新进页把请求登记到选中 tab 锚点.
        // 系统先把焦点塞给首标签时会触发 fallback 事件, Resolver 随即重送到真正选中项.
        if (restoreCardIndex >= 0) {
            gridFocus.focusItem(restoreCardIndex)
            // 送焦有结果 (送达 / 用户接手 / 判空取消) 才放开 tab 的"聚焦即选中" —— 快照事件,
            // 不再空转 120 轮. 无超时是刻意的, 出口见 TvGridFocusState 文件头
            snapshotFlow { gridFocus.switching }.first { !it }
            restorePending = false
            // 落点有结果 (送达 / 用户接手 / 判空取消) 才放开默认 hero: 送达时 hero 已由
            // onFocused 设成正确那张, 这里放开只是为了兜住"恢复失败"的情形
            heroDefaultBlockedByRestore = false
        } else {
            focusSelectedTab()
            heroDefaultBlockedByRestore = false // 新进页没有要恢复的卡, 默认 hero 该照常出
        }
    }

    // 从详情页/播放器返回本页时重新落点.
    //
    // 不能只靠上面那个 LaunchedEffect(Unit): 本页作为主页的一个 tab, 快速返回时整棵子树可能
    // 一直没被销毁 (TV 用 crossfade 过渡), 该效应不会再跑; 而网格项在离开期间被销毁, 焦点随之
    // 悬空 —— 表现为返回后看不到焦点圈, 按下键才落到首卡. 判据与组合是否存活无关, 因此用
    // OnReturnToForeground: 每次重新回到栈顶 (首次进页面除外, 那次由上面的效应处理) 补一次落点.
    //
    // **原先读的是页面 lifecycle 的 RESUMED**, Nav3 里被盖住的条目一直是 RESUMED, 那条路一个
    // 事件都不发了 (2026-08-22 改), 见 LocalPageIsForeground 的文档.
    OnReturnToForeground("collection") {
        val card = lastFocusedCard
        if (card >= 0) gridFocus.focusItem(card) else focusSelectedTab()
    }

    // 焦点卡因收藏状态改变离开本 tab: 等它真的从列表里消失, 再安排落点.
    //
    // 不能在点下拉菜单那一刻就 request: 改收藏要一次网络往返, 那时卡还在, 落点解析第一帧就会
    // 把焦点聚焦回原卡并判定"到位"结束; 等卡真消失时已无人接管, 焦点悬空 —— Compose 会
    // clearFocus 整棵树并做一次初始焦点分配 (见 FocusTargetNode.onReset/onDetach, 源码明确
    // **不**把焦点交给焦点祖先, 那只是注释里的将来打算), 落点是遍历顺序第一个可聚焦元素 =
    // 第一个 tab 标签; 而标签的"聚焦即选中"刻意不认系统塞来的焦点 (见 selectByFocusArmed),
    // 于是高亮停在第一个标签而指示条还留在当前 tab 上.
    //
    // 一次 request 覆盖两种结局, 由 [TvGridFocusState] 与下方的判空取消一起分岔: 本 tab 还有卡 ->
    // 夹到相邻下标 (焦点留在原位置); 整个 tab 空了 -> onEmptyIdle 回到选中标签.
    LaunchedEffect(awaitingRemovalSubjectId) {
        val subjectId = awaitingRemovalSubjectId ?: return@LaunchedEffect
        // 超时兜底: 请求成功但列表迟迟不刷新时也要收尾, 否则隐形锚点一直可聚焦, 焦点就停在
        // 那个不可见节点上 (方向键还能走, 但看不到焦点圈)
        withTimeoutOrNull(TV_COLLECTION_AWAIT_REMOVAL_TIMEOUT_MILLIS) {
            snapshotFlow { items.itemSnapshotList.items.none { it.subjectId == subjectId } }
                .first { it }
        }
        awaitingRemovalSubjectId = null
        gridFocus.focusItem(lastFocusedCard.coerceAtLeast(0))
    }

    // 网格通用返回规则: 不在首卡时按返回先回网格第一张卡 (借统一落点解析:
    // 等滚动/组合完成后由锚点送焦). 已在首卡时不启用, 返回交给上层 (回探索页).
    // derivedStateOf: 焦点下标每移一格都变, 直接读会让整页每格重组, 收窄成布尔
    var gridHasFocus by remember { mutableStateOf(false) }
    // **不能只看 gridHasFocus**: 从详情页/播放器返回本页时它要等分页数据到达 → 滚到目标卡 →
    // 卡片组合 → 聚焦到位才变 true (搜索页实测 300ms~1.9s, 目标卡越靠后越久). 这段窗口里
    // 本处判 false, 返回键就被放行到上层, 用户明明停在网格深处却**一步弹回探索页**
    // (与 issue #2 同一个根因). 三段接力覆盖整个恢复过程:
    //   组合 → 派出落点: restorePending (上方进页恢复的现成标志, 组合那刻就是 true)
    //   派出 → 焦点落位: gridFocus.switching
    //   落位之后:       gridHasFocus
    val backToFirstCard by remember {
        derivedStateOf {
            val gridEngaged = gridHasFocus || gridFocus.switching || restorePending
            gridEngaged && lastFocusedCard > 0
        }
    }
    BackHandler(enabled = !navLocked && backToFirstCard) {
        gridFocus.focusItem(0)
    }
    // **转场窗口内吞掉返回键**, 且必须注册在上面那条之后 (BackHandler 走
    // OnBackPressedDispatcher, 后注册的先拿到). 语义是"这一下不算": 用户在转场里按的返回
    // 既不该被本页当成"回网格首卡", 也不该被主壳当成"切回探索页" —— 到了详情页再按一下就是
    // 正常返回. 理由与取证同 [lockNavigationForTransition].
    BackHandler(enabled = navLocked) { /* 吞掉 */ }

    // 卡片长按弹出的收藏下拉 (与探索页一致); 打开后短暂吞掉长按残余的确认键, 避免误触第一项.
    // remember: 工厂被网格 items 内容 lambda 捕获, 每次新实例都会让所有可见卡片跟着重组
    val collectionMenuFor: (SubjectCollectionInfo) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit = remember {
        { info ->
            { expanded, onDismiss ->
                EditCollectionTypeDropDown(
                    currentType = info.collectionType,
                    expanded = expanded,
                    onDismissRequest = onDismiss,
                    onClick = { action ->
                        // 改成别的状态后本条目会离开当前 tab, 焦点此刻正在它的卡片上 (菜单是长按它
                        // 弹出的). 先把焦点钉到隐形锚点躲开即将到来的销毁 (同跨 tab 导航的做法),
                        // 再登记等待条目消失 —— 落点由上方的等待效应安排. 直接留在卡上等销毁的话
                        // 焦点会悬空并被系统重分配到第一个 tab 标签.
                        //
                        // 这里读 state 而非捕获外层的 selectedType: 本工厂 remember 无 key
                        // (避免每次重组换实例让所有可见卡片跟着重组), 捕获的值会停在首次组合那一刻.
                        if (action.type != COLLECTION_TABS_SORTED[state.selectedTypeIndex]) {
                            awaitingRemovalSubjectId = info.subjectId
                            runCatching { transitAnchor.requestFocus() }
                        }
                        scope.launch {
                            runCatching { setCollectionTypeUseCase(info.subjectId, action.type) }
                                .onFailure {
                                    // 改失败, 条目不会离开列表: 立刻收尾并把焦点送回原卡,
                                    // 否则等待效应要空等到超时, 期间焦点停在不可见锚点上
                                    if (awaitingRemovalSubjectId == info.subjectId) {
                                        awaitingRemovalSubjectId = null
                                        gridFocus.focusItem(lastFocusedCard.coerceAtLeast(0))
                                    }
                                    toaster.showLoadError(LoadError.fromException(it))
                                }
                        }
                    },
                    // 卡片的菜单只有长按一个入口, 恒吞掉那次长按残余的确认键
                    modifier = Modifier.consumeHeldConfirmKey(),
                )
            }
        }
    }

    // 播放键: 短按播聚焦条目的下一集, 长按强制重拉当前 tab 的收藏列表 (默认只在进页/一小时
    // 定时同步时刷新). 挂在页面根上而不是网格上: 焦点在 tab 行时也能刷
    // 动作面板「刷新本页」= 强制重拉当前分类 (播放键长按已改为全局的「打开动作面板」)
    TvPageRefreshHandler { state.refreshSelectedPage() }
    val playKeyModifier = tvPlayKeyShortPress(
        onPlay = {
            val info = lastFocusedCard.takeIf { it >= 0 }
                ?.let { runCatching { items.peek(it) }.getOrNull() }
            if (info != null) {
                navigateToPlay(info)
                true
            } else {
                false
            }
        },
    )

    Box(
        modifier.fillMaxSize()
            // 方向/确认键即取消在途送焦; tvGridKeyNavigation 不再自己上报, 全指这一处
            .tvFocusNavSignal(focus)
            .then(playKeyModifier),
    ) {
        // 背景 backdrop 层 (探索/搜索页同款, 恒用"卡片态"渐变): 观看途中优先下一集剧照,
        // 缺失回退整部官方主图. URL 用 lambda 传入: 聚焦条目状态在组件内部才读取,
        // 遥控器换卡只重组这一小块.
        // 三级回落 + 封面兜底/垫底 (四页同构), 语义见 TvHeroMediaPipelineState
        TvPageBackdropLayer(
            backdropUrl = { heroPipeline.backdropUrl(heroDisplay()?.toHeroMediaSpec()) },
            // 本页在主壳内, 图层正下方是主壳铺的 shellBackgroundColor (见 TvMainScreenLayout)
            fadeColor = AniThemeDefaults.shellBackgroundColor,
            modifier = Modifier.align(Alignment.TopEnd),
            underlayUrl = { heroPipeline.underlayUrl(heroDisplay()?.toHeroMediaSpec()) },
            // 这张图解码完顺手算主题色, 点进详情页第一帧就是动态色 (详情页取的也是这张)
            themeSeedSubjectId = { heroDisplay()?.subjectId },
        )

        Column(
            Modifier.fillMaxSize()
                .padding(start = TV_COLLECTION_START_PAD, top = TV_COLLECTION_TOP_PAD),
        ) {
            // 悬浮分类 Tab (透明底浮于 backdrop 上)
            TvCollectionTabRow(
                selectedType = selectedType,
                counts = { type -> state.collectionCounts?.getCount(type) },
                // 跨 tab 落点解析期间与进页恢复焦点期间抑制 tab 的"聚焦即选中" (兜底: 万一
                // 瞬时焦点飘到某个标签上, 不能让它改写目标 tab 的选择)
                onSelect = { type -> if (!gridFocus.switching && !restorePending) selectType(type) },
                focusScope = focus,
                tabFocusKeys = tabFocusKeys,
                navigationLocked = { selectedTabFocusPending },
                onTabFocusChanged = { index, focused ->
                    if (focused) {
                        focusedTabTvIndex = index
                        if (index == selectedTabTvIndex()) {
                            selectedTabFocusPending = false
                        } else if (selectedTabFocusPending) {
                            // 正等着"选中的标签获焦" (focusSelectedTab 已发过请求), 焦点却落到了
                            // 别的标签 —— 只能是系统兜底/焦点重分配塞来的: 用户的左右键在按下时
                            // 就清掉本标志 (onUserNavigation), 长按期间标签行又被 navigationLocked
                            // 锁着. 把请求再发一遍 (下面 notifyFocusFallbackSettled 只重试**还挂着
                            // 的** scope 请求, 请求已被消耗/短路时就没人管了; 每次重发都由一次真实
                            // 获焦事件驱动, 不自旋). 不修的话高亮停在错的标签, 而指示条/hero 还在
                            // 选中 tab 上 —— 登录返回后偶发的"焦点跑到第一个标签"就是这个形状.
                            collectionLogger.info { "TvCollection: 等选中标签期间焦点落到 $index, 改派回选中标签" }
                            focusSelectedTab()
                        }
                        // 返回页首帧还压在详情页退场层后面时, 网格锚点虽已附着却会拒绝送焦;
                        // 系统随后把焦点塞到首标签, 以这个真实的"页面已可聚焦"事件重试原目标.
                        focus.notifyFocusFallbackSettled()
                    } else if (focusedTabTvIndex == index) {
                        focusedTabTvIndex = -1
                    }
                },
                // 标签间导航也算用户接手: 取消挂起的网格落点解析, 否则它的 onEmptyIdle
                // 会把焦点拉回"选中的标签"(选中态比焦点滞后一帧, 于是像是被拉回上一个标签)
                onUserNavigation = {
                    // **不在这里调 focus.notifyUserNavigation()**: 页面根的 tvFocusNavSignal 是外层
                    // onPreviewKeyEvent, 每次按键必先于本行跑过一遍, 再报一次只会让一次按键推进
                    // 两次代数 —— 网格送焦的取消判据就得靠"在协程里重取基线"去躲那个中间态, 而
                    // 重取会吞掉真正的用户取消 (见 TvGridFocusState.SendFocusEffect).
                    selectedTabFocusPending = false
                },
                onNavigateDown = {
                    // 主走统一落点解析聚焦当前视口首行行首 (到位确认 + 重试; 同搜索页:
                    // 直连首卡 requestFocus 偶发被焦点系统静默拒绝时 runCatching 照样报成功,
                    // 下键被吞且不重试); 网格空时退到错误横幅 (登录/重试按钮)
                    val firstVisibleRow = state.getGridState(state.selectedTypeIndex)
                        .layoutInfo.visibleItemsInfo.firstOrNull()?.row
                    if (firstVisibleRow != null) {
                        // **换过 tab 就不能拿视口首行当落点**: 切 tab 时页面把 lastFocusedCard 重置成
                        // -1 (忘掉上次那张卡), 但该 tab 的 LazyGridState **滚动位置还是上次留下的** ——
                        // 两者不一致, 于是"视口首行"是上次停的那一行, 焦点落到列表中间.
                        // 真机复现 (2026-08-22): 一路右滑穿过所有 tab, 再从标签行左滑回第一个 tab,
                        // 按下键落到中间某张卡 (日志里 focusRowEdge 的 row 出现过 6/5/4/1).
                        // 目标定成第 0 行, SendFocusEffect 会把网格一并滚回顶部, 焦点与滚动重新一致;
                        // 落点也不再受加载中 visibleItemsInfo 抖动的影响.
                        // 没换 tab 的情形 (上到标签行再下来) 保持原样: 回到刚才看的那一行.
                        val targetRow = if (lastFocusedCard >= 0) firstVisibleRow else 0
                        gridFocus.focusRowEdge(targetRow, direction = 1)
                        true
                    } else {
                        runCatching { errorCardFocusRequester.requestFocus() }.getOrDefault(false)
                    }
                },
            )

            // Hero 信息块 (固定高度, 切换聚焦条目时网格不跳动). 聚焦条目状态在子组件内部
            // 才读取, 遥控器换卡只重组信息块自身, 不连带整页作用域
            TvCollectionHeroBlock(
                heroInfoProvider = heroDisplay,
                episodeStillCache = episodeStillCache,
                summaryFallbackCache = summaryFallbackCache,
                remainingMinutesOf = { episodeId ->
                    playHistories.firstOrNull { it.episodeId == episodeId }?.let { history ->
                        val duration = history.durationMillis
                        if (duration != null && duration > 0 && history.positionMillis > 0) {
                            (((duration - history.positionMillis).coerceAtLeast(0L) + 59_999) / 60_000)
                                .toInt().coerceAtLeast(1)
                        } else null
                    }
                },
                // end 留白与探索页 hero 块一致, 否则 fillMaxWidth(比例) 的基数比其他页宽
                modifier = Modifier.fillMaxWidth()
                    .padding(top = TV_COLLECTION_TABS_TO_HERO_GAP, end = TV_PAGE_END_PAD)
                    .height(TV_COLLECTION_HERO_INFO_HEIGHT),
            )

            // 过渡期的隐形焦点驻留点 (跨 tab 换网格 / 改收藏状态让条目离开本 tab 时焦点先躲到这里,
            // 机制与摆放位置的讲究见 [TvFocusTransitAnchor]; 与时间表换天共用同一实现).
            // extraCanFocus: 改收藏状态那条路径上没有挂起的落点请求 (要等条目真的从列表消失才发),
            // 锚点得靠这个条件保持可聚焦
            TvFocusTransitAnchor(
                requester = transitAnchor,
                switching = { gridFocus.switching },
                extraCanFocus = { awaitingRemovalSubjectId != null },
                // 在途请求被取消 / 等条目消失结束: 焦点还在锚点上而锚点即将不可聚焦, 补落点到选中标签
                onStranded = { focusSelectedTab() },
            )

            // 竖版海报网格
            if (items.loadState.hasError) {
                LoadErrorCard(
                    LoadError.fromCombinedLoadStates(items.loadState),
                    onRetry = { items.refresh() },
                    Modifier.padding(top = TV_COLLECTION_HERO_TO_GRID_GAP, end = TV_PAGE_END_PAD)
                        // 请求器挂在卡片容器上, requestFocus 委托给子树第一个焦点目标 (登录/重试按钮);
                        // 按上键显式送回选中 tab (跨层级的方向搜索不可靠)
                        .focusRequester(errorCardFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                focusSelectedTab()
                            } else {
                                false
                            }
                        },
                )
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth()
                    .padding(top = TV_COLLECTION_HERO_TO_GRID_GAP)
                    .onFocusChanged { gridHasFocus = it.hasFocus },
            ) {
                // 复刻 GridCells.Adaptive 的列数算法 (整数 px 运算), 供跨 tab 导航的行列换算
                val density = LocalDensity.current
                val gridColumns = with(density) {
                    val available = (this@BoxWithConstraints.maxWidth - TV_PAGE_END_PAD).roundToPx()
                    val spacing = TV_PAGE_CARD_SPACING.roundToPx()
                    maxOf(1, (available + spacing) / (TV_PAGE_CARD_WIDTH.roundToPx() + spacing))
                }
                // 底部补白 = 视口高 - 一行卡高: 让最后一行也能吸到网格顶部
                // (内容不足一屏时 animateScrollToItem 滚不动, 接近底部的行会失去吸顶)
                val gridBottomPad = run {
                    val available = this@BoxWithConstraints.maxWidth - TV_PAGE_END_PAD
                    val cardWidth =
                        (available - TV_PAGE_CARD_SPACING * (gridColumns - 1)) / gridColumns
                    val cardHeight = cardWidth / TV_PORTRAIT_CARD_COVER_RATIO
                    (this@BoxWithConstraints.maxHeight - cardHeight).coerceAtLeast(24.dp)
                }
                // 跨 tab 网格过渡: 开了完整视觉效果 (设置项, 默认关) 才按 TV 顺序方向整体水平
                // 滑动, 滑出边界被裁掉; 否则降级为渐隐渐现 (静止渐隐比运动滑动更能掩盖低端机
                // 掉帧 —— 实测这段 560ms 双网格滑动是换 tab 那记 jank 的主要来源). 过渡期间
                // 新旧两个网格同时组合, 各自读自己 tab 的分页数据 (有缓存), 滚动位置按 tab 保留.
                val fullTransitions = LocalThemeSettings.current.tvFullVisualEffects
                AnimatedContent(
                    targetState = state.selectedTypeIndex,
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    transitionSpec = {
                        if (fullTransitions) {
                            val forward = TV_COLLECTION_TABS.indexOf(COLLECTION_TABS_SORTED[targetState]) >
                                    TV_COLLECTION_TABS.indexOf(COLLECTION_TABS_SORTED[initialState])
                            slideInHorizontally(tween(TV_COLLECTION_TAB_SLIDE_MILLIS)) { width ->
                                if (forward) width else -width
                            } togetherWith slideOutHorizontally(tween(TV_COLLECTION_TAB_SLIDE_MILLIS)) { width ->
                                if (forward) -width else width
                            }
                        } else {
                            fadeIn(tween(TV_COLLECTION_TAB_FADE_MILLIS)) togetherWith
                                    fadeOut(tween(TV_COLLECTION_TAB_FADE_MILLIS))
                        }
                    },
                    label = "collectionTabGrid",
                ) { tabIndex ->
                    val tabItems = remember(tabIndex) {
                        state.getCollectionLazyPagingItems(tabIndex)
                    }.collectWithLifecycle()
                    val gridState = remember(tabIndex) { state.getGridState(tabIndex) }
                    val isActiveTab = tabIndex == state.selectedTypeIndex
                    // 统一落点解析 (跨 tab / 同列导航 / 回首卡 / 进页恢复只是目标参数不同,
                    // 机制见 [TvGridFocusState]): 整个 tab 一张卡都没有 (且不在
                    // 加载) 则聚焦 tab 标签. 只在选中 tab 的网格实例上运行; 跨 tab 时目标先于
                    // selectType 设置, 新 tab 网格组合后由本效应接手解析 (滑动过渡中即聚焦,
                    // 焦点圈随网格滑入).
                    if (isActiveTab) {
                        gridFocus.SendFocusEffect(gridState) { tabItems.itemCount }
                        // 替代旧 runResolveLoop 的 onEmptyIdle: 数据到了但这个 tab 是空的, 目标卡
                        // 永远不会出现, 得主动取消在途请求 (否则 SendFocusEffect 一直等 itemCount > 0).
                        // 取消后隐形锚点上的焦点被判 stranded, 由它的 onStranded 落回选中标签
                        val countSaysEmpty = state.collectionCounts
                            ?.getCount(COLLECTION_TABS_SORTED[tabIndex]) == 0
                        LaunchedEffect(
                            tabItems.itemCount,
                            tabItems.isLoadingFirstPageOrRefreshing,
                            countSaysEmpty,
                        ) {
                            if (tabItems.itemCount == 0 &&
                                (countSaysEmpty || !tabItems.isLoadingFirstPageOrRefreshing) &&
                                gridFocus.switching
                            ) {
                                gridFocus.cancel()
                            }
                        }
                    }
                    // 分页 generation 替换时的焦点抢救: 这套 pager 是 Room + RemoteMediator,
                    // 每次写库 (REFRESH 先清表回填 / append) 都换 generation, 重载窗口外的条目
                    // 退回 placeholder —— 聚焦卡的 key 从 subjectId 换成 placeholder key, 节点
                    // 被销毁, 焦点被系统重分配 (实测闪到顶部标签行, 其"聚焦即选中"还会误切 tab).
                    // 4K 视口 60+ 卡远超窗口, REFRESH 必现.
                    // 恢复三步: 钉锚点 (发出的落点请求同时抑制 tab 聚焦即选中) → 等聚焦下标回填
                    // 成真数据 (placeholder 卡虽可聚焦, 但回填时 key 替换又会销毁一次, 不能停在
                    // 它上面) → 送回原下标.
                    if (isActiveTab) LaunchedEffect(tabItems) {
                        snapshotFlow {
                            val focused = lastFocusedCard
                            val count = tabItems.itemCount
                            gridRegionFocused && focused >= 0 &&
                                    (count <= focused || tabItems.itemSnapshotList[focused] == null)
                            // collectLatest: 新一轮塌陷要取消上一轮还在等的那 8 秒, 否则过期的恢复
                            // 会在新一轮之后再补一发
                        }.collectLatest { collapsed ->
                            if (!collapsed || gridFocus.switching) return@collectLatest
                            // 目标在这里定死, 不在 8 秒之后重读 lastFocusedCard: 重读既可能拿到
                            // -1 (负下标那条路), 也会在用户中途换了卡时把焦点送到别处
                            val target = lastFocusedCard
                            if (target < 0) return@collectLatest
                            runCatching { transitAnchor.requestFocus() }
                            gridFocus.focusItem(target) // 立即挂起请求, 抑制 tab 聚焦即选中
                            val navAtStart = focus.userNavGeneration
                            val refilled = withTimeoutOrNull(8_000) {
                                snapshotFlow {
                                    target in 0 until tabItems.itemCount &&
                                            tabItems.itemSnapshotList[target] != null
                                }.first { it }
                            }
                            // **第二发要有前提, 不能无条件发** (原先是无条件的).
                            //
                            // 它的用意是"首个请求可能已在 placeholder 卡上假到位过, 数据回填后补一发".
                            // 但这中间最长有 8 秒, 用户按一下方向键就会取消第一发的落点并把焦点移走 ——
                            // 而这一发照旧执行, 等于**从用户脚下把焦点抢回网格**, 正是这套框架要根除的
                            // 那类问题. withTimeoutOrNull 的结果原先也被丢弃, 超时 (数据始终没回来)
                            // 同样会重发, 可能又落回 placeholder.
                            //
                            // 所以两个前提都要成立: 数据真的回填了 (refilled != null), 且这段时间里
                            // 用户没动过手 (交互代数未变). 换 tab 不用单独判 —— isActiveTab 一变,
                            // 这个 LaunchedEffect 整个离开组合, 协程随之取消.
                            if (refilled == null || focus.userNavGeneration != navAtStart) {
                                return@collectLatest
                            }
                            gridFocus.focusItem(target)
                        }
                    }
                    // 聚焦行吸顶 (同探索页): 关闭默认"刚好露出"式的自动滚动, 聚焦行直接滚到
                    // 网格顶部, 上方的行完全滚出视口
                    val noBringIntoView = remember {
                        object : BringIntoViewSpec {
                            override fun calculateScrollDistance(
                                offset: Float,
                                size: Float,
                                containerSize: Float,
                            ): Float = 0f
                        }
                    }
                    if (isActiveTab) {
                        LaunchedEffect(gridState) {
                            // collectLatest + TvScrollAnimator: 连发按键取消进行中的滚动并继承
                            // 速度, 列表连续流动 (原 collect 要等上一格动画跑完才响应下一个目标)
                            val scrollAnimator = TvScrollAnimator()
                            snapshotFlow { lastFocusedCard }.collectLatest { focused ->
                                if (focused >= 0) {
                                    runCatching {
                                        scrollAnimator.animateScrollToItem(gridState, (focused / gridColumns) * gridColumns)
                                    }
                                }
                            }
                        }
                    }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides noBringIntoView) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(TV_PAGE_CARD_WIDTH),
                            modifier = Modifier
                                .fillMaxSize()
                                // 长按方向键的移动频率上限 (同探索页卡片区/时间表日期行). 必须挂在
                                // tvGridKeyNavigation 之前: 两者都是 onPreviewKeyEvent, 靠前的先收到,
                                // 于是导航逻辑只看得见放行的那几发.
                                // 系统连发约 20 次/秒, 而每换一格就要重启一次 hero 背景/文字的换挡
                                .tvFocusMoveRateLimit()
                                // 同列上下导航 + 播放键直达 (共享实现, 理由见 [tvGridKeyNavigation]);
                                // extraKeys 处理跨 tab 行对齐导航: 行末右键 -> 右侧 tab 同一行最左卡,
                                // 行首左键对称 (第一个 tab 行首不消费, 交给焦点系统 -> 侧边栏).
                                // 落点无记忆: 回程按当前所在行对应端落点, 不回到来时的卡.
                                .tvGridKeyNavigation(
                                    gridFocus,
                                    focusedIndex = { lastFocusedCard },
                                    itemCount = { tabItems.itemCount },
                                    columns = { gridColumns },
                                    // 顶行上键回选中 tab (主按钮已移除)
                                    onTopRowUp = {
                                        focusSelectedTab()
                                    },
                                    enabled = { isActiveTab },
                                    extraKeys = { event, focused, cols, count ->
                                        val tvIndex = TV_COLLECTION_TABS.indexOf(selectedType)
                                        when (event.key) {
                                            Key.DirectionRight -> {
                                                val rowEnd = focused % cols == cols - 1 ||
                                                        focused == count - 1
                                                if (rowEnd && tvIndex in 0..<TV_COLLECTION_TABS.size - 1) {
                                                    gridFocus.focusRowEdge(focused / cols, direction = 1)
                                                    // 切 tab 前把焦点钉到隐形锚点: 原卡片随分页替换销毁后焦点
                                                    // 悬空会被系统重分配 (可能落到第一个 tab 标签, 其"聚焦即
                                                    // 选中"会把选择拽回去); 锚点不可见, 不产生聚焦样式闪烁
                                                    runCatching { transitAnchor.requestFocus() }
                                                    selectType(TV_COLLECTION_TABS[tvIndex + 1])
                                                    true
                                                } else if (rowEnd) {
                                                    // 末 tab 的行末按右: 消费掉. 不消费会落到默认方向搜索,
                                                    // 右侧无目标时它会退出/重进内容焦点组, 被外壳的
                                                    // onEnter 送回首个可聚焦元素 (第一个 tab 标签)
                                                    true
                                                } else {
                                                    // 行内还有卡: 交给默认方向搜索横向移动
                                                    false
                                                }
                                            }

                                            Key.DirectionLeft -> {
                                                if (focused % cols == 0 && tvIndex > 0) {
                                                    gridFocus.focusRowEdge(focused / cols, direction = -1)
                                                    // 同右键分支: 防止焦点悬空被系统重分配
                                                    runCatching { transitAnchor.requestFocus() }
                                                    selectType(TV_COLLECTION_TABS[tvIndex - 1])
                                                    true
                                                } else {
                                                    false
                                                }
                                            }

                                            else -> false
                                        }
                                    },
                                ),
                            state = gridState,
                            horizontalArrangement = Arrangement.spacedBy(TV_PAGE_CARD_SPACING),
                            verticalArrangement = Arrangement.spacedBy(TV_PAGE_CARD_SPACING),
                            contentPadding = PaddingValues(end = TV_PAGE_END_PAD, bottom = gridBottomPad),
                        ) {
                            items(
                                tabItems.itemCount,
                                key = tabItems.itemKey { "TvCollectionPage-" + it.subjectId },
                            ) { index ->
                                val info = tabItems[index]
                                TvPortraitCard(
                                    imageUrl = info?.subjectInfo?.imageLarge,
                                    contentDescription = info?.subjectInfo?.displayName,
                                    onClick = { info?.let(navigateToSubject) },
                                    onFocused = {
                                        info?.let {
                                            heroItem = it
                                            // 邻居按网格几何算 (中间卡四方向), 见 tvGridNeighborsOf
                                            heroNeighbors = it.subjectId to tvGridNeighborsOf(
                                                index, gridColumns,
                                            ) { i ->
                                                // 剧照偏好按**每个邻居自己**的观看状态定: 网格里
                                                // "在看"与其余条目是混着的, 见 TvHeroNeighbor
                                                if (i in 0 until tabItems.itemCount) {
                                                    tabItems.peek(i)?.let { n ->
                                                        TvHeroNeighbor(
                                                            n.subjectId,
                                                            n.stillEpisodeIdOrNull() != null,
                                                        )
                                                    }
                                                } else null
                                            }
                                        }
                                        lastFocusedCard = index
                                    },
                                    // 焦点在网格与否 (塌缩恢复的判据): 获焦 true / 正常失焦 false;
                                    // 节点被分页替换销毁时**不会**回调, true 得以保留
                                    onFocusChangedExtra = { gridRegionFocused = it },
                                    // 只有选中 tab 的网格参与送焦: 非选中 tab 的实例仍在组合里
                                    // (滑动过渡), 让它们也挂锚点会与选中 tab 抢同一个 key
                                    modifier = Modifier
                                        .ifThen(isActiveTab) {
                                            tvGridFocusItem(
                                                gridFocus,
                                                index = index,
                                                itemCount = tabItems.itemCount,
                                            )
                                        },
                                    menu = info?.let { collectionMenuFor(it) },
                                    // 下一集播放进度 (语义同探索页继续观看卡): 看到一半按播放位置;
                                    // 追平连载 (Watched) 满条; 其余 (想看/看完/未开始) 不显示
                                    progress = info?.progressInfo?.let { progressInfo ->
                                        when (progressInfo.continueWatchingStatus) {
                                            is ContinueWatchingStatus.Watched -> 1f
                                            is ContinueWatchingStatus.Continue -> progressInfo.nextEpisodeIdToPlay
                                                ?.let { nextId -> playHistories.firstOrNull { it.episodeId == nextId } }
                                                ?.let { history ->
                                                    val duration = history.durationMillis
                                                    if (duration != null && duration > 0) {
                                                        (history.positionMillis.toFloat() / duration).coerceIn(0f, 1f)
                                                    } else null
                                                }

                                            else -> null
                                        }
                                    },
                                )
                            }
                        }
                    }
                    // 空分类提示: 在内容区 (侧边栏右侧) 居中; 网格区偏页面下半,
                    // 上移一段让它视觉上接近整页居中
                    if (tabItems.itemCount == 0 && !tabItems.isLoadingFirstPageOrRefreshing && !tabItems.loadState.hasError) {
                        Box(
                            Modifier.fillMaxSize().offset(y = -TV_COLLECTION_EMPTY_HINT_RAISE),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(Lang.collection_tv_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }

        // 底缘弱渐变遮罩 (页面背景色, smoothstep 采样无折点): 轻压被视口截断的下一行卡片,
        // 保证右下角提示在滚动的海报上仍可读. 只绘制, 不参与点击/焦点.
        run {
            val bg = MaterialTheme.colorScheme.background
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(TV_PAGE_BOTTOM_SCRIM_HEIGHT)
                    .background(
                        Brush.verticalGradient(
                            *Array(11) { i ->
                                val f = i / 10f
                                val ease = f * f * (3f - 2f * f)
                                f to bg.copy(alpha = ease * TV_PAGE_BOTTOM_SCRIM_MAX_ALPHA)
                            },
                        ),
                    ),
            )
        }

        // 右下角遥控键提示 (参考 Prime 的同位置提示): 次要色低调常显
        Row(
            Modifier.align(Alignment.BottomEnd)
                .padding(end = TV_PAGE_END_PAD, bottom = TV_PAGE_HINT_BOTTOM_PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                Modifier.size(TV_PAGE_HINT_ICON_SIZE),
                tint = tvHeroSecondaryContentColor(),
            )
            Text(
                stringResource(Lang.tv_card_remote_hint),
                color = tvHeroSecondaryContentColor(),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Hero 背景想用"下一集剧照"时返回该集 id (观看途中/追平连载); 其余状态用整部 backdrop. */
private fun SubjectCollectionInfo.stillEpisodeIdOrNull(): Int? {
    val status = progressInfo.continueWatchingStatus
    return if (status is ContinueWatchingStatus.Continue || status is ContinueWatchingStatus.Watched) {
        progressInfo.nextEpisodeIdToPlay
    } else null
}

/** 交给共享流水线/展示层的最小描述, 见 [TvHeroMediaSpec]. */
private fun SubjectCollectionInfo.toHeroMediaSpec(neighbors: TvHeroNeighbors = TvHeroNeighbors()) =
    TvHeroMediaSpec(
        subjectId = subjectId,
        preferNextEpisodeStill = stillEpisodeIdOrNull() != null,
        coverUrl = subjectInfo.imageLarge,
        neighbors = neighbors,
    )

/**
 * Hero 信息块 (含换条目渐隐渐现). [heroInfoProvider] 用 lambda 传入: 聚焦条目状态在
 * 本组件内部才读取, 遥控器每移一格只重组这一块, 不连带整页作用域. 退场内容读退场
 * 条目自己的数据 (contentKey=条目).
 */
@Composable
private fun TvCollectionHeroBlock(
    heroInfoProvider: () -> SubjectCollectionInfo?,
    episodeStillCache: Map<Int, TvNextEpisodeMedia>,
    summaryFallbackCache: Map<Int, String>,
    remainingMinutesOf: (Int) -> Int?,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = heroInfoProvider(),
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(TV_HERO_TEXT_FADE_MILLIS)) togetherWith
                    fadeOut(tween(TV_HERO_TEXT_FADE_MILLIS))
        },
        contentKey = { it?.subjectId },
        label = "collectionHeroInfo",
    ) { hero ->
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hero != null) {
                // 两张表都是进程级共享的 (TvHeroMediaCache), 邻居预取会为用户还没看到的条目
                // 写入, 而 SnapshotStateMap 没有按键订阅粒度 —— 直接读的话每次邻居写入都重组
                // 这个文字块. derived 之后写入只重算这两个值, 没变就不往下传播 (同一份顾虑见
                // TvHeroMediaCache.subjectInfos 处的说明)
                val nextEpisodeOverview by remember(hero) {
                    derivedStateOf {
                        hero.stillEpisodeIdOrNull()
                            ?.let { episodeStillCache[hero.subjectId]?.overview }
                            ?.takeIf { it.isNotBlank() }
                    }
                }
                val summaryFallback by remember(hero) {
                    derivedStateOf { summaryFallbackCache[hero.subjectId] }
                }
                TvCollectionHeroInfo(
                    info = hero,
                    nextEpisodeOverview = nextEpisodeOverview,
                    summaryFallback = summaryFallback,
                    remainingMinutesOf = remainingMinutesOf,
                )
            }
        }
    }
}

/**
 * Hero 信息块内容: 标题 / 评分 + 连载信息 + 开播年月 / 个人观看状态 (高亮) / 简介.
 * 结构与探索页 hero 一致, 个人状态行改用主题色高亮 (追番页的核心信息).
 */
@Composable
private fun ColumnScope.TvCollectionHeroInfo(
    info: SubjectCollectionInfo,
    nextEpisodeOverview: String?,
    summaryFallback: String?,
    remainingMinutesOf: (episodeId: Int) -> Int?,
) {
    Text(
        info.subjectInfo.displayName,
        Modifier.fillMaxWidth(TV_HERO_TITLE_WIDTH_FRACTION),
        color = tvHeroContentColor(),
        style = MaterialTheme.typography.headlineLarge,
        // 超长换行, 至多两行 (与探索页/搜索页统一); 简介 weight 自动让出空间
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val score = info.subjectInfo.ratingInfo.score
        if ((score.toFloatOrNull() ?: 0f) > 0f) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "$score/10",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiringLabel(
                remember(info) {
                    AiringLabelState(stateOf(info.airingInfo), stateOf(info.progressInfo))
                },
                style = MaterialTheme.typography.labelLarge,
                progressColor = tvHeroSecondaryContentColor(),
            )
            val airDate = info.subjectInfo.airDate
            if (airDate.isValid) {
                Text(
                    "    " + stringResource(Lang.exploration_tv_air_date, airDate.year, airDate.month),
                    color = tvHeroSecondaryContentColor(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
    // 个人观看状态行 (主题色高亮). 三态语义同探索页 (见 SubjectProgressInfo.compute):
    //  - 已看完最新一集/全部 (Watched/Done): "第 8 集 · 集名 · 已看完[最新一集 · 周几更新]"
    //  - 看到一半 (有播放记录): "第 4 集 · 集名 · 剩余 23 分钟"
    //  - 看完上一集且有新集 / 还没开始: "下一集: 第 4 集 · 集名".
    // 集号与尾段为固定段永不截断; 集名居中段, 超长跑马灯滚动. 未开播不显示本行.
    val status = info.progressInfo.continueWatchingStatus
    val nextEp = info.progressInfo.nextEpisodeIdToPlay?.let { id ->
        info.episodes.firstOrNull { it.episodeId == id }
    }
    if (nextEp != null && status !is ContinueWatchingStatus.NotOnAir) {
        val epLabel = stringResource(
            Lang.playback_history_episode_label,
            nextEp.episodeInfo.sort.toString(),
        )
        val epName = nextEp.episodeInfo.nameCn.ifBlank { nextEp.episodeInfo.name }
        val caughtUp = status is ContinueWatchingStatus.Watched || status is ContinueWatchingStatus.Done
        val remainingMinutes = if (caughtUp) null else remainingMinutesOf(nextEp.episodeId)
        Row(
            Modifier.fillMaxWidth(TV_HERO_SUMMARY_WIDTH_FRACTION),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val epInfoColor = MaterialTheme.colorScheme.primary
            val epInfoStyle = MaterialTheme.typography.labelLarge
            // 主按钮已移除 (处于焦点动线死角), 其动作语义并入本行头部: 继续观看/开始观看 + 集号;
            // 播放动作由遥控器播放键承担 (见网格键处理), 右下角有常显提示
            val head = when {
                caughtUp -> epLabel
                status is ContinueWatchingStatus.Continue ->
                    stringResource(Lang.subject_progress_continue_watching, epLabel)
                status is ContinueWatchingStatus.Start ->
                    stringResource(Lang.subject_progress_start_watching) + " · " + epLabel
                else -> stringResource(Lang.exploration_tv_next_episode, epLabel)
            }
            Text(
                head,
                color = epInfoColor,
                style = epInfoStyle,
                maxLines = 1,
            )
            if (epName.isNotBlank()) {
                Text(
                    " · $epName",
                    Modifier.weight(1f, fill = false)
                        .basicMarquee(iterations = tvHeroMarqueeIterations()),
                    color = epInfoColor,
                    style = epInfoStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
            if (caughtUp) {
                val watchedStatus = status as? ContinueWatchingStatus.Watched
                val updatesOn = watchedStatus?.nextEpisodeAirDate?.toLocalDateOrNull()?.let { date ->
                    stringResource(Lang.subject_progress_updates_on, WeekFormatter.System.format(date))
                }
                Text(
                    " · " + (
                            if (watchedStatus != null) {
                                stringResource(Lang.exploration_tv_watched_latest)
                            } else {
                                stringResource(Lang.exploration_tv_all_caught_up)
                            }
                            ) + (updatesOn?.let { " · $it" } ?: ""),
                    color = epInfoColor,
                    style = epInfoStyle,
                    maxLines = 1,
                )
            } else if (remainingMinutes != null) {
                Text(
                    " · " + stringResource(Lang.exploration_tv_minutes_left, remainingMinutes),
                    color = epInfoColor,
                    style = epInfoStyle,
                    maxLines = 1,
                )
            }
        }
    }
    // 简介: 观看途中优先展示下一集的 TMDB 单集简介 (回忆剧情起点), 缺失回退整部简介 + bgm.tv 兜底
    Text(
        nextEpisodeOverview
            ?: info.subjectInfo.summary.trim().ifBlank { summaryFallback.orEmpty() },
        Modifier.weight(1f).fillMaxWidth(TV_HERO_SUMMARY_WIDTH_FRACTION),
        color = tvHeroContentColor(),
        style = MaterialTheme.typography.bodyMedium,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 悬浮分类 Tab 行: 透明底, 未选中降透明度, 选中加粗 + 底部平滑滑动的主题色指示条; 聚焦即切换.
 * 数字统计以小号淡色跟在标签后. 按下键把焦点送入下方内容 (主按钮/网格).
 */
@Composable
private fun TvCollectionTabRow(
    selectedType: UnifiedCollectionType,
    counts: (UnifiedCollectionType) -> Int?,
    onSelect: (UnifiedCollectionType) -> Unit,
    focusScope: TvFocusScope,
    tabFocusKeys: List<TvFocusKey>,
    navigationLocked: () -> Boolean,
    /** 某个标签获得/失去焦点 (下标按本行显示顺序); 进页落点循环靠它判断"选中的标签到位了没". */
    onTabFocusChanged: (index: Int, focused: Boolean) -> Unit,
    onUserNavigation: () -> Unit,
    onNavigateDown: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 各 tab 在行内的 (x 偏移, 宽度), 驱动下方滑动指示条
    val tabBounds = remember {
        mutableStateListOf(*Array(TV_COLLECTION_TABS.size) { 0.dp to 0.dp })
    }
    // 焦点下标记账 / "聚焦即选中"封印 / 左右键显式移动 / 连发守卫都在共享原语里 (见 TvFocusRail.kt).
    // 标签恒在屏且必然可聚焦, 所以送焦直接 requestFocus, 不用走 scope 请求 + 悬挂.
    val rail = rememberTvFocusRail(
        scope = focusScope,
        keyAt = { index -> tabFocusKeys[index] },
        onMove = { index -> runCatching { focusScope.requesterOf(tabFocusKeys[index]).requestFocus() } },
    )
    Column(modifier) {
        Row(
            Modifier.tvFocusRailKeys(
                state = rail,
                itemCount = { TV_COLLECTION_TABS.size },
                onUserNavigation = onUserNavigation,
                onNavigateDown = onNavigateDown,
                // 第一个标签按左要放行, 靠焦点系统进侧边栏 —— 本页唯一的左出口
                consumeLeftEdge = false,
                // 从空网格回落标签的那一小段窗口只吞长按残余连发. 新的一次独立按键仍可取消
                // 程序化落点并正常导航, 避免极端情况下目标始终拒焦时把标签行永久锁住.
                preSwallow = { event -> navigationLocked() && event.isAutoRepeat == true },
            ),
            horizontalArrangement = Arrangement.spacedBy(TV_COLLECTION_TAB_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TV_COLLECTION_TABS.forEachIndexed { index, type ->
                val interactionSource = remember { MutableInteractionSource() }
                val focused by interactionSource.collectIsFocusedAsState()
                val selected = type == selectedType
                Row(
                    Modifier
                        .onGloballyPositioned { coords ->
                            tabBounds[index] = with(density) {
                                coords.positionInParent().x.toDp() to coords.size.width.toDp()
                            }
                        }
                        // 无条件挂: 链上元素个数恒定, 选中态变化不会重建其后的焦点节点
                        .tvFocusRailItem(
                            state = rail,
                            index = index,
                            onFocusChanged = { focused -> onTabFocusChanged(index, focused) },
                            onSelectByFocus = { onSelect(type) },
                        )
                        .clickable(interactionSource, indication = null) { onSelect(type) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 聚焦 (即选中) 时主题色示焦; 未选中降透明度
                    val labelColor = when {
                        focused -> MaterialTheme.colorScheme.primary
                        selected -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = TV_COLLECTION_TAB_UNSELECTED_ALPHA)
                    }
                    Text(
                        type.displayTextTv(),
                        color = labelColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                    counts(type)?.let { count ->
                        Text(
                            count.toString(),
                            color = labelColor.copy(alpha = labelColor.alpha * 0.7f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // 平滑滑动的选中指示条
        val (targetX, targetWidth) = tabBounds[TV_COLLECTION_TABS.indexOf(selectedType).coerceAtLeast(0)]
        // **量出来之前不画**: tabBounds 初值是 (0,0), 由 onGloballyPositioned 事后填。若那一帧就
        // 组合出指示条, animateDpAsState 会把 0 当成初值, 等真实位置到达再动画 —— 于是返回本页时
        // 肉眼可见竖线从最左侧滑/跳到选中标签 (2026-08-23 实测)。等真实位置到了再首次组合,
        // animateDpAsState 直接以它为初值落位; 之后切 tab 仍照常动画 (组件一直在组合里)。
        if (targetWidth > 0.dp) {
            TvCollectionTabIndicator(targetX, targetWidth)
        }
    }
}

/**
 * tab 行的选中指示条, 单独成组件: 动画值的组合期读收在这里 —— 切 tab 的几百毫秒里
 * 每帧重组的只有这一个 Box, 不殃及整条 tab 行 (5 个 tab 的文字/计数); X 用 offset
 * 的布局期 lambda 读, 滑动过程连本组件的重组都省掉 (宽度动画仍会重组, 半径 = 1 Box).
 */
@Composable
private fun TvCollectionTabIndicator(
    targetX: Dp,
    targetWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val indicatorX by animateDpAsState(targetX, label = "tabIndicatorX")
    val indicatorWidth by animateDpAsState(targetWidth, label = "tabIndicatorWidth")
    Box(
        modifier
            .padding(top = 4.dp)
            .offset { IntOffset(indicatorX.roundToPx(), 0) }
            .width(indicatorWidth)
            .height(TV_COLLECTION_TAB_INDICATOR_HEIGHT)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun UnifiedCollectionType.displayTextTv(): String {
    return when (this) {
        UnifiedCollectionType.WISH -> stringResource(Lang.subject_collection_wish)
        UnifiedCollectionType.DOING -> stringResource(Lang.subject_collection_doing)
        UnifiedCollectionType.DONE -> stringResource(Lang.subject_collection_done)
        UnifiedCollectionType.ON_HOLD -> stringResource(Lang.subject_collection_on_hold)
        UnifiedCollectionType.DROPPED -> stringResource(Lang.subject_collection_dropped)
        UnifiedCollectionType.NOT_COLLECTED -> stringResource(Lang.subject_collection_uncollected)
    }
}

/**
 * TV 追番页的分类 tab 顺序: 想看 在看 搁置 看过 抛弃. 仅影响本页展示与左右导航次序;
 * [UserCollectionsState] 内部仍按 [COLLECTION_TABS_SORTED] 的下标存取, 使用处经类型换算.
 */
private val TV_COLLECTION_TABS = listOf(
    UnifiedCollectionType.WISH,
    UnifiedCollectionType.DOING,
    UnifiedCollectionType.ON_HOLD,
    UnifiedCollectionType.DONE,
    UnifiedCollectionType.DROPPED,
)

/** 每个标签常驻一个事件驱动焦点锚点; 下标按 [TV_COLLECTION_TABS] 的显示顺序. */
private data class CollectionTabFocusKey(val index: Int) : TvFocusKey

/**
 * 重新进入本页时等收藏计数到达的上限 (毫秒). 超时就按当前 (可能为空的) 计数定分类,
 * 不让"等计数"把进页焦点拖在半空.
 */
private const val TV_COLLECTION_COUNTS_WAIT_MILLIS = 3000L

/**
 * 等"改了收藏状态的条目"从当前 tab 列表消失的上限 (毫秒). 需覆盖一次网络往返 + 分页刷新;
 * 超时只是收尾兜底 (把焦点从隐形锚点送走), 正常路径远早于此.
 */
private const val TV_COLLECTION_AWAIT_REMOVAL_TIMEOUT_MILLIS = 5000L

/** 跨 tab 网格滑动过渡时长 (完整动画档). */
private const val TV_COLLECTION_TAB_SLIDE_MILLIS = 560

/** 跨 tab 网格渐隐过渡时长 (降级档). */
private const val TV_COLLECTION_TAB_FADE_MILLIS = 500

/** 空分类提示相对网格区中心的上移量 (网格区偏页面下半, 上移后视觉上接近整页居中). */
private val TV_COLLECTION_EMPTY_HINT_RAISE = 200.dp

/** 内容左侧留白 (外层主壳已让开侧边栏 48dp, 总左缘 = 48 + 此值, 与探索页一致). */
private val TV_COLLECTION_START_PAD = 16.dp

/** 页面顶部留白 (tab 行之上). */
private val TV_COLLECTION_TOP_PAD = 24.dp

/** Tab 之间的间距. */
private val TV_COLLECTION_TAB_SPACING = 28.dp

/** 未选中 Tab 的文字不透明度. */
private const val TV_COLLECTION_TAB_UNSELECTED_ALPHA = 0.5f

/** Tab 选中指示条厚度. */
private val TV_COLLECTION_TAB_INDICATOR_HEIGHT = 3.dp

/** Tab 行到 Hero 信息块 (标题) 的间距. */
private val TV_COLLECTION_TABS_TO_HERO_GAP = 10.dp

/**
 * Hero 信息块固定高度 (标题 + 评分/连载行 + 个人状态行 + 简介). 简介用 weight 填满剩余空间;
 * 固定高度保证切换聚焦条目 (简介长短不同) 时网格不跳动. 调大 = 简介更多行, 网格更矮.
 * 当前取值让简介露出约 3 行 (标题+元信息+状态行 ≈ 108dp, 简介每行 ≈ 20dp).
 */
private val TV_COLLECTION_HERO_INFO_HEIGHT = 240.dp

/** Hero 信息块 (简介底部) 到网格的间距. */
private val TV_COLLECTION_HERO_TO_GRID_GAP = 8.dp

/**
 * 本页的诊断日志: 只在"焦点被塞到了不该去的地方、由页面自愈改派"时打一条 —— 那是偶发的
 * (系统兜底/焦点重分配挑的时机), 事后只有日志分得清"焦点自己跑了"与"用户按的".
 */
private val collectionLogger = logger("TvCollectionPage")
