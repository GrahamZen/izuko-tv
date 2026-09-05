/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import kotlin.time.TimeSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.player.EpisodeHistory
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.recommend.RecommendedItemInfo
import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo
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
import me.him188.ani.app.tools.WeekFormatter
import me.him188.ani.app.ui.foundation.LocalTvBackLongPressHost
import me.him188.ani.app.ui.foundation.TvPageRefreshHandler
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.isAutoRepeat
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvScrollAnimator
import me.him188.ani.app.ui.foundation.focus.tvAnchorBringIntoViewSpec
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.focus.tvFocusMoveRateLimit
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.stateOf
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TvPageBackdropLayer
import me.him188.ani.app.ui.foundation.tv.rememberTvSettledHeroProvider
import me.him188.ani.app.ui.foundation.tv.TvFocusRing
import me.him188.ani.app.ui.foundation.tv.TV_HERO_MEDIA_DEBOUNCE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvNavigationSettle
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaCache
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaSpec
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbor
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbors
import me.him188.ani.app.ui.foundation.tv.TvHeroPrefetch
import me.him188.ani.app.ui.foundation.tv.rememberTvHeroMediaPipeline
import me.him188.ani.app.ui.foundation.tv.resolveTvHeroMedia
import me.him188.ani.app.ui.foundation.tv.TvNextEpisodeMedia
import me.him188.ani.app.ui.foundation.tv.prefetchTvSummaryFallback
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropReady
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropUrl
import me.him188.ani.app.ui.foundation.tv.TV_NAV_LOCK_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_NAV_READY_BUDGET
import me.him188.ani.app.ui.foundation.tv.TV_HERO_SUMMARY_WIDTH_FRACTION
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
import me.him188.ani.app.ui.foundation.tv.TvHeroButton
import me.him188.ani.app.ui.foundation.tv.TvPortraitCard
import me.him188.ani.app.ui.foundation.tv.TvPortraitCardFocusRing
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvHeroMarqueeIterations
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.tv.tvPlayKeyShortPress
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_continue_watching
import me.him188.ani.app.ui.lang.exploration_recommendations
import me.him188.ani.app.ui.lang.exploration_schedule
import me.him188.ani.app.ui.lang.exploration_tv_air_date
import me.him188.ani.app.ui.lang.exploration_tv_all_caught_up
import me.him188.ani.app.ui.lang.exploration_tv_minutes_left
import me.him188.ani.app.ui.lang.exploration_tv_next_episode
import me.him188.ani.app.ui.lang.exploration_tv_watch_now
import me.him188.ani.app.ui.lang.exploration_tv_watched_latest
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.subject_progress_updates_on
import me.him188.ani.app.ui.lang.tv_card_remote_hint
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
 * TV 沉浸式探索页: 全屏背景为聚焦条目的 TMDB backdrop (左/下渐隐入背景色),
 * 上半区展示聚焦条目的标题 / Bangumi 评分数字 + 连载信息 / 简介; 下半区为可滚动的卡片区 ——
 * 继续观看一行 + 推荐纵向无限行, 全部为固定锚点轮播 (聚焦框钉死, 卡片在框下滑动).
 *
 * ## 布局架构: 三层叠放, 卡片区几何恒定
 *
 * 页面根是一个 Box, 三层互不挤压:
 *  1. hero 覆盖层 (信息块 + 展开态按钮), 顶在 [TV_EXPLORATION_HERO_TOP];
 *  2. 锚位区块标签覆盖层, 钉在 [TV_EXPLORATION_LABEL_TOP] —— 区块标题本身仍是列表 item
 *     (非聚焦区块的标题跟卡片行一起在下方可见, hero 态整段露在 hero 下方, 与 main 观感一致),
 *     覆盖层只在行内那个**越过标签线**之后接手 (两者 alpha 互补, 见 [tvSectionHeaderAlpha]);
 *  3. 卡片区, 顶边钉在 [TV_EXPLORATION_CARD_TOP], **不随 hero/标签的任何显隐变化**.
 *
 * 这是从一串真机 bug 里换来的架构结论: 旧实现把三者放同一个 Column 里 (卡片区 weight(1f)),
 * hero 收展/标签出没都会挪卡片区顶边, 于是需要"hero 两态等高""标签段等高""几何变化与滚动
 * 同帧"等一堆不变量与补丁, 错一个卡片就"自己动一下". 叠放层各管各的, 这类位移在结构上不存在.
 *
 * ## 滚动: 官方 pivot 式 BringIntoViewSpec (androidx.tv 迁移指南的推荐做法)
 *
 * 不手动驱动滚动. 纵向列与每条横向行各提供一个 [tvAnchorBringIntoViewSpec]: 卡片一聚焦,
 * 框架自动把它滚到锚位 (返回"到锚位的距离"而非默认的"最小滚动到可见"), 动画即调参过的
 * 低刚度 spring; 连续按键时框架的 UpdatableAnimationState 自动改目标、速度连续 (leanback 手感).
 *
 * ## 焦点: 三级"进组落点"链 (按键/下标记, 不记节点) + 两个显式落点请求
 *
 * 页面根 → 纵向列 → 行, 每级一个 focusProperties.onEnter 把无方向的进入改道到"上次那个":
 * 列记上次聚焦的**行键**, 行记上次聚焦的**下标** —— 侧边栏回来、全局兜底 enter 都自动落回
 * 原卡. 不用官方 [focusRestorer]: 它记节点引用, 行/卡滚出视口销毁后引用失效会退化成
 * "第一个可聚焦项" (= 出血区里上一行的隐形卡), 真机表现为跳行/整行横向被拽走.
 * 只有两处需要显式定位 (合并为两个请求状态, 解析都带到位确认+重试):
 *  - [TvCardFocusRequest]: 聚焦指定行的指定卡 (进页恢复用保存的行键 + 返回键分层规则);
 *  - hero 按钮请求: 聚焦 hero 的某颗按钮 (按钮只在 hero 态组合, 请求会先把它组合出来).
 */
@Composable
fun TvExplorationPage(
    state: ExplorationPageState,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val bangumiSummaryService = remember { GlobalKoin.get<BangumiSummaryService>() }
    val setCollectionTypeUseCase = remember { GlobalKoin.get<SetSubjectCollectionTypeOrDeleteUseCase>() }
    val settingsRepository = remember { GlobalKoin.get<SettingsRepository>() }
    val playHistoryRepository = remember { GlobalKoin.get<EpisodePlayHistoryRepository>() }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    // 聚焦条目 (卡片 onFocusChanged 上报); 标题/封面来自卡片自身数据, 立即可显示.
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

    val fullVisualEffects = LocalThemeSettings.current.tvFullVisualEffects

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
            // (onFocused 已种入 infoCache), 倒种进进程缓存让解析链第一跳直接命中
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
    // 机理与实测数据见 [rememberTvSettledHero].
    //
    // **provider 版, 与追番/搜索/时间表三页一致**: 值版要求在这里就把 heroTarget 读出来, 而这是
    // 每格方向键都变的热状态 —— 那次读记在本函数身上, 于是每换一张卡整页重跑一遍, 连带 LazyColumn
    // 的内容 lambda 换新实例, 满屏卡片跟着重组. 读取全部下沉到 backdrop 层与 hero 覆盖层内部.
    val heroDisplay = rememberTvSettledHeroProvider { heroTarget }
    // 单集剧照 -> 整部 backdrop -> 竖版封面居中裁切, 三级回落见 tvHeroBackdropUrl.
    // 同样是 lambda: tvHeroBackdropUrl 读的是服务层热表 (快照可观察), 在这里读的话预取一落表
    // 又是整页重跑.
    val backdropUrl: () -> String? = { heroPipeline.backdropUrl(heroDisplay()?.toHeroMediaSpec()) }
    // 超时兜底的另一半 (URL 已解析但图片本体卡住): 语义见 TvHeroMediaPipelineState.underlayUrl
    val backdropUnderlayUrl: () -> String? = { heroPipeline.underlayUrl(heroDisplay()?.toHeroMediaSpec()) }
    // 提前取色的目标条目 (见 TvPageBackdropLayer 的 themeSeedSubjectId)
    val backdropSubjectId: () -> Int? = { heroDisplay()?.subjectId }

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
    val navigateToSubject: (subjectId: Int, name: String, cover: String, source: String) -> Unit =
        { subjectId, name, cover, source ->
            if (!navLocked) {
                Analytics.recordEvent(SubjectEnter) {
                    put("source", source)
                    put("subject_id", subjectId)
                }
                lockNavigationForTransition()
                scope.launch {
                    withTimeoutOrNull(TV_NAV_READY_BUDGET) {
                        // tvHeroBackdropReady 读的是服务层热表 (快照可观察), 预取一落表这里就放行
                        snapshotFlow {
                            infoCache[subjectId] != null && tmdb.tvHeroBackdropReady(subjectId)
                        }.first { it }
                    }
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
    // remember: 工厂被网格 items 内容 lambda 捕获, 每次新实例都会让所有可见卡片跟着重组 ——
    // 而本函数的 body 直读 heroTarget/carouselIndex 这些每格方向键都变的热状态, 不 remember
    // 就是"每按一格键, 满屏卡片重组一次". 另外三页 (追番/搜索/时间表) 早就是 remember 的
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
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 轮播 (hero) 状态
    // ------------------------------------------------------------------
    // 最高热度是 Hero 轮播 (无卡片行): 两枚操作按钮 (立即观看 / 时间表) + 右侧不可聚焦的轮播
    // 指示器. 焦点在按钮上时左右键手动切换轮播 (第一个条目按左不消费, 交给焦点系统聚焦侧边栏);
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
    val switchCarousel: (Int) -> Unit = { delta ->
        if (carouselSize > 0) {
            carouselIndex = ((carouselIndex + delta) % carouselSize + carouselSize) % carouselSize
            carouselInteraction++
        }
    }

    // ------------------------------------------------------------------
    // 行结构 (数据驱动): 继续观看一行 (若有) + 推荐 N 行. 行号 == LazyColumn item 下标.
    // ------------------------------------------------------------------
    // 两个分页实例挂在 ViewModel 上 (见 ExplorationPageState), 跨导航活着: 现收的话每次返回都
    // 要一百多毫秒才把缓存好的数据 present 出来, 那段空窗里"继续观看"整行不存在, 而 listState
    // 存的是**下标**不是键 —— 下标 1 当场变成推荐区第一行, 卡片就坐在锚位上闪那么十来帧.
    val followedItems = state.followedSubjectsPager
    val recommendations = state.recommendationPager
    val hasFollowed = followedItems.itemCount > 0
    val followedRowCount = if (hasFollowed) 1 else 0
    val recRowCount =
        (recommendations.itemCount + TV_EXPLORATION_REC_ROW_SIZE - 1) / TV_EXPLORATION_REC_ROW_SIZE
    val rowCount = followedRowCount + recRowCount
    // 行键稳定跨 hasFollowed 翻转 ("继续观看"分页迟到时推荐行的键不变, 状态不串行);
    // 页面级焦点簿记一律记**键**, 不记绝对行号 —— 绝对行号会在 followed 行迟到时整体 +1.
    val rowKeyAt: (Int) -> String = { index ->
        if (index < followedRowCount) TV_FOLLOWED_ROW_KEY else tvRecRowKey(index - followedRowCount)
    }
    val rowIndexOfKey: (String) -> Int? = { key ->
        when {
            rowCount == 0 -> null
            // 「继续观看」行只在 hasFollowed 时才发出来, 所以这里必须问一句再答. 早先无条件
            // 答 0 ("没了就退到首行"), 于是这个键在整行消失期间仍被当成"存在的第 0 行":
            // anchorRowKey 指着一个根本没组合的行 (进组请求必失败, 见 LazyColumn 的 onEnter),
            // itemIndexOfRowKey 又把它算到推荐首行去 —— 页面段每帧 scrollToItem 到错的 item.
            key == TV_FOLLOWED_ROW_KEY -> 0.takeIf { hasFollowed }
            else -> key.removePrefix(TV_REC_ROW_KEY_PREFIX).toIntOrNull()
                ?.let { (followedRowCount + it).coerceAtMost(rowCount - 1) }
        }
    }
    // 行键 → LazyColumn 的 item 下标. item 布局 (与下面的 items 块一一对应):
    //   [继续观看标题, 继续观看行] (仅 hasFollowed) + [推荐标题, 推荐行 × N]
    // 只有一个用处: 目标行没组合出来时 scrollToItem 把它滚进来 (其余定位全靠焦点驱动).
    val itemIndexOfRowKey: (String) -> Int? = { key ->
        val row = rowIndexOfKey(key)
        when {
            row == null -> null
            row < followedRowCount -> 1 // 0 是继续观看标题
            // 推荐区: 前面是 (继续观看标题+行)? + 推荐标题
            else -> followedRowCount * 2 + 1 + (row - followedRowCount)
        }
    }

    // ------------------------------------------------------------------
    // 焦点簿记 + 两个显式落点请求
    // ------------------------------------------------------------------
    // null = 焦点在 hero (或从未进过卡片区). 跨导航保存: 进详情页返回后恢复到同一行.
    var focusedRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 聚焦卡在行内的下标 (返回键分层规则 / 继续观看播放键用); 行内恢复用各行自己保存的下标
    var focusedCardIndex by remember { mutableIntStateOf(0) }
    var cardAreaHasFocus by remember { mutableStateOf(false) }
    val heroExpanded = focusedRowKey == null
    val focusedRowIndex = focusedRowKey?.let(rowIndexOfKey)
    // 进卡片区的落点行: 上次聚焦的行, 没有则首行 (键而非行号, 分页迟到不错位)
    val anchorRowKey = focusedRowKey?.takeIf { rowIndexOfKey(it) != null }
        ?: rowCount.takeIf { it > 0 }?.let { rowKeyAt(0) }

    // 显式落点请求 (仅两处来源: 进页恢复 / 返回键分层; 其余焦点移动全走空间搜索 + 进组落点链).
    // 实例身份比较: 连发同参请求也能重新触发解析.
    var cardFocusRequest by remember { mutableStateOf<TvCardFocusRequest?>(null) }
    // hero 落点请求. **同样按实例身份比较** (见 [TvHeroFocusRequest]): 直接存枚举的话, 请求没
    // 落地时值不变 → LaunchedEffect 不重启 → 同一颗按钮再请求多少次都不会重新送焦.
    var heroFocusRequest by remember { mutableStateOf<TvHeroFocusRequest?>(null) }
    val focus = rememberTvFocusScope()

    // 卡片聚焦的簿记入口. **落点请求进行中时只认目标行写进来的**: 恢复目标的那一行还没组合出来
    // 的那几帧里, 焦点系统 (或全局兜底) 会先把焦点塞进别的行, 那张卡的 onFocused 一旦改写
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

    // 卡片区纵向列表 + 两级"进组落点"请求器: columnFocusRequester = 页面外进来时先进卡片区,
    // anchorRowRequester = 进卡片区后落到上次聚焦的那一行 (挂在该行上, 见 LazyColumn 的 onEnter)
    val listState = rememberLazyListState()
    val columnFocusRequester = remember { FocusRequester() }
    val anchorRowRequester = remember { FocusRequester() }

    // Hero 按钮只在 hero 态组合; 请求保持非空让按钮先组合, 锚点 attach 后再单发送焦.
    LaunchedEffect(heroFocusRequest) {
        val req = heroFocusRequest ?: return@LaunchedEffect
        focus.request(req.button)
    }
    // 卡片落点请求的页面段: 只负责把目标行滚进组合 (行内段由 TvAnchoredCardRow 接手并在
    // 到位后清空请求). 目标行已组合就什么都不做 —— scrollToItem 是瞬移, 平时的滚动全由
    // 聚焦触发的 bring-into-view spring 做.
    LaunchedEffect(cardFocusRequest) {
        val req = cardFocusRequest ?: return@LaunchedEffect
        val item = snapshotFlow { itemIndexOfRowKey(req.rowKey) }.filterNotNull().first()
        if (cardFocusRequest === req && listState.layoutInfo.visibleItemsInfo.none { it.key == req.rowKey }) {
            runCatching { listState.scrollToItem(item) }
        }
    }

    // 进页/返回的初始焦点: 曾在某行 -> 恢复该行 (cardIndex=-1: 行自己跨导航保存的聚焦卡);
    // 首次进入或曾在 hero -> hero 主按钮. 此后页面外进来的焦点走页面根 onEnter (见下).
    // 恢复请求是否已派出. 见下方返回键分层: 本效应调度前那一两帧 cardFocusRequest 还是 null,
    // 那时按返回会被放行成"退出应用确认" —— 曾在某行时用它把这一段也算作焦点在卡片区
    var entryRestoreDispatched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val saved = focusedRowKey
        if (saved != null) {
            cardFocusRequest = TvCardFocusRequest(saved, cardIndex = -1)
        } else {
            heroFocusRequest = TvHeroFocusRequest(TvHeroFocusButton.PRIMARY)
        }
        entryRestoreDispatched = true
    }
    // 「继续观看」整行迟到 (分页) 或在详情页改过观看进度后刷新时整行短暂消失又回来: 行回来的
    // 时候, 上面那次恢复请求往往已经在空等中试满次数被清掉了, 而焦点这会儿停在推荐区. 行一
    // 出现就补发一次. 焦点本来就在这一行上时行内解析首帧即判到位, 是个空操作.
    LaunchedEffect(hasFollowed) {
        if (hasFollowed && focusedRowKey == TV_FOLLOWED_ROW_KEY && cardFocusRequest == null) {
            cardFocusRequest = TvCardFocusRequest(TV_FOLLOWED_ROW_KEY, cardIndex = -1)
        }
    }
    // 恢复期间列表头部插了 item ("继续观看"分页比推荐晚到) 时, 把目标行瞬时对到锚位.
    // 不做这一步的话: 卡片区顶上有 200dp 出血内边距, 插进来的目标行落在**出血区里** —— 它
    // 已组合已布局, 页面段因此判定"已可见"而不滚, 可它并不在锚位上; 紧接着它的卡片拿到焦点,
    // bring-into-view 的 spring 就把整屏从出血区一路拉到锚位. 锚位正是固定聚焦框待的地方,
    // 于是用户看到的是"焦点先停在推荐区第一行第一张卡上, 再整屏滑一段回来"(2026-08-12 真机
    // 日志钉死: 全程没有一条推荐区的卡片聚焦记录, 焦点根本没去过那儿, 动的是内容).
    // 瞬时而不是动画: 分页到货不是用户动作, 没有可跟随的手势.
    LaunchedEffect(hasFollowed, rowCount) {
        val target = cardFocusRequest?.rowKey ?: return@LaunchedEffect
        val item = itemIndexOfRowKey(target) ?: return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != item || listState.firstVisibleItemScrollOffset != 0) {
            listState.requestScrollToItem(item)
        }
    }

    // hero 重新拿到焦点时列表滚回顶部 (row0 预览露在 hero 下方). bring-into-view 只在卡片
    // 聚焦时工作, 这一条是它管不到的唯一滚动, 用同款 spring 动画器.
    val toTopAnimator = remember { TvScrollAnimator() }
    LaunchedEffect(heroExpanded) {
        if (!heroExpanded) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            toTopAnimator.animateScrollToItem(listState, 0)
        }
        // 动画按启动那一刻算好的固定位移跑: 中途分页到达往列表头部插 item 会让它落在别处
        // (下面那条结构 effect 此时正在滚动中, 不插手), 跑完补一次瞬时归零兜底.
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            listState.requestScrollToItem(0)
        }
        // 之后持续钉住. 下面那条结构 effect 是"数据一变就看一眼位置", 而它读到的是**上一次
        // 布局**的位置: 效果体跑在插入 item 后的 measure 之前时读到的还是 0, 于是一次
        // requestScrollToItem 都不发 (键锚点也就没被清掉), 紧接着的 measure 把列表顶下去,
        // 而 hasFollowed/rowCount 此后不再变 —— 没有任何东西会再来纠正, 列表就永久停在非 0,
        // hero 按钮永久 alpha=0 (节点还在, 所以"看不见但确定键照常进详情", 退出重进才好).
        // 跑在 measure 之前还是之后不确定, 所以是偶发. 这里不再赌顺序, 改成对实际位置反应.
        // 滚动中不插手 (回顶动画/校正本身), 钉回后新位置是 (0,0), 不会自激.
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if ((index != 0 || offset != 0) && !listState.isScrollInProgress) {
                    listState.requestScrollToItem(0)
                }
            }
    }
    // 列表头部插入 item 后重新钉回 0. hero 态的行位置全靠"列表停在 0", 而"继续观看"分页比
    // 推荐晚到, 到达时往列表头部插入两个 item (标题 + 行) —— LazyColumn 默认保持**首个可见
    // item 不动** (按键锚点校正滚动位置), 于是内容整体下移一个区块: 行内标题被顶进 hero 文字
    // 里, 首行滑进出血区淡光, hero 按钮又因 firstVisibleItemIndex != 0 判为"未到顶"而隐身
    // (冷启动必现, 往下导航一次才恢复). requestScrollToItem 顺带清掉那个键锚点, 正是这里要的.
    // 不做动画: 数据到达不是用户动作. 滚动中 (hero 回顶动画) 不插手, 那条动画本身就停在 0.
    LaunchedEffect(hasFollowed, rowCount) {
        if (heroExpanded && !listState.isScrollInProgress &&
            (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
        ) {
            listState.requestScrollToItem(0)
        }
    }

    // TRENDING 时轮播条目驱动 hero (标题即时, 评分/连载/简介/backdrop 异步跟上)
    LaunchedEffect(carouselIndex, heroExpanded, carouselSize) {
        if (heroExpanded && carouselSize > 0) {
            // 下一项当作"邻居"传下去: URL 预取与图片预热共用同一条路 (见 TvHeroNeighbors).
            // 轮播是全页唯一 100% 确定的目标 —— 6 秒后必然轮到它, 提前量足足一整轮, 而且
            // 换图时用户根本没在操作, 等待全落在眼里
            val nextCarousel = if (carouselSize > 1) {
                trending.peekOrNull((carouselIndex + 1) % carouselSize)?.bangumiId
            } else {
                null
            }
            trending[carouselIndex.coerceIn(0, carouselSize - 1)]?.let {
                onFocusItem(
                    it.bangumiId, it.nameCn, null, false, it.imageLarge,
                    // 轮播条目走整部 backdrop, 不是剧照
                    TvHeroNeighbors(singleStep = listOfNotNull(nextCarousel?.let(::TvHeroNeighbor))),
                )
            }
            // 预取**下一项**: 这是全页唯一确定性的目标 —— 6 秒后必然轮到它, 提前量足足一整轮.
            // 不预取的话每次自动轮播换图都是现拉三跳, 用户什么都没做就在等图.
            // 后台槽会等当前这项的前台请求跑完才开工 (见 TvHeroPrefetch)
            if (carouselSize > 1) {
                val nextIndex = (carouselIndex + 1) % carouselSize
                trending.peekOrNull(nextIndex)?.let { next ->
                    TvHeroPrefetch.background(next.bangumiId) {
                        resolveTvHeroMedia(next.bangumiId, collectionRepo, tmdb)
                    }
                }
            }
        }
    }
    // 自动轮播: 仅在 hero 态推进; carouselInteraction 变化 (手动切换) 会重启本效果, 重置计时
    LaunchedEffect(carouselSize, heroExpanded, carouselInteraction) {
        if (!heroExpanded || carouselSize <= 1) return@LaunchedEffect
        while (true) {
            delay(TV_CAROUSEL_AUTO_ADVANCE_MILLIS)
            carouselIndex = (carouselIndex + 1) % carouselSize
        }
    }

    // 返回键分层规则: 不在行首卡 -> 回本行首卡; 区块内非首行的行首卡 -> 回区块首行首卡;
    // 区块首行的行首卡 -> hero 主按钮.
    //
    // **不能只看 cardAreaHasFocus**: 从详情页/播放器返回本页时它要等目标行列组合出来、焦点
    // 请求到位才变 true (搜索页实测 300ms~1.9s). 这段窗口里本处判 false, 返回键就被放行到
    // 上层 —— 本页是主页 tab, 那等于**直接弹出退出应用确认** (与 issue #2 同一个根因).
    // cardFocusRequest 非空 = 进页恢复/分层跳转的落点解析还在进行, 视同焦点已在卡片区;
    // 再加恢复请求派出前那一两帧 (仅"曾在某行"时, 见 entryRestoreDispatched).
    // 注意 hero 态不受影响: 那时 focusedRowKey 为 null、cardAreaHasFocus 为 false,
    // 三个条件全不成立, 返回照旧放行去退出应用.
    val restoringToCards = !entryRestoreDispatched && focusedRowKey != null
    BackHandler(enabled = !navLocked && (cardAreaHasFocus || cardFocusRequest != null || restoringToCards)) {
        val key = focusedRowKey
        val sectionFirstKey = if (key == TV_FOLLOWED_ROW_KEY) TV_FOLLOWED_ROW_KEY else tvRecRowKey(0)
        when {
            key == null -> heroFocusRequest = TvHeroFocusRequest(TvHeroFocusButton.PRIMARY)
            focusedCardIndex > 0 -> cardFocusRequest = TvCardFocusRequest(key, cardIndex = 0)
            key != sectionFirstKey -> cardFocusRequest = TvCardFocusRequest(sectionFirstKey, cardIndex = 0)
            else -> heroFocusRequest = TvHeroFocusRequest(TvHeroFocusButton.PRIMARY)
        }
    }
    // **转场窗口内吞掉返回键** (navLocked 的另一半; 原先它只挡前进的确认键). 必须注册在上面
    // 那条之后 —— BackHandler 走 OnBackPressedDispatcher, 后注册的先拿到.
    // 不挡的话: 导航发出后本页还在转场里活着并继续收按键, 这一下返回会被本页或主壳吃掉
    // (主壳那条是 `page != Exploration -> 切回探索页`), 表现为"返回默默生效 / 像返回了两次".
    // 语义是"这一下不算": 到了目标页再按一下就是正常返回.
    BackHandler(enabled = navLocked) { /* 吞掉 */ }
    // 长按返回在本页不再单独注册: 根部兜底统一弹快捷菜单 (回到主界面 / 回到·关闭正在播放 /
    // 刷新本页 / 退出应用, 见 TvQuickActionMenu). 菜单的「回到主界面」落地后把焦点送上轮播
    // 主按钮 —— 发起方可能在别的 tab / 别的目的地 (那一刻本页还没组合出来), 只能留一个标志,
    // 这里看到就消费. heroFocusRequest 会先把按钮组合出来, 再由锚点附着事件送焦, 列表也随
    // hero 态回顶
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
                    // focusedRowKey 置空 = 回 hero 态 (列表随之滚回顶部), 正是这个动作的语义.
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

    // hero 的播放键: 短按直接播当前轮播条目 (按钮本身走确认键进详情, 同卡片的约定).
    // 长按不在这里: 播放键长按是全局手势「打开动作面板」, 由根部统一跟踪器认领
    val heroPlayKeyModifier = tvPlayKeyShortPress(
        onPlay = {
            carouselItem()?.let {
                navigateToPlay(it.bangumiId, it.nameCn, it.imageLarge, "home_trending_play")
                true
            } ?: false
        },
    )
    // 继续观看行的播放键: 短按续播聚焦那部. 一份 modifier 整行共用:
    // 同一时刻只有一张卡有焦点, 按键只会送到那一张; 落点用页面记的"行内聚焦下标"取
    val followedPlayKeyModifier = tvPlayKeyShortPress(
        onPlay = {
            val subject = focusedCardIndex
                .takeIf { focusedRowKey == TV_FOLLOWED_ROW_KEY }
                ?.let { runCatching { followedItems.peek(it) }.getOrNull() }
                ?.subjectInfo
            if (subject != null) {
                navigateToPlay(
                    subject.subjectId, subject.displayName, subject.imageLarge,
                    "home_followed_play",
                )
                true
            } else {
                false
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
            .tvFocusNavSignal(focus)
            // 页面外进来的任何焦点 (侧边栏右键/返回、全局兜底的无方向 requestFocus) 统一在此
            // 改道: 焦点在卡片区时送进进组落点链回上次那张卡, 否则回 hero 主按钮.
            // 不改道的话默认 enter 落到"第一个可聚焦项" —— 卡片区向上出血后停靠线上方那一行
            // 始终组合着且可聚焦, 焦点会落到**上一行**去 (真机: 从详情页返回 ~1s 后跳行,
            // 1s = 导航 crossfade 时长, 详情页销毁时它身上的焦点消失触发全局兜底).
            .focusProperties {
                onEnter = {
                    // 焦点在卡片区 -> 进落点链 (列记行键, 行记下标); 在 hero -> 主按钮.
                    // requestFocus(Enter) 返回是否成功: 失败 (卡片区空 / 按钮碰巧没组合) 一律
                    // 退回 hero 落点请求 —— 它会先把按钮组合出来再等待锚点送焦, 不会让焦点悬空.
                    val target = if (focusedRowKey != null) {
                        columnFocusRequester
                    } else {
                        focus.requesterOf(TvHeroFocusButton.PRIMARY)
                    }
                    val ok = runCatching { target.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
                    if (!ok) heroFocusRequest = TvHeroFocusRequest(TvHeroFocusButton.PRIMARY)
                }
            }
            // onEnter 只在**焦点组**节点上生效: 不加这个的话 focusProperties 会落到下面的
            // 每个焦点目标上而不是充当进出边界, 改道根本不触发 (侧边栏回来仍落到上一行).
            .focusGroup(),
    ) {
        // ------------------------------------------------------------------
        // 背景 backdrop 层
        // ------------------------------------------------------------------
        // 按原比例 (16:9) 缩放, 贴右上角, 高度为屏高的固定比例 (对齐 Prime 实测: 图占屏顶约 76%).
        // 左缘/下缘渐隐入页面背景. 两态渐变: 焦点在 hero 时收得晚, 焦点在卡片区时下缘提前收、
        // 左缘大幅加深, 两组停点间用动画插值平滑过渡.
        val backdropCardness by animateFloatAsState(
            if (heroExpanded) 0f else 1f,
            animationSpec = tween(TV_BACKDROP_STATE_ANIM_MILLIS),
            label = "backdropCardness",
        )
        // 渐隐 = 直接叠画页面底色渐变 (本页在主壳内, 图下即 shellBackgroundColor 纯色),
        // 与旧 DstOut 擦除逐像素等价但不需要离屏合成 (2026-07-31 性能整改).
        // 本层与追番/搜索两页是同一个组件 (它就是从这里抽出去的): 探索页多的只有两态渐变插值
        // (cardness) 和自己那一档高度, 顶缘压暗则不要 —— 本页顶部是空的.
        // 邻居图片预热的范围与档位见 TvHeroNeighbors.singleStep / TvHeroImagePrefetch.
        TvPageBackdropLayer(
            backdropUrl = backdropUrl,
            fadeColor = AniThemeDefaults.shellBackgroundColor,
            modifier = Modifier.align(Alignment.TopEnd),
            heightFraction = TV_EXPLORATION_BACKDROP_HEIGHT_FRACTION,
            topScrim = false,
            cardness = { backdropCardness },
            underlayUrl = backdropUnderlayUrl,
            // 这张图解码完顺手算主题色, 点进详情页第一帧就是动态色 (详情页取的也是这张)
            themeSeedSubjectId = backdropSubjectId,
        )

        // ------------------------------------------------------------------
        // hero 覆盖层 (z 在卡片区之下: 出血上移的离场行淡出时从它上面滑过)
        // ------------------------------------------------------------------
        // 左侧再留 TV_EXPLORATION_START_PAD (外层已让开侧边栏 48dp) —— 总左缘 64dp,
        // 使侧边栏按钮中心 (32dp) 恰好在屏幕左缘与内容左缘的正中间. 下同.
        TvExplorationHeroOverlay(
            heroTarget = heroDisplay,
            infoCache = infoCache,
            episodeStillCache = episodeStillCache,
            summaryFallbackCache = summaryFallbackCache,
            playHistories = { playHistories },
            heroExpanded = heroExpanded,
            heroFocusRequestActive = heroFocusRequest != null,
            listState = listState,
            heroPlayKeyModifier = heroPlayKeyModifier,
            carouselIndex = { carouselIndex },
            switchCarousel = switchCarousel,
            onWatchNowClick = {
                carouselItem()?.let {
                    navigateToSubject(it.bangumiId, it.nameCn, it.imageLarge, "home_trending_detail")
                }
            },
            onScheduleClick = { navigator.navigateSchedule() },
            onNavigateDownToCards = {
                // 下键进首行 (恢复该行上次聚焦卡). 不走进组落点链: 它记的是"上次聚焦的
                // 行", 可能在深处 —— 而 hero 态列表已滚回顶部, 用户看到的是首行, 焦点跑到
                // 看不见的深行会把列表又拉下去.
                if (rowCount > 0) {
                    cardFocusRequest = TvCardFocusRequest(rowKeyAt(0), cardIndex = -1)
                    true
                } else {
                    false
                }
            },
            onHeroButtonFocused = { focusedRowKey = null },
            primaryFocusModifier = Modifier
                .tvFocusAnchor(focus, TvHeroFocusButton.PRIMARY)
                .onFocusChanged {
                    if (it.isFocused && heroFocusRequest?.button == TvHeroFocusButton.PRIMARY) heroFocusRequest = null
                },
            scheduleFocusModifier = Modifier
                .tvFocusAnchor(focus, TvHeroFocusButton.SCHEDULE)
                .onFocusChanged {
                    if (it.isFocused && heroFocusRequest?.button == TvHeroFocusButton.SCHEDULE) heroFocusRequest = null
                },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = TV_EXPLORATION_START_PAD, top = TV_EXPLORATION_HERO_TOP, end = TV_PAGE_END_PAD),
        )

        // ------------------------------------------------------------------
        // 锚位区块标签覆盖层: 钉在标签线上, 显示**聚焦行所属区块**名 —— 只在标签线上没有任何
        // 行内标题的时候才出现 (alpha = 1 − 两个行内标题里"占着标签线"的那份, 见
        // [tvSectionHeaderAlpha]). 于是可见的标签恒为一份: 行内标题跟着卡片一起移动, 到标签线
        // 正好停在本层位置上 (hero → 首行那一段就是它在移动, 不是"消失后在上方出现"), 再往下
        // 换行才越线快速淡出、由本层无缝接手.
        //
        // 取两者的 max 而不是只看聚焦区块那一个: 上键回"继续观看"时聚焦区块瞬间切换, 而"推荐"
        // 标题还压在标签线上要滑下去, 只看聚焦区块的话本层会在那一帧直接跳出来, 与它叠成双词.
        // ------------------------------------------------------------------
        val anchorLabel = when {
            rowCount == 0 -> null
            (focusedRowIndex ?: 0) < followedRowCount -> stringResource(Lang.exploration_continue_watching)
            else -> stringResource(Lang.exploration_recommendations)
        }
        if (anchorLabel != null) {
            TvSectionHeader(
                anchorLabel,
                Modifier.padding(start = TV_EXPLORATION_START_PAD, top = TV_EXPLORATION_LABEL_TOP)
                    .graphicsLayer {
                        alpha = 1f - maxOf(
                            tvSectionHeaderAlpha(listState, TV_FOLLOWED_HEADER_KEY, this),
                            tvSectionHeaderAlpha(listState, TV_REC_HEADER_KEY, this),
                        )
                    },
            )
        }

        // ------------------------------------------------------------------
        // 卡片区: 顶边钉在 TV_EXPLORATION_CARD_TOP, 几何与 hero/标签完全解耦.
        //
        // 整个区向左/向上出血 (布局上仍按原尺寸参与排版, 仅测量/放置扩一段): 离场内容不被
        // 内容区边缘 90 度硬切 —— 向左滑出的卡片压暗着滑过侧边栏区域, 向上滑出的行越过锚位
        // 继续上移、压着 hero 区域边移边淡 (Prime 实拍: "移出去边淡", 不是原地消失). 锚位线
        // 经 LazyRow contentPadding start / LazyColumn contentPadding top 退回原位. 出血必须
        // 做在 Lazy 容器外面的测量层: LazyRow/LazyColumn 自带主轴硬裁剪, 行内做不到.
        // ------------------------------------------------------------------
        val density = LocalDensity.current
        // 锚位 = 内容 rest 位置 (LazyColumn top contentPadding / LazyRow start contentPadding)
        // **加上 [TvFocusRing.Gap]**: bring-into-view 拿到的是**焦点目标**矩形, 而焦点目标是
        // 卡片内缩一圈之后的封面, 聚焦框却画在外框上 —— 不加这一点点, 框与卡片就差 3dp 对不齐
        // (真机肉眼可见), 且 rest 位置与 pivot 目标不一致会让首行永远差这一段.
        val verticalBringIntoViewSpec = remember(density) {
            tvAnchorBringIntoViewSpec(with(density) { (TV_EXPLORATION_TOP_BLEED + TvFocusRing.Gap).toPx() })
        }
        val horizontalBringIntoViewSpec = remember(density) {
            tvAnchorBringIntoViewSpec(with(density) { (TV_EXPLORATION_ROW_START_BLEED + TvFocusRing.Gap).toPx() })
        }
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .padding(start = TV_EXPLORATION_START_PAD, top = TV_EXPLORATION_CARD_TOP)
                .layout { measurable, constraints ->
                    val bleedX = TV_EXPLORATION_ROW_START_BLEED.roundToPx()
                    val bleedY = TV_EXPLORATION_TOP_BLEED.roundToPx()
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = constraints.maxWidth + bleedX,
                            maxWidth = constraints.maxWidth + bleedX,
                            minHeight = constraints.maxHeight + bleedY,
                            maxHeight = constraints.maxHeight + bleedY,
                        ),
                    )
                    layout(placeable.width - bleedX, placeable.height - bleedY) {
                        placeable.place(-bleedX, -bleedY)
                    }
                },
        ) {
            // 末行也要能停到锚位: 行尾留出"可见高度 − 一行卡高"的空白, 否则列表滚到内容底
            // 就停住, 末行只能停在锚位下方 (固定聚焦框下错位很显眼). maxHeight 是出血后的
            // 高度, 减掉出血才是可见高度.
            val lastRowBottomPad = (
                    maxHeight - TV_EXPLORATION_TOP_BLEED -
                            TV_PAGE_CARD_WIDTH / TV_PORTRAIT_CARD_COVER_RATIO
                    ).coerceAtLeast(TV_EXPLORATION_MIN_BOTTOM_PAD)
            CompositionLocalProvider(LocalBringIntoViewSpec provides verticalBringIntoViewSpec) {
                LazyColumn(
                    Modifier.fillMaxSize().clipToBounds()
                        .focusRequester(columnFocusRequester)
                        // 进入卡片区落回上次聚焦的行 (行内再由行自己落回上次聚焦的卡).
                        // 同样按**键**记而不用 focusRestorer: 行滚出视口后节点销毁, 引用失效
                        // 会退化成"第一个可聚焦项"= 出血区里上一行的隐形首卡.
                        .focusProperties {
                            onEnter = {
                                // 用返回布尔的那个重载: 锚点行没组合出来时 (行滚出纵向视口, 或
                                // 分页刚重新 present、整行还不存在) 请求是**失败**而不是抛异常,
                                // 早先 runCatching 包 Unit 重载把这两种都吞了 -> 放行默认进组
                                // -> 焦点落到组合窗口最左那张卡 -> 那张卡的 onFocused 改写簿记,
                                // 恢复目标就此丢失 (真机: 一路返回后焦点停在推荐区).
                                val ok = runCatching {
                                    anchorRowRequester.requestFocus(FocusDirection.Enter)
                                }.getOrDefault(false)
                                val target = anchorRowKey
                                if (!ok && target != null && cardFocusRequest?.rowKey != target) {
                                    // 转显式落点请求: 它负责把目标行滚进组合, 行内解析再聚焦到卡.
                                    // 这里刻意不 cancelFocusChange() —— 目标行迟迟不来的话那会让整页
                                    // 一个焦点都没有 (方向键全失效); 让默认进组先给个落点, 簿记
                                    // 由 recordFocusedCard 挡住不被改写, 更稳妥.
                                    cardFocusRequest = TvCardFocusRequest(target, cardIndex = -1)
                                }
                            }
                        }
                        .focusGroup()
                        // 长按方向键的移动频率上限: 系统连发 ~20 次/秒, 每发都换卡的话滑动
                        // 动画不断被打断. 挂在整个卡片区上, 上下左右一起限.
                        .tvFocusMoveRateLimit()
                        .onFocusChanged { cardAreaHasFocus = it.hasFocus },
                    state = listState,
                    contentPadding = PaddingValues(
                        top = TV_EXPLORATION_TOP_BLEED,
                        end = TV_PAGE_END_PAD,
                        bottom = lastRowBottomPad,
                    ),
                    verticalArrangement = Arrangement.spacedBy(TV_EXPLORATION_ROW_GAP),
                ) {
                    // 区块标题是列表 item (与 main 一致): 非聚焦区块的标题跟着卡片行一起在
                    // 下方可见 ("继续观看"聚焦时下面看得到"推荐"), hero 态则整段露在 hero 下方
                    // —— 于是 hero 态的行比卡片态低一个标题块, hero↔卡片切换就是这一段的
                    // spring 滚动 (自然, 且卡片区几何恒定所以不会抖).
                    // 标题的可见性**位置驱动**(同卡片行的 rowTopFade): 一路跟着卡片上移, 停在
                    // 标签线上仍是它在显示, 越线才快速淡出并由固定标签覆盖层接手 —— 不做
                    // "聚焦即透明"的瞬时切换 (那样表现为"先消失再在上方出现").
                    if (hasFollowed) {
                        item(key = TV_FOLLOWED_HEADER_KEY) {
                            TvSectionHeader(
                                stringResource(Lang.exploration_continue_watching),
                                Modifier.padding(start = TV_EXPLORATION_ROW_START_BLEED)
                                    .sectionHeaderTopFade(listState, TV_FOLLOWED_HEADER_KEY),
                            )
                        }
                        item(key = TV_FOLLOWED_ROW_KEY) {
                            TvAnchoredCardRow(
                                itemCount = followedItems.itemCount,
                                isFirstRow = true,
                                onNavigateUpToHero = {
                                    heroFocusRequest = TvHeroFocusRequest(TvHeroFocusButton.SCHEDULE)
                                },
                                focusRequest = cardFocusRequest?.takeIf { it.rowKey == TV_FOLLOWED_ROW_KEY },
                                onFocusRequestDone = { cardFocusRequest = null },
                                bringIntoViewSpec = horizontalBringIntoViewSpec,
                                modifier = Modifier.rowTopFade(listState, TV_FOLLOWED_ROW_KEY)
                                    // 进卡片区的落点行 (见 LazyColumn 的 onEnter)
                                    .ifThen(anchorRowKey == TV_FOLLOWED_ROW_KEY) {
                                        focusRequester(anchorRowRequester)
                                    },
                            ) { index, reportFocus ->
                                val item = followedItems[index]
                                val subject = item?.subjectInfo
                                TvPortraitCard(
                                    imageUrl = subject?.imageLarge,
                                    contentDescription = subject?.displayName,
                                    onClick = {
                                        subject?.let {
                                            navigateToSubject(it.subjectId, it.displayName, it.imageLarge, "home_followed")
                                        }
                                    },
                                    onFocused = {
                                        item?.let {
                                            // 在看条目自带完整信息, 种入缓存立即显示评分/连载/简介
                                            onFocusItem(
                                                it.subjectInfo.subjectId,
                                                it.subjectInfo.displayName,
                                                it.subjectCollectionInfo,
                                                true,
                                                it.subjectInfo.imageLarge,
                                                // 本行只有左右; 左边刚来过必然是热的, 只取顺方向.
                                                // 整行都是"在看", 邻居一律走单集剧照那一档
                                                TvHeroNeighbors(
                                                    singleStep = listOfNotNull(
                                                        followedItems.peekOrNull(index + 1)
                                                            ?.subjectInfo?.subjectId
                                                            ?.let { TvHeroNeighbor(it, true) },
                                                    ),
                                                    urlOnly = listOfNotNull(
                                                        followedItems.peekOrNull(index + 2)
                                                            ?.subjectInfo?.subjectId
                                                            ?.let { TvHeroNeighbor(it, true) },
                                                    ),
                                                ),
                                            )
                                        }
                                        recordFocusedCard(TV_FOLLOWED_ROW_KEY, index)
                                        reportFocus()
                                    },
                                    modifier = Modifier.width(TV_PAGE_CARD_WIDTH)
                                        // 播放键: 短按直接进播放器续播, 长按强制重拉本栏目
                                        .then(followedPlayKeyModifier),
                                    // 聚焦框由卡片区固定锚位的 TvPortraitCardFocusRing 统一画
                                    showFocusRing = false,
                                    menu = subject?.let { collectionMenuFor(it.subjectId) },
                                    // 下一集的播放进度 (语义同详情页选集卡): 看到一半按播放位置;
                                    // 已看完最新一集在等更新 (Watched) / 看完全部 (Done) 显示满条;
                                    // 看完上一集且有新集 / 还没开始看 (无播放记录) 不显示
                                    progress = item?.subjectCollectionInfo?.progressInfo?.let { progressInfo ->
                                        val caughtUp = progressInfo.continueWatchingStatus.let {
                                            it is ContinueWatchingStatus.Watched || it is ContinueWatchingStatus.Done
                                        }
                                        if (caughtUp) 1f
                                        else progressInfo.nextEpisodeIdToPlay
                                            ?.let { nextId -> playHistories.firstOrNull { it.episodeId == nextId } }
                                            ?.let { history ->
                                                val duration = history.durationMillis
                                                if (duration != null && duration > 0) {
                                                    (history.positionMillis.toFloat() / duration).coerceIn(0f, 1f)
                                                } else null
                                            }
                                    },
                                )
                            }
                        }
                    }

                    if (recRowCount > 0) {
                        item(key = TV_REC_HEADER_KEY) {
                            TvSectionHeader(
                                stringResource(Lang.exploration_recommendations),
                                Modifier.padding(start = TV_EXPLORATION_ROW_START_BLEED)
                                    .sectionHeaderTopFade(listState, TV_REC_HEADER_KEY),
                            )
                        }
                    }
                    // 推荐: 每行固定行容量, 行数随分页无限增长 (纵向无限行)
                    items(recRowCount, key = { tvRecRowKey(it) }) { recRow ->
                        val rowKey = tvRecRowKey(recRow)
                        val rowStart = recRow * TV_EXPLORATION_REC_ROW_SIZE
                        val rowItemCount = minOf(TV_EXPLORATION_REC_ROW_SIZE, recommendations.itemCount - rowStart)
                        val absoluteRow = followedRowCount + recRow
                        // 压暗档 (Prime 式主次): 全亮只留给聚焦行及其上方 (上方的淡出交给位置
                        // 驱动), 聚焦行之下的预览行压暗; hero 态不压暗 —— 那时整个卡片区都是
                        // 预览, 主次由 backdrop 层级表达.
                        val rowDimTarget by remember(absoluteRow) {
                            derivedStateOf {
                                val focused = focusedRowKey?.let(rowIndexOfKey)
                                if (focused == null || absoluteRow <= focused) 1f else TV_ROW_UNFOCUSED_DIM_ALPHA
                            }
                        }
                        val rowDimAlpha = animateFloatAsState(
                            rowDimTarget,
                            tween(TV_ROW_DIM_FADE_MILLIS),
                            label = "rowDim",
                        )
                        TvAnchoredCardRow(
                            itemCount = rowItemCount,
                            // 无继续观看时推荐首行就是最顶行, 按上键回 hero
                            isFirstRow = absoluteRow == 0,
                            onNavigateUpToHero = { heroFocusRequest = TvHeroFocusRequest(TvHeroFocusButton.SCHEDULE) },
                            focusRequest = cardFocusRequest?.takeIf { it.rowKey == rowKey },
                            onFocusRequestDone = { cardFocusRequest = null },
                            bringIntoViewSpec = horizontalBringIntoViewSpec,
                            modifier = Modifier.rowTopFade(listState, rowKey) { rowDimAlpha.value }
                                // 进卡片区的落点行 (见 LazyColumn 的 onEnter)
                                .ifThen(anchorRowKey == rowKey) { focusRequester(anchorRowRequester) },
                            // 推荐行横向循环: 末卡右侧即首卡
                            loop = true,
                        ) { localIndex, reportFocus ->
                            val item = recommendations[rowStart + localIndex] as? RecommendedSubjectInfo
                            TvPortraitCard(
                                imageUrl = item?.imageLarge,
                                contentDescription = item?.nameCn,
                                onClick = {
                                    item?.let {
                                        navigateToSubject(it.bangumiId, it.nameCn, it.imageLarge, "home_recommendation")
                                    }
                                },
                                onFocused = {
                                    item?.let {
                                        onFocusItem(
                                            it.bangumiId, it.nameCn, null, false, it.imageLarge,
                                            // 顺方向两格 + 下一行同列 (gridKeyNavigation 的下键落点)
                                            tvRecNeighborsOf(recommendations, rowStart + localIndex),
                                        )
                                    }
                                    recordFocusedCard(rowKey, localIndex)
                                    reportFocus()
                                },
                                modifier = Modifier.width(TV_PAGE_CARD_WIDTH)
                                    // 播放键**短按**: 直接进播放器 (无进度从第一集; 信息未加载退化为详情).
                                    // 必须走 tvPlayKeyShortPress 而不是自己判 KeyDown —— 播放键按下
                                    // 那一刻还分不出短按还是长按, 自己在 KeyDown 处理会把全局的长按手势
                                    // (打开动作面板) 整个吃掉. 这里原先正是那么写的
                                    .then(
                                        tvPlayKeyShortPress(
                                            onPlay = {
                                                item?.let {
                                                    navigateToPlay(
                                                        it.bangumiId, it.nameCn, it.imageLarge,
                                                        "home_recommendation_play",
                                                    )
                                                    true
                                                } ?: false
                                            },
                                        ),
                                    ),
                                // 聚焦框由卡片区固定锚位的 TvPortraitCardFocusRing 统一画
                                showFocusRing = false,
                                menu = item?.let { collectionMenuFor(it.bangumiId) },
                            )
                        }
                    }
                }
            }
            // 固定锚位聚焦框 (Prime Video 式): 焦点在卡片区内才显示. 行间/行内导航期间卡片
            // 仍在赶路, 框先亮在锚位等卡片滑进来. 卡片区已向左/向上出血, 框用同量 padding
            // 退回锚位线; 画在 LazyColumn 裁剪边界外, 不会被滑出的行裁掉.
            if (cardAreaHasFocus) {
                TvPortraitCardFocusRing(
                    Modifier.padding(
                        start = TV_EXPLORATION_ROW_START_BLEED,
                        top = TV_EXPLORATION_TOP_BLEED,
                    ),
                )
            }
        }

        // 轮播指示器 (不可聚焦): 垂直位置钉在 hero backdrop 下边界, 水平居中; 仅 hero 态显示.
        if (heroExpanded && carouselSize > 1) {
            Box(
                Modifier.fillMaxWidth()
                    .fillMaxHeight(TV_EXPLORATION_BACKDROP_HEIGHT_FRACTION),
                contentAlignment = Alignment.BottomCenter,
            ) {
                TvCarouselIndicator(
                    count = carouselSize,
                    selectedIndex = carouselIndex.coerceIn(0, carouselSize - 1),
                    modifier = Modifier.padding(bottom = TV_CAROUSEL_INDICATOR_EDGE_RAISE),
                )
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

/**
 * hero 覆盖层: 定高信息块 (标题 / 评分连载 / [继续观看的下一集行] / 简介) + 按钮块.
 * 纯覆盖层 —— 它的任何显隐/高度变化都不影响卡片区 (卡片区顶边是常量).
 *
 * 按钮块只在 hero 态组合 (加上 [heroFocusRequestActive]: 落点请求要先把按钮组合出来才能聚焦);
 * 显示上等卡片区滚回顶部才淡入 —— 从深处的行回 hero 时列表还在滚, 出血让归位途中的行画在
 * 按钮区域上, 先显示会叠字.
 */
@Composable
private fun TvExplorationHeroOverlay(
    // lambda 而非值: hero 目标是每格方向键都变的热状态, 收值的话那次读记在**调用方**
    // (页面 body) 身上, 换一张卡整页重跑. 收 lambda 则只有本组件重组. 同 [TvPageBackdropLayer].
    heroTarget: () -> TvHeroTarget?,
    infoCache: Map<Int, SubjectCollectionInfo>,
    episodeStillCache: Map<Int, TvNextEpisodeMedia>,
    summaryFallbackCache: Map<Int, String>,
    playHistories: () -> List<EpisodeHistory>,
    heroExpanded: Boolean,
    heroFocusRequestActive: Boolean,
    listState: LazyListState,
    heroPlayKeyModifier: Modifier,
    carouselIndex: () -> Int,
    switchCarousel: (Int) -> Unit,
    onWatchNowClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onNavigateDownToCards: () -> Boolean,
    onHeroButtonFocused: () -> Unit,
    primaryFocusModifier: Modifier,
    scheduleFocusModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    // 覆盖层高度按"按钮块在不在"两档 (同 main 的几何): 有按钮时块底钉在锚位线上方一个行距,
    // 即按钮下缘到"继续观看"标题恰好 TV_EXPLORATION_ROW_GAP —— 按钮块自己多高都不会挤掉这段
    // 间距, 也不会在下面留一片空白; 没按钮 (焦点在卡片区) 时块底钉在固定标签的上边线.
    // 落点请求期间 (上键回 hero, 按钮先组合出来才能聚焦) 就按有按钮算, 否则简介会先缩一下再弹回.
    val buttonsPresent = heroExpanded || heroFocusRequestActive
    Column(modifier.height(if (buttonsPresent) TV_HERO_BLOCK_HEIGHT_EXPANDED else TV_HERO_BLOCK_HEIGHT)) {
        // 信息块吃掉按钮块之外的全部高度: hero 态简介少两行给按钮, 卡片态满高 —— 覆盖层内部
        // 怎么分配都与卡片区无关. 换聚焦条目时整块文字渐隐渐现 (contentKey=条目); 块内顶对齐:
        // 标题固定在块顶, 有 info 时简介 weight(1f) 撑满至块底, info 到达不引起位置跳动.
        AnimatedContent(
            targetState = heroTarget(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            transitionSpec = {
                fadeIn(tween(TV_HERO_TEXT_FADE_MILLIS)) togetherWith
                        fadeOut(tween(TV_HERO_TEXT_FADE_MILLIS))
            },
            contentKey = { it?.subjectId },
            label = "heroInfoText",
        ) { target ->
            val info = target?.let { infoCache[it.subjectId] }
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (target != null) {
                    // 定高一行 + 超宽跑马灯 (宽度框不变): 长标题原本会换到第二行,
                    // 把介绍挤掉一行, 且不同条目间标题一行/两行来回跳
                    Text(
                        target.title,
                        Modifier.fillMaxWidth(TV_HERO_TITLE_WIDTH_FRACTION)
                            .basicMarquee(iterations = tvHeroMarqueeIterations()),
                        color = tvHeroContentColor(),
                        style = MaterialTheme.typography.headlineLarge,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip, // 跑马灯滚全文, 不要省略号
                    )
                }
                if (info != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // 评分: ★ 评分数字/10
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
                        // 连载信息, 后面跟一个空格 + 开播年月
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AiringLabel(
                                remember(info) {
                                    AiringLabelState(
                                        stateOf(info.airingInfo),
                                        stateOf(info.progressInfo),
                                    )
                                },
                                style = MaterialTheme.typography.labelLarge,
                                progressColor = tvHeroSecondaryContentColor(),
                            )
                            val airDate = info.subjectInfo.airDate
                            if (airDate.isValid) {
                                Text(
                                    "    " + stringResource(
                                        Lang.exploration_tv_air_date,
                                        airDate.year, airDate.month,
                                    ),
                                    color = tvHeroSecondaryContentColor(),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                    // 继续观看: 独立一行显示下一集集号 + 集名 (Bangumi 本地数据, 即时显示)
                    val nextEp = if (target?.fromFollowed == true) {
                        info.progressInfo.nextEpisodeIdToPlay?.let { nextId ->
                            info.episodes.firstOrNull { it.episodeId == nextId }
                        }
                    } else null
                    if (nextEp != null) {
                        val epLabel = stringResource(
                            Lang.playback_history_episode_label,
                            nextEp.episodeInfo.sort.toString(),
                        )
                        val epName = nextEp.episodeInfo.nameCn.ifBlank { nextEp.episodeInfo.name }
                        // 三态 (nextEp 语义见 SubjectProgressInfo.compute — 追平时指回已看完的最新一集):
                        //  - 已看完最新一集/全部 (Watched/Done): "第 8 集 · 集名 · 已看完"
                        //  - 看到一半 (有播放记录): "第 4 集 · 集名 · 剩余 23 分钟"
                        //  - 看完上一集且有新集/还没开始: "下一集: 第 4 集 · 集名".
                        // 集号与尾段为固定段永不截断; 集名居中段, 超长跑马灯滚动展示全文
                        val caughtUp = info.progressInfo.continueWatchingStatus.let {
                            it is ContinueWatchingStatus.Watched || it is ContinueWatchingStatus.Done
                        }
                        val remainingMinutes = if (caughtUp) null else playHistories()
                            .firstOrNull { it.episodeId == nextEp.episodeId }
                            ?.let { history ->
                                val duration = history.durationMillis
                                if (duration != null && duration > 0 && history.positionMillis > 0) {
                                    // 向上取整: 剩 30 秒也显示 1 分钟
                                    (((duration - history.positionMillis).coerceAtLeast(0L) + 59_999) / 60_000)
                                        .toInt().coerceAtLeast(1)
                                } else null
                            }
                        Row(
                            Modifier.fillMaxWidth(TV_HERO_SUMMARY_WIDTH_FRACTION)
                                // 定高使"本行 + 10dp 列间距"恰为简介两行行距 (2×20dp):
                                // 有无此行时简介的换行网格对齐, 最后一行结束位置一致
                                .height(TV_HERO_STATUS_ROW_HEIGHT),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val epInfoColor = tvHeroSecondaryContentColor()
                            val epInfoStyle = MaterialTheme.typography.labelLarge
                            Text(
                                if (caughtUp || remainingMinutes != null) epLabel
                                else stringResource(Lang.exploration_tv_next_episode, epLabel),
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
                                // 连载已追平: "已看完最新一集 · 周三更新" (更新时间同详情页观看按钮,
                                // WeekFormatter); 完结看完: "已看完"
                                val watchedStatus = info.progressInfo.continueWatchingStatus
                                        as? ContinueWatchingStatus.Watched
                                val updatesOn = watchedStatus?.nextEpisodeAirDate?.toLocalDateOrNull()
                                    ?.let { date ->
                                        stringResource(
                                            Lang.subject_progress_updates_on,
                                            WeekFormatter.System.format(date),
                                        )
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
                                    " · " + stringResource(
                                        Lang.exploration_tv_minutes_left, remainingMinutes,
                                    ),
                                    color = epInfoColor,
                                    style = epInfoStyle,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    // 简介占满信息块剩余高度: 行数由 TV_HERO_BLOCK_HEIGHT 决定; 宽度用单独的
                    // HERO_SUMMARY_WIDTH_FRACTION, 调小让右边留给 backdrop 清晰区.
                    // 继续观看: 优先展示下一集的 TMDB 单集简介, 缺失回退整部简介
                    // 两次查表收进 derivedStateOf: 这两张表是**进程级共享**的
                    // (TvHeroMediaCache), 邻居预取会为用户还没看到的条目写入, 而
                    // SnapshotStateMap 没有按键订阅粒度 —— 直接在这里读的话, 一次聚焦最多
                    // 8 次邻居写入全都会重组这个文字块, 正好抵消掉预取想换来的流畅
                    // (同一份顾虑见 TvHeroMediaCache.subjectInfos 处的说明).
                    // derived 之后写入只重算这个字符串, 值没变就不往下传播.
                    val summaryText by remember(target, info) {
                        derivedStateOf {
                            val nextEpOverview = target?.takeIf { it.fromFollowed }
                                ?.let { episodeStillCache[it.subjectId]?.overview }
                                ?.takeIf { it.isNotBlank() }
                            nextEpOverview
                                ?: info.subjectInfo.summary.trim()
                                    .ifBlank { target?.let { summaryFallbackCache[it.subjectId] }.orEmpty() }
                        }
                    }
                    Text(
                        summaryText,
                        Modifier.weight(1f).fillMaxWidth(TV_HERO_SUMMARY_WIDTH_FRACTION),
                        color = tvHeroContentColor(),
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 按钮块: 一颗作用于当前轮播条目的动作钮 + 一颗页面级入口. 组合条件带上落点请求
        // (请求要先组合出按钮才能聚焦). 显示上等卡片区滚回顶部才淡入 (见 KDoc); 只淡出
        // 不提前移除 —— 按钮可能还持有焦点, 提前销毁会让焦点悬空被全局兜底抢走.
        // 关闭 48dp 最小可交互尺寸约束, 否则缩小后的按钮被撑到 48dp 高、内容居中.
        if (buttonsPresent) {
            val listAtTop by remember(listState) {
                derivedStateOf {
                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                }
            }
            val buttonsShown = heroExpanded && listAtTop
            val buttonsAlpha = animateFloatAsState(
                if (buttonsShown) 1f else 0f,
                tween(if (buttonsShown) TV_HERO_BUTTON_FADE_IN_MILLIS else TV_HERO_BUTTON_FADE_OUT_MILLIS),
                label = "heroButtonsAlpha",
            )
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Column(
                    Modifier
                        .padding(top = TV_HERO_INFO_TO_BUTTONS_GAP)
                        // 淡入淡出读在 lambda 里, 过程只失效图层不重组
                        .graphicsLayer { alpha = buttonsAlpha.value }
                        .then(heroPlayKeyModifier)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    if (carouselIndex() > 0) {
                                        switchCarousel(-1)
                                        true
                                    } else {
                                        false // 第一个条目: 交给焦点系统 -> 侧边栏探索按钮
                                    }
                                }

                                Key.DirectionRight -> {
                                    switchCarousel(1)
                                    true
                                }

                                else -> false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(TV_HERO_BUTTON_GAP),
                ) {
                    // hero 只留一颗动作钮, 与卡片同一套约定: 确认 = 进详情, 播放键 = 直接播.
                    // 图标用播放三角与遥控器播放键呼应 —— 省下的那一行给时间表入口
                    TvHeroButton(
                        text = stringResource(Lang.exploration_tv_watch_now),
                        icon = Icons.Rounded.PlayArrow,
                        filled = true,
                        onClick = onWatchNowClick,
                        onFocused = onHeroButtonFocused,
                        // 进入主页 / 从卡片区按上返回时的聚焦目标
                        modifier = primaryFocusModifier,
                    )
                    // 新番时间表入口: 页面级目的地, 与上面那颗"对当前条目的动作"不是同一层级 ——
                    // 用描边款区分. hero 里最下面的可聚焦项: 下键显式路由进首行 (显式是必要的:
                    // 卡片区向上出血后其焦点组边界盖过 hero, 空间搜索不再认为它在"下方").
                    // 只能挂在本颗 (最下面) 按钮上: onPreviewKeyEvent 父先手, 挂整个按钮块会把
                    // 上面那颗的下键也吞掉, 永远到不了本按钮 (真机踩过).
                    TvHeroButton(
                        text = stringResource(Lang.exploration_schedule),
                        icon = Icons.Rounded.CalendarMonth,
                        filled = false,
                        onClick = onScheduleClick,
                        onFocused = onHeroButtonFocused,
                        modifier = scheduleFocusModifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                onNavigateDownToCards()
                            } else {
                                false
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 固定锚点横向卡片行 (探索页行内滚动): 聚焦卡片始终停靠在锚位, 按左右键时焦点视觉位置不动,
 * 卡片列表整体滑过 —— 滚动由 [bringIntoViewSpec] (pivot 式) 在卡片聚焦时自动完成, 本组件
 * 不驱动滚动. 行尾留出整行空白让末卡也能停到锚位.
 *
 * 焦点记忆: 进入本行落回上次聚焦的卡 —— **按下标记 (`focusedIndex`), 不用官方
 * `focusRestorer()`**. 后者记的是**节点引用**: 行整条滚出纵向视口 (或卡片还没组合出来) 时
 * 节点已销毁, 引用失效就退化成"进组第一个可聚焦项"= 当前组合窗口里最左那张卡, 于是 pivot
 * 把本行拉到那张卡上 —— 真机症状"上下导航时卡片左右挪一段, 与速度无关, 某些行必现".
 * 下标存在 `rememberSaveable` 里, 由 LazyColumn 的 SaveableStateHolder 跨行销毁保住.
 *
 * [focusRequest] 是显式落点 (进页恢复 / 返回键分层), 解析带到位确认 + 滚动组合重试,
 * cardIndex < 0 表示"本行保存的上次聚焦卡".
 */
@Composable
private fun TvAnchoredCardRow(
    itemCount: Int,
    isFirstRow: Boolean,
    onNavigateUpToHero: () -> Unit,
    focusRequest: TvCardFocusRequest?,
    onFocusRequestDone: () -> Unit,
    bringIntoViewSpec: BringIntoViewSpec,
    modifier: Modifier = Modifier,
    loop: Boolean = false,
    card: @Composable (index: Int, reportFocus: () -> Unit) -> Unit,
) {
    // 上次聚焦卡, 跨导航/跨行销毁保存 (LazyColumn 的 saveable holder 保住)
    var focusedIndex by rememberSaveable { mutableIntStateOf(-1) }
    var rowHasFocus by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focus = rememberTvFocusScope()
    // 从行外进入本行的落点: 挂在"上次聚焦的那张卡"上 (见 KDoc 为何不用 focusRestorer).
    // 请求失败 (那张卡还没组合出来) 时不做任何事, 让默认进组行为兜住这一帧.
    val enterRequester = remember { FocusRequester() }
    // 落点解析: 目标卡挂 scope 锚点; 未组合时请求悬挂, scrollToItem 后 attach 即送达.
    var resolvedTarget by remember { mutableIntStateOf(-1) }
    val currentOnDone by rememberUpdatedState(onFocusRequestDone)
    // 进组自救: 上次聚焦卡还没组合出来 (分页数据没到 / 行刚滚回视口) 时进组请求会失败,
    // 此时**必须取消这次进组**并走解析 —— 放行默认进组会落到"组合窗口里最左那张卡",
    // pivot 随即把整行拉到它上面, 就是真机看到的"上下导航时卡片左右挪一段".
    var pendingEnter by remember { mutableStateOf<Int?>(null) }
    var resolvedNavGeneration by remember { mutableIntStateOf(-1) }
    val finishResolve = {
        resolvedTarget = -1
        pendingEnter = null
        if (focusRequest != null) currentOnDone()
    }
    LaunchedEffect(focusRequest, pendingEnter, itemCount) {
        val wantedIndex = when {
            focusRequest != null -> focusRequest.cardIndex
            pendingEnter != null -> pendingEnter
            else -> {
                resolvedTarget = -1
                return@LaunchedEffect
            }
        }
        if (itemCount <= 0) {
            resolvedTarget = -1
            return@LaunchedEffect
        }
        val idx = when {
            wantedIndex != null && wantedIndex >= 0 -> wantedIndex
            focusedIndex >= 0 -> focusedIndex
            else -> 0
        }.let { if (loop && itemCount > 1) it else it.coerceAtMost(itemCount - 1) }
        resolvedTarget = idx
        resolvedNavGeneration = focus.userNavGeneration
        if (rowHasFocus && focusedIndex == idx) {
            finishResolve()
        } else {
            focus.request(ExplorationRowCardFocus(idx))
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) {
                runCatching { listState.scrollToItem(idx) }
            }
        }
    }
    // 用户自行导航时 scope 已取消 pending; 同步结束页面级请求，避免旧目标继续影响进组改道.
    LaunchedEffect(focus.userNavGeneration) {
        if (resolvedTarget >= 0 && focus.userNavGeneration != resolvedNavGeneration) finishResolve()
    }
    // 横向循环: 用虚拟"无限"列表, 卡片按 index % itemCount 取 —— 右移到末卡再右即回到首卡.
    // 起点在 index 0 (首卡在最左), 左移到首卡再左则离开本行 (交给焦点系统 -> 侧边栏).
    val loopEnabled = loop && itemCount > 1
    val virtualCount = if (loopEnabled) Int.MAX_VALUE else itemCount
    BoxWithConstraints(
        modifier.ifThen(isFirstRow) {
            // 最顶行按上键回 hero (显式路由: hero 按钮在卡片态没有组合, 空间搜索找不到它;
            // 落点请求会先把按钮组合出来再聚焦)
            onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    onNavigateUpToHero()
                    true
                } else {
                    false
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
            LazyRow(
                Modifier
                    .tvFocusNavSignal(focus)
                    .onFocusChanged { rowHasFocus = it.hasFocus }
                    // 从行外进来的焦点落回本行上次聚焦的卡 (按下标, 见 KDoc). onEnter 只在
                    // 焦点组节点上生效, 所以显式补 focusGroup().
                    .focusProperties {
                        onEnter = {
                            if (resolvedTarget >= 0) {
                                // **落点解析进行中: 一律放行, 绝不改道.**
                                // 这次 enter 十有八九就是作用域发给目标锚点的请求
                                // .requestFocus() —— 它为了让目标卡组合出来刚把本行滚走,
                                // 于是"上次聚焦的卡"多半已不在组合窗口里, 改道必然失败并
                                // cancelFocusChange(), 把解析器自己的请求一起取消掉. 两者互相拆台
                                // 直到解析器 60 次用尽放弃, 焦点最后落在"上次聚焦的卡"上而不是
                                // 请求的那张 —— 期间焦点在卡片区内外反复进出, 用户按什么键都
                                // 落在当帧抢赢的那个节点上.
                                // (2026-08-12 真机: 返回键从 rec-row-8 跳 rec-row-0, 单次
                                //  复现打出 277 轮互相取消.)
                                // 放行 = 不 cancel 不改道, 原请求照常落到它自己的目标上.
                            } else {
                                val saved = focusedIndex.coerceAtLeast(0)
                                val ok = runCatching {
                                    enterRequester.requestFocus(FocusDirection.Enter)
                                }.getOrDefault(false)
                                if (!ok) {
                                    // 那张卡还没组合出来 -> 取消进组, 交给解析 (滚过去让它组合再
                                    // 聚焦); 放行默认进组会横向拽走整行, 见 pendingEnter 的注释
                                    pendingEnter = saved
                                    cancelFocusChange()
                                }
                            }
                        }
                    }
                    .focusGroup(),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(TV_PAGE_CARD_SPACING),
                // start: 锚位线退回内容区左缘 (行本体已随卡片区向左出血); end: 行尾留出
                // 整行空白让末卡也能停到锚位
                contentPadding = PaddingValues(
                    start = TV_EXPLORATION_ROW_START_BLEED,
                    end = (this@BoxWithConstraints.maxWidth - TV_EXPLORATION_ROW_START_BLEED - TV_PAGE_CARD_WIDTH)
                        .coerceAtLeast(0.dp),
                ),
            ) {
                items(virtualCount) { index ->
                    // derivedStateOf 收窄成布尔: 落点解析期间 resolvedTarget 变化只让目标卡
                    // 自己 (挂/摘请求器) 重组
                    val isTarget by remember(index) { derivedStateOf { resolvedTarget == index } }
                    // 进组落点 = 上次聚焦卡 (没聚焦过则首卡); 同样收窄成布尔, 只让挂/摘请求器
                    // 的那两张卡重组
                    val isEnterTarget by remember(index) {
                        derivedStateOf { focusedIndex.coerceAtLeast(0) == index }
                    }
                    // 卡片亮度跟位置走 (Prime 式): 越过锚位线的离场卡在 TV_CARD_FADE_DISTANCE
                    // 内渐渐压暗到 TV_CARD_PAST_DIM_ALPHA 并保持 —— 滑过左侧出血区 (侧边栏
                    // 底下) 时压暗可见, 直到滑出屏幕左缘; 向左导航时对称地提亮回来.
                    // 位置读在 graphicsLayer 的 lambda 里, 滚动每帧只失效图层, 零重组.
                    // 请求器挂容器上, requestFocus 委托给内部第一个焦点目标 (卡片).
                    Box(
                        Modifier
                            .graphicsLayer {
                                // 逐绘制指令调制 alpha, 不走离屏缓冲 (理由同 [rowTopFade])
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                                val x = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == index }?.offset ?: 0
                                alpha = if (x >= 0) 1f else {
                                    val t = (-x / TV_CARD_FADE_DISTANCE.toPx()).coerceIn(0f, 1f)
                                    1f - t * (1f - TV_CARD_PAST_DIM_ALPHA)
                                }
                            }
                            .ifThen(isTarget) {
                                tvFocusAnchor(focus, ExplorationRowCardFocus(index))
                            }
                            .ifThen(isEnterTarget) { focusRequester(enterRequester) },
                    ) {
                        card(
                            if (loopEnabled) index % itemCount else index,
                            {
                                focusedIndex = index
                                if (resolvedTarget == index) finishResolve()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 显式卡片落点请求 (仅进页恢复与返回键分层两处使用; 其余焦点移动全走空间搜索 + 进组落点链).
 * [rowKey] 是行的稳定键 (不用绝对行号: "继续观看"分页迟到会让推荐行整体位移);
 * [cardIndex] < 0 = 该行自己跨导航保存的上次聚焦卡. 实例身份比较, 同参重发也能触发解析.
 */
private class TvCardFocusRequest(
    val rowKey: String,
    val cardIndex: Int,
)

/** hero 的两颗按钮作为焦点落点: 进页/返回键回 [PRIMARY] (立即观看); 卡片区顶行按上键回 [SCHEDULE]. */
private enum class TvHeroFocusButton : TvFocusKey { PRIMARY, SCHEDULE }

/**
 * 显式 hero 落点请求. 包一层只为**实例身份**, 与 [TvCardFocusRequest] 同一个理由: 送焦可能没
 * 落地 (被别的请求抢了焦点 / 目标那一刻还没组合), 而「再请求一次」必须能重新触发解析效应.
 *
 * 裸枚举的失败模式 (2026-08-23 真机): 动作面板点「回到主界面」时这个请求被进页恢复的卡片抢了
 * 焦点, 值就一直留在 PRIMARY (只有主按钮真正获焦或用户按方向/确认键才清). 此后在卡片区顶行
 * 按返回 —— 那一步就是 `heroFocusRequest = PRIMARY` —— 是个静默的空赋值, 效应不重启, 返回键
 * 从此完全没反应 (页面 BackHandler 仍开着, 也到不了主壳的退出逻辑).
 */
private class TvHeroFocusRequest(val button: TvHeroFocusButton)

private data class ExplorationRowCardFocus(val index: Int) : TvFocusKey

private val TV_EXPLORATION_NAV_KEYS = setOf(
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
private fun <T : Any> LazyPagingItems<T>.peekOrNull(index: Int): T? =
    if (index in 0 until itemCount) peek(index) else null

/**
 * 推荐区从平铺下标 [flatIndex] 出发的预取目标: 顺方向两格 + 下一行同列.
 *
 * 本页的行区不取反方向: 行内只有左右, 左边是刚走过来的地方, 缓存必然是热的 (网格页不同,
 * 见 [tvGridNeighborsOf]). 不取更远: 要连按三下才到, 那时前两格早就跑完、第三格也已经作为
 * 新的邻居被排上了.
 */
private fun tvRecNeighborsOf(
    recommendations: LazyPagingItems<RecommendedItemInfo>,
    flatIndex: Int,
): TvHeroNeighbors {
    // 推荐行的条目一律走整部 backdrop (不是"在看"的), 偏好恒 false
    fun idAt(i: Int) = (recommendations.peekOrNull(i) as? RecommendedSubjectInfo)
        ?.bangumiId?.let(::TvHeroNeighbor)
    // 推荐行开着循环导航 (末卡右侧回到首卡, 见 TvAnchoredCardRow 的 loop): 顺方向按行内下标
    // 取模, 否则行尾的预取押在下一行开头, 真正的落点 (本行首卡) 反而没人管
    val rowStart = flatIndex - flatIndex % TV_EXPLORATION_REC_ROW_SIZE
    val rowSize = minOf(TV_EXPLORATION_REC_ROW_SIZE, recommendations.itemCount - rowStart)
    fun wrappedIdAt(step: Int): TvHeroNeighbor? {
        if (rowSize <= 1) return null // 单卡的行没有"右边"
        val i = rowStart + (flatIndex - rowStart + step) % rowSize
        return if (i == flatIndex) null else idAt(i) // 兜一圈回到自己 (行只有 2 张时的 +2) 不算邻居
    }
    return TvHeroNeighbors(
        singleStep = listOfNotNull(wrappedIdAt(1), idAt(flatIndex + TV_EXPLORATION_REC_ROW_SIZE)),
        urlOnly = listOfNotNull(wrappedIdAt(2)),
    )
}

private data class TvHeroTarget(
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
     * 在卡片的 onFocused 里算好带过来: 只有那里知道自己在第几行第几个. 与 [subjectId] 同时
     * 变化 (都由聚焦位置决定), 不会给本数据类引入额外的相等性抖动.
     */
    val neighbors: TvHeroNeighbors = TvHeroNeighbors(),
) {
    /** 交给共享流水线/展示层的最小描述, 见 [TvHeroMediaSpec]. */
    fun toHeroMediaSpec() = TvHeroMediaSpec(subjectId, fromFollowed, coverUrl, neighbors)
}

/**
 * 上次离开本页时 hero 指向的条目, 用作返回时的初值. 与那几张表 (见 [TvHeroMediaCache]) 一样
 * 是**进程级**的, 但只有本页用得上, 所以留在本文件.
 *
 * 光把那几张表提到进程级还不够: `backdropUrl` 是 `heroTarget?.let { ... }` 算出来的,
 * 而 `heroTarget` 要等卡片重新上报聚焦才有值 —— 返回后的头几帧它仍是 null, `Crossfade`
 * 照样从空白起步. 用上次的值开局即可跳过这几帧; 焦点恢复到同一张卡时它本来就等于新值,
 * 落到别的卡上则是两张真图之间的正常 crossfade (仍好过从黑底淡入).
 */
private object TvExplorationLastHero {
    var target: TvHeroTarget? = null
}

/**
 * 区块标题. 两处用同一个组件, 保证两条路径的高度/字体完全一致 (它们必须等高, 否则 hero 态与
 * 卡片态的行位置会差出去):
 *  - 列表里的行内标题 (每个区块一个 item);
 *  - 钉在 [TV_EXPLORATION_LABEL_TOP] 的固定标签覆盖层 (代替被滚到锚位上方的那一个).
 *
 * 可见度由调用方给的位置驱动 alpha 决定 (行内那个用 [sectionHeaderTopFade], 覆盖层取其补数),
 * 本组件自己不管显隐.
 */
@Composable
private fun TvSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        title,
        modifier,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
    )
}

/**
 * 轮播指示器 (横排小圆点, 不可聚焦, 纯展示). 当前项为拉长胶囊, 其余为小圆点. 由外部左右键驱动
 * ([selectedIndex]), 自身不处理焦点与按键. 少于 2 项时不显示. 在给定宽度内水平居中.
 */
@Composable
private fun TvCarouselIndicator(
    count: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 1) return
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == selectedIndex
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (active) 20.dp else 6.dp)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        },
                        RoundedCornerShape(50),
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 行键 (页面级焦点簿记一律记键, 不记绝对行号)
// ---------------------------------------------------------------------------

private const val TV_FOLLOWED_HEADER_KEY = "followed-header"
private const val TV_FOLLOWED_ROW_KEY = "followed-row"
private const val TV_REC_HEADER_KEY = "rec-header"
private const val TV_REC_ROW_KEY_PREFIX = "rec-row-"
private fun tvRecRowKey(recRow: Int) = "$TV_REC_ROW_KEY_PREFIX$recRow"

// ---------------------------------------------------------------------------
// 三层叠放的几何常量: 卡片区顶边是常量, 与 hero/标签显隐完全解耦 (架构见页面 KDoc)
// ---------------------------------------------------------------------------

/** hero 覆盖层顶边 (对齐 backdrop 顶部区域). */
private val TV_EXPLORATION_HERO_TOP = 28.dp

/**
 * Hero 覆盖层高度 —— **卡片态** (无操作按钮, 信息块吃满): 文字下边界正好落在固定标签的
 * 上边线. 调大 = 文字下边界下移. 它只决定覆盖层自己的内容排布, 卡片区顶边是独立常量.
 * hero 态见 [TV_HERO_BLOCK_HEIGHT_EXPANDED].
 */
private val TV_HERO_BLOCK_HEIGHT = 240.dp

/** 锚位区块标签顶边 = hero 顶边 + hero 块高 (紧贴 hero 下缘). */
private val TV_EXPLORATION_LABEL_TOP = TV_EXPLORATION_HERO_TOP + TV_HERO_BLOCK_HEIGHT

/** 行间距, 同时也是标题到卡片行的距离 (一个参数保证行内标题与固定标签两条路径等距). */
private val TV_EXPLORATION_ROW_GAP = 12.dp

/**
 * 区块标题块占位 = 标题行高 (titleMedium ≈ 24dp) + [TV_EXPLORATION_ROW_GAP].
 *
 * **固定标签覆盖层与列表里的行内标题必须占同一段高度**: 卡片态的锚位行紧贴固定标签下方,
 * hero 态的首行紧贴行内标题下方 —— 两者差一点, hero↔卡片切换时行位置就差一点.
 */
private val TV_SECTION_LABEL_BLOCK = 24.dp + TV_EXPLORATION_ROW_GAP

/**
 * 卡片区顶边 = **锚位线** (聚焦行的卡片顶线). 常量 —— hero/按钮/标签的任何显隐都不挪它.
 * hero 态列表停在 0, 于是首行在锚位下方一个 [TV_SECTION_LABEL_BLOCK] 处 (行内标题占着),
 * 焦点进卡片区时那一段由 pivot 用 spring 滚掉 —— 与 main 观感一致, 但几何不变所以不抖.
 */
private val TV_EXPLORATION_CARD_TOP = TV_EXPLORATION_LABEL_TOP + TV_SECTION_LABEL_BLOCK

/**
 * Hero 覆盖层高度 —— **hero 态** (信息块 + 操作按钮块): 块底 = 锚位线上方一个行距, 于是
 * 按钮下缘到"继续观看"行内标题恰好 [TV_EXPLORATION_ROW_GAP] (与 main 的 Column 排布等价).
 *
 * 从锚位线倒推而不是写死: 按钮块的高度是字号/内边距算出来的, 写死信息块高度的话按钮下面
 * 会多出或缺掉一段空白 (真机一眼可见"按钮离继续观看太远").
 */
private val TV_HERO_BLOCK_HEIGHT_EXPANDED =
    TV_EXPLORATION_CARD_TOP - TV_EXPLORATION_ROW_GAP - TV_EXPLORATION_HERO_TOP

/** Hero 信息块与操作按钮块之间的间距 (较短, 让按钮贴近简介). */
private val TV_HERO_INFO_TO_BUTTONS_GAP = 6.dp

/** 两枚操作按钮之间的间距 (很短). */
private val TV_HERO_BUTTON_GAP = 4.dp

/** hero 操作按钮离场淡出时长 (焦点进卡片区): 要快 —— 离场行会从按钮区域上滑过. */
private const val TV_HERO_BUTTON_FADE_OUT_MILLIS = 90

/** hero 操作按钮回场淡入时长. 起点是"卡片区已滚回顶部", 可以从容一点. */
private const val TV_HERO_BUTTON_FADE_IN_MILLIS = 150

/**
 * Hero "下一集"状态行的固定高度. 加上列间距 10dp 后恰为简介两行行距 (bodyMedium
 * lineHeight 20dp × 2), 使有无此行时简介行网格对齐 (见使用处).
 */
private val TV_HERO_STATUS_ROW_HEIGHT = 30.dp

/** 轮播指示器最多显示的圆点数 (同时也是自动轮播覆盖的条目数). */
private const val TV_CAROUSEL_MAX_DOTS = 20

/** 自动轮播切换间隔. */
private const val TV_CAROUSEL_AUTO_ADVANCE_MILLIS = 6000L

/** 轮播指示器相对 hero backdrop 下边界的上抬量 (0 = 指示器底边正好压在下边界上). */
private val TV_CAROUSEL_INDICATOR_EDGE_RAISE = 24.dp

/**
 * 内容左侧额外留白 (外层 MainScreen 已让开侧边栏收起宽度 48dp, 总左缘 = 48 + 此值).
 * 默认 16 使总左缘 64, 侧边栏按钮中心 (32) 恰在屏幕左缘与内容左缘的正中间.
 */
private val TV_EXPLORATION_START_PAD = 16.dp

/** backdrop 高度占屏高比例 (Prime 实测: 图占屏顶约 76%, 渐变尾正好压在卡片区上缘之下). */
private const val TV_EXPLORATION_BACKDROP_HEIGHT_FRACTION = 0.66f

/** backdrop 两态渐变 (hero 态 <-> 卡片态) 切换动画时长. */
private const val TV_BACKDROP_STATE_ANIM_MILLIS = 400

/** 推荐区每行的条目数 (每行是一条固定锚点轮播, 行数随分页无限增长). */
private const val TV_EXPLORATION_REC_ROW_SIZE = 12

/**
 * 卡片行的上边界淡出 (Prime 式"无边界"): 卡片区已向上出血 [TV_EXPLORATION_TOP_BLEED],
 * 行越过锚位线后**继续可见地上移** (压着 hero 区域), 同时透明度按越线距离衰减,
 * [TV_ROW_FADE_DISTANCE] 处完全消失 (Prime 实拍: 离场行是"移出去边淡", 不是原地消失,
 * 也没有可见的裁剪边) —— 淡出淡入与滑动天然同步, 向上换行回来的行对称地边下移边浮现.
 *
 * 性能: 位置/透明度全部读在 graphicsLayer 的 lambda 里, 滚动每帧只失效图层, 零重组,
 * 每帧仅几次可见项查表.
 *
 * **必须 [CompositingStrategy.ModulateAlpha]**: 默认的 Auto 档遇到 alpha < 1 会把整层先画进
 * 离屏缓冲再合成 (`saveLayerAlpha`), 而这里的层是**整行宽**的 —— 1080p 下每行 ≈0.6MP, 4K UI
 * 下 ≈1.5MP, 而聚焦行之下的预览行同时压着暗 (三四行), 于是每帧多出几 MP 的离屏光栅化.
 * 行内卡片互不重叠、alpha 纯粹是压暗, 逐绘制指令调制 alpha 的结果与离屏合成一致 (卡内那条
 * 半透明进度条在 45% 压暗下的极小差异肉眼不可辨), 缓冲整块省掉.
 *
 * [extraAlpha] 与位置透明度相乘 (推荐行的压暗档).
 */
private fun Modifier.rowTopFade(
    listState: LazyListState,
    key: String,
    extraAlpha: () -> Float = { 1f },
): Modifier = this.graphicsLayer {
    compositingStrategy = CompositingStrategy.ModulateAlpha
    val top = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: 0
    val positional = if (top >= 0) 1f else {
        (1f + top / TV_ROW_FADE_DISTANCE.toPx()).coerceIn(0f, 1f)
    }
    alpha = positional * extraAlpha()
}

/**
 * 区块标题 (行内那一个) 的位置驱动可见度 —— 也是"它占着标签线的程度", 固定标签覆盖层取
 * `1 − max(各区块本值)`, 于是可见的标签恒为一份, 交接点就是标题的**停位线**(= 覆盖层位置):
 *  - 停位线及以下 (含 hero 态露在 hero 下方、非聚焦区块露在下方): 完全可见, 跟着卡片一起移动
 *    —— hero → 首行那一段就是标题从锚位线滑到停位线, 全程可见 (不是"消失后在上方出现");
 *  - 越过停位线 (再往下换行): 按越线距离在 [TV_LABEL_FADE_DISTANCE] 内快速淡出, 同时覆盖层
 *    在停位线上淡入接手 —— 标签不会跟着卡片一路平移上去. 淡出期间**钉在停位线上不动**
 *    (见 [sectionHeaderTopFade]), 交接是原地的.
 *  - 没组合出来 (滚远了): 0, 覆盖层全权显示 (位置与文字都一样, 这一跳看不见).
 */
private fun tvSectionHeaderAlpha(listState: LazyListState, key: String, density: Density): Float {
    val above = tvSectionHeaderAboveLine(listState, key, density) ?: return 0f
    if (above <= 0f) return 1f
    return (1f - above / with(density) { TV_LABEL_FADE_DISTANCE.toPx() }).coerceIn(0f, 1f)
}

/**
 * 行内标题越过停位线的距离 (px, >0 = 已在线上方); null = 没组合出来.
 *
 * 停位线用**实测**的标题高度算 (`info.size`), 不吃 [TV_SECTION_LABEL_BLOCK] 里 24dp 那个估值:
 * 字体缩放下标题高度会变, 差一点点就会让停稳的标题挂在 0.97 alpha 上 (与覆盖层叠出双影).
 */
private fun tvSectionHeaderAboveLine(listState: LazyListState, key: String, density: Density): Float? {
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
    val rest = -(info.size + with(density) { TV_EXPLORATION_ROW_GAP.toPx() })
    return rest - info.offset
}

/**
 * [tvSectionHeaderAlpha] 挂在行内标题上的形态 (全读在 graphicsLayer 的 lambda 里, 零重组).
 *
 * 越线后**钉在停位线上只淡出、不跟着卡片上移**: 淡出的这一份与在同一位置淡入的固定标签覆盖层
 * 就成了原地交接, 一个字都不动. 若放它照常上移, 交接期间会同时看到"一个推荐往上飞走、原地
 * 还有一个推荐"(真机报过) —— 位置不同的两份互补 alpha 是叠影, 不是交接.
 */
private fun Modifier.sectionHeaderTopFade(listState: LazyListState, key: String): Modifier =
    this.graphicsLayer {
        val above = tvSectionHeaderAboveLine(listState, key, this) ?: return@graphicsLayer
        translationY = above.coerceAtLeast(0f)
        alpha = if (above <= 0f) {
            1f
        } else {
            (1f - above / TV_LABEL_FADE_DISTANCE.toPx()).coerceIn(0f, 1f)
        }
    }

/**
 * 区块标题越过停位线后的淡出距离. 远小于行距 (≈168dp): 用户要的是"焦点确认进下一个区块时
 * 标题快速渐隐", 而不是跟着卡片平移出画. 32dp ≈ 换行滚动的前 1/5 段 (~50ms).
 */
private val TV_LABEL_FADE_DISTANCE = 32.dp

/**
 * 行的位置驱动淡出距离: 行越过卡片区锚位线多远就多淡, 到此距离完全消失.
 *
 * 几何约束 (记号: 卡高 H = [TV_PAGE_CARD_WIDTH]/[TV_PORTRAIT_CARD_COVER_RATIO] ≈ 156dp,
 * 行距 P = H + [TV_EXPLORATION_ROW_GAP] ≈ 168dp):
 * - 必须 < P: 下一行完全停靠时上一行的 top 恰好在一个行距处, 淡出距离比它大就留下
 *   永远挂着的半透明残影 (真机踩过, 180dp 时残 ~8%).
 * - 用户定的观感基准: **下一行有一半高度进入焦点框时上一行必须完全看不见**.
 *   下一行 top = H/2 时上一行 top = H/2 − P ≈ −88dp, 故取 88dp.
 * - 也必须 < [TV_EXPLORATION_TOP_BLEED], 否则残余撞上出血区顶部的裁剪边.
 */
private val TV_ROW_FADE_DISTANCE = 88.dp

/** 卡片区底部留白的下限 (内容本来就撑不满一屏时用它, 不会给出多余的可滚动空白). */
private val TV_EXPLORATION_MIN_BOTTOM_PAD = 24.dp

/**
 * 卡片区向上出血量: 给离场行留出"越过锚位线继续上移边淡出"的可见空间
 * (> [TV_ROW_FADE_DISTANCE], 到出血区顶部裁剪边时已完全透明).
 */
private val TV_EXPLORATION_TOP_BLEED = 200.dp

/**
 * 行内卡片越过锚位线后的压暗渐变距离: 在此距离内从全亮渐变到
 * [TV_CARD_PAST_DIM_ALPHA] 并保持, 直到滑出屏幕左缘.
 */
private val TV_CARD_FADE_DISTANCE = 64.dp

/**
 * 越过锚位线的离场卡片的压暗亮度 (Prime 式左侧暗区, 同选集轮播的左侧压暗):
 * 离场卡滑过左侧出血区 (侧边栏底下) 时压暗可见, 不是硬切消失.
 */
private const val TV_CARD_PAST_DIM_ALPHA = 0.45f

/**
 * 卡片区向左出血量 = 侧边栏收起宽度 ([TvNavigationRailDefaults.CollapsedWidth])
 * + 内容列额外留白 ([TV_EXPLORATION_START_PAD]), 即内容区左缘到屏幕左缘的总距离:
 * 出血后行的左缘就是屏幕左缘, 离场卡片可见地滑出屏幕.
 */
private val TV_EXPLORATION_ROW_START_BLEED = TvNavigationRailDefaults.CollapsedWidth + TV_EXPLORATION_START_PAD

/**
 * 聚焦行之下预览行的压暗亮度 (Prime Video 实测约四成亮): 全亮只留给聚焦行, 主次分明;
 * 换行时旧行边下滑边压暗 / 下方行边上滑边提亮 ([TV_ROW_DIM_FADE_MILLIS], 与滑动大致同步).
 * 仅在焦点位于卡片区时生效 —— hero 态整个卡片区都是预览, 主次由 backdrop 层级表达.
 */
private const val TV_ROW_UNFOCUSED_DIM_ALPHA = 0.45f

/** 压暗档切换 (聚焦行↔预览行) 的渐变时长. */
private const val TV_ROW_DIM_FADE_MILLIS = 200
