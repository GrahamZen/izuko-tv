/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.subject.CanonicalTagKind
import me.him188.ani.app.data.network.BangumiSummaryService
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbMatchHints
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.search.RatingRange
import me.him188.ani.app.domain.search.SearchSort
import me.him188.ani.app.domain.search.SubjectSearchQuery
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.TV_CONFIRM_KEYS
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.session.TvNavigationSideRail
import me.him188.ani.app.ui.foundation.session.buildTvRailItems
import me.him188.ani.app.ui.foundation.tv.tvPlayKeyShortPress
import me.him188.ani.app.ui.foundation.focus.TvScrollAnimator
import me.him188.ani.app.ui.foundation.tv.TvPageBackdropLayer
import me.him188.ani.app.ui.foundation.tv.TvPortraitCard
import me.him188.ani.app.ui.foundation.focus.tvFocusMoveRateLimit
import me.him188.ani.app.ui.foundation.tv.rememberTvSettledHeroProvider
import me.him188.ani.app.ui.foundation.tv.TV_HERO_MEDIA_DEBOUNCE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvNavigationSettle
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaCache
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaSpec
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbor
import me.him188.ani.app.ui.foundation.tv.TvHeroNeighbors
import me.him188.ani.app.ui.foundation.tv.prefetchTvBackdrop
import me.him188.ani.app.ui.foundation.tv.rememberTvHeroMediaPipeline
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
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvGridFocus
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.focus.tvGridFocusItem
import me.him188.ani.app.ui.foundation.focus.tvGridKeyNavigation
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_search_filter_audience
import me.him188.ani.app.ui.lang.exploration_search_filter_category
import me.him188.ani.app.ui.lang.exploration_search_filter_character
import me.him188.ani.app.ui.lang.exploration_search_filter_custom
import me.him188.ani.app.ui.lang.exploration_search_filter_emotion
import me.him188.ani.app.ui.lang.exploration_search_filter_genre
import me.him188.ani.app.ui.lang.exploration_search_filter_rating
import me.him188.ani.app.ui.lang.exploration_search_filter_region
import me.him188.ani.app.ui.lang.exploration_search_filter_series
import me.him188.ani.app.ui.lang.exploration_search_filter_setting
import me.him188.ani.app.ui.lang.exploration_search_filter_source
import me.him188.ani.app.ui.lang.exploration_search_filter_technology
import me.him188.ani.app.ui.lang.exploration_search_sort_collection
import me.him188.ani.app.ui.lang.exploration_search_sort_date
import me.him188.ani.app.ui.lang.exploration_search_sort_match
import me.him188.ani.app.ui.lang.exploration_search_sort_rank
import me.him188.ani.app.ui.lang.search_tv_empty
import me.him188.ani.app.ui.lang.search_tv_filter
import me.him188.ani.app.ui.lang.search_tv_filter_any
import me.him188.ani.app.ui.lang.search_tv_filter_confirm
import me.him188.ani.app.ui.lang.search_tv_filter_rating_min
import me.him188.ani.app.ui.lang.search_tv_filter_sort
import me.him188.ani.app.ui.lang.search_tv_input_hint
import me.him188.ani.app.ui.lang.search_tv_remote_hint
import me.him188.ani.app.ui.lang.search_tv_results_all
import me.him188.ani.app.ui.lang.search_tv_results_title
import me.him188.ani.app.ui.search.LoadErrorCard
import me.him188.ani.app.ui.search.collectItemsWithLifecycle
import me.him188.ani.app.ui.search.isLoadingFirstPageOrRefreshing
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource

/** 见下面进程被杀后恢复搜索的那段 effect: 那条路平时不跑, 出事后只能靠日志判定。 */
private val searchRestoreLogger = logger("TvSearchPage")

/**
 * TV 沉浸式搜索页 (交互参考 Crunchyroll TV 搜索 + 本 fork 的沉浸式追番页):
 * 两个形态渐隐切换 ——
 * - 输入态: 顶部居中一个搜索框 (聚焦自动弹系统键盘), 下方候选列表 (空文本 = 搜索历史,
 *   有文本 = 补全建议); 确认提交后整个输入 UI 消失;
 * - 结果态: 全屏沉浸展示 (骨架同追番页): 顶部为搜索词 + 筛选按钮, Hero 区显示聚焦条目的
 *   标题/评分/元信息/简介, TMDB backdrop 渐隐背景, 下方 2:3 海报网格 (行吸顶/播放键直达).
 *
 * 返回分层: 网格非首卡 -> 回首卡; 结果态其余位置 -> 回输入态 (保留文字与光标, 自动弹键盘);
 * 输入态 -> 退出搜索页. 进详情/播放返回本页恢复焦点到原卡片.
 */
@Composable
fun TvSearchPage(
    state: SearchPageState,
    onIntent: (SearchPageIntent) -> Unit,
    suggestionsPager: (String) -> Flow<PagingData<String>>,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // 输入框内容与光标位置 (跨形态与跨导航保留: 从结果态返回可原样继续编辑)
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.query.keywords, TextRange(state.query.keywords.length)))
    }
    // 进页时若带初始查询 (如详情页点标签跳转) 直接落在结果态
    var showResults by rememberSaveable { mutableStateOf(state.query.hasSearchRequest()) }
    // 带初始查询进入时, 结果态按返回直接退出本页 (回详情页), 想改词要点顶部搜索词文字;
    // 用户在本页手动提交过搜索后恢复"返回回输入态"
    var backGoesToInput by rememberSaveable { mutableStateOf(!state.query.hasSearchRequest()) }
    // 网格滚动与最后聚焦卡片下标提到页面级: 跨形态切换与跨导航 (进详情返回) 都要保留.
    // 传 State 而非取值: 结果面板里的协程 (落点等待/吸顶 snapshotFlow) 要能观察到实时变化
    val gridState = rememberLazyGridState()
    val lastFocusedCard = rememberSaveable { mutableIntStateOf(-1) }
    // 进页那一刻的恢复目标快照 (从详情/播放器返回时恢复焦点); 只在结果态首次组合时消费一次
    val restoreCardIndex = remember { lastFocusedCard.intValue }
    var restoreConsumed by remember { mutableStateOf(false) }

    // 提交一次搜索并切到结果态. 关键词与筛选条件走同一条路: 查询不要求有关键词, "只选标签
    // 不打字"同样是一次合法搜索 (见 [willTriggerSearch]) —— 输入态的筛选钮正是靠这里在空
    // 关键词下直接进结果态.
    val applyQuery: (SubjectSearchQuery) -> Unit = apply@{ newQuery ->
        val normalized = newQuery.normalized()
        if (!normalized.willTriggerSearch()) return@apply // 搜不出东西的查询: 不切结果态
        query = TextFieldValue(normalized.keywords, TextRange(normalized.keywords.length))
        keyboard?.hide()
        onIntent(SearchPageIntent.UpdateQuery(normalized, submit = true))
        lastFocusedCard.intValue = -1
        backGoesToInput = true
        showResults = true
    }
    val submit: (String) -> Unit = { text -> applyQuery(state.query.copy(keywords = text)) }

    // 本页的 rememberSaveable 恢复了 (所以还停在结果态), 但 VM 是新的 —— [SearchViewModel] 的
    // 初始查询取自**路由** (从主页进搜索页时为空), 用户在页面里提交的词只活在 VM 里, 没有写回
    // 路由。结果就是: 结果态顶部搜索词为空 + pager 用空词查 → "没有找到相关条目", 看起来像
    // 结果丢了。本地 [query] 是 rememberSaveable, 一直还在, 用它把查询补回 VM。
    //
    // **只服务"进程被杀后恢复"这一种情形**。2026-08-23 排查时它一度在普通返回时也会跑 (同一进程
    // 内连着三次, `dumpsys activity exit-info` 无主进程死亡记录) —— 那是 Nav3 的 per-entry
    // ViewModelStore 被误销毁导致的, 根因已由
    // [rememberBackStackAwareViewModelStoreNavEntryDecorator] 修掉, 普通返回不再走到这里。
    // 留着它是因为进程真的被杀 (低内存电视上很常见) 之后 VM 必然是新的, 那时仍要靠本地保存的
    // 输入把结果找回来, 否则用户看到的是"结果态 + 空搜索词 + 没有找到相关条目"。
    LaunchedEffect(Unit) {
        if (!showResults || state.query.hasSearchRequest()) return@LaunchedEffect
        if (query.text.isNotBlank()) {
            searchRestoreLogger.info {
                "Search VM has no query (recreated on back navigation or after process death); " +
                    "resubmitting saved keywords (${query.text.length} chars)"
            }
            // **不能走 submit()**: 那是"用户主动提交新搜索"的语义 —— 它把 lastFocusedCard 清成 -1
            // 并置 backGoesToInput = true。于是返回后没有可恢复的落点, 焦点掉到顶部搜索词/搜索框
            // (2026-08-23 实测的回归, 正是这一行造成的)。这里只是把丢掉的查询补回 VM, 焦点恢复
            // 与返回分层都必须保持原样。
            onIntent(
                SearchPageIntent.UpdateQuery(
                    state.query.copy(keywords = query.text).normalized(),
                    submit = true,
                ),
            )
        } else {
            searchRestoreLogger.info {
                "Search VM has no query and no saved keywords either, falling back to input state"
            }
            showResults = false
        }
    }

    // 内容区焦点入口: 从页面外进来的焦点 (导航兜底的无方向 enter) 一律先送进内容区而非侧边栏
    // (同主页外壳 TvMainScreenLayout 的做法); 侧边栏靠内容区里按左键进入
    val contentFocus = remember { FocusRequester() }
    // 侧边栏右键/返回退出时的焦点还原: 结果面板在此注册"回上次聚焦卡片"的处理 (走带
    // 到位确认+重试的落点解析器). 未注册 (输入态) 或没有可回的卡时退回 contentFocus
    // 进组默认落点. 不还原会落到左上角搜索词文字上, 且直连 requestFocus 偶发被焦点系统
    // 静默拒绝时会看起来"按下键没反应"
    val railExitRestore = remember { mutableStateOf<(() -> Boolean)?>(null) }
    // 搜索框的落点 (输入面板挂在输入框上): 无方向进入本页内容区时的默认目标, 也是输入态里
    // "候选项按返回回到框"的目标
    val inputFieldFocus = remember { FocusRequester() }
    val navigator = LocalNavigator.current
    // **整页焦点丢失后自己立刻补** (2026-08-23): 结果态切回输入态时, 焦点先正常落到搜索框, 20ms
    // 后又被转场那一下带走 (结果面板销毁), 此后本页没有任何焦点 —— 只能等 AniAppContent 的全局
    // 兜底出手, 而它刻意先让位若干帧再每 100ms 打一次, 实测 ~700ms 才回来. 用户看到的就是
    // "搜索框要卡一下才高亮".
    // 这里不做轮询: 只在"整页 hasFocus 由真变假"这一个事件上补一次, 落点与 onEnter 同一套
    // (结果态回上次那张卡, 输入态回搜索框). 页面不在前台时不补 —— 那是别的页面的焦点, 抢过来
    // 就是这套框架最该避免的行为.
    var pageHasFocus by remember { mutableStateOf(false) }
    val pageIsForeground = LocalPageIsForeground.current
    LaunchedEffect(Unit) {
        snapshotFlow { pageHasFocus }.collect { has ->
            if (has || !pageIsForeground.value) return@collect
            if (railExitRestore.value?.invoke() != true) {
                runCatching { inputFieldFocus.requestFocus() }
            }
        }
    }
    // **筛选弹窗提到页面级**: 输入态与结果态共用同一个入口. 它原先只挂在结果态里, 而进结果态
    // 必须先提交一次搜索 —— 于是"不输入关键词直接选标签"在本页没有入口 (上游手机/桌面端的筛选
    // 胶囊行一直就在搜索页上, 不需要先搜一次).
    var showFilterDialog by remember { mutableStateOf(false) }
    if (showFilterDialog) {
        TvSearchFilterDialog(
            // 输入态: 把还没提交的输入框文字一并带进去, 于是"关键词 + 标签"能一次确认
            query = if (showResults) state.query else state.query.copy(keywords = query.text.trim()),
            filterState = state.searchFilterState,
            onConfirm = { newQuery ->
                showFilterDialog = false
                if (showResults) {
                    // 结果态原样: 只换查询, 不动返回分层 (标签深链进来的 backGoesToInput) 与落点记忆
                    onIntent(SearchPageIntent.UpdateQuery(newQuery, submit = true))
                } else {
                    // 输入态: 与手动提交同一条路; 什么都没选时 applyQuery 自己会留在输入态
                    applyQuery(newQuery)
                }
            },
            onDismiss = { showFilterDialog = false },
        )
    }
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .onFocusChanged { pageHasFocus = it.hasFocus }
            .focusProperties { onEnter = { contentFocus.requestFocus() } }
            .focusGroup(),
    ) {
        Box(
            Modifier.fillMaxSize()
                // 让开左缘侧边栏, 使本页内容左边界与探索/追番页一致
                .padding(start = TvNavigationRailDefaults.CollapsedWidth)
                .focusRequester(contentFocus)
                // **页面外/兜底进来的焦点统一在此改道** (同探索页与主壳的做法): 结果态回上次
                // 聚焦的那张卡, 输入态回搜索框.
                //
                // 不改道的后果 (2026-08-23 真机日志钉死): 这个 Box 只是个不可聚焦的 focusGroup,
                // Compose 对它的 requestFocus 会把 Enter 转成 Right、从左边缘做 2D 搜索, 落点
                // 不可预测 —— 实测落在**候选列表**上。触发链是: 结果态按返回切回输入态 → 搜索框
                // 拿到焦点 → 23ms 后焦点丢失 (结果面板随转场销毁那一下) → 整页没有焦点 → 700ms
                // 后 AniAppContent 的全局兜底打一次无方向 requestFocus → 落到候选项第一条。
                // 用户症状: "深滚一路返回, 先跳到第一个候选, 再按一次返回才回搜索框"。
                .focusProperties {
                    onEnter = {
                        if (railExitRestore.value?.invoke() != true) {
                            runCatching { inputFieldFocus.requestFocus() }
                        }
                    }
                }
                .focusGroup(),
        ) {
            AnimatedContent(
                targetState = showResults,
                transitionSpec = {
                    fadeIn(tween(TV_SEARCH_MODE_FADE_MILLIS)) togetherWith
                            fadeOut(tween(TV_SEARCH_MODE_FADE_MILLIS))
                },
                label = "searchMode",
            ) { results ->
                if (results) {
                    TvSearchResultsPane(
                        state = state,
                        onIntent = onIntent,
                        gridState = gridState,
                        lastFocusedCard = lastFocusedCard,
                        restoreCardIndex = if (restoreConsumed) -1 else restoreCardIndex,
                        onRestoreConsumed = { restoreConsumed = true },
                        onBackToInput = { showResults = false },
                        backGoesToInput = backGoesToInput,
                        onOpenFilter = { showFilterDialog = true },
                        railExitRestore = railExitRestore,
                    )
                } else {
                    TvSearchInputPane(
                        query = query,
                        onQueryChange = { query = it },
                        historyPager = state.searchHistoryPager,
                        suggestionsPager = suggestionsPager,
                        onSubmit = submit,
                        fieldFocusRequester = inputFieldFocus,
                        onOpenFilter = { showFilterDialog = true },
                        hasFilters = state.query.hasFilters(),
                    )
                }
            }
        }
        // 左缘 overlay 侧边栏: 与主页/详情页完全同一实现. 本页不显示头像信息 (selfInfo = null,
        // 保留槽位使其余按钮位置不变); "搜索"项 = 回输入态改词
        TvNavigationSideRail(
            selfInfo = null,
            onAvatarClick = {},
            onExitFocus = {
                // 结果态优先回上次聚焦的卡片 (与进页恢复一致), 其余情况进内容区默认落点
                if (railExitRestore.value?.invoke() != true) {
                    runCatching { contentFocus.requestFocus() }
                }
            },
            items = buildTvRailItems(
                onSearch = { showResults = false },
                onNavigateToPage = { navigator.popBackOrNavigateToMain(it) },
                onSettings = { navigator.navigateSettings() },
            ),
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

/**
 * 这次查询会不会真的发起一次搜索. **判据必须跟 `SearchViewModel.shouldTriggerSearch` 对齐**,
 * 不能改用 [SubjectSearchQuery.hasSearchRequest]: 后者把任何"非默认排序"都算作有搜索请求, 而
 * 最多收藏/最新发布这两档在没有关键词也没有筛选时服务端只返回空列表 —— 切过去就是
 * "没有找到相关条目" + 一个孤零零的排序胶囊. 只有排名排序自带"有排名"这个筛选 (见
 * `SubjectSearchRepository.toSubjectSearchFilters`), 单选它就是全站排行榜.
 */
private fun SubjectSearchQuery.willTriggerSearch(): Boolean =
    keywords.isNotEmpty() || !tags.isNullOrEmpty() || season != null || rating != null ||
            nsfw != null || sort == SearchSort.RANK

// ============================ 输入态 ============================

@Composable
private fun TvSearchInputPane(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    historyPager: Flow<PagingData<String>>,
    suggestionsPager: (String) -> Flow<PagingData<String>>,
    onSubmit: (String) -> Unit,
    fieldFocusRequester: FocusRequester,
    onOpenFilter: () -> Unit,
    hasFilters: Boolean,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // **搜索框分两态** (2026-08-23 用户要求: 返回回到框上不该弹键盘, 按确认键才弹):
    //  - 非编辑态: 焦点落在**框这个整体**上 (只高亮描边), 里面的 BasicTextField 不可聚焦、
    //    readOnly —— Compose 的输入框一获焦就自己开输入会话, 只删掉显式的 keyboard.show() 是
    //    挡不住的, 必须让它压根拿不到焦点;
    //  - 编辑态: 确认键进入, 焦点交给输入框, 键盘随之弹出。
    //
    // 为什么值得这么绕: IME 会吃掉一次返回键, 于是"从结果页退回主页"要按三下 (关键盘/回框/离开)。
    // 分两态之后, 路过搜索框不再弹键盘, 退出只要两下, 而想打字的人多按一下确认。
    var editing by remember { mutableStateOf(false) }
    var everEdited by remember { mutableStateOf(false) }
    var boxFocused by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    // 进/出编辑态的焦点交接必须放在效应里: `canFocus` 是组合期读的, 在按键回调里当场
    // requestFocus 时对方还不可聚焦, 请求会被静默拒绝
    // 退出编辑态时要不要把焦点收回框上. **只有主动退出才收** (返回键 / IME 被关):
    // 焦点被方向键带走那条路 (下面输入框的 onFocusChanged) 也会退出编辑态, 那时用户已经站在
    // 第一个候选上了, 再收一次就是把焦点从他脚下拽回来 —— 表现为"往下导航时焦点被拉回搜索框一次".
    var returnFocusToBox by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            everEdited = true
            runCatching { editorFocus.requestFocus() }
            keyboard?.show()
        } else if (everEdited) {
            keyboard?.hide()
            if (returnFocusToBox) {
                returnFocusToBox = false
                runCatching { fieldFocusRequester.requestFocus() }
            }
        }
    }

    // **IME 被系统关掉时同步退出编辑态**: 编辑态里按返回, 这一下多半被 IME 自己吃掉 (键盘收起,
    // 事件到不了下面的 BackHandler), 于是 editing 还是 true —— 光标留在框里, 而此刻按确认键
    // 事件落在已持焦的输入框上, 没人负责把键盘叫回来, 表现是"按确认没反应, 要先返回一次再确认".
    //
    // 只认"先看见它弹出、再看见它消失"这个序列: 若某些形态下 IME insets 根本不上报 (窗口声明了
    // adjustNothing 时尤其要防), 第一个 await 就永远不满足, 本效应静默失效, 不会误把编辑态关掉.
    // **必须用 rememberUpdatedState 保住 state 身份**: 直接 `val v = WindowInsets.isImeVisible`
    // 在组合期就把值解出来了, 效应里的 snapshotFlow 捕获的是那个常量 —— 观察不到任何变化,
    // 两个 await 就是死等 (2026-08-25 第一版即栽在这, 表现是修了等于没修).
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = rememberUpdatedState(WindowInsets.isImeVisible)
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        snapshotFlow { imeVisible.value }.first { it }
        snapshotFlow { imeVisible.value }.first { !it }
        returnFocusToBox = true
        editing = false
    }

    // 输入态的返回分层 (与探索页"最终落点是轮播主按钮"同一条规矩):
    //  - 编辑态里按返回: 退出编辑回到框 (键盘若还开着, 这一下多半已被 IME 自己吃掉, 那就是下一下);
    //  - 焦点在下面的候选项上: 返回先送回搜索框;
    //  - 焦点已经在框上: 不拦, 让返回穿到上层离开本页。
    // 于是"退出搜索页"永远从同一个位置发生, 不会出现"在候选列表里按一下返回整页就没了"。
    // 判据用"本面板持焦但框没持焦": 焦点在侧边栏时不拦 (那边有自己的规矩)。
    var paneHasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = editing) {
        returnFocusToBox = true
        editing = false
    }
    BackHandler(enabled = !editing && paneHasFocus && !boxFocused) {
        runCatching { fieldFocusRequester.requestFocus() }
    }

    Column(
        modifier.fillMaxSize().imePadding()
            .onFocusChanged { paneHasFocus = it.hasFocus },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(TV_SEARCH_INPUT_TOP_PAD))
        // 搜索框 (参考 Crunchyroll: 顶部居中单框; 聚焦高亮描边) 与右侧筛选钮.
        // **筛选入口必须在输入态也有**: 查询本来就允许"只有标签没有关键词"
        // ([SubjectSearchQuery.hasSearchRequest]), 但本页进结果态必须先提交一次搜索, 而筛选钮
        // 原先只在结果态顶部行 —— 于是"不打字直接按标签浏览"在本页够不着 (上游手机/桌面端的
        // 筛选胶囊行一直摆在搜索页上, 不需要先搜一次).
        // 整行宽度仍是候选列表那个比例 (框左边界与下方候选项对齐不变), 框用 weight 让出钮的宽度;
        // height(IntrinsicSize.Min) 让钮跟着框的内容高度走, 不写死高度.
        Row(
            Modifier.fillMaxWidth(TV_SEARCH_INPUT_WIDTH_FRACTION).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                Modifier.weight(1f)
                    .ifThen(boxFocused || editing) {
                        border(
                            2.5.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(TV_SEARCH_INPUT_CORNER),
                        )
                    }
                    // 非编辑态: 框自己是焦点目标 (页面级 requester 指向这里, 见 inputFieldFocus);
                    // 编辑态: 让位给里面的输入框
                    .focusRequester(fieldFocusRequester)
                    .focusProperties { canFocus = !editing }
                    .onFocusChanged { boxFocused = it.isFocused }
                    // 确认键进编辑态. 认 KeyUp: 键盘一弹出就抢走后续按键, 在 KeyDown 上做会把同一次
                    // 按键的 KeyUp 留给 IME (它可能当成一次"确定"); KeyDown 也一并吞掉, 免得
                    // focusable 的默认点击语义或 Enter 触发别的东西
                    .onPreviewKeyEvent { event ->
                        if (event.key !in TV_CONFIRM_KEYS) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyUp) editing = true
                        true
                    }
                    .focusable()
                    // 进页/回本态的初始焦点: 落在框上 (非编辑态), 不是落进输入框
                    .tvWindowInitialFocus(),
                shape = RoundedCornerShape(TV_SEARCH_INPUT_CORNER),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val textStyle = MaterialTheme.typography.titleMedium
                    BasicTextField(
                        value = query,
                        onValueChange = { onQueryChange(it.copy(text = it.text.trim('\n'))) },
                        modifier = Modifier.weight(1f)
                            .focusRequester(editorFocus)
                            .focusProperties { canFocus = editing }
                            .onFocusChanged {
                                // 焦点被方向键带走 (走到候选项) 也算退出编辑
                                if (!it.isFocused && editing) editing = false
                            },
                        readOnly = !editing,
                        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit(query.text) }),
                        decorationBox = { innerTextField ->
                            Box {
                                if (query.text.isEmpty()) {
                                    Text(
                                        stringResource(Lang.search_tv_input_hint),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = textStyle,
                                        maxLines = 1,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }
            TvSearchInputFilterButton(
                hasFilters = hasFilters,
                onClick = onOpenFilter,
                modifier = Modifier.fillMaxHeight(),
            )
        }

        // 候选列表: 空文本显示搜索历史, 有文本显示防抖后的补全建议; 确认即提交
        var debounced by remember { mutableStateOf(query.text) }
        LaunchedEffect(query.text) {
            delay(TV_SEARCH_SUGGESTION_DEBOUNCE_MILLIS)
            debounced = query.text
        }
        val isHistory = debounced.isEmpty()
        val values = remember(debounced) {
            if (debounced.isEmpty()) historyPager else suggestionsPager(debounced)
        }.collectAsLazyPagingItemsWithLifecycle()
        LazyColumn(
            Modifier.fillMaxWidth(TV_SEARCH_INPUT_WIDTH_FRACTION)
                .padding(top = 12.dp)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                count = values.itemCount,
                key = values.itemKey { "tv-search-suggestion-$it" },
                contentType = values.itemContentType { 1 },
            ) { index ->
                val text = values[index] ?: return@items
                TvSearchSuggestionRow(
                    text = text,
                    isHistory = isHistory,
                    onClick = { onSubmit(text) },
                )
            }
        }
    }
}

/**
 * 输入态搜索框右侧的筛选钮: 打开与结果态同一个筛选弹窗 (排序 / 最低评分 / 标签).
 * 空关键词下选了标签确认即直接进结果态 —— 这是"不打字只按标签浏览"的入口.
 * 外观跟搜索框同一套 (同底色/同圆角/聚焦时主题色描边), 已有筛选时右上角一个小圆点.
 */
@Composable
private fun TvSearchInputFilterButton(
    hasFilters: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(modifier) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxHeight()
                .ifThen(focused) {
                    border(
                        2.5.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(TV_SEARCH_INPUT_CORNER),
                    )
                },
            shape = RoundedCornerShape(TV_SEARCH_INPUT_CORNER),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            interactionSource = interactionSource,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(Lang.search_tv_filter),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
        // 有筛选时的小圆点 (同结果态顶部行的筛选钮)
        if (hasFilters) {
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

@Composable
private fun TvSearchSuggestionRow(
    text: String,
    isHistory: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        interactionSource = interactionSource,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (isHistory) Icons.Default.History else Icons.Default.Search,
                contentDescription = null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ============================ 结果态 ============================

@Composable
private fun TvSearchResultsPane(
    state: SearchPageState,
    onIntent: (SearchPageIntent) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    lastFocusedCard: MutableIntState,
    restoreCardIndex: Int,
    onRestoreConsumed: () -> Unit,
    onBackToInput: () -> Unit,
    backGoesToInput: Boolean,
    /** 打开筛选弹窗 (弹窗本体在页面级, 输入态共用同一个). */
    onOpenFilter: () -> Unit,
    /** 侧边栏右键退出时的焦点还原注册槽 (见页面级声明); 本面板在位时写入, 离场清空. */
    railExitRestore: MutableState<(() -> Boolean)?>,
    modifier: Modifier = Modifier,
) {
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val bangumiSummaryService = remember { GlobalKoin.get<BangumiSummaryService>() }
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val setCollectionTypeUseCase = remember { GlobalKoin.get<SetSubjectCollectionTypeOrDeleteUseCase>() }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val items = state.searchState.collectItemsWithLifecycle()

    // Hero 数据源: 聚焦卡片驱动; 默认当前列表第一项, 列表确认为空才清
    var heroItem by remember { mutableStateOf<SubjectPreviewItemInfo?>(null) }
    // 聚焦卡的邻居 (subjectId -> 邻居), 在 onFocused 里按网格几何算好; 记 subjectId 是为了
    // 默认 hero (列表第一项, 没被聚焦过) 时不错用上一次聚焦位置的邻居
    var heroNeighbors by remember { mutableStateOf<Pair<Int, TvHeroNeighbors>?>(null) }
    // **进页恢复到某张卡时不能先摆默认 hero** (与追番页同病同修, 见那边的
    // `heroDefaultBlockedByRestore`): [heroItem] 是 `remember` —— 从详情页返回时本页组合已经重建、
    // 值回到 null, 下面这条效应数据一到就把它设成**第一项**, 而恢复落点要几十到几百毫秒后才由
    // 卡片的 onFocused 改成正确那张. 真机上看得见 backdrop 先闪一下第一张卡的图再跳回来
    // (翻得越深越明显: 落点要先滚过去).
    // 放开的时机在下方恢复效应里: 落点**有结果**(送达/用户接手/判空取消) 才放 —— 送达时 hero 已经
    // 是正确那张, 放开只为兜住"恢复失败"的情形 (那时该退回第一项).
    var heroDefaultBlockedByRestore by remember { mutableStateOf(restoreCardIndex >= 0) }
    LaunchedEffect(
        items.itemCount > 0,
        items.isLoadingFirstPageOrRefreshing,
        heroDefaultBlockedByRestore,
    ) {
        if (heroDefaultBlockedByRestore) return@LaunchedEffect
        val cur = heroItem
        if (items.itemCount > 0) {
            if (cur == null || items.itemSnapshotList.items.none { it.subjectId == cur.subjectId }) {
                heroItem = runCatching { items.peek(0) }.getOrNull()
            }
        } else if (!items.isLoadingFirstPageOrRefreshing) {
            heroItem = null
        }
    }

    // hero 的**展示**目标: 低特效档下连发导航期间不换背景图/文字, 停下来才换一次 (完整特效档
    // 原样直通). 下面的数据预取仍读真实的 heroItem —— 停下来时数据已在缓存里, 换挡不等网络.
    // 用 provider 版的理由同追番页 (别把热状态读进页面 body), 见 [rememberTvSettledHeroProvider]
    val heroDisplay = rememberTvSettledHeroProvider { heroItem }

    // subjectId -> TMDB backdrop URL (null = 已查过没有); 搜索结果没有 summary 字段,
    // 简介一律按聚焦条目异步向 bgm.tv 取 ("" = 查过没有); 网络错误都不写缓存, 下次聚焦重试.
    // 收藏状态供长按菜单高亮当前项, 聚焦时顺带拉取, 菜单操作成功后本地覆盖
    // backdrop 与简介走进程级共享表 (见 TvHeroMediaCache): 搜索结果里的条目多半在探索/追番页
    // 也见过, 共表之后不必再各解析一次
    val summaryCache = TvHeroMediaCache.summaryFallbacks
    val collectionTypeCache = remember { mutableStateMapOf<Int, UnifiedCollectionType>() }
    // hero 媒体流水线 (连发合并/调度器/邻居预取/图片预热/封面兜底), 与探索/追番页同一 host.
    // 本页的解析链最短: 只有整部 backdrop 一跳 —— 必须用原名 (日文) 匹配 TMDB (中文译名命中
    // 率低, 且失败会写持久负缓存); 搜索结果没有分集数据, 拿不到"最新已播集日期"只能不传
    // (连载新番的负缓存因此要等常规过期, 不像另外三页那样能按播出日期限期失效)
    val heroPipeline = rememberTvHeroMediaPipeline(
        tmdb = tmdb,
        fullVisualEffects = false, // 本页无剧照链, 恒 w1280 档
        // 键到 items 上: 重新搜索换分页实例时重启, 不然闭包里捕获的是旧实例
        restartKey = items,
        spec = {
            heroItem?.let { info ->
                info.toHeroMediaSpec(
                    heroNeighbors?.takeIf { it.first == info.subjectId }?.second ?: TvHeroNeighbors(),
                )
            }
        },
        resolve = { s ->
            items.itemSnapshotList.items.firstOrNull { it.subjectId == s.subjectId }?.let { item ->
                tmdb.prefetchTvBackdrop(
                    s.subjectId, item.originalName.ifBlank { item.title },
                    hints = item.tmdbHints,
                )
            }
        },
        resolveNeighbor = { _, neighbor ->
            // 邻居的原名就在列表项里 (本页链短的原因), 不用发条目信息请求
            items.itemSnapshotList.items.firstOrNull { it.subjectId == neighbor.subjectId }?.let { item ->
                tmdb.prefetchTvBackdrop(
                    neighbor.subjectId, item.originalName.ifBlank { item.title },
                    hints = item.tmdbHints,
                )
            }
        },
        afterResolve = { s ->
            launch { bangumiSummaryService.prefetchTvSummaryFallback(s.subjectId) }
            // 收藏状态供长按菜单高亮当前项, 聚焦时顺带拉取, 菜单操作成功后本地覆盖
            if (s.subjectId !in collectionTypeCache) {
                launch {
                    runCatching { collectionRepo.subjectCollectionFlow(s.subjectId).first() }
                        .onSuccess { collectionTypeCache[s.subjectId] = it.collectionType }
                }
            }
            true
        },
    )

    // 卡片长按弹出的收藏下拉 (与探索页/追番页一致); 打开后短暂吞掉长按残余的确认键, 避免误触第一项.
    // remember: 工厂被网格 items 内容 lambda 捕获, 每次新实例都会让所有可见卡片跟着重组
    val collectionMenuFor: (Int) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit = remember {
        { subjectId ->
            { expanded, onDismiss ->
                EditCollectionTypeDropDown(
                    currentType = collectionTypeCache[subjectId] ?: UnifiedCollectionType.NOT_COLLECTED,
                    expanded = expanded,
                    onDismissRequest = onDismiss,
                    onClick = { action ->
                        scope.launch {
                            runCatching { setCollectionTypeUseCase(subjectId, action.type) }
                                .onSuccess { collectionTypeCache[subjectId] = action.type }
                                .onFailure { toaster.showLoadError(LoadError.fromException(it)) }
                        }
                    },
                    // 卡片的菜单只有长按一个入口, 恒吞掉那次长按残余的确认键
                    modifier = Modifier.consumeHeldConfirmKey(),
                )
            }
        }
    }

    // 焦点动线锚点与网格落点解析 (与追番页共用 [TvGridFocusState] 落点机制:
    // 目标滚进视口后靠锚点附着事件送达, 不轮询)
    val titleFocusRequester = remember { FocusRequester() }
    val errorCardFocusRequester = remember { FocusRequester() }
    val focus = rememberTvFocusScope()
    val gridFocus = rememberTvGridFocus(focus)
    var gridHasFocus by remember { mutableStateOf(false) }
    var gridColumns by remember { mutableIntStateOf(1) }
    // 进页恢复的落点流程是否已收尾 (发出 request 或放弃). 见下方 backToFirstCard: 这一段
    // "数据已到但 request 还没发出"的缝隙必须算作"焦点还在网格里", 否则返回键会误跳输入态
    var restoreSettled by remember { mutableStateOf(false) }

    gridFocus.SendFocusEffect(gridState) { items.itemCount }

    // 侧边栏右键退出 → 回上次聚焦的卡片 (与进页恢复一致), 而不是空间焦点搜索/进组默认
    // 落到左上角搜索词文字上. 走上面的落点解析器: 聚焦到位确认 + 重试, 直连 requestFocus
    // 偶发被焦点系统静默拒绝时不至于永久卡死
    DisposableEffect(Unit) {
        railExitRestore.value = restore@{
            val count = items.itemCount
            val last = lastFocusedCard.intValue
            if (count <= 0 || last < 0) return@restore false
            gridFocus.focusItem(minOf(last, count - 1))
            true
        }
        onDispose { railExitRestore.value = null }
    }

    // 初始焦点: 返回本页恢复到此前聚焦的卡片, 新搜索聚焦第一张; 结果没到先等 (加载失败聚焦错误卡)
    LaunchedEffect(Unit) {
        val target = if (restoreCardIndex >= 0) restoreCardIndex else 0
        onRestoreConsumed()
        // Paging 数据/错误本身就是事件源; 首个终态到达后一次性分派落点.
        snapshotFlow { items.itemCount to items.loadState.hasError }
            .first { (count, hasError) -> count > 0 || hasError }
        when {
            items.itemCount > 0 -> gridFocus.focusItem(target)
            items.loadState.hasError -> runCatching { errorCardFocusRequester.requestFocus() }
        }
        // 落点已派出 (pending 从这一刻起接手兜底), 恢复流程收尾
        restoreSettled = true
        // 见 heroDefaultBlockedByRestore: 等落点有结果再放开默认 hero. switching 已是 false
        // (没派出落点的分支) 时当帧返回
        if (heroDefaultBlockedByRestore) {
            snapshotFlow { gridFocus.switching }.first { !it }
            heroDefaultBlockedByRestore = false
        }
    }

    // 已选筛选项 (标签 / 最低评分 / 非默认排序): 顶部行下方一行胶囊, 点击取消该项.
    // remember: 列表被网格 items 内容 lambda 间接捕获 (见 onNavigateDown), 只在查询变化时重建
    val currentSortLabel = tvSearchSortLabel(state.query.sort)
    val activeFilters = remember(state.query, currentSortLabel) {
        buildList {
            state.query.tags.orEmpty().forEach { tag ->
                add(tag to state.query.copy(tags = (state.query.tags.orEmpty() - tag).ifEmpty { null }))
            }
            state.query.rating?.min?.let { min ->
                add("≥$min" to state.query.copy(rating = null))
            }
            if (state.query.sort != SearchSort.MATCH) {
                add(currentSortLabel to state.query.copy(sort = SearchSort.MATCH))
            }
        }
    }
    val chipsFocusRequester = remember { FocusRequester() }

    // 从上方 (顶部行/筛选行) 把焦点送进网格: 主走落点解析器聚焦当前视口首行行首 (到位
    // 确认 + 重试; 此前首选"直连首卡 requestFocus", 偶发被焦点系统静默拒绝时 runCatching
    // 照样报成功, 下键被吞且不再重试, 表现为卡在顶部行下不去); 网格空时退到错误横幅
    val focusGridFromAbove: () -> Boolean = {
        val firstVisible = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
        if (firstVisible != null) {
            gridFocus.focusItem((firstVisible / gridColumns) * gridColumns)
            true
        } else {
            runCatching { errorCardFocusRequester.requestFocus() }.getOrDefault(false)
        }
    }

    // 返回分层: 网格非首卡 -> 回首卡; 其余 (首卡/顶部行) -> 回输入态改词.
    // 点标签深链进入 (backGoesToInput=false) 时后者不拦截, 返回直接退出本页回详情页.
    // derivedStateOf: 条件里的焦点下标每移一格都变, 直接读会让整个结果面板每格重组,
    // 收窄成布尔后只在 首卡<->非首卡 边界变化时才失效
    // **「焦点在网格里」不能只看 gridHasFocus**: 从详情页返回本页时它要等数据到达 → 滚到目标卡
    // → 卡片组合 → 聚焦到位才变 true (实测 300ms~1.9s, 目标卡越靠后越久). 这段窗口里按返回会
    // 掉到下面那条"回输入态"上 —— 用户明明停在第 19 张卡, 整页却退回搜索框, 看起来就是
    // "搜索结果全没了" (issue #2; 实测那次按键与焦点落位只差 6ms). 恢复未收尾时一律视同焦点
    // 已在网格里: pending 非空 = 落点解析进行中; itemCount == 0 = 数据还没到 (request 都还没发).
    // 时间表页的同名判据本就是这么写的 (ScheduleLayeredBackHandler 的 gridFocus.switching).
    //
    // 三段接力覆盖整个恢复过程, 缺一段就漏:
    //   组合 → 派出落点: !restoreSettled
    //   派出 → 焦点落位: gridFocus.switching
    //   落位之后:       gridHasFocus
    val backToFirstCard by remember {
        derivedStateOf {
            val gridEngaged = gridHasFocus || gridFocus.switching || !restoreSettled
            gridEngaged && lastFocusedCard.intValue > 0
        }
    }
    BackHandler(enabled = backToFirstCard) {
        gridFocus.focusItem(0)
    }
    BackHandler(enabled = backGoesToInput && !backToFirstCard) {
        onBackToInput()
    }

    // 播放键: 短按直达播放聚焦那张卡. **挂在页面根上而不是网格的键路由里** —— 那条路由只看
    // KeyDown, 而播放键按下那一刻还分不出短按还是长按, 在那儿处理会把全局的长按手势 (打开动作
    // 面板) 整个吃掉. 本页原先正是那么写的, 表现为卡片上长按播放键直接进了播放器
    val playKeyModifier = tvPlayKeyShortPress(
        onPlay = {
            val info = lastFocusedCard.intValue.takeIf { it >= 0 }
                ?.let { runCatching { items.peek(it) }.getOrNull() }
            if (info != null) {
                onIntent(SearchPageIntent.Play(info))
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
        // 背景 backdrop 层: 同追番页 (16:9 贴右上角, 恒用卡片态渐变).
        // URL 用 lambda 传入: 聚焦条目状态在组件内部才读取, 换卡只重组这一小块
        TvPageBackdropLayer(
            // 搜索结果没有"下一集"的概念, 只用整部 backdrop; 封面兜底/垫底的 NSFW 门控
            // 在 toHeroMediaSpec 里 (判据照抄卡片: 卡片不出图, 全屏更不能出)
            backdropUrl = { heroPipeline.backdropUrl(heroDisplay()?.toHeroMediaSpec()) },
            // 本页是独立页面, 图层正下方是页面根 Box 自铺的 colorScheme.background
            fadeColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.align(Alignment.TopEnd),
            underlayUrl = { heroPipeline.underlayUrl(heroDisplay()?.toHeroMediaSpec()) },
            // 这张图解码完顺手算主题色, 点进详情页第一帧就是动态色 (详情页取的也是这张)
            themeSeedSubjectId = { heroDisplay()?.subjectId },
        )

        Column(
            Modifier.fillMaxSize()
                .padding(start = TV_SEARCH_START_PAD, top = TV_SEARCH_TOP_PAD),
        ) {
            // 顶部行: 搜索词 (确认回输入态改词) + 筛选按钮
            TvSearchTopRow(
                keywords = state.query.keywords,
                hasFilters = state.query.hasFilters(),
                titleFocusRequester = titleFocusRequester,
                onEditQuery = onBackToInput,
                onOpenFilter = onOpenFilter,
                onNavigateDown = {
                    // 有已选筛选项时先落到筛选行, 否则直接进网格
                    (activeFilters.isNotEmpty() &&
                            runCatching { chipsFocusRequester.requestFocus() }.getOrDefault(false)) ||
                            focusGridFromAbove()
                },
                onFallbackFocused = focus::notifyFocusFallbackSettled,
            )

            // 已选筛选项行 (点击取消; 超宽时吸左滚动): 占固定高度块 (上间距 + 行高 = 简介
            // 两行行距 40dp), 出现时下方 hero 信息块等量压缩 —— 简介少两行, 网格位置不动
            if (activeFilters.isNotEmpty()) {
                TvSearchActiveFiltersRow(
                    filters = activeFilters,
                    onRemove = { newQuery ->
                        onIntent(SearchPageIntent.UpdateQuery(newQuery, submit = true))
                    },
                    entryFocusRequester = chipsFocusRequester,
                    onNavigateUp = { runCatching { titleFocusRequester.requestFocus() }.getOrDefault(false) },
                    onNavigateDown = focusGridFromAbove,
                    onEmptied = { runCatching { titleFocusRequester.requestFocus() } },
                    modifier = Modifier.padding(top = TV_SEARCH_FILTERS_TOP_GAP)
                        .height(TV_SEARCH_FILTERS_ROW_HEIGHT),
                )
            }

            // Hero 信息块 (固定高度; 换条目整块文字渐隐渐现). 聚焦条目状态在子组件内部
            // 才读取, 遥控器换卡只重组信息块自身, 不连带整个结果面板
            TvSearchHeroInfoBlock(
                heroItemProvider = heroDisplay,
                summaryCache = summaryCache,
                // end 留白与探索页 hero 块一致, 否则 fillMaxWidth(比例) 的基数比其他页宽.
                // 有筛选行时等量压缩高度, 保持网格位置不变
                modifier = Modifier.fillMaxWidth()
                    .padding(top = TV_SEARCH_TITLE_TO_HERO_GAP, end = TV_PAGE_END_PAD)
                    .height(
                        if (activeFilters.isEmpty()) TV_SEARCH_HERO_INFO_HEIGHT
                        else TV_SEARCH_HERO_INFO_HEIGHT - TV_SEARCH_FILTERS_TOP_GAP - TV_SEARCH_FILTERS_ROW_HEIGHT,
                    ),
            )

            // 竖版海报网格
            if (items.loadState.hasError) {
                LoadErrorCard(
                    LoadError.fromCombinedLoadStates(items.loadState),
                    onRetry = { items.refresh() },
                    Modifier.padding(top = TV_SEARCH_HERO_TO_GRID_GAP, end = TV_PAGE_END_PAD)
                        .focusRequester(errorCardFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                runCatching { titleFocusRequester.requestFocus() }.getOrDefault(false)
                            } else {
                                false
                            }
                        },
                )
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth()
                    .padding(top = TV_SEARCH_HERO_TO_GRID_GAP)
                    .onFocusChanged { gridHasFocus = it.hasFocus },
            ) {
                // 复刻 GridCells.Adaptive 的列数算法 (整数 px 运算), 供行列换算
                val density = LocalDensity.current
                gridColumns = with(density) {
                    val available = (this@BoxWithConstraints.maxWidth - TV_PAGE_END_PAD).roundToPx()
                    val spacing = TV_PAGE_CARD_SPACING.roundToPx()
                    maxOf(1, (available + spacing) / (TV_PAGE_CARD_WIDTH.roundToPx() + spacing))
                }
                // 底部补白 = 视口高 - 一行卡高: 让最后一行也能吸到网格顶部
                // (内容不足一屏时 animateScrollToItem 滚不动, 接近底部的行会失去吸顶)
                val gridBottomPad = run {
                    val available = this@BoxWithConstraints.maxWidth - TV_PAGE_END_PAD
                    val cardWidth = (available - TV_PAGE_CARD_SPACING * (gridColumns - 1)) / gridColumns
                    val cardHeight = cardWidth / TV_PORTRAIT_CARD_COVER_RATIO
                    (this@BoxWithConstraints.maxHeight - cardHeight).coerceAtLeast(24.dp)
                }
                // 聚焦行吸顶 (同追番页): 关闭默认"刚好露出"式自动滚动, 聚焦行滚到网格顶部
                val noBringIntoView = remember {
                    object : BringIntoViewSpec {
                        override fun calculateScrollDistance(
                            offset: Float,
                            size: Float,
                            containerSize: Float,
                        ): Float = 0f
                    }
                }
                LaunchedEffect(gridState) {
                    // collectLatest + TvScrollAnimator: 连发按键取消进行中的滚动并继承速度,
                    // 列表连续流动 (原 collect 要等上一格动画跑完才响应下一个目标)
                    val scrollAnimator = TvScrollAnimator()
                    snapshotFlow { lastFocusedCard.intValue }.collectLatest { focused ->
                        if (focused >= 0) {
                            runCatching {
                                scrollAnimator.animateScrollToItem(gridState, (focused / gridColumns) * gridColumns)
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalBringIntoViewSpec provides noBringIntoView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(TV_PAGE_CARD_WIDTH),
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            // 长按方向键的移动频率上限 (同追番页/探索页). 必须挂在 tvGridKeyNavigation
                            // 之前: 两者都是 onPreviewKeyEvent, 靠前的先收到, 导航逻辑只看放行的那几发
                            .tvFocusMoveRateLimit()
                            // 同列上下导航 + 播放键直达 (与追番页共享实现, 理由见 [tvGridKeyNavigation])
                            .tvGridKeyNavigation(
                                gridFocus,
                                focusedIndex = { lastFocusedCard.intValue },
                                itemCount = { items.itemCount },
                                columns = { gridColumns },
                                // 顶行上键: 有筛选行先回筛选行, 否则回顶部行搜索词
                                onTopRowUp = {
                                    (activeFilters.isNotEmpty() &&
                                            runCatching { chipsFocusRequester.requestFocus() }.getOrDefault(false)) ||
                                            runCatching { titleFocusRequester.requestFocus() }.getOrDefault(false)
                                },
                            ),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(TV_PAGE_CARD_SPACING),
                        verticalArrangement = Arrangement.spacedBy(TV_PAGE_CARD_SPACING),
                        contentPadding = PaddingValues(end = TV_PAGE_END_PAD, bottom = gridBottomPad),
                    ) {
                        items(
                            count = items.itemCount,
                            // 搜索分页可能跨页返回重复条目, key 必须掺入 index (同原搜索页做法),
                            // 只用 subjectId 会因重复 key 直接崩溃
                            key = { index ->
                                val item = items.peek(index)
                                if (item == null) {
                                    "TvSearchPage-placeholder-$index"
                                } else {
                                    "TvSearchPage-$index-${item.subjectId}"
                                }
                            },
                        ) { index ->
                            val info = items[index]
                            TvPortraitCard(
                                // NSFW 模糊模式的条目不显示封面 (占位图), 隐藏条目同理
                                imageUrl = info?.takeIf { it.nsfwMode != NsfwMode.BLUR && !it.hide }?.imageUrl,
                                contentDescription = info?.title,
                                onClick = {
                                    info?.let { onIntent(SearchPageIntent.OpenSubjectDetails(index, it)) }
                                },
                                onFocused = {
                                    info?.let {
                                        heroItem = it
                                        // 邻居按网格几何算 (中间卡四方向), 见 tvGridNeighborsOf
                                        heroNeighbors = it.subjectId to tvGridNeighborsOf(
                                            index, gridColumns,
                                        ) { i ->
                                            // 本页无剧照链, 偏好恒 false
                                            if (i in 0 until items.itemCount) {
                                                items.peek(i)?.subjectId?.let(::TvHeroNeighbor)
                                            } else null
                                        }
                                    }
                                    lastFocusedCard.intValue = index
                                },
                                modifier = Modifier
                                    .tvGridFocusItem(gridFocus, index = index, itemCount = items.itemCount),
                                menu = info?.let { collectionMenuFor(it.subjectId) },
                            )
                        }
                    }
                }
                // 空结果提示 / 首屏加载指示
                if (items.itemCount == 0 && !items.loadState.hasError) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (items.isLoadingFirstPageOrRefreshing) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                stringResource(Lang.search_tv_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }

        // 底缘弱渐变遮罩: 轻压被视口截断的下一行卡片, 保证右下角提示可读
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

        // 右下角遥控键提示
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
                stringResource(Lang.search_tv_remote_hint),
                color = tvHeroSecondaryContentColor(),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** 顶部行: 搜索词 (可聚焦, 确认回输入态) + 筛选圆钮 (有筛选生效时角标小圆点). */
/**
 * Hero 信息块 (标题 + 评分/元信息行 + 简介): 换条目整块文字渐隐渐现 (contentKey=条目).
 * [heroItemProvider] 用 lambda 传入: 聚焦条目状态在本组件内部才读取, 遥控器每移一格
 * 只重组这一块, 不连带整个结果面板作用域.
 */
@Composable
private fun TvSearchHeroInfoBlock(
    heroItemProvider: () -> SubjectPreviewItemInfo?,
    summaryCache: Map<Int, String>,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = heroItemProvider(),
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(TV_HERO_TEXT_FADE_MILLIS)) togetherWith
                    fadeOut(tween(TV_HERO_TEXT_FADE_MILLIS))
        },
        contentKey = { it?.subjectId },
        label = "searchHeroInfo",
    ) { hero ->
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hero != null) {
                Text(
                    hero.title,
                    Modifier.fillMaxWidth(TV_HERO_TITLE_WIDTH_FRACTION),
                    color = tvHeroContentColor(),
                    style = MaterialTheme.typography.headlineLarge,
                    // 超长换行, 至多两行 (与探索页/追番页统一); 简介 weight 自动让出空间
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val score = hero.rating.score
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
                    // 元信息行 (开播季度 · 话数 · 类型标签, 见 SubjectPreviewItemInfo.compute)
                    Text(
                        hero.tags,
                        color = tvHeroSecondaryContentColor(),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 进程级共享表 (TvHeroMediaCache.summaryFallbacks), 邻居预取会为用户还没看到的
                // 条目写入; SnapshotStateMap 没有按键订阅粒度, 直接读会让每次邻居写入都重组这个
                // 文字块. derived 之后值没变就不往下传播 (见 TvHeroMediaCache.subjectInfos 的说明)
                val summary by remember(hero.subjectId) {
                    derivedStateOf { summaryCache[hero.subjectId].orEmpty() }
                }
                Text(
                    summary,
                    Modifier.weight(1f).fillMaxWidth(TV_HERO_SUMMARY_WIDTH_FRACTION),
                    color = tvHeroContentColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvSearchTopRow(
    keywords: String,
    hasFilters: Boolean,
    titleFocusRequester: FocusRequester,
    onEditQuery: () -> Unit,
    onOpenFilter: () -> Unit,
    onNavigateDown: () -> Boolean,
    onFallbackFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 关掉 48dp 最小交互尺寸 (TV 无触摸), 搜索词/筛选钮按真实内容高度排
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    Row(
        modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                onNavigateDown()
            } else {
                false
            }
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 搜索词: 聚焦时填充主题色圆角块. 只换字色在沉浸背景 (hero 大图) 上几乎看不出来,
        // 需要一个有面积的形状; 未聚焦时底透明, 不占视觉重量.
        run {
            val interactionSource = remember { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            val color = if (focused) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Surface(
                onClick = onEditQuery,
                modifier = Modifier.focusRequester(titleFocusRequester)
                    .onFocusChanged { if (it.isFocused) onFallbackFocused() },
                shape = RoundedCornerShape(8.dp),
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                interactionSource = interactionSource,
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        Modifier.size(20.dp),
                        tint = color,
                    )
                    Text(
                        if (keywords.isBlank()) {
                            stringResource(Lang.search_tv_results_all)
                        } else {
                            stringResource(Lang.search_tv_results_title, keywords)
                        },
                        color = color,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // 筛选圆钮
        run {
            val interactionSource = remember { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            Box {
                Surface(
                    onClick = onOpenFilter,
                    shape = CircleShape,
                    // 常态不画圆底 (与搜索词一致, 只留图标), 聚焦时才填充主题色示焦
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    interactionSource = interactionSource,
                ) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = stringResource(Lang.search_tv_filter),
                        Modifier.padding(7.dp).size(18.dp),
                        tint = if (focused) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (hasFilters) {
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
    }
}

/**
 * 已选筛选项行: 每项一个胶囊 (文字 + ✕), 点击取消该筛选并立即刷新结果.
 * 超宽时吸左滚动: 聚焦项滚到最左, 列表末端由 LazyRow 自然钳制 (露出最后一项即不再滚);
 * 全部放得下时滚不动, 表现为正常全显示 + 自由导航.
 */
@Composable
private fun TvSearchActiveFiltersRow(
    filters: List<Pair<String, SubjectSearchQuery>>,
    onRemove: (SubjectSearchQuery) -> Unit,
    entryFocusRequester: FocusRequester,
    onNavigateUp: () -> Boolean,
    onNavigateDown: () -> Boolean,
    onEmptied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focus = rememberTvFocusScope()
    var focusedChip by remember { mutableIntStateOf(-1) }
    // 移除一项后原聚焦胶囊销毁, 焦点悬空: 记住移除位置, 重组后聚焦相邻项; 删光交回上方标题
    var refocusAfterRemove by remember { mutableIntStateOf(-1) }
    LaunchedEffect(listState) {
        snapshotFlow { focusedChip }.collect { chip ->
            if (chip >= 0) runCatching { listState.animateScrollToItem(chip) }
        }
    }
    LaunchedEffect(filters.size) {
        if (refocusAfterRemove < 0) return@LaunchedEffect
        if (filters.isEmpty()) {
            refocusAfterRemove = -1
            onEmptied()
            return@LaunchedEffect
        }
        focus.request(SearchActiveFilterFocus(minOf(refocusAfterRemove, filters.lastIndex)))
        refocusAfterRemove = -1
    }
    val noBringIntoView = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = 0f
        }
    }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides noBringIntoView,
        // 关掉 M3 可点击组件的 48dp 最小交互尺寸: TV 无触摸, 胶囊按真实内容高度排, 行才紧凑
        LocalMinimumInteractiveComponentSize provides 0.dp,
    ) {
        LazyRow(
            modifier.tvFocusNavSignal(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> onNavigateUp()
                    Key.DirectionDown -> onNavigateDown()
                    else -> false
                }
            },
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(
                count = filters.size,
                key = { filters[it].first },
            ) { index ->
                val (label, removedQuery) = filters[index]
                TvSearchActiveFilterChip(
                    label = label,
                    onClick = {
                        refocusAfterRemove = index
                        onRemove(removedQuery)
                    },
                    onFocused = { focusedChip = index },
                    modifier = Modifier
                        .ifThen(index == 0) { focusRequester(entryFocusRequester) }
                        .tvFocusAnchor(focus, SearchActiveFilterFocus(index)),
                )
            }
        }
    }
}

private data class SearchActiveFilterFocus(val index: Int) : TvFocusKey

@Composable
private fun TvSearchActiveFilterChip(
    label: String,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val container = if (focused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (focused) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = CircleShape,
        color = container,
        interactionSource = interactionSource,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                color = content,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                Modifier.size(14.dp),
                tint = content,
            )
        }
    }
}

// ============================ 筛选面板 ============================

/**
 * 筛选弹窗: 排序 / 最低评分 / 各标签维度的胶囊选项. 改动先存本地, 确认才应用
 * (避免每碰一个选项就触发一次搜索), 取消/返回丢弃.
 */
@Composable
private fun TvSearchFilterDialog(
    query: SubjectSearchQuery,
    filterState: SearchFilterState,
    onConfirm: (SubjectSearchQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedTags = remember { mutableStateMapOf<String, Boolean>().apply { query.tags.orEmpty().forEach { put(it, true) } } }
    var sort by remember { mutableStateOf(query.sort) }
    var minRating by remember { mutableStateOf(query.rating?.min) }
    val firstChipModifier = Modifier.tvWindowInitialFocus()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            // 独立窗口: 遥控器全局键接回主窗口 (长按返回一步弹快捷菜单, 见 tvOverlayWindowKeys)
            Modifier.tvOverlayWindowKeys(onDismiss)
                .fillMaxWidth(TV_SEARCH_FILTER_DIALOG_WIDTH_FRACTION)
                .fillMaxHeight(TV_SEARCH_FILTER_DIALOG_HEIGHT_FRACTION),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(Lang.search_tv_filter),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                )
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                // 焦点进入某分区时该分区吸附到列表顶 (分区标题与胶囊行同属一个 item,
                // 默认 BringIntoView 只保证聚焦的胶囊可见, 上移导航时标题会留在视口外
                // 永远露不出来; 吸附后标题总是完整可见, 同详情页区块吸附的行为)
                val sectionSnap: (index: Int) -> Modifier = { index ->
                    Modifier.onFocusChanged {
                        if (it.hasFocus) {
                            scope.launch { runCatching { listState.animateScrollToItem(index) } }
                        }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).padding(top = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "sort") {
                        TvSearchFilterSection(
                            stringResource(Lang.search_tv_filter_sort),
                            modifier = sectionSnap(0),
                        ) {
                            SearchSort.entries.forEachIndexed { index, entry ->
                                TvSearchFilterChip(
                                    text = tvSearchSortLabel(entry),
                                    selected = sort == entry,
                                    onClick = { sort = entry },
                                    modifier = if (index == 0) firstChipModifier else Modifier,
                                )
                            }
                        }
                    }
                    item(key = "rating") {
                        TvSearchFilterSection(
                            stringResource(Lang.search_tv_filter_rating_min),
                            modifier = sectionSnap(1),
                        ) {
                            listOf(null, 7, 8, 9).forEach { min ->
                                TvSearchFilterChip(
                                    text = min?.let { "$it+" }
                                        ?: stringResource(Lang.search_tv_filter_any),
                                    selected = minRating == min,
                                    onClick = { minRating = min },
                                )
                            }
                        }
                    }
                    items(
                        filterState.chips.size,
                        key = { "chip-$it" },
                    ) { chipIndex ->
                        val chip = filterState.chips[chipIndex]
                        TvSearchFilterSection(
                            tvSearchFilterKindLabel(chip.kind),
                            modifier = sectionSnap(2 + chipIndex),
                        ) {
                            chip.values.forEach { value ->
                                TvSearchFilterChip(
                                    text = value,
                                    selected = selectedTags[value] == true,
                                    onClick = {
                                        selectedTags[value] = !(selectedTags[value] == true)
                                    },
                                )
                            }
                        }
                    }
                }
                // 只有"确认": 取消 = 返回键 (弹窗出口只留一个, 也不占焦点位)
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    TvSearchFilterChip(
                        text = stringResource(Lang.search_tv_filter_confirm),
                        selected = true,
                        onClick = {
                            onConfirm(
                                query.copy(
                                    tags = selectedTags.filterValues { it }.keys.toList().ifEmpty { null },
                                    sort = sort,
                                    rating = minRating?.let { RatingRange(it, null) },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSearchFilterSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun TvSearchFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val container = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = container,
        interactionSource = interactionSource,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            color = content,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun tvSearchSortLabel(sort: SearchSort): String = when (sort) {
    SearchSort.MATCH -> stringResource(Lang.exploration_search_sort_match)
    SearchSort.RANK -> stringResource(Lang.exploration_search_sort_rank)
    SearchSort.COLLECTION -> stringResource(Lang.exploration_search_sort_collection)
    SearchSort.DATE -> stringResource(Lang.exploration_search_sort_date)
}

@Composable
private fun tvSearchFilterKindLabel(kind: CanonicalTagKind?): String = when (kind) {
    CanonicalTagKind.Audience -> stringResource(Lang.exploration_search_filter_audience)
    CanonicalTagKind.Category -> stringResource(Lang.exploration_search_filter_category)
    CanonicalTagKind.Character -> stringResource(Lang.exploration_search_filter_character)
    CanonicalTagKind.Emotion -> stringResource(Lang.exploration_search_filter_emotion)
    CanonicalTagKind.Genre -> stringResource(Lang.exploration_search_filter_genre)
    CanonicalTagKind.Rating -> stringResource(Lang.exploration_search_filter_rating)
    CanonicalTagKind.Region -> stringResource(Lang.exploration_search_filter_region)
    CanonicalTagKind.Series -> stringResource(Lang.exploration_search_filter_series)
    CanonicalTagKind.Setting -> stringResource(Lang.exploration_search_filter_setting)
    CanonicalTagKind.Source -> stringResource(Lang.exploration_search_filter_source)
    CanonicalTagKind.Technology -> stringResource(Lang.exploration_search_filter_technology)
    null -> stringResource(Lang.exploration_search_filter_custom)
}

// ============================ 常量 ============================

/** 输入态/结果态之间的渐隐切换时长. */
private const val TV_SEARCH_MODE_FADE_MILLIS = 500

/** 输入态: 搜索框距页面顶部的距离. */
private val TV_SEARCH_INPUT_TOP_PAD = 48.dp

/** 输入态: 搜索框与候选列表占屏宽比例. */
private const val TV_SEARCH_INPUT_WIDTH_FRACTION = 0.55f

/** 输入态: 搜索框圆角. */
private val TV_SEARCH_INPUT_CORNER = 12.dp

/** 输入态: 补全建议的防抖时长. */
private const val TV_SEARCH_SUGGESTION_DEBOUNCE_MILLIS = 300L

/** 结果态: 内容左侧留白 (外层已让开侧边栏 48dp, 总左缘 = 48 + 此值, 与探索/追番页一致). */
private val TV_SEARCH_START_PAD = 16.dp

/** 结果态: 页面顶部留白. */
private val TV_SEARCH_TOP_PAD = 24.dp

/** 结果态: 顶部行到 Hero 信息块的间距. */
private val TV_SEARCH_TITLE_TO_HERO_GAP = 4.dp

/**
 * Hero 信息块固定高度 (标题 + 评分/元信息行 + 简介), 切换聚焦条目时网格不跳动.
 * 简介用 weight 填满剩余空间, 调大 = 简介更多行, 网格更矮
 * (标题+元信息行 ≈ 80dp, 简介每行 ≈ 20dp).
 */
private val TV_SEARCH_HERO_INFO_HEIGHT = 230.dp

/**
 * 结果态: 已选筛选项行的固定行高. 与上间距 [TV_SEARCH_FILTERS_TOP_GAP] 相加恰为简介
 * 两行行距 (2×20dp): 筛选行出现时 hero 信息块等量压缩 (简介少两行), 网格位置不动
 * 且简介换行网格对齐不破.
 */
private val TV_SEARCH_FILTERS_ROW_HEIGHT = 30.dp

/** 结果态: 已选筛选项行与顶部行的间距. */
private val TV_SEARCH_FILTERS_TOP_GAP = 10.dp

/** Hero 信息块 (简介底部) 到网格的间距. */
private val TV_SEARCH_HERO_TO_GRID_GAP = 16.dp



/** 筛选弹窗宽/高占屏比例. */
private const val TV_SEARCH_FILTER_DIALOG_WIDTH_FRACTION = 0.62f
private const val TV_SEARCH_FILTER_DIALOG_HEIGHT_FRACTION = 0.8f

/**
 * 交给共享流水线/展示层的最小描述, 见 [TvHeroMediaSpec]. 封面兜底的 NSFW 门控在这里:
 * **判据照抄卡片那边** (见本页 imageUrl 的 takeIf) —— 打了码或被隐藏的条目卡片上就不出图,
 * 兜底要是照放, 等于把用户特意藏起来的图铺满整屏.
 */
private fun SubjectPreviewItemInfo.toHeroMediaSpec(neighbors: TvHeroNeighbors = TvHeroNeighbors()) =
    TvHeroMediaSpec(
        subjectId = subjectId,
        coverUrl = takeIf { it.nsfwMode != NsfwMode.BLUR && !it.hide }?.imageUrl.orEmpty(),
        neighbors = neighbors,
    )
