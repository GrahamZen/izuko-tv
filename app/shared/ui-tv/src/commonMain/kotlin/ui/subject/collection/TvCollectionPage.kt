/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import kotlin.math.roundToInt
import me.him188.ani.app.ui.foundation.session.LocalTvRailEnter
import me.him188.ani.app.ui.foundation.focus.TV_TRANSIT_ANCHOR_SIZE
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_ASPECT_RATIO
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FADE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.rememberTvScrollActivityReporter
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageCallbacks
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeGridPageState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageMetrics
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.resolveSavedOrder
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.toNavPlaceholder
import me.him188.ani.app.data.network.BangumiSummaryService
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.AniDisplayTier
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.navigation.OnReturnToForeground
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.isAutoRepeat
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallTone
import me.him188.ani.app.ui.foundation.tv.TV_CARD_HERO_TUNING
import me.him188.ani.app.ui.foundation.tv.TV_GRID_TOP_BLEED
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_BOTTOM_BLEED
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_CARD_FOCUS_STYLE
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_HERO_ROW_TOP
import me.him188.ani.app.ui.foundation.tv.TvPosterWallToneSource
import me.him188.ani.app.ui.foundation.tv.focusScale
import me.him188.ani.app.ui.foundation.tv.tvPosterWallCardWidth
import me.him188.ani.app.ui.foundation.tv.tvPosterWallColumns
import me.him188.ani.app.ui.foundation.tv.tvTextOverCardsShadow
import me.him188.ani.app.ui.foundation.tv.tvPosterWallEndMargin
import me.him188.ani.app.ui.foundation.TvPageRefreshHandler
import me.him188.ani.app.ui.foundation.tv.tvPlayKeyShortPress
import me.him188.ani.app.ui.foundation.tv.rememberTvScrollHiddenProvider
import me.him188.ani.app.ui.foundation.tv.rememberTvSettledHeroProvider
import me.him188.ani.app.ui.foundation.tv.TV_NAV_LOCK_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaCache
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaSpec
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbor
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbors
import me.him188.ani.app.ui.foundation.tv.rememberTvHeroMediaPipeline
import me.him188.ani.app.ui.foundation.tv.resolveTvHeroMedia
import me.him188.ani.app.ui.foundation.tv.tvGridNeighborsOf
import me.him188.ani.app.ui.foundation.tv.prefetchTvSummaryFallback
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.tv.TV_GRID_START_BLEED
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_END_PAD
import me.him188.ani.app.ui.foundation.tv.TV_PORTRAIT_CARD_COVER_RATIO
import me.him188.ani.app.ui.foundation.focus.TvFocusTransitAnchor
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvGridFocus
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusRail
import me.him188.ani.app.ui.foundation.focus.tvFocusRailItem
import me.him188.ani.app.ui.foundation.focus.tvFocusRailKeys
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.tv.tvSwapSpec
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.rememberTvBesideAnchorPositionProvider
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_collection_doing
import me.him188.ani.app.ui.lang.subject_collection_done
import me.him188.ani.app.ui.lang.subject_collection_dropped
import me.him188.ani.app.ui.lang.subject_collection_on_hold
import me.him188.ani.app.ui.lang.subject_collection_uncollected
import me.him188.ani.app.ui.lang.subject_collection_wish
import me.him188.ani.app.ui.search.LoadErrorCard
import me.him188.ani.app.ui.search.isLoadingFirstPageOrRefreshing
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.analytics.Analytics
import me.him188.ani.utils.analytics.AnalyticsEvent.Companion.SubjectEnter
import me.him188.ani.utils.analytics.recordEvent
import org.jetbrains.compose.resources.stringResource

/**
 * TV 追番页 (海报墙):
 * - 顶部悬浮收藏分类 Tab (透明底, 未选中降透明度, 选中高亮 + 平滑滑动指示条), 聚焦即切换;
 * - 分类标签下面直接是「海报 + 番名」网格, 卡片带播放进度条, 聚焦行尽量停在视口垂直正中 (同 Apple TV);
 *   深色主题下卡片墙的整屏底色是深灰 (由主壳铺, 见 TvPosterWallTone);
 * - 卡片上按确定先进 hero 态: 背景为聚焦条目的 TMDB backdrop (在看条目优先下一集单集剧照), 显示标题 / 评分 /
 *   连载信息 / 个人观看状态 (高亮) / 简介; hero 态里再按确定进详情页, 按返回回卡片墙.
 *   长按卡片弹收藏菜单, 播放键直接播聚焦条目的下一集.
 *
 * 焦点动线: Tab 行 ↓ 网格; 网格首行 ↑ 回选中 Tab; 行缘左右键换到相邻 Tab 的同一行.
 * 数据全部来自收藏分页列表自身 (条目信息完整, 无需二次请求); 仅 backdrop/单集剧照/简介兜底异步.
 *
 * 分工: 本页管标签行、数据、hero 媒体流水线、网格送焦框架 ([TvGridFocusState])、返回键分层、导航、收藏菜单与错误横幅;
 * 背景图 / hero 文字 / 网格是原生 View, 接线见 [TvCollectionNativeGrid].
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
    // 分类标签的显示顺序 —— 用户可以自己排 (设置 - 界面 -「自定义追番页标签顺序」).
    // 选中项仍按类型存取 (state 内部是 COLLECTION_TABS_SORTED 的下标), 所以重排只换展示与左右
    // 导航次序, 不会把内容切走
    val tabOrder = rememberTvCollectionTabOrder()
    // 收藏计数是异步来的, 冷启动进页时往往还是 null: 全按 0 算 -> 落到第一个标签, 计数到达后
    // 由下方效应再定一次
    val firstNonEmptyTabIndex: () -> Int = {
        val counts = state.collectionCounts
        val type = tabOrder.firstOrNull { (counts?.getCount(it) ?: 0) > 0 }
            ?: tabOrder.first()
        COLLECTION_TABS_SORTED.indexOf(type)
    }
    if (freshEntry && !enteredBefore) {
        enteredBefore = true
        // 写在组合体里而不是效应里: 效应要等一帧, 那一帧会先用上次的分类渲染一次
        // (标签指示条与网格内容闪一下再跳走)
        val target = firstNonEmptyTabIndex()
        if (state.selectedTypeIndex != target) state.selectTypeIndex(target)
    }

    // 页面这里只取实例、不收集: 原生网格的接线 (TvCollectionNativeGrid) 已对同一缓存实例
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
    // 统一网格落点协调器 (进页恢复焦点 / 标签行下键 / 跨 tab 行对齐 / 返回回首卡 / 改收藏后的落点共用,
    // 机制见 [TvGridFocusState]; 原生网格经 NativeSendFocusEffect 接请求); 声明在 hero 默认值效应之前, 后者要在解析期间让路
    val focus = rememberTvFocusScope()
    val gridFocus = rememberTvGridFocus(focus)
    // 等条目消失 (改收藏状态让它离开本 tab) 那段过渡期的隐形焦点驻留点 (理由见 TvFocusTransitAnchor)
    val transitAnchor = remember { FocusRequester() }
    // 焦点当前是否在网格卡片上: 原生网格报卡片获焦置 true, 焦点离开网格 (去 tab/侧边栏) 置 false.
    // 下方重新进入的收尾效应拿它判断用户是否已经进了网格
    var gridRegionFocused by remember { mutableStateOf(false) }

    // 重新进入本页的收尾 (承上方 freshEntry): 等收藏计数到达后再定一次落哪个分类.
    // 网格位置不用在这里复位: 原生网格各标签的位置存在 rememberTvNativeGridPageState 里, 与 enteredBefore
    // 一样随重新进入丢掉, 网格从顶部排起 —— 与 hero 默认展示的列表第一项对得上.
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
    }

    // Hero 数据源: 聚焦卡片时记录该条目快照; 展示时再按 subjectId 对回最新列表数据
    // (看完一集返回本页后分页已刷新, 快照里的进度是旧的)
    var heroItem by remember { mutableStateOf<SubjectCollectionInfo?>(null) }
    // 聚焦卡的邻居 (subjectId -> 邻居), 在 onCardFocused 里按网格几何算好; 记 subjectId 是为了
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
    // 旧 hero (hero 态的背景图与文字不闪没, 新信息到了再换入); 确认新 tab 为空才清掉.
    // 落点解析期间不设默认 (否则先闪一下第一张卡的状态): 目标卡聚焦后由 onCardFocused
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
    // 收 provider: 在 body 里读 heroInfo 会把那次读记到本页上, 每换一格整页重组, 正是
    // hero 内容收 lambda 交给原生接线 (只让那一小块随换卡重组) 想避免的事. 机理与实测数据见 [rememberTvSettledHeroProvider]
    // 原生海报墙在页面这一侧的状态 (hero 态、整屏黑度、各标签网格的位置, 见 TvNativeGridPageState). 背景图与 hero 文字只在
    // hero 态画; hero 流水线在卡片墙上照样跑 —— 它同时是详情页的预取
    val nativeState = rememberTvNativeGridPageState()
    // 进出 hero 态那一刻背景图与文字当场换到聚焦的那张 (返回键远跳落地后排队的确认, 焦点刚到就进 hero 态)
    val heroDisplay = rememberTvSettledHeroProvider(flushOn = { nativeState.heroActive }) { heroInfo }
    // hero **文字**的展示目标, 与背景图分开: 低特效档下网格滚动 (换行) 期间为 null, 停稳后才是
    // 最后聚焦那张; 完整档透传. 机理与实测见 TvScrollActivity
    val heroTextDisplay = rememberTvScrollHiddenProvider(flushOn = { nativeState.heroActive }) { heroInfo }

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
    // 剧照原图 (停稳后升档): 视觉效果完整档且 4K 界面才要 (见 TvVisualEffectsLevel.originalImages) —— 1080p 界面上
    // 原图看不出区别, 只白花流量与解码
    val fullVisualEffects = LocalThemeSettings.current.visualEffects.originalImages && AniDisplayTier.isHighRes
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
    // 还要在转场动画里活一小会儿, 期间它**仍在组合、仍在收按键**. 不锁的话再按一次确认键 -> 连进两层,
    // 返回要按两下. (转场里按返回原先会被本页或主壳的 BackHandler 吃掉 —— 2026-08-22 真机: 详情页退出后
    // 落在探索页顶部 / 弹出侧边栏; 现在不在栈顶的页面返回处理一律不生效, 见 BackHandler, 这里不必再吞)
    // 定时解锁而非永不解锁: 本页正常随导航退出组合, remember 一并丢弃; 万一没退出 (导航被拒)
    // 也能自愈. 进去马上又退回来 (本页还没被移出组合) 时回到栈顶就解锁
    var navLocked by remember { mutableStateOf(false) }
    val pageForeground = LocalPageIsForeground.current
    LaunchedEffect(pageForeground) {
        snapshotFlow { pageForeground.value }.collect { if (it) navLocked = false }
    }
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
    // 播放键 (见下方 playKeyModifier): 直接进播放页 —— 看完全部则从第一集重温, 其余接着播 nextEpisodeIdToPlay
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
    val tabFocusKeys = remember { List(tabOrder.size) { CollectionTabFocusKey(it) } }
    // 选中标签在本页显示顺序里的下标. 用函数而非捕获值: 效应/按键回调里调用时要读到最新选中项
    val selectedTabTvIndex: () -> Int = { tabOrder.indexOf(COLLECTION_TABS_SORTED[state.selectedTypeIndex]) }
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
    // 错误横幅的高度 (含上间距): 原生网格的顶线跟着它往下让
    var nativeErrorCardHeightPx by remember { mutableIntStateOf(0) }
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
            // onCardFocused 设成正确那张, 这里放开只是为了兜住"恢复失败"的情形
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
    // 一次 request 覆盖两种结局, 由 [TvGridFocusState] 与原生网格接线里的判空取消 (TvCollectionNativeGrid) 一起分岔:
    // 本 tab 还有卡 -> 夹到相邻下标 (焦点留在原位置); 整个 tab 空了 -> 请求被取消, 隐形锚点的 onStranded 回到选中标签.
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

    // 网格通用返回规则: 不在首卡时按返回先回网格第一张卡 (借统一落点解析, 原生网格远跳回去,
    // 见下方 wallFarJump). 已在首卡时不启用, 返回交给上层 (回探索页).
    // derivedStateOf: 焦点下标每移一格都变, 直接读会让整页每格重组, 收窄成布尔
    var gridHasFocus by remember { mutableStateOf(false) }
    // **不能只看 gridHasFocus**: 从详情页/播放器返回本页时它要等分页数据到达 → 原生网格排到目标卡 →
    // 聚焦到位才变 true. 这段窗口里本处判 false, 返回键就被放行到上层, 用户明明停在网格深处却**一步弹回探索页**
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
    val nativeScrollReporter = rememberTvScrollActivityReporter()
    val railEnter = LocalTvRailEnter.current
    // 返回键回首卡是远跳: 这一发送焦前的滚动走 Apple TV 那条 spring, 一路滚上去 (原生网格按它选滚法), 别的送焦照旧
    // 瞬时对齐. 只在处理按键 / 协程里读写; 首卡本来就在屏上 (不用滚) 时这一发收尾就清掉
    var wallFarJump by remember { mutableStateOf(false) }
    LaunchedEffect(gridFocus) {
        snapshotFlow { gridFocus.switching }.collect { if (!it) wallFarJump = false }
    }
    BackHandler(enabled = !navLocked && backToFirstCard) {
        wallFarJump = true
        gridFocus.focusItem(0)
    }

    // 卡片长按弹出的收藏下拉 (与探索页一致, 锚在原生网格报上来的封面框上); 打开后短暂吞掉长按残余的确认键, 避免误触第一项.
    // remember: 工厂传给原生网格的接线 (TvCollectionNativeGrid), 每次新实例都会让它跟着重组
    val collectionMenuFor: (SubjectCollectionInfo) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit = remember {
        { info ->
            { expanded, onDismiss ->
                EditCollectionTypeDropDown(
                    currentType = info.collectionType,
                    expanded = expanded,
                    onDismissRequest = onDismiss,
                    onClick = { action ->
                        // 改成别的状态后本条目会离开当前 tab, 焦点此刻正在它的卡片上 (菜单是长按它
                        // 弹出的). 先把焦点钉到隐形锚点躲开即将到来的销毁,
                        // 再登记等待条目消失 —— 落点由上方的等待效应安排. 直接留在卡上等销毁的话
                        // 焦点会悬空并被系统重分配到第一个 tab 标签.
                        //
                        // 这里读 state 而非捕获外层的 selectedType: 本工厂 remember 无 key
                        // (避免每次重组换实例让原生网格的接线跟着重组), 捕获的值会停在首次组合那一刻.
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
                    // 摆在长按的那张卡旁边 (右边放得下放右边, 否则左边), 不压住封面
                    positionProvider = rememberTvBesideAnchorPositionProvider(),
                )
            }
        }
    }

    // 播放键: 短按播聚焦条目的下一集, 长按强制重拉当前 tab 的收藏列表 (默认只在进页/一小时
    // 定时同步时刷新). 挂在页面根上而不是网格上: 焦点在 tab 行时也能刷
    // 动作面板「刷新本页」= 强制重拉当前分类 (播放键长按已改为全局的「打开动作面板」)
    TvPageRefreshHandler { state.refreshSelectedPage() }
    // hero 态 (见 TvNativeGridPageState): 卡片上按确定先切到 hero 态 (背景图与简介淡入, 聚焦行移到简介下面),
    // 再按确定才放大进详情页 (从详情页返回仍停在 hero 态); 按返回变回卡片墙.
    // hero 态聚焦行离网格顶线多远: 行落在三页对齐的 TV_POSTER_WALL_HERO_ROW_TOP. 网格顶线在标签行下面、隔着简介到网格的间距
    // (简介块画在原生视图里, 不占布局高度)
    val wallHeroLinePx = with(LocalDensity.current) {
        (TV_POSTER_WALL_HERO_ROW_TOP - TV_COLLECTION_TOP_PAD - TV_COLLECTION_TAB_ROW_HEIGHT - TV_COLLECTION_HERO_TO_GRID_GAP).roundToPx()
    }
    // 排在网格返回规则 (回首卡) 之后登记, 优先级更高: hero 态里按返回先回卡片墙
    BackHandler(enabled = nativeState.heroActive) {
        nativeState.exitHero()
    }
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
            // 方向/确认键即取消在途送焦; 全页只在这一处上报
            .tvFocusNavSignal(focus)
            .then(playKeyModifier),
    ) {
        // 整屏底色 (主壳画, 见 TvPosterWallTone): 深色主题下 hero 态近黑, 卡片墙深灰. 黑度由原生视图逐帧写进来
        // (见 TvNativeGridPageState.tone)
        val wallTone = LocalTvPosterWallTone.current
        TvPosterWallToneSource(wallTone) { nativeState.tone }
        // 海报墙本体 (见 TvCollectionNativeGrid.kt): 背景图 / hero 文字 / 网格都在原生视图里, 画在标签行底下.
        // 背景图恒用"卡片态"渐变, 观看途中优先下一集剧照, 缺失回退整部官方主图; 三级回落 + 封面兜底/垫底 (四页同构),
        // 语义见 TvHeroMediaPipelineState
        val density = LocalDensity.current
        val windowSize = LocalWindowInfo.current.containerSize
        val pageWidth = with(density) { if (windowSize.width > 0) windowSize.width.toDp() else 960.dp } -
            TvNavigationRailDefaults.CollapsedWidth
        val pageHeight = with(density) { if (windowSize.height > 0) windowSize.height.toDp() else 540.dp }
        val gridContentWidth = pageWidth - TV_GRID_START_BLEED - TV_PAGE_END_PAD
        val nativeColumns = with(density) { tvPosterWallColumns(gridContentWidth) }
        val nativeCardWidth = tvPosterWallCardWidth(gridContentWidth, nativeColumns)
        val nativeCardHeight = nativeCardWidth / TV_PORTRAIT_CARD_COVER_RATIO
        val heroWidth = pageWidth - TV_COLLECTION_START_PAD - TV_PAGE_END_PAD
        val nativeMetrics = with(density) {
            val backdropHeightPx = (pageHeight * TV_CARD_HERO_TUNING.backdropHeight).roundToPx()
            TvNativeGridPageMetrics(
                pageWidthPx = pageWidth.roundToPx(),
                pageHeightPx = pageHeight.roundToPx(),
                // 标签行 + 过渡锚点 (1dp) + 错误横幅 (有的话) + 简介到网格的间距
                gridTopPx = (TV_COLLECTION_TOP_PAD + TV_COLLECTION_TAB_ROW_HEIGHT + TV_TRANSIT_ANCHOR_SIZE + TV_COLLECTION_HERO_TO_GRID_GAP)
                    .roundToPx() + if (items.loadState.hasError) nativeErrorCardHeightPx else 0,
                grid = TvNativeGridMetrics(
                    columns = nativeColumns,
                    startPx = TV_GRID_START_BLEED.roundToPx(),
                    endPx = TV_PAGE_END_PAD.roundToPx(),
                    topBleedPx = TV_GRID_TOP_BLEED.roundToPx(),
                    bottomBleedPx = TV_POSTER_WALL_BOTTOM_BLEED.roundToPx(),
                    endMarginPx = tvPosterWallEndMargin(nativeCardHeight, TV_POSTER_WALL_CARD_FOCUS_STYLE.focusScale).roundToPx(),
                    heroLinePx = wallHeroLinePx,
                    fadeDistancePx = TV_CARD_FADE_DISTANCE.toPx(),
                    // 卡照常从标签行底下滑过 (同探索页), 标签字自带投影 (见 TvCollectionTabRow)
                    dimPastTopLine = false,
                ),
                backdropWidthPx = (backdropHeightPx * TV_BACKDROP_ASPECT_RATIO).roundToInt(),
                backdropHeightPx = backdropHeightPx,
                heroLeftPx = TV_COLLECTION_START_PAD.roundToPx(),
                heroTopPx = (TV_COLLECTION_TOP_PAD + TV_COLLECTION_TAB_ROW_HEIGHT + TV_COLLECTION_TABS_TO_HERO_GAP).roundToPx(),
                heroWidthPx = heroWidth.roundToPx(),
                heroHeightPx = TV_COLLECTION_WALL_HERO_INFO_HEIGHT.roundToPx(),
                titleWidthPx = (heroWidth * TV_CARD_HERO_TUNING.titleWidth).roundToPx(),
                summaryWidthPx = (heroWidth * TV_CARD_HERO_TUNING.summaryWidth).roundToPx(),
                // 网格从收起的侧边栏底下画过 (同探索页): 最左一列的放大与投影不在页面左缘被裁掉
                bleedLeftPx = TvNavigationRailDefaults.CollapsedWidth.roundToPx(),
            )
        }
        TvCollectionNativeGrid(
            state = nativeState,
            metrics = nativeMetrics,
            cardWidth = nativeCardWidth,
            selectedTab = state.selectedTypeIndex,
            tabOrderIndex = { tab -> tabOrder.indexOf(COLLECTION_TABS_SORTED[tab]) },
            items = items,
            countSaysEmpty = state.collectionCounts?.getCount(COLLECTION_TABS_SORTED[state.selectedTypeIndex]) == 0,
            playHistories = { playHistories },
            heroRaw = { heroInfo },
            heroDisplay = heroDisplay,
            heroText = heroTextDisplay,
            heroPipeline = heroPipeline,
            episodeStillCache = episodeStillCache,
            summaryFallbackCache = summaryFallbackCache,
            fadeColor = wallTone?.heroColor ?: AniThemeDefaults.shellBackgroundColor,
            gridFocus = gridFocus,
            farJump = { wallFarJump },
            onFarJumpConsumed = { wallFarJump = false },
            callbacks = TvNativeGridPageCallbacks(
                onCardFocused = { index, info ->
                    info?.let {
                        heroItem = it
                        // 邻居按网格几何算 (见 tvGridNeighborsOf): 卡片墙上不画背景图, hero 流水线也要拿它们给详情页预取.
                        // 剧照偏好按每个邻居自己的观看状态定 (网格里"在看"与其余条目是混着的, 见 TvHeroNeighbor)
                        heroNeighbors = it.subjectId to tvGridNeighborsOf(index, nativeColumns) { i ->
                            if (i in 0 until items.itemCount) {
                                items.peek(i)?.let { n -> TvHeroNeighbor(n.subjectId, n.stillEpisodeIdOrNull() != null) }
                            } else {
                                null
                            }
                        }
                    }
                    lastFocusedCard = index
                    gridRegionFocused = true
                },
                onCardClick = { _, info -> navigateToSubject(info) },
                onTopRowUp = { focusSelectedTab() },
                onRowEdge = { direction, row ->
                    // 行缘换标签: 行末按右 → 右边标签同一行行首, 行首按左对称; 首个标签行首按左进侧边栏
                    val tvIndex = tabOrder.indexOf(COLLECTION_TABS_SORTED[state.selectedTypeIndex])
                    when {
                        direction > 0 -> {
                            if (tvIndex in 0..<tabOrder.size - 1) {
                                gridFocus.focusRowEdge(row, direction = 1)
                                selectType(tabOrder[tvIndex + 1])
                            }
                            true
                        }

                        tvIndex > 0 -> {
                            gridFocus.focusRowEdge(row, direction = -1)
                            selectType(tabOrder[tvIndex - 1])
                            true
                        }

                        else -> {
                            railEnter?.requestFocus()
                            true
                        }
                    }
                },
                onGridFocusChanged = { has ->
                    gridHasFocus = has
                    if (!has) gridRegionFocused = false
                },
                onScrollingChanged = { nativeScrollReporter?.setScrolling(it) },
            ),
            menuFor = collectionMenuFor,
        )

        Column(
            Modifier.fillMaxSize()
                .padding(start = TV_COLLECTION_START_PAD, top = TV_COLLECTION_TOP_PAD),
        ) {
            // 悬浮分类 Tab (透明底浮于 backdrop 上)
            TvCollectionTabRow(
                modifier = Modifier.height(TV_COLLECTION_TAB_ROW_HEIGHT),
                tabs = tabOrder,
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
                    // 视口首行 = 原生网格顶线以下第一张所在的行 (出血区里正在淡出的上一行不算, 见 firstIndexBelowTopLine)
                    val firstVisibleRow = nativeState.view?.let { view ->
                        view.firstIndexBelowTopLine()?.let { it / (view.grid?.metrics?.columns ?: 1).coerceAtLeast(1) }
                    }
                    if (firstVisibleRow != null) {
                        // **换过 tab 就不能拿视口首行当落点**: 切 tab 时页面把 lastFocusedCard 重置成
                        // -1 (忘掉上次那张卡), 但原生网格按标签各自保留位置, 换回来的那份**还停在上次留下的地方** ——
                        // 两者不一致, 于是"视口首行"是上次停的那一行, 焦点落到列表中间
                        // (一路右滑穿过所有 tab, 再从标签行左滑回第一个 tab, 按下键就是这样).
                        // 目标定成第 0 行, 送焦时网格一并滚回顶部, 焦点与滚动重新一致.
                        // 没换 tab 的情形 (上到标签行再下来) 保持原样: 回到刚才看的那一行.
                        val targetRow = if (lastFocusedCard >= 0) firstVisibleRow else 0
                        gridFocus.focusRowEdge(targetRow, direction = 1)
                        true
                    } else {
                        // 选中的 tab 没有卡: 有错误横幅就进横幅, 没有也**吃掉这一下**. 放行给默认方向搜索的话,
                        // 换 tab 滑动过渡里上一个 tab 的网格还在屏上退场, 焦点可能落到它那张马上就不显示的卡上
                        runCatching { errorCardFocusRequester.requestFocus() }
                        true
                    }
                },
            )

            // 过渡期的隐形焦点驻留点 (改收藏状态让条目离开本 tab 时焦点先躲到这里,
            // 机制与摆放位置的讲究见 [TvFocusTransitAnchor]; 与时间表换天共用同一实现).
            // extraCanFocus: 改收藏状态那条路径上没有挂起的落点请求 (要等条目真的从列表消失才发),
            // 锚点得靠这个条件保持可聚焦
            TvFocusTransitAnchor(
                requester = transitAnchor,
                switching = { gridFocus.switching },
                // 驻留期间的按键被锚点吞掉, 不算用户接管 (否则在途送焦被取消, 焦点落回标签行)
                scope = focus,
                extraCanFocus = { awaitingRemovalSubjectId != null },
                // 在途请求被取消 / 等条目消失结束: 焦点还在锚点上而锚点即将不可聚焦, 补落点到选中标签
                onStranded = { focusSelectedTab() },
            )

            // 列表加载出错时的错误横幅, 在标签行下面
            if (items.loadState.hasError) {
                LoadErrorCard(
                    LoadError.fromCombinedLoadStates(items.loadState),
                    onRetry = { items.refresh() },
                    // 原生网格的顶线按它的高度往下让 (见 nativeMetrics)
                    Modifier.onSizeChanged { nativeErrorCardHeightPx = it.height }
                        .padding(top = TV_COLLECTION_HERO_TO_GRID_GAP, end = TV_PAGE_END_PAD)
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
        }
    }
}

/** Hero 背景想用"下一集剧照"时返回该集 id (观看途中/追平连载); 其余状态用整部 backdrop. */
internal fun SubjectCollectionInfo.stillEpisodeIdOrNull(): Int? {
    val status = progressInfo.continueWatchingStatus
    return if (status is ContinueWatchingStatus.Continue || status is ContinueWatchingStatus.Watched) {
        progressInfo.nextEpisodeIdToPlay
    } else null
}

/** 交给共享流水线/展示层的最小描述, 见 [TvHeroMediaSpec]. */
internal fun SubjectCollectionInfo.toHeroMediaSpec(neighbors: TvHeroNeighbors = TvHeroNeighbors()) =
    TvHeroMediaSpec(
        subjectId = subjectId,
        preferNextEpisodeStill = stillEpisodeIdOrNull() != null,
        coverUrl = subjectInfo.imageLarge,
        neighbors = neighbors,
    )

/**
 * 悬浮分类 Tab 行: 透明底, 未选中降透明度, 选中加粗 + 底部平滑滑动的主题色指示条; 聚焦即切换.
 * 数字统计以小号淡色跟在标签后. 按下键把焦点送入下方网格 (没有卡时送进错误横幅).
 */
@Composable
private fun TvCollectionTabRow(
    /** 本行要摆的分类, 按显示顺序 (见 [rememberTvCollectionTabOrder]). */
    tabs: List<UnifiedCollectionType>,
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
    val tabBounds = remember(tabs.size) {
        mutableStateListOf(*Array(tabs.size) { 0.dp to 0.dp })
    }
    // 焦点下标记账 / "聚焦即选中"封印 / 左右键显式移动 / 连发守卫都在共享原语里 (见 TvFocusRail.kt).
    // 标签恒在屏且必然可聚焦, 所以送焦直接 requestFocus, 不用走 scope 请求 + 悬挂.
    // 卡片墙上越过网格顶线的卡不压暗, 照常从本行底下滑过: 字靠投影压在封面上读得清
    val labelShadow = tvTextOverCardsShadow()
    val rail = rememberTvFocusRail(
        scope = focusScope,
        keyAt = { index -> tabFocusKeys[index] },
        onMove = { index -> runCatching { focusScope.requesterOf(tabFocusKeys[index]).requestFocus() } },
    )
    Column(modifier) {
        Row(
            Modifier.tvFocusRailKeys(
                state = rail,
                itemCount = { tabs.size },
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
            tabs.forEachIndexed { index, type ->
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
                        style = MaterialTheme.typography.titleMedium.copy(shadow = labelShadow),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                    counts(type)?.let { count ->
                        Text(
                            count.toString(),
                            color = labelColor.copy(alpha = labelColor.alpha * 0.7f),
                            style = MaterialTheme.typography.labelMedium.copy(shadow = labelShadow),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // 平滑滑动的选中指示条
        val (targetX, targetWidth) = tabBounds[tabs.indexOf(selectedType).coerceAtLeast(0)]
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
    // 流畅档直接到位 (见 tvContentSwapAnimated): 默认弹簧一跑就是二十来帧, 而它只是一条小横线
    val spec = tvSwapSpec<Dp>(spring(visibilityThreshold = Dp.VisibilityThreshold))
    val indicatorX by animateDpAsState(targetX, spec, label = "tabIndicatorX")
    val indicatorWidth by animateDpAsState(targetWidth, spec, label = "tabIndicatorWidth")
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

/**
 * 本页分类标签的显示顺序: 用户排过的那份 ([ThemeSettings.tvCollectionTabOrder]) 对齐到当前全集.
 *
 * 排过之后集合仍可能变 (以后加/删分类), 所以一律经 [resolveSavedOrder]: 缺的按默认位置补回,
 * 不认识的丢掉 —— 自定义页与本页共用这一个函数, 两边才不会排出两个样子.
 */
@Composable
internal fun rememberTvCollectionTabOrder(): List<UnifiedCollectionType> {
    val saved = LocalThemeSettings.current.tvCollectionTabOrder
    return remember(saved) { resolveSavedOrder(saved, TV_COLLECTION_TABS) }
}

@Composable
internal fun UnifiedCollectionType.displayTextTv(): String {
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
 * TV 追番页分类 tab 的**默认**顺序: 想看 在看 搁置 看过 抛弃. 用户排过之后以他排的为准
 * (见 [rememberTvCollectionTabOrder]); 这份仍是"全集"与补位基准, 加新分类时按它该在的位置插.
 *
 * 仅影响本页展示与左右导航次序; [UserCollectionsState] 内部仍按 [COLLECTION_TABS_SORTED] 的下标
 * 存取, 使用处经类型换算.
 */
internal val TV_COLLECTION_TABS = listOf(
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

/** 内容左侧留白 (外层主壳已让开侧边栏 48dp, 总左缘 = 48 + 此值, 与探索页一致). */
internal val TV_COLLECTION_START_PAD = 16.dp

/** 页面顶部留白 (tab 行之上). */
internal val TV_COLLECTION_TOP_PAD = 24.dp

/** Tab 之间的间距. */
internal val TV_COLLECTION_TAB_SPACING = 28.dp

/** 未选中 Tab 的文字不透明度. */
internal const val TV_COLLECTION_TAB_UNSELECTED_ALPHA = 0.5f

/** Tab 选中指示条厚度. */
internal val TV_COLLECTION_TAB_INDICATOR_HEIGHT = 3.dp

/** Tab 行到 Hero 信息块 (标题) 的间距. */
private val TV_COLLECTION_TABS_TO_HERO_GAP = 10.dp

/** Tab 行定高 (一行字 24 + 上下各 4 + 指示条上间距 4 与厚度 3): 网格顶线与 Hero 信息块的高度由它倒推, 见 [TV_COLLECTION_WALL_HERO_INFO_HEIGHT]. */
private val TV_COLLECTION_TAB_ROW_HEIGHT = 39.dp

/**
 * 网格上方的间距: 卡片墙是 Tab 行 (及错误横幅) 到网格顶线, hero 态是 Hero 信息块 (简介底部) 到聚焦行.
 * 聚焦卡放大时顶边向上伸出 7.6~9dp, 这段要盖得住, 否则第一行放大的卡会顶到简介最后一行.
 */
private val TV_COLLECTION_HERO_TO_GRID_GAP = 16.dp

/**
 * hero 态的 Hero 信息块高度 (标题 + 评分/连载行 + 个人状态行 + 简介): 从 Tab 行下方 [TV_COLLECTION_TABS_TO_HERO_GAP] 处往下长,
 * 下沿停在聚焦行 (三页对齐的 [TV_POSTER_WALL_HERO_ROW_TOP]) 上方 [TV_COLLECTION_HERO_TO_GRID_GAP] 处; 标题与信息行之外的高度全给简介.
 */
private val TV_COLLECTION_WALL_HERO_INFO_HEIGHT = TV_POSTER_WALL_HERO_ROW_TOP - TV_COLLECTION_HERO_TO_GRID_GAP -
        TV_COLLECTION_TOP_PAD - TV_COLLECTION_TAB_ROW_HEIGHT - TV_COLLECTION_TABS_TO_HERO_GAP

/**
 * 本页的诊断日志: 只在"焦点被塞到了不该去的地方、由页面自愈改派"时打一条 —— 那是偶发的
 * (系统兜底/焦点重分配挑的时机), 事后只有日志分得清"焦点自己跑了"与"用户按的".
 */
private val collectionLogger = logger("TvCollectionPage")
