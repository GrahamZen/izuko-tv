/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.episode.SetEpisodeCollectionTypeRequest
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.animation.LocalAniMotionScheme
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_tv_up_next_in
import me.him188.ani.app.ui.lang.playback_up_next_start
import me.him188.ani.app.ui.subject.details.SubjectDetailsUIState
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeCarousel
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem
import org.jetbrains.compose.resources.stringResource

// ---- 调参 ----

/** 一行完整显示的卡片数 (卡宽由屏宽反推: 左右各留页面边距, 正好放下这么多张). */
private const val TV_STRIP_VISIBLE_CARDS = 4

/** 卡片间距. */
private val TV_STRIP_CARD_SPACING = 16.dp

/** 选集条展开/收起的滑动动画时长 (毫秒). */
private const val TV_STRIP_SLIDE_MS = 250


/** 倒计时提示行与卡片行的间距. */
private val TV_STRIP_HEADER_GAP = 12.dp

/**
 * 卡片行离屏幕底缘的距离.
 *
 * 12dp: 锚位聚焦框是**向外探出**的 (`TvFocusRing.Gap + Width` ≈ 4dp, 见 FocusEpisodeAnchorRing),
 * 所以框的下缘离屏幕边还有 8dp —— 卡片行看着落在底缘上而不是贴死. 去掉卡片下方那行信息之后
 * 这一条是唯一还压着卡片高度的东西, 越小画面留得越多 (用户 2026-09-07 要求再往下压一档,
 * 上一版是 24dp).
 */
private val TV_STRIP_BOTTOM_PADDING = 12.dp

/**
 * 倒计时态里落点卡**之后**那两张的不透明度 (再往后一律 0 = 看不见但仍占位、仍可聚焦).
 *
 * 只留三张, 且后两张压得很低: 那一刻唯一要传达的是"马上要播的是这一集", 后面的卡只起"再往
 * 右还有东西"的暗示作用, 亮一点就开始抢戏 —— 0.55/0.25 那一版真机上看下来第二张还是太显眼
 * (2026-09-07 用户反馈), 整体再降一档.
 */
private const val TV_STRIP_UP_NEXT_TRAILING_ALPHA_1 = 0.25f
private const val TV_STRIP_UP_NEXT_TRAILING_ALPHA_2 = 0.12f

/** 倒计时提示行里秒数那半截的不透明度 (靠深浅分主次, 播放器控件一律黑白). */
private const val TV_STRIP_HEADER_SUBTLE_ALPHA = 0.75f

/**
 * 播放器控制层里的选集条 (Prime 形态): 复用详情页的选集轮播
 * ([FocusEpisodeCarousel], 缩略图/播放进度/长按标记看过等一应俱全).
 *
 * 仅在展开态渲染 (由 [TvPlayerOverlayState.episodeStripExpanded] 驱动, 图标行按下键
 * 唤出, 平时完全不可见): 控制行隐藏 (调用方处理), 屏幕底部只有卡片行 (无标题行, 一行正好
 * [TV_STRIP_VISIBLE_CARDS] 张完整卡, 聚焦卡左侧露上一张卡切边). 点击卡片切换当前播放集并回
 * 纯画面; 再按下键进详情层.
 *
 * **卡片行下方不放简介/时长/播出日期** (2026-09-07 去掉): 集名在聚焦卡上本来就是滚动显示的
 * (`basicMarquee`), 那一行能补的只有简介 —— 而它正剧透用户马上要看的这一集, 又占掉整个下半屏.
 * 想看简介的入口仍在: 长按卡片开「本集详情」弹窗 (全文可滚)。
 *
 * ## 「接下来播放」倒计时态 (见 [TvPlayerOverlayState.upNextCountdown])
 *
 * 一集快播完时本条会自动展开成这一态: 提示行 + 卡片行, 落点是**下一集**而不是正在播的那一集,
 * 锚位聚焦框走成倒计时环 (走满 = 自动连播), 落点之后只留两张渐隐的卡. 用户按任意方向键即退出,
 * 就地变成普通选集条 —— 卡片行与焦点都不动, 只是装饰消失 (见 [TvUpNextState]).
 *
 * 分集列表取自 [EpisodeViewModel.episodeListUiStateFlow] 的 `allEpisodes` (播放器自己的数据
 * 路径, 起播必经), 而不是详情状态里的那份 —— 后者要等整套详情组装完, 在起播这一刻能慢到两三秒.
 * 取 `allEpisodes` 是为了与详情页选集轮播**逐项一致**: 特别篇按序号插在正片之间.
 * 详情状态 (subjectDetailsStateLoader, 进屏已预载) 只供 TMDB 剧照/时长/简介和播放进度,
 * 没到就先无图, 不影响选集.
 *
 * 列表状态按三态上报 [TvPlayerOverlayState.onEpisodeStripStateChanged]: 还没到时下键会等,
 * 只有确认无分集 (未开播/加载失败) 才让图标行下键直通详情页.
 */
@Composable
internal fun TvPlayerEpisodeStrip(
    vm: EpisodeViewModel,
    overlay: TvPlayerOverlayState,
    upNext: TvUpNextState,
    rowFocusModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val detailsState = vm.episodeDetailsState
    // 兜底触发加载 (正常已在进屏时预载, loader 有"已加载"守卫). 只为下面那些增量信息 ——
    // 分集列表本身不等它
    LaunchedEffect(Unit) {
        detailsState.subjectDetailsStateLoader.load(detailsState.subjectId, detailsState.subjectInfo.value)
    }
    // 分集列表走播放器自己的数据路径 (见 EpisodeViewModel.episodeListUiStateFlow): 那是起播的
    // 必经之路, 必然比整套详情状态先到. 详情状态只供 TMDB 剧照/时长/简介, 没到就先无图
    val episodeList by vm.episodeListUiStateFlow.collectAsStateWithLifecycle()
    // 用 allEpisodes 而不是 mainEpisodes: 与详情页选集轮播完全同一份列表 —— 特别篇按序号插在
    // 正片之间 (尸鬼 20.5 落在 20 与 21 中间). 两边都是同一个 FocusEpisodeCarousel, 列表却不同
    // 的话, 同一个条目在详情页有 SP、进播放器就没了.
    val episodes = episodeList?.allEpisodes.orEmpty()
    val uiState by detailsState.subjectDetailsStateLoader.state.collectAsStateWithLifecycle()
    val state = (uiState as? SubjectDetailsUIState.Ok)?.value
    SideEffect {
        val stripState = when {
            // "还没到"与"没有"必须分开: 前者下键要等, 后者才该直通详情层
            episodeList == null ->
                // 详情状态都加载失败了, 按"确认无分集"上报: 详情层有错误页和重试入口
                if (uiState is SubjectDetailsUIState.Err) TvEpisodeStripState.EMPTY
                else TvEpisodeStripState.LOADING

            episodes.isEmpty() -> TvEpisodeStripState.EMPTY
            else -> TvEpisodeStripState.AVAILABLE
        }
        overlay.onEpisodeStripStateChanged(stripState)
    }
    // 换集那一瞬列表会短暂变空 (播放器换会话, episodeListUiStateFlow 先发一次空), 直接 return
    // 会把整棵子树当场摘掉 —— 退场动画一帧都播不出来, 观感就是"选集条瞬间消失". 留住最后一份
    // 非空列表把退场演完; 真的没有分集时 lastEpisodes 本来就是空的, 照旧不渲染
    var lastEpisodes by remember { mutableStateOf(emptyList<EpisodeListItem>()) }
    if (episodes.isNotEmpty() && episodes !== lastEpisodes) lastEpisodes = episodes
    val shownEpisodes = lastEpisodes
    if (shownEpisodes.isEmpty()) return

    // 详情状态未到时挂空流: 各自的初值 (emptyMap) 保持不变, 到了再重新订阅
    val stillsFlow = remember(state) { state?.tmdbEpisodeStillsFlow ?: emptyFlow() }
    val progressFlow = remember(state) { state?.playProgressFlow ?: emptyFlow() }
    // 简介与时长仍要取: 卡片下方那行信息去掉之后, 两者都改由长按卡片的「本集详情」弹窗给出
    // (简介是正文, 时长与播出日期在底行右端, 见 FocusEpisodeCarousel 里那个弹窗的 meta)
    val overviewsFlow = remember(state) { state?.tmdbEpisodeOverviewsFlow ?: emptyFlow() }
    val runtimesFlow = remember(state) { state?.tmdbEpisodeRuntimesFlow ?: emptyFlow() }
    val tmdbEpisodeStills by stillsFlow.collectAsStateWithLifecycle(emptyMap())
    val playProgress by progressFlow.collectAsStateWithLifecycle(emptyMap())
    val episodeOverviews by overviewsFlow.collectAsStateWithLifecycle(emptyMap())
    val episodeRuntimes by runtimesFlow.collectAsStateWithLifecycle(emptyMap())

    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    // 倒计时态的装饰都吊在"下一集是哪一集"上: 它没算出来 (最后一集 / 下一集还没播出) 就
    // 退化成普通选集条 —— 那时本来也没有可倒数的目标
    val upNextEpisodeId = upNext.nextEpisode?.episodeId
    val countingDown = overlay.upNextCountdown && upNextEpisodeId != null
    val currentEpisodeId = vm.episodeSelectorState.current?.episodeId

    // 展示中的集 (聚焦卡; 没有卡片聚焦时是落点集), 轮播回调上报.
    // 回调 remember 住: 换新实例会让轮播每次重组都跳过不了 (它是个大组件)
    var displayedEpisodeId by remember { mutableStateOf<Int?>(null) }
    val onDisplayed = remember { { item: EpisodeListItem? -> displayedEpisodeId = item?.episodeId } }

    // 渐隐的锚点 = "该被看见的那一集". 倒计时态是下一集; **预告段是当前播放集** —— 于是预告段
    // 摆出来时透明度就已经是倒计时那一套, 到点锚点往右挪一格, 每张卡各自补一次
    // animateFloatAsState, 视觉上整条渐隐梯度跟着焦点一起"顺推"过去, 而不是凭空出现.
    //
    // 预告段里用户一旦自己挪开焦点就撤掉渐隐 (他在挑集, 不是在等下一集): 那时压暗谁都是误导
    val dimAnchorId = when {
        countingDown -> upNextEpisodeId
        upNext.preRoll && (displayedEpisodeId == null || displayedEpisodeId == currentEpisodeId) ->
            currentEpisodeId

        else -> null
    }
    // lambda 都 remember 住: 不然每次重组都换新实例, 轮播里那些以它为 key 的缓存 (卡片 alpha /
    // 环的 Path 与 PathMeasure) 跟着全部作废
    val cardAlpha: ((Int) -> Float)? = remember(shownEpisodes, dimAnchorId) {
        if (dimAnchorId == null) return@remember null
        val anchor = shownEpisodes.indexOfFirst { it.episodeId == dimAnchorId }
        if (anchor < 0) return@remember null
        ({ index: Int ->
            when (index - anchor) {
                1 -> TV_STRIP_UP_NEXT_TRAILING_ALPHA_1
                2 -> TV_STRIP_UP_NEXT_TRAILING_ALPHA_2
                // 锚点卡本身与它左侧的照旧 (左侧另有"聚焦卡左边压暗"那一套)
                else -> if (index > anchor) 0f else 1f
            }
        })
    }
    val anchorCountdown: (() -> Float)? = remember(countingDown, upNext) {
        if (countingDown) ({ upNext.countdownFraction }) else null
    }

    // 换集时选集条**淡出**, 不下滑.
    //
    // 下滑读作"收起来了" (那是用户按上键/返回的语义); 而这一下是"这一集结束了" —— 画面已经切到
    // 下一集, 选集条原地淡掉才贴合. 两条路都要覆盖: 点卡片换集 (那时 currentEpisodeId 还没变,
    // 由点击处置真) 与倒计时走完自动连播 (由 currentEpisodeId 变化置真).
    // 到点自己复位, 免得之后正常收起也变成淡出
    // **每次进入倒计时态都显式把焦点送到下一集那张卡**, 不只是改落点.
    //
    // 光靠落点不够: 本条在控制层里可能一直没离开组合 (上一趟浏览过选集, 收起时只是层级变了),
    // 那时卡片行还停在上次浏览的位置上, 下一集那张卡多半不在组合里 —— 落点请求器没附着, 焦点
    // 会落到"进组第一个可聚焦项"上. 送焦通道会先滚到位再按帧重试 (见 requestEpisodeFocus),
    // 且用户一按键就收手
    var upNextReveal by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(countingDown) {
        if (countingDown) upNextReveal = upNextEpisodeId
    }

    // **提示行在场 <=> 焦点就在下一集那张卡上**, 这是个不变量: 那行字的意思正是"聚焦的这一集
    // 马上会自动播", 焦点跑到别的卡上还留着它就是句误导.
    //
    // 方向键那条路由根路由先一步退出本态 (见 TvEpisodeScreen), 这里兜住**其余所有**让焦点换卡的
    // 路 —— 空间焦点搜索、焦点恢复、送焦落错、长按弹窗关闭后的归还.
    //
    // 送焦未落定 (`upNextReveal != null`) 那一小段要放过: 刚进本态时轮播还记着上一轮浏览停在哪张
    // 卡, 不放过的话提示刚出现就被自己判成"焦点跑了"而当场关掉
    // 打断**只摘掉装饰, 不收掉这一趟提示**: 用户还在这一集里, 他按返回时提示该能退回来
    // (见根路由那条"就地退回上一档"). 真正收掉只有两条路 —— 倒计时态里按返回, 或播放位置走出
    // 触发窗口
    LaunchedEffect(countingDown, displayedEpisodeId) {
        if (!countingDown || upNextReveal != null) return@LaunchedEffect
        if (displayedEpisodeId != null && displayedEpisodeId != upNextEpisodeId) {
            overlay.exitUpNextCountdown()
        }
    }

    // 仅展开态渲染: 平时完全不可见 (无 peek), 图标行按下键唤出.
    // 入场从底部整体上滑 (视觉上 = 卡片本来就在进度条下方, 聚焦时上移进画面,
    // 上方控制行同时淡出), 收起反向滑出.
    //
    // **必须连层级一起判**: `episodeStripExpanded` 在 hideAll 里刻意不复位 (见
    // TvPlayerOverlayState), 纯视频态下它常常还是 true. 从前那没关系 —— 那时整个控制层是卸载的;
    // 但只要有东西 (OP/ED 提示按钮) 把控制层留在场上, 本条就跟着留在组合里, 于是下次
    // showControls() 把标志复位时它开始播**滑出动画**, 而 chrome 的 alpha 正好同时从 0 升到 1 ——
    // 屏幕上就是"选集条闪现了一下" (真机可见). 带上层级之后纯视频态下它压根不在场, 控制层回来时
    // 也就没有动画可播.
    //
    // 倒计时态走的是同一条判据: 它把层级切成 CONTROLS 并置展开 (见 enterUpNextCountdown),
    // 不是第四种可见组合
    //
    // 入场动画**在本条第一次组合时也要播**: 从纯视频态一步到位地进"控制层 + 展开态"时
    // (片尾预告段 / 倒计时态自动展开), 外层控制层那个 AnimatedVisibility 自己正在入场, 本条是
    // "带着 visible=true 出生"的 —— 而 AnimatedVisibility 对初始可见不播动画, 观感就是"选集条
    // 闪现" (2026-09-07 用户报). 用 MutableTransitionState 起手在 false、首帧再置真, 于是无论
    // 从哪条路进来都是从底部滑上来, 与按下键唤出的那一下是同一个动画 (用户已经熟悉它)
    val stripVisible = remember { MutableTransitionState(false) }
    stripVisible.targetState = overlay.episodeStripExpanded && overlay.layer == TvPlayerLayer.CONTROLS
    AnimatedVisibility(
        stripVisible,
        modifier = modifier,
        enter = slideInVertically(tween(TV_STRIP_SLIDE_MS)) { it } + fadeIn(tween(TV_STRIP_SLIDE_MS)),
        exit = slideOutVertically(tween(TV_STRIP_SLIDE_MS)) { it } + fadeOut(tween(TV_STRIP_SLIDE_MS)),
    ) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .onFocusChanged { if (it.hasFocus) overlay.focusRegion = TvPlayerFocusRegion.EPISODES }
            .focusGroup(),
    ) {
        // 卡宽由屏宽反推: 左右各留页面边距, 一行正好 TV_STRIP_VISIBLE_CARDS 张完整卡
        val cellWidth = (this.maxWidth - TV_PLAYER_HORIZONTAL_PAD * 2 -
            TV_STRIP_CARD_SPACING * (TV_STRIP_VISIBLE_CARDS - 1)) / TV_STRIP_VISIBLE_CARDS
        val cellHeight = cellWidth * 9f / 16f
        Column(
            Modifier.fillMaxWidth().padding(bottom = TV_STRIP_BOTTOM_PADDING),
            verticalArrangement = Arrangement.spacedBy(TV_STRIP_HEADER_GAP),
        ) {
            // 提示行只在倒计时态出现, 就在落点卡正上方.
            //
            // 整条选集条是**贴底**摆的 (见调用处的 align(BottomStart)), 所以这一行进出场只把
            // 自己那点高度加在卡片行上方, 卡片一动不动 —— 用户按方向键退出倒计时态时, 屏幕上
            // 唯一变化的是这行字消失
            AniAnimatedVisibility(visible = countingDown) {
                TvUpNextStripHeader(
                    upNext,
                    Modifier.padding(horizontal = TV_PLAYER_HORIZONTAL_PAD),
                )
            }
            FocusEpisodeCarousel(
                episodes = shownEpisodes,
                // 只管"正在播放"徽标 (详情页里是"下一集要看的"). 初始滚动位置与落焦目标另有
                // landingEpisodeId —— 倒计时态里这两者不是同一集
                currentEpisodeId = currentEpisodeId,
                onEpisodeClick = { item ->
                    if (item.episodeId == currentEpisodeId) {
                        // 点的就是**正在播的那一集**: 只把选集条收掉, 不重新起一遍.
                        //
                        // selectEpisodeId 没有同集守卫 (见 EpisodeSelectorState), 走下去就是完整的
                        // 一次换集: 换会话、重选数据源、重新缓冲, 进度还得再定位一次. 而那张卡上写
                        // 着"正在播放", 点它的意思是"就看这个" —— 多半还是误触
                        overlay.hideAll()
                    } else {
                        // **先让退场演完, 再真的换集**.
                        //
                        // 换集是当场跑完的 (真机日志: 点下去那一刻就 Pausing player -> Stopping
                        // player -> Cleared video surface -> 起新的取源), 背景整块变黑; 而选集条的
                        // 退场要 250ms —— 两件事叠在一起, 退场就被"背景已经全变了"盖掉, 观感是
                        // 选集条瞬间消失 (2026-09-07 埋点实测: 退场确实演满了 282ms, 只是没人看见).
                        // 错开之后用户看到的是"卡片滑走 → 画面再换".
                        //
                        // **不要反过来"让选集条在换集期间强制留在场上"**: 试过, 那个标志要维持到
                        // 动画结束, 而它一真, 只要控制层这期间被唤出来 (按下键) 就会连选集条一起
                        // 画出来 —— 屏幕上进度条与选集卡同时出现 (2026-09-07 用户报).
                        //
                        // 用**会话级 scope** 而不是组合的: 本条自己在退场结束时就被摘掉了 (实测
                        // ~290ms), 挂组合作用域会把这一下切集连带取消, 变成"点了没反应".
                        overlay.hideAll()
                        vm.backgroundScope.launch(Dispatchers.Main) {
                            delay(TV_STRIP_SLIDE_MS.toLong())
                            // 选集器的 items 来自 episodeCollectionsFlow (全量, 含特别篇), 所以
                            // 正常都命中这一支; 整页导航只是数据还没到齐时的兜底
                            if (!vm.episodeSelectorState.selectEpisodeId(item.episodeId)) {
                                navigator.navigateEpisodeDetails(vm.subjectId, item.episodeId)
                            }
                        }
                    }
                },
                episodeStills = tmdbEpisodeStills,
                playProgress = playProgress,
                episodeOverviews = episodeOverviews,
                episodeRuntimes = episodeRuntimes,
                horizontalPadding = TV_PLAYER_HORIZONTAL_PAD,
                // 倒计时态: 落点改成下一集 (正在播的那一集仍由 currentEpisodeId 画"正在播放"徽标),
                // 锚位框走成倒计时环, 落点之后的卡渐隐
                landingEpisodeId = upNextEpisodeId.takeIf { countingDown },
                anchorCountdown = anchorCountdown,
                cardAlpha = cardAlpha,
                revealEpisodeId = upNextReveal,
                onRevealConsumed = { upNextReveal = null },
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                cellSpacing = TV_STRIP_CARD_SPACING,
                // 卡片行上方不放集信息行 (集号/集名在卡片上, 简介走长按弹窗)
                showEpisodeInfo = false,
                onDisplayedChanged = onDisplayed,
                // 自动连播换集时用户正好在选集条里: 焦点跟到新当前集 (只在换集那一次).
                //
                // **倒计时态不跟**: 那一刻的换集正是倒计时走完自动连播, 而本条随即就要收掉
                // (见 TvEpisodeScreen 里那条效应) —— 跟焦等于把焦点送进正在退场的子树, 节点一
                // 被移除焦点就凭空消失了 (TvFocusScope 反复写的那一条)
                focusCurrentEpisodeOnChange = !countingDown,
                // 长按卡片: 标记看过/取消看过 (菜单开合上报, 抑制控制层自动隐藏)
                onSetEpisodeCollectionType = { item, type ->
                    scope.launch {
                        vm.setEpisodeCollectionType.invokeSafe(
                            SetEpisodeCollectionTypeRequest(vm.subjectId, item.episodeId, type),
                        )?.let { toaster.showLoadError(it) }
                    }
                },
                onActionMenuExpandedChanged = { overlay.onPopupExpandedChanged(it) },
                // 卡片浮在视频画面上, 与进度条旁的胶囊按钮同一套黑白配色 (聚焦即白底黑字)
                monochrome = true,
                rowFocusModifier = rowFocusModifier,
            )
        }
    }
    }
}

/**
 * 倒计时提示行: 「接下来播放」+ 剩余秒数.
 *
 * 秒数**读在本组件里**而不是调用方: 它每秒变一次, 在上层读会让整条选集条 (含四张带图卡片)
 * 每秒陪跑一次重组. 没在倒数 (关掉了自动连播, 或片尾刚放完还没进倒计时窗口) 就只有前半句,
 * 那时它就是一句"下一集是这个, 按确定就播".
 */
@Composable
private fun TvUpNextStripHeader(state: TvUpNextState, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            stringResource(Lang.playback_up_next_start),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
        state.countdownSeconds?.let { seconds ->
            Text(
                stringResource(Lang.video_player_tv_up_next_in, seconds),
                color = Color.White.copy(alpha = TV_STRIP_HEADER_SUBTLE_ALPHA),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}
