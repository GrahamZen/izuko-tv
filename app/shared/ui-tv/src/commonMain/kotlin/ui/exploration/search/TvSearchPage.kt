/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.search

import me.him188.ani.app.ui.foundation.focus.TvFocusRestoreClaim
import me.him188.ani.app.ui.foundation.focus.tvSwallowKeysWhenLeaving
import androidx.compose.animation.AnimatedContent
import me.him188.ani.app.ui.foundation.tv.tvTouchTap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.schedule.AnimeSeason
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
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKeyOnFocus
import me.him188.ani.app.ui.foundation.tv.TV_GRID_TOP_BLEED
import me.him188.ani.app.ui.foundation.tv.tvGridItemTopFade
import me.him188.ani.app.ui.foundation.tv.tvGridTopBleed
import me.him188.ani.app.ui.foundation.tv.firstItemBelowTopLine
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
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
import me.him188.ani.app.ui.foundation.tv.ReportTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.rememberTvScrollHiddenProvider
import me.him188.ani.app.ui.foundation.tv.rememberTvSettledHeroProvider
import me.him188.ani.app.ui.foundation.tv.tvContentSwapAnimated
import me.him188.ani.app.ui.foundation.tv.tvScrollHiddenTextTransform
import me.him188.ani.app.ui.foundation.tv.tvHeroLineEnter
import me.him188.ani.app.ui.foundation.tv.tvHeroTextEnterBaseDelay
import me.him188.ani.app.ui.foundation.tv.tvHeroTextStaggerEnabled
import me.him188.ani.app.ui.foundation.tv.tvScrollHiddenTextSlidePx
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
import me.him188.ani.app.ui.foundation.tv.tvHeroTitleHandoff
import me.him188.ani.app.ui.foundation.tv.TvHeroRatingBadge
import me.him188.ani.app.ui.foundation.tv.TvHeroSummaryText
import me.him188.ani.app.ui.foundation.tv.tvAnimatedScroll
import me.him188.ani.app.ui.foundation.tv.TV_INSTANT_CONTENT_SWAP
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
import me.him188.ani.app.ui.lang.exploration_search_filter_season_all
import me.him188.ani.app.ui.lang.exploration_search_filter_series
import me.him188.ani.app.ui.lang.exploration_search_filter_setting
import me.him188.ani.app.ui.lang.exploration_search_filter_source
import me.him188.ani.app.ui.lang.exploration_search_filter_technology
import me.him188.ani.app.ui.lang.exploration_search_filter_year_all
import me.him188.ani.app.ui.lang.exploration_search_sort_collection
import me.him188.ani.app.ui.lang.exploration_search_sort_date
import me.him188.ani.app.ui.lang.exploration_search_sort_match
import me.him188.ani.app.ui.lang.exploration_search_sort_rank
import me.him188.ani.app.ui.lang.search_tv_empty
import me.him188.ani.app.ui.lang.search_tv_filter
import me.him188.ani.app.ui.lang.search_tv_remote_input_caption
import me.him188.ani.app.ui.lang.search_tv_filter_any
import me.him188.ani.app.ui.lang.search_tv_filter_confirm
import me.him188.ani.app.ui.lang.search_tv_clear_history
import me.him188.ani.app.ui.lang.search_tv_remote_connected
import me.him188.ani.app.ui.lang.search_tv_remote_host_changed
import me.him188.ani.app.ui.lang.search_tv_remote_panel_desc
import me.him188.ani.app.ui.lang.search_tv_remote_reset
import me.him188.ani.app.ui.lang.search_tv_remote_unavailable
import me.him188.ani.app.ui.lang.search_tv_remote_waiting
import me.him188.ani.app.ui.lang.search_tv_filter_rating_min
import me.him188.ani.app.ui.lang.search_tv_filter_season
import me.him188.ani.app.ui.lang.search_tv_filter_sort
import me.him188.ani.app.ui.lang.search_tv_filter_year
import me.him188.ani.app.ui.lang.search_tv_input_hint
import me.him188.ani.app.ui.lang.search_tv_remote_hint
import me.him188.ani.app.ui.lang.search_tv_results_all
import me.him188.ani.app.ui.lang.search_tv_results_title
import me.him188.ani.app.ui.search.LoadErrorCard
import me.him188.ani.app.ui.search.collectItemsWithLifecycle
import me.him188.ani.app.ui.search.isLoadingFirstPageOrRefreshing
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.app.ui.remote.RemoteSearchResultsSnapshot
import me.him188.ani.app.ui.remote.RemoteSearchResultsSource
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds
import me.him188.ani.app.ui.foundation.focus.TvFocusRestoreGate

/**
 * `railExitRestore` 的结果. 布尔不够用: "没送成"要分成**两种**, 否则调用方只能一律退到搜索框 ——
 * 而数据没到那一种退过去就是用户看到的"焦点先闪一下搜索框再跑到卡上" (2026-09-18).
 */
private enum class RailExitRestoreResult {
    /** 已经把焦点送到那张卡了. */
    Done,

    /** 有落点记忆, 但分页数据还没回来; 落点已自行接管 (异步等数据). 调用方**什么都别做**. */
    NotReady,

    /** 没有可恢复的落点 (输入态 / 空结果 / 没点过卡片). 调用方走自己的退路. */
    NoTarget,
}

/**
 * 返回本页时等分页数据重新到位的上限 (见 railExitRestore).
 *
 * 实测这个窗口是 211~711ms, 长尾到 1944ms (见 memory 里 issue #2 那份表), 取 3 秒留足余量;
 * 再长就当数据出了问题, 把焦点交回页面兜底.
 */
private val RESTORE_DATA_TIMEOUT = 3.seconds

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
    // 网格换行滚动登记进页面级信号: 低特效档下 hero 文字块在滚动期间不画, 见 TvScrollActivity
    ReportTvScrollActivity(gridState)
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

    // 手机扫码输入 (见 [TvRemoteControl]): 服务应用级常驻, 本页只登记在场/取走不在场时收到的提交;
    // 手机提交的词走与手动提交同一条路 (输入态/结果态都收, 结果态收到就换词重搜).
    // 服务起不来或没连局域网时 url 为 null, 二维码不画
    val remoteInputUrl by TvRemoteControl.url.collectAsState()
    val remotePhoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    val remoteKnownHost by TvRemoteControl.knownHost.collectAsState()
    val remoteHostChanged by TvRemoteControl.hostChanged.collectAsState()
    val currentApplyQuery by rememberUpdatedState(applyQuery)
    // 网页预填电视当前查询: 结果态用 VM 里的, 输入态把还没提交的输入框文字也带上 (同筛选弹窗)
    val currentQuery by rememberUpdatedState(
        if (showResults) state.query else state.query.copy(keywords = query.text.trim()),
    )
    // 季度表是 SearchViewModel 在 init 里异步拉的, 注册那一刻多半还是空的 —— 必须跟着重组更新,
    // 直接在 DisposableEffect 里捕获 state 会把空列表钉死 (网页上年份那一节就永远不出现)
    val currentYears by rememberUpdatedState(
        state.seasons.map { it.year }.distinct().sortedDescending(),
    )
    DisposableEffect(Unit) {
        TvRemoteControl.acquire()
        TvRemoteControl.currentQueryProvider = { currentQuery }
        TvRemoteControl.currentYearsProvider = { currentYears }
        onDispose {
            TvRemoteControl.currentQueryProvider = null
            TvRemoteControl.currentYearsProvider = null
            TvRemoteControl.release()
        }
    }

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

    // **必须声明在上面的恢复效应之后** (效应按声明顺序跑): 先跑的话它把 showResults 置 true, 恢复效应
    // 随后拿着组合时的旧 state 看到 "VM 没有查询" 就把页面拨回输入态 (2026-09-10 真机日志坐实).
    // 放在后面, 恢复效应在输入态直接返回, 这里再把提交搜掉
    LaunchedEffect(Unit) {
        // 本页不在场时 (书签从别的页面打开) 收到的那条提交: 服务把电视导航到本页, 进场先把它搜掉
        TvRemoteControl.takePending()?.let { currentApplyQuery(it.applyTo(currentQuery)) }
        // 网页上的筛选项套在当前查询上 (保留季度等网页没有的字段), 与筛选弹窗确认同一条路
        TvRemoteControl.submissions.collect { currentApplyQuery(it.applyTo(currentQuery)) }
    }

    // 内容区焦点入口: 从页面外进来的焦点 (导航兜底的无方向 enter) 一律先送进内容区而非侧边栏
    // (同主页外壳 TvMainScreenLayout 的做法); 侧边栏靠内容区里按左键进入
    val contentFocus = remember { FocusRequester() }
    // 结果面板的落点解析是否在途 (内层的 gridFocus.switching 提到页面级, 给下面的 onEnter 读)
    val gridSendInFlight = remember { mutableStateOf(false) }
    // 侧边栏右键/返回退出时的焦点还原: 结果面板在此注册"回上次聚焦卡片"的处理 (走带
    // 到位确认+重试的落点解析器). 未注册 (输入态) 或没有可回的卡时退回 contentFocus
    // 进组默认落点. 不还原会落到左上角搜索词文字上, 且直连 requestFocus 偶发被焦点系统
    // 静默拒绝时会看起来"按下键没反应"
    val railExitRestore = remember { mutableStateOf<(() -> RailExitRestoreResult)?>(null) }
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
        // 必须把 pageIsForeground 一并纳入: 从详情页返回时本页不一定重建 (hero 缩回走
        // TvZoomStackScene, 两页都留在组合里), pageHasFocus 在**进**详情页那一刻就已经
        // true -> false, 那时本页还不是栈顶而被下面的守卫跳过; 返回时它没有新的变化,
        // 只听它的话这条流再也不发射, 落点就没人补了 —— 表现为焦点停在页顶的搜索框
        // (2026-09-20 实测: 遥控器进详情页必现; 从手机控制台进的不走 zoom、本页正常重建,
        // 走的是进页恢复那条路, 所以反而正常).
        snapshotFlow { pageHasFocus to pageIsForeground.value }.collect { (has, foreground) ->
            if (has || !foreground) return@collect
            // 页面自己的进页恢复流程正在派落点时不插手 —— 那条路会送到同一张卡, 这里再送一次
            // 就是肉眼可见的那一下"闪" (2026-09-20 实测: 页面重建时两条路都跑, Done -> card 8
            // 连打两遍). 本页不重建时 (hero 缩回) 恢复流程根本不跑, 门是关的, 这里照常兜底.
            if (TvFocusRestoreGate.restoring) return@collect
            // **不在这里自己调 railExitRestore**: 内容区 onEnter 里那套判据才是唯一送焦入口
            // (① 落点在途放行 / ② 回上次那张卡 / ③ 默认进组). 在它之外先送一次, 会撞上档 ②
            // 自己的送焦 —— 档 ① 的判据 gridSendInFlight 要等 switching 置位, 中间有十几毫秒
            // 的空档, onEnter 正好落进去走了档 ②, 于是同一张卡被送两遍
            // (2026-09-20 实测: Done -> card 8 连打两遍, 相隔 189ms, 就是那一下"闪").
            // 这里只发起"进入内容区"的请求, 落到哪交给 onEnter 判.
            searchRestoreLogger.info { "[restore] page fallback -> content" }
            runCatching { contentFocus.requestFocus() }
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
            years = remember(state.seasons) { state.seasons.map { it.year }.distinct().sortedDescending() },
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
            .focusProperties {
                onEnter = {
                    // 进本页焦点组的落点, 三档优先级 —— **与侧边栏出组那条 (onExitFocus) 保持同一套**,
                    // 页面根原先只有最后一档, 那是"从详情页返回先看到搜索词高亮一下"的来源:
                    //
                    // contentFocus 指的是内容区那个**不可聚焦**的 focusGroup, 对它 requestFocus 就是默认
                    // 进组 —— 落到第一个可聚焦节点, 也就是顶部那块搜索词 (它"聚焦即填充主题色", 很扎眼),
                    // 然后才被落点拉到卡片上 (真机日志: 两者相隔 126ms).
                    when {
                        // ① 落点解析在途: 放行. 它送焦到目标卡的那个 requestFocus 同样要"进入"本组,
                        // 在这里改道就把它拆了 (同 TvAnchoredCardRow 那条"解析进行中一律放行")
                        gridSendInFlight.value -> {}
                        // ② 结果态: 直接回上次那张卡. **不依赖上面那个跨层 state 的时序** ——
                        // railExitRestore 是同步的, 数据在就当场送到位
                        // ② 结果态: 回上次那张卡. **必须 cancelFocusChange**, 不能放行 ——
                        // railExitRestore 里的 focusItem 是**异步**的 (悬挂到目标卡附着锚点),
                        // 放行等于让触发本次 onEnter 的那个请求继续做默认进组, 当场把焦点给了内容区
                        // 第一个可聚焦节点 (顶部搜索词块, 聚焦即填充主题色), 89ms 后异步落点才把焦点
                        // 拉到卡片上 —— 那 89ms 就是用户看到的"闪" (2026-09-18 真机日志钉死).
                        //
                        // 与档 ① 的区别: 那时进组请求本身就是 focusItem 发的, 放行才能让它落地;
                        // 这里请求刚发出、还没开始进组, 放行放的是别人的请求.
                        railExitRestore.value?.invoke() == RailExitRestoreResult.Done -> cancelFocusChange()
                        // ③ 其余 (输入态 / 没有可回的卡): 才交给默认进组
                        else -> contentFocus.requestFocus()
                    }
                }
            }
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
                        // NotReady (数据还没到) 一律**放行**: 既不改道也不退到搜索框 —— 退过去就是
                        // 那一下"闪". 放行后本组此刻若没有可聚焦目标, 整页会短暂没有焦点, 由上面那条
                        // 页面级兜底接手 (落点已在等数据, 到了自己送焦). 同 TvAnchoredCardRow 里
                        // "解析进行中就放行"的处理.
                        if (railExitRestore.value?.invoke() == RailExitRestoreResult.NoTarget) {
                            searchRestoreLogger.info { "[restore] onEnter -> search field" }
                            runCatching { inputFieldFocus.requestFocus() }
                        }
                    }
                }
                .focusGroup(),
        ) {
            val modeSwapAnimated = tvContentSwapAnimated()
            AnimatedContent(
                targetState = showResults,
                transitionSpec = {
                    // 流畅档直接换 (见 tvContentSwapAnimated): 这 500ms 里输入态与结果态两棵树同时活着
                    if (modeSwapAnimated) {
                        fadeIn(tween(TV_SEARCH_MODE_FADE_MILLIS)) togetherWith
                                fadeOut(tween(TV_SEARCH_MODE_FADE_MILLIS))
                    } else {
                        // 退场用 snap 淡出而不是 ExitTransition.None, 见 TV_INSTANT_CONTENT_SWAP
                        TV_INSTANT_CONTENT_SWAP
                    }
                },
                label = "searchMode",
            ) { results ->
                // 流畅档: 退场那一面当帧就不画 (见 TV_INSTANT_CONTENT_SWAP —— AnimatedContent 要下一帧
                // 才移除它, 没有淡出的话两面会叠一帧)
                if (!modeSwapAnimated && results != showResults) return@AnimatedContent
                // 切走的那一面淡出 500ms 期间焦点常还在它上面 (结果面板要等首页数据才送焦): 吞掉按键, 免得在看不见的候选 /
                // 历史上按确认又提交一次、在搜索框上按确认弹出输入法 (2026-09-14 审查)
                Box(Modifier.tvSwallowKeysWhenLeaving { results != showResults }, propagateMinConstraints = true) {
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
                        gridSendInFlight = gridSendInFlight,
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
                        remoteInputUrl = remoteInputUrl,
                        remotePhoneConnected = remotePhoneConnected,
                        remoteKnownHost = remoteKnownHost,
                        remoteHostChanged = remoteHostChanged,
                        onResetRemoteAddress = { TvRemoteControl.resetAddress() },
                        onRemoveHistory = { onIntent(SearchPageIntent.RemoveHistory(it)) },
                        onClearHistory = { onIntent(SearchPageIntent.ClearHistory) },
                    )
                }
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
                if (railExitRestore.value?.invoke() != RailExitRestoreResult.Done) {
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
    keywords.isNotEmpty() || !tags.isNullOrEmpty() || year != null || season != null || rating != null ||
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
    remoteInputUrl: String?,
    remotePhoneConnected: Boolean,
    remoteKnownHost: String?,
    remoteHostChanged: Boolean,
    onResetRemoteAddress: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
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
    // 搜索框内最右的「清除历史」图标 (用户 2026-09-10): 平时不可聚焦, 方向键路过搜索框碰不到它; **长按搜索框**
    // 把焦点送上去 (武装 -> 效应里 requestFocus, 同 editing 的交接方式), 再按确认才清空; 失焦即解除武装
    var clearArmed by remember { mutableStateOf(false) }
    val clearIconFocus = remember { FocusRequester() }
    LaunchedEffect(clearArmed) {
        if (clearArmed) runCatching { clearIconFocus.requestFocus() }
    }
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

    // 候选列表数据: 空文本显示搜索历史, 有文本显示防抖后的补全建议; 确认即提交.
    // 提前到布局之前算: 搜索框的「下键落点」与列表标题行都要看它
    var debounced by remember { mutableStateOf(query.text) }
    LaunchedEffect(query.text) {
        delay(TV_SEARCH_SUGGESTION_DEBOUNCE_MILLIS)
        debounced = query.text
    }
    val isHistory = debounced.isEmpty()
    val values = remember(debounced) {
        if (debounced.isEmpty()) historyPager else suggestionsPager(debounced)
    }.collectAsLazyPagingItemsWithLifecycle()
    val firstRowFocus = remember { FocusRequester() }
    // 版式 (2026-09-10 按用户草图): 左 = 搜索区 (搜索框+筛选钮 / 候选列表), 右 = 「手机扫码输入」面板
    // (标题+重置钮 / 说明 / 二维码 / 地址 / 连接状态). 左缘让出悬浮侧边栏的收起宽度; 面板定宽, 搜索区吃掉其余宽度.
    // **顶部内边距只给左侧搜索区**: 挂在整行上的话右侧那一栏也被砍掉这一截, 面板是在「屏幕高减顶距」里居中,
    // 看上去比屏幕中心低半个顶距 (用户 2026-09-10 反馈偏低)
    Row(
        modifier.fillMaxSize().imePadding()
            .onFocusChanged { paneHasFocus = it.hasFocus }
            .padding(
                start = TvNavigationRailDefaults.CollapsedWidth + TV_SEARCH_PAGE_START_GAP,
                end = TV_SEARCH_PAGE_END_PAD,
            ),
        horizontalArrangement = Arrangement.spacedBy(TV_SEARCH_PANEL_GAP),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = TV_SEARCH_INPUT_TOP_PAD)) {
            // 搜索框 (聚焦高亮描边) 与右侧筛选钮.
            // **筛选入口必须在输入态也有**: 查询本来就允许"只有标签没有关键词"
            // ([SubjectSearchQuery.hasSearchRequest]), 但本页进结果态必须先提交一次搜索, 而筛选钮
            // 原先只在结果态顶部行 —— 于是"不打字直接按标签浏览"在本页够不着 (上游手机/桌面端的
            // 筛选胶囊行一直摆在搜索页上, 不需要先搜一次).
            // 框用 weight 让出钮的宽度; height(IntrinsicSize.Min) 让钮跟着框的内容高度走, 不写死高度.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
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
                        .focusProperties {
                            canFocus = !editing
                            // 下键直落第一条候选 (显式指定, 不靠空间搜索猜)
                            if (values.itemCount > 0) down = firstRowFocus
                        }
                        .onFocusChanged { boxFocused = it.isFocused }
                        // 长按删除历史行后焦点被送回这里, 同一次按住剩下的连发/抬起不能被下面当成
                        // "确认键抬起 -> 进编辑态" (表现为一松手键盘弹出来), 见 consumeHeldConfirmKeyOnFocus
                        .consumeHeldConfirmKeyOnFocus()
                        // 确认键: 短按进编辑态, 长按把焦点送到框内的「清除历史」图标 (没有历史时长按无效).
                        // 短按仍认 KeyUp: 键盘一弹出就抢走后续按键, 在 KeyDown 上做会把同一次按键的 KeyUp
                        // 留给 IME (它可能当成一次"确定"); tvLongPressKey 把认领键的 KeyDown/KeyUp 全吞掉,
                        // focusable 的默认点击语义或 Enter 不会触发别的东西
                        .tvLongPressKey(
                            onLongPress = { if (isHistory && values.itemCount > 0) clearArmed = true },
                            onShortPress = { editing = true },
                        )
                        // 进页/回本态的初始焦点由页面级 inputFieldFocus (onEnter 改道 + 整页失焦补救) 负责.
                        // **别在这里挂 tvWindowInitialFocus**: 挂在 focusable 之后时它的 requester 与
                        // onFocusChanged 只认链上排在后面的焦点目标 (= 里面不可聚焦的输入框), 送焦永远被拒,
                        // 框获焦也不上报 —— 请求悬挂, 回输入态 2 秒后必打一条"送焦请求悬挂" (2026-09-11 日志)
                        // 触屏 (平板装了 TV 包): 点一下 = 确认键短按 (进编辑态), 长按 = 确认键长按 (武装清除历史).
                        // 武装期间点框不进编辑态: 那一下多半是冲着框里的清除图标去的 (两处都会收到同一次点按). 电视上不装
                        .tvTouchTap(
                            onTap = { if (!clearArmed) editing = true },
                            // 编辑态下不认长按: 输入框里长按是选字 / 粘贴, 而长按一旦触发会吞掉这次按住剩下的事件
                            onLongPress = if (editing) null else ({ if (isHistory && values.itemCount > 0) clearArmed = true }),
                        )
                        .focusable(),
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
                        if (isHistory && values.itemCount > 0) {
                            TvSearchInlineClearIcon(
                                armed = clearArmed,
                                focusRequester = clearIconFocus,
                                onDisarm = { clearArmed = false },
                                onClear = {
                                    clearArmed = false
                                    onClearHistory()
                                    // 图标随最后一条历史一起消失, 焦点不会自动改派, 先送回搜索框
                                    runCatching { fieldFocusRequester.requestFocus() }
                                },
                                onEscape = { runCatching { fieldFocusRequester.requestFocus() } },
                                label = stringResource(Lang.search_tv_clear_history),
                            )
                        }
                    }
                }
                // 筛选钮: 纯图标方钮 (用户 2026-09-10: 不要文字, 聚焦时文字浮在钮下方)
                TvSearchIconButton(
                    icon = Icons.Rounded.Tune,
                    label = stringResource(Lang.search_tv_filter),
                    onClick = onOpenFilter,
                    badge = hasFilters,
                    modifier = Modifier.fillMaxHeight(),
                )
            }

            LazyColumn(
                Modifier.fillMaxWidth().padding(top = TV_SEARCH_LIST_TOP_GAP).weight(1f),
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
                        // 只有历史能删 (补全建议不是本地数据); 删掉的那行若正持焦, 焦点回搜索框
                        onRemove = if (isHistory) ({ onRemoveHistory(text) }) else null,
                        onRemovedWhileFocused = { runCatching { fieldFocusRequester.requestFocus() } },
                        modifier = if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier,
                    )
                }
            }
        }
        // 面板在右侧那一栏里整体垂直居中, 不与顶部的搜索框争第一视觉焦点 (搜索才是主操作)
        Box(
            Modifier.width(TV_SEARCH_REMOTE_PANEL_WIDTH).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            TvSearchRemotePanel(
                url = remoteInputUrl,
                phoneConnected = remotePhoneConnected,
                knownHost = remoteKnownHost,
                hostChanged = remoteHostChanged,
                onReset = onResetRemoteAddress,
            )
        }
    }
}

/**
 * 「手机扫码输入」面板: 标题行 (右端「重置地址」图标钮) / 一句说明 / 二维码 / 地址文字 / 状态行.
 * 地址固定 (见 TvRemoteControl), 印出来给手机加书签用; IP 变了 ([hostChanged]) 时地址与状态行标红,
 * 提示重新扫码. 没连局域网 (url == null) 时二维码位置画一块占位, 状态行说明原因.
 * 面板里唯一可聚焦的是重置钮: 从搜索框/候选行按右键过来, 返回键按输入态的规矩回搜索框.
 *
 * 位置: 在右侧那一栏里**整体垂直居中** (用户 2026-09-10) —— 与顶部的搜索框顶对齐时两个大块争第一视觉焦点,
 * 而搜索才是主操作.
 */
@Composable
private fun TvSearchRemotePanel(
    url: String?,
    phoneConnected: Boolean,
    knownHost: String?,
    hostChanged: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(TV_SEARCH_REMOTE_PANEL_PAD),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Lang.search_tv_remote_input_caption),
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                TvSearchPanelIconButton(
                    icon = Icons.Rounded.Refresh,
                    label = stringResource(Lang.search_tv_remote_reset),
                    onClick = onReset,
                )
            }
            Text(
                stringResource(Lang.search_tv_remote_panel_desc),
                // 多让一段: 标题行右端重置钮聚焦时的浮动标签落在这块区域, 不留空会叠在说明文字上
                Modifier.fillMaxWidth().padding(top = TV_SEARCH_PANEL_DESC_TOP_GAP),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (url != null) {
                QrCodeImage(url, Modifier.size(TV_SEARCH_QR_SIZE), quietZone = TV_SEARCH_QR_QUIET_ZONE)
            } else {
                Box(
                    Modifier.size(TV_SEARCH_QR_SIZE + TV_SEARCH_QR_QUIET_ZONE * 2)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
                )
            }
            if (url != null) {
                // 地址印出来: 手机加书签 / 手敲用. IP 变了标红 —— 手机上存的是旧地址, 这个新地址它还不知道
                Text(
                    url,
                    Modifier.fillMaxWidth(),
                    color = if (hostChanged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            val statusText = when {
                url == null -> stringResource(Lang.search_tv_remote_unavailable)
                hostChanged -> stringResource(Lang.search_tv_remote_host_changed, knownHost.orEmpty())
                phoneConnected -> stringResource(Lang.search_tv_remote_connected)
                else -> stringResource(Lang.search_tv_remote_waiting)
            }
            Text(
                statusText,
                Modifier.fillMaxWidth(),
                color = when {
                    url != null && hostChanged -> MaterialTheme.colorScheme.error
                    url != null && phoneConnected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 面板标题行右端的小图标钮 (重置地址): 未聚焦只有图标, 聚焦 = 主题色实底 (示焦约定见 FocusHighlight),
 * 文字标签只在聚焦时浮现在钮下方 (同搜索行的 [TvSearchIconButton]).
 */
@Composable
private fun TvSearchPanelIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var heightPx by remember { mutableIntStateOf(0) }
    val labelGapPx = with(LocalDensity.current) { TV_SEARCH_ROW_BUTTON_LABEL_GAP.roundToPx() }
    Box(modifier.onSizeChanged { heightPx = it.height }) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(TV_SEARCH_PANEL_BUTTON_SIZE),
            shape = CircleShape,
            color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            interactionSource = interactionSource,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, Modifier.size(20.dp))
            }
        }
        if (focused) {
            Text(
                label,
                Modifier.align(Alignment.TopCenter)
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints())
                        layout(0, 0) { placeable.place(-placeable.width / 2, heightPx + labelGapPx) }
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 搜索框内最右的「清除历史」图标: 没有底色块, 只有图标本身 (用户 2026-09-10). 平时 `canFocus = false`,
 * 方向键导航碰不到; 只有搜索框长按把它武装起来才可聚焦并接住焦点. 聚焦 = 图标变主题色 + 文字标签浮在
 * 下方; 确认键清空历史; 方向键 / 失焦 = 退回搜索框并解除武装.
 *
 * 长按武装那一刻用户的手还按着, 剩下的连发与 KeyUp 会随着焦点一起落到这里 —— [consumeHeldConfirmKeyOnFocus]
 * 把它们吞掉, 见到新的一次按下才算「按了清除」.
 */
@Composable
private fun TvSearchInlineClearIcon(
    armed: Boolean,
    focusRequester: FocusRequester,
    onDisarm: () -> Unit,
    onClear: () -> Unit,
    onEscape: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val iconSizePx = with(LocalDensity.current) { TV_SEARCH_INLINE_ICON_SIZE.roundToPx() }
    val labelOffsetPx = with(LocalDensity.current) { TV_SEARCH_INLINE_ICON_LABEL_OFFSET.roundToPx() }
    Box(
        modifier
            .focusRequester(focusRequester)
            .focusProperties { canFocus = armed }
            .onFocusChanged { if (!it.isFocused && armed) onDisarm() }
            .consumeHeldConfirmKeyOnFocus()
            .onPreviewKeyEvent { event ->
                when {
                    event.key in TV_CONFIRM_KEYS -> {
                        if (event.type == KeyEventType.KeyUp) onClear()
                        true
                    }
                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                            event.key == Key.DirectionUp || event.key == Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) onEscape()
                        true
                    }
                    else -> false
                }
            }
            // 触屏: 武装后点图标 = 确认键 (清空历史). 电视上不装
            .tvTouchTap(onTap = { if (armed) onClear() })
            .focusable(interactionSource = interactionSource),
    ) {
        // 聚焦效果: 图标背后一个主题色圆底 + 图标反白 (同面板里的重置钮). 只换图标颜色的话在深色界面上
        // 看不出来是"选中了". 用 drawBehind 画而不是加个底块: 圆比图标大, 塞进布局会把搜索框撑高,
        // 画出去的那一圈落在搜索框自己的内边距里, 不会被裁
        val focusRing = MaterialTheme.colorScheme.primary
        Icon(
            Icons.Rounded.DeleteSweep,
            contentDescription = label,
            Modifier.size(TV_SEARCH_INLINE_ICON_SIZE)
                .drawBehind {
                    if (focused) drawCircle(focusRing, radius = TV_SEARCH_INLINE_ICON_FOCUS_RADIUS.toPx())
                },
            tint = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (focused) {
            Text(
                label,
                Modifier.align(Alignment.TopCenter)
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints())
                        // 浮在搜索框之下: 图标高 + 框的下内边距 + 间距, 不参与布局
                        layout(0, 0) { placeable.place(-placeable.width / 2, iconSizePx + labelOffsetPx) }
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 搜索框那一行里的纯图标方钮 (筛选 / 清空历史): 与搜索框同底色同圆角, 聚焦时主题色描边, **文字标签只在
 * 聚焦时浮现在钮的正下方**, 用 0 高度的 layout 放置, 不占布局、不把候选列表往下挤 (用户 2026-09-10: 行里
 * 不要文字). [badge] 为 true 时右上角一个小圆点 (筛选钮「已有筛选」的提示, 同结果态顶部行).
 */
@Composable
private fun TvSearchIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var heightPx by remember { mutableIntStateOf(0) }
    val labelGapPx = with(LocalDensity.current) { TV_SEARCH_ROW_BUTTON_LABEL_GAP.roundToPx() }
    Box(modifier.onSizeChanged { heightPx = it.height }) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxHeight()
                .width(TV_SEARCH_ROW_BUTTON_WIDTH)
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        if (focused) {
            Text(
                label,
                Modifier.align(Alignment.TopCenter)
                    // 自身占 0×0, 文字放到钮底边之下 labelGap 处并按钮的中线居中 —— 浮在下面, 不参与行布局
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints())
                        layout(0, 0) { placeable.place(-placeable.width / 2, heightPx + labelGapPx) }
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 候选行. [onRemove] 非 null (历史记录) 时长按确认键**直接删除** —— 手机端是行尾的叉号, 遥控器上没有
 * 第二个可聚焦的位置, 长按是唯一不占版面的入口. 不弹菜单也不二次确认 (用户 2026-09-10 要求; 手机端同样
 * 没有确认): 搜索历史丢了代价为零.
 *
 * @param onRemovedWhileFocused 本行持焦时被移出组合 (删除生效后 paging 重刷) 时回调: 焦点跟着节点
 *   一起消失, 不会自动改派, 得有人把它送回搜索框.
 */
@Composable
private fun TvSearchSuggestionRow(
    text: String,
    isHistory: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    onRemovedWhileFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val currentFocused by rememberUpdatedState(focused)
    val currentOnRemovedWhileFocused by rememberUpdatedState(onRemovedWhileFocused)
    DisposableEffect(Unit) {
        onDispose { if (currentFocused) currentOnRemovedWhileFocused() }
    }
    Box(modifier.fillMaxWidth()) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
            .then(
                // 有删除入口才接管确认键 (按住到阈值当场删除, 短按仍是提交); 补全建议交回 Surface 自己的点击
                if (onRemove == null) Modifier
                else Modifier.tvLongPressKey(onLongPress = onRemove, onShortPress = onClick),
            ),
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
    railExitRestore: MutableState<(() -> RailExitRestoreResult)?>,
    /** 见页面级 onEnter: 本面板的落点解析在途时, 页面根不要改道抢焦点. */
    gridSendInFlight: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val bangumiSummaryService = remember { GlobalKoin.get<BangumiSummaryService>() }
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val setCollectionTypeUseCase = remember { GlobalKoin.get<SetSubjectCollectionTypeOrDeleteUseCase>() }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val items = state.searchState.collectItemsWithLifecycle()
    // **长生命周期的闭包必须读这个而不是 items 本身**: 提交一次搜索就会换一个新的 pager,
    // collectItemsWithLifecycle 跟着产出新的 LazyPagingItems 实例. 而 LaunchedEffect(Unit) /
    // DisposableEffect(Unit) 里的闭包捕获的是**首次组合那一个** —— 它永远停在"还没搜索"的空列表上,
    // 于是"等数据到位"的那几处永远等不到 (2026-09-20 实测: itemCount 始终 0, 而页面上明明有卡片).
    val currentItems by rememberUpdatedState(items)

    // Web 控制台的「结果」标签读这份列表 (见 RemoteSearchResults): 快照由 HTTP 线程在需要时读, 不进本面板的
    // 组合; 手机上「加载更多」= 访问最后一项, 分页库照常追加下一页 (出错时重试), 投到本面板的主线程 scope
    val remoteQuery by rememberUpdatedState(state.query)
    DisposableEffect(items) {
        val source = object : RemoteSearchResultsSource {
            override fun snapshot(): RemoteSearchResultsSnapshot {
                val loadState = items.loadState
                val error = loadState.refresh as? LoadState.Error ?: loadState.append as? LoadState.Error
                return RemoteSearchResultsSnapshot(
                    query = remoteQuery,
                    items = items.itemSnapshotList.items,
                    refreshing = loadState.refresh is LoadState.Loading,
                    appending = loadState.append is LoadState.Loading,
                    endReached = loadState.append.endOfPaginationReached,
                    error = error?.error?.let { it.message ?: it::class.simpleName },
                )
            }

            override fun loadMore() {
                scope.launch {
                    if (items.loadState.append is LoadState.Error) {
                        items.retry()
                    } else if (items.itemCount > 0) {
                        items[items.itemCount - 1]
                    }
                }
            }
        }
        TvRemoteControl.registerSearchResults(source)
        onDispose { TvRemoteControl.unregisterSearchResults(source) }
    }

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
    // hero **文字**的展示目标, 与背景图分开: 低特效档下网格滚动 (换行) 期间为 null, 停稳后才是
    // 最后聚焦那张; 完整档透传. 机理与实测见 TvScrollActivity
    val heroTextDisplay = rememberTvScrollHiddenProvider { heroItem }

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

    // 恢复落点期间让全局兜底让位, 否则它会抢在前面把焦点塞给页顶的搜索框, 等本页的落点派出去
    // 又被拉到卡片上 —— 看着就是"焦点先闪一下搜索框再跑到卡上" (用户 2026-09-18).
    // 覆盖的两段与上面 backToFirstCard 的判据同源 (落位之后那段由兜底自己的 hasFocusInside 管).
    // 返回本页时落点在等分页数据 (见下面 railExitRestore 的 await 分支)
    var awaitingRestoreData by remember { mutableStateOf(false) }
    TvFocusRestoreClaim(active = !restoreSettled || gridFocus.switching || awaitingRestoreData)
    DisposableEffect(gridFocus.switching, awaitingRestoreData) {
        gridSendInFlight.value = gridFocus.switching || awaitingRestoreData
        onDispose { gridSendInFlight.value = false }
    }

    // 侧边栏右键退出 → 回上次聚焦的卡片 (与进页恢复一致), 而不是空间焦点搜索/进组默认
    // 落到左上角搜索词文字上. 走上面的落点解析器: 聚焦到位确认 + 重试, 直连 requestFocus
    // 偶发被焦点系统静默拒绝时不至于永久卡死
    val restoreScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        railExitRestore.value = restore@{
            val last = lastFocusedCard.intValue
            if (last < 0) {
                searchRestoreLogger.info { "[restore] NoTarget (lastFocusedCard=$last)" }
                return@restore RailExitRestoreResult.NoTarget
            }
            val count = currentItems.itemCount
            if (count > 0) {
                searchRestoreLogger.info { "[restore] Done -> card $last (itemCount=$count)" }
                gridFocus.focusItem(minOf(last, count - 1))
                return@restore RailExitRestoreResult.Done
            }
            // 已经在等了就别再起一个 (三个调用点可能先后问到)
            if (awaitingRestoreData) return@restore RailExitRestoreResult.NotReady
            // **数据还没到时接管并等它**, 不是认输.
            //
            // 调用方 (页面级的"没焦点就找个落点"兜底) 拿到 false 会把焦点退给顶部搜索框, 而几百毫秒后
            // 数据到了落点又把焦点拉到卡片上 —— 看着就是"焦点先闪一下搜索框再跑到卡上", 中间按的方向键
            // 还按搜索框的拓扑走 (用户 2026-09-18). 从详情页返回时必然撞上: 列表页在缩回栈里是**常驻组合**,
            // 不会重新跑进页恢复那条路, 而 LazyPagingItems 仍要重新 present 一轮.
            //
            // 等不到就交回兜底 (焦点宁可落在搜索框, 也不能整页没有焦点 —— 那是方向键全失效).
            awaitingRestoreData = true
            searchRestoreLogger.info { "[restore] NotReady -> awaiting data for card $last" }
            restoreScope.launch {
                try {
                    val ready = withTimeoutOrNull(RESTORE_DATA_TIMEOUT) {
                        snapshotFlow { currentItems.itemCount }.first { it > 0 }
                    }
                    if (ready == null) {
                        searchRestoreLogger.info { "[restore] await timed out, handing focus back to fallback" }
                    } else {
                        searchRestoreLogger.info { "[restore] data arrived (itemCount=$ready) -> card $last" }
                        gridFocus.focusItem(minOf(last, ready - 1))
                    }
                } finally {
                    awaitingRestoreData = false
                }
            }
            RailExitRestoreResult.NotReady
        }
        onDispose { railExitRestore.value = null }
    }

    // 初始焦点: 返回本页恢复到此前聚焦的卡片, 新搜索聚焦第一张; 结果没到先等 (加载失败聚焦错误卡)
    LaunchedEffect(Unit) {
        val target = if (restoreCardIndex >= 0) restoreCardIndex else 0
        onRestoreConsumed()
        // Paging 数据/错误本身就是事件源; 首个终态到达后一次性分派落点.
        // 有上限: 数据迟迟不来时放弃恢复并收尾, 否则 restoreSettled 永远是 false,
        // TvFocusRestoreClaim 把全局兜底永久挡在门外, 方向键彻底失效 (2026-09-20 实测到 6 秒没到).
        val dataReady = withTimeoutOrNull(RESTORE_DATA_TIMEOUT) {
            snapshotFlow { currentItems.itemCount to currentItems.loadState.hasError }
                .first { (count, hasError) -> count > 0 || hasError }
            true
        } ?: false
        when {
            !dataReady -> {} // 超时: 不派落点, 交给全局兜底
            currentItems.itemCount > 0 -> gridFocus.focusItem(target)
            currentItems.loadState.hasError -> runCatching { errorCardFocusRequester.requestFocus() }
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
        // 吸顶线以下那一张 (出血区里正在淡出的上一行不算, 见 firstItemBelowTopLine)
        val firstVisible = gridState.firstItemBelowTopLine()?.index
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
            // 搜索结果没有"下一集"的概念, 只用整部 backdrop; 隐藏条目的封面兜底/垫底门控
            // 在 toHeroMediaSpec 里 (判据照抄卡片: 卡片不出图, 全屏更不能出)
            backdropUrl = { heroPipeline.backdropUrl(heroDisplay()?.toHeroMediaSpec()) },
            // NSFW 模糊模式: 同卡片降采样打码 (TMDB 图与封面兜底都算); 详情页不打码
            obscure = { heroDisplay()?.nsfwMode == NsfwMode.BLUR },
            // 本页是独立页面, 图层正下方是页面根 Box 自铺的 colorScheme.background
            fadeColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.align(Alignment.TopEnd),
            underlayUrl = { heroPipeline.underlayUrl(heroDisplay()?.toHeroMediaSpec()) },
            // 这张图解码完顺手算主题色, 点进详情页第一帧就是动态色 (详情页取的也是这张)
            themeSeedSubjectId = { heroDisplay()?.subjectId },
            // 按下即压暗: 焦点一换到新条目就暗, 等展示目标跟上再放开 (本页无剧照, 不升档)
            dimTrigger = { heroItem?.subjectId },
            dimming = { heroItem?.subjectId != heroDisplay()?.subjectId },
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
                heroItemProvider = heroTextDisplay,
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
                    // 向上出血: 离场的行越过网格顶边继续上移、边移边淡, 同探索页 (见 tvGridTopBleed)
                    .tvGridTopBleed()
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
                    // maxHeight 含向上出血, 先减掉
                    (this@BoxWithConstraints.maxHeight - TV_GRID_TOP_BLEED - cardHeight).coerceAtLeast(24.dp)
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
                val animatedScroll = tvAnimatedScroll()
                LaunchedEffect(gridState, animatedScroll) {
                    // collectLatest + TvScrollAnimator: 连发按键取消进行中的滚动并继承速度,
                    // 列表连续流动 (原 collect 要等上一格动画跑完才响应下一个目标)
                    val scrollAnimator = TvScrollAnimator(animated = animatedScroll)
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
                        contentPadding = PaddingValues(top = TV_GRID_TOP_BLEED, end = TV_PAGE_END_PAD, bottom = gridBottomPad),
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
                                // 隐藏条目不显示封面 (占位图); NSFW 模糊模式降采样打码
                                imageUrl = info?.takeIf { !it.hide }?.imageUrl,
                                obscureImage = info?.nsfwMode == NsfwMode.BLUR,
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
                                    // 越过吸顶线的行边上移边淡出 (同探索页, 见 tvGridItemTopFade)
                                    .tvGridItemTopFade(gridState, index, TV_PAGE_CARD_SPACING)
                                    .tvGridFocusItem(gridFocus, index = index, itemCount = items.itemCount),
                                menu = info?.let { collectionMenuFor(it.subjectId) },
                            )
                        }
                    }
                }
                // 空结果提示 / 首屏加载指示
                if (items.itemCount == 0 && !items.loadState.hasError) {
                    // 补回向上出血 (见 tvGridTopBleed), 否则提示居中的是含出血的整块, 看上去偏上
                    Box(Modifier.fillMaxSize().padding(top = TV_GRID_TOP_BLEED), contentAlignment = Alignment.Center) {
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
    val slidePx = tvScrollHiddenTextSlidePx()
    // 分行错落进场 (完整档): 容器不整块进场, 各行自己带延迟进, 见 tvHeroLineEnter
    val stagger = tvHeroTextStaggerEnabled()
    // 流畅档直接换字, 不淡入淡出 (见 tvContentSwapAnimated)
    val swapAnimated = tvContentSwapAnimated()
    // 各行进场的基准起点在 transitionSpec 里算好 (那里才知道 initialState), 内容首次组合时读走 (理由见探索页)
    val enterPlan = remember { IntArray(1) }
    val heroTextTarget = heroItemProvider()
    AnimatedContent(
        targetState = heroTextTarget,
        modifier = modifier,
        transitionSpec = {
            enterPlan[0] = tvHeroTextEnterBaseDelay(initialState != null)
            tvScrollHiddenTextTransform(
                slidePx, sequential = initialState != null, childrenEnter = stagger,
                hiding = targetState == null, animated = swapAnimated,
            )
        },
        contentKey = { it?.subjectId },
        label = "searchHeroInfo",
    ) { hero ->
        // **流畅档: 退场那一份当帧就不画**. `AnimatedContent` 要等 transition 收敛才移除退场项,
        // 那是下一帧, 而流畅档没有淡出把它变透明 (snap 也不行 —— Transition 在 targetState 变化
        // 的那次组合里返回的还是旧值), 于是整整一帧新旧两份都画着 —— 就是"换 hero 时文字重影"
        // (2026-09-19 逐帧取证). 卡片态看不到是因为那条路是 A → null → B, 两份从不同时在.
        if (!swapAnimated && hero?.subjectId != heroTextTarget?.subjectId) return@AnimatedContent
        val lineBase = remember { enterPlan[0] }
        fun Modifier.line(index: Int) = tvHeroLineEnter(this@AnimatedContent, stagger, lineBase, index, slidePx)
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hero != null) {
                Text(
                    hero.title,
                    Modifier.line(0).fillMaxWidth(TV_HERO_TITLE_WIDTH_FRACTION)
                        // 放大转场的标题接线, 见该 modifier (本页标题不跑马灯)
                        .tvHeroTitleHandoff(hero.subjectId, hero.title),
                    color = tvHeroContentColor(),
                    style = MaterialTheme.typography.headlineLarge,
                    // 超长换行, 至多两行 (与探索页/追番页统一); 简介 weight 自动让出空间
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.line(1),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val score = hero.rating.score
                    TvHeroRatingBadge(score)
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
                TvHeroSummaryText(
                    summary,
                    Modifier.line(2).weight(1f).fillMaxWidth(TV_HERO_SUMMARY_WIDTH_FRACTION),
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
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFallbackFocused()
                        }
                    },
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
    /** 可选年份 (上游的番剧索引季度表, 见 SearchPageState.seasons); 空表示这一节不显示. */
    years: List<Int>,
    onConfirm: (SubjectSearchQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedTags = remember { mutableStateMapOf<String, Boolean>().apply { query.tags.orEmpty().forEach { put(it, true) } } }
    var sort by remember { mutableStateOf(query.sort) }
    var minRating by remember { mutableStateOf(query.rating?.min) }
    // 年份 / 季度: 上游在手机端做成了两个下拉 (SearchFilter.kt 的 YearFilterChip / SeasonFilterChip),
    // 电视这边按本弹窗一贯的做法摊成胶囊分区 —— 下拉在遥控器上要多一层焦点, 而这里本来就是一屏可选项
    var year by remember { mutableStateOf(query.year) }
    var season by remember { mutableStateOf(query.season) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 必须在 Dialog 内容里建: 窗口初始焦点的私有 scope 按 LocalWindowInfo 判窗口焦点来重试,
        // 建在外面读到的是主窗口 (见 ViewAllGridDialog 同一处注释)
        val firstChipModifier = Modifier.tvWindowInitialFocus()
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
                // 焦点进入某分区时该分区吸附到列表顶: 分区标题与胶囊行同属一个 item, 默认 BringIntoView 只保证聚焦的
                // 胶囊可见, 上移导航时标题会留在视口外永远露不出来; 吸附后标题总是完整可见 (同详情页区块吸附的行为).
                // 吸附本身有三条约束, 都是踩出来的:
                //
                // 1. **只保留最后一次**: 连按向下时上一次的 animateScrollToItem 还没跑完又起一个, 两个动画抢同一个
                //    滚动位置, 画面往回跳一下;
                // 2. **已经贴在顶上就不动**: 省掉一次没必要的动画;
                // 3. **比视口还高的分区不吸附**: 年份那一节胶囊多, FlowRow 折成好几行, 整节高过视口 —— 这时"把它的顶
                //    拉到视口顶"与 Compose 自己的 bringIntoView (把焦点滚进视野) 方向相反, 你往下走到它的后几行,
                //    吸附又把画面拽回这一节的开头, 就是"往下滚画面却跑上去". 快按必现、慢按看不出来, 因为慢按时
                //    上一个动画已经跑完 (用户 2026-09-16). 这种分区交给默认的 bringIntoView 就好.
                var snapJob by remember { mutableStateOf<Job?>(null) }
                val sectionSnap: (index: Int) -> Modifier = { index ->
                    Modifier.onFocusChanged {
                        if (it.hasFocus) {
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { v -> v.index == index }
                            val viewport = listState.layoutInfo.viewportSize.height
                            val tooTall = info != null && viewport > 0 && info.size > viewport
                            if (!tooTall && (info == null || info.offset != 0)) {
                                snapJob?.cancel()
                                snapJob = scope.launch { runCatching { listState.animateScrollToItem(index) } }
                            }
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
                    if (years.isNotEmpty()) {
                        item(key = "year") {
                            TvSearchFilterSection(
                                stringResource(Lang.search_tv_filter_year),
                                modifier = sectionSnap(2),
                            ) {
                                TvSearchFilterChip(
                                    text = stringResource(Lang.exploration_search_filter_year_all),
                                    selected = year == null,
                                    // 清年份连带清季度: 季度从属于年份 (同上游 withYearFilter)
                                    onClick = { year = null; season = null },
                                )
                                years.forEach { y ->
                                    TvSearchFilterChip(
                                        text = y.toString(),
                                        selected = year == y,
                                        onClick = { if (year != y) season = null; year = y },
                                    )
                                }
                            }
                        }
                        // 季度只在选了年份之后才出现: 没有年份时它整节都是无效选项, 在遥控器上是白占焦点位
                        if (year != null) {
                            item(key = "season") {
                                TvSearchFilterSection(
                                    stringResource(Lang.search_tv_filter_season),
                                    modifier = sectionSnap(3),
                                ) {
                                    TvSearchFilterChip(
                                        text = stringResource(Lang.exploration_search_filter_season_all),
                                        selected = season == null,
                                        onClick = { season = null },
                                    )
                                    AnimeSeason.entries.forEach { s ->
                                        TvSearchFilterChip(
                                            text = "Q${s.quarterNumber}",
                                            selected = season == s,
                                            onClick = { season = s },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val tagSectionBase = when {
                        years.isEmpty() -> 2
                        year == null -> 3
                        else -> 4
                    }
                    items(
                        filterState.chips.size,
                        key = { "chip-$it" },
                    ) { chipIndex ->
                        val chip = filterState.chips[chipIndex]
                        TvSearchFilterSection(
                            tvSearchFilterKindLabel(chip.kind),
                            modifier = sectionSnap(tagSectionBase + chipIndex),
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
                                    year = year,
                                    season = year?.let { season },
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

/** 输入态: 搜索框圆角. */
private val TV_SEARCH_INPUT_CORNER = 12.dp

/** 输入态: 补全建议的防抖时长. */
private const val TV_SEARCH_SUGGESTION_DEBOUNCE_MILLIS = 300L

/** 输入态: 搜索区左缘与悬浮侧边栏收起宽度之间的间距. */
private val TV_SEARCH_PAGE_START_GAP = 24.dp

/** 输入态: 右侧面板到屏幕右缘. */
private val TV_SEARCH_PAGE_END_PAD = 48.dp

/** 输入态: 搜索区与右侧面板的间距. */
private val TV_SEARCH_PANEL_GAP = 32.dp

/** 输入态: 搜索框那一行里图标方钮的宽度 (高度跟随行). */
private val TV_SEARCH_ROW_BUTTON_WIDTH = 56.dp

/** 输入态: 搜索框内「清除历史」图标的边长. */
private val TV_SEARCH_INLINE_ICON_SIZE = 22.dp

/** 输入态: 「清除历史」图标聚焦时背后圆底的半径 (直径 36dp, 落在搜索框 14dp 的内边距之内). */
private val TV_SEARCH_INLINE_ICON_FOCUS_RADIUS = 18.dp

/** 输入态: 「清除历史」图标聚焦时浮动标签相对图标底边的下移量 (框的下内边距 14dp + 6dp 间距). */
private val TV_SEARCH_INLINE_ICON_LABEL_OFFSET = 20.dp

/** 输入态: 图标钮聚焦时浮现的文字标签与钮底边的间距. */
private val TV_SEARCH_ROW_BUTTON_LABEL_GAP = 6.dp

/** 输入态: 「手机扫码输入」面板宽度 (定宽, 搜索区吃掉其余宽度). */
private val TV_SEARCH_REMOTE_PANEL_WIDTH = 300.dp

/** 输入态: 候选列表与搜索框行的间距. */
private val TV_SEARCH_LIST_TOP_GAP = 12.dp

/** 输入态: 面板标题行右端图标钮 (重置地址) 的直径. */
private val TV_SEARCH_PANEL_BUTTON_SIZE = 36.dp

/** 输入态: 面板说明文字额外下移, 给标题行重置钮的浮动标签让位. */
private val TV_SEARCH_PANEL_DESC_TOP_GAP = 12.dp

/** 输入态: 面板内边距. */
private val TV_SEARCH_REMOTE_PANEL_PAD = 20.dp

/** 「手机扫码输入」二维码本体边长 (不含留白). 36 字符的地址是 29 模块, 180dp 下一模块 6.2dp, 沙发距离够扫. */
private val TV_SEARCH_QR_SIZE = 180.dp

/** 二维码四周留白: 规范要求 ≥ 4 模块, 按上面的模块尺寸算 24dp. */
private val TV_SEARCH_QR_QUIET_ZONE = 24.dp

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
// 0.62 -> 0.78: 年份那一节胶囊多, 窄弹窗里要折成好几行 (整节比视口还高, 见 sectionSnap 那里的说明)
private const val TV_SEARCH_FILTER_DIALOG_WIDTH_FRACTION = 0.78f
private const val TV_SEARCH_FILTER_DIALOG_HEIGHT_FRACTION = 0.88f

/**
 * 交给共享流水线/展示层的最小描述, 见 [TvHeroMediaSpec]. 封面兜底的隐藏门控在这里:
 * **判据照抄卡片那边** (见本页 imageUrl 的 takeIf) —— 被隐藏的条目卡片上就不出图,
 * 兜底要是照放, 等于把用户特意藏起来的图铺满整屏. NSFW 模糊模式不拦, 由背景层 obscure 打码.
 */
private fun SubjectPreviewItemInfo.toHeroMediaSpec(neighbors: TvHeroNeighbors = TvHeroNeighbors()) =
    TvHeroMediaSpec(
        subjectId = subjectId,
        coverUrl = takeIf { !it.hide }?.imageUrl.orEmpty(),
        neighbors = neighbors,
    )
