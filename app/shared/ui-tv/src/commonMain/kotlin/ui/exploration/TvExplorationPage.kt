/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import me.him188.ani.app.data.network.RecommendationRefreshProgress
import me.him188.ani.app.domain.episode.GetAnimeScheduleFlowUseCase
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallTone
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo
import me.him188.ani.app.data.recommendation.RecommendationGroup
import me.him188.ani.app.data.recommendation.RecommendationGroupKind
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.subjectInfo
import me.him188.ani.app.data.network.BangumiSummaryService
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.ui.main.TvPosterWallPreviewPage
import me.him188.ani.app.ui.foundation.AniDisplayTier
import me.him188.ani.app.ui.foundation.LocalTvBackLongPressHost
import me.him188.ani.app.ui.foundation.TvPageRefreshHandler
import me.him188.ani.app.ui.foundation.TvPageShuffleHandler
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.isAutoRepeat
import me.him188.ani.app.ui.foundation.focus.TvFocusRestoreClaim
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.session.LocalTvRailEnter
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_CARD_HERO_TUNING
import me.him188.ani.app.ui.foundation.tv.TV_CAROUSEL_HERO_TUNING
import me.him188.ani.app.ui.foundation.tv.TV_HERO_TEXT_BOTTOM
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_HERO_ROW_TOP
import me.him188.ani.app.ui.foundation.tv.TV_HERO_REFERENCE_PAGE_HEIGHT
import me.him188.ani.app.ui.foundation.tv.tvHeroScaleShift
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.TvPosterWallScaled
import me.him188.ani.app.ui.foundation.tv.tvPosterWallGrid
import me.him188.ani.app.ui.foundation.tv.TvPosterWallToneSource
import me.him188.ani.app.ui.foundation.tv.rememberTvScrollHiddenProvider
import me.him188.ani.app.ui.foundation.tv.rememberTvSettledHeroProvider
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropGeometry
import me.him188.ani.app.ui.foundation.tv.TV_HERO_MEDIA_DEBOUNCE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaCache
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaSpec
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbor
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbors
import me.him188.ani.app.ui.foundation.tv.TvHeroPrefetch
import me.him188.ani.app.ui.foundation.tv.rememberTvHeroMediaPipeline
import me.him188.ani.app.ui.foundation.tv.resolveTvHeroMedia
import me.him188.ani.app.ui.foundation.tv.prefetchTvSummaryFallback
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropReady
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropUrl
import me.him188.ani.app.ui.foundation.tv.TV_NAV_LOCK_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_NAV_READY_BUDGET
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_END_PAD
import me.him188.ani.app.ui.foundation.tv.TV_PAGE_HINT_BOTTOM_PAD
import me.him188.ani.app.ui.foundation.tv.TV_PORTRAIT_CARD_COVER_RATIO
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.tv.tvPlayKeyShortPress
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.rememberTvBesideAnchorPositionProvider
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_rec_also_watched
import me.him188.ani.app.ui.lang.exploration_rec_because_you_liked
import me.him188.ani.app.ui.lang.exploration_rec_change_taste
import me.him188.ani.app.ui.lang.exploration_rec_for_you_high_rated
import me.him188.ani.app.ui.lang.exploration_rec_loading
import me.him188.ani.app.ui.lang.exploration_rec_progress_candidates
import me.him188.ani.app.ui.lang.exploration_rec_progress_collections
import me.him188.ani.app.ui.lang.exploration_rec_progress_collections_start
import me.him188.ani.app.ui.lang.exploration_rec_similar_to
import me.him188.ani.app.ui.lang.exploration_rec_this_season
import me.him188.ani.app.ui.lang.exploration_rec_this_season_new
import me.him188.ani.app.ui.lang.exploration_rec_top_rated
import me.him188.ani.app.ui.lang.exploration_rec_trending
import me.him188.ani.app.ui.lang.exploration_recommendations
import me.him188.ani.app.ui.main.TvWallScaleEntry
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.analytics.Analytics
import me.him188.ani.utils.analytics.AnalyticsEvent.Companion.SubjectEnter
import me.him188.ani.utils.analytics.recordEvent
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import me.him188.ani.app.data.models.subject.FollowedSubjectInfo
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FADE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_BOTTOM_BLEED
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_CARD_FOCUS_STYLE
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_ROW_SPACING
import me.him188.ani.app.ui.foundation.tv.focusScale
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_HEADER_GAP
import me.him188.ani.app.ui.foundation.tv.tvPosterWallEndMargin
import me.him188.ani.app.ui.foundation.tv.tvPosterWallLabelHeight
import android.graphics.Rect as AndroidRect
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_ASPECT_RATIO
import me.him188.ani.app.ui.foundation.tv.rememberTvScrollActivityReporter
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreListener
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreMetrics
import com.github.panpf.sketch.LocalPlatformContext
import me.him188.ani.app.data.network.TrendsRepository
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.tv.TvHeroImagePrefetch
import me.him188.ani.app.ui.foundation.tv.isOriginalSizeTmdbUrl

/**
 * TV 沉浸式探索页 (海报墙): 顶上是热门轮播 (轮播条目的 TMDB 背景图 + 标题 / 评分连载 / 简介 + 「立即观看」「新番时间表」两颗按钮
 * + 轮播指示器), 下面是按组分段的「海报 + 番名」卡片墙 —— 继续观看一行 + 推荐按组分行, 组间一行标题. 焦点一进卡片区整页上滚,
 * 轮播跟着上移、还露着一截; 卡片上按确认进 hero 态 (背景与文字换成聚焦卡), 再按确认放大进详情页.
 *
 * 画面与焦点是原生视图 (接线见 TvExplorationNativeWall.kt, 结构与停位规则见 TvNativeExploreList.kt): 行横滑不循环、按需挪
 * (焦点在屏上完整露出的几张之间走时不滚, 走到边上那张才整行挪一格), 上下落到屏上同一列那张, 纵向聚焦行尽量停在视口垂直正中
 * (同 Apple TV). 本页是它的「大脑」:
 *  - 数据: 继续观看分页、推荐分组与切行 (见 [tvRecRowsOf])、热门轮播与自动轮播计时;
 *  - hero 媒体流水线与换挡规则: 轮播与聚焦卡两路各自换挡, 原生视图进出 hero 态时当帧换到另一路;
 *  - 焦点簿记 (行键 + 行内下标, 原生视图经 [TvNativeExploreListener] 同步报上来) 与两个显式落点请求 ([TvCardFocusRequest] /
 *    [TvHeroFocusRequest], 由 TvExplorationNativeWall 转给原生视图送焦);
 *  - 返回键分层、导航 (进详情页前的门控与转场期间的锁)、长按收藏菜单、新番时间表预取.
 *
 * 深色主题下整页底色是 Apple TV 那种深灰 (由主壳铺, 见 TvMainScreenLayout).
 */
@Composable
fun TvExplorationPage(
    state: ExplorationPageState,
    modifier: Modifier = Modifier,
) {
    // 海报墙大小 (动作面板): 卡片按它缩放, 轮播与 hero 的文字不变 (见 TvPosterWallScaled)
    TvPosterWallScaled { TvExplorationPageContent(state, modifier) }
}

@Composable
private fun TvExplorationPageContent(
    state: ExplorationPageState,
    modifier: Modifier,
) {
    val navigator = LocalNavigator.current
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val trendsRepository = remember { GlobalKoin.get<TrendsRepository>() }
    val sketch = LocalSketch.current
    val platformContext = LocalPlatformContext.current
    val bangumiSummaryService = remember { GlobalKoin.get<BangumiSummaryService>() }
    val setCollectionTypeUseCase = remember { GlobalKoin.get<SetSubjectCollectionTypeOrDeleteUseCase>() }
    val settingsRepository = remember { GlobalKoin.get<SettingsRepository>() }
    val playHistoryRepository = remember { GlobalKoin.get<EpisodePlayHistoryRepository>() }
    val scope = rememberCoroutineScope()
    // 新番时间表的数据预取 (两台电视实测, 进时间表 0.73~1.08s 里 0.6~0.8s 在等它): 焦点落到 hero 按钮上就在后台拉一次, 进页时
    // 首帧就是数据. 缓存还新鲜时是空操作; 请求挂在仓库自己的作用域上, 离开本页也会跑完、落进缓存. 落在「新番时间表」上立即拉;
    // 落在「立即观看」上 (开屏默认就在这) 晚一点, 不跟探索页首屏的请求抢
    val scheduleUseCase = remember { GlobalKoin.get<GetAnimeScheduleFlowUseCase>() }
    val schedulePrefetch = remember { mutableStateOf<Job?>(null) }
    val prefetchSchedule: (delayMillis: Long) -> Unit = { delayMillis ->
        schedulePrefetch.value?.cancel()
        schedulePrefetch.value = scope.launch {
            delay(delayMillis)
            try {
                scheduleUseCase.prefetch()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失败不管: 进页时自己再拉 (失败不缓存)
            }
        }
    }
    val toaster = LocalToaster.current

    // 画面与焦点在原生视图里 (见 TvExplorationNativeWall.kt), 数据 / hero 流水线 / 焦点簿记 / 返回键分层 / 导航在本页
    val nativeState = rememberTvExplorationNativeState()
    val nativeScrollReporter = rememberTvScrollActivityReporter()
    val railEnter = LocalTvRailEnter.current
    // 海报墙的列数. 行结构 (哪张卡在哪一行) 页面各处都要用, 所以在页面级按窗口宽度算, 不等卡片区量出来:
    // 本页在主壳里, 左边让出收起的侧边栏, 右边留 TV_PAGE_END_PAD.
    // 窗口还没测量的那几帧尺寸是 0 (冷启动时本页正好在那会儿组合, 见 AniDisplayTier): 先按 1080p 电视的 960 × 540 dp 算,
    // 否则会先按 1 列切一遍行, 下一帧再换成 6 列
    val wallLayout = tvExplorationWallLayout()
    val wallColumns = wallLayout.columns

    // 聚焦条目 (卡片聚焦时上报, 见 nativeListener); 标题/封面来自卡片自身数据, 立即可显示.
    // 初值取上次离开时的目标 (见 TvExplorationLastHero.target): 返回本页时首帧就能算出背景图,
    // 不必等卡片重新上报聚焦
    var heroTarget by remember { mutableStateOf(TvExplorationLastHero.target) }
    // subjectId -> Bangumi 完整条目信息 (评分/连载/简介). 在看列表的条目自带, 聚焦时直接种入.
    val infoCache = remember { mutableStateMapOf<Int, SubjectCollectionInfo>() }
    // hero 媒体 (backdrop / 下一集剧照 / 简介兜底) 全部走 TvHeroMediaCache: 进程级且四个 TV 页
    // 共用 —— 为什么不用 remember、为什么四页同表, 见那里的 KDoc
    val episodeStillCache = TvHeroMediaCache.nextEpisodeMedia
    val summaryFallbackCache = TvHeroMediaCache.summaryFallbacks
    // 播放历史 (响应式): 退出播放器回到本页时进度条 / 剩余分钟自动更新.
    // 继续观看卡的进度条与 hero 剩余分钟都从这里取"下一集"的播放位置.
    val playHistories by playHistoryRepository.flow.collectAsStateWithLifecycle(emptyList())

    // 剧照原图 (停稳后升档): 视觉效果完整档且 4K 界面才要 (见 TvVisualEffectsLevel.originalImages) —— 1080p 界面上
    // 原图看不出区别, 只白花流量与解码
    val fullVisualEffects = LocalThemeSettings.current.visualEffects.originalImages && AniDisplayTier.isHighRes

    // hero 媒体流水线 (连发合并/调度器/邻居预取/图片预热/封面兜底), 四页共用的机械部分
    // 全在 host 里 —— 见 rememberTvHeroMediaPipeline. 本页只提供解析链与两个私有钩子.
    val heroPipeline = rememberTvHeroMediaPipeline(
        tmdb = tmdb,
        fullVisualEffects = fullVisualEffects,
        restartKey = Unit,
        spec = { heroTarget?.toHeroMediaSpec() },
        resolve = { s ->
            resolveTvHeroMedia(
                s.subjectId, collectionRepo, tmdb,
                preferNextEpisodeStill = s.preferNextEpisodeStill,
                settingsRepository = settingsRepository,
            )
        },
        // 剧照那一跳 (settingsRepository) 必须带上: 邻居与目标同属一行, 继续观看行的 hero
        // 显示的是单集剧照 —— 预取版少这一跳的话, 热好的是它不显示的整部 backdrop, 真走过去
        // 时剧照照样现下 (2026-08-14 实测 313ms, 预热全程空转)
        resolveNeighbor = { _, neighbor ->
            resolveTvHeroMedia(
                neighbor.subjectId, collectionRepo, tmdb,
                preferNextEpisodeStill = neighbor.preferNextEpisodeStill,
                settingsRepository = settingsRepository,
            )
        },
        beforeResolve = { s ->
            // 进程缓存有就先直出 (返回本页时页面级 infoCache 是空的, 但进程级还在):
            // hero 文字只依赖这一份, 不该陪着媒体链等. 在看列表的条目自带完整信息
            // (聚焦时 onFocusItem 已种入 infoCache), 倒种进进程缓存让解析链第一跳直接命中
            val info = infoCache[s.subjectId]
                ?: TvHeroMediaCache.peekSubjectInfo(s.subjectId)?.also { infoCache[s.subjectId] = it }
            if (info != null) TvHeroMediaCache.putSubjectInfo(s.subjectId, info)
        },
        afterResolve = { s ->
            var info = infoCache[s.subjectId]
            if (info == null) {
                // 解析链的结果落在进程级普通表里; 页面这张快照表只写**聚焦**那一个条目 ——
                // 预取写进来的话, 用户发呆时后台每落一条就把 hero 文字块重组一遍
                info = TvHeroMediaCache.peekSubjectInfo(s.subjectId)
                    ?: return@rememberTvHeroMediaPipeline false // 没拿到, 下次聚焦重试
                infoCache[s.subjectId] = info
            }
            // 过期缓存自刷新: repository 的 flow 先 emit 本地缓存 (可能过期, 如收藏时"未开播"、
            // 现已完结), 过期时会拉服务器并再次 emit. 解析链的 .first() 拿到旧值就取消会把刷新
            // 请求一并取消, 过期状态永远留在页面 —— 这里持续收集, 后续 emission 覆盖 infoCache
            // (聚焦换卡时 collectLatest 取消; 延迟一拍避免快速划卡时空转)
            launch {
                delay(TV_HERO_MEDIA_DEBOUNCE_MILLIS)
                runCatching {
                    collectionRepo.subjectCollectionFlow(s.subjectId).collect { fresh ->
                        infoCache[s.subjectId] = fresh
                    }
                }
            }
            // 并行不串行: 简介不影响任何图片显示, 串在主链上白白把邻居预取压后一个 RTT
            if (info.subjectInfo.summary.isBlank()) {
                launch { bangumiSummaryService.prefetchTvSummaryFallback(s.subjectId) }
            }
            true
        },
    )

    // 继续观看优先展示下一集剧照, 缺失时回退整部 backdrop.
    // 剧照按设置降档: 默认 w1280 (原图偶有 4K 级, 解码 8-33MB, 是低端盒子每次换卡的重锤;
    // 铺满后经渐隐压暗在 10-foot 距离不可辨), 开了完整视觉效果才用原图. backdrop 那路
    // 服务层已是 w1280 档, 不受影响 —— fullVisualEffects 已在上面声明
    // 展示用目标: 低端档 (完整视觉效果关闭) 下连发导航期间不换背景图/文字, 停下来才换一次.
    // 数据预取那条流水线 (上面的 LaunchedEffect) 仍读真实的 heroTarget —— 停下来时数据已在缓存里.
    // 机理与实测数据见 [rememberTvSettledHeroProvider].
    //
    // **收 provider, 与追番/搜索/时间表三页一致**: heroTarget 是每格方向键都变的热状态, 在这里读出来的话
    // 那次读记在本函数身上, 于是每换一张卡整页重跑一遍. 读取全部下沉到 TvExplorationNativeSources 里,
    // 重组的只有那一小块.
    // null = 焦点在 hero (或从未进过卡片区). 跨导航保存: 进详情页返回后恢复到同一行.
    var focusedRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 背景与 hero 文字在卡片墙上只跟轮播走 (卡片区里换卡不换背景 —— 那时两者都已随列表退场), 真实的 heroTarget 仍跟着聚焦卡,
    // 喂 hero 流水线 (详情页预取) 与 hero 态. 两路各自换挡, 原生视图进出 hero 态时当帧换到另一路 (见 TvExplorationNativeSources).
    // 初值同 heroTarget, 见 TvExplorationLastHero
    var carouselHeroTarget by remember { mutableStateOf(TvExplorationLastHero.carouselTarget) }
    // 轮播态 (焦点在 hero 上) 翻页不等方向键抬起: 那里没有按住扫过多项的手势, 等抬起只是晚起步
    // 进出 hero 态那一刻背景图与文字当场换到聚焦的那张 (返回键远跳落地后排队的确认, 焦点刚到就进 hero 态)
    val heroDisplay = rememberTvSettledHeroProvider(awaitKeyRelease = { focusedRowKey != null }, flushOn = { nativeState.heroActive }) { heroTarget }
    // hero **文字**的展示目标, 与背景图分开: 低特效档下卡片滚动期间为 null (文字块整个不画),
    // 停稳后才是最后聚焦那张 (读的是真实 heroTarget, 不经 300ms 换挡, 停下来那一刻就是终值);
    // 完整档透传. 背景图照旧走上面的换挡合并. 机理与实测见 TvScrollActivity
    val heroTextDisplay = rememberTvScrollHiddenProvider(flushOn = { nativeState.heroActive }) { heroTarget }
    // 轮播那一路. 与聚焦卡那一路共用一个换挡的话, 按下确定后要等静默与抬键才换到聚焦卡, 头几帧背景与文字还是轮播那一部
    val carouselHeroDisplay = rememberTvSettledHeroProvider(awaitKeyRelease = { false }) { carouselHeroTarget }
    val carouselHeroText = rememberTvScrollHiddenProvider { carouselHeroTarget }

    val onFocusItem: (
        subjectId: Int, title: String, seed: SubjectCollectionInfo?, fromFollowed: Boolean, coverUrl: String,
        neighbors: TvHeroNeighbors,
    ) -> Unit =
        { subjectId, title, seed, fromFollowed, coverUrl, neighbors ->
            // 继续观看行的 seed 来自 paging flow (始终最新), 无条件覆盖 —— 看完一集回到本页时
            // 进度/下一集要跟着变 (只在缺失时写入会把 info 冻结在页面首次聚焦时的状态)
            if (seed != null && (fromFollowed || subjectId !in infoCache)) infoCache[subjectId] = seed
            heroTarget = TvHeroTarget(subjectId, title, fromFollowed, coverUrl, neighbors)
            // 供返回本页时做初值, 见 TvExplorationLastHero.target
            TvExplorationLastHero.target = heroTarget
        }
    // 焦点在行尾「更多」卡上时背景与文字换成按下去接着推荐的依据: 种子行是种子那部 (这一行就是按它推的), 别的组是藏在「更多」卡底下的
    // 下一部 (按下去第一个露出来); 文字里多一行「按确定推荐更多…」(见 TvHeroTarget.more). 封面 (TMDB 横版图都没有时的背景) 是种子 / 下一部的
    // 竖版封面. 两样都没有 (旧批次) 时背景留在刚才那张
    val focusMoreHero: (TvRecRow) -> Unit = { row ->
        val group = row.group
        val seed = group.seedSubjectId
        val peek = group.peek
        val target = when {
            seed != null -> TvHeroTarget(seed, group.titleArg.orEmpty(), coverUrl = group.moreImageUrl.orEmpty(), more = group)
            peek != null -> TvHeroTarget(peek.bangumiId, peek.nameCn, coverUrl = peek.imageLarge, more = group)
            else -> null
        }
        if (target != null && target != heroTarget) {
            heroTarget = target
            TvExplorationLastHero.target = target
        }
    }
    // 进详情页: 先等目标页首屏的材料备齐再跳, 备不齐则最多等 [TV_NAV_READY_BUDGET].
    //
    // 这是 Android 官方 postponeEnterTransition 的思路 —— 把等待挪到**跳转之前**.
    // 原先点下即跳, 于是详情页拿 Placeholder 状态开局 (10-foot 变体的占位就是一个居中转圈的
    // 空页), 等 SubjectDetailsStateLoader 首次发射才整页换成真布局, backdrop 再单独淡进来:
    // 一次点击看到三段先后到达的画面, 每段长度还随磁盘/网络抖动. 用户的原话是"总是在急着等加载,
    // 每次等待时间都很短但不一样长".
    //
    // 门控条件就是本页聚焦时那套预取的产物 (卡片聚焦即开拉, 见上面的 hero 加载 effect):
    // - infoCache: 条目信息已进仓库缓存 -> 详情页那次 create() 首帧就能本地命中, 转圈窗口缩到
    //   一两帧, 恰好落在入场淡入 alpha≈0 的那几帧里, 看不见;
    // - 服务层 backdrop 热表: 背景图 URL 已解析 -> 详情页 peekBackdropUrl 同步拿到, Hero 首帧即有图.
    // 常见情形 (焦点在卡上停过一下) 两者早就齐了, 门是 0ms, 手感与从前一致.
    //
    // 预算故意压在 500ms 以内: 这段时间页面上没有任何反馈 (卡片仍是聚焦态), 再长就会被读成
    // "遥控器没响应". 超预算即按老路跳转, 不比从前差.
    // **闸门要一直关到本页离开屏幕为止, 不是只关到导航发出为止**: 导航发出后本页还要在
    // 转场动画里活 [CROSSFADE_DURATION] 那么久, 期间它仍在组合、仍在收按键 —— 只锁到
    // 导航发出的话, 常见情形 (预取已就绪, 门是 0ms) 下第二次确认键正好落在这段窗口里,
    // 于是连进两层, 返回要按两下. [TV_NAV_LOCK_MILLIS] 就是覆盖这段窗口的.
    //
    // 用定时解锁而不是"永不解锁": 本页正常会随导航退出组合, remember 一并丢弃, 返回时是
    // 全新的一份; 万一没退出 (导航被拒等), 定时解锁能自愈, 不至于整页再也点不动.
    var navLocked by remember { mutableStateOf(false) }
    fun lockNavigationForTransition() {
        navLocked = true
    }
    // 解锁计时必须从**导航真正发出**那一刻起算, 不是从按键那一刻: navigateToSubject 按下之后
    // 还要先过一道最长 [TV_NAV_READY_BUDGET] 的门控 (这段时间屏幕上没有任何反馈, 正是用户会
    // 补按一次确认的时候). 从按键起算的话, 门吃满时锁会比本页的退场动画先到期, 尾部漏出一截
    // "本页仍在组合、仍在收按键, 锁却已经开了"的窗口 —— 与这把锁要消除的现象是同一个.
    fun unlockNavigationAfterTransition() {
        scope.launch {
            delay(TV_NAV_LOCK_MILLIS)
            navLocked = false
        }
    }
    // 门控中 (按了确认、导航还没发出) 的那次进入; 期间按返回 = 反悔, 取消它 (见下方 BackHandler)
    var pendingNav by remember { mutableStateOf<Job?>(null) }
    // 进了详情页马上又退回来: 转场还没走完, 本页没被移出组合, 锁也还没到期 —— 回到栈顶就解锁, 不然回来后一时点不动卡片.
    // 导航发出后本页收到的确认键由条目装饰器吞掉 (不在栈顶), 锁在那之后本来就不再起作用
    val pageForeground = LocalPageIsForeground.current
    LaunchedEffect(pageForeground) {
        snapshotFlow { pageForeground.value }.collect { if (it && pendingNav == null) navLocked = false }
    }
    val navigateToSubject: (subjectId: Int, name: String, cover: String, source: String) -> Unit =
        { subjectId, name, cover, source ->
            if (!navLocked) {
                Analytics.recordEvent(SubjectEnter) {
                    put("source", source)
                    put("subject_id", subjectId)
                }
                lockNavigationForTransition()
                pendingNav = scope.launch {
                    withTimeoutOrNull(TV_NAV_READY_BUDGET) {
                        // tvHeroBackdropReady 读的是服务层热表 (快照可观察), 预取一落表这里就放行
                        snapshotFlow {
                            infoCache[subjectId] != null && tmdb.tvHeroBackdropReady(subjectId)
                        }.first { it }
                    }
                    pendingNav = null
                    navigator.navigateSubjectDetails(
                        subjectId = subjectId,
                        placeholder = SubjectDetailPlaceholder(id = subjectId, name = name, coverUrl = cover),
                    )
                    unlockNavigationAfterTransition()
                }
            }
        }
    // 立即观看: 直接进播放页 —— 有观看进度接着播下一集, 没有则从第一集开始;
    // 分集信息尚未加载到 (聚焦后异步拉取中) 时退化为进详情页, 保证点击总有响应
    val navigateToPlay: (subjectId: Int, name: String, cover: String, source: String) -> Unit =
        { subjectId, name, cover, source ->
            val info = infoCache[subjectId]
            val episodeId = info?.progressInfo?.nextEpisodeIdToPlay
                ?: info?.episodes?.firstOrNull()?.episodeId
            if (episodeId != null) {
                // 同 navigateToSubject: 转场期间本页还在收按键, 不锁就会连进两层
                if (!navLocked) {
                    Analytics.recordEvent(SubjectEnter) {
                        put("source", source)
                        put("subject_id", subjectId)
                    }
                    lockNavigationForTransition()
                    navigator.navigateEpisodeDetails(subjectId, episodeId)
                    // 这条路没有门控, 按键即导航, 锁的起点与转场起点本就重合
                    unlockNavigationAfterTransition()
                }
            } else {
                navigateToSubject(subjectId, name, cover, source)
            }
        }
    // 卡片长按弹出的收藏下拉 (复用详情页收藏按钮的 EditCollectionTypeDropDown). 当前收藏状态取自
    // infoCache (聚焦时异步拉取的 SubjectCollectionInfo), 未就绪则按未收藏处理.
    // remember: 工厂是 TvExplorationNativeWall 的参数, 每次重组换新实例的话它的参数就不相等、跳不过,
    // 页面 body 每重组一次它跟着重组一次. 另外三页 (追番/搜索/时间表) 也是 remember 的
    val collectionMenuFor: (Int) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit = remember {
        { subjectId ->
            { expanded, onDismiss ->
                EditCollectionTypeDropDown(
                    currentType = infoCache[subjectId]?.collectionType ?: UnifiedCollectionType.NOT_COLLECTED,
                    expanded = expanded,
                    onDismissRequest = onDismiss,
                    onClick = { action ->
                        scope.launch {
                            runCatching { setCollectionTypeUseCase(subjectId, action.type) }
                                .onFailure { toaster.showLoadError(LoadError.fromException(it)) }
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

    // ------------------------------------------------------------------
    // 轮播 (hero) 状态
    // ------------------------------------------------------------------
    // 最高热度是 Hero 轮播 (无卡片行): 两枚操作按钮 (立即观看 / 时间表) + 不可聚焦的轮播
    // 指示器. 焦点在按钮上时左右键手动切换轮播 (第一个条目按左不翻, 交给侧边栏, 见 nativeListener);
    // 用户静止一段时间后自动轮播下一个.
    val trending = state.trendingSubjectInfoPager
    val carouselSize = minOf(trending.itemCount, TV_CAROUSEL_MAX_DOTS)
    var carouselIndex by rememberSaveable { mutableIntStateOf(0) }
    // 每次用户手动切换 +1, 用作自动轮播 LaunchedEffect 的 key: 手动操作即重置计时
    var carouselInteraction by remember { mutableIntStateOf(0) }
    // lambda: 只有两个事件回调用得到它 (hero 的"立即观看"与播放键), 在 body 里算的话
    // carouselIndex 的读记在本函数身上 —— 自动轮播每 [TV_CAROUSEL_AUTO_ADVANCE_MILLIS] 推进一次,
    // 于是页面连着卡片区周期性整体重组. 分页取数由下面那条 LaunchedEffect 触发, 不靠这里.
    val carouselItem = {
        if (carouselSize > 0) trending[carouselIndex.coerceIn(0, carouselSize - 1)] else null
    }
    // 这一次换页是不是自动轮播推进的: 自动换页文字用放慢的过渡, 按键翻页跟卡片行一个节奏 (用户 2026-09-10)
    var carouselAutoAdvanced by remember { mutableStateOf(false) }
    val switchCarousel: (Int) -> Unit = { delta ->
        if (carouselSize > 0) {
            carouselAutoAdvanced = false
            carouselIndex = ((carouselIndex + delta) % carouselSize + carouselSize) % carouselSize
            carouselInteraction++
        }
    }

    // ------------------------------------------------------------------
    // 行结构 (数据驱动): 继续观看一行 (若有) + 推荐 N 行.
    // ------------------------------------------------------------------
    // 两个分页实例挂在 ViewModel 上 (见 ExplorationPageState), 跨导航活着: 现收的话每次返回都
    // 要一百多毫秒才把缓存好的数据 present 出来, 那段空窗里"继续观看"整行不存在, 下面各行整体上移,
    // 返回时恢复的列表停位就对不上行.
    val followedItems = state.followedSubjectsPager
    // 推荐按组画: 一组一行, 各有标题与来源 (见 RecommendationGroupKind). 组的条数不固定
    // (种子那一组最多 9 条, /recs 的上限), 所以行容量必须问组要, 不能按固定行宽切平铺下标.
    // 没登录 / 没有收藏时的「推荐」(FEED) 是一组两百条, 切成多行、只有首行带标题, 见 TvRecRow
    val recGroups by state.recommendationGroups.collectAsStateWithLifecycle()
    val recFlat = remember(recGroups) { recGroups.flatMap { it.items } }
    // 正在「更多」的组: 行尾那张卡画成加载中 (见 rememberTvExplorationNativeItems)
    val extendingGroups by state.extendingRecommendationGroups.collectAsStateWithLifecycle()
    // 装完 / 登录后第一次进页时推荐区是空的, 而一次重算要十几秒 (二十来个请求) —— 不说一声的话
    // 新用户看到的就是一片空白, 不知道这儿本来该有七行东西. 只在**真的空着**时提示:
    // 已经有内容时的后台重算 (TTL 到期、画像变了) 不该打扰人.
    val recRefreshing by state.recommendationsRefreshing.collectAsStateWithLifecycle()
    val recLoadingHint = stringResource(Lang.exploration_rec_loading)
    var recEmptyHintShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(recRefreshing, recGroups.isEmpty()) {
        if (recRefreshing && recGroups.isEmpty() && !recEmptyHintShown) {
            recEmptyHintShown = true
            toaster.toast(recLoadingHint)
        }
    }
    // 未登录的推荐 (一整组 FEED) 按屏上完整放得下的张数切行: 一行正好排满、不横向溢出, 往下多排几行;
    // 登录后的分组一组一条横滑行 (见 tvRecRowsOf)
    val recRows = remember(recGroups, wallColumns) { tvRecRowsOf(recGroups, wallColumns) }
    val hasFollowed = followedItems.itemCount > 0

    // ------------------------------------------------------------------
    // 焦点簿记 + 两个显式落点请求
    // ------------------------------------------------------------------
    // 聚焦卡在行内的下标 (返回键分层规则 / 继续观看播放键用); 行内恢复用各行自己保存的下标
    var focusedCardIndex by remember { mutableIntStateOf(0) }
    var cardAreaHasFocus by remember { mutableStateOf(false) }
    // focusedRowKey 是**换行就变**的热状态, 直读会把整个页面 body 记成它的订阅者 —— 每按一次
    // 上/下键整页重跑一遍. 报告者弱机上"上下键 127-140ms、左右键 63ms"的差正出在这里
    // (2026-09-19; 左右换卡只动 hero 那一路, 早就是 provider 版了). body 里只要它的**派生结论**, 收窄成布尔:
    val heroExpanded by remember { derivedStateOf { focusedRowKey == null } }

    // 显式落点请求 (进页恢复 / 返回键分层 / 回到主界面; 其余焦点移动由原生视图自己走), 由 TvExplorationNativeWall
    // 转给原生视图送焦. 实例身份比较: 连发同参请求也会再送一次.
    var cardFocusRequest by remember { mutableStateOf<TvCardFocusRequest?>(null) }
    // hero 落点请求. **同样按实例身份比较** (见 [TvHeroFocusRequest]): 直接存枚举的话, 请求没
    // 落地时值不变 → 转发的快照流收不到新值 → 同一颗按钮再请求多少次都不会重新送焦.
    var heroFocusRequest by remember { mutableStateOf<TvHeroFocusRequest?>(null) }
    // 焦点最后停在哪颗 hero 按钮上 (跨导航保存): 从别的页回来、页面外进来时回到它, 而不是一律回主按钮 ——
    // 用户在「新番时间表」上进去, 回来焦点却在「立即观看」, 顺手一按确认就进了轮播那部的详情页
    var lastHeroButton by rememberSaveable { mutableStateOf(TvHeroFocusButton.PRIMARY) }

    // 卡片聚焦的簿记入口. **落点请求进行中时只认目标行写进来的**: 恢复目标的那一行还没排出来
    // 的那几帧里, 焦点可能先落进别的行 (全局兜底等), 那张卡的聚焦回调一旦改写
    // focusedRowKey, 恢复目标就没了 —— 真机症状是连进两层详情页再一路返回, 焦点停在推荐区
    // 不回「继续观看」, 数据回得快时又是好的.
    // 只挡"别的行", 不挡目标行: 请求到位那一下正是由目标行的卡片报上来的.
    val recordFocusedCard: (String, Int) -> Unit = { rowKey, index ->
        val pendingRow = cardFocusRequest?.rowKey
        if (pendingRow == null || pendingRow == rowKey) {
            focusedRowKey = rowKey
            focusedCardIndex = index
        }
    }

    // **数据在焦点底下换了内容时 hero 要跟着换**.
    //
    // 推荐行的卡片是按**下标**绑定的 (原生行卡片的稳定 id 按下标, 内容按下标去 recFlat 里取, 见 TvNativeExploreItem.Row),
    // 所以推荐重算落库时持焦的那张卡原地换成另一部作品 —— 焦点没动过, 聚焦回调也就不会再报一次,
    // 于是满屏卡片全换了而 hero 还停在旧那部.
    //
    // 按下标是刻意的: 按条目 id 的话, 重算会把正持焦的那张卡换掉, 焦点跟着丢.
    //
    // 聚焦位置走 snapshotFlow 而不是写进 key: focusedRowKey / focusedCardIndex 是每按一次
    // 方向键就变的热状态, 作 key 等于把整页 body 订阅上去 (同 body 里其它几处收窄).
    LaunchedEffect(recFlat, recRows) {
        snapshotFlow { focusedRowKey to focusedCardIndex }.collect { (rowKey, cardIndex) ->
            val recRow = rowKey
                ?.removePrefix(TV_REC_ROW_KEY_PREFIX)?.toIntOrNull()
                ?.takeIf { it in recRows.indices }
                ?: return@collect
            val row = recRows[recRow]
            if (cardIndex >= row.size) {
                if (row.group.extendable) {
                    focusMoreHero(row)
                } else if (row.size > 0) {
                    // 焦点停在行尾「更多」卡上而它收起了 (再也接不出新的): 挪到这一行最后一张, 不让焦点悬空
                    cardFocusRequest = TvCardFocusRequest(rowKey, cardIndex = row.size - 1)
                }
                return@collect
            }
            val item = recFlat.getOrNull(row.start + cardIndex) ?: return@collect
            // 已经是它了就别动: 每次重组都重报一遍会把 hero 媒体流水线的连发合并打乱. 按「更多」接出来的第一张就是刚才 hero 上的下一部,
            // 同一部也要重报: 去掉「按确定推荐更多」那一行
            if (item.bangumiId == heroTarget?.subjectId && heroTarget?.more == null) return@collect
            onFocusItem(
                item.bangumiId, item.nameCn, null, false, item.imageLarge,
                tvRecNeighborsOf(recFlat, recRows, recRow, cardIndex),
            )
        }
    }

    // 整屏底色 (主壳画, 见 TvPosterWallTone): 卡片墙是深灰, hero 态整屏压成 hero 的底 (近黑); 热门轮播只把自己那块铺成 hero 的底 ——
    // 登记一条分界线, 跟着轮播背景图的下缘走、随列表滚动 (线以上黑, 往下一段渐变到深灰): 轮播态在第一行海报后面, 第一行居中时正好在它的
    // 组标题上沿, 再往下翻随轮播滚出屏, 不用整屏换色. hero 态里照样登记 (那时整屏已黑), 进出时线以上一直是黑的
    val wallTone = LocalTvPosterWallTone.current
    TvPosterWallToneSource(
        wallTone,
        // 黑度与分界线由原生视图逐帧写进来 (见 TvExplorationNativeState)
        amount = { nativeState.tone },
        heroBottom = { nativeState.splitY },
    )
    // 返回键的远跳走 TvScrollSpring.Far (Apple TV 页面滚动那一组, 逐格那条 spring 跳十来行一闪而过、还掉帧).
    // wallFarJumpRow: 返回键分层的目标行 (回本行首卡 / 跳回组首行) —— 转发落点请求时据此让原生视图按远跳滚过去 (见 TvExplorationNativeWall),
    // 焦点落到卡上或回到轮播按钮就清掉. 只在处理按键 / 回调 / 协程里读写, 不进组合
    var wallFarJumpRow by remember { mutableStateOf<String?>(null) }

    // 进页/返回的初始焦点: 曾在某行 -> 恢复该行 (cardIndex=-1: 行自己跨导航保存的聚焦卡);
    // 曾在 hero -> 上次停的那颗 hero 按钮 (首次进入 = 主按钮). 此后页面外进来的焦点走页面根 onEnter (见下).
    // 恢复请求是否已派出. 见下方返回键分层: 本效应调度前那一两帧 cardFocusRequest 还是 null,
    // 那时按返回会被放行成"退出应用确认" —— 曾在某行时用它把这一段也算作焦点在卡片区
    var entryRestoreDispatched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val saved = focusedRowKey
        if (saved == null) {
            heroFocusRequest = TvHeroFocusRequest(lastHeroButton)
            entryRestoreDispatched = true
            return@LaunchedEffect
        }
        val restore = TvCardFocusRequest(saved, cardIndex = -1)
        cardFocusRequest = restore
        entryRestoreDispatched = true
        // 恢复那一行要等它的数据: 进程被回收后重开 (focusedRowKey 是 rememberSaveable), 冷启动常常好几秒都等不到,
        // 那一行也可能已经没了. 等不来就回轮播态、送焦到 hero 按钮 —— 不能一直悬着, 否则全局兜底一出手就按 Enter
        // 从最左边找, 焦点落进侧边栏, 数据到了也不回来 (2026-09-26 装完直接打开复现).
        // 期间用户按过键 / 恢复已到位时请求已被换掉或清空, 不动.
        delay(TV_ENTRY_CARD_RESTORE_TIMEOUT)
        if (cardFocusRequest === restore) {
            cardFocusRequest = null
            focusedRowKey = null
            heroFocusRequest = TvHeroFocusRequest(lastHeroButton)
        }
    }
    // 页面自己在派落点 (进页恢复 / 返回键分层 / 回到主界面) 期间让全局兜底等一等: 它只让 15 帧就出手, 按 Enter 从最左边
    // 找, 有侧边栏的页面上落点就是侧边栏. 兜底那边有上限 (3 秒), 这里的请求卡住也照样兜得住. 见 TvFocusRestoreGate.
    // 只在本页在前台时登记: 那道闸是全局的, 本页被详情页盖着时挂着的旧请求不该让别的页面丢焦点时也多等.
    // derivedStateOf: 两个请求在 body 里只读成一个布尔, 翻转才重组
    val restoringFocus by remember { derivedStateOf { cardFocusRequest != null || heroFocusRequest != null } }
    // 在自己的小作用域里读: 落点请求每按一下上下键设一次、清一次, 这个布尔跟着翻两次, 在 body 里读就是整页重组两次
    TvFocusRestoreClaimWhen { restoringFocus && pageForeground.value }
    // 「继续观看」整行迟到 (分页) 或在详情页改过观看进度后刷新时整行短暂消失又回来: 行回来的
    // 时候, 上面那次恢复请求往往已经作废了, 而焦点这会儿停在推荐区. 行一出现就补发一次.
    LaunchedEffect(hasFollowed) {
        if (hasFollowed && focusedRowKey == TV_FOLLOWED_ROW_KEY && cardFocusRequest == null) {
            cardFocusRequest = TvCardFocusRequest(TV_FOLLOWED_ROW_KEY, cardIndex = -1)
        }
    }
    LaunchedEffect(heroExpanded) {
        if (!heroExpanded) return@LaunchedEffect
        // 回卡片墙与列表回顶由原生视图在焦点落到轮播按钮那一刻自己做; 没跑完的远跳作废
        wallFarJumpRow = null
    }

    // TRENDING 时轮播条目驱动 hero (标题即时, 评分/连载/简介/backdrop 异步跟上).
    // carouselIndex 在 snapshotFlow 里读, 不作 key: key 是组合期读取, 自动轮播每 6s 推进一次就让整页 body 连卡片区
    // 重跑一遍 (理由同上面的 carouselItem; 2026-09-13 审查). 语义不变: 下标变一次跑一次 (含首次)
    val currentTrending by rememberUpdatedState(trending)
    val currentOnFocusItem by rememberUpdatedState(onFocusItem)
    LaunchedEffect(heroExpanded, carouselSize) {
        if (!heroExpanded || carouselSize <= 0) return@LaunchedEffect
        snapshotFlow { carouselIndex }.collect { index ->
            val pager = currentTrending
            // 下一项当作"邻居"传下去: URL 预取与图片预热共用同一条路 (见 TvHeroNeighbors).
            // 轮播是全页唯一 100% 确定的目标 —— 6 秒后必然轮到它, 提前量足足一整轮, 而且
            // 换图时用户根本没在操作, 等待全落在眼里
            val nextCarousel = if (carouselSize > 1) {
                pager.peekOrNull((index + 1) % carouselSize)?.bangumiId
            } else {
                null
            }
            pager[index.coerceIn(0, carouselSize - 1)]?.let {
                currentOnFocusItem(
                    it.bangumiId, it.nameCn, null, false, it.imageLarge,
                    // 轮播条目走整部 backdrop, 不是剧照
                    TvHeroNeighbors(singleStep = listOfNotNull(nextCarousel?.let(::TvHeroNeighbor))),
                )
                // 卡片墙上的背景与文字只跟轮播走 (见 carouselHeroTarget)
                carouselHeroTarget = heroTarget
                TvExplorationLastHero.carouselTarget = heroTarget
            }
            // 预取**下一项**: 这是全页唯一确定性的目标 —— 6 秒后必然轮到它, 提前量足足一整轮.
            // 不预取的话每次自动轮播换图都是现拉三跳, 用户什么都没做就在等图.
            // 后台槽会等当前这项的前台请求跑完才开工 (见 TvHeroPrefetch)
            if (carouselSize > 1) {
                val nextIndex = (index + 1) % carouselSize
                pager.peekOrNull(nextIndex)?.let { next ->
                    TvHeroPrefetch.background(next.bangumiId) {
                        resolveTvHeroMedia(next.bangumiId, collectionRepo, tmdb)
                    }
                }
            }
        }
    }
    // 冷启动时 hero 文字不陪媒体链等: 轮播这几部在本地库里有的 (之前浏览过, 或换人时垫过的), 一拿到列表就读进进程缓存,
    // 聚焦的那一部直接显示. 只读本地、不发请求 —— 本地没有的照旧由解析链去取
    LaunchedEffect(carouselSize) {
        for (offset in 0 until carouselSize) {
            val item = currentTrending.peekOrNull(offset) ?: continue
            if (TvHeroMediaCache.peekSubjectInfo(item.bangumiId) != null) continue
            val info = collectionRepo.subjectCollectionOffline(item.bangumiId) ?: continue
            TvHeroMediaCache.putSubjectInfo(item.bangumiId, info)
            // 页面这张表只写聚焦的那一个 (理由见 afterResolve)
            if (heroTarget?.subjectId == item.bangumiId && item.bangumiId !in infoCache) {
                infoCache[item.bangumiId] = info
            }
        }
    }
    // 轮播条目的背景图下载进磁盘 (整机共用的图片缓存, 换人重启后也在). 原图档不投机下载, 理由同邻居预热
    val prefetchCarouselImage: (Int) -> Unit = { subjectId ->
        tmdb.tvHeroBackdropUrl(subjectId, fullVisualEffects = false, preferNextEpisodeStill = false)
            ?.takeUnless { it.isOriginalSizeTmdbUrl() }
            ?.let { TvHeroImagePrefetch.prefetch(it, sketch, platformContext) }
    }
    // 整轮轮播按顺序预取 (背景图匹配有的要按十几个别名逐个搜 TMDB, 冷启动一部就是三四秒): 一部做完再提交下一部,
    // 排队里始终只有一个, 照样给前台让路, 也不挤掉卡片导航的邻居预取. 首次启动时本页垫在引导的登录层 / 选人页下面
    // 就开始跑, 用户进来时整轮多半已经就绪. 已解析过的直接命中缓存, 不发请求. 解析完连图一起落盘
    LaunchedEffect(carouselSize) {
        for (offset in 1 until carouselSize) {
            val item = currentTrending.peekOrNull(offset) ?: continue
            TvHeroPrefetch.backgroundAndAwait(item.bangumiId) {
                resolveTvHeroMedia(item.bangumiId, collectionRepo, tmdb)
            }
            prefetchCarouselImage(item.bangumiId)
        }
    }
    // 热度榜过期时轮播先放手上那份 (见 TrendsRepository.firstPage), 后台取到的新一页在这里趁空闲预热: 条目信息进本地库,
    // 背景图地址与图片进整机缓存 —— 下次打开应用时轮播直接就绪. 走同一个后台槽, 给前台让路
    LaunchedEffect(Unit) {
        trendsRepository.firstPageRefreshed.collect { fresh ->
            for (item in fresh.subjects.take(TrendsRepository.HERO_CAROUSEL_SIZE)) {
                TvHeroPrefetch.backgroundAndAwait(item.bangumiId) {
                    resolveTvHeroMedia(item.bangumiId, collectionRepo, tmdb)
                }
                prefetchCarouselImage(item.bangumiId)
            }
        }
    }
    // 自动轮播: 仅在轮播态 (焦点在 hero 按钮上) 推进; carouselInteraction 变化 (手动切换) 会重启本效果, 重置计时
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(carouselSize, heroExpanded, carouselInteraction) {
        if (!heroExpanded || carouselSize <= 1) return@LaunchedEffect
        // 本页被放大进来的详情页盖着 (列表页常驻组合、不画, 见 TvZoomStackScene), 或窗口没有焦点 (首次引导的登录层、
        // 退出面板这类对话框盖在上面, 或应用在后台) 时不计时: 换图会连带 hero 媒体解析 / 预取 / 解码大图 / 重组, 全是白做.
        // 回到前台后**重新计时** —— 不能"等满 6 秒再等前台", 那样停久了一回来就立刻换图: 缩回刚落地画面就跳, hero 地址也对不上缩回那张
        // (撤层要等到就绪超时). 2026-09-15 审查
        snapshotFlow { pageForeground.value && windowInfo.isWindowFocused }.collectLatest { active ->
            if (!active) return@collectLatest
            while (true) {
                delay(TV_CAROUSEL_AUTO_ADVANCE_MILLIS)
                carouselAutoAdvanced = true
                carouselIndex = (carouselIndex + 1) % carouselSize
            }
        }
    }

    // 返回键分层规则: 不在行首卡 -> 回本行首卡; 区块内非首行的行首卡 -> 回区块首行首卡;
    // 区块首行的行首卡 -> hero 主按钮; hero 主按钮上轮播不在第一项 -> 回第一项
    // (最后这层在 [TvHeroCarouselBackHandler], 单独注册).
    //
    // **不能只看 cardAreaHasFocus**: 从详情页/播放器返回本页时它要等目标行排出来、焦点
    // 请求到位才变 true (搜索页实测 300ms~1.9s). 这段窗口里本处判 false, 返回键就被放行到
    // 上层 —— 本页是主页 tab, 那等于**直接弹出退出应用确认** (与 issue #2 同一个根因).
    // cardFocusRequest 非空 = 进页恢复/分层跳转的落点解析还在进行, 视同焦点已在卡片区;
    // 再加恢复请求派出前那一两帧 (仅"曾在某行"时, 见 entryRestoreDispatched).
    // 注意轮播态不受影响: 那时 focusedRowKey 为 null、cardAreaHasFocus 为 false,
    // 三个条件全不成立, 返回照旧放行去退出应用.
    // 收窄成一个布尔 (重组规则 2): 这里读的四个量里 focusedRowKey 与 cardFocusRequest 都是热的,
    // 直接写在 enabled 表达式里等于把整页订阅上去.
    val backEnabled by remember {
        derivedStateOf {
            val restoringToCards = !entryRestoreDispatched && focusedRowKey != null
            !navLocked && (cardAreaHasFocus || cardFocusRequest != null || restoringToCards)
        }
    }
    BackHandler(enabled = backEnabled) {
        // 上一下返回的远跳还在滚 (焦点没落位) 时又按了返回: 那一步先当场落地 (行一步挪到目标卡并照落地记下, 见
        // TvNativeExploreView.settleFarJump), 再按它的落点走下一层 —— 同一下里做完两步. 下一层按请求的目标算: 焦点此刻还停在
        // 出发的那张 / 停放在原生视图上, 按它算会再发一次同样的回首卡, 连按两下就回不到轮播. cardIndex -1 (行自己记的那张) 当作不在行首
        nativeState.view?.settleFarJump()
        val pending = cardFocusRequest
        val key = pending?.rowKey ?: focusedRowKey
        val cardIndex = if (pending != null) pending.cardIndex.let { if (it < 0) 1 else it } else focusedCardIndex
        val sectionFirstKey = if (key == TV_FOLLOWED_ROW_KEY) TV_FOLLOWED_ROW_KEY else tvRecRowKey(0)
        when {
            key == null -> {
                heroFocusRequest = TvHeroFocusRequest(TvHeroFocusButton.PRIMARY)
            }

            // 回本行首卡同样按远跳走 (同追番页返回回首卡): 途中按方向键当场落到行首, 按确认排队, 落地后点它
            cardIndex > 0 -> {
                wallFarJumpRow = key
                cardFocusRequest = TvCardFocusRequest(key, cardIndex = 0)
            }

            key != sectionFirstKey -> {
                wallFarJumpRow = sectionFirstKey
                cardFocusRequest = TvCardFocusRequest(sectionFirstKey, cardIndex = 0)
            }

            else -> {
                // 挂着的回首卡请求作废, 否则它落地时把焦点又拉回卡片 (后落地的赢)
                cardFocusRequest = null
                heroFocusRequest = TvHeroFocusRequest(TvHeroFocusButton.PRIMARY)
            }
        }
    }
    // 轮播态的最后一层 (注册在卡片那条之后、pendingNav 那条之前, 三者的 enabled 互斥).
    TvHeroCarouselBackHandler(
        enabled = { !navLocked && !cardAreaHasFocus && cardFocusRequest == null && carouselIndex > 0 },
        // 走 switchCarousel 而不是直接赋 0: 它顺带重置自动轮播计时、并把这次换页标记成
        // 按键节奏 (自动换页的文字过渡更慢, 见 carouselAutoAdvanced)
        onBack = { switchCarousel(-carouselIndex) },
    )
    // 按了确认、导航还没发出 (门控在等首屏材料, 最多 TV_NAV_READY_BUDGET) 时按返回 = 反悔: 取消这次进入, 留在本页.
    // 必须注册在上面那条之后 —— BackHandler 走 OnBackPressedDispatcher, 后注册的先拿到.
    // 导航发出之后本页不在栈顶, 本页与主壳的返回处理都不生效 (见 BackHandler), 返回直接交给导航, 退掉正在进入的详情页.
    // (原先这里在整个转场窗口吞掉返回, 防它被本页或主壳吃掉, 代价是进详情页后要等 ~1s 才退得出去)
    BackHandler(enabled = pendingNav != null) {
        pendingNav?.cancel()
        pendingNav = null
        navLocked = false
    }
    // hero 态: 按返回回卡片墙 (注册在最后, 先拿到). 导航已按下未发出时让给上面那条 (反悔)
    BackHandler(enabled = nativeState.heroActive && pendingNav == null) {
        nativeState.exitHero()
    }
    // 长按返回本页不单独注册: 根部兜底统一弹快捷菜单 (回到主界面 / 回到·关闭正在播放 /
    // 刷新本页 / 退出应用, 见 TvQuickActionMenu). 菜单的「回到主界面」落地后把焦点送上轮播
    // 主按钮 —— 发起方可能在别的 tab / 别的目的地 (那一刻本页还没组合出来), 只能留一个标志,
    // 这里看到就消费. heroFocusRequest 由 TvExplorationNativeWall 转给原生视图送焦, 焦点落到按钮上时
    // 原生视图自己回卡片墙、列表回顶
    val backLongPressHost = LocalTvBackLongPressHost.current
    if (backLongPressHost != null) {
        LaunchedEffect(backLongPressHost) {
            snapshotFlow { backLongPressHost.pendingHomeFocus }.collect { pending ->
                if (pending) {
                    backLongPressHost.pendingHomeFocus = false
                    // **必须先撤掉进页恢复的卡片落点**: 从详情页点「回到主界面」时本页正在同一
                    // 帧组合, 上面那条恢复效应 (组合在前, 因此先跑) 已经派出「回到上次那张卡」
                    // 的请求 —— 两个请求打架时后落地的卡片赢, 焦点停在进详情页时的那张卡上,
                    // 与「回主界面」的语义相反 (真机: 焦点在卡上而不是轮播主按钮).
                    // focusedRowKey 置空 = 回轮播态 (列表随之滚回顶部), 正是这个动作的语义.
                    focusedRowKey = null
                    cardFocusRequest = null
                    heroFocusRequest = TvHeroFocusRequest(TvHeroFocusButton.PRIMARY)
                }
            }
        }
    }
    // 快捷菜单「刷新本页」= 强制重拉"在看"(继续观看栏). 推荐流不重拉 —— 换的是推荐结果,
    // 不是"更没更"
    TvPageRefreshHandler { state.refreshFollowedSubjects() }
    // 「换一批」只换推荐; 「继续观看」是任务入口, 不该被换掉
    TvPageShuffleHandler { state.shuffleRecommendations() }

    // 原生海报墙的事件 (见 TvExplorationNativeWall.kt): 焦点簿记、hero 媒体、导航都走上面这一套入口
    // 下标超出这一行 = 行尾「更多」卡, 不是条目 (往后数会数到下一组去)
    val nativeRecItemAt: (String, Int) -> RecommendedSubjectInfo? = { rowKey, index ->
        rowKey.removePrefix(TV_REC_ROW_KEY_PREFIX).toIntOrNull()
            ?.let { recRows.getOrNull(it) }
            ?.takeIf { index in 0 until it.size }
            ?.let { recFlat.getOrNull(it.start + index) }
    }
    val posterConfirm = LocalThemeSettings.current.tvPosterConfirm
    val nativeListener = object : TvNativeExploreListener {
        override fun onCardFocused(rowKey: String, index: Int, column: Int) {
            if (rowKey == TV_FOLLOWED_ROW_KEY) {
                followedItems.peekOrNull(index)?.let {
                    onFocusItem(
                        it.subjectInfo.subjectId,
                        it.subjectInfo.displayName,
                        it.subjectCollectionInfo,
                        true,
                        it.subjectInfo.imageLarge,
                        // 只取本行顺方向 (左边刚来过必然是热的); 整行都是"在看", 邻居走单集剧照那一档
                        TvHeroNeighbors(
                            singleStep = listOfNotNull(
                                followedItems.peekOrNull(index + 1)?.subjectInfo?.subjectId?.let { id -> TvHeroNeighbor(id, true) },
                            ),
                            urlOnly = listOfNotNull(
                                followedItems.peekOrNull(index + 2)?.subjectInfo?.subjectId?.let { id -> TvHeroNeighbor(id, true) },
                            ),
                        ),
                    )
                }
            } else {
                val recRow = rowKey.removePrefix(TV_REC_ROW_KEY_PREFIX).toIntOrNull()
                if (recRow != null && recRow in recRows.indices) {
                    val row = recRows[recRow]
                    if (index >= row.size) {
                        focusMoreHero(row)
                    } else {
                        recFlat.getOrNull(row.start + index)?.let {
                            onFocusItem(it.bangumiId, it.nameCn, null, false, it.imageLarge, tvRecNeighborsOf(recFlat, recRows, recRow, index))
                        }
                    }
                }
            }
            recordFocusedCard(rowKey, index)
            if (cardFocusRequest?.rowKey == rowKey) cardFocusRequest = null
            // 远跳落地 (或焦点已换到别的行) 就清掉
            wallFarJumpRow = null
            nativeState.capture()
        }

        override fun onCardClick(rowKey: String, index: Int) {
            // 「海报上按确定」选直接播放: 同播放键 (见 navigateToPlay); 另两档进详情页
            val play = posterConfirm == TvPosterConfirmAction.Play
            if (rowKey == TV_FOLLOWED_ROW_KEY) {
                followedItems.peekOrNull(index)?.subjectInfo?.let {
                    if (play) {
                        navigateToPlay(it.subjectId, it.displayName, it.imageLarge, "home_followed_play")
                    } else {
                        navigateToSubject(it.subjectId, it.displayName, it.imageLarge, "home_followed")
                    }
                }
            } else {
                nativeRecItemAt(rowKey, index)?.let {
                    if (play) {
                        navigateToPlay(it.bangumiId, it.nameCn, it.imageLarge, "home_recommendation_play")
                    } else {
                        navigateToSubject(it.bangumiId, it.nameCn, it.imageLarge, "home_recommendation")
                    }
                }
            }
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: AndroidRect) {
            val subjectId = if (rowKey == TV_FOLLOWED_ROW_KEY) {
                followedItems.peekOrNull(index)?.subjectInfo?.subjectId
            } else {
                nativeRecItemAt(rowKey, index)?.bangumiId
            }
            if (subjectId != null) nativeState.menu = subjectId to anchor
        }

        override fun onMoreClick(rowKey: String) {
            val group = rowKey.removePrefix(TV_REC_ROW_KEY_PREFIX).toIntOrNull()
                ?.let { recRows.getOrNull(it) }?.group
                ?.takeIf { it.extendable }
                ?: return
            // 接到的几条追加在「更多」卡原来的位置上: 焦点不动就落在新接的第一张 (卡按下标绑定), 背景跟着换 (见上面按下标补报的那段)
            state.extendRecommendationGroup(group.key)
        }

        override fun onBindCard(rowKey: String, index: Int) {
            // 分页的访问提示: 绑到哪张, 继续观看就往后取到哪 (按下标读一次 followedItems 即向分页报告访问位置)
            if (rowKey == TV_FOLLOWED_ROW_KEY && index in 0 until followedItems.itemCount) followedItems[index]
        }

        override fun onHeroButtonFocused(button: Int) {
            val which = if (button == 1) TvHeroFocusButton.SCHEDULE else TvHeroFocusButton.PRIMARY
            focusedRowKey = null
            lastHeroButton = which
            prefetchSchedule(if (which == TvHeroFocusButton.PRIMARY) TV_EXPLORATION_SCHEDULE_PREFETCH_DELAY_MILLIS else 0)
            if (heroFocusRequest?.button == which) heroFocusRequest = null
            nativeState.capture()
        }

        override fun onHeroButtonClick(button: Int) {
            if (button == 1) {
                navigator.navigateSchedule()
            } else {
                carouselItem()?.let { navigateToSubject(it.bangumiId, it.nameCn, it.imageLarge, "home_trending_detail") }
            }
        }

        override fun onSwitchCarousel(delta: Int): Boolean {
            // 第一个条目按左不翻: 交给侧边栏
            if (delta < 0 && carouselIndex <= 0) return false
            switchCarousel(delta)
            return true
        }

        override fun onExitLeft() {
            railEnter?.requestFocus()
        }

        override fun onHeroActiveChanged(active: Boolean) = Unit

        override fun onCardAreaFocusChanged(hasFocus: Boolean) {
            cardAreaHasFocus = hasFocus
        }

        override fun onToneChanged(tone: Float, splitY: Float) = Unit

        override fun onScrollingChanged(scrolling: Boolean) {
            nativeScrollReporter?.setScrolling(scrolling)
            if (!scrolling) nativeState.capture()
        }
    }
    // 播放键短按: 一份挂在整个原生视图上, 按焦点簿记取目标 (hero 按钮 → 轮播那一部; 继续观看 → 续播; 推荐 → 直接播).
    // 长按不在这里: 播放键长按是全局手势「打开动作面板」, 由根部统一跟踪器认领
    // 动作面板里的「海报墙大小」: 盖在本页上开编辑窗口. 值变了原生墙按新尺寸重建 (TvNativeHost 的 rebuildKey), 打开前焦点在墙上的话
    // 照进页那样送回去: 曾在某行回那一行 (行自己记着停在哪张), 否则回那颗 hero 按钮.
    // 落点在打开那一刻记下: 重建出来的新视图先把焦点落在 hero 按钮上, 它的回调会清掉 focusedRowKey、改写 lastHeroButton
    var wallScaleReturnRow by remember { mutableStateOf<String?>(null) }
    var wallScaleReturnHero by remember { mutableStateOf(TvHeroFocusButton.PRIMARY) }
    TvWallScaleEntry(
        page = TvPosterWallPreviewPage.EXPLORATION,
        wallHasFocus = { nativeState.view?.hasFocus() == true },
        beforeOpen = {
            wallScaleReturnRow = focusedRowKey
            wallScaleReturnHero = lastHeroButton
            nativeState.capture()
        },
        refocus = {
            val row = wallScaleReturnRow
            if (row != null) {
                cardFocusRequest = TvCardFocusRequest(row, cardIndex = -1)
            } else {
                heroFocusRequest = TvHeroFocusRequest(wallScaleReturnHero)
            }
        },
    )
    val nativePlayKeyModifier = tvPlayKeyShortPress(
        onPlay = {
            val rowKey = focusedRowKey
            when {
                !cardAreaHasFocus || rowKey == null -> carouselItem()?.let {
                    navigateToPlay(it.bangumiId, it.nameCn, it.imageLarge, "home_trending_play")
                    true
                } ?: false

                rowKey == TV_FOLLOWED_ROW_KEY -> followedItems.peekOrNull(focusedCardIndex)?.subjectInfo?.let {
                    navigateToPlay(it.subjectId, it.displayName, it.imageLarge, "home_followed_play")
                    true
                } ?: false

                else -> nativeRecItemAt(rowKey, focusedCardIndex)?.let {
                    navigateToPlay(it.bangumiId, it.nameCn, it.imageLarge, "home_recommendation_play")
                    true
                } ?: false
            }
        },
    )

    Box(
        modifier.fillMaxSize()
            // 用户方向/确认键先取消旧的程序化落点; 子节点随后可基于同一次按键登记新落点.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isAutoRepeat != true &&
                    event.key in TV_EXPLORATION_NAV_KEYS
                ) {
                    cardFocusRequest = null
                    heroFocusRequest = null
                }
                false
            }
            // 页面外进来的任何焦点 (侧边栏右键/返回、全局兜底的无方向 requestFocus) 统一在此
            // 改道: 送进原生视图, 由它回上次那张卡 / 上次停的那颗 hero 按钮 (见 TvNativeExploreView.onRequestFocusInDescendants);
            // 送不进去就退回 hero 落点请求.
            .focusProperties {
                onEnter = onEnter@{
                    // 页面自己正在派落点时, 无方向的进组就是那条请求本身 (hero 按钮 / 目标卡的 requestFocus): 放行 ——
                    // 改道会把它截走 (目标行可能还在等数据, 改道去"上次那一行"就失败), 焦点留在页面外的侧边栏上.
                    // 用户按方向键进来的照旧改道.
                    if (requestedFocusDirection == FocusDirection.Enter && restoringFocus) return@onEnter
                    if (nativeState.view?.hasFocus() != true) {
                        val ok = runCatching { nativeState.focusRequester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
                        if (!ok) heroFocusRequest = TvHeroFocusRequest(lastHeroButton)
                    }
                }
            }
            // onEnter 只在**焦点组**节点上生效: 不加这个的话 focusProperties 会落到下面的
            // 每个焦点目标上而不是充当进出边界, 改道根本不触发.
            .focusGroup(),
    ) {
        // 原生海报墙的几何见 tvExplorationWallLayout
        val nativeItems = rememberTvExplorationNativeItems(hasFollowed, followedItems, recRows, recFlat, playHistories, extendingGroups)
        TvExplorationNativeSources(
            state = nativeState,
            heroPipeline = heroPipeline,
            carouselRaw = { carouselHeroTarget },
            carouselDisplay = carouselHeroDisplay,
            carouselText = carouselHeroText,
            cardRaw = { heroTarget },
            cardDisplay = heroDisplay,
            cardText = heroTextDisplay,
            autoAdvanced = { heroExpanded && carouselAutoAdvanced },
            infoCache = infoCache,
            episodeStillCache = episodeStillCache,
            summaryFallbackCache = summaryFallbackCache,
            playHistories = { playHistories },
        )
        TvExplorationNativeWall(
            state = nativeState,
            metrics = wallLayout.metrics,
            cardWidth = wallLayout.cardWidth,
            items = nativeItems,
            carouselCount = carouselSize,
            carouselIndex = { carouselIndex.coerceIn(0, (carouselSize - 1).coerceAtLeast(0)) },
            fadeColor = wallTone?.heroColor ?: AniThemeDefaults.shellBackgroundColor,
            geometry = TV_WALL_BACKDROP_GEOMETRY,
            splitGate = { wallTone?.splitGate() ?: 1f },
            listener = nativeListener,
            cardFocusRequest = { cardFocusRequest },
            heroFocusRequest = { heroFocusRequest },
            farJumpRow = { wallFarJumpRow },
            focusedRowKey = { focusedRowKey },
            focusedCardIndex = { focusedCardIndex },
            menuFor = collectionMenuFor,
            modifier = nativePlayKeyModifier,
        )

        // 推荐区空着、又真的在重算时, 左下角说进行到哪了; 已经有内容时的后台重算不打扰人
        val recProgress by state.recommendationsRefreshProgress.collectAsStateWithLifecycle()
        recProgress?.takeIf { recGroups.isEmpty() }?.let { progress ->
            TvRecommendationRefreshProgress(
                progress,
                // 页面从屏幕左缘铺起 (侧边栏盖在上面, 见 TvMainScreenLayout), 左边让开侧边栏
                Modifier.align(Alignment.BottomStart)
                    .padding(start = TvNavigationRailDefaults.CollapsedWidth + TV_EXPLORATION_START_PAD, bottom = TV_PAGE_HINT_BOTTOM_PAD),
            )
        }
    }
}

/**
 * 推荐第一次重算时读到第几页收藏、完成了几个请求: 一次要十几秒, 只说一句「需要十几秒」的话看不出是在动还是卡住了.
 * 不进列表、不可聚焦 (列表结构与焦点簿记都不受影响).
 */
@Composable
private fun TvRecommendationRefreshProgress(
    progress: RecommendationRefreshProgress,
    modifier: Modifier = Modifier,
) {
    val text = when {
        progress.stage == RecommendationRefreshProgress.Stage.Candidates ->
            stringResource(Lang.exploration_rec_progress_candidates, progress.requestsDone)

        progress.collectionPagesTotal > 0 -> stringResource(
            Lang.exploration_rec_progress_collections,
            progress.collectionPagesDone,
            progress.collectionPagesTotal,
        )

        else -> stringResource(Lang.exploration_rec_progress_collections_start)
    }
    Text(
        text,
        modifier,
        color = tvHeroSecondaryContentColor(),
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * 轮播态下的返回键: 轮播不在第一项时先回第一项, 再按才放行 (本页是主页 tab, 放行即退出应用确认).
 *
 * 给"去侧边栏"留的短路. hero 按钮上的左键只有停在第一项时才去侧边栏 (见 nativeListener 的
 * onSwitchCarousel), 翻到第五项时想去侧边栏得连按五次左键.
 *
 * 单独一个 composable 而不是并进页面那条 BackHandler: [enabled] 里读的 carouselIndex 是热状态
 * (自动轮播每 6s 推进一次), 放在页面 body 里读会让整页跟着周期性重组; 在这里读, 重组就圈在
 * 这一个节点上.
 */
@Composable
private fun TvHeroCarouselBackHandler(enabled: () -> Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled(), onBack = onBack)
}

/** [TvFocusRestoreClaim] 的读取器版: [active] 在这里读, 它翻转时只重组这一小块. */
@Composable
private fun TvFocusRestoreClaimWhen(active: () -> Boolean) {
    TvFocusRestoreClaim(active())
}

/**
 * 显式卡片落点请求 (进页恢复 / 返回键分层 / 回到主界面; 其余焦点移动由原生视图自己走).
 * [rowKey] 是行的稳定键 (不用绝对行号: "继续观看"分页迟到会让推荐行整体位移);
 * [cardIndex] < 0 = 该行自己跨导航保存的上次聚焦卡. 实例身份比较, 同参重发也会再送一次.
 */
internal class TvCardFocusRequest(
    val rowKey: String,
    val cardIndex: Int,
)

/**
 * hero 的两颗按钮作为焦点落点: 返回键回 [PRIMARY] (立即观看); 卡片区顶行按上键回 [SCHEDULE];
 * 进页 / 从别的页回来回上次停的那颗 (首次进入 = [PRIMARY]).
 */
internal enum class TvHeroFocusButton { PRIMARY, SCHEDULE }

/**
 * 显式 hero 落点请求. 包一层只为**实例身份**, 与 [TvCardFocusRequest] 同一个理由: 送焦可能没
 * 落地 (被别的请求抢了焦点 / 目标那一刻还没建好), 而「再请求一次」必须能重新送一次焦.
 *
 * 裸枚举的失败模式 (2026-08-23 真机): 动作面板点「回到主界面」时这个请求被进页恢复的卡片抢了
 * 焦点, 值就一直留在 PRIMARY (只有主按钮真正获焦或用户按方向/确认键才清). 此后在卡片区顶行
 * 按返回 —— 那一步就是 `heroFocusRequest = PRIMARY` —— 是个静默的空赋值, 不会再送一次焦, 返回键
 * 从此完全没反应 (页面 BackHandler 仍开着, 也到不了主壳的退出逻辑).
 */
internal class TvHeroFocusRequest(val button: TvHeroFocusButton)

/**
 * 焦点落到「立即观看」(开屏默认就在这) 之后多久才预取新番时间表: 开屏那一阵探索页自己的请求 (轮播、继续观看、背景图) 正忙,
 * 让开一会儿. 落到「新番时间表」上是立即拉.
 */
private const val TV_EXPLORATION_SCHEDULE_PREFETCH_DELAY_MILLIS = 2_000L

/**
 * 进页恢复"上次那张卡"最多等多久, 等不来就回 hero 按钮 (见进页落点那个 LaunchedEffect).
 * 比全局兜底为页面恢复让位的上限 (3 秒, `FOCUS_FALLBACK_RESTORE_CEILING`) 短一截: 退回 hero 这一发要赶在兜底出手之前落地.
 */
private val TV_ENTRY_CARD_RESTORE_TIMEOUT = 2.5.seconds

internal val TV_EXPLORATION_NAV_KEYS = setOf(
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
    Key.DirectionCenter,
    Key.Enter,
    Key.NumPadEnter,
)

/** 聚焦卡片 → Hero 展示目标 (标题从卡片数据即时取得, 其余异步). */
/**
 * 越界取 null.
 *
 * [LazyPagingItems.peek] 是**直接下标访问** (`itemSnapshotList[index]`), 越界当场抛
 * `IndexOutOfBoundsException` —— 预取邻居天然会算到行尾之外, 必须走这个而不是裸 `peek`.
 */
internal fun <T : Any> LazyPagingItems<T>.peekOrNull(index: Int): T? =
    if (index in 0 until itemCount) peek(index) else null

/**
 * 推荐区第 [recRow] 行第 [localIndex] 张出发的预取目标: 顺方向两格 + 下一行同列.
 *
 * 本页的行区不取反方向: 行内只有左右, 左边是刚走过来的地方, 缓存必然是热的 (网格页不同,
 * 见 [tvGridNeighborsOf]). 不取更远: 要连按三下才到, 那时前两格早就跑完、第三格也已经作为
 * 新的邻居被排上了.
 */
internal fun tvRecNeighborsOf(
    items: List<RecommendedSubjectInfo>,
    rows: List<TvRecRow>,
    recRow: Int,
    localIndex: Int,
): TvHeroNeighbors {
    val row = rows[recRow]
    val flatIndex = row.start + localIndex
    // 推荐行的条目一律走整部 backdrop (不是"在看"的), 偏好恒 false
    fun idAt(i: Int) = items.getOrNull(i)?.bangumiId?.let(::TvHeroNeighbor)
    // 顺方向按行内下标取模: 行尾那几张的顺方向邻居算回本行开头, 不押到下一行开头
    fun wrappedIdAt(step: Int): TvHeroNeighbor? {
        if (row.size <= 1) return null // 单卡的行没有"右边"
        val i = row.start + (localIndex + step) % row.size
        return if (i == flatIndex) null else idAt(i) // 兜一圈回到自己 (行只有 2 张时的 +2) 不算邻居
    }
    // 下键落点是**下一行同列**. 各行条数不同 (种子那组最多 9 条), 列号超出下一行就没有落点
    val below = rows.getOrNull(recRow + 1)?.takeIf { localIndex < it.size }?.let { idAt(it.start + localIndex) }
    return TvHeroNeighbors(
        singleStep = listOfNotNull(wrappedIdAt(1), below),
        urlOnly = listOfNotNull(wrappedIdAt(2)),
    )
}

/**
 * 继续观看卡的进度条 (语义同详情页选集卡): 看到一半按播放位置; 已看完最新一集在等更新 (Watched) / 看完全部 (Done)
 * 显示满条; 看完上一集且有新集 / 还没开始看 (无播放记录) 不显示.
 */
internal fun followedCardProgress(item: FollowedSubjectInfo?, playHistories: List<EpisodeHistory>): Float? =
    item?.subjectCollectionInfo?.progressInfo?.let { progressInfo ->
        val caughtUp = progressInfo.continueWatchingStatus.let {
            it is ContinueWatchingStatus.Watched || it is ContinueWatchingStatus.Done
        }
        if (caughtUp) {
            1f
        } else {
            progressInfo.nextEpisodeIdToPlay
                ?.let { nextId -> playHistories.firstOrNull { it.episodeId == nextId } }
                ?.let { history ->
                    val duration = history.durationMillis
                    if (duration != null && duration > 0) {
                        (history.positionMillis.toFloat() / duration).coerceIn(0f, 1f)
                    } else null
                }
        }
    }

/** 分组标题. 只有"因为你喜欢《X》"与"看过《X》的人还看了"要填参数. */
@Composable
internal fun tvRecGroupTitle(group: RecommendationGroup): String = when (group.kind) {
    RecommendationGroupKind.BECAUSE_YOU_LIKED -> stringResource(
        Lang.exploration_rec_because_you_liked,
        group.titleArg.orEmpty(),
    )

    RecommendationGroupKind.ALSO_WATCHED -> stringResource(
        Lang.exploration_rec_also_watched,
        group.titleArg.orEmpty(),
    )

    RecommendationGroupKind.SIMILAR_TO -> stringResource(
        Lang.exploration_rec_similar_to,
        group.titleArg.orEmpty(),
    )

    RecommendationGroupKind.FOR_YOU_HIGH_RATED -> stringResource(Lang.exploration_rec_for_you_high_rated)
    RecommendationGroupKind.TOP_RATED -> stringResource(Lang.exploration_rec_top_rated)
    RecommendationGroupKind.THIS_SEASON -> stringResource(Lang.exploration_rec_this_season)
    RecommendationGroupKind.THIS_SEASON_NEW -> stringResource(Lang.exploration_rec_this_season_new)
    RecommendationGroupKind.CHANGE_TASTE -> stringResource(Lang.exploration_rec_change_taste)
    RecommendationGroupKind.TRENDING -> stringResource(Lang.exploration_rec_trending)
    RecommendationGroupKind.FEED -> stringResource(Lang.exploration_recommendations)
}

/**
 * 推荐区的一行: 平铺列表 (各组依次拼起来) 里第 [start] 条起的 [size] 条, 全部属于 [group];
 * [header] = 这一行上方要不要放区块标题.
 *
 * 一组一行、各带标题; 只有 [RecommendationGroupKind.FEED] (未登录时的整份推荐) 例外 —— 那一组两百条, 按屏上完整放得下的张数
 * 切成多行 (不横滑, 只排满行, 见 [tvRecRowsOf]), 标题只在首行上方放一次.
 */
@Immutable
internal class TvRecRow(
    val group: RecommendationGroup,
    val start: Int,
    val size: Int,
    val header: Boolean,
)

internal fun tvRecRowsOf(groups: List<RecommendationGroup>, feedRowSize: Int): List<TvRecRow> {
    val rows = mutableListOf<TvRecRow>()
    var groupStart = 0
    for (group in groups) {
        val feed = group.kind == RecommendationGroupKind.FEED
        val rowSize = if (feed) feedRowSize.coerceAtLeast(1) else group.items.size
        // FEED 只排满行: 条数不一定是列数的整数倍, 末尾凑不满一行的几条不放; 整组不到一行时照放
        val shown = if (feed && group.items.size >= rowSize) group.items.size / rowSize * rowSize else group.items.size
        for (offset in 0 until shown step rowSize.coerceAtLeast(1)) {
            rows += TvRecRow(
                group,
                start = groupStart + offset,
                size = minOf(rowSize, shown - offset),
                header = offset == 0,
            )
        }
        groupStart += group.items.size
    }
    return rows
}

internal data class TvHeroTarget(
    val subjectId: Int,
    val title: String,
    /** 是否来自"继续观看"行: hero 背景优先展示下一集的 TMDB 单集剧照 (而非整部 backdrop). */
    val fromFollowed: Boolean = false,
    /**
     * 该条目的竖版封面: TMDB 横版图都没有时拿它当全屏背景 (居中裁切), 见 [tvHeroBackdropUrl].
     * 从卡片直接带过来而不是等 infoCache —— 卡片本来就是拿它渲染的, 一定已经有值.
     */
    val coverUrl: String = "",
    /**
     * 从这里出发最可能走到的几个条目 (顺方向 +1/+2 与下一行同列), 用来在本条目就绪后**后台**
     * 预热它们的 hero 材料, 见 [TvHeroPrefetch].
     *
     * 在卡片的聚焦回调里算好带过来 (见 nativeListener): 只有那里知道自己在第几行第几个. 与 [subjectId] 同时
     * 变化 (都由聚焦位置决定), 不会给本数据类引入额外的相等性抖动.
     */
    val neighbors: TvHeroNeighbors = TvHeroNeighbors(),
    /**
     * 焦点在这一组行尾的「更多」卡上 (本条目是种子行的种子 / 别的组藏在「更多」卡底下的下一部): 文字多一行「按确定推荐更多…」, 简介只留一句
     * (见 tvExplorationNativeHeroText). null = 普通的聚焦卡.
     */
    val more: RecommendationGroup? = null,
) {
    /** 交给共享流水线/展示层的最小描述, 见 [TvHeroMediaSpec]. */
    fun toHeroMediaSpec() = TvHeroMediaSpec(subjectId, fromFollowed, coverUrl, neighbors)
}

/**
 * 上次离开本页时 hero 指向的条目, 用作返回时的初值. 与那几张表 (见 [TvHeroMediaCache]) 一样
 * 是**进程级**的, 但只有本页用得上, 所以留在本文件.
 *
 * 光把那几张表提到进程级还不够: 背景图地址是按 hero 目标算出来的 (见 TvExplorationNativeSources),
 * 而 `heroTarget` 要等卡片重新上报聚焦才有值 —— 返回后的头几帧它仍是 null, 背景图
 * 照样从空白起步. 用上次的值开局即可跳过这几帧; 焦点恢复到同一张卡时它本来就等于新值,
 * 落到别的卡上则是两张真图之间的正常交叉淡入 (仍好过从黑底淡入).
 */
private object TvExplorationLastHero {
    var target: TvHeroTarget? = null

    /** 卡片墙上背景与轮播文字停在的那一部 (轮播当前项), 同理做返回时的初值. */
    var carouselTarget: TvHeroTarget? = null
}

// ---------------------------------------------------------------------------
// 行键 (页面级焦点簿记一律记键, 不记绝对行号: 「继续观看」分页迟到时推荐行的键不变, 绝对行号会整体 +1)
// ---------------------------------------------------------------------------

internal const val TV_FOLLOWED_HEADER_KEY = "followed-header"
internal const val TV_FOLLOWED_ROW_KEY = "followed-row"

/** 海报墙列表最前面那个 hero 占位的项键 (见 TvNativeExploreItem.Spacer). */
internal const val TV_WALL_HERO_SPACER_KEY = "wall-hero-spacer"

private const val TV_REC_HEADER_KEY_PREFIX = "rec-header-"
internal fun tvRecHeaderKey(recRow: Int) = "$TV_REC_HEADER_KEY_PREFIX$recRow"
internal const val TV_REC_ROW_KEY_PREFIX = "rec-row-"
internal fun tvRecRowKey(recRow: Int) = "$TV_REC_ROW_KEY_PREFIX$recRow"

// ---------------------------------------------------------------------------
// 几何常量: 页面按这些算好原生海报墙的尺寸 (见 TvNativeExploreMetrics)
// ---------------------------------------------------------------------------

/** hero 文字块顶边 (对齐 backdrop 顶部区域). */
private val TV_EXPLORATION_HERO_TOP = 28.dp

/**
 * Hero 文字块高度 —— **聚焦卡的 hero 态** (没有轮播按钮, 信息块吃满): 文字下边界落在三页对齐的 [TV_HERO_TEXT_BOTTOM].
 * 带轮播按钮的一档见 [TV_HERO_BLOCK_HEIGHT_EXPANDED].
 */
private val TV_HERO_BLOCK_HEIGHT = TV_HERO_TEXT_BOTTOM - TV_EXPLORATION_HERO_TOP

/** 海报墙背景图的布局高 (占屏高): 轮播与卡片 hero 两套里大的那套, 小的那套按比例缩下去 (见 TvNativeExploreMetrics.cardBackdropScale). */
private val TV_WALL_BACKDROP_HEIGHT = maxOf(TV_CAROUSEL_HERO_TUNING.backdropHeight, TV_CARD_HERO_TUNING.backdropHeight)

/** 海报墙背景图在轮播与聚焦卡两套之间过渡用的遮罩几何. */
internal val TV_WALL_BACKDROP_GEOMETRY = tvHeroBackdropGeometry(hero = TV_CAROUSEL_HERO_TUNING, card = TV_CARD_HERO_TUNING)

/** 轮播圆点的中心在背景图分界线 ([TV_CAROUSEL_HERO_TUNING] 的 clearLine) 下方多远: 压在下缘羽化带里, 挨着首行预览. */
private val TV_CAROUSEL_INDICATOR_OFFSET = 60.dp

/** 轮播指示器的圆点边长、当前项胶囊的宽度、间距. */
private val TV_CAROUSEL_DOT_SIZE = 6.dp
private val TV_CAROUSEL_DOT_SELECTED_WIDTH = 20.dp
private val TV_CAROUSEL_DOT_GAP = 8.dp

/** 轮播指示器非当前项的不透明度 (乘在当前项的颜色上). */
private const val TV_CAROUSEL_DOT_INACTIVE_ALPHA = 0.4f

/** hero 文字块下缘 = hero 顶边 + [TV_HERO_BLOCK_HEIGHT] (即 [TV_HERO_TEXT_BOTTOM]). */
private val TV_EXPLORATION_LABEL_TOP = TV_EXPLORATION_HERO_TOP + TV_HERO_BLOCK_HEIGHT

/** 轮播按钮块下缘到首组标题的间距 (见 [TV_HERO_BLOCK_HEIGHT_EXPANDED]), 也算在 [TV_SECTION_LABEL_BLOCK] 里. */
private val TV_EXPLORATION_ROW_GAP = 12.dp

/** 区块标题块占位 = 标题那一行 (24dp) + [TV_EXPLORATION_ROW_GAP]: 轮播态首组标题在 hero 文字块下缘之下多远, 见 [TV_EXPLORATION_CARD_TOP]. */
private val TV_SECTION_LABEL_BLOCK = 24.dp + TV_EXPLORATION_ROW_GAP

/**
 * 海报墙的组标题块: 标题那一行 (24dp; 字的行高 23sp, 见 tvPosterWallHeaderStyle) + 标题到海报的间距 [TV_POSTER_WALL_HEADER_GAP]
 * (卡聚焦时往上放大, 间距留得比 [TV_EXPLORATION_ROW_GAP] 宽).
 */
private val TV_WALL_SECTION_LABEL_BLOCK = 24.dp + TV_POSTER_WALL_HEADER_GAP

/**
 * 轮播态 (列表停在顶上) 首组标题的顶边 = hero 文字块下缘 + [TV_SECTION_LABEL_BLOCK]. hero 占位的高度 = 它 − [TV_EXPLORATION_WALL_TOP],
 * 轮播按钮块的下缘由它倒推 (见 [TV_HERO_BLOCK_HEIGHT_EXPANDED]).
 */
private val TV_EXPLORATION_CARD_TOP = TV_EXPLORATION_LABEL_TOP + TV_SECTION_LABEL_BLOCK

/**
 * 海报墙 (见 TvNativeExploreList.kt): 卡片区顶线离页面顶的距离, 同追番 / 搜索页的顶部留白.
 * 轮播态下首组标题在 [TV_EXPLORATION_CARD_TOP] (两者之差就是 hero 占位的高度).
 */
private val TV_EXPLORATION_WALL_TOP = 24.dp

/** 窗口还没测量时海报墙按这个尺寸算列数与视口 (1080p 电视的界面尺寸), 见 wallColumns 处. */
private val TV_EXPLORATION_FALLBACK_WINDOW_WIDTH = 960.dp
private val TV_EXPLORATION_FALLBACK_WINDOW_HEIGHT = 540.dp

/** 海报墙卡片区向上出血: 离场的行越过顶线继续上移的那一截 (不在顶线处裁掉). */
private val TV_EXPLORATION_WALL_TOP_BLEED = 120.dp

/**
 * 海报墙卡片区向下出血 (见 TV_POSTER_WALL_BOTTOM_BLEED): 屏内末行下面可能先是一个组标题, 在那一截之上再多垫一个
 * 标题的高度, 下一行照样露进来、照样已经排好.
 */
private val TV_EXPLORATION_WALL_BOTTOM_BLEED = TV_POSTER_WALL_BOTTOM_BLEED + TV_WALL_SECTION_LABEL_BLOCK

/**
 * Hero 文字块高度 —— **轮播态** (信息块 + 轮播按钮块): 块底 = 首组标题 ([TV_EXPLORATION_CARD_TOP]) 上方一个行距, 于是
 * 按钮下缘到首组标题恰好 [TV_EXPLORATION_ROW_GAP].
 *
 * 从首组标题倒推而不是写死: 按钮块的高度是字号/内边距算出来的, 写死信息块高度的话按钮下面
 * 会多出或缺掉一段空白 (真机一眼可见"按钮离继续观看太远").
 */
private val TV_HERO_BLOCK_HEIGHT_EXPANDED =
    TV_EXPLORATION_CARD_TOP - TV_EXPLORATION_ROW_GAP - TV_EXPLORATION_HERO_TOP

/** Hero 信息块与操作按钮块之间的间距 (较短, 让按钮贴近简介). */
private val TV_HERO_INFO_TO_BUTTONS_GAP = 6.dp

/** 两枚操作按钮之间的间距 (很短). */
private val TV_HERO_BUTTON_GAP = 4.dp

/** 轮播指示器最多显示的圆点数 (同时也是自动轮播覆盖的条目数). */
private const val TV_CAROUSEL_MAX_DOTS = 20

/** 自动轮播切换间隔. */
private const val TV_CAROUSEL_AUTO_ADVANCE_MILLIS = 6000L


/**
 * 探索页海报墙的几何: 列数、卡宽、交给原生视图的各项尺寸. 页面与「海报墙大小」的预览页共用这一份, 预览才与真页一模一样.
 * 在 [TvPosterWallScaled] 里调用时卡片 (列数、卡宽、行高与行距) 按海报墙大小算, 轮播、hero 与组标题照常.
 */
internal class TvExplorationWallLayout(
    val columns: Int,
    val cardWidth: Dp,
    val metrics: TvNativeExploreMetrics,
)

/**
 * 算 [TvExplorationWallLayout]. 本页在主壳里, 左边让出收起的侧边栏, 右边留 [TV_PAGE_END_PAD].
 * 窗口还没测量的那几帧尺寸是 0 (冷启动时本页正好在那会儿组合, 见 AniDisplayTier): 先按 1080p 电视的 960 × 540 dp 算,
 * 否则会先按 1 列切一遍行, 下一帧再换成 6 列.
 */
@Composable
internal fun tvExplorationWallLayout(): TvExplorationWallLayout {
    val railWidth = TvNavigationRailDefaults.CollapsedWidth
    val cardScale = LocalTvPosterWallScale.current
    val pageWindowSize = with(LocalDensity.current) {
        val size = LocalWindowInfo.current.containerSize
        DpSize(
            if (size.width > 0) size.width.toDp() else TV_EXPLORATION_FALLBACK_WINDOW_WIDTH,
            if (size.height > 0) size.height.toDp() else TV_EXPLORATION_FALLBACK_WINDOW_HEIGHT,
        )
    }
    // 海报墙的列数. 行结构 (哪张卡在哪一行) 页面各处都要用, 所以按窗口宽度算, 不等卡片区量出来
    val wallContentWidth = tvExplorationWallContentWidth()
    val wallGrid = with(LocalDensity.current) { tvPosterWallGrid(wallContentWidth, cardScale) }
    val wallColumns = wallGrid.columns
    // 原生海报墙 (见 TvExplorationNativeWall.kt): 背景图 / hero 文字与按钮 / 卡片列表 / 轮播指示器都在原生视图里, 几何按本页的常量算好交给它.
    // 列表各项一律定高: 停位按高度直接算, 不用等测量. 行 = 海报 + 两行番名 + 行距 (这三样随海报墙大小缩放), 组标题 [TV_WALL_SECTION_LABEL_BLOCK],
    // hero 占位 = 轮播态下卡片区顶线到首组标题的距离: 首组标题落在 [TV_EXPLORATION_CARD_TOP], 轮播按钮下缘到它恰好一个 [TV_EXPLORATION_ROW_GAP]
    val wallCardWidth = wallGrid.cardWidth
    val wallLabelHeight = tvPosterWallLabelHeight() * cardScale
    val wallRowSpacing = TV_POSTER_WALL_ROW_SPACING * cardScale
    val metrics = with(LocalDensity.current) {
        val pageWidth = pageWindowSize.width - railWidth
        val heroContentWidth = pageWidth - TV_EXPLORATION_START_PAD - TV_PAGE_END_PAD
        val backdropHeightPx = (pageWindowSize.height * TV_WALL_BACKDROP_HEIGHT).roundToPx()
        val cardHeight = wallCardWidth / TV_PORTRAIT_CARD_COVER_RATIO
        // 页面比参照高高 (界面缩放调小) 时背景图跟着变大, 图下面的东西一起下移 (见 tvHeroScaleShift), 挪出来的高度给简介, 100% 时都是 0:
        // 轮播态 = 首组标题、卡片、轮播按钮与指示器, 首行海报顶边落在背景图的同一处; hero 态 = 聚焦行 (三页对齐的那条线) 与简介块下沿
        val carouselShiftPx =
            tvHeroScaleShift(pageWindowSize.height, TV_EXPLORATION_CARD_TOP + TV_WALL_SECTION_LABEL_BLOCK).roundToPx()
        val heroShiftPx = tvHeroScaleShift(pageWindowSize.height, TV_POSTER_WALL_HERO_ROW_TOP).roundToPx()
        val spacerHeight = TV_EXPLORATION_CARD_TOP - TV_EXPLORATION_WALL_TOP
        // 轮播背景图的下缘离屏顶多远, 与它比 hero 占位长出去的那一截 (压在第一组标题与海报上面, 见 tvNativeCarouselShift)
        val carouselBottomPx = (pageWindowSize.height * TV_CAROUSEL_HERO_TUNING.backdropHeight).toPx()
        val overhangPx = (carouselBottomPx - (TV_EXPLORATION_WALL_TOP + spacerHeight).toPx() - carouselShiftPx)
            .roundToInt().coerceAtLeast(0)
        // 指示器挨着首组标题: 下移时从参照高下的位置跟着挪, 不下移时照旧按页面高算
        val dotsLineHeight = if (carouselShiftPx > 0) TV_HERO_REFERENCE_PAGE_HEIGHT else pageWindowSize.height
        TvNativeExploreMetrics(
            pageWidthPx = pageWidth.roundToPx(),
            pageHeightPx = pageWindowSize.height.roundToPx(),
            bleedLeftPx = railWidth.roundToPx(),
            columns = wallColumns,
            listTopPx = TV_EXPLORATION_WALL_TOP.roundToPx(),
            listTopBleedPx = TV_EXPLORATION_WALL_TOP_BLEED.roundToPx(),
            listBottomBleedPx = TV_EXPLORATION_WALL_BOTTOM_BLEED.roundToPx(),
            spacerPx = spacerHeight.roundToPx() + carouselShiftPx,
            headerPx = TV_WALL_SECTION_LABEL_BLOCK.roundToPx(),
            rowPx = (cardHeight + wallLabelHeight + wallRowSpacing).roundToPx(),
            rowGapPx = wallRowSpacing.roundToPx(),
            // 顶线到屏幕底边
            viewportPx = (pageWindowSize.height - TV_EXPLORATION_WALL_TOP).roundToPx(),
            endMarginPx = tvPosterWallEndMargin(cardHeight, TV_POSTER_WALL_CARD_FOCUS_STYLE.focusScale).roundToPx(),
            // hero 态聚焦行的组标题停在哪 (从卡片区顶线算): 行落在三页对齐的 TV_POSTER_WALL_HERO_ROW_TOP,
            // 组标题在它上面, 即简介块下沿那条线
            heroHeaderTopPx = (TV_POSTER_WALL_HERO_ROW_TOP - TV_WALL_SECTION_LABEL_BLOCK - TV_EXPLORATION_WALL_TOP).roundToPx() +
                heroShiftPx,
            // 横滑行行首停靠线 = 内容区左缘: 原生视图从屏幕左缘画起, 越过停靠线的卡从侧边栏底下可见地滑出屏幕
            rowStartPx = (railWidth + TV_EXPLORATION_START_PAD).roundToPx(),
            endPadPx = TV_PAGE_END_PAD.roundToPx(),
            fadeDistancePx = TV_CARD_FADE_DISTANCE.toPx(),
            carouselBottomPx = carouselBottomPx,
            overhangPx = overhangPx,
            backdropWidthPx = (backdropHeightPx * TV_BACKDROP_ASPECT_RATIO).roundToInt(),
            backdropHeightPx = backdropHeightPx,
            cardBackdropScale = TV_CARD_HERO_TUNING.backdropHeight / TV_WALL_BACKDROP_HEIGHT,
            heroStartPx = TV_EXPLORATION_START_PAD.roundToPx(),
            heroTopPx = TV_EXPLORATION_HERO_TOP.roundToPx(),
            heroEndPadPx = TV_PAGE_END_PAD.roundToPx(),
            heroBlockPx = TV_HERO_BLOCK_HEIGHT.roundToPx() + heroShiftPx,
            heroBlockExpandedPx = TV_HERO_BLOCK_HEIGHT_EXPANDED.roundToPx() + carouselShiftPx,
            titleWidthPx = (heroContentWidth * TV_CAROUSEL_HERO_TUNING.titleWidth).roundToPx(),
            carouselSummaryWidthPx = (heroContentWidth * TV_CAROUSEL_HERO_TUNING.summaryWidth).roundToPx(),
            cardSummaryWidthPx = (heroContentWidth * TV_CARD_HERO_TUNING.summaryWidth).roundToPx(),
            buttonsTopGapPx = TV_HERO_INFO_TO_BUTTONS_GAP.roundToPx(),
            buttonGapPx = TV_HERO_BUTTON_GAP.roundToPx(),
            dotsCenterYPx = (dotsLineHeight * TV_CAROUSEL_HERO_TUNING.clearLine).roundToPx() +
                TV_CAROUSEL_INDICATOR_OFFSET.roundToPx() + carouselShiftPx,
            dotPx = TV_CAROUSEL_DOT_SIZE.toPx(),
            dotSelectedWidthPx = TV_CAROUSEL_DOT_SELECTED_WIDTH.toPx(),
            dotGapPx = TV_CAROUSEL_DOT_GAP.toPx(),
            dotInactiveAlpha = TV_CAROUSEL_DOT_INACTIVE_ALPHA,
        )
    }
    return TvExplorationWallLayout(wallColumns, wallCardWidth, metrics)
}

/**
 * 探索页海报墙卡片区的内容宽度: 窗口宽减去收起的侧边栏、左留白与右边距. 列数与卡宽按它算 ([tvExplorationWallLayout]), 「海报墙大小」
 * 滑块的档位也按它算. 窗口还没测量时按 1080p 电视的宽度算 (同 [tvExplorationWallLayout]).
 */
@Composable
internal fun tvExplorationWallContentWidth(): Dp {
    val width = LocalWindowInfo.current.containerSize.width
    val windowWidth = with(LocalDensity.current) { if (width > 0) width.toDp() else TV_EXPLORATION_FALLBACK_WINDOW_WIDTH }
    return windowWidth - TvNavigationRailDefaults.CollapsedWidth - TV_EXPLORATION_START_PAD - TV_PAGE_END_PAD
}

/**
 * 内容左侧额外留白: 页面从屏幕左缘铺起 (侧边栏盖在上面), 内容左缘 = 侧边栏收起宽度 48dp + 此值.
 * 默认 16 使总左缘 64, 侧边栏按钮中心 (32) 恰在屏幕左缘与内容左缘的正中间.
 */
private val TV_EXPLORATION_START_PAD = 16.dp

