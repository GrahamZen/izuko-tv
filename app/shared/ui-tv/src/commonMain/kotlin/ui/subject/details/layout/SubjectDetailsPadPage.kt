/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.repository.subject.EpisodesFetchState
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.episode.SetEpisodeCollectionTypeRequest
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.AniImageLoadSuccess
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.TvPageRefreshHandler
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.theme.glassContainerColor
import me.him188.ani.app.ui.foundation.tv.SyncTvTmdbSubjectText
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_WALL_BACKDROP_CROSSFADE_MILLIS
import me.him188.ani.app.ui.foundation.tv.tvTitle
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.lang.subject_details_load_retrying
import me.him188.ani.app.ui.lang.subject_details_rating_summary
import me.him188.ani.app.ui.lang.subject_details_login_to_collect
import me.him188.ani.app.ui.lang.subject_episode_expand
import me.him188.ani.app.ui.lang.pad_subject_details_more_tags
import me.him188.ani.app.ui.lang.subject_details_stat_collected
import me.him188.ani.app.ui.lang.subject_details_stat_watching
import me.him188.ani.app.ui.lang.subject_details_stat_wish
import me.him188.ani.app.ui.lang.subject_details_tv_feedback
import me.him188.ani.app.ui.rating.FiveRatingStars
import me.him188.ani.app.ui.rating.renderScore
import me.him188.ani.app.ui.subject.AiringLabel
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.app.ui.subject.collection.components.EditableSubjectCollectionTypeDialogsHost
import me.him188.ani.app.ui.subject.collection.components.EditableSubjectCollectionTypeState
import me.him188.ani.app.ui.subject.collection.components.SubjectCollectionActions
import me.him188.ani.app.ui.subject.collection.components.SubjectCollectionActionsForCollect
import me.him188.ani.app.ui.subject.collection.components.SubjectCollectionTypeEditActions
import me.him188.ani.app.ui.subject.collection.components.renderCollectionTypeAsCurrent
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadAttempt
import me.him188.ani.app.ui.subject.details.components.COVER_WIDTH_TO_HEIGHT_RATIO
import me.him188.ani.app.ui.subject.details.components.RatingHistogram
import me.him188.ani.app.ui.subject.details.sections.groupThousands
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsState
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsUiState
import me.him188.ani.app.ui.subject.details.state.rememberTmdbAiringLabelState
import me.him188.ani.app.ui.subject.details.state.rememberTmdbSubjectProgressState
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem
import me.him188.ani.app.ui.subject.episode.list.arrangeSpecials
import me.him188.ani.app.ui.subject.episode.list.gridSpecialEpisodes
import me.him188.ani.app.ui.subject.rememberSubjectStatusStrings
import me.him188.ani.app.ui.subject.renderSubjectSeason
import me.him188.ani.app.ui.user.SelfInfoUiState
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * 触屏 (平板 / iPad) 的条目详情页 (版式见设计稿方案 A): 整页铺着这部背景图的模糊版 (同遥控器版翻离首屏后那一层), 没有首屏大图,
 * 内容一整页往下滚 ——
 * - 头部 ([TvPadDetailsHeader]): 封面在左; 右边标题 / 另一个名字 / 季度与连载 (右端评分), 两行简介 (「展开」开全文), 标签,
 *   最下一行操作 (播放, 收藏, Bangumi, 反馈) 与收藏统计;
 * - 下面依次是选集、角色、制作人员、评价、关联作品 (见 TvPadDetailsSections), 每区一条横滑行.
 *
 * 没有焦点效果: 不画聚焦框、不放大, 点击出涟漪; 页面里 M3 涟漪的焦点态层关掉 ([TvPadDetailsRipple]), 焦点被兜底送进页面时也看不出来.
 * 左边是侧边栏, 栏顶是返回. 遥控器版见 [SubjectDetailsTvPage]; 播放器内嵌的介绍页 (videoBackground) 仍用遥控器版.
 */
@Composable
fun SubjectDetailsPadPage(
    state: SubjectDetailsState,
    selfInfo: SelfInfoUiState,
    onPlay: (episodeId: Int) -> Unit,
    onClickTag: (Tag) -> Unit,
    onClickLogin: () -> Unit,
    /** 参数 = 打开后落在第几条评论. */
    onShowComments: (initialFocusIndex: Int) -> Unit,
    onEpisodeCollectionUpdate: (SetEpisodeCollectionTypeRequest) -> Unit,
    windowInsets: WindowInsets,
    onClickOpenExternal: () -> Unit,
    onCoverImageSuccess: (AniImageLoadSuccess) -> Unit,
    onClickCache: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val info = state.info
    if (info == null) {
        SubjectDetailsPadLoadingPlaceholder(null, windowInsets, SubjectDetailsLoadAttempt.First, modifier)
        return
    }
    val uiState by state.uiState.collectAsStateWithLifecycle()
    // 选集行用全量列表, 特别篇按设置摆放 (同遥控器版与播放器选集条); 全部分集弹窗仍分正片 / 特别篇
    val specialsPlacement = LocalThemeSettings.current.tvEpisodeSpecials
    val episodes = remember(uiState.episodeListUiState, specialsPlacement) {
        uiState.episodeListUiState.allEpisodes.arrangeSpecials(specialsPlacement)
    }
    val mainEpisodes = uiState.episodeListUiState.mainEpisodes
    val specialEpisodes = remember(uiState.episodeListUiState, specialsPlacement) {
        uiState.episodeListUiState.gridSpecialEpisodes(specialsPlacement)
    }
    // "当前集"只在正片里找: 特别篇通常一直是未看状态
    val currentEpisodeId = remember(mainEpisodes) { mainEpisodes.firstOrNull { !it.isDoneOrDropped }?.episodeId }

    val exposedCharacters = state.exposedCharactersPager.collectAsLazyPagingItemsWithLifecycle()
    val allCharacters = state.charactersPager.collectAsLazyPagingItemsWithLifecycle()
    val exposedStaff = state.exposedStaffPager.collectAsLazyPagingItemsWithLifecycle()
    val allStaff = state.staffPager.collectAsLazyPagingItemsWithLifecycle()
    val related = state.relatedSubjectsPager.collectAsLazyPagingItemsWithLifecycle()
    val comments = state.subjectCommentState.list.collectAsLazyPagingItemsWithLifecycle()
    val episodesFetch by state.episodesFetchStateFlow.collectAsStateWithLifecycle(EpisodesFetchState.Pending)
    val episodesState = tvPadEpisodesState(episodes.isNotEmpty(), uiState.isPlaceholder, episodesFetch)
    val charactersState = tvPadPeopleState(exposedCharacters.itemCount, uiState.totalCharactersCount)
    val staffState = tvPadPeopleState(exposedStaff.itemCount, uiState.totalStaffCount)
    val reviewsState = tvPadPagedState(comments.itemCount, comments.loadState.refresh, state.subjectCommentState.count)
    val relatedState = tvPadPagedState(related.itemCount, related.loadState.refresh)

    val stills by state.tmdbEpisodeStillsFlow.collectAsStateWithLifecycle(emptyMap())
    val playProgress by state.playProgressFlow.collectAsStateWithLifecycle(emptyMap())
    val runtimes by state.tmdbEpisodeRuntimesFlow.collectAsStateWithLifecycle(emptyMap())
    val overviews by state.tmdbEpisodeOverviewsFlow.collectAsStateWithLifecycle(emptyMap())
    val names by state.tmdbEpisodeNamesFlow.collectAsStateWithLifecycle(emptyMap())
    // 非中文界面的 TMDB 标题与简介写进共享表 (本页标题、回到海报墙后的卡片都用它)
    SyncTvTmdbSubjectText(state)
    val displaySummary = rememberTvDetailsDisplaySummary(state, info)
    val backdropUrl = rememberTvPadBackdropUrl(state, info)

    // 动作面板「刷新本页」: 无视缓存新鲜度重取这个条目 (同遥控器版)
    val collectionRepository = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val refreshScope = rememberCoroutineScope()
    TvPageRefreshHandler {
        refreshScope.launch { collectionRepository.refreshSubjectCollection(state.subjectId) }
    }

    var showAllEpisodes by rememberSaveable { mutableStateOf(false) }
    val onSetEpisodeCollectionType = { item: EpisodeListItem, type: UnifiedCollectionType ->
        onEpisodeCollectionUpdate(SetEpisodeCollectionTypeRequest(state.subjectId, item.episodeId, type))
    }

    TvPadDetailsScaffold(backdropUrl, windowInsets, modifier) { topPadding ->
        val pad = TV_PAD_DETAILS_PADDING
        LazyColumn(
            Modifier.fillMaxSize(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(top = topPadding, bottom = TV_PAD_DETAILS_BOTTOM_PADDING),
            verticalArrangement = Arrangement.spacedBy(TV_PAD_DETAILS_SECTION_GAP),
        ) {
            item(key = "header") {
                TvPadDetailsHeader(
                    state = state,
                    info = info,
                    uiState = uiState,
                    selfInfo = selfInfo,
                    displaySummary = displaySummary,
                    mainEpisodeCount = mainEpisodes.size,
                    onPlay = onPlay,
                    onClickLogin = onClickLogin,
                    onClickTag = onClickTag,
                    onClickOpenExternal = onClickOpenExternal,
                    onCoverImageSuccess = onCoverImageSuccess,
                    modifier = Modifier.padding(horizontal = pad),
                )
            }
            // 各区块位置固定: 在路上时是同样几何的占位, 到了原地换; 确认没有的才撤 (淡出, 下面的滑上来). 见 TvPadSectionState
            tvPadSection("episodes", episodesState, skeleton = { TvPadEpisodesSkeleton(pad) }) {
                TvPadEpisodesSection(
                    episodes = episodes,
                    currentEpisodeId = currentEpisodeId,
                    stills = stills,
                    playProgress = playProgress,
                    runtimes = runtimes,
                    overviews = overviews,
                    names = names,
                    onPlay = { onPlay(it.episodeId) },
                    onSetCollectionType = onSetEpisodeCollectionType,
                    onClickCache = onClickCache,
                    onShowAllEpisodes = { showAllEpisodes = true },
                    horizontalPadding = pad,
                )
            }
            tvPadSection("characters", charactersState, skeleton = { TvPadPeopleSkeleton(stringResource(Lang.subject_details_characters), pad) }) {
                TvPadCharactersSection(exposedCharacters, allCharacters, uiState.totalCharactersCount, horizontalPadding = pad)
            }
            tvPadSection("staff", staffState, skeleton = { TvPadPeopleSkeleton(stringResource(Lang.subject_details_staff), pad) }) {
                TvPadStaffSection(exposedStaff, allStaff, uiState.totalStaffCount, horizontalPadding = pad)
            }
            tvPadSection("reviews", reviewsState, skeleton = { TvPadReviewsSkeleton(pad) }) {
                TvPadReviewsSection(comments, state.subjectCommentState.count, onShowComments, horizontalPadding = pad)
            }
            tvPadSection("related", relatedState, skeleton = { TvPadRelatedSkeleton(pad) }) {
                TvPadRelatedSection(related, horizontalPadding = pad)
            }
        }
    }
    if (showAllEpisodes) {
        TvAllEpisodesDialog(
            mainEpisodes = mainEpisodes,
            specialEpisodes = specialEpisodes,
            currentEpisodeId = currentEpisodeId,
            stills = stills,
            playProgress = playProgress,
            runtimes = runtimes,
            overviews = overviews,
            names = names,
            onPlay = { onPlay(it.episodeId) },
            onSetCollectionType = onSetEpisodeCollectionType,
            onClickCache = onClickCache,
            onDismiss = { showAllEpisodes = false },
        )
    }
}

/**
 * [SubjectDetailsPadPage] 的加载占位: 同一副骨架 (模糊底 + 侧边栏), 头部先按手上有的画 —— 导航带来的封面与标题, 背景取进程内热缓存
 * (上一个页面聚焦这张卡时就查过). 等得久了 (800ms 后) 在标题下补一个转圈, 首屏超时重来之后再写一句第几次尝试.
 */
@Composable
fun SubjectDetailsPadLoadingPlaceholder(
    subjectInfo: SubjectInfo?,
    windowInsets: WindowInsets,
    loadAttempt: SubjectDetailsLoadAttempt,
    modifier: Modifier = Modifier,
) {
    val tmdbImageService = remember { GlobalKoin.get<TmdbImageService>() }
    val backdropUrl = subjectInfo?.let { info ->
        tmdbImageService.peekBackdropUrl(info.subjectId)
            ?: info.imageLarge.takeIf { tmdbImageService.peekBackdropResolved(info.subjectId) && it.isNotBlank() }
    }
    var slowLoad by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(TV_PAD_SLOW_LOAD_SPINNER_DELAY)
        slowLoad = true
    }
    TvPadDetailsScaffold(backdropUrl, windowInsets, modifier) { topPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = topPadding).padding(horizontal = TV_PAD_DETAILS_PADDING)) {
            val coverWidth = tvPadCoverWidth(maxWidth)
            Row(horizontalArrangement = Arrangement.spacedBy(TV_PAD_HEADER_GAP)) {
                TvPadCover(subjectInfo?.imageLarge, subjectInfo?.subjectId, coverWidth, onSuccess = {})
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    subjectInfo?.let { TvPadTitle(it) }
                    if (slowLoad) {
                        CircularProgressIndicator(Modifier.padding(top = 8.dp).size(28.dp), strokeWidth = 3.dp)
                        if (loadAttempt.isRetrying) {
                            Text(
                                stringResource(Lang.subject_details_load_retrying, loadAttempt.attempt, loadAttempt.maxAttempts),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 头部以下的一个区块 (键 [key], 位置固定): 在路上时是 [skeleton], 有内容时是 [content], 两者同一个列表项、原地换;
 * 确认没有时不占列表项 —— 撤掉时淡出, 下面的区块滑上来 (同一列表项里换内容不动画).
 */
private fun LazyListScope.tvPadSection(
    key: String,
    state: TvPadSectionState,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (state == TvPadSectionState.Empty) return
    item(key = key) {
        Box(Modifier.animateItem(fadeInSpec = null)) {
            if (state == TvPadSectionState.Content) content() else skeleton()
        }
    }
}

/**
 * 页面与占位共用的骨架: 页面底色 + 模糊底, 左边侧边栏 (栏顶返回), 右边是内容 ([content] 收到内容顶部该留的距离, 已含状态栏).
 * 内容区从侧边栏右缘起, 横滑行滑出去的卡片在那里裁掉, 不从侧边栏底下穿过.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvPadDetailsScaffold(
    backdropUrl: String?,
    windowInsets: WindowInsets,
    modifier: Modifier = Modifier,
    content: @Composable (topPadding: Dp) -> Unit,
) {
    val pageColor = AniThemeDefaults.pageContentBackgroundColor
    CompositionLocalProvider(
        LocalRippleConfiguration provides TvPadDetailsRipple,
        LocalContentColor provides contentColorFor(pageColor),
    ) {
        Box(modifier.fillMaxSize().background(pageColor)) {
            backdropUrl?.let { TvPadDetailsBackdrop(it) }
            val navigator = LocalNavigator.current
            val topInset = windowInsets.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
            // 页面自己的落点: 内容区整块, 什么都不画. 进页送到这里; 焦点兜底 (页面没焦点时往页面里送, 落点是最左的侧边栏) 进侧边栏也改送到这里 ——
            // 侧边栏条目持焦会画高亮, 触屏上没人按方向键, 它就一直亮着. 点侧边栏不需要焦点, 照常跳转
            val pageFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { pageFocus.requestFocus() } }
            Row(Modifier.fillMaxSize().windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal))) {
                Box(
                    Modifier.width(TV_PAD_DETAILS_RAIL_WIDTH).fillMaxHeight().padding(top = topInset)
                        .focusProperties { onEnter = { runCatching { pageFocus.requestFocus() } } }
                        .focusGroup(),
                ) {
                    TvDetailsSideRail(onExitToHero = {}, onBack = { navigator.popBackStack() })
                }
                Box(Modifier.weight(1f).fillMaxHeight().focusRequester(pageFocus).focusable()) {
                    content(topInset + TV_PAD_DETAILS_TOP_PADDING)
                }
            }
        }
    }
}

/**
 * 整页的模糊底 ([TvDetailsBlurredBackdrop], 同遥控器版翻离首屏那一层; 设置里选了不模糊时用中等那一档). 解好就淡进来;
 * 刚组合时就已经解好 (占位页换成真页、返回本页) 直接显示.
 */
@Composable
private fun TvPadDetailsBackdrop(url: String) {
    val ready = tvDetailsBlurredBackdropReady(url)
    val shown = remember(url) { Animatable(if (ready) 1f else 0f) }
    var loaded by remember(url) { mutableStateOf(false) }
    LaunchedEffect(shown, loaded) {
        if (loaded) shown.animateTo(1f, tween(TV_WALL_BACKDROP_CROSSFADE_MILLIS))
    }
    TvDetailsBlurredBackdrop(url, alpha = { shown.value }, load = true, onLoaded = { loaded = true })
}

/**
 * 背景图地址: TMDB 横版图, 确认没有才用竖版封面 (结果没出来之前不放图, 免得先铺封面再换). 首帧取进程内热缓存
 * (上一个页面聚焦这张卡时就查过), flow 照常收着校正.
 */
@Composable
private fun rememberTvPadBackdropUrl(state: SubjectDetailsState, info: SubjectInfo): String? {
    val tmdbImageService = remember { GlobalKoin.get<TmdbImageService>() }
    var resolved by remember(state) { mutableStateOf(tmdbImageService.peekBackdropResolved(state.subjectId)) }
    var tmdbUrl by remember(state) { mutableStateOf(tmdbImageService.peekBackdropUrl(state.subjectId)) }
    LaunchedEffect(state) {
        state.tmdbBackdropUrlFlow.collect {
            tmdbUrl = it
            resolved = true
        }
    }
    return tmdbUrl ?: info.imageLarge.takeIf { resolved && it.isNotBlank() }
}

/**
 * 头部 (照平板设计稿), 左边封面, 右边一列 ([TvPadHeaderLayout]):
 * - 上: 标题 / 另一个名字 / 季度与连载, 右上角是评分 (条形图在上、分数 + 星级排名在下, 点开评分弹窗; 见 [TvPadTitleRow]);
 * - 中: 简介 (剩下的高度放几行算几行, 末尾「展开」)、两行标签 (放不下的收进「更多标签」); 点简介或「更多标签」开同一个弹窗
 *   (全文 + 全部标签, 见 [TvPadSummaryDialog]);
 * - 下: 左边播放 / 收藏 / Bangumi / 反馈, 右端收藏统计; 这一行贴着封面的底.
 * 全部选集与缓存在选集区的标题行 (见 TvPadEpisodesSection).
 */
@Composable
private fun TvPadDetailsHeader(
    state: SubjectDetailsState,
    info: SubjectInfo,
    uiState: SubjectDetailsUiState,
    selfInfo: SelfInfoUiState,
    displaySummary: String,
    mainEpisodeCount: Int,
    onPlay: (episodeId: Int) -> Unit,
    onClickLogin: () -> Unit,
    onClickTag: (Tag) -> Unit,
    onClickOpenExternal: () -> Unit,
    onCoverImageSuccess: (AniImageLoadSuccess) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 只认自己打开的那次评分弹窗 (同一个 state 也挂在全量评论里的写评价上)
    val ratingSource = remember { Any() }
    var showSummaryDialog by remember { mutableStateOf(false) }
    if (showSummaryDialog) {
        TvPadSummaryDialog(
            title = info.tvTitle(),
            summary = displaySummary,
            aliases = subjectAliasesText(info),
            meta = subjectMetaLine(info, mainEpisodeCount.takeIf { it > 0 }),
            tags = info.tags,
            onClickTag = onClickTag,
            onDismiss = { showSummaryDialog = false },
        )
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val coverWidth = tvPadCoverWidth(maxWidth)
        TvPadHeaderLayout(
            cover = { TvPadCover(info.imageLarge, info.subjectId, coverWidth, onSuccess = onCoverImageSuccess) },
            top = {
                TvPadTitleRow(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(TV_PAD_HEADER_TITLE_GAP)) {
                            TvPadTitle(info)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    renderSubjectSeason(info.airDate),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                // 连载信息等条目数据到了再出 (之前是空的)
                                if (!uiState.isPlaceholder) {
                                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                        Text(" · ", style = MaterialTheme.typography.titleSmall)
                                        AiringLabel(
                                            state.rememberTmdbAiringLabelState(uiState),
                                            style = MaterialTheme.typography.titleSmall,
                                            progressColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    score = { TvPadRatingScore(info) },
                    histogram = { height -> TvPadRatingHistogram(info, height) },
                    onClickRating = { state.requestEditRating(ratingSource) },
                )
            },
            summary = { room, width -> TvPadSummary(displaySummary, room, width, onClick = { showSummaryDialog = true }) },
            tags = { TvPadTags(info.tags, onClickTag, onClickMore = { showSummaryDialog = true }) },
            bottom = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 播放键的文字 (继续观看第几话) 与收藏状态要等条目数据: 之前占着位置不画, 到了淡进来 (不先显示「未知」或别的收藏状态)
                    val actionsAlpha by animateFloatAsState(if (uiState.isPlaceholder) 0f else 1f, tween(TV_PAD_ACTIONS_FADE_MILLIS))
                    TvPadActions(
                        state = state,
                        info = info,
                        uiState = uiState,
                        selfInfo = selfInfo,
                        onPlay = onPlay,
                        onClickLogin = onClickLogin,
                        onClickOpenExternal = onClickOpenExternal,
                        modifier = Modifier.weight(1f).graphicsLayer { alpha = actionsAlpha },
                    )
                    // 自己改了收藏类型后统计跟着变 (uiState); 还没加载出来时先用条目信息里的
                    TvPadStats(if (uiState.isPlaceholder) info.collectionStats else uiState.collectionStats)
                }
            },
        )
    }
}

/**
 * 头部的摆法: [cover] 在左按自己的高; 右边一列占剩下的宽, 从上往下 [top]、[summary]、[tags] 依次排 (隔 [TV_PAD_HEADER_RIGHT_GAP],
 * 没内容的不占间距), [bottom] 贴底. 整块高度至少是封面的高, 于是 [bottom] 的底与封面的底齐.
 * [summary] 最后排, 拿到其余几块排完剩下的高度 (room), 按它能放几行就放几行 (见 [TvPadSummary]); 标题两行、标签两行时剩下的少,
 * 还放不下 (简介已是最少行数) 时 [bottom] 往下挪 (离标签至少 [TV_PAD_HEADER_BOTTOM_GAP]), 先伸进下面的区块间距, 报给外面的高度不变,
 * 下面的选集不动; 超出间距的部分才把整块撑高.
 */
@Composable
private fun TvPadHeaderLayout(
    cover: @Composable () -> Unit,
    top: @Composable () -> Unit,
    summary: @Composable (room: Dp, width: Dp) -> Unit,
    tags: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    SubcomposeLayout { constraints ->
        val columnGap = TV_PAD_HEADER_GAP.roundToPx()
        val gap = TV_PAD_HEADER_RIGHT_GAP.roundToPx()
        fun slot(key: TvPadHeaderSlot, content: @Composable () -> Unit, slotConstraints: Constraints) =
            subcompose(key) { Box { content() } }.first().measure(slotConstraints)
        val cover = slot(TvPadHeaderSlot.Cover, cover, constraints.copy(minWidth = 0, minHeight = 0))
        val width = (constraints.maxWidth - cover.width - columnGap).coerceAtLeast(0)
        val fullWidth = Constraints(minWidth = width, maxWidth = width)
        val top = slot(TvPadHeaderSlot.Top, top, fullWidth)
        val tags = slot(TvPadHeaderSlot.Tags, tags, fullWidth)
        val bottom = slot(TvPadHeaderSlot.Bottom, bottom, fullWidth)
        fun spaced(height: Int) = if (height > 0) height + gap else 0
        val room = (cover.height - spaced(top.height) - spaced(tags.height) - TV_PAD_HEADER_BOTTOM_GAP.roundToPx() - bottom.height)
            .coerceAtLeast(0).toDp()
        val summary = slot(TvPadHeaderSlot.Summary, { summary(room, width.toDp()) }, fullWidth)
        val summaryTop = spaced(top.height)
        val tagsTop = summaryTop + spaced(summary.height)
        // 右列要的高; 比封面高出来的先伸进下面那段区块间距 (留 TV_PAD_HEADER_OVERFLOW_KEEP), 下面的选集不动; 再多才把整块撑高
        val needed = maxOf(cover.height, tagsTop + tags.height + TV_PAD_HEADER_BOTTOM_GAP.roundToPx() + bottom.height)
        val overflow = (TV_PAD_DETAILS_SECTION_GAP - TV_PAD_HEADER_OVERFLOW_KEEP).roundToPx().coerceAtLeast(0)
        val height = maxOf(cover.height, needed - overflow)
        layout(constraints.maxWidth, height) {
            val x = cover.width + columnGap
            cover.place(0, 0)
            top.place(x, 0)
            summary.place(x, summaryTop)
            tags.place(x, tagsTop)
            bottom.place(x, needed - bottom.height)
        }
    }
}

private enum class TvPadHeaderSlot { Cover, Top, Summary, Tags, Bottom }


/**
 * 头部最上一行: [title] 在左占剩下的宽; 评分一列在右上角, [histogram] 在上、[score] 在下, 两块水平居中对齐. 整列点开评分弹窗,
 * 涟漪比内容宽出一圈, 这一圈不占布局 (头部高度紧, 见 [tvPadPressOutset]).
 *
 * 下面的简介横跨到评分列底下, 所以评分列的底不低于 [title] 的底: 条形图的高 = [title] 量出来的高减去分数那一组与间距,
 * 夹在 [TV_PAD_HISTOGRAM_MIN_HEIGHT] 与 [TV_PAD_HISTOGRAM_HEIGHT] 之间; 标题太矮、条形图已到最矮时这一行跟着评分列长高.
 */
@Composable
private fun TvPadTitleRow(
    title: @Composable () -> Unit,
    score: @Composable () -> Unit,
    histogram: @Composable (height: Dp) -> Unit,
    onClickRating: () -> Unit,
) {
    SubcomposeLayout { constraints ->
        fun slot(key: TvPadTitleRowSlot, content: @Composable () -> Unit, slotConstraints: Constraints) =
            subcompose(key) { Box { content() } }.first().measure(slotConstraints)
        val width = constraints.maxWidth
        val scorePlaceable = slot(TvPadTitleRowSlot.Score, score, Constraints())
        val histogramWidth = TV_PAD_HISTOGRAM_WIDTH.roundToPx()
        val columnWidth = maxOf(scorePlaceable.width, histogramWidth)
        val titleWidth = (width - columnWidth - TV_PAD_HEADER_GAP.roundToPx()).coerceAtLeast(0)
        val titlePlaceable = slot(TvPadTitleRowSlot.Title, title, Constraints(minWidth = titleWidth, maxWidth = titleWidth))
        val scoreGap = TV_PAD_RATING_HISTOGRAM_GAP.roundToPx()
        val histogramHeight = (titlePlaceable.height - scorePlaceable.height - scoreGap)
            .coerceIn(TV_PAD_HISTOGRAM_MIN_HEIGHT.roundToPx(), TV_PAD_HISTOGRAM_HEIGHT.roundToPx())
        val histogramDp = histogramHeight.toDp()
        val histogramPlaceable = slot(
            TvPadTitleRowSlot.Histogram,
            { histogram(histogramDp) },
            Constraints.fixed(histogramWidth, histogramHeight),
        )
        val columnHeight = scorePlaceable.height + scoreGap + histogramHeight
        val press = slot(
            TvPadTitleRowSlot.Press,
            {
                Box(
                    Modifier.tvPadPressOutset(TV_PAD_RATING_PRESS_INSET, TV_PAD_RATING_PRESS_INSET / 2)
                        .clip(RoundedCornerShape(TV_PAD_RATING_PRESS_INSET + 4.dp))
                        .clickable(onClick = onClickRating)
                        .padding(horizontal = TV_PAD_RATING_PRESS_INSET, vertical = TV_PAD_RATING_PRESS_INSET / 2),
                )
            },
            Constraints.fixed(columnWidth, columnHeight),
        )
        layout(width, maxOf(titlePlaceable.height, columnHeight)) {
            val x = width - columnWidth
            titlePlaceable.place(0, 0)
            // 点按范围先摆, 画在分数与条形图底下 (它们不接触摸, 点上去落到它)
            press.place(x, 0)
            histogramPlaceable.place(x + (columnWidth - histogramWidth) / 2, 0)
            scorePlaceable.place(x + (columnWidth - scorePlaceable.width) / 2, histogramHeight + scoreGap)
        }
    }
}

private enum class TvPadTitleRowSlot { Title, Score, Histogram, Press }

/**
 * 给后面的点按范围四周加宽 [horizontal] / [vertical] (后面要有同样大小的 padding): 量的时候算进去, 报给外面的大小不算,
 * 内容留在原位, 只有涟漪伸出去.
 */
private fun Modifier.tvPadPressOutset(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(h * 2, v * 2))
    layout((placeable.width - h * 2).coerceAtLeast(0), (placeable.height - v * 2).coerceAtLeast(0)) {
        placeable.place(-h, -v)
    }
}

/** 评分的大分数 + 星级与「排名 · 人数」一组. */
@Composable
private fun TvPadRatingScore(info: SubjectInfo) {
    val rating = info.ratingInfo
    Row(
        horizontalArrangement = Arrangement.spacedBy(TV_PAD_RATING_SCORE_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            remember(rating.score) { renderScore(rating.score) },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FiveRatingStars(remember(rating.score) { rating.scoreFloat.roundToInt() }, starSize = TV_PAD_RATING_STAR_SIZE)
            Text(
                stringResource(
                    Lang.subject_details_rating_summary,
                    rating.rank.toString(),
                    remember(rating.total) { groupThousands(rating.total) },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 十档评分分布的条形图, 宽占满, 总高 [height] (柱高 = 总高减去刻度那一行, 见 RatingHistogram 的排法: 柱、6dp、刻度). */
@Composable
private fun TvPadRatingHistogram(info: SubjectInfo, height: Dp) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = TV_PAD_HISTOGRAM_LABEL_SIZE, letterSpacing = 0.sp)
    val labelHeight = with(LocalDensity.current) { labelStyle.lineHeight.toDp() }
    RatingHistogram(
        info.ratingInfo,
        Modifier.fillMaxWidth(),
        // 压在模糊底上: 轨道用正文色的淡底, 不用实色
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TV_PAD_HISTOGRAM_TRACK_ALPHA),
        barHeight = (height - TV_PAD_HISTOGRAM_LABEL_GAP - labelHeight).coerceAtLeast(0.dp),
        barSpacing = TV_PAD_HISTOGRAM_BAR_SPACING,
        labelStyle = labelStyle,
    )
}

/** 收藏统计: 一行小字「60,300 收藏  7,259 在看  5,677 想看」, 摆在操作那一行的右端. */
@Composable
private fun TvPadStats(stats: SubjectCollectionStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(TV_PAD_STATS_GAP)) {
        listOf(
            stats.collect to stringResource(Lang.subject_details_stat_collected),
            stats.doing to stringResource(Lang.subject_details_stat_watching),
            stats.wish to stringResource(Lang.subject_details_stat_wish),
        ).forEach { (count, label) ->
            Text(
                "${remember(count) { groupThousands(count) }} $label",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 竖版封面 (NSFW 设为模糊时打码, 见 NsfwPolicy); 没有图时是同尺寸的玻璃底. */
@Composable
private fun TvPadCover(url: String?, subjectId: Int?, width: Dp, onSuccess: (AniImageLoadSuccess) -> Unit) {
    val nsfw = rememberNsfwPolicy()
    AsyncImage(
        url?.takeIf { it.isNotBlank() },
        contentDescription = null,
        Modifier.width(width).aspectRatio(COVER_WIDTH_TO_HEIGHT_RATIO)
            .clip(RoundedCornerShape(TV_PAD_COVER_CORNER))
            .background(glassContainerColor()),
        contentScale = ContentScale.Crop,
        onSuccess = onSuccess,
        downsampleLongEdgePx = subjectId?.let { nsfw.coverDownsample(it) },
    )
}

/** 封面宽度: 内容区窄 (竖屏平板) 时缩小, 给右边那一列留够宽. */
private fun tvPadCoverWidth(contentWidth: Dp): Dp =
    if (contentWidth >= TV_PAD_WIDE_HEADER_MIN_WIDTH) TV_PAD_COVER_WIDTH else TV_PAD_COVER_WIDTH_NARROW

/** 标题 (两行截断) + 另一个名字. */
@Composable
private fun TvPadTitle(info: SubjectInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            info.tvTitle(),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        info.tvSecondaryName()?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 简介: [room] 高度里放得下几行就几行 (至少 [TV_PAD_SUMMARY_MIN_LINES] 行, 见 [TvPadHeaderLayout]),
 * 末尾跟一个主题色的「展开」(放不下时在它前面截断加省略号, 见 [tvPadCollapsedSummary]); 整块点一下 [onClick] (开全文弹窗).
 *
 * 行数与截断都按头部量好交下来的 [room] / [width] 在这一次组合里算完, 不套按约束取宽度的布局 —— 那层要等自己测量才重算,
 * 比头部晚一帧, 头部高度变了时会先按旧行数排一帧 (整块撑高再缩回, 看着闪一下).
 */
@Composable
private fun TvPadSummary(summary: String, room: Dp, width: Dp, onClick: () -> Unit) {
    if (summary.isBlank()) return
    // 行数有限, 段落之间的空行不占它 (全文弹窗里照原样)
    val preview = remember(summary) { summary.replace(BLANK_LINES, "\n").trim() }
    val style = MaterialTheme.typography.bodyLarge
    val expand = stringResource(Lang.subject_episode_expand)
    val expandColor = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    // 上下各有半个点按内缩 (见下面的 padding)
    val lines = ((room - TV_PAD_SUMMARY_PRESS_INSET) / lineHeight).toInt().coerceAtLeast(TV_PAD_SUMMARY_MIN_LINES)
    // 字占满整列宽, 右边与标签、评分列、收藏统计齐; 涟漪左右比字宽出一圈, 这一圈不占布局 (见 tvPadPressOutset)
    val textWidth = with(LocalDensity.current) { width.roundToPx() }
    val text = remember(preview, textWidth, style, expand, expandColor, lines) {
        tvPadCollapsedSummary(measurer, preview, style, textWidth, lines, expand, expandColor)
    }
    Text(
        text,
        Modifier.tvPadPressOutset(TV_PAD_SUMMARY_PRESS_INSET, 0.dp)
            .clip(RoundedCornerShape(TV_PAD_SUMMARY_PRESS_INSET))
            .clickable(onClick = onClick)
            .padding(horizontal = TV_PAD_SUMMARY_PRESS_INSET, vertical = TV_PAD_SUMMARY_PRESS_INSET / 2),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 收着的简介: [text] 后面跟 [more] (主题色) 共 [maxLines] 行; 放不下时从后往前截, 截掉的地方加省略号, 让 [more] 正好落在末行.
 * 按 [widthPx] 实排 (二分找能留下的最多字数), 不按字数估: 中日文与英文字宽差一倍.
 */
private fun tvPadCollapsedSummary(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    widthPx: Int,
    maxLines: Int,
    more: String,
    moreColor: Color,
): AnnotatedString {
    fun withMore(prefix: String) = buildAnnotatedString {
        append(prefix)
        append(' ')
        withStyle(SpanStyle(color = moreColor)) { append(more) }
    }

    fun fits(candidate: AnnotatedString) =
        !measurer.measure(candidate, style, maxLines = maxLines, constraints = Constraints(maxWidth = widthPx)).hasVisualOverflow

    val whole = withMore(text)
    if (widthPx <= 0 || fits(whole)) return whole
    var low = 0
    var high = text.length
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (fits(withMore(text.take(mid).trimEnd() + "…"))) low = mid else high = mid - 1
    }
    return withMore(text.take(low).trimEnd() + "…")
}

/**
 * 标签: 最多 [TV_PAD_TAGS_LINES] 行, 放不下时末行最后是「更多标签」(主题色的字, 给它腾位置, 挤掉末行最后几个), 点了 [onClickMore]
 * (开简介弹窗, 全部标签在里面). 放得下就全列出来, 没有它. 点标签进搜索.
 */
@Composable
private fun TvPadTags(tags: List<Tag>, onClickTag: (Tag) -> Unit, onClickMore: () -> Unit) {
    if (tags.isEmpty()) return
    Layout(
        content = {
            tags.forEach { tag -> TvTagChip(tag.name, Modifier.clickable { onClickTag(tag) }) }
            Text(
                stringResource(Lang.pad_subject_details_more_tags),
                Modifier.clip(RoundedCornerShape(TV_PAD_TAG_LINK_INSET))
                    .clickable(onClick = onClickMore)
                    .padding(horizontal = TV_PAD_TAG_LINK_INSET, vertical = TV_PAD_TAG_LINK_INSET / 2),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        },
    ) { measurables, constraints ->
        val gap = TV_PAD_TAG_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val chips = measurables.dropLast(1).map { it.measure(loose) }
        val toggle = measurables.last().measure(loose)
        val lines = tvPadFlowLines(chips.map { it.width }, constraints.maxWidth, gap)
        val overflows = lines.size > TV_PAD_TAGS_LINES
        // 要摆的: (第几个, 第几行); 第几个 = chips.size 表示按钮
        val shown = mutableListOf<Pair<Int, Int>>()
        if (!overflows) {
            lines.forEachIndexed { line, items -> items.forEach { shown += it to line } }
        } else {
            lines.take(TV_PAD_TAGS_LINES).forEachIndexed { line, items ->
                val kept = items.toMutableList()
                if (line == TV_PAD_TAGS_LINES - 1) {
                    while (kept.isNotEmpty() && kept.sumOf { chips[it].width } + gap * kept.size + toggle.width > constraints.maxWidth) {
                        kept.removeAt(kept.lastIndex)
                    }
                }
                kept.forEach { shown += it to line }
                if (line == TV_PAD_TAGS_LINES - 1) shown += chips.size to line
            }
        }
        val lineCount = (shown.maxOfOrNull { it.second } ?: -1) + 1
        val lineHeight = (chips + toggle).maxOf { it.height }
        val height = (lineHeight * lineCount + gap * (lineCount - 1)).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            var line = -1
            var x = 0
            for ((index, lineIndex) in shown) {
                if (lineIndex != line) {
                    line = lineIndex
                    x = 0
                }
                val placeable = if (index == chips.size) toggle else chips[index]
                // 「更多标签」是字不是胶囊, 比胶囊矮: 在行里竖着居中
                placeable.place(x, lineIndex * (lineHeight + gap) + (lineHeight - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}

/** 宽度依次为 [widths] 的格子按 [maxWidth] 一行行排 (格间 [gap]), 返回每行放哪几个. 比一行还宽的格子单占一行. */
internal fun tvPadFlowLines(widths: List<Int>, maxWidth: Int, gap: Int): List<List<Int>> {
    val lines = mutableListOf<MutableList<Int>>()
    var x = 0
    widths.forEachIndexed { i, w ->
        if (lines.isEmpty() || (x > 0 && x + w > maxWidth)) {
            lines += mutableListOf<Int>()
            x = 0
        }
        lines.last() += i
        x += w + gap
    }
    return lines
}

/**
 * 操作: 播放 (正文色实底, 分集还没出来时转圈等着, 一集都没有时弹说明) / 收藏 (五态下拉; 没登录是「登录后收藏」) / 在 Bangumi 打开 / 反馈.
 * 样子同平板海报墙 hero 的胶囊按钮. 全部选集与缓存在选集区的标题行.
 */
@Composable
private fun TvPadActions(
    state: SubjectDetailsState,
    info: SubjectInfo,
    uiState: SubjectDetailsUiState,
    selfInfo: SelfInfoUiState,
    onPlay: (episodeId: Int) -> Unit,
    onClickLogin: () -> Unit,
    onClickOpenExternal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val tonalColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = contentColor.copy(alpha = TV_PAD_TONAL_ALPHA),
        contentColor = contentColor,
    )
    val tonalIconColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = contentColor.copy(alpha = TV_PAD_TONAL_ALPHA),
        contentColor = contentColor,
    )
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 连载信息按 TMDB 改成已开播时按钮文字跟着改 (见 rememberTmdbAiringLabelState); 要播哪一集不变
        val progressState = state.rememberTmdbSubjectProgressState(uiState)
        val playGate = rememberTvPlayEpisodesGate(
            state,
            loaded = !uiState.isPlaceholder,
            hasEpisodes = uiState.episodeListUiState.allEpisodes.isNotEmpty(),
            episodeIdToPlay = progressState.episodeIdToPlay,
            onPlay = onPlay,
        )
        val strings = rememberSubjectStatusStrings()
        Button(
            onClick = playGate::press,
            colors = ButtonDefaults.buttonColors(containerColor = contentColor, contentColor = MaterialTheme.colorScheme.surface),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            if (playGate.waiting) {
                CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(20.dp))
            }
            Text(progressState.buttonText(strings), Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
        }
        playGate.dialog?.let { outcome ->
            TvSubjectNoEpisodesDialog(fetchFailed = outcome == TvPlayEpisodesOutcome.FETCH_FAILED, onDismiss = playGate::dismissDialog)
        }

        if (selfInfo.isSessionValid == false) {
            FilledTonalButton(onClick = onClickLogin, colors = tonalColors) {
                SubjectCollectionActions.Collect.icon()
                Text(stringResource(Lang.subject_details_login_to_collect), Modifier.padding(start = 8.dp))
            }
        } else {
            TvPadCollectionButton(uiState.collectionTypeEdit, state, tonalColors)
        }

        FilledTonalIconButton(onClick = onClickOpenExternal, colors = tonalIconColors) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Bangumi")
        }
        // 反馈: 标题 logo 不对 / 对应的作品不对 (见 TvSubjectFeedbackDialog)
        var feedbackOpen by remember { mutableStateOf(false) }
        FilledTonalIconButton(onClick = { feedbackOpen = true }, colors = tonalIconColors) {
            Icon(Icons.Outlined.Feedback, contentDescription = stringResource(Lang.subject_details_tv_feedback))
        }
        if (feedbackOpen) {
            TvSubjectFeedbackDialog(info.subjectId, info.displayName, info.name) { feedbackOpen = false }
        }
    }
}

/** 收藏按钮: 当前收藏状态 (图标 + 文字), 点开五态下拉; 设为「看过」时弹「同时标记所有剧集」(同遥控器版的收藏圆钮). */
@Composable
private fun TvPadCollectionButton(
    presentation: EditableSubjectCollectionTypeState.Presentation,
    actions: SubjectCollectionTypeEditActions,
    colors: ButtonColors,
) {
    EditableSubjectCollectionTypeDialogsHost(presentation, actions)
    var expanded by remember { mutableStateOf(false) }
    val type = presentation.selfCollectionType
    val action = remember(type) { SubjectCollectionActionsForCollect.find { it.type == type } }
    Box {
        FilledTonalButton(
            onClick = { if (!presentation.isPlaceholder && !presentation.isSetSelfCollectionTypeWorking) expanded = true },
            colors = colors,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            action?.icon?.invoke()
            Box(Modifier.padding(start = 8.dp)) {
                if (type == UnifiedCollectionType.NOT_COLLECTED) {
                    action?.title?.invoke()
                } else {
                    Text(renderCollectionTypeAsCurrent(type))
                }
            }
        }
        val scope = rememberCoroutineScope()
        val toaster = LocalToaster.current
        // 菜单用上游平板的样子 (M3 菜单, 当前值是主题色字): 电视那套是「聚焦项铺色」, 触屏上菜单一打开第一项就拿到焦点, 一直亮着
        CompositionLocalProvider(LocalAniUiBehavior provides LocalAniUiBehavior.current.copy(focusDrivenNavigation = false)) {
            EditCollectionTypeDropDown(
                currentType = type,
                expanded = expanded,
                onDismissRequest = { expanded = false },
                onClick = {
                    scope.launch { actions.setSelfCollectionType(it.type)?.let(toaster::showLoadError) }
                },
            )
        }
    }
}

/**
 * 页面里 M3 涟漪的透明度: 焦点态层为 0 (焦点不画任何东西), 按下的比默认略重一点, 压在模糊底和剧照上也看得出.
 * 按透明度配置的构造器标了过时, 但只有它能同时管到 M3 按钮内部自带的涟漪 (主题级配置只有「透明度 / 内嵌描边」两种焦点样式, 没有「不画」).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
private val TvPadDetailsRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0f, hoveredAlpha = 0.08f, pressedAlpha = 0.14f),
)

/** 侧边栏那一列的宽度 (栏里图标 32dp + 两侧各 16dp). */
private val TV_PAD_DETAILS_RAIL_WIDTH = 64.dp

/** 内容左右留白 (横滑行两端也留这么多, 滑动时卡片画到页面右缘). */
private val TV_PAD_DETAILS_PADDING = 32.dp

/** 内容顶部留白 (状态栏之下). */
private val TV_PAD_DETAILS_TOP_PADDING = 32.dp

/** 内容底部留白. */
private val TV_PAD_DETAILS_BOTTOM_PADDING = 48.dp

/** 区块之间的间距. */
private val TV_PAD_DETAILS_SECTION_GAP = 36.dp

/** 头部封面与右边那一列的间距. */
private val TV_PAD_HEADER_GAP = 36.dp

/** 封面宽度 (横屏) / 内容区窄时的宽度, 与切换的内容区宽度. */
private val TV_PAD_COVER_WIDTH = 240.dp
private val TV_PAD_COVER_WIDTH_NARROW = 180.dp
private val TV_PAD_WIDE_HEADER_MIN_WIDTH = 900.dp

private val TV_PAD_COVER_CORNER = 16.dp

/** 简介里段落之间的空行 (连续换行, 中间可夹空白). */
private val BLANK_LINES = Regex("\n\\s*\n")

/** 操作那一行在条目数据到了之后淡进来的时长. */
private const val TV_PAD_ACTIONS_FADE_MILLIS = 200

/**
 * 头部右列各块之间的间距、标题与季度那一行的间距、标签与最下一行 (操作) 至少隔多远、收藏统计三项之间的间距.
 */
private val TV_PAD_HEADER_RIGHT_GAP = 14.dp
private val TV_PAD_HEADER_TITLE_GAP = 10.dp
private val TV_PAD_HEADER_BOTTOM_GAP = 18.dp

/** 头部右列比封面高时可以伸进下面的区块间距, 但离选集至少留这么多 (见 TvPadHeaderLayout). */
private val TV_PAD_HEADER_OVERFLOW_KEEP = 12.dp
private val TV_PAD_STATS_GAP = 16.dp

/** 评分那一列按下时涟漪比内容宽出的那一圈; 星星的大小; 大分数与右边星级之间的间距; 条形图与下面分数那一组之间的间距. */
private val TV_PAD_RATING_PRESS_INSET = 8.dp
private val TV_PAD_RATING_STAR_SIZE = 16.dp
private val TV_PAD_RATING_SCORE_GAP = 12.dp
private val TV_PAD_RATING_HISTOGRAM_GAP = 6.dp

/**
 * 评分分布条形图: 宽、最高与最矮 (连刻度; 实际的高按标题那一块的高定, 见 TvPadTitleRow), 柱与刻度之间的间距
 * (RatingHistogram 里写死的 6dp), 柱间距, 轨道 (正文色) 的不透明度.
 * 照设计稿: 窄的十根柱 (柱宽约 11dp、间距 6dp), 最高时柱高约 52dp; 最矮时柱高约 16dp, 还看得出高低; 刻度字小一档
 * (见 TV_PAD_HISTOGRAM_LABEL_SIZE), 「10」放得进一根柱宽.
 */
private val TV_PAD_HISTOGRAM_WIDTH = 168.dp
private val TV_PAD_HISTOGRAM_HEIGHT = 74.dp
private val TV_PAD_HISTOGRAM_MIN_HEIGHT = 38.dp
private val TV_PAD_HISTOGRAM_LABEL_GAP = 6.dp
private val TV_PAD_HISTOGRAM_BAR_SPACING = 6.dp
private const val TV_PAD_HISTOGRAM_TRACK_ALPHA = 0.1f
private val TV_PAD_HISTOGRAM_LABEL_SIZE = 10.sp

/** 标签最多几行 (其余收进简介弹窗)、标签间距、「更多标签」那几个字按下时涟漪宽出的一圈. */
private const val TV_PAD_TAGS_LINES = 2
private val TV_PAD_TAG_GAP = 8.dp
private val TV_PAD_TAG_LINK_INSET = 8.dp

/** 简介最少几行 (其余按头部剩下的高度, 见 TvPadSummary; 标题与标签都占两行时可以挤到只剩一行, 头部不长、下面的选集不动). */
private const val TV_PAD_SUMMARY_MIN_LINES = 1

/** 简介按下时涟漪比字宽出的那一圈 (也是它的圆角). */
private val TV_PAD_SUMMARY_PRESS_INSET = 8.dp

/** 次要按钮的淡底 (正文色的不透明度), 同平板海报墙 hero 的次按钮. */
private const val TV_PAD_TONAL_ALPHA = 0.16f

/** 加载占位补出转圈的等待阈值: 短等待放转圈反而更显慢. */
private val TV_PAD_SLOW_LOAD_SPINNER_DELAY = 800.milliseconds
