/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropDecodeAtOriginalSize
import me.him188.ani.app.ui.foundation.tv.TV_HERO_ZOOM_LOAD_BUDGET_MILLIS
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.ui.foundation.tv.TvPolishFlags
import me.him188.ani.app.ui.foundation.tv.TvBackdropFade
import me.him188.ani.app.ui.foundation.tv.lerpTvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.tvBackdropTreatmentPainter
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomEasing
import me.him188.ani.app.ui.foundation.tv.tvHeroShrinkEasing
import me.him188.ani.app.ui.foundation.tv.tvHeroSwapDim
import me.him188.ani.app.ui.foundation.tv.TV_HERO_SWAP_AT
import me.him188.ani.app.ui.foundation.tv.TV_HERO_SHRINK_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_SHRINK_READY_TIMEOUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_ZOOM_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_ZOOM_NAV_HOLD_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_ZOOM_REVEAL_T
import me.him188.ani.app.ui.foundation.tv.TV_HERO_ZOOM_TAIL_T
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.graphics.GraphicsLayerScope
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniScrollableTextDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.focus.tvSwallowKeysWhenLeaving
import me.him188.ani.app.ui.foundation.navigation.LocalNavEntryContentKey
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.navigation.OnReturnToForeground
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import me.him188.ani.app.ui.foundation.AniImageLoadSuccess
import com.kmpalette.color
import com.kmpalette.palette.graphics.Palette
import kotlinx.collections.immutable.toImmutableList
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.tmdbBackdropOriginalSizeUrl
import me.him188.ani.app.domain.episode.SetEpisodeCollectionTypeRequest
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.tools.ColorUtils
import me.him188.ani.app.ui.foundation.AniDisplayTier
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.session.TvNavigationSideRail
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.restoreFocusAfter
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.tv.TV_CAPSULE_SIZE
import me.him188.ani.app.ui.foundation.tv.tvBackdropFadeToBlackStops
import me.him188.ani.app.ui.foundation.tv.tvBackdropFadeFromBlackStops
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_LEFT_FADE_START
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_LEFT_FADE_END
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_BOTTOM_FADE_START
import me.him188.ani.app.ui.foundation.tv.TV_FOCUSED_CONTAINER_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_ICON_GLYPH_SIZE
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.tv.TvCapsuleButton
import me.him188.ani.app.ui.foundation.tv.TvZoomedImageOverlay
import me.him188.ani.app.ui.foundation.tv.rememberTvImageZoomState
import me.him188.ani.app.ui.foundation.tv.tvImageZoomKeys
import me.him188.ani.app.ui.foundation.session.buildTvRailItems
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.GLASS_CONTAINER_ALPHA
import me.him188.ani.app.ui.foundation.theme.glassContainerColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_air_date_format
import me.him188.ani.app.ui.lang.subject_details_aliases
import me.him188.ani.app.ui.lang.subject_details_total_episodes
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_episodes
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.lang.subject_details_login_to_collect
import me.him188.ani.app.ui.lang.subject_details_no_summary
import me.him188.ani.app.ui.lang.subject_details_related_subjects
import me.him188.ani.app.ui.lang.subject_details_show_more
import me.him188.ani.app.ui.lang.subject_details_stat_collected
import me.him188.ani.app.ui.lang.subject_details_stat_watching
import me.him188.ani.app.ui.lang.subject_details_stat_wish
import me.him188.ani.app.ui.subject.AiringLabel
import me.him188.ani.app.ui.subject.SubjectProgressState
import me.him188.ani.app.ui.subject.rememberSubjectStatusStrings
import me.him188.ani.app.ui.subject.collection.components.EditableSubjectCollectionTypeDialogsHost
import me.him188.ani.app.ui.subject.collection.components.EditableSubjectCollectionTypeState
import me.him188.ani.app.ui.subject.collection.components.SubjectCollectionActions
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.app.ui.subject.collection.components.SubjectCollectionActionsForCollect
import me.him188.ani.app.ui.subject.collection.components.renderCollectionTypeAsCurrent
import me.him188.ani.app.ui.subject.details.components.AnimatedGradientBackground
import me.him188.ani.app.ui.subject.details.components.COVER_WIDTH_TO_HEIGHT_RATIO
import me.him188.ani.app.ui.subject.details.components.RatingHistogram
import me.him188.ani.app.ui.subject.details.components.RelatedSubjectsLazyRow
import me.him188.ani.app.ui.subject.details.components.rememberNavigateToRelatedSubject
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.subject.details.sections.CharactersSection
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.ui.subject.details.sections.ReviewsSummarySection
import me.him188.ani.app.ui.subject.details.sections.TV_REVIEW_HEADER_GAP
import me.him188.ani.app.ui.subject.details.sections.SectionHeader
import me.him188.ani.app.ui.subject.details.sections.StaffSection
import me.him188.ani.app.ui.subject.details.sections.groupThousands
import me.him188.ani.app.ui.subject.details.sections.SubjectRatingSummary
import me.him188.ani.app.ui.subject.details.sections.DETAILS_TEXT_CONTENT_PADDING
import me.him188.ani.app.ui.subject.details.sections.DETAILS_TEXT_END_RESERVE
import me.him188.ani.app.ui.subject.details.sections.MENU_CONTAINER_ALPHA
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeCarousel
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeGridDropdown
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsState
import me.him188.ani.app.ui.subject.renderSubjectSeason
import me.him188.ani.app.ui.user.SelfInfoUiState
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.logging.logger
import org.jetbrains.compose.resources.stringResource

/**
 * [SubjectDetailsTvPage] 的首屏占位: 详情数据还在路上时, 先用**手上已有的东西**按目标页的
 * 版式画出 Hero —— 背景图取自进程内热缓存 ([TmdbImageService.peekBackdropUrl], 上一个页面
 * 聚焦这张卡时就查过), 标题取自导航占位.
 *
 * 目的是消掉"点一张卡看三段画面"里的第一段. 原先这里是居中转圈的空白页, 于是依次看到:
 * 转圈 -> 整页换成真布局 -> 背景图再淡进来. 现在落地即有大图和标题, 且**位置与真布局完全一致**
 * (共用 [MultiColumnScaffold] + 同一套留白/字号/阴影), 真布局到达时 Hero 区域原地不动,
 * 只有信息带与下方区块补上来.
 *
 * 背景图的三态判定与 [SubjectDetailsTvPage] 逐字对应 (有图 / 确认无图回退竖版封面 / 未解析
 * 则不放图), 否则两边会在切换的一瞬互相跳变.
 *
 * 冷启 (热缓存里没有) 时只有标题, 没有转圈 —— 短等待放个转圈反而更显慢; 真的久等
 * ([SLOW_LOAD_SPINNER_DELAY] 之后) 才把转圈补出来, 免得慢网络下看着像卡死.
 */
@Composable
fun SubjectDetailsTvLoadingPlaceholder(
    subjectInfo: SubjectInfo?,
    layoutParams: SubjectDetailsLayoutParams,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val tmdbImageService = remember { GlobalKoin.get<TmdbImageService>() }
    // 三态: resolved=false 还没解析过 (等), resolved=true 且 url=null 确认无图 (回退封面)
    //
    // **必须是 derivedStateOf 而不是 remember 收值**: peek 读的是进程内热表, 那是一张
    // SnapshotStateMap —— 解析结果落表时组合会被唤醒, 但 remember 在 subjectId 没变时不会重跑
    // 里面的读, 于是首次读到 null 之后, 稍后到达的 backdrop 永远显示不出来 (进详情页有门控,
    // 但门控超时先放行的慢网络下正好落在这个窗口里, 表现成占位页只有标题).
    // 用 derived 而不是直接裸读: 那张表按 subjectId 存**所有**条目, 邻居预取的任意写入都会
    // 让读过它的作用域失效; derived 只在这一条的值真的变了才往下传播.
    val heroBackdrop = subjectInfo?.let { info ->
        remember(info.subjectId, info.imageLarge) {
            derivedStateOf {
                tmdbImageService.peekBackdropUrl(info.subjectId)
                    ?: info.imageLarge.takeIf {
                        tmdbImageService.peekBackdropResolved(info.subjectId) && it.isNotBlank()
                    }
            }
        }
    }
    val heroBackdropUrl = heroBackdrop?.value

    // 放大会话进行中 (见 TvHeroZoomLayer): 图与底色由下面那一层画, 占位页透明, 只画大标题 (跟着会话进度平移) 与侧边栏
    val zoomSession = TvHeroZoomHandoff.session
    val underZoom = zoomSession != null
    // 放大进来的占位页, 会话可能在条目信息到达之前就结束 (放大层等不到真页, 自己收场): 之后这一页照常画背景、底色与
    // 加载转圈, 但标题和侧边栏不能跟着消失 —— 标题此时只有导航时从列表页带来的那一份, 侧边栏本来就在同一位置
    val enteredWithZoom = remember { underZoom }
    val navTitle = remember { zoomSession?.title }
    val abortFade = rememberTvZoomAbortFade(zoomPathChosen = enteredWithZoom, zoomSession = zoomSession)

    var slowLoad by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_LOAD_SPINNER_DELAY)
        slowLoad = true
    }

    val pad = layoutParams.contentHorizontalPadding
    val scrollState = rememberScrollState()
    Box(modifier.fillMaxSize().graphicsLayer { alpha = abortFade.value }) {
        MultiColumnScaffold(
            layoutParams.copy(
                contentHorizontalPadding = 0.dp,
                contentTopPadding = (pad - TV_HERO_TITLE_TOP_TRIM).coerceAtLeast(0.dp),
            ),
            Modifier,
            showTopBar = false,
            windowInsets,
            scrollState = scrollState,
            backgroundOverlay = {
                // 放大会话进行中背景由放大那一层画 (见 TvHeroZoomLayer), 这里组合着但不画: 会话结束那一帧直接显示 ——
                // 新图片实例头一两帧是空的, 到那时才组合会闪一下
                heroBackdropUrl?.let { url -> TvHeroBackdrop(url, scrollState, onSuccess = {}, hidden = underZoom) }
            },
            containerColor = if (underZoom) Color.Transparent else AniThemeDefaults.pageContentBackgroundColor,
        ) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(start = pad)) {
                // 与 TvHeroBlock 的标题列逐项对齐 (top 8dp / headlineLarge / 白字 + 柔和黑影 /
                // 两行截断), 真布局到达时标题不位移
                val titleShadow = with(LocalDensity.current) {
                    Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(0f, 1.dp.toPx()),
                        blurRadius = 6.dp.toPx(),
                    )
                }
                Column(
                    Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        subjectInfo?.displayName ?: navTitle.orEmpty(),
                        Modifier.tvHeroZoomTitleShift(zoomSession),
                        style = MaterialTheme.typography.headlineLarge.copy(shadow = titleShadow),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (slowLoad && !underZoom) {
                        CircularProgressIndicator(
                            Modifier.padding(top = 16.dp).size(28.dp),
                            color = Color.White,
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
        }
        // 放大进来的占位页也画侧边栏 (列表页那条已被放大那一层的底色盖住, 真页那条要等它组合; 会话提前结束也留着): 只是样子, 焦点
        // 不许进来 —— 全局兜底会把焦点送到页面上唯一可聚焦的节点, 侧边栏一持焦就展开. 不用 canFocus 开关 (见
        // 2026-09-10 那次: 停用一个已在焦点树里的节点会把整页焦点吞掉), 用焦点组的 onEnter 拒绝进入
        if (enteredWithZoom) {
            Box(
                Modifier.align(Alignment.CenterStart).zIndex(1f)
                    // 跟着整屏底色一起渐入: 按确认到图上屏之间 (scrimAlpha = 0) 本来会直接蹦在列表页上, 而同页的大标题
                    // 早就有"会话起跑前不画"的保护 (见 tvHeroZoomTitleShift), 这一处漏了
                    .graphicsLayer { alpha = zoomSession?.scrimAlpha ?: 1f }
                    .focusGroup()
                    .focusProperties { onEnter = { cancelFocusChange() } },
            ) {
                TvDetailsSideRail(onExitToHero = {}, scrimColor = tvDetailsRailScrimColor())
            }
        }
    }
}

/**
 * 导航那一刻按"会放大"选了**不淡入**的转场, 放大却没起跑就放弃了 —— 放大层等图超出加载预算 (长按快速翻卡后立刻按确认常见),
 * 或会话对不上真页 (背景图地址变了): 本页从 0 淡入, 否则整页硬切出来像闪一下 (2026-09-15 用户). 返回的 alpha 在绘制里读.
 * 放弃那一次组合里就换成从 0 起的动画, 整页露出来的第一帧已是 0. 起跑过的会话不算 (放大层的图已在屏上, 接手照旧).
 */
@Composable
private fun rememberTvZoomAbortFade(
    zoomPathChosen: Boolean,
    zoomSession: TvHeroZoomHandoff.Session?,
): Animatable<Float, AnimationVector1D> {
    var sawStart by remember { mutableStateOf(false) }
    LaunchedEffect(zoomSession) {
        val s = zoomSession ?: return@LaunchedEffect
        snapshotFlow { s.started }.first { it }
        sawStart = true
    }
    val aborted = zoomPathChosen && zoomSession == null && !sawStart
    val fade = remember(aborted) { Animatable(if (aborted) 0f else 1f) }
    LaunchedEffect(fade) { fade.animateTo(1f, tween(TV_DETAILS_ABORT_FADE_MILLIS)) }
    return fade
}

/** 首屏占位里补出加载转圈的等待阈值: 短等待放转圈反而更显慢. */
private val SLOW_LOAD_SPINNER_DELAY = 800.milliseconds

/**
 * TV (10-foot UI) 条目详情页: 单列信息流, 参考主流 TV 流媒体应用的结构 —
 * Hero 首屏 (背景封面 + 标题/元数据/简介/主操作) + 各内容区块顺序下排.
 *
 * 与 [SubjectDetailsMultiColumnPage] 内容一致, 仅重排:
 * 原侧栏的作品信息表 / 收藏统计 / 标签下沉到"关联作品"之后的"作品信息"块.
 *
 * 首屏数据未到时的占位见 [SubjectDetailsTvLoadingPlaceholder].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SubjectDetailsTvPage(
    state: SubjectDetailsState,
    selfInfo: SelfInfoUiState,
    layoutParams: SubjectDetailsLayoutParams,
    onPlay: (episodeId: Int) -> Unit,
    onClickTag: (Tag) -> Unit,
    onClickLogin: () -> Unit,
    /** 参数 = 打开后落在第几条评论. */
    onShowComments: (initialFocusIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    onEpisodeCollectionUpdate: (SetEpisodeCollectionTypeRequest) -> Unit = {},
    showTopBar: Boolean = true,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    backgroundPalette: Palette? = null,
    onClickOpenExternal: () -> Unit = {},
    onCoverImageSuccess: (AniImageLoadSuccess) -> Unit = {},
    onClickCache: (() -> Unit)? = null,
    /**
     * 视频背景模式 (TV 播放器内嵌): 页面底色透明, 不放渐变/TMDB 背景图,
     * 改为对下层视频画遮罩 —— 首屏只压底部, 滚动后整屏变暗 (与独立详情页视觉一致).
     */
    videoBackground: Boolean = false,
    /** 内嵌变体介绍页顶部按上键的回调 (回到播放器选集条); null 不处理. */
    onVideoBackgroundExitUp: (() -> Unit)? = null,
) {
    // 页面间过渡由导航转场承担 (NavigationMotionScheme.calculateCrossfade, 同步 crossfade):
    // 滚动归零/焦点落位等状态恢复发生在入场淡入的头几帧, 无可见闪动. 页内不再叠加渐显
    // (两层透明度相乘会让入场页中途露出底色).

    // info 加载中: 显示 TV 布局自己的加载占位, 不 return 空白 —— 调用方在 TV 上
    // 不等 info 就进入本页 (避免先闪单栏旧布局), 加载通常一瞬.
    val info = state.info
    if (info == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val presentation by state.presentation.collectAsStateWithLifecycle()
    // 卡片流用保持数据源顺序的全量列表: 特别篇按序号插在正片之间 (尸鬼 20.5 落在 20 与 21
    // 中间), 与播放器选集列表看到的顺序一致. 它们的 TMDB 剧照/简介/时长本来就已按全量分集
    // 匹配好 (SubjectDetailsStateFactory 传的是 collection.episodes), 这里只是把先前没人取的
    // 那几个 key 用起来. 选集网格仍要正片/特别篇分组, 故两份都留着.
    val episodes = presentation.episodeListUiState.allEpisodes
    val mainEpisodes = presentation.episodeListUiState.mainEpisodes
    val specialEpisodes = presentation.episodeListUiState.otherEpisodes
    // "当前集"只在正片里找: 特别篇通常一直是未看状态, 算进来会让看完正片的条目
    // 把 SP 当成"下一集要看的", 进页面直接滚到那里
    val currentEpisodeId = remember(mainEpisodes) { mainEpisodes.firstOrNull { !it.isDoneOrDropped }?.episodeId }

    // 角色/制作人员/作品信息: 仅独立页组合完整区块; 内嵌变体不收集 (全量名单由
    // 播放器胶囊面板承担, 这里收集只会白发请求)
    val exposedCharacters =
        if (videoBackground) null else state.exposedCharactersPager.collectAsLazyPagingItemsWithLifecycle()
    val allCharacters =
        if (videoBackground) null else state.charactersPager.collectAsLazyPagingItemsWithLifecycle()
    val totalCharactersCount by state.totalCharactersCountState
    val exposedStaff =
        if (videoBackground) null else state.exposedStaffPager.collectAsLazyPagingItemsWithLifecycle()
    val allStaff =
        if (videoBackground) null else state.staffPager.collectAsLazyPagingItemsWithLifecycle()
    val totalStaffCount by state.totalStaffCountState
    val related = state.relatedSubjectsPager.collectAsLazyPagingItemsWithLifecycle()
    val comments = state.subjectCommentState.list.collectAsLazyPagingItemsWithLifecycle()
    val commentCount = state.subjectCommentState.count

    // 水平留白由本页面各区块自理 (Hero 背景图需贴屏幕边缘出血), 不在滚动容器上统一加
    val pad = layoutParams.contentHorizontalPadding
    // TMDB 横版背景图, 三态: 结果未出时 Hero 不放任何图并按"有图"样式排版 (等待,
    // 常见情形图直接淡入零跳变); 确认无图才回退到竖版封面. 若直接用 null 当加载中,
    // 每次进页都会先闪一下回退布局再切到有图, 视觉上像页面跳变.
    //
    // 首帧初值取进程内热缓存 (TmdbImageService.peekBackdropUrl): 上一个页面 (探索/搜索/
    // 时间表) 聚焦这张卡时就已经查过同一条目, 结果同步可读. 拿到就等于**首帧即有图** ——
    // 那张图还在 Coil 内存缓存里, Hero 一进场就是满的; 下面的 flow 仍照常收, 只是从
    // "决定首屏长什么样"退化成"后台校正". 热缓存没有 (冷启/别处进来) 时行为与从前一致.
    val tmdbImageService = remember { GlobalKoin.get<TmdbImageService>() }
    var backdropResolved by remember(state) {
        mutableStateOf(!videoBackground && tmdbImageService.peekBackdropResolved(state.subjectId))
    }
    var tmdbBackdropUrl by remember(state) {
        mutableStateOf(if (videoBackground) null else tmdbImageService.peekBackdropUrl(state.subjectId))
    }
    LaunchedEffect(state) {
        // 视频背景模式不放背景图, 不必发起 TMDB 请求
        if (videoBackground) return@LaunchedEffect
        state.tmdbBackdropUrlFlow.collect {
            tmdbBackdropUrl = it
            backdropResolved = true
        }
    }
    // 无 TMDB 横版图时的回退: 拿竖版封面当全屏背景 (Crop 默认居中 = 取海报中间那条横带).
    // 封面用 Bangumi 的 l 档, 即上传原图 (实测 1400~2700 px 宽), 4K 面板上放大 1.4~2.7 倍,
    // 压着 scrim 与底缘渐隐看不出来; 更高清晰度没有来源 (c/m 档是 150/100 px 缩略图,
    // /r/<宽>/ 缩放前缀对封面路径返回 400, TMDB 那边本项目只取 backdrop 不取 poster).
    //
    // 关键: 回退后**排版与有图时完全一致** —— 标题白字浮在图上, 简介留给"作品信息"子页,
    // 右侧不再单独摆一张竖版封面 (原先那套"无图版式"只在连封面也没有时才出现).
    // 只在 backdropResolved 之后才用它, 否则会先闪一下封面再被 TMDB 图换掉.
    val heroBackdropUrl = tmdbBackdropUrl
        ?: info.imageLarge.takeIf { backdropResolved && it.isNotBlank() }
    // 放大转场 (TvHeroZoomHandoff.Session): 导航那一刻若判定会放大, 放大由 TvHeroZoomLayer 那一层画 —— 它挂在占位页 /
    // 真页的切换之外, 两者切换时不重建. 本页在会话进行中: 底色透明、渐变底不画、自己的背景图组合着但不显示 (提前把
    // 位图加载好), 只画大标题 (跟着会话进度从列表页的位置平移过来) 与侧边栏; 放大到位且自己的背景图已就位时接手 ——
    // 同一张图同一位置, 那一层撤掉, 其余内容一次性出现 (放大的是另一张图时先淡入换图, 见 handoff). 首屏那几个几百毫秒的
    // 组合帧因此都落在放大之后.
    val zoomSession = TvHeroZoomHandoff.session
        ?.takeIf { !videoBackground && it.subjectId == state.subjectId && it.detailsUrl == heroBackdropUrl }
    val underZoom = zoomSession != null
    val enteredWithZoom = remember { underZoom }
    val abortFade = rememberTvZoomAbortFade(
        zoomPathChosen = remember { TvHeroZoomHandoff.session?.subjectId == state.subjectId },
        zoomSession = zoomSession,
    )
    var revealed by remember { mutableStateOf(!enteredWithZoom) }
    var ownBackdropLoaded by remember { mutableStateOf(false) }
    // 换图接手 (放大的是列表页的单集剧照, 本页是整部背景): 自己的背景图在放大层上淡进来, 进度只在绘制里读
    var crossFading by remember { mutableStateOf(false) }
    val crossImageAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // 会话在但对不上本页 (背景图 URL 变了等): 放弃这次放大, 本页照常画
        val s = TvHeroZoomHandoff.session
        if (s != null && zoomSession == null) {
            zoomLogger.info { "Details entry: zoom abandoned (session doesn't match details page), fade in" }
            TvHeroZoomHandoff.endSession(s)
        }
    }
    // 接手: 放大到位 + 自己的背景图已加载 (以 alpha 0 组合着, 位图就在内存里; 显示与撤层落在同一帧).
    // 会话一结束 (接手 / 放弃 / 看门狗) 就放行其余内容
    LaunchedEffect(zoomSession) {
        val s = zoomSession
        if (s == null) {
            if (enteredWithZoom) revealed = true
            return@LaunchedEffect
        }
        snapshotFlow { s.t >= 1f && ownBackdropLoaded }.first { it }
        if (s.url != s.detailsUrl && !s.swapped) {
            // 放大的是另一张图、途中没压暗换过来 (起跑时详情页那张还没加载好): 先把自己的图淡进来再撤层, 不硬换. 其余内容照旧等会话结束一次出现, 首屏以下区块那几个
            // 重组帧因此落在淡入之后, 不卡这一段
            s.handingOver = true
            crossFading = true
            crossImageAlpha.animateTo(1f, tween(TV_HERO_ZOOM_CROSS_IMAGE_FADE_MILLIS))
        }
        // 缩回已经开始 (放大到位那一刻按了返回): **不结束会话** —— 会话一结束 covering 就为假, 而缩回层还在等图
        // 与帧头 (最多 150ms), 这段窗口里本页会整页露出来 (首屏按钮都在), 随即被缩回层盖掉 = 闪一下
        // (用户 2026-09-16). 会话改由缩回层上屏那一刻结束 (见 TvHeroZoomHandoff.armShrink).
        if (TvHeroZoomHandoff.shrink?.fromSession === s) return@LaunchedEffect
        TvHeroZoomHandoff.endSession(s)
    }
    // 首屏信息带 (副标题 / 圆钮 / 播放按钮 / 标签 / 评分) 在放大尾段就先组合好, 显示前不画 (TvHeroBlock.bodyHidden):
    // 显示那一帧只翻透明度, 不再现组合. 2026-09-10 追踪: 接手后现组合的那一帧 90ms, 首屏晚这么久才出来. 尾段已在减速,
    // 这一帧停在那里几乎看不出来 (见 TV_HERO_ZOOM_TAIL_T); 与本页首次组合错开一帧, 两份重活不叠在一帧
    var bodyEarly by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val s = zoomSession ?: return@LaunchedEffect
        snapshotFlow { s.t >= TV_HERO_ZOOM_TAIL_T }.first { it }
        withFrameNanos { }
        bodyEarly = true
    }
    // 放大落地途中 (位移到 TV_HERO_ZOOM_REVEAL_T) 就显示首屏信息带: 停稳时按钮已在、可以直接按; 放大层照常画完最后一段.
    // 派生状态: 进度每帧在变, 本页只在这个布尔翻转时重组一次
    val uiEarly by remember(zoomSession) {
        derivedStateOf { zoomSession?.let { it.t >= TV_HERO_ZOOM_REVEAL_T } == true }
    }
    // 接手后先只组合首屏 (hero 信息带), 首屏以下的区块推迟, 且**分三帧**放出: 1 选集页 / 2 角色 + 制作人员 / 3 作品信息 +
    // 关联 + 评价. 2026-09-10 追踪接手那一帧 262ms, 大半是首屏看不见的区块; 只推迟一帧的话仍是一整帧 80~125ms, 正撞在
    // "停稳后第一下按键"上 (用户要停稳即可操作). 分三帧后 UI 出现后 300ms 内最长帧 39~47ms (2026-09-13 Shield AOT).
    // 这些区块都在首屏之下, 晚几帧出现看不见; 角色区没轮到时先放等高骨架, 布局不跳. 只在放大进来时这么做 (常规进页照旧一次组合)
    // 区块组合出来之前按的下键由首屏信息带扣住, 选集页出来再把焦点送过去 (focusEpisodesWhenReady)
    var sectionsReady by remember { mutableStateOf(!enteredWithZoom) }
    var sectionsStage by remember { mutableStateOf(if (enteredWithZoom) 0 else 3) }
    var focusEpisodesWhenReady by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (!revealed || sectionsReady) return@LaunchedEffect
        // 缩回已经开始: 本页正在离场 (快速路径下只是藏着, 组合还在), 这三帧的区块组合全是白干, 而且恰好落在缩回起步的
        // 那几帧上 —— "刚放大完成就立刻返回"正是这条路 (2026-09-16 审查). 预画早就有同一道闸, 这里补齐
        // **每一阶段都重查**: 只在开头查一次的话, 查过之后用户才按返回, 后面两帧的区块组合照样跑进缩回动画里
        // (快速路径保留详情页组合, 这些重活是实打实的) —— 2026-09-16 审查
        if (TvHeroZoomHandoff.shrinking) return@LaunchedEffect
        withFrameNanos { }
        sectionsReady = true
        sectionsStage = 1
        withFrameNanos { }
        if (TvHeroZoomHandoff.shrinking) return@LaunchedEffect
        sectionsStage = 2
        withFrameNanos { }
        if (TvHeroZoomHandoff.shrinking) return@LaunchedEffect
        sectionsStage = 3
    }
    // 看门狗: 无论如何 (图没到 / 接手条件凑不齐) 都在限时内放行, 别让页面停在只有标题的状态
    LaunchedEffect(Unit) {
        if (revealed) return@LaunchedEffect
        delay(TV_HERO_REVEAL_WATCHDOG_MILLIS)
        zoomSession?.let { TvHeroZoomHandoff.endSession(it) }
        revealed = true
    }
    // TMDB 分集缩略图 (episodeId -> URL); 无图的集回退纯文字卡
    val tmdbEpisodeStills by state.tmdbEpisodeStillsFlow.collectAsStateWithLifecycle(emptyMap())
    // 各集播放进度 (episodeId -> 0..1), 选集卡片底部进度条
    val playProgress by state.playProgressFlow.collectAsStateWithLifecycle(emptyMap())
    // TMDB 分集时长 (episodeId -> 分钟), 聚焦集信息行右侧
    val episodeRuntimes by state.tmdbEpisodeRuntimesFlow.collectAsStateWithLifecycle(emptyMap())
    // TMDB 本地化分集简介 (episodeId -> 简介), 排在 Bangumi 简介前展示
    val episodeOverviews by state.tmdbEpisodeOverviewsFlow.collectAsStateWithLifecycle(emptyMap())
    // Bangumi 简介整段无中文 (全日文/纯英文) 时用 TMDB 中文整部简介替换; null = 用原文
    val tmdbSummaryOverride by state.tmdbSummaryOverrideFlow.collectAsStateWithLifecycle(null)
    // Ani 服务器简介为空时的 bgm.tv 兜底 (null = 结果未出, "" = bgm 也没有, 非空 = bgm 简介)
    val bangumiSummaryFallback by state.bangumiSummaryFallbackFlow.collectAsStateWithLifecycle(null)
    // 简介优先级: Ani 服务器 > bgm.tv (仅替代不合并) > TMDB 中文.
    // Ani 有简介时维持原逻辑 (全外文则被 TMDB 中文替换); Ani 为空时等 bgm.tv 结果 (未出结果先按
    // 空显示, 避免先闪 TMDB 再换成 bgm), bgm.tv 也没有才用 TMDB 中文兜底.
    val displaySummary = if (info.summary.isNotBlank()) {
        tmdbSummaryOverride ?: info.summary
    } else when (bangumiSummaryFallback) {
        null -> ""
        "" -> tmdbSummaryOverride.orEmpty()
        else -> bangumiSummaryFallback.orEmpty()
    }
    val scrollState = rememberScrollState()
    // 分页驱动器: 滚动量是"当前页起点 + 页内露出"的派生值, 整页只有它一个写者 (见 TvDetailsPager).
    // 换页照 Prime Video 淡入淡出 + 短距离滑动.
    val backdropFadePx = with(LocalDensity.current) { HERO_BACKDROP_FADE_DISTANCE.toPx() }
    val pager = remember(scrollState, backdropFadePx, videoBackground) {
        TvDetailsPager(scrollState, backdropFadePx, transitions = !videoBackground)
    }
    LaunchedEffect(pager) { pager.run() }

    // Hero 标签墙的状态: rememberSaveable 跨"点击标签→搜索→返回"保留 —
    // 返回本页时浏览模式不变, 焦点直接恢复到最后聚焦的那个标签上 (restorePending 标记)
    var tagsBrowseMode by rememberSaveable { mutableStateOf(false) }
    var focusedTagIndex by rememberSaveable { mutableStateOf(-1) }
    var tagsRestorePending by rememberSaveable { mutableStateOf(false) }

    // 最后一个真正持有过焦点的区块 (各区块 onFocused 上报). 两个用途: 返回键三级分层 (见下方
    // BackHandler 处的长注释) + **跨页返回时的落点恢复** (见下方进页 effect).
    // rememberSaveable 存 ordinal: 跳到全屏页时本页被 NavHost 销毁, 枚举本体在 commonMain 里
    // 没有默认 Saver.
    var backLevelOrdinal by rememberSaveable { mutableIntStateOf(TvDetailsSection.HERO.ordinal) }
    // 进页那一刻的快照: 非 HERO = 这是"返回本页"且离开前焦点在海报页以外的区块.
    // null = 新进本页 (或离开前就在海报页), 走原来的"落在海报页"路径
    val restoreSection = remember {
        TvDetailsSection.entries[backLevelOrdinal].takeIf { it != TvDetailsSection.HERO }
    }

    // 统一事件式焦点调度器: 进页 / 返回分层 / 弹层关闭 / 跨页恢复都只登记目标 key;
    // 节点未组合时请求悬挂到锚点 attach, 用户导航则在页面根取消在途请求.
    val anchors = rememberTvFocusScope()
    // 跨区块纵向导航路由 (见 [TvDetailsSectionNav]): 区块边缘元素的 上/下 键显式
    // 送焦点到相邻区块, 落点/存在性解析全在路由内, 每次组合从头登记.
    val sectionNav = remember { TvDetailsSectionNav() }
    // 只在本页还是前台时才兑现"回选集条" (内嵌变体的前台信号 = 播放器在栈顶且 layer 是详情层,
    // 见 TvEpisodeScreen): 详情层退场还要淡出 500ms, 期间子树若仍持焦, 每个上键都会再唤一次
    // 选集条 (2026-08-28 真机日志: 选集条收起后又自己弹出). 消费掉即可 —— 焦点落点由播放器侧
    // 的补发负责, 这里不用管.
    val exitTopForeground = LocalPageIsForeground.current
    sectionNav.onExitTop = onVideoBackgroundExitUp?.let { exitUp ->
        { if (exitTopForeground.value) exitUp() }
    }
    sectionNav.register(
        TvDetailsSection.HERO,
        if (videoBackground) anchors.episodesSummary else anchors.heroPlay,
    )
    sectionNav.register(TvDetailsSection.EPISODES, anchors.episodesCarousel)
    // 这三个区块原来只有 sectionNav 自己的请求器 (方向键路由用). 跨页返回要把焦点送回它们,
    // 而"送焦点"必须走等待锚点附着并确认真实到位的作用域 (返回时分页数据可能还没到, 区块
    // 那几帧不存在, 单发 requestFocus 会静默失败) —— 于是两边
    // 共用同一枚请求器: 这里注册, 区块容器照旧挂 sectionNav.entry(...)
    sectionNav.register(TvDetailsSection.CHARACTERS, anchors.charactersSection)
    sectionNav.register(TvDetailsSection.STAFF, anchors.staffSection)
    sectionNav.register(TvDetailsSection.BELOW, anchors.belowSection)
    sectionNav.register(TvDetailsSection.REVIEWS, anchors.reviewsSection)
    sectionNav.setPresent(TvDetailsSection.HERO, true)
    sectionNav.setPresent(TvDetailsSection.EPISODES, !videoBackground)
    // 角色/制作人员区块自身在空数据时不渲染 (Section 内部 early return), 存在性同步该条件
    sectionNav.setPresent(
        TvDetailsSection.CHARACTERS,
        exposedCharacters != null && exposedCharacters.itemCount > 0,
    )
    sectionNav.setPresent(
        TvDetailsSection.STAFF,
        exposedStaff != null && exposedStaff.itemCount > 0,
    )
    sectionNav.setPresent(
        TvDetailsSection.BELOW,
        // 与该区块的组合条件一致 (无内容不组合). 评价已挪到自己那一页, 不再算进这里
        related.itemCount > 0,
    )
    sectionNav.setPresent(TvDetailsSection.REVIEWS, !videoBackground && comments.itemCount > 0)
    // 区块 -> 进入落点锚点. **唯一一份**: 进页恢复、焦点补救、跨区块方向键三处共用 (原来抄了两份).
    val anchorFor: (TvDetailsSection) -> TvDetailsFocusAnchor = { section ->
        when (section) {
            // 选集页: 有轮播就回轮播 (行内 focusRestorer 落到上次那张卡), 否则回简介块
            TvDetailsSection.EPISODES ->
                if (videoBackground || episodes.isEmpty()) TvDetailsFocusAnchor.EPISODES_SUMMARY
                else TvDetailsFocusAnchor.EPISODES_CAROUSEL

            TvDetailsSection.CHARACTERS -> TvDetailsFocusAnchor.CHARACTERS_SECTION
            TvDetailsSection.STAFF -> TvDetailsFocusAnchor.STAFF_SECTION
            TvDetailsSection.BELOW -> TvDetailsFocusAnchor.BELOW_SECTION
            TvDetailsSection.REVIEWS -> TvDetailsFocusAnchor.REVIEWS_SECTION
            TvDetailsSection.HERO ->
                if (videoBackground) TvDetailsFocusAnchor.EPISODES_SUMMARY else TvDetailsFocusAnchor.HERO_PLAY
        }
    }
    // 跨区块方向键的送焦也走调度器 (见 TvDetailsSectionNav.send)
    sectionNav.send = { section -> anchors.request(anchorFor(section)) }
    sectionNav.stepping = { pager.isStepping }
    sectionNav.page = { pager.displayedPage ?: -1 }
    // 换页闸期间按的那一下**推迟**到闸开再跑 (见 TvDetailsSectionNav.defer), 只保留最后一次:
    // 闸是为了"一页一页地走", 不是吃掉输入 (用户 2026-09-16 "连按两下第二下都被吞")
    val stepScope = rememberCoroutineScope()
    val pendingStep = remember { mutableStateOf<Job?>(null) }
    val deferStep = remember(pager) {
        { block: () -> Unit ->
            pendingStep.value?.cancel()
            pendingStep.value = stepScope.launch {
                delay(pager.stepRemaining)
                block()
            }
        }
    }
    sectionNav.defer = deferStep
    // 返回键的一步 (闸推迟后要原样重跑, 故做成可重入的 lambda; 内容在下面两个 BackHandler 都声明完之后填,
    // 它要用到那边的缩回 / 导航状态). 键到达时组合早已跑完, 不会读到占位值
    val backStep = remember { mutableStateOf({}) }

    // 标签墙跨页恢复目标 (进页那一刻的快照; -1 = 无). 菜单开着离开的情形由菜单自理.
    val tagRestoreIndex = remember {
        if (tagsRestorePending && !tagsBrowseMode && focusedTagIndex >= 0) focusedTagIndex else -1
    }

    // 进入页面时初始焦点给 Hero 区的播放按钮.
    // 过去初始焦点由左上角返回按钮提供, 该按钮在 TV 上已移除.
    // 例外: 从标签跳转的搜索页返回时, 恢复到离开前聚焦的标签.
    LaunchedEffect(Unit) {
        // 标签菜单开着离开的: 菜单重新展开后自理初始焦点 (Popup 独立焦点域)
        if (tagsRestorePending && tagsBrowseMode) return@LaunchedEffect
        val target = when {
            tagRestoreIndex >= 0 -> TvDetailsFocusAnchor.TAG_WALL

            // 返回本页且离开前在海报页以外的区块: 焦点回那个区块.
            // 区块入口请求器与 sectionNav 共用 (见上方 register), 送焦点走事件驱动的锚点调度器 ——
            // 返回时区块的存在性还取决于分页数据 (那几帧可能还没 present 出来), 单发 requestFocus
            // 会静默失败
            restoreSection != null -> when (restoreSection) {
                // 选集页: 有轮播就回轮播 (行内 focusRestorer 落到上次那张卡), 否则回简介块
                TvDetailsSection.EPISODES ->
                    if (videoBackground || episodes.isEmpty()) TvDetailsFocusAnchor.EPISODES_SUMMARY
                    else TvDetailsFocusAnchor.EPISODES_CAROUSEL

                TvDetailsSection.CHARACTERS -> TvDetailsFocusAnchor.CHARACTERS_SECTION
                TvDetailsSection.STAFF -> TvDetailsFocusAnchor.STAFF_SECTION
                TvDetailsSection.BELOW -> TvDetailsFocusAnchor.BELOW_SECTION
                TvDetailsSection.REVIEWS -> TvDetailsFocusAnchor.REVIEWS_SECTION
                TvDetailsSection.HERO -> TvDetailsFocusAnchor.HERO_PLAY // takeIf 已排除, 仅穷举
            }

            // 播放器内嵌变体: 首屏是介绍页 (选集条已移入播放器控制层, 不在本页),
            // 进入焦点给简介块 ("暂无信息"兜底保证恒可聚焦)
            videoBackground -> TvDetailsFocusAnchor.EPISODES_SUMMARY

            else -> TvDetailsFocusAnchor.HERO_PLAY
        }
        // 新进本页才把滚动归零: 返回本页时 rememberScrollState 恢复的正是离开时的位置, 而焦点
        // 也回同一个区块 —— 两头一致, 全程没有可见位移.
        //
        // (原先无条件归零是为了消掉另一种回跳: 位置恢复到旧区块、焦点却给海报页, 于是先显示
        //  旧位置再滑回顶部. 现在焦点跟着位置一起恢复, 这个理由不成立了.)
        if (restoreSection == null) scrollState.scrollTo(0)
        anchors.request(target)
        if (tagRestoreIndex >= 0) tagsRestorePending = false
    }

    // 当前该落在哪个区块 (按最后一个真正持焦的区块算), 下面两处补救共用.
    // **必须包 rememberUpdatedState**: 两个消费者都在 LaunchedEffect(Unit) / 一次性挂接的
    // 回调里, 直接捕获会把首次组合的闭包冻结住 —— episodes 永远是进页时的空列表, 于是补救
    // 把焦点送往 EPISODES_SUMMARY 这种"只在没分集时存在"的锚点, 悬挂到被全局兜底抢先
    // (2026-08-26 真机日志: 补落点 EPISODES_SUMMARY 时 episodes 明明已经 12 了).
    val currentFocusAnchor by rememberUpdatedState {
        when {
            // 从标签跳搜索页回来的那一次: 与进页落点同一个判据, 回到离开前那个标签而不是播放按钮
            // (只在还没离开海报区块时算数; 人往下走过就按区块来)
            tagRestoreIndex >= 0 && backLevelOrdinal == TvDetailsSection.HERO.ordinal ->
                TvDetailsFocusAnchor.TAG_WALL

            else -> when (TvDetailsSection.entries[backLevelOrdinal]) {
                TvDetailsSection.EPISODES ->
                    if (videoBackground || episodes.isEmpty()) TvDetailsFocusAnchor.EPISODES_SUMMARY
                    else TvDetailsFocusAnchor.EPISODES_CAROUSEL

                TvDetailsSection.CHARACTERS -> TvDetailsFocusAnchor.CHARACTERS_SECTION
                TvDetailsSection.STAFF -> TvDetailsFocusAnchor.STAFF_SECTION
                TvDetailsSection.BELOW -> TvDetailsFocusAnchor.BELOW_SECTION
                TvDetailsSection.REVIEWS -> TvDetailsFocusAnchor.REVIEWS_SECTION
                TvDetailsSection.HERO ->
                    if (videoBackground) TvDetailsFocusAnchor.EPISODES_SUMMARY
                    else TvDetailsFocusAnchor.HERO_PLAY
            }
        }
    }

    // **组合活过一次导航时补落点**: 详情页可经标签/关联条目无限嵌套, 一路返回回来时 Nav3 常常
    // 保留着本页的组合 —— 上面那个 `LaunchedEffect(Unit)` 于是**一次都不会重跑**, 页面没有登记
    // 任何落点请求, 而焦点在被盖住期间早就没了.
    //
    // 后果不是"没有焦点"而是"焦点跑到侧边栏": 全局兜底往页面打的是一次无方向 requestFocus,
    // Compose 把 Enter 转成 Right 从**最左**做二维搜索, 最左正是本页左缘那条 overlay 导航栏,
    // 它一持焦就展开 —— 用户看到的是"返回后侧边栏自己弹出来", 按一下右键才回得来.
    //
    // 判据: 这条路径下 `TvFocusScope: 送焦请求悬挂` 一条都不会打 —— 请求压根没登记, 不是引擎
    // 没送到. 搜索/时间表/追番三页早就装了这个, 详情页是漏的那个.
    OnReturnToForeground("subject-details") {
        // 标签菜单开着离开的: 菜单重新展开后自理初始焦点 (Popup 独立焦点域), 同进页效应
        if (tagsBrowseMode) return@OnReturnToForeground
        // 不必判"焦点是不是已经在目标上": request() 对已真实持焦的目标是空操作, 不会抢
        anchors.request(currentFocusAnchor())
    }

    // **整页焦点丢了就立刻自己补**, 与搜索页同一套. 上一处只管"回到前台"那一个时刻, 而真机
    // 日志逮到的是后半段: 落点请求把焦点**成功**送到了标签墙 (`锚点获焦 key=TAG_WALL`), 0.58 秒
    // 后焦点却凭空没了 —— 且**没有任何失焦回调**. 焦点正常移走一定有回调, 没有回调就只能是
    // "节点被销毁把焦点带走了": Compose 清焦点时既不交给焦点祖先也不改派 (见
    // FocusTargetNode.onReset). 于是本页一个焦点都没有, 又走上面那条"跑到侧边栏"的老路.
    //
    // 锚点那层救不了这个: TAG_WALL 锚点挂在标签墙容器上, 被销毁的是容器**内部**的那个标签,
    // 容器还在 -> onAnchorDetached 不触发 -> TvFocusScope 仍以为本页持着焦.
    //
    // 只在"整页 hasFocus 由真变假"这一个事件上补一次, 不轮询; 页面不在前台时不补 —— 那是别的
    // 页面的焦点, 抢过来正是这套框架最该避免的事.
    var pageHasFocus by remember { mutableStateOf(false) }
    var pageHadFocus by remember { mutableStateOf(false) }
    val pageIsForeground = LocalPageIsForeground.current
    LaunchedEffect(Unit) {
        snapshotFlow { pageHasFocus }.collect { has ->
            if (has) {
                pageHadFocus = true
                return@collect
            }
            // 还没进过焦点: 那是进页那一次, 落点归进页效应管, 不是"丢了"
            if (!pageHadFocus || !pageIsForeground.value || tagsBrowseMode) return@collect
            anchors.request(currentFocusAnchor())
        }
    }

    // 返回键分层, 三级: 选集页之下的区域 (角色/制作人员/关联条目...)
    // 按返回先回到选集卡片; 选集页内按返回回到最顶上的海报页 (焦点回播放按钮);
    // 海报页再按返回才真正退出详情页. 纵向滚动均由聚焦驱动 (焦点进入哪一页, 见 TvDetailsPager).
    // 弹窗/阅读模式等自行消费返回键的场景优先级更高, 不会走到这里.
    //
    // 层级用"最后一个真正持有过焦点的区块"记忆 (各区块 onFocused 上报), 不读瞬时焦点, 也不读
    // 滚动位置, 因为这两个量在过渡期间都会说谎:
    //  - 焦点"不在页面任何地方"是独立状态 (弹窗是独立窗口, 开着期间宿主无焦点元素; 关闭后
    //    归还也要好几帧), 不能和"焦点在别处"合并 —— 合并后关弹窗那一下会被判成"在选集页
    //    下方", 于是把用户往下送回卡片, 白吃一次按键;
    //  - 滚动是动画量, 一次返回按下后仍 > 0 好几百毫秒, 而焦点已经落到上一层, 期间再按返回
    //    就会读到"层级在下方"这种不存在的组合, 表现为连按两次又回到原地.
    // (簿记本体 backLevelOrdinal 声明在页面顶部, 进页 effect 要读它)
    // 层级同样以**显示页**为准, 理由与方向键那处一样 (见 TvDetailsSectionNav.originOf): 返回键自己那句
    // `pager.goTo(HERO.page)` 只改页不改层级, 而层级要等焦点落到 hero、它的 onFocusChanged 才更新 ——
    // 中间这一段画面已在第一页而层级还说"在选集页", 于是再按一下返回又走一遍"回第一页"(原地不动),
    // 得按第三下才退得出去. 用 derivedStateOf 收窄: 换页每次都写 current, 但只有层级真的变了才重组本页
    val backLevel by remember(pager) {
        derivedStateOf {
            val remembered = TvDetailsSection.entries[backLevelOrdinal]
            val shown = pager.displayedPage
            if (shown == null || remembered.page == shown) remembered
            else TvDetailsSection.entries.first { it.page == shown }
        }
    }
    // 选集整页的简介是否渲染了展开按钮 (即简介被截断了): 决定卡片上键要不要指向它
    var summaryExpandPresent by remember { mutableStateOf(false) }
    BackHandler(enabled = backLevel != TvDetailsSection.HERO) {
        // 上一层还没走完就吞掉这一下: 与方向键同一道闸 (见 TvDetailsPager.isStepping). 返回键走的是自己的分层
        // 逻辑, 不经过 TvDetailsSectionNav, 所以那边的闸管不到它 —— 连按返回照样一路盖掉过渡, 中间层看不见
        // (用户 2026-09-16: "返回键似乎没有限流"). 这里只管**节奏**, 分层规则一行没动.
        if (pager.isStepping) deferStep { backStep.value() } else backStep.value()
    }
    // 放大进来、列表页还没被移出组合时按返回: 缩回列表页 hero 框再回去 (TvHeroZoomHandoff.Shrink, 由 TvHeroShrinkLayer 等图
    // 上屏后出栈); 缩不了 (列表页已离场 / 换过图) 就照常出栈淡出. 只在首屏层级, 与上一条互斥
    val navigator = LocalNavigator.current
    // 放大进来的这个导航条目 (记在条目上, 见 TvHeroZoomHandoff.isZoomEntry): 进播放器 / 别的页再回来, 本页组合是新的
    // (enteredWithZoom 为假), 按返回照样缩回. enteredWithZoom 本身不改 —— 它管的是进页那一次的揭幕节奏
    val entryKey = LocalNavEntryContentKey.current
    val stackedEntry = TvHeroZoomHandoff.isZoomEntry(entryKey)
    val backScope = rememberCoroutineScope()
    val backForeground = LocalPageIsForeground.current
    var fadingOut by remember { mutableStateOf(false) }
    // 首屏再按返回 = 退出本页 (缩回 / 淡出 / 硬出栈). 抽成 lambda: [backStep] 在闸推迟后重跑时也要能走到这一支
    val exitPage: () -> Unit = exitPage@{
        // 翻页过渡里按返回 (从第二页回首屏、焦点已落 hero, 但背景还在 450ms 变亮途中): 把此刻的实际亮度交过去,
        // 缩回从它续着走, 不然缩回层会先跳到全亮再缩 (见 Shrink.startFade)
        if (TvHeroZoomHandoff.beginShrink(state.subjectId, entryKey, pager.backdropFadeNow()) { navigator.popBackStack() }) {
            zoomLogger.info { "Details back: shrink begin" }
        } else if (stackedEntry) {
            // 叠放布局里出栈没有导航转场 (列表页那个布局不换), 直接出栈是硬切: 本页先淡出 (列表页此时照常画在下面) 再出栈.
            // 走到这里 = 放大没起跑就放弃了
            if (fadingOut) return@exitPage
            fadingOut = true
            zoomLogger.info { "Details back: fade out (cannot shrink, stacked)" }
            // 淡出期间本页吞掉非返回键 (见根节点 tvSwallowKeysWhenLeaving): 否则途中按确认进了播放器, 淡完那一下会把播放器退掉.
            // 出栈前再确认本页仍在栈顶, 只出一次 (2026-09-15 审查)
            backScope.launch {
                abortFade.animateTo(0f, tween(TV_DETAILS_ABORT_FADE_MILLIS))
                if (backForeground.value) navigator.popBackStack()
                else zoomLogger.info { "Details back: fade out finished but page no longer on top, not popping" }
            }
        } else {
            zoomLogger.info { "Details back: fade (cannot shrink)" }
            navigator.popBackStack()
        }
    }
    BackHandler(enabled = (enteredWithZoom || stackedEntry) && backLevel == TvDetailsSection.HERO) { exitPage() }

    // 返回键一步的实际内容 (声明见上面 backStep). **每次都重读 backLevel** —— 被闸推迟后跑时层级可能已经变了:
    // 在第二页连按两下返回, 第一下把页翻回首屏 (层级随之变 HERO), 推迟的第二下就该退出本页而不是原地再翻一次
    backStep.value = {
        when (backLevel) {
            TvDetailsSection.HERO -> exitPage()
            TvDetailsSection.EPISODES -> {
                pager.goTo(TvDetailsSection.HERO.page)
                anchors.request(TvDetailsFocusAnchor.HERO_PLAY)
            }
            // 聚焦轮播行 (focusRestorer 恢复到上次聚焦的卡片), 选集页随焦点吸附滚入.
            // 没有轮播可聚焦时 (内嵌变体的选集条在播放器控制层; 未开播条目连分集都没有)
            // 退而聚焦简介块 ("暂无信息"兜底保证它恒可聚焦), 返回键不至于无效
            else -> anchors.request(
                if (videoBackground || episodes.isEmpty()) TvDetailsFocusAnchor.EPISODES_SUMMARY
                else TvDetailsFocusAnchor.EPISODES_CAROUSEL,
            )
        }
    }

    // 选集快速跳转网格 (辅助入口, 轮播仍是主体): 上千集时逐格横向导航不现实
    var showEpisodeGrid by rememberSaveable { mutableStateOf(false) }
    // 长按角色/制作人员卡片放大看图: 状态在页面级 (大图是全屏层, 画在区块里会被滚动列裁掉).
    // 不用 rememberSaveable —— 开着期间一切按键都被吞掉, 走不到跳页那一步
    val imageZoom = rememberTvImageZoomState()
    // 网格菜单关闭后轮播要跳到的集 (菜单里最后聚焦的那格)
    var revealEpisodeId by remember { mutableStateOf<Int?>(null) }

    // 区块没组合时按的那一下下键 (见 sectionsReady): 选集页组合出来后把焦点送过去. 走锚点调度 (等目标附着再送), 不是直接 requestFocus
    val latestEpisodesEmpty by rememberUpdatedState(episodes.isEmpty())
    LaunchedEffect(Unit) {
        snapshotFlow { focusEpisodesWhenReady && sectionsStage >= 1 }.first { it }
        focusEpisodesWhenReady = false
        anchors.request(
            if (videoBackground || latestEpisodesEmpty) TvDetailsFocusAnchor.EPISODES_SUMMARY
            else TvDetailsFocusAnchor.EPISODES_CAROUSEL,
        )
    }
    // 选集页预画: 区块都组合完、再静一会儿 (TV_DETAILS_PREWARM_DELAY_MILLIS) 且页面还停在首屏时, 把选集页在可见范围里以 1% 不透明度画两帧.
    // 屏外的区块 HWUI 整块跳过, 从没画过; 冷启动后第一次往下翻时, 录制绘制命令与 GPU 第一次提交都挤在滚动开头 (2026-09-14 Sony 实测
    // 每轮停 1~2 次, 主线程每帧 14~18ms + 渲染线程提交 8~17ms). 预画把这笔挪到画面静止、没人按键的时候
    var episodesPrewarm by remember { mutableStateOf(false) }
    var episodesTopInRoot by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(Unit) {
        if (videoBackground) return@LaunchedEffect
        snapshotFlow { revealed && sectionsStage >= 3 }.first { it }
        delay(TV_DETAILS_PREWARM_DELAY_MILLIS)
        if (scrollState.value != 0 || scrollState.isScrollInProgress) return@LaunchedEffect // 用户已经往下走了, 用不着
        // 返回缩回正在跑: 本页这时只是藏着 (快速路径不销毁), 预画仍会在主线程录一遍整页绘制命令 ——
        // 恰好落在缩回那几帧里 (延时到点与按返回撞上就会发生, 2026-09-15 审查). 等下一次机会
        if (TvHeroZoomHandoff.shrinking) return@LaunchedEffect
        episodesPrewarm = true
        withFrameNanos { }
        withFrameNanos { }
        episodesPrewarm = false
    }

    BoxWithConstraints(
        modifier.graphicsLayer { alpha = abortFade.value }.tvSwallowKeysWhenLeaving { fadingOut }.tvImageZoomKeys(imageZoom).tvFocusNavSignal(anchors)
            // 见上方"整页焦点丢了就立刻自己补"
            .onFocusChanged { pageHasFocus = it.hasFocus },
    ) {
        // TV 上不渲染顶栏 (原本只为返回/主页/外链按钮而设, 已全部移除), 滚动内容直达屏幕上边缘.
        // 顶部留白 ≈ 内容左侧留白 (区块统一的水平留白, 海报页与下方区块左边界对齐),
        // 名义值要再减去标题上方的附加空白 (标题列自带 top 8dp + headlineLarge 行高
        // 顶部内衬约 12dp): 左边距贴的是字形左缘, 顶部也要贴字形上缘才对等.
        val contentTopPad = (pad - TV_HERO_TITLE_TOP_TRIM).coerceAtLeast(0.dp)
        // Hero 区块占满首屏: 标题在顶, 信息带锚定在画面最底部.
        // 信息带底缘正好贴屏幕下边界, 下一区块完全在折叠线以下.
        val heroHeight = maxHeight - contentTopPad - 16.dp
        // 页起点不再解析推算: PageSection 内部实测本页在滚动内容中的位置
        // (屏幕位置 + 已滚距离), 脚手架顶部留白/insets 等全部自动包含, 无固定偏差.
        // 选集区自成完整一屏 (标题+简介+封面+轮播): 吸附后整页占满屏幕,
        // 上下各留 EPISODES_PAGE_VERTICAL_MARGIN 的空隙.
        val episodesPageHeight = maxHeight - EPISODES_PAGE_VERTICAL_MARGIN * 2
        // 一屏高度, 留给页面末尾的收尾留白 (深层嵌套的内容 lambda 里取不到 BoxWithConstraints 的接收者)
        val viewportHeight = maxHeight
        // 首屏以下的区块**不定高、不等距** (用户 2026-09-15 推翻了上一版的"整数页"): 每个区块只定义自己的**起点**
        // (第三页 = 作品信息, 第四页 = 评价), 往下翻就吸附到那个起点, 上一页露不露得出来无所谓 —— 区块切换动画按
        // 实际滚动差值走 (见 TvDetailsPager), 距离不等也不用分情况. 定高那版把内容挤裁 (关联卡标题被切) 且留白怪异
        // 画面纵向运动全部由"分区吸附"显式驱动: 焦点在 Hero 区 (顶栏/信息带)
        // 内移动画面固定在顶部; 焦点进入某个区块则滚动到该区块顶部. 为此禁用纵向滚动容器的
        // 默认 BringIntoView (否则它与吸附动画互相打架, 造成跳动); 区块列内部重新提供默认
        // spec, 保证选集行等横向 LazyRow 的横向滚动不受影响.
        val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
        val noBringIntoView = remember {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
            }
        }
        // 左缘 overlay 导航栏 (zIndex 置顶): 仅在横屏海报首屏 (未下滑) 显示.
        // 收起态是贴左缘的一列纯图标; 焦点从 Hero 左列按钮按左进入后展开为图标+文字,
        // 并从左侧压一层渐变遮罩盖住海报页. 用途: 详情页可经关联条目无限嵌套,
        // 这里提供一键回主页的逃生通道 (返回键只逐层退). 图标/文字尺寸对齐主页导航栏.
        // 播放器内嵌变体不渲染: 播放器界面不该提供离开播放器的侧边入口 (返回键即退出),
        // 去掉后左右边距对称 (rail 图标不再占据左边距).
        // derivedStateOf: 本作用域 (BoxWithConstraints) 包住整页内容, 裸读 scrollState.value
        // 会让吸附滚动动画期间整页每帧重组; 收敛为只在 0/非0 边界失效一次
        val atPageTop by remember { derivedStateOf { scrollState.value == 0 } }
        // 侧边栏不等放大转场 (revealed): 上一页的侧边栏在同一位置, 藏了会"先消失再出现" (用户 2026-09-10)
        if (atPageTop && !videoBackground) {
            // 遮罩颜色用主题色 (surface 向 surfaceTint 偏移, 再稍向黑压深以在海报上保证可读),
            // 随主题/动态取色变化; 压深比例按日夜主题分档 (见常量注释, 可调).
            // 羽化渐变方式由共用侧边栏统一按探索页那套平滑多色标处理.
            val railScrimDarken = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
                TV_DETAILS_RAIL_SCRIM_DARKEN_DARK
            } else {
                TV_DETAILS_RAIL_SCRIM_DARKEN_LIGHT
            }
            val railScrimColor = lerp(
                lerp(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.surfaceTint,
                    TV_DETAILS_RAIL_SCRIM_TINT,
                ),
                Color.Black,
                railScrimDarken,
            )
            TvDetailsSideRail(
                onExitToHero = { anchors.request(TvDetailsFocusAnchor.HERO_PLAY) },
                // **本栏才是本页"按遍历顺序最先拿到焦点"的那个节点**: 全局兜底打的是无方向
                // requestFocus, Compose 把 Enter 转成 Right 从**最左**做二维搜索, 最左正是本栏 ——
                // Hero 播放按钮上那处上报根本轮不到. 于是在途的落点请求 (如从标签跳搜索页返回要
                // 回到标签墙) 拿不到重试事件.
                // (这只是补齐这个 API 的语义; "返回后侧边栏自己弹开"那个 bug 的真因见上方两处补救.)
                onFallbackFocused = anchors::notifyFocusFallbackSettled,
                modifier = Modifier.align(Alignment.CenterStart).zIndex(1f),
                scrimColor = railScrimColor,
            )
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides noBringIntoView) {
        MultiColumnScaffold(
        layoutParams.copy(
            contentHorizontalPadding = 0.dp,
            contentTopPadding = contentTopPad,
        ),
        Modifier,
        // 顶栏按钮 (返回/主页/外链) 在 TV 上已全部移除, 顶栏本身也不再渲染,
        // 否则 Scaffold 会把滚动区压到顶栏之下, 往下翻时内容在 64dp 处被裁出一条边界
        showTopBar = false,
        windowInsets,
        scrollState = scrollState,
        backgroundOverlay = {
            if (videoBackground) {
                // 视频作背景: 对下层视频画遮罩 (首屏只压底部 + 左缘, 滚动后整屏变暗)
                TvVideoBackgroundScrim(scrollState)
            } else {
                val surfaceColor = MaterialTheme.colorScheme.surface
                val colors = remember(backgroundPalette) {
                    backgroundPalette?.swatches
                        ?.map { ColorUtils.blendColor(it.color, surfaceColor, 0.85f) }
                        ?.toImmutableList()
                }
                // backdrop 图到位且停在页顶 (图不透明盖满) 时暂停光斑动画: 被盖住还在跑的
                // 全屏 blur 是探针实测里详情页"永不静止"的主因 (2026-07-31, 常驻 13-30fps).
                // 滚动后 backdrop 渐隐、光斑重新露出, 动画随之恢复
                var backdropLoaded by remember(heroBackdropUrl) { mutableStateOf(false) }
                if (colors != null && !underZoom) {
                    AnimatedGradientBackground(
                        colors,
                        speed = 0.05,
                        modifier = Modifier.fillMaxSize(),
                        paused = { backdropLoaded && scrollState.value <= 0 },
                    )
                }
                // 全屏背景: TMDB 横版图, 没有则竖版封面居中裁切 (见 heroBackdropUrl).
                // 连封面也没有时才什么都不铺, 由 TvHeroBlock 走"无图版式"
                heroBackdropUrl?.let { url ->
                    TvHeroBackdrop(
                        imageUrl = url,
                        // 放大会话进行中: 组合着 (提前把位图加载好) 但不画, 放大那一层在下面顶着; 接手那一帧才显示
                        // (换图接手时在放大层上淡进来)
                        hidden = underZoom && !crossFading,
                        fadeInAlpha = { if (underZoom) crossImageAlpha.value else 1f },
                        // 换图淡入期间, 底缘渐隐画成页面底色的同色渐变, 不擦图: 擦掉的地方会透出下面放大层里的旧图, 淡入到头时
                        // 底缘比接手后亮一截, 接手那一帧又暗回去 (2026-09-14 用户: 继续观看进来"底下的黑色遮罩闪一下"). 同色
                        // 渐变下每一帧都是标准的新旧交叉淡入, 到头正好等于接手后的样子; 接手后恢复擦除 (底下换成了动态渐变)
                        solidUnderlay = if (underZoom && crossFading) AniThemeDefaults.pageContentBackgroundColor else null,
                        scrollState = scrollState,
                        // 换页的滚动是跳的: 背景淡出进度改由 TvDetailsPager 按时长走, 不随滚动量一下子到底
                        scrollFade = { pager.backdropProgress() },
                        // 页面主题色从**这张背景图**取: 它就是屏幕上最大的一块颜色, 主色与它同源
                        // 才不脱节 (改用竖版封面试过, 有些条目两张图色调差很远, 观感割裂).
                        // 没有 backdrop 的条目由下面那条 hero 分支用竖版封面兜底, 见 heroBackdropUrl
                        onSuccess = {
                            backdropLoaded = true
                            ownBackdropLoaded = true
                            onCoverImageSuccess(it)
                        },
                    )
                }
            }
        },
        containerColor = if (videoBackground || underZoom) Color.Transparent else AniThemeDefaults.pageContentBackgroundColor,
        // 放大期间底色透明 (图与底由放大层画), 文字颜色仍按页面底色配: 否则没显式指定颜色的文字 (评分大数字 / 收藏统计)
        // 在会话结束前是祖先的默认黑色, 继续观看换图接手时整整黑 250ms (2026-09-14 用户看到"从黑变白"). 视频背景照旧
        contentColor = contentColorFor(if (videoBackground) Color.Transparent else AniThemeDefaults.pageContentBackgroundColor),
    ) {
        Column(
            // 起始留白只加在 Hero 块内 (startPadding), 不能加在整列上:
            // 列级 padding 会把选集轮播 LazyRow 的左边界一起右移, 向左滑过锚点的
            // 卡片在此处被硬裁出一条边 (卡片行必须保持全宽出血); 侧边栏也只在海报首屏显示
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(layoutParams.sectionSpacing),
        ) {
            // 播放器内嵌变体不渲染独立页 Hero (介绍页在选集条之后, 见下方 videoBackground 分支)
            if (!videoBackground) TvHeroBlock(
                state = state,
                info = info,
                selfInfo = selfInfo,
                onPlay = onPlay,
                onClickLogin = onClickLogin,
                onClickOpenExternal = onClickOpenExternal,
                horizontalPadding = pad,
                // 放大转场 (见 zoomFrom): 标题从列表页的位置平移过来, 其余到位后一次性出现
                titleModifier = Modifier.tvHeroZoomTitleShift(zoomSession),
                bodyComposed = revealed || bodyEarly,
                // lambda: 在三处 graphicsLayer 里读, uiEarly 翻转那一帧只改层属性, 不重组整个 hero 块
                bodyHidden = { underZoom && !uiEarly },
                // 播放按钮 = HERO_PLAY 锚点 (挂请求器 + 到位确认)
                primaryButtonModifier = Modifier
                    // **hero 下缘接线**. 独立页的 hero 不是 [PageSection] (它是整屏的 Box), 纵向离场一直没人接管,
                    // 从这里按下走的是二维空间搜索: 目标那一刻可能在屏幕外 / 子项被回收, 于是越过整整一页落到第三页
                    // (用户 2026-09-16; 更早的"冷进入往下翻直接到第三页"同因). 埋点确认过 —— 那几下按键 moveDown
                    // 一条日志都没有, 根本没进路由.
                    //
                    // 接在**播放按钮自己**身上而不是 hero 外层: 外层挂 focusProperties.onExit 要配 focusGroup, 而 focusGroup
                    // 会把搜索先圈在 hero 内部, 于是"开始观看"按下变成跳去标签 (2026-09-16 试过, 当场回归). 播放按钮是
                    // hero 最下缘那个可聚焦元素, 只接它这一下, hero 内部 (图标簇 -> 播放按钮) 的纵向移动照常走空间搜索.
                    .tvSectionEdge(sectionNav, TvDetailsSection.HERO, down = true)
                    .tvFocusAnchor(anchors, TvDetailsFocusAnchor.HERO_PLAY)
                    .onFocusChanged {
                        if (it.isFocused) anchors.notifyFocusFallbackSettled()
                    },
                // 中列: 收藏统计 + 标签墙 + 连载信息. 标签墙浏览模式需要 BringIntoView
                // 滚动露出隐藏标签, 恢复默认 spec (页面级已禁用)
                middleColumn = {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                        TvHeroInfoColumn(
                            state = state,
                            info = info,
                            // 点击标签跳转搜索: 标记返回时要把焦点恢复到该标签
                            onClickTag = {
                                tagsRestorePending = true
                                onClickTag(it)
                            },
                            browseMode = tagsBrowseMode,
                            onBrowseModeChange = { tagsBrowseMode = it },
                            focusedTagIndex = focusedTagIndex,
                            onFocusedTagIndexChange = { focusedTagIndex = it },
                            restorePending = tagsRestorePending,
                            onRestoreConsumed = { tagsRestorePending = false },
                            anchors = anchors,
                            wallRestoreIndex = tagRestoreIndex,
                            modifier = Modifier.weight(1f).padding(start = 24.dp),
                        )
                    }
                },
                // 播放按钮底部进度条: 取"继续观看"目标集的进度
                playProgress = state.subjectProgressState.episodeIdToPlay?.let { playProgress[it] },
                // 播放按钮长按: 跳到当前集的选集卡片 (复用网格菜单的 reveal 机制 ——
                // 轮播滚到该集并聚焦, 页面随焦点吸附到选集页, 按住的残余确认键由卡片吞掉)
                onLongPressPlay = {
                    (state.subjectProgressState.episodeIdToPlay ?: currentEpisodeId)
                        ?.let { revealEpisodeId = it }
                },
                // 加载中按"有图"排版: 大多数条目有 backdrop, 图到了直接淡入; 确认无 TMDB 图时
                // 竖版封面会顶上来当背景 (heroBackdropUrl), 排版不变 —— 于是只有"连封面都没有"
                // 的条目才落到无图版式 (标题用主题色 + 右侧竖版封面 + 简介挪到 Hero 上).
                // 视频背景模式恒为"有图"排版 (视频画面就是背景, 竖版封面会挡住它)
                hasBackdrop = videoBackground || heroBackdropUrl != null || !backdropResolved,
                onCoverImageSuccess = onCoverImageSuccess,
                displaySummary = displaySummary,
                // 收藏钮右侧的"选集"圆钮 + 锚定其下的快速跳转网格菜单
                episodeGridCapsule = {
                    Box {
                        TvCapsuleButton(
                            onClick = { showEpisodeGrid = true },
                            icon = { Icon(Icons.Rounded.GridView, contentDescription = null) },
                            label = { Text(stringResource(Lang.subject_details_episodes), softWrap = false) },
                            modifier = Modifier
                                .tvFocusAnchor(anchors, TvDetailsFocusAnchor.EPISODE_GRID_ENTRY),
                        )
                        FocusEpisodeGridDropdown(
                            expanded = showEpisodeGrid,
                            // 网格是数字方块快速跳转, 保持正片/特别篇分组 (对应旧版选集对话框)
                            episodes = mainEpisodes,
                            specialEpisodes = specialEpisodes,
                            currentEpisodeId = currentEpisodeId,
                            episodeRuntimes = episodeRuntimes,
                            onEpisodeClick = {
                                showEpisodeGrid = false
                                onPlay(it.episodeId)
                            },
                            // 返回键正常关闭: 焦点还给入口圆钮, 不跳转
                            onDismissRequest = {
                                showEpisodeGrid = false
                                anchors.request(TvDetailsFocusAnchor.EPISODE_GRID_ENTRY)
                            },
                            // 长按 (按住 OK) 某集方格: 轮播跳到该集, 焦点落到卡片上并触发选集区吸附滚动
                            onEpisodeLongClick = { item ->
                                showEpisodeGrid = false
                                revealEpisodeId = item.episodeId
                            },
                            onCacheClick = onClickCache,
                        )
                    }
                },
                // 占满首屏, 信息带贴底
                modifier = Modifier.height(heroHeight)
                    // 换页时 hero (第 0 页) 原地淡出 / 淡入 (滚动是跳的, 见 TvDetailsPager)
                    .graphicsLayer { pager.apply(TvDetailsSection.HERO.page, this) }
                    // 首屏以下区块还没组合 (见 sectionsReady) 时按下键: 扣住这一下, 选集页组合出来再送焦点过去 ——
                    // sectionNav 往下送焦点是直接 requestFocus, 目标还不存在时这一下会被静默吞掉
                    .onPreviewKeyEvent {
                        if (sectionsReady || it.key != Key.DirectionDown) return@onPreviewKeyEvent false
                        if (it.type == KeyEventType.KeyDown) focusEpisodesWhenReady = true
                        true
                    }
                    // 焦点回到 Hero 信息带时滚回页面顶部, 否则标题永远滚不回来
                    // (滚动仅由焦点元素的 BringIntoView 驱动, 而标题不可聚焦)
                    .onFocusChanged {
                        if (it.hasFocus) {
                            backLevelOrdinal = TvDetailsSection.HERO.ordinal
                            pager.goTo(TvDetailsSection.HERO.page)
                        }
                    },
            )
            // 放大转场: 到位前不组合 hero 之下的区块, 到位后也晚首屏一帧 (见 sectionsReady)
            if (revealed && sectionsReady) CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
            // 水平留白不加在区块列上, 由各区块自理: 选集轮播的卡片行要一直画到屏幕右边缘
            // (出血, 停靠留边由轮播内部 contentPadding 提供), 其余区块照常留边
            Column(
                verticalArrangement = Arrangement.spacedBy(layoutParams.sectionSpacing),
            ) {
            // 角色/制作人员区块的三态 (提前算, 选集区的下键闸门要读; 判据注释见骨架调用处)
            val relationsSettled = exposedCharacters != null && exposedStaff != null &&
                    (exposedCharacters.itemCount > 0 || totalCharactersCount == 0) &&
                    (exposedStaff.itemCount > 0 || totalStaffCount == 0)
            val relationsAnyContent = exposedCharacters != null && exposedStaff != null &&
                    (exposedCharacters.itemCount > 0 || exposedStaff.itemCount > 0)
            val relationsConfirmedEmpty = totalCharactersCount == 0 && totalStaffCount == 0
            // 骨架还挂着 = 选集之下的布局尚未定型
            val relationsSkeletonVisible = exposedCharacters != null && exposedStaff != null &&
                    !(relationsSettled && relationsAnyContent) && !relationsConfirmedEmpty

            if (videoBackground) {
                // ---- 播放器内嵌变体: 页序为 介绍页 -> 其余区块 ----
                // 选集条已移入播放器控制层 (图标行下方, Prime 形态), 不在本页.
                // 介绍页: 整屏吸附区块, 无操作按钮 (播放/选集/收藏/缓存/外链全部由
                // 播放器控制栏承担), 只有 标题 / 简介+封面 / 单排信息带
                PageSection(
                    pager,
                    page = TvDetailsSection.HERO.page,
                    scrollState,
                    layoutParams.sectionSpacing,
                    nav = sectionNav,
                    // 内嵌变体的介绍页就是本页最顶层 (对应独立页的海报页)
                    onFocused = { backLevelOrdinal = TvDetailsSection.HERO.ordinal },
                ) {
                    TvEmbeddedHeroPage(
                        state = state,
                        info = info,
                        displaySummary = displaySummary,
                        comments = comments,
                        commentCount = commentCount,
                        onShowComments = onShowComments,
                        mainEpisodeCount = mainEpisodes.size.takeIf { it > 0 },
                        // 上/下边缘接线 (上回播放器选集条, 下到关联条目区) 全走路由
                        sectionNav = sectionNav,
                        // 点击标签跳转搜索: 标记返回时要把焦点恢复到该标签
                        onClickTag = {
                            tagsRestorePending = true
                            onClickTag(it)
                        },
                        browseMode = tagsBrowseMode,
                        onBrowseModeChange = { tagsBrowseMode = it },
                        focusedTagIndex = focusedTagIndex,
                        onFocusedTagIndexChange = { focusedTagIndex = it },
                        restorePending = tagsRestorePending,
                        onRestoreConsumed = { tagsRestorePending = false },
                        anchors = anchors,
                        wallRestoreIndex = tagRestoreIndex,
                        horizontalPadding = pad,
                        modifier = Modifier.height(heroHeight),
                    )
                }
            } else PageSection(
                pager,
                page = TvDetailsSection.EPISODES.page,
                scrollState,
                EPISODES_PAGE_VERTICAL_MARGIN,
                nav = sectionNav,
                onFocused = { backLevelOrdinal = TvDetailsSection.EPISODES.ordinal },
                modifier = Modifier
                    .onGloballyPositioned { episodesTopInRoot = it.positionInRoot().y }
                    // 预画 (见 episodesPrewarm): 绘制阶段把整块挪进可见范围、1% 不透明度. 只动图层属性, 不触发布局, 焦点与测量不受影响;
                    // ModulateAlpha 不开离屏, 与正常显示时是同一套绘制指令 (预热的正是它们)
                    .graphicsLayer {
                        if (episodesPrewarm && !episodesTopInRoot.isNaN()) {
                            translationY = -episodesTopInRoot
                            alpha = 0.01f
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    },
            ) {
            // 选集整页: 上半 = 完整标题 + 简介 (截断, 占满剩余高度) + 右侧竖版封面,
            // 下半 = 选集轮播; 合起来正好一屏 (上下留 EPISODES_PAGE_VERTICAL_MARGIN).
            // 封面尺寸从整页高度推出 (而非上半区高度): 高 = 整页 x TV_EPISODES_COVER_HEIGHT_FRACTION,
            // 锚定右上, 超出上半区的部分向下延伸 (不占布局高度, 不推挤轮播); 上半区的
            // 标题/简介与下方轮播的小标题/集简介都以"封面宽 + 32dp 间距"收右边界 ——
            // 四者右缘对齐到同一条线, 全部不与封面重叠
            val episodesCoverHeight = episodesPageHeight * TV_EPISODES_COVER_HEIGHT_FRACTION
            val episodesTextEndReserve = if (info.imageLarge.isNotBlank()) {
                episodesCoverHeight * COVER_WIDTH_TO_HEIGHT_RATIO + 32.dp
            } else {
                0.dp
            }
            Column(
                // 整页高度收窄 EPISODES_PAGE_CONTENT_LIFT: 上半是 weight(1f) 的简介, 收窄只吃掉
                // 简介文字下方的留白 (文字顶对齐不动), 把轮播及其后区块整体上移. 封面仍按原
                // episodesPageHeight 计算 (TopEnd 无界锚定不占布局高度), 不受影响.
                Modifier.height(episodesPageHeight - EPISODES_PAGE_CONTENT_LIFT),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad)
                    // 从上方 (Hero) 向下进入本区域时不要停在简介的展开按钮上, 直接送到选集卡片:
                    // 按钮贴右缘、又在卡片行上方, 空间搜索向下必然先命中它. 从下方 (卡片按上键,
                    // 走显式 upFocus) 进入时方向不是 Down, 不受影响.
                    .focusProperties {
                        onEnter = {
                            if (requestedFocusDirection == FocusDirection.Down) {
                                runCatching { anchors.episodesCarousel.requestFocus() }
                            }
                        }
                    }
                    .focusGroup(),
            ) {
                Column(
                    Modifier.fillMaxHeight().padding(end = episodesTextEndReserve),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 撤掉的「作品信息」块并到简介全文弹窗里: 别名进正文末尾, 放送日期与话数进底行.
                    // 先算出来, 下面的 alwaysShowExpand 要据此决定按钮渲不渲染
                    val summaryExtra = subjectAliasesText(info)
                    val summaryMeta = subjectMetaLine(info, mainEpisodes.size.takeIf { it > 0 })
                    Text(
                        info.displayName,
                        style = MaterialTheme.typography.headlineLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 固定占满标题下的剩余高度 (两种模式尺寸一致);
                    // 聚焦后按确认键进入阅读模式 (上下键滚动 + 右侧滚动条).
                    // 简介为空 (未开播条目常见, 可能连分集都没有) 时兜底显示"暂无信息",
                    // 保持本块始终可聚焦 —— 否则选集页可能没有任何焦点目标, 向下导航整页跳过
                    TvTruncatedSummary(
                        displaySummary.ifBlank { stringResource(Lang.subject_details_no_summary) },
                        dialogTitle = info.displayName,
                        // 撤掉的"作品信息"块并到这里: 别名进正文末尾, 放送日期与话数进底行元信息
                        dialogExtra = summaryExtra,
                        dialogMeta = summaryMeta,
                        // 本块下面紧接着选集卡, 展开按钮排到正文末行右边 (见该参数的说明)
                        expandOnLastLine = true,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        // 展开按钮默认只在简介被截断时出现, 这里还有两种情况必须强制渲染:
                        // 1. 无分集 (未开播条目) 时它是整页唯一的焦点目标;
                        // 2. **弹窗里还有简介之外的内容** (别名 / 放送日期, 见 dialogExtra / dialogMeta) ——
                        //    「作品信息」块撤掉后这些只在弹窗里, 简介短的条目没有按钮的话就彻底够不到了
                        //    (用户 2026-09-15: "现在有的动画没有显示更多按钮")
                        alwaysShowExpand = episodes.isEmpty() || summaryExtra != null || summaryMeta != null,
                        // 上报按钮有没有渲染: 卡片的上键只在它存在时才指过去 (见 upFocus)
                        onExpandButtonPresenceChange = { summaryExpandPresent = it },
                        // EPISODES_SUMMARY 锚点挂在按钮上 (到位确认由外层 Column 的 onFocusChanged 做)
                        expandModifier = Modifier.tvFocusAnchor(
                            anchors,
                            TvDetailsFocusAnchor.EPISODES_SUMMARY,
                        ),
                        // 按钮再往上: 显式回上一区块 (Hero), 同样不指望空间搜索
                        onNavigateUp = if (sectionNav.canMoveUp(TvDetailsSection.EPISODES)) {
                            { sectionNav.moveUp(TvDetailsSection.EPISODES) }
                        } else null,
                    )
                }
                if (info.imageLarge.isNotBlank()) {
                    AsyncImage(
                        info.imageLarge,
                        contentDescription = null,
                        Modifier
                            .align(Alignment.TopEnd)
                            .wrapContentHeight(align = Alignment.Top, unbounded = true)
                            .height(episodesCoverHeight)
                            .aspectRatio(COVER_WIDTH_TO_HEIGHT_RATIO)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                        // 选集页在首屏之下: 图一到就预传 GPU, 免得冷启动后第一次往下翻时当场上传 (同选集卡剧照)
                        onSuccess = { it.bitmap?.prepareToDraw() },
                    )
                }
            }
            FocusEpisodeCarousel(
                episodes = episodes,
                horizontalPadding = pad,
                // 小标题行/集简介行与上半区文字共用右边界 (给封面让位)
                endPadding = pad + episodesTextEndReserve,
                // 无图的集用玻璃底: 本页底下压着 backdrop, 实心底色会把图整块盖掉
                // (同页的标签/信息带按钮本来就是这个底)
                glass = true,
                // 聚焦集简介用阅读模式组件: 平时按高度截断, 按确认键进入滚动阅读;
                // 视口只有两行高, 一次滚一行
                // 集简介: 3 行截断, 不可聚焦 (全文在长按卡片的本集详情弹窗里) ——
                // 正文让位后从上方到卡片只需一次下键
                descContent = { desc, _ ->
                    TvTruncatedSummary(
                        desc,
                        dialogTitle = null,
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        // contentPadding 用默认值 (共用常量): 两块正文左右缘对齐,
                        // 且与右侧时长/日期行对行齐平
                        maxLines = TV_EPISODE_DESC_MAX_LINES,
                        minLines = TV_EPISODE_DESC_MAX_LINES,
                    )
                },
                currentEpisodeId = currentEpisodeId,
                onEpisodeClick = { onPlay(it.episodeId) },
                episodeStills = tmdbEpisodeStills,
                playProgress = playProgress,
                episodeRuntimes = episodeRuntimes,
                episodeOverviews = episodeOverviews,
                // 长按卡片: 标记看过/取消看过
                onSetEpisodeCollectionType = { item, type ->
                    onEpisodeCollectionUpdate(
                        SetEpisodeCollectionTypeRequest(state.subjectId, item.episodeId, type),
                    )
                },
                // 网格菜单关闭后跳到菜单里聚焦的那一集
                revealEpisodeId = revealEpisodeId,
                onRevealConsumed = { revealEpisodeId = null },
                // 返回键分层: 选集之下的区域按返回把焦点送回轮播卡片
                rowFocusModifier = Modifier.tvFocusAnchor(
                    anchors,
                    TvDetailsFocusAnchor.EPISODES_CAROUSEL,
                ),
                // **跨页的下键不在这里接线**: 这里只能给一枚裸 FocusRequester, 目标那一刻没附着 (下一页还没组合 /
                // 子项被回收) 就静默失败 —— 于是按键被吃掉或退回空间搜索, 表现为"第二页往下有概率直接进第四页"
                // (用户 2026-09-16). 跨页统一由 PageSection 的 focusProperties.onExit 接住, 走调度器等附着.
                // 下面这段注释保留原委, 参数已改为 null.
                // 骨架期不拦 (曾用 FocusRequester.Cancel 锁住"下方未定型"的窗口, 但慢条目
                // relations 要 1.5~3 秒, 用户按下键几秒没反应更难受; 高度已由骨架钉死、
                // 交接与真区块同帧, 提前翻下去也不会晃) —— 落到关联条目区, 人物区就绪后
                // 原地填充, 上键随时回去.
                downFocus = null,
                // 卡片按上键落到简介的展开按钮: 不能交给空间焦点搜索 —— 简介正文已不可聚焦,
                // 上方唯一的目标是贴右缘的小按钮, 从左侧卡片往上找不到几何上方的候选.
                // 按钮没渲染 (简介没被截断) 时传 null: 指向未附着的请求器会变成"按了没反应",
                // 交回空间搜索至少还能跳出本区块
                upFocus = anchors.episodesSummary.takeIf { summaryExpandPresent },
                // 不再有"选集"标题行: 该位置改放聚焦集的小标题 (见 FocusEpisodeCarousel),
                // "看过/全X话"连载进度与 Hero 重复已去掉
            )
            }
            }
            // ---- 角色 / 制作人员 (仅独立页; 内嵌变体是精简版, 这两类内容由播放器
            // 胶囊面板承担). "查看全部"与人物点击均为 TV 居中弹窗形态
            // (ViewAllSheet/PeoplePreview 已按平台分支). 两块共用一个吸附区块:
            // 焦点从区块外进入时角色行吸顶, 在角色/制作人员之间移动不再重新吸附
            // (最小滚动逐步露出). 空数据时区块自身不渲染 (Section 内部 early return),
            // sectionNav 的存在性与之同步, 跨区块下键自动跳过.
            // 数据还在路上 (relations 取数要 1.5~3 秒, 远晚于首屏) 时渲染**等高骨架**:
            // 这两块原先"没数据就整个不渲染", 数据一到几百 dp 突然插进滚动列中间 —— 已经翻到
            // 下方区块的用户被整体推走 (滚动锚定接得住焦点, 但插入那一帧的抖动接不住).
            // 骨架把位置先钉死, 数据到位时只是原地填充.
            //
            // **骨架与真区块必须无缝交接** (2026-08-26 第一版按 "count 非 null 就收骨架" 踩过):
            // count 由 repository flow 的 onEach 设置, 而 itemCount 要等 LazyPagingItems 的
            // paging 查询刷新 —— 两件事不同步, count 先到的那一拍里骨架已收、真区块未出,
            // 塌下去几百 dp 再弹回来, 抖动比不加骨架还难看. 两个 count (角色/制作人员两条独立流)
            // 还会一先一后. 所以骨架一直撑到**真区块出现的同一帧**才让位:
            //   showReal      = 两块都尘埃落定 (itemCount>0 或确认为空) 且至少一块有内容 -> 真区块
            //   两边都确认为空 -> 什么都不渲染 (收缩方向不挤焦点, 有滚动锚定兜底)
            //   其余一律骨架   (含 "count 已到但 paging 未跟上" 的窗口)
            // 选集页之后多留一段空白: 选集页是刻意铺满一屏的整页, 底下再露出半个"角色"标题就显得挤
            // (用户 2026-09-15). 实测标题原本落在 524..540 (露出 16dp), 推 32dp 后到 556 完全出屏.
            //
            // 与末页那段 (TV_LAST_PAGE_LEAD_GAP) 方向相反: 那边**要**露出标题作为"下面还有"的提示,
            // 这边一点都不要露. 同样加在页与页之间, 在第三页上看不见 (页起点是从角色区块量的).
            if (!videoBackground && sectionsStage >= 2) {
                Spacer(Modifier.height(TV_EPISODES_PAGE_TRAIL_GAP))
            }
            // 分帧放出时 (见 sectionsStage) 角色区没轮到也先放骨架, 与真区块等高
            if (relationsSkeletonVisible || (sectionsStage < 2 && relationsSettled && relationsAnyContent)) {
                // 骨架与真角色区同页号 (2): 换页时跟着同一组位移 / 淡入淡出 (见 TvDetailsPager)
                Box(Modifier.graphicsLayer { pager.apply(TvDetailsSection.CHARACTERS.page, this) }) {
                    RelationsSkeletonSection(layoutParams.sectionSpacing, pad)
                }
            }
            if (exposedCharacters != null && allCharacters != null && exposedStaff != null && allStaff != null &&
                relationsSettled && relationsAnyContent && sectionsStage >= 2
            ) {
                PageSection(
                    pager,
                    page = TvDetailsSection.CHARACTERS.page,
                    scrollState,
                    layoutParams.sectionSpacing,
                    nav = sectionNav,
                    onFocused = { backLevelOrdinal = TvDetailsSection.CHARACTERS.ordinal },
                ) {
                    // 两排之间比常规区块间距窄 8dp: 这一页要在 540dp 里装下两排 130dp 的圆头像,
                    // 还要把下一页的"评价"标题留在视口里 (见 TV_LAST_PAGE_LEAD_GAP 的标定)
                    Column(verticalArrangement = Arrangement.spacedBy(TV_PEOPLE_ROW_GAP)) {
                        CharactersSection(
                            exposedCharacters, allCharacters, totalCharactersCount,
                            modifier = Modifier
                                // 区块进入落点 (上方选集卡片下键经路由落到第一个头像)
                                .tvFocusAnchor(anchors, TvDetailsFocusAnchor.CHARACTERS_SECTION)
                                // 跨页返回恢复的到位确认 (焦点落进本区块子树即算到位) + 区块记账.
                                // 角色行与制作人员共用一个吸附区块 (那层 onFocused 只报得出
                                // CHARACTERS), 两行各自再报一次才能恢复到"离开前那一行"
                                .onFocusChanged {
                                    if (it.hasFocus) {
                                        backLevelOrdinal = TvDetailsSection.CHARACTERS.ordinal
                                    }
                                }
                                .focusGroup(),
                            // 水平留白走行内 contentPadding 而**不是**外层 padding: 卡片行要
                            // 保持全宽出血, 外层 padding 会把行的左边界一起右移, 于是向左滑过
                            // 停靠位的卡片正好在停靠线上被硬裁出一条边 (同选集轮播, 见本页
                            // 顶部 Column 的注释). 标题由区块内部按同一留白对齐.
                            // 两侧都留白: 只留起始侧的话, 滑到行末时行再也滚不动, 最后一格 ("查看全部")
                            // 就贴着屏幕右缘被裁掉 (用户 2026-09-15). 滚动途中的出血观感不受影响 ——
                            // LazyRow 按自身边界裁, 中间的格照样铺到屏幕边
                            contentPadding = PaddingValues(horizontal = pad),
                            // 卡片下键显式送往下一区块 (跨区块空间搜索不可靠)
                            // 只接同页的制作人员; 没有制作人员数据时给 null, 下键交给 PageSection.onExit 走路由
                            // (否则静态落点会直接跳到第四页, 绕过换页闸与送焦)
                            downFocus = sectionNav.samePageDownTargetFrom(TvDetailsSection.CHARACTERS),
                            // 长按卡片放大看头像 (短按仍是人物预览)
                            imageZoom = imageZoom,
                        )
                        Box(
                            Modifier
                                .tvFocusAnchor(anchors, TvDetailsFocusAnchor.STAFF_SECTION)
                                .onFocusChanged {
                                    if (it.hasFocus) {
                                        backLevelOrdinal = TvDetailsSection.STAFF.ordinal
                                    }
                                }
                                .focusGroup(),
                        ) {
                            StaffSection(
                                exposedStaff,
                                allStaff,
                                totalStaffCount,
                                gridColumns = layoutParams.staffGridColumns,
                                // 跨页下键交给 PageSection.onExit 的路由 (同选集卡, 见那里的注释)
                                downFocus = null,
                                imageZoom = imageZoom,
                                contentPadding = PaddingValues(horizontal = pad),
                            )
                        }
                    }
                }
            }
            // 末页之前多留一段空白: 上一页 (角色 + 制作人员) 到底之后, 视口里还剩几十 dp, 本页的开头会
            // 顺着露出来 —— 只露"评价"两个字是想要的提示, 露出半截评论卡就难看 (实测卡片探出 36dp,
            // 用户 2026-09-15). 这段空白**加在页与页之间而不是块内**: 页起点是从"评价"标题算的
            // (见 TvDetailsPager), 所以它在本页上完全看不见, 只决定上一页底部露多少.
            //
            // 注意这只对"上一页内容够高"成立: 某个条目若没有制作人员那一排, 上一页变矮, 露出来的自然更多 ——
            // 连续布局的固有性质, 不为此给页面定高 (定高那版把内容挤裁过, 见 2026-09-15 的改版记录).
            if (!videoBackground && sectionsStage >= 3) {
                Spacer(Modifier.height(TV_LAST_PAGE_LEAD_GAP))
            }
            // 末页: 关联条目 + 评价 (2026-09-15 重排). 「作品信息」块已撤销 (内容与 hero 信息带重复,
            // 别名与精确日期并进了选集页简介的全文弹窗). 内嵌变体的评价在介绍页里, 这里只剩关联条目,
            // 无关联时整块不组合 (上方区块即页面终点, 下键无落点属预期).
            if ((!videoBackground || related.itemCount > 0) && sectionsStage >= 3) {
                PageSection(
                    pager,
                    page = TvDetailsSection.BELOW.page,
                    scrollState,
                    layoutParams.sectionSpacing,
                    nav = sectionNav,
                    onFocused = { backLevelOrdinal = TvDetailsSection.BELOW.ordinal },
                ) {
                    Column(
                        // 进入落点**不挂在这儿**: 本页现在装着评价 + 关联条目两块, 挂根上的话 requestFocus
                        // 经 enter 落到第一个可聚焦子项 = 评价卡, 于是评价按下键路由到 BELOW 又落回自己
                        // (用户 2026-09-15: "评价往下导航下不去到关联条目"). 锚点挂在关联条目那一列上.
                        Modifier.focusGroup(),
                        // 不用区块间距 (24dp) 而用评价块内部的标题→卡片间距: 评价卡上下两侧的留白就一样大了,
                        // 否则卡片夹在 16 与 24 之间看着偏上 (用户 2026-09-15); 顺带把关联条目提上来 8dp
                        verticalArrangement = Arrangement.spacedBy(TV_REVIEW_HEADER_GAP),
                    ) {
                        // 「作品信息」块 (放送开始 / 话数 / 别名) 2026-09-15 撤掉: 放送时间与话数 hero 的信息带上
                        // 已经写着 ("已完结 · 全 28 话 · 2023年9月"), 这个块**基本是重复的**, 只多出精确到日的日期
                        // 和别名 —— 为这点内容占掉半页不值当. 两样都并进选集页简介的全文弹窗 (见 dialogExtra / dialogMeta).
                        // 评价排在关联条目**之前**: 它是本页的起点, 于是"评价在屏幕上的位置"与有没有关联条目无关
                        // (用户 2026-09-15). 上一页底部也因此恒定露出"评价"两个字, 而不是有时露评价有时露关联条目.
                        //
                        // 两块必须在同一个 PageSection 里 —— 两个 PageSection 用同一个页序号会各自上报这一页的
                        // 起点, 互相覆盖. 没有关联条目时这里就是本页的全部内容, 页起点由实测得出 (见 TvDetailsPager),
                        // 不用分情况写死.
                        if (!videoBackground && comments.itemCount > 0) {
                            TvReviewsPage(
                                comments = comments,
                                totalCount = commentCount,
                                onShowAll = onShowComments,
                                horizontalPadding = pad,
                                anchors = anchors,
                                sectionNav = sectionNav,
                            )
                        }
                        if (related.itemCount > 0) {
                            // TV 上用横向单行 rail 而非多行网格 (锚位条: 聚焦卡停在停靠位)
                            Column(
                                Modifier
                                    // BELOW 的进入落点 (上一块按下键、跨页返回都送到这儿): 必须挂在
                                    // 关联条目这一列, 不能挂末页根上 (见上面那条注释)
                                    .tvFocusAnchor(anchors, TvDetailsFocusAnchor.BELOW_SECTION)
                                    .focusGroup()
                                    // 独立页: 关联卡片上键回评价, 下键就地消费 (本页是页面终点, 防斜跳).
                                    // 内嵌变体维持原空间搜索行为
                                    .ifThen(!videoBackground) {
                                        tvSectionEdge(sectionNav, TvDetailsSection.BELOW, up = true, down = true)
                                    },
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                // 留白只加在标题上; 卡片行全宽出血, 停靠留边由行内
                                // contentPadding 提供 (外层 padding 会在停靠线上硬裁离场卡)
                                SectionHeader(
                                    stringResource(Lang.subject_details_related_subjects),
                                    modifier = Modifier.padding(horizontal = pad),
                                )
                                RelatedSubjectsLazyRow(
                                    related,
                                    onClick = rememberNavigateToRelatedSubject(),
                                    // 150dp: 本页还要装评价块, 能装下是靠评价块瘦身 (标题间距退回 16dp + 卡 124dp).
                                    // 页面内容 476dp, 分页器按 24(页顶) + 476 + 24(露出余量) = 524 < 540 判定不需要滚;
                                    // 余量只有 16dp, 再往这一页加东西就会让焦点下到本行时页面动起来 (2026-09-15 踩过)
                                    itemWidth = 150.dp,
                                    spacing = 20.dp,
                                    contentPadding = PaddingValues(horizontal = pad),
                                )
                            }
                        }
                    }
                }
            }
            // 收尾留白: 最后一个区块 (评价) 比一屏矮时, 滚动量会被内容总高卡住 —— 它的**起点**永远到不了屏幕上方
            // (2026-09-15 截图: 评价标题停在屏幕 26% 处, 上面还挂着关联卡的尾巴). 补一屏的空白让每一页都滚得到自己的起点.
            // 本页的滚动只由焦点吸附驱动 (用户不能自由滚), 这段空白不会被滚进视野
            Spacer(Modifier.height(viewportHeight))
            }
            }
        }
        }
        }
        // 长按角色/制作人员卡片弹出的居中大图. zIndex 置顶: 左缘导航栏用了 1f,
        // 而这一层必须盖住页面上的一切 (它是"只看图"的状态)
        TvZoomedImageOverlay(imageZoom, Modifier.zIndex(2f))
    }
}

/**
 * 详情页左缘 overlay 导航栏, 仅横屏海报首屏显示: 与主页侧边栏完全同一实现 ([TvNavigationSideRail]).
 * 条目也与主页一致 (头像 → 用户信息页 / 搜索 / 探索 / 收藏 / 缓存 / 设置); 头像 selfInfo 就地取.
 * 详情页可经关联条目无限嵌套, 返回键只逐层退, 故本栏在条目上按返回键/右键把焦点还给 Hero 播放按钮
 * (由 [onExitToHero] 处理), 作为一键回主页的逃生通道.
 */
@Composable
private fun TvDetailsSideRail(
    onExitToHero: () -> Unit,
    modifier: Modifier = Modifier,
    scrimColor: Color? = null,
    /** 本栏 (含条目) 拿到焦点时上报, 见调用处 —— 它是本页系统兜底的实际落点. */
    onFallbackFocused: () -> Unit = {},
) {
    val navigator = LocalNavigator.current
    // 详情页不显示头像/用户名 (selfInfo = null), 但保留头像槽位使其余按钮位置不变
    TvNavigationSideRail(
        selfInfo = null,
        onAvatarClick = {},
        onExitFocus = onExitToHero,
        scrimColor = scrimColor,
        // 与主页同一份条目, 只差点击行为: 切 tab 前先弹掉整个嵌套栈回主页
        items = buildTvRailItems(
            onSearch = { navigator.navigateSubjectSearch() },
            onNavigateToPage = { navigator.popBackOrNavigateToMain(it) },
            onSettings = { navigator.navigateSettings() },
        ),
        // hasFocus 而不是 isFocused: 兜底落到的是栏里的某个**条目**, 栏容器本身不持焦
        modifier = modifier.onFocusChanged { if (it.hasFocus) onFallbackFocused() },
    )
}

/**
 * 图标按钮的字形 (glyph) 尺寸: 侧边栏与 Hero 圆钮共用. 配 32dp 容器,
 * 即 M3 extra-small icon button 规格 (20dp icon / 32dp container).
 */

/**
 * 详情页 backdrop 那套遮罩的声明: 左侧可读性 scrim + 下缘渐隐. 列表页那份见 `tvPageBackdropTreatment`,
 * 放大转场画的是两者的插值 (见 [TvBackdropTreatment]).
 *
 * 下缘的起点压后 + 底缘留一成不擦: 原来从 0.62 起擦、0.98 擦光, 屏幕下四成完全没有图, 选集卡片那一带整片发黑
 * (常被当成"多压了一层黑遮罩", 其实是图被擦没了).
 */
private fun tvHeroBackdropTreatment(solidUnderlay: Color?) = TvBackdropTreatment(
    // 左侧暗色 scrim: 保证浮在图上的标题可读
    left = TvBackdropFade(start = 0f, end = 0.55f, maxAlpha = 0.6f, color = Color.Black),
    // 有纯色垫底时画同色渐变 (擦掉 a 露出纯色 C 与在图上叠一层 alpha a 的 C 逐像素相同), 不必开离屏缓冲
    bottom = TvBackdropFade(
        start = 0.72f, end = 1f, maxAlpha = 0.88f,
        color = solidUnderlay ?: Color.Black, toEdge = true,
    ),
    bottomDstOut = solidUnderlay == null,
)

/** 某条边此刻的软边带宽 (本层坐标). [gap] = 这条边全程要走的距离 (根坐标), [scale] = 本层这一轴此刻的缩放. */
private fun tvHeroSoftEdgeBand(gap: Float, base: Float, remaining: Float, scale: Float): Float {
    if (gap <= 0f || base <= 0f || scale <= 0f) return 0f
    return base * (gap * remaining / minOf(base, gap)).coerceAtMost(1f) / scale
}

/**
 * 这一帧还画不画得出软边 —— **按真实几何算**, 与绘制处同源.
 *
 * 原来写成 `(1-t)^曲线 × 比例 > 0.001`, 等价于 `t < 0.734`, 既没用源框尺寸也没用边距和缩放: 按探索页
 * 0.66 屏高 / 1920×1080 算, 在那个切换点左边还有 3.26 个屏幕像素、下边 1.84 个, 于是放大时突然撤掉、
 * 缩小时突然出现, 4K 下更宽 (2026-09-16 审查).
 *
 * 两处 (离屏判据 / 绘制判据) 必须共用它: 分开写就会出现"离屏关了却还在 DstOut 擦穿底层"那种错误.
 */
private fun tvHeroSoftEdgeVisible(zoomFrom: Rect, box: Rect, size: Size, t: Float): Boolean {
    if (t >= 1f || size.width <= 0f || size.height <= 0f) return false
    val sx = lerp(zoomFrom.width / size.width, 1f, t)
    val sy = lerp(zoomFrom.height / size.height, 1f, t)
    val short = minOf(zoomFrom.width, zoomFrom.height)
    val remaining = (1f - t).coerceAtLeast(0f).pow(TV_HERO_SOFT_EDGE_CURVE)
    val base = short * TV_HERO_SOFT_EDGE_FRACTION
    val baseBottom = short * TV_HERO_SOFT_EDGE_BOTTOM_FRACTION
    return tvHeroSoftEdgeBand(zoomFrom.left - box.left, base, remaining, sx) > 0.5f ||
            tvHeroSoftEdgeBand(box.right - zoomFrom.right, base, remaining, sx) > 0.5f ||
            tvHeroSoftEdgeBand(zoomFrom.top - box.top, base, remaining, sy) > 0.5f ||
            tvHeroSoftEdgeBand(box.bottom - zoomFrom.bottom, baseBottom, remaining, sy) > 0.5f
}

/**
 * 软边最宽时占源框短边的比例 (左 / 右 / 上), 见 [TvPolishFlags.zoomSoftEdge].
 * 半径随"离满屏还有多远"长: 满屏为 0, 贴回 hero 框时取这个值. 2026-09-16 用户逐帧调定.
 */
private const val TV_HERO_SOFT_EDGE_FRACTION = 0.20f

/** 下缘软边的比例, 与其余三边分开: 下缘本来就压着一条宽渐隐带 (占层高的后 28%, 见 tvHeroBackdropTreatment). */
private const val TV_HERO_SOFT_EDGE_BOTTOM_FRACTION = 0.20f

/**
 * 软边半径随进度生长的曲线指数: 半径 = 最大值 × (离满屏的距离)^指数.
 *
 * 1 = 线性, **> 1 = 前段慢、末段快**. 线性时落位前那一小段看得出突变 —— 羽化要在很短的时间里从"还很窄"接上
 * 列表页本身很宽的那条渐隐带; 指数拉大等于把生长推到末尾集中完成. 4.0 是 2026-09-16 用户逐帧比对定的.
 */
private const val TV_HERO_SOFT_EDGE_CURVE = 4.0f

/** 详情页侧边栏遮罩: surface 向 surfaceTint (封面取色动态主色) 的偏移比例, 调大主题色更浓. */
private const val TV_DETAILS_RAIL_SCRIM_TINT = 0.35f

/**
 * 详情页侧边栏遮罩向黑压深的比例 —— 浅色 (白天) 主题档.
 * 调小更浅 (0 = 不压深, 纯主题色面板); 白天面板浅、文字图标是深色 (onSurface), 越浅反而对比越高.
 */
private const val TV_DETAILS_RAIL_SCRIM_DARKEN_LIGHT = 0.06f

/** 详情页侧边栏遮罩向黑压深的比例 —— 深色 (黑夜) 主题档. 深色底配浅色文字, 压深无碍可读. */
private const val TV_DETAILS_RAIL_SCRIM_DARKEN_DARK = 0.35f

/**
 * Hero 标题顶部留白的视觉补偿: 标题列自带 top 8dp + headlineLarge 行高顶部内衬约 12dp,
 * 从名义顶部留白中减去, 使字形上缘到屏幕上边界的距离 ≈ 字形左缘到左边界的距离.
 */
private val TV_HERO_TITLE_TOP_TRIM = 20.dp

/** 展开按钮的圆角. */
private val TV_SUMMARY_EXPAND_CORNER = 8.dp

/**
 * 展开按钮常态底色的不透明度 ("玻璃"): 半透明到能透出底下的背景图, 又足以把文字从图上托起来.
 * 全透明的话按钮只剩文字, 在花哨的 backdrop 上认不出是个可按的东西.
 */
private const val TV_SUMMARY_EXPAND_GLASS_ALPHA = 0.4f

/** 选集信息行里剧集简介的行数上限 (全文在长按卡片的本集详情弹窗里). */
private const val TV_EPISODE_DESC_MAX_LINES = 3

/**
 * 别名段落, 进简介全文弹窗的正文末尾. 没有别名就返回 null (弹窗里不多出一个空标题).
 *
 * 原本有个独立的「作品信息」区块 (放送开始 / 话数 / 别名), 2026-09-15 撤掉了: 放送时间与话数
 * hero 的信息带上已经写着 ("已完结 · 全 28 话 · 2023年9月"), 那个块**基本是重复的**, 只多出
 * 精确到日的日期和别名 —— 为这点内容占掉半页不值当.
 */
@Composable
private fun subjectAliasesText(info: SubjectInfo): String? =
    info.aliases.takeIf { it.isNotEmpty() }
        ?.let { stringResource(Lang.subject_details_aliases) + "\n" + it.joinToString(" / ") }

/** 简介全文弹窗底行的一行元信息: 精确到日的放送日期 + 正片话数 (见 [subjectAliasesText]). */
@Composable
private fun subjectMetaLine(info: SubjectInfo, mainEpisodeCount: Int?): String? {
    val parts = buildList {
        if (info.airDate.isValid) {
            add(
                stringResource(
                    Lang.subject_details_air_date_format,
                    info.airDate.year.toString(),
                    info.airDate.month.toString(),
                    info.airDate.day.toString(),
                ),
            )
        }
        if (mainEpisodeCount != null) {
            add(stringResource(Lang.subject_details_total_episodes) + " " + mainEpisodeCount)
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * 截断简介: 正文**不可聚焦**, 溢出时右下角出现展开按钮, 按下开纯文字弹窗读全文.
 *
 * 为什么正文不参与焦点: TV 上每个焦点停留点都要一次按键, 而正文一旦可聚焦就有歧义 ——
 * 「按下键是滚动文字还是移到下一行?」主流流媒体 (Netflix / Prime Video / Disney+) 一律不让正文
 * 可聚焦, 全文放在显式入口后面的弹层里. 弹窗是模态, 里面没有别的焦点目标, 上下键滚动天然无歧义.
 *
 * 尾部按 [DETAILS_TEXT_END_RESERVE] **恒定**预留 (不随是否截断变化): 否则「是否截断」由排版
 * 决定、而预留又会改变排版, 互为因果会抖动. 该常量与集简介共用, 两块正文宽度因此完全一致.
 */
@Composable
private fun TvTruncatedSummary(
    summary: String,
    /** 全文弹窗的标题; null = 不提供展开入口 (全文在别处, 如长按卡片的本集详情弹窗). */
    dialogTitle: String?,
    modifier: Modifier = Modifier,
    /** 文字样式; null 用 bodyMedium. */
    style: TextStyle? = null,
    /** 块内边距; 与集简介右侧时长/日期的上下内收共用同一常量 (两者要行对行齐平). */
    contentPadding: Dp = DETAILS_TEXT_CONTENT_PADDING,
    /** 最大行数; null = 按父级给的高度截断 (占满剩余空间). */
    maxLines: Int? = null,
    /**
     * 最小行数: 恒定预留这么多行的高度, 内容再短也不缩 —— 切集时简介长短不同, 不预留会让
     * 下方卡片行跳动. 与 [maxLines] 取同值即「固定 N 行」.
     *
     * 用 minLines 而不是给容器写死 dp: 排版真正的约束是容器高度, 标称行高又摊不平首行的字体
     * 内衬, 算出来的 dp 只要差几像素末行就被裁掉 (表现为设了 maxLines=3 却只显示 2 行).
     */
    minLines: Int? = null,
    /**
     * 即使没被截断也渲染展开按钮. 调用方需要本块提供页面**唯一**焦点目标时传 true
     * (无分集的条目 / 播放器内嵌介绍页), 否则整页可能没有任何可聚焦元素, 向下导航会整页跳过.
     */
    alwaysShowExpand: Boolean = false,
    /** 展开按钮的外部修饰符 (页面级焦点锚点挂载点). */
    expandModifier: Modifier = Modifier,
    /** 展开按钮上键的显式出口 (内嵌介绍页: 回播放器选集条). */
    onNavigateUp: (() -> Unit)? = null,
    /**
     * 上报当前是否渲染了展开按钮. 调用方据此决定别处的按键落点要不要指向它 ——
     * 按钮不存在时那个请求器未附着, 指过去会变成"按了没反应".
     */
    onExpandButtonPresenceChange: ((Boolean) -> Unit)? = null,
    /**
     * 只在全文弹窗里出现、不进块内截断文字的补充段落 (条目详情页用它放别名).
     *
     * 原本有个独立的"作品信息"区块 (放送开始 / 话数 / 别名), 2026-09-15 撤掉了: 放送时间与话数
     * hero 的信息带上已经写着 ("已完结 · 全 28 话 · 2023年9月"), 那个块**基本是重复的**, 只多出
     * 精确到日的日期和别名 —— 为这点内容占掉半页不值当. 日期进 [dialogMeta], 别名进这里.
     */
    dialogExtra: String? = null,
    /** 全文弹窗底行右端的一行元信息 (见 AniScrollableTextDialog 的 meta). */
    dialogMeta: String? = null,
    /**
     * 展开按钮坐在**正文末行右边** (参照 Prime Video 的"更多"), 正文相应不留底内边距 ——
     * 那一截原本就是给贴在块底的按钮让位的.
     *
     * 只有独立详情页的选集页这么排: 它下面紧接着选集卡, 按钮贴块底时正文与卡片之间白空一行.
     * 内嵌介绍页下面接的是标签墙, 间距另有标定 (见 TV_EMBEDDED_SUMMARY_HEIGHT), 保持原样.
     */
    expandOnLastLine: Boolean = false,
) {
    val textStyle = style ?: MaterialTheme.typography.bodyMedium
    // summary 变化 (切集 / 兜底简介到达) 时重新判定截断
    var truncated by remember(summary) { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    // **正持焦时按钮不销毁** (2026-08-26 真机日志抓到的链条): 进页 episodes 还没到的窗口里
    // 用户按下键, 焦点落到本按钮 (当时 alwaysShowExpand=true, 它是选集页唯一焦点目标);
    // 几十 ms 后 episodes 到位, alwaysShowExpand 翻 false, 简介不长 -> 按钮销毁把焦点带走,
    // 全局兜底把焦点塞给最左的侧边栏 -> "进详情页侧边栏自己弹开". 持焦期间粘住, 失焦后再收.
    var expandFocused by remember { mutableStateOf(false) }
    // 展开按钮的落位: 正文末行的中线与按钮自身高度 (都是排版后才知道的量, 见按钮那里的说明)
    var lastLineCenterPx by remember { mutableFloatStateOf(0f) }
    var expandHeightPx by remember { mutableIntStateOf(0) }
    val contentPaddingPx = with(LocalDensity.current) { contentPadding.toPx() }
    val showExpand = dialogTitle != null && (truncated || alwaysShowExpand || expandFocused)
    LaunchedEffect(showExpand) { onExpandButtonPresenceChange?.invoke(showExpand) }
    Box(modifier) {
        // 内层这一圈 Box 只包住正文的高度. 按钮要贴的是**正文末行**而不是本块的下边界 ——
        // 本块常被 weight 拉满剩余空间, 而正文按整行收敛, 下面必然余出一截; 直接对齐外层
        // BottomEnd 会掉到那一截的底部, 离末行老远.
        Box(Modifier.fillMaxWidth()) {
            Text(
                summary,
                Modifier.fillMaxWidth()
                    .padding(
                        start = contentPadding,
                        top = contentPadding,
                        end = contentPadding,
                        // 见 [expandOnLastLine]: 那一截原本是给贴在块底的按钮让位的, 按钮挪到末行
                        // 右边之后还给正文, 正好多排一行
                        bottom = if (expandOnLastLine) 0.dp else contentPadding,
                    )
                    // 只有可能出按钮时才在这里留位: 没有展开入口的正文块 (集简介, 全文在长按
                    // 弹窗里) 由调用方自己按同一个常量留 —— 那截预留归时长/日期用
                    .ifThen(dialogTitle != null) { padding(end = DETAILS_TEXT_END_RESERVE) },
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines ?: Int.MAX_VALUE,
                minLines = minLines ?: 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    truncated = result.hasVisualOverflow
                    // 末行的墨迹带 (相对本 Box: 正文自己还内缩了 contentPadding), 给按钮定位用
                    val last = (result.lineCount - 1).coerceAtLeast(0)
                    lastLineCenterPx = contentPaddingPx +
                            (result.getLineTop(last) + result.getLineBottom(last)) / 2f
                },
            )
            if (showExpand) {
                TvSummaryExpandButton(
                    onClick = { showDialog = true },
                    onNavigateUp = onNavigateUp,
                    modifier = (
                            if (expandOnLastLine) {
                                // **垂直居中对齐到正文末行**. 按钮 (~28dp) 比一行正文 (~20dp) 高, 而正文块
                                // 的下边界还含着末行的行距下半段 —— 底边对齐会把整个按钮压到末行下面去,
                                // 看着就像正文后面多空了一行. 按内容坐标算而不是靠对齐: 末行位置只有排版
                                // 完才知道.
                                Modifier.align(Alignment.TopEnd)
                                    .padding(end = contentPadding)
                                    .offset {
                                        IntOffset(0, (lastLineCenterPx - expandHeightPx / 2f).roundToInt())
                                    }
                                    .onSizeChanged { expandHeightPx = it.height }
                            } else {
                                // 与正文同一内边距: 按钮下边界与正文末行下边界齐平
                                Modifier.align(Alignment.BottomEnd).padding(contentPadding)
                            }
                            )
                        .onFocusChanged { expandFocused = it.hasFocus } // 见 showExpand: 持焦时粘住
                        .restoreFocusAfter(showDialog)
                        .then(expandModifier),
                )
            }
        }
    }
    if (showDialog && dialogTitle != null) {
        AniScrollableTextDialog(
            title = dialogTitle,
            text = if (dialogExtra.isNullOrBlank()) summary else summary + "\n\n" + dialogExtra,
            onDismissRequest = {
                showDialog = false
            },
            meta = dialogMeta,
        )
    }
}

/**
 * 简介右下角的展开按钮: 圆角矩形 + 文字, 常态半透明玻璃底, 聚焦时填充主题色.
 *
 * 用文字而不是省略号图标: 图标要靠猜, 文字直接说明按下去会发生什么. Apple TV 的做法是在截断
 * 的正文末尾直接接一个 "... More" (tvOS 社区据此做了 TvOSMoreButton 组件), 即入口就在文字被
 * 切断的那个位置 —— 这里沿用同一逻辑, 把它放进正文块的右下角.
 */
@Composable
private fun TvSummaryExpandButton(
    onClick: () -> Unit,
    onNavigateUp: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(TV_SUMMARY_EXPAND_CORNER),
            color = if (focused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = TV_SUMMARY_EXPAND_GLASS_ALPHA)
            },
            interactionSource = interactionSource,
            modifier = onNavigateUp?.let { up ->
                modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                        up()
                        true
                    } else {
                        false
                    }
                }
            } ?: modifier,
        ) {
            Text(
                stringResource(Lang.subject_details_show_more),
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                // 玻璃底上用 onSurface (而非正文的 onSurfaceVariant): 底色已被压深, 弱化色会糊掉
                color = if (focused) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/**
 * Hero 信息带中列: 左 = 统计数字 + 连载信息 (总高对齐左列按钮块); 右 = 标签墙 (三行截断).
 *
 * 标签平时即可聚焦/点击; 放不下时"显示更多"跟在最后一个可见标签右边 (FlowRow overflow).
 * 按下"显示更多"弹出标签菜单 (Popup, 同选集网格菜单形态): 尽可能显示全部标签,
 * 放不下时纵向导航自动滚动; Popup 独立于页面, 页面绝不会跟着滚. 返回键关闭菜单,
 * 焦点回到"显示更多".
 *
 * 点击标签跳转搜索后返回本页: [browseMode] (菜单开合)/[focusedTagIndex]/[restorePending]
 * 由调用方 rememberSaveable 保留, 重组时菜单原样恢复, 焦点直接回到最后聚焦的标签上.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvHeroInfoColumn(
    state: SubjectDetailsState,
    info: SubjectInfo,
    onClickTag: (Tag) -> Unit,
    browseMode: Boolean,
    onBrowseModeChange: (Boolean) -> Unit,
    /** 最后聚焦的标签下标 (-1 无), 跨页面往返恢复焦点用. */
    focusedTagIndex: Int,
    onFocusedTagIndexChange: (Int) -> Unit,
    /** 为 true 时 (点击标签跳转后返回, 菜单开合状态) 菜单打开后恢复焦点到 [focusedTagIndex]. */
    restorePending: Boolean,
    onRestoreConsumed: () -> Unit,
    /** 页面统一焦点锚点调度器: 标签墙恢复 (TAG_WALL) 与菜单关闭归还 (TAG_SHOW_MORE) 走它. */
    anchors: TvFocusScope,
    /** 截断态标签墙的跨页恢复目标下标 (进页快照, -1 = 无): 该标签挂 TAG_WALL 锚点请求器. */
    wallRestoreIndex: Int,
    modifier: Modifier = Modifier,
) {
    val tags = info.tags

    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // 左: 连载信息在上, 垂直中心对齐左列 44dp 圆钮行的中心; 统计数字在下,
        // 垂直中心对齐 38dp 播放按钮的中心 (44 + SpaceBetween 自动 10 + 38 与左列几何同构;
        // 三列底对齐, 总高一致, 顶也是对齐的)
        Column(
            Modifier.height(TV_HERO_MIDDLE_HEIGHT),
            verticalArrangement = Arrangement.SpaceBetween,
            // 列宽 = 两块中较宽者, 窄的一块水平居中 —— 连载信息与统计数字的水平中心对齐
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.height(TV_CAPSULE_SIZE).offset(y = TV_AIRING_ALIGN_TRIM),
                contentAlignment = Alignment.CenterStart,
            ) {
                // 两行内容比锚定盒 (圆钮行高, 32dp) 高: 无界测量 + 居中对齐,
                // 超出部分对称溢出而不是被盒子底边裁掉 (圆钮从 44dp 缩小后两行放不下了)
                Column(
                    Modifier.wrapContentHeight(align = Alignment.CenterVertically, unbounded = true),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        renderSubjectSeason(info.airDate),
                        // 连载信息整体比统计数字小一号 (titleSmall / labelMedium)
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        AiringLabel(
                            state.airingLabelState,
                            style = MaterialTheme.typography.labelMedium,
                            progressColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box(
                Modifier.height(38.dp).offset(y = TV_STATS_ALIGN_TRIM),
                contentAlignment = Alignment.CenterStart,
            ) {
                // 同上: 内容高于锚定盒时无界测量, 对称溢出不裁剪
                Box(Modifier.wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)) {
                    TvCompactStatsRow(info.collectionStats)
                }
            }
        }
        // 右: 标签墙, 占剩余宽度. 容器与左列按钮块同高, 内容顶对齐 —— 顶行顶缘 = 块顶 =
        // 连载信息顶缘 (连载信息盒在中列顶部), 超出块高的部分向下溢出.
        TvHeroTagsWall(
            tags = tags,
            onClickTag = onClickTag,
            browseMode = browseMode,
            onBrowseModeChange = onBrowseModeChange,
            focusedTagIndex = focusedTagIndex,
            onFocusedTagIndexChange = onFocusedTagIndexChange,
            restorePending = restorePending,
            onRestoreConsumed = onRestoreConsumed,
            anchors = anchors,
            wallRestoreIndex = wallRestoreIndex,
            modifier = Modifier.weight(1f).height(TV_HERO_MIDDLE_HEIGHT),
            flowModifier = Modifier.fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .offset(y = TV_TAGS_ALIGN_TRIM),
        )
    }
}

/**
 * 标签墙: 三行截断的 FlowRow (行尾"显示更多"按钮, TAG_SHOW_MORE 锚点) + 弹出的完整
 * 标签菜单. 独立详情页信息带 ([TvHeroInfoColumn]) 与播放器内嵌介绍页信息带
 * ([TvEmbeddedHeroPage]) 共用; 几何差异 (定高/对齐微调) 由 [modifier]/[flowModifier] 注入.
 *
 * wrapContentHeight(unbounded) 由调用方按需传入: FlowRow 需用无限高度测量 —— 若受容器
 * 约束, FlowRow 的 overflow 逻辑会把放不下的一整行标签丢掉 (曾导致少一行).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvHeroTagsWall(
    tags: List<Tag>,
    onClickTag: (Tag) -> Unit,
    browseMode: Boolean,
    onBrowseModeChange: (Boolean) -> Unit,
    focusedTagIndex: Int,
    onFocusedTagIndexChange: (Int) -> Unit,
    restorePending: Boolean,
    onRestoreConsumed: () -> Unit,
    anchors: TvFocusScope,
    wallRestoreIndex: Int,
    modifier: Modifier = Modifier,
    flowModifier: Modifier = Modifier.fillMaxWidth(),
    /** 截断行数 (超出进"显示更多"菜单); 内嵌介绍页空间大, 传更大值. */
    maxLines: Int = 3,
) {
    Box(modifier, contentAlignment = Alignment.TopStart) {
        FlowRow(
            flowModifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = maxLines,
            overflow = FlowRowOverflow.expandIndicator {
                TvShowMoreTagsButton(
                    onClick = { onBrowseModeChange(true) },
                    // 菜单关闭后焦点归还本按钮 (TAG_SHOW_MORE 锚点)
                    modifier = Modifier
                        .tvFocusAnchor(anchors, TvDetailsFocusAnchor.TAG_SHOW_MORE),
                )
            },
        ) {
            tags.forEachIndexed { i, tag ->
                TvTagChip(
                    tag.name,
                    Modifier
                        // 跨页返回的恢复目标标签挂 TAG_WALL 锚点 (附着后由调度器送达并确认)
                        .ifThen(i == wallRestoreIndex) {
                            tvFocusAnchor(anchors, TvDetailsFocusAnchor.TAG_WALL)
                        }
                        .onFocusChanged {
                            if (it.isFocused) onFocusedTagIndexChange(i)
                        }
                        .clickable { onClickTag(tag) },
                )
            }
        }
        TvTagsMenu(
            // 只在本页是栈顶时展开: 点菜单里的标签跳搜索页后 browseMode 仍为真 (返回要照原样恢复), 而菜单是独立的可聚焦
            // 窗口, 不关的话一直盖在搜索页上吃按键, 按确认又点一个标签 (2026-09-14 审查). 返回后本页回到栈顶, 菜单照原样展开
            expanded = browseMode && LocalPageIsForeground.current.value,
            tags = tags,
            onClickTag = onClickTag,
            initialFocusIndex = if (restorePending && focusedTagIndex >= 0) focusedTagIndex else 0,
            onTagFocused = onFocusedTagIndexChange,
            onRestoreConsumed = onRestoreConsumed,
            onDismissRequest = {
                onBrowseModeChange(false)
                anchors.request(TvDetailsFocusAnchor.TAG_SHOW_MORE)
            },
        )
    }
}

/**
 * "显示更多"弹出的标签菜单: 从锚点上方弹出 (同选集网格菜单的形态与定位), 尽可能显示全部标签;
 * 放不下时纵向移动焦点自动滚动 (菜单内恢复默认 BringIntoView), 可导航到所有标签.
 * Popup 独立于页面滚动容器, 页面不会跟着动. 返回键/点击外部关闭.
 *
 * 需组合在锚点 (标签墙) 所在的 Box 内.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TvTagsMenu(
    expanded: Boolean,
    tags: List<Tag>,
    onClickTag: (Tag) -> Unit,
    onDismissRequest: () -> Unit,
    /** 打开时聚焦的标签下标 (跨页返回时为上次聚焦的标签, 平时为 0). */
    initialFocusIndex: Int = 0,
    onTagFocused: (Int) -> Unit = {},
    onRestoreConsumed: () -> Unit = {},
) {
    if (!expanded || tags.isEmpty()) return
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gap = with(density) { 8.dp.roundToPx() }
                val x = anchorBounds.left
                    .coerceAtMost(windowSize.width - popupContentSize.width)
                    .coerceAtLeast(0)
                val y = (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        // 页面级禁用了 BringIntoView; 菜单内恢复默认行为, 焦点纵向移动时自动滚动
        val defaultBringIntoView = remember { object : BringIntoViewSpec {} }
        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoView) {
            val initialIndex = initialFocusIndex.coerceIn(tags.indices)
            val initialModifier = Modifier.tvWindowInitialFocus()
            Surface(
                // Popup 是独立窗口, 按键到不了播放页的根路由 (播放器内嵌详情页也开得出这个菜单)
                Modifier.tvOverlayWindowKeys(onDismissRequest).width(560.dp).heightIn(max = 400.dp),
                shape = RoundedCornerShape(16.dp),
                // 半透明容器 (详情页所有弹出菜单统一), 隐约透出下层内容
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = MENU_CONTAINER_ALPHA),
                shadowElevation = 8.dp,
            ) {
                FlowRow(
                    Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEachIndexed { i, tag ->
                        TvTagChip(
                            tag.name,
                            Modifier
                                .then(if (i == initialIndex) initialModifier else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        onTagFocused(i)
                                        if (i == initialIndex) onRestoreConsumed()
                                    }
                                }
                                .clickable { onClickTag(tag) },
                        )
                    }
                }
            }
        }
    }
}


/** 收藏统计: 竖排单元 —— 数字在上 (细字重), 收藏/在看/想看小字在下. */
@Composable
private fun TvCompactStatsRow(
    stats: SubjectCollectionStats,
    modifier: Modifier = Modifier,
    /** 单元排布; 内嵌介绍页传 SpaceBetween (配 fillMaxWidth, 首末单元与海报左右边界对齐). */
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(16.dp),
    /** 文字整体缩放, 数字与下方小字同步 (1 = 原始大小: 数字 titleMedium / 小字 labelSmall). */
    textScale: Float = 0.8f,
) {
    fun TextStyle.scaled(): TextStyle = if (textScale == 1f) this else copy(
        fontSize = fontSize * textScale,
        lineHeight = if (lineHeight.isSpecified) lineHeight * textScale else lineHeight,
    )

    val resolvedNumberStyle = MaterialTheme.typography.titleMedium.scaled()
    val labelStyle = MaterialTheme.typography.labelSmall.scaled()
    Row(modifier, horizontalArrangement = horizontalArrangement) {
        listOf(
            stats.collect to stringResource(Lang.subject_details_stat_collected),
            stats.doing to stringResource(Lang.subject_details_stat_watching),
            stats.wish to stringResource(Lang.subject_details_stat_wish),
        ).forEach { (count, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    remember(count) { groupThousands(count) },
                    style = resolvedNumberStyle,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                )
                Text(
                    label,
                    style = labelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * TV 标签 chip: 无描边, 低透明度主题色玻璃底 (对齐 M3 state-layer 观感), 紧凑内边距.
 * [modifier] 由调用方注入 focusRequester/onFocusChanged/clickable; clip 在最外层,
 * 点击/聚焦指示随圆角裁切.
 */
@Composable
private fun TvTagChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        Modifier
            .clip(TV_TAG_SHAPE)
            .then(modifier)
            .background(tvGlassColor(0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** [TvTagChip] 的圆角. */
private val TV_TAG_SHAPE = RoundedCornerShape(6.dp)

/**
 * 标签墙的"显示更多"小按钮 (跟在最后一个可见标签右边): 聚焦时玻璃底提示.
 *
 * 结构与 [TvTagChip] 完全同构 (Box + 同字号 + 同内边距) -> 高度严格相同, 与标签
 * 最后一行对齐. 不能用可点击 Surface: M3 会给它套最小交互尺寸 (48dp), 占位变高、
 * 可见部分垂直居中, 看起来比标签矮半截且下沉.
 */
@Composable
private fun TvShowMoreTagsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val containerColor by animateColorAsState(
        if (focused) tvGlassColor() else Color.Transparent,
    )
    Box(
        Modifier
            .clip(TV_TAG_SHAPE)
            .then(modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(Lang.subject_details_show_more),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/**
 * 页面级纵向 BringIntoView 已禁用 (见 [SubjectDetailsTvPage]): focusable 获得焦点时总会发起 bringIntoView
 * 请求, 请求自带焦点元素在本页内的精确边界, 是唯一可靠的"焦点位置"来源 —— 本组件接住它, 但**不滚任何东西**,
 * 只把位置换算成页内露出量交给 [TvDetailsPager] (边缘留 [SECTION_ITEM_REVEAL_MARGIN] 余量).
 *
 * 页在滚动内容中的位置是实测的 (屏幕位置 + 已滚距离, 该和在滚动中恒定), 而非按布局参数解析推算 ——
 * 脚手架的顶部留白/insets 等全部自动包含, 不会有固定偏差.
 */
@OptIn(ExperimentalFoundationApi::class)
/**
 * 角色/制作人员区块的等高骨架 (数据在路上时占位, 见调用处的三态注释).
 *
 * 高度对齐真区块: 标题行按 TextButton 最小高 40dp (真标题行右侧有"查看全部"按钮撑高),
 * 卡行 = FocusHighlightCard 内边距 10dp x2 + PersonCard 头像 48dp = 68dp;
 * 角色一行, 制作人员两行 (STAFF_GRID_ROWS). 差个位数 dp 由滚动锚定兜底, 不追求逐 dp 精确.
 */
/**
 * 评价页 (独立页第四页): 三张评论卡 + 行末"查看全部", 都可聚焦, 确认键进全量弹窗里对应的那一条.
 *
 * 左右键在行内走, 上/下键换页 (见下面的 tvSectionEdge). 排版与"一张卡对一条结果"的理由见
 * [ReviewsSummarySection] 的 KDoc.
 */
@Composable
private fun TvReviewsPage(
    comments: LazyPagingItems<UIComment>,
    totalCount: Int?,
    onShowAll: (initialFocusIndex: Int) -> Unit,
    horizontalPadding: Dp,
    anchors: TvFocusScope,
    sectionNav: TvDetailsSectionNav,
) {
    ReviewsSummarySection(
        comments = comments,
        totalCount = totalCount,
        onShowAll = onShowAll,
        modifier = Modifier
            .padding(horizontal = horizontalPadding)
            .tvFocusAnchor(anchors, TvDetailsFocusAnchor.REVIEWS_SECTION)
            // 上键回上一页 (中间隔着不可聚焦的标题/汇总/摘要, 空间搜索不可靠); 下键就地消费 (本页是页面终点, 防斜跳)
            .tvSectionEdge(sectionNav, TvDetailsSection.REVIEWS, up = true, down = true)
            .focusGroup(),
    )
}

@Composable
private fun RelationsSkeletonSection(sectionSpacing: Dp, horizontalPadding: Dp) {
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f) // 同 TV_CARD_CONTAINER_ALPHA
    val cardShape = RoundedCornerShape(12.dp)

    @Composable
    fun SkeletonBlock(title: String, rows: Int) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                SectionHeader(title)
            }
            repeat(rows) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(cardShape)
                        .background(cardColor),
                )
            }
        }
    }

    Column(
        Modifier.padding(horizontal = horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        SkeletonBlock(stringResource(Lang.subject_details_characters), rows = 1)
        SkeletonBlock(stringResource(Lang.subject_details_staff), rows = 2)
    }
}

/**
 * 详情页的一页. 它只做三件事, 都不碰滚动量:
 *
 * 1. 把自己的**起点**报给 [TvDetailsPager.reportStart] (实测屏幕位置 + 当前滚动量 = 内容坐标系 y, 再减去要留的顶部空隙).
 *    页与页之间因此不定高、不等距 —— 每页只定义自己从哪儿开始 (用户 2026-09-15 拍板).
 * 2. 焦点从页外进来时 [TvDetailsPager.goTo].
 * 3. 本页比一屏高时, 把焦点元素的下缘位置报给 [TvDetailsPager.reveal].
 *
 * 滚动量由 [TvDetailsPager.run] 一个协程按 `起点 + 露出` 独占写入, 所以这里不需要吸附动画、不需要锚定补偿、
 * 也不需要"过渡进行中暂停滚动"的闸 —— 没有第二个写者要防.
 */
@Composable
private fun PageSection(
    pager: TvDetailsPager,
    /** 本页序号 (hero 0, 选集 1, 往下递增), 见 [TV_DETAILS_PAGE_COUNT]. */
    page: Int,
    scrollState: ScrollState,
    /**
     * 本页起点距屏幕顶的留白. 普通页传区块间距 (layoutParams.sectionSpacing):
     * 到位后页上方只剩纯空隙, 上一页的底边正好压在屏幕顶上, 不露出.
     * 选集整页传 [EPISODES_PAGE_VERTICAL_MARGIN] (整页上下各留同样空隙).
     */
    snapTopMargin: Dp,
    /** 跨区块纵向路由 (见下面 focusProperties.onExit); null = 不接管纵向离场. */
    nav: TvDetailsSectionNav? = null,
    /**
     * 焦点从区块外进入本区块时回调一次 (区块内部移动不重复触发).
     *
     * 页面用它记住"焦点最后落在哪一层", 供返回键分层判定. 之所以要记忆而不是现读焦点:
     * 焦点"不在页面任何地方"是一个独立状态 (弹窗是独立窗口), 现读会把它误判成"在别的层".
     */
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val snapMarginPx = with(density) { snapTopMargin.toPx() }
    val revealMarginPx = with(density) { SECTION_ITEM_REVEAL_MARGIN.toPx() }
    var sectionFocused by remember { mutableStateOf(false) }

    val responder = remember(pager, page, snapMarginPx, revealMarginPx) {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect

            // 不滚任何东西, 只把"焦点元素下缘相对本页起点的位置"报上去. 页起点在屏幕顶留了
            // snapMarginPx, 所以下缘在屏幕上的 y = snapMarginPx + rect.bottom; 再留一点余量.
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {
                val rect = localRect() ?: return
                pager.reveal(page, snapMarginPx + rect.bottom + revealMarginPx)
            }
        }
    }
    Box(
        // 起点测量排在**调用方的 modifier 之前** (即最外层): 调用方可能在自己那段挂 graphicsLayer
        // (选集页的预画就是, 它把整块 translationY 挪进可见范围), 而图层变换会进 positionInRoot ——
        // 测量排在图层内侧的话读到的是被挪过的位置, 起点是假的 (同 tailfix29: 当年换页图层挂在测量
        // 外面, 角色区每帧被推着横扫过屏幕).
        Modifier
            .onGloballyPositioned {
                // 本页起点 = 屏幕位置 + 当前滚动量 - 顶部留白, 即**内容坐标系**里的 y (用户滚动不改它,
                // 只随上方内容尺寸变化而变). 前提: 滚动视口顶 == root y0 (TV 全屏无顶栏/insets, 成立);
                // 若将来给页面加顶部 padding, 此处需改为减去实测的视口顶 y.
                // 上方内容长高 (角色/制作人员在 relations 数据 1.5~3 秒后到位才渲染, 一插入就是几百 dp)
                // 时这个值自己变, 驱动器随即把滚动量对齐过去 —— 本页在视口里纹丝不动, 插入发生在屏幕外.
                // 旧版为此专门写了"滚动锚定"去补差值, 现在不需要: 滚动量本来就是从它派生的.
                pager.reportStart(
                    page,
                    it.positionInRoot().y + scrollState.value - snapMarginPx,
                    snapMarginPx + it.size.height,
                )
            }
            .then(modifier)
            .onFocusChanged { state ->
                if (state.hasFocus && !sectionFocused) {
                    pager.goTo(page)
                    onFocused?.invoke()
                }
                sectionFocused = state.hasFocus
            }
            .bringIntoViewResponder(responder)
            // 页内焦点围栏 + **纵向离场一律走路由**.
            //
            // 左右: 取消离组 —— 边缘元素按左右时空间搜索找不到同页目标, 会斜跳到上/下一页, 页面跟着走.
            //
            // 上下: **不再交给空间搜索**. 原来只有一部分区块挂了显式接线 (tvSectionEdge / downFocus / upFocus),
            // 没挂的地方 (首屏下键、角色与制作人员的上键) 靠二维搜索找邻居 —— 而目标那一刻可能在屏幕外、
            // 子项被回收, 于是"有概率"越过整整一页落到别处: 冷进入往下翻直接到第三页、第三页往上翻回到第一页
            // (用户 2026-09-16). 在这里统一接住: 任何方向的纵向离组都取消掉, 改由 [TvDetailsSectionNav] 显式送焦
            // (它再走 TvFocusScope, 目标未附着就挂起等附着). 区块**内部**的上下移动不触发 onExit, 不受影响.
            //
            // 同一页上有两个区块时 (角色 / 制作人员): 往上按本页第一个区块算, 往下按最后一个算 —— 它们之间的
            // 移动是页内移动, 走不到这里.
            .focusProperties {
                onExit = {
                    when (requestedFocusDirection) {
                        FocusDirection.Left, FocusDirection.Right -> cancelFocusChange()

                        FocusDirection.Up -> if (nav != null) {
                            cancelFocusChange()
                            nav.moveUp(TvDetailsSection.entries.first { it.page == page })
                        }

                        FocusDirection.Down -> if (nav != null) {
                            cancelFocusChange()
                            nav.moveDown(TvDetailsSection.entries.last { it.page == page })
                        }

                        else -> {}
                    }
                }
            }
            .focusGroup(),
    ) {
        // 换页过渡的图层 (见 TvDetailsPager): 挂在起点测量 (上面的 onGloballyPositioned) 之**内** ——
        // 挂外面的话图层位移会进 positionInRoot, 起点每帧都在变, 页面会自己横扫过屏幕 (tailfix29 踩过)
        Box(Modifier.graphicsLayer { pager.apply(page, this) }) { content() }
    }
}

/** 页面内的固定焦点锚点, 由页面级 [TvFocusScope] 统一调度. */
private enum class TvDetailsFocusAnchor : TvFocusKey {
    /** Hero 播放按钮 (进页初始焦点 / 返回键回顶 / 侧边栏退出). */
    HERO_PLAY,

    /** 选集轮播行 (focusRestorer 恢复到上次聚焦的卡片). */
    EPISODES_CAROUSEL,

    /** 选集页简介块 (无分集时返回键分层的兜底目标). */
    EPISODES_SUMMARY,

    /** 选集快速跳转网格的入口圆钮 (网格菜单关闭后焦点归还). */
    EPISODE_GRID_ENTRY,

    /** 角色区块 (跨页返回恢复; 请求器与 [TvDetailsSectionNav] 的区块进入落点共用). */
    CHARACTERS_SECTION,

    /** 制作人员区块 (同上). */
    STAFF_SECTION,

    /** 关联条目/评价区块 (同上). */
    BELOW_SECTION,

    /** 评价整页 (独立页第四页) 的进入落点. */
    REVIEWS_SECTION,

    /** 标签墙上的恢复目标标签 (点标签进搜索页返回时). */
    TAG_WALL,

    /** 标签墙行尾"显示更多"按钮 (标签菜单关闭后焦点归还). */
    TAG_SHOW_MORE,
}

private val TvFocusScope.heroPlay get() = requesterOf(TvDetailsFocusAnchor.HERO_PLAY)
private val TvFocusScope.episodesCarousel get() = requesterOf(TvDetailsFocusAnchor.EPISODES_CAROUSEL)
private val TvFocusScope.episodesSummary get() = requesterOf(TvDetailsFocusAnchor.EPISODES_SUMMARY)
private val TvFocusScope.charactersSection get() = requesterOf(TvDetailsFocusAnchor.CHARACTERS_SECTION)
private val TvFocusScope.staffSection get() = requesterOf(TvDetailsFocusAnchor.STAFF_SECTION)
private val TvFocusScope.belowSection get() = requesterOf(TvDetailsFocusAnchor.BELOW_SECTION)
private val TvFocusScope.reviewsSection get() = requesterOf(TvDetailsFocusAnchor.REVIEWS_SECTION)

/**
 * 页面纵向区块, 按导航顺序排列 (跨区块 上/下 键的路由依据, 见 [TvDetailsSectionNav]).
 *
 * [page] 是它所属的页 (见 [TvDetailsPager]). 区块比页多一个: 角色与制作人员在**同一页**上,
 * 它们之间的上下键只是页内移动, 不换页 —— 页号在这里定义一次, 别在调用处写数字.
 */
private enum class TvDetailsSection(val page: Int) {
    /** 首屏: 独立页 = Hero (标题/简介/信息带); 内嵌变体 = 介绍页 (海报/简介/标签墙/评价). */
    HERO(0),

    /** 选集整页 (仅独立页; 内嵌变体的选集条在播放器控制层, 不在本页). */
    EPISODES(1),

    /** 角色区块 (仅独立页; 内嵌变体由播放器胶囊面板承担). 无数据不组合. */
    CHARACTERS(2),

    /** 制作人员 (仅独立页; 与角色同页, 进入该页时角色行在页首). 无数据不组合. */
    STAFF(2),

    /**
     * 评价整页 (仅独立页, 见 TvReviewsPage): 评论卡铺满一屏, 上一页底部只露出它的标题
     * (用户 2026-09-15). 内嵌变体的评价仍在介绍页里, 不单独成页.
     */
    REVIEWS(3),

    /**
     * 关联条目 + 独立页的作品信息表 (信息表不可聚焦, 排在页首 —— 焦点下到
     * 关联条目时本页到位, 信息表正好露出). 无可聚焦内容时上一页即页面终点.
     */
    BELOW(3),

}

/**
 * 跨区块纵向导航路由: TV 上跨区块的空间焦点搜索不可靠 (中间隔大段不可聚焦内容时
 * 落错或落空), 区块边缘元素的 上/下 键必须显式送焦点. 过去各处手接 FocusRequester
 * (条件挂载 + 逐参数穿透), 这里统一成一张有序区块表 ——
 *
 * - 每个区块经 [register]/[entry] 提供进入落点 (焦点组容器, requestFocus 经 enter
 *   落到第一个可聚焦子项), 经 [setPresent] 报告当前是否存在 (无内容的区块被跳过);
 * - 边缘元素只声明"我在区块 X 的 上/下 边缘" ([tvSectionEdge] 修饰符, 或把
 *   [samePageDownTargetFrom] 挂到 focusProperties), 落点解析 (下一个存在的区块) 全在本类;
 * - 最顶区块再按上走 [onExitTop] 出口 (内嵌变体回播放器选集条);
 *   最底区块按下消费按键 (页面终点, 防空间搜索斜跳到别的区块).
 *
 * 与页面级 [TvFocusScope] 分工: scope 管程序化送焦; 这里管方向键驱动的相邻区块移动.
 * 纵向滚动仍由 [TvDetailsPager] 按当前页派生, 与两者正交.
 *
 * [present] 与 [onExitTop] 是普通字段, 每次组合从头赋值: 事件处理只在按键时读取;
 * 组合期唯一的读者 ([samePageDownTargetFrom] 给角色行传落点) 与写入同处一个重组作用域
 * (该作用域本就读 paging itemCount, 数量变化必然整体重组), 不需要快照状态.
 */
@Stable
private class TvDetailsSectionNav {
    /** 最顶区块再按上的出口; null = 页顶即终点 (不消费, 交回空间搜索). */
    var onExitTop: (() -> Unit)? = null

    private val entries = mutableMapOf<TvDetailsSection, FocusRequester>()
    private val present = mutableSetOf<TvDetailsSection>()

    /** [section] 的进入落点请求器 (挂到区块根焦点组; 惰性创建). */
    fun entry(section: TvDetailsSection): FocusRequester =
        entries.getOrPut(section) { FocusRequester() }

    /** 复用已有请求器 (如页面 scope 的锚点) 作为 [section] 的进入落点. */
    fun register(section: TvDetailsSection, requester: FocusRequester) {
        entries[section] = requester
    }

    /** 组合期更新: [section] 当前是否存在 (未组合/无内容的区块在路由中被跳过). */
    fun setPresent(section: TvDetailsSection, value: Boolean) {
        if (value) present.add(section) else present.remove(section)
    }

    /**
     * [from] 之下第一个存在区块的进入落点, **仅当它与 [from] 同页**; 跨页或已是最底时 null.
     *
     * 跨页**不能**走静态落点: 它绕过 [moveDown] 的三样东西 —— 换页闸 (长按会一口气连跳两页)、以显示页为准的起点
     * 钳制、以及经 [send] 的 TvFocusScope 送焦 (裸 FocusRequester 在目标未附着时静默失败, 按键被消费而焦点没动,
     * 正是"往上翻有概率回到第一页"那一类). 同页移动不经过 [PageSection] 的 onExit (它只在离开区块时触发),
     * 所以同页这一档仍需要静态落点: 角色行 -> 制作人员就是它.
     */
    fun samePageDownTargetFrom(from: TvDetailsSection): FocusRequester? =
        TvDetailsSection.entries.firstOrNull { it.ordinal > from.ordinal && it in present }
            ?.takeIf { it.page == from.page }
            ?.let { entry(it) }

    private fun prevPresent(from: TvDetailsSection): TvDetailsSection? =
        TvDetailsSection.entries.lastOrNull { it.ordinal < from.ordinal && it in present }

    fun canMoveUp(from: TvDetailsSection): Boolean =
        prevPresent(from) != null || onExitTop != null

    /**
     * 送焦到某个区块. 由页面注入 (走 [TvFocusScope]: 未附着则请求悬挂、锚点一附着即送、并确认真的到位).
     *
     * **不能用裸 `FocusRequester.requestFocus()`**: 目标那一刻没附着时它直接抛异常, 原来外面包着 `runCatching`
     * 吞掉 —— 于是按键被消费、焦点却没动, 最后被页面/全局兜底塞到别处 (实测就是首屏的播放按钮, 表现为
     * "从第三页往上翻有概率回到第一页"). 真机日志里那行 `FocusRequester is not initialized` 就是它
     * (2026-09-16, 冷进入时选集区块刚组合 23ms 后).
     */
    var send: ((TvDetailsSection) -> Unit)? = null

    /** 上一次换页还没走够 (见 TvDetailsPager.isStepping): 长按时据此一页一页地走, 不让后面的换页盖掉前面的. */
    var stepping: (() -> Boolean)? = null

    /**
     * 闸期间按的那一下**推迟执行**而不是丢掉 (页面注入; 只保留最后一次).
     *
     * 闸的目的是"一页一页地走", 不是"吃掉输入" —— 原来直接 return 把按键丢了, 于是连按两下第二下没反应
     * (用户 2026-09-16). 推迟之后: 长按连发 (167ms 一次) 期间反复覆盖同一个待办, 闸一开走一页, 节奏与原来一样;
     * 而刻意的连按两下两下都算数. 待办里**重新走一遍** moveDown/moveUp, 按闸开那一刻的显示页重算路线, 不用旧目标.
     */
    var defer: ((() -> Unit) -> Unit)? = null

    /** 屏幕上正显示第几页 (见 TvDetailsPager.current). */
    var page: (() -> Int)? = null

    /**
     * 方向键真正的起点区块 —— **焦点所在的页与屏幕上显示的页可能不是同一页**.
     *
     * 返回键是特例: 它直接改页 ([TvDetailsPager.goTo] 同步写 current), 焦点却走异步送焦 (锚点附着才送).
     * 这中间画面已经在新页上、焦点还留在旧页, 按方向键就会按**旧页**算路线: 在第二页按返回再按下,
     * 得到的是 `moveDown(EPISODES)` = 第三页 (用户 2026-09-16). [stepping] 那道闸只盖住换页时长的
     * 一部分, 按得比它晚就必现 —— 不是竞态, 是两个量本来就会不一致.
     *
     * 纵向移动的语义是"从**看得见的**那一页往下 / 往上", 所以不一致时一律以页为准: 往下取该页最后一个
     * 区块, 往上取第一个 (与 [PageSection] 交给本类的口径相同).
     */
    private fun originOf(from: TvDetailsSection, down: Boolean): TvDetailsSection {
        val p = page?.invoke() ?: return from
        if (p < 0 || from.page == p) return from // p < 0 = 翻页器还没接管 (见 TvDetailsPager.displayedPage)
        return (if (down) TvDetailsSection.entries.lastOrNull { it.page == p }
        else TvDetailsSection.entries.firstOrNull { it.page == p }) ?: from
    }

    /** [from] 之下第一个存在的区块; null = 已是最底. */
    private fun nextPresent(from: TvDetailsSection): TvDetailsSection? =
        TvDetailsSection.entries.firstOrNull { it.ordinal > from.ordinal && it in present }

    /**
     * 从 [from] 的下缘向下: 焦点送往下一个存在的区块. 恒返回 true ——
     * 没有下一区块时也消费按键 (页面底缘, 防斜跳; "无关联条目时下键无落点"属预期).
     */
    fun moveDown(from: TvDetailsSection): Boolean {
        val origin = originOf(from, down = true)
        val target = nextPresent(origin) ?: return true
        // 闸只管**换页**: 同一页内的区块移动 (评价 -> 关联条目) 被闸住的话, 换页后紧接着按的那一下会被吞掉,
        // 观感是"最后这一下焦点运动有延迟" (用户 2026-09-16); 而已经停稳再按就正常 —— 那时窗口早过了
        if (target.page != origin.page && stepping?.invoke() == true) {
            defer?.invoke { moveDown(from) }
            return true
        }
        send?.invoke(target) ?: runCatching { entry(target).requestFocus() }
        return true
    }

    /** 从 [from] 的上缘向上: 焦点送往上一个存在的区块, 页顶走 [onExitTop]; 返回是否消费. */
    fun moveUp(from: TvDetailsSection): Boolean {
        val origin = originOf(from, down = false)
        prevPresent(origin)?.let { target ->
            if (target.page != origin.page && stepping?.invoke() == true) {
                defer?.invoke { moveUp(from) } // 同 [moveDown]: 闸只管换页, 且推迟而不是丢掉
                return true
            }
            send?.invoke(target) ?: runCatching { entry(target).requestFocus() }
            return true
        }
        onExitTop?.let {
            it()
            return true
        }
        return false
    }
}

/**
 * 声明本元素处于 [section] 的纵向边缘: 声明方向的按键不交给空间焦点搜索,
 * 由 [nav] 显式路由到相邻区块. KeyUp 与 KeyDown 同进退 (释放事件漏给下层会多走一步).
 */
private fun Modifier.tvSectionEdge(
    nav: TvDetailsSectionNav,
    section: TvDetailsSection,
    up: Boolean = false,
    down: Boolean = false,
): Modifier = onPreviewKeyEvent { event ->
    when {
        up && event.key == Key.DirectionUp ->
            if (event.type == KeyEventType.KeyDown) nav.moveUp(section) else nav.canMoveUp(section)

        down && event.key == Key.DirectionDown ->
            // 下缘恒消费 (见 [TvDetailsSectionNav.moveDown]), 释放事件同样吞掉
            if (event.type == KeyEventType.KeyDown) nav.moveDown(section) else true

        else -> false
    }
}

/**
 * Hero 全屏背景图 (页面背景层, 不随内容滚动): 贴顶/贴右出血, 左缘与底缘渐变入页面背景色,
 * 随滚动淡出以免与滚上来的内容争夺可读性.
 */
@Composable
private fun TvHeroBackdrop(
    imageUrl: String,
    scrollState: ScrollState,
    onSuccess: (AniImageLoadSuccess) -> Unit,
    /** 放大转场的起始框 (根坐标), 见 [TvHeroZoomLayer]; null = 本页常规的全屏背景. */
    zoomFrom: Rect? = null,
    /** 放大进度 0..1, 绘制里读 (不进组合); 常规背景恒 1. */
    zoomT: () -> Float = { 1f },
    /**
     * 放大期间的羽化要不要画, 绘制里读: 图上屏之前不画 —— 羽化不依赖图, 照常画的话透明等待期里它会按 hero 框的
     * 位置作为一条深色带压在列表页的卡片上 (2026-09-10 录屏: 推荐行第三张往右顶上一条黑带).
     */
    featherOn: () -> Boolean = { false },
    /** 组合着但不画: 放大会话进行中真页用它提前把位图加载好, 接手那一帧再显示. */
    hidden: Boolean = false,
    /** 额外乘上的不透明度, 绘制里读: 放大换图接手时的淡入 (见真页的 handoff). */
    fadeInAlpha: () -> Float = { 1f },
    /** 盖在图上的一层黑的不透明度, 绘制里读: 放大 / 缩回换图时的压暗 (见 tvHeroSwapDim). 只画在图这一层 (随缩放), 不开离屏. */
    extraDim: () -> Float = { 0f },
    /**
     * 列表页压在这张图上的那套遮罩 (见 [TvBackdropTreatment]): 转场画的是它与本页那份的**插值**.
     *
     * 原来是两套各画各的再交叉淡入 —— 中途"两套各有一部分同时存在", 不等于形状从 A 连续变到 B, 两端各留一个台阶
     * (2026-09-16 逐帧实测: 起跑那一帧右半区亮度掉 16%, 观感是"背景提前变成详情页的样子", 连补三轮没补住).
     */
    sourceTreatment: TvBackdropTreatment? = null,
    /**
     * 本层底下垫的是这个纯色时 (放大那一层), 底缘渐隐直接画成它的渐变, 不用 DstOut 擦除、**不开离屏缓冲**: 擦掉 a 露出
     * 纯色 C 与在图上叠一层 alpha a 的 C, 逐像素相同. 离屏的代价在放大期间特别大 —— 遮罩渐入与羽化每帧都在变, 整块
     * 全屏离屏缓冲每帧重画一遍 (2026-09-10 实测 4K 每帧 GPU 20~30ms, 交叉淡入同期 11~12ms). 顺带图少一次重采样
     * (离屏是先放大画进全屏缓冲再缩回去), 与列表页 hero 直接画在框里的那张更一致. null = 常规 (底下是动态渐变, 必须擦).
     */
    solidUnderlay: Color? = null,
    /**
     * 本层是**缩回**而不是放大.
     *
     * 软边半径两个方向的走势**相同**: 都是"图铺满全屏时为 0, 贴回 hero 框时最宽" —— 放大是从宽收到 0, 缩回是从 0 长到宽.
     * 用 `1 - t` 表达对两者都成立 (t = 1 恒为满屏). 2026-09-16 一度把缩回改成按 t 算, 于是落位那一刻羽化突然收没、
     * 黑边一下子不见了, 正好在缩小结束那一帧跳一下 (用户逐帧: 相邻两帧逐像素相同后猛跳一步). 本参数现在只用于注释
     * 与将来可能的分向调参, 不再改变走势.
     */
    shrinking: Boolean = false,
    /** 停留够久后换原图档加清 (见 [HeroBackdropSharpeningOverlay]). 缩回备用层传 false: 它常驻组合、平时不画, 加清只会白占一张原图位图. */
    sharpen: Boolean = true,
    /** 向下滚动的淡出进度 (0..1) 由调用方给, 绘制里读; 返回 null 时照常按滚动量算. 见真页的 TvDetailsPager (换页的滚动是跳的). */
    scrollFade: () -> Float? = { null },
) {
    // 自己的框 (根坐标), 与起始框相减得到位移; 布局回调里写、绘制里读, 不进组合
    var ownBounds by remember { mutableStateOf<Rect?>(null) }
    // 放大期间的羽化边 (见 drawWithContent): 列表页 hero 的左缘 / 底缘是渐入页面底色的, 本页只有左侧 scrim 与底缘
    // 部分擦除, 缩放中途图的左边、下边是硬的 (用户逐帧看到). 用列表页同一套渐变 (卡片态起止) 画在同一层里, 到位后
    // 就是本页自己的边. 颜色取本层底下垫的那个纯色 (放大层的底): 边缘要与周边**完全同色**, 浅色主题下外壳色与页面
    // 底色并不相同. 停点只算一次, 每帧只改渐变的起止坐标 (渲染线程的渐变缓存按停点命中)
    val featherColor = solidUnderlay ?: AniThemeDefaults.shellBackgroundColor
    val leftFeatherStops = remember(featherColor) {
        tvBackdropFadeFromBlackStops(
            start = TV_BACKDROP_LEFT_FADE_START / TV_BACKDROP_LEFT_FADE_END, end = 1f, color = featherColor,
        )
    }
    val bottomFeatherStops = remember(featherColor) { tvBackdropFadeToBlackStops(start = 0f, end = 1f, color = featherColor) }
    // 软边实验 (见 TvPolishFlags.zoomSoftEdge): DstOut 只看 alpha, 色无关. 外缘全擦 -> 内侧不擦
    val eraseStops = remember { tvBackdropFadeFromBlackStops(start = 0f, end = 1f, maxAlpha = 1f, color = Color.Black) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (zoomFrom != null) Modifier.onGloballyPositioned { ownBounds = it.boundsInRoot() } else Modifier)
                .graphicsLayer {
                    if (hidden) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    // 向下滚动逐渐淡出, 但保留半透明而非完全消失
                    val progress = scrollFade() ?: (scrollState.value / HERO_BACKDROP_FADE_DISTANCE.toPx()).coerceIn(0f, 1f)
                    alpha = (1f - progress * (1f - HERO_BACKDROP_MIN_ALPHA)) * fadeInAlpha()
                    // 底部渐隐用 DstOut 擦除本层 alpha, 需要离屏合成; 垫纯色时改画同色渐变, 不必离屏 (见 solidUnderlay)
                    // 软边要擦本层已画好的 alpha, 必须离屏; 其余情形照旧 (见 solidUnderlay).
                    //
                    // **只在真会画软边的那几帧开**: 带宽走 (1 - t)^4, 接近满屏时很快掉到半个像素以下就不画了,
                    // 而原来的判据只是 `软边开 && t < 1`, 于是整整 250ms 都白开着离屏 (2026-09-16 审查).
                    // 不画软边的帧里本层是不透明的, 离屏与否逐像素相同, 关掉不改观感
                    compositingStrategy = when {
                        // ownBounds 还没量到时保守地开着: 宁可多开一两帧, 不要在动画中间来回换缓冲
                        TvPolishFlags.zoomSoftEdge && zoomFrom != null && zoomT() < 1f &&
                                ownBounds?.let { tvHeroSoftEdgeVisible(zoomFrom, it, size, zoomT()) } != false ->
                            CompositingStrategy.Offscreen

                        solidUnderlay != null -> CompositingStrategy.Auto
                        else -> CompositingStrategy.Offscreen
                    }
                    // 放大缩放的是**整层** (图 + 底缘擦除 + 左侧 scrim + 羽化), 不是只缩图: 遮罩跟着图一起缩, 缩放中途
                    // 边缘才是软的 (只缩图的话遮罩留在全屏坐标, 图的四条硬边全露出来). 这层本来就是离屏的, 不多花一层
                    val t = zoomT()
                    val own = ownBounds
                    if (zoomFrom != null && t < 1f && own != null && size.width > 0f && size.height > 0f) {
                        // 两个框都是 16:9 的 Crop, 起始态 = 把全屏那层按框缩放平移过去, 与列表页那张像素级重合
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = lerp(zoomFrom.width / size.width, 1f, t)
                        scaleY = lerp(zoomFrom.height / size.height, 1f, t)
                        translationX = lerp(zoomFrom.left - own.left, 0f, t)
                        translationY = lerp(zoomFrom.top - own.top, 0f, t)
                    }
                }
                .drawWithContent {
                    drawContent()
                    val swapDim = extraDim()
                    if (swapDim > 0f) drawRect(Color.Black, alpha = swapDim)
                    // 底部渐隐: 擦除图片自身的透明度, 露出下层的动态渐变背景, 而不是画一层纯背景色盖住它 (否则浅色主题下
                    // 是一片突兀的纯白). 起点压后 + 底缘留一成不擦: 原来从 0.62 起擦、0.98 擦光, 屏幕下四成完全没有图,
                    // 选集卡片那一带整片发黑 (常被当成"多压了一层黑遮罩", 其实是图被擦没了).
                    // 放大转场中本页自己的两层遮罩**随进度渐入** (t = 0 时不画): t = 0 那一刻这一层必须与列表页 hero
                    // 像素级一样 —— 一上来就压 0.6 黑的左 scrim 会让图"先黑一下" (用户 2026-09-10 截图).
                    // 每条渐变都**裁到它不透明的那一段**再画 (clipRect, 渐变坐标不变): drawRect 铺满整层时, 透明的部分 GPU
                    // 照样逐像素混合一遍. 放大期间这几条渐变每帧都要重画, 4K 下每条全屏混合约 2~3ms (2026-09-10 实测
                    // 4K 放大每帧 GPU 30ms, 去掉离屏缓冲后几乎没降 —— 大头是六次全屏填充, 不是离屏)
                    // **一份声明 + 插值**, 不再是"两套各画各的再交叉淡入" (见 [TvBackdropTreatment]):
                    // 画的是 lerp(列表页那份, 本页这份, t) —— t = 0 逐像素等于列表页, t = 1 逐像素等于本页,
                    // 中间是渐变带的位置与浓淡在连续变形, 结构上不会有台阶.
                    //
                    // 每条渐变仍只画它不透明的那一段 (见 tvBackdropTreatmentPainter): 铺满整层时透明部分 GPU 照样
                    // 逐像素混合一遍, 4K 下每条全屏混合约 2~3ms (2026-09-10 实测: 放大每帧 GPU 30ms, 大头是全屏填充次数)
                    val t = zoomT()
                    val ownTreatment = tvHeroBackdropTreatment(solidUnderlay)
                    val treatment = if (zoomFrom != null && t < 1f) {
                        lerpTvBackdropTreatment(sourceTreatment ?: TvBackdropTreatment(), ownTreatment, t)
                    } else {
                        ownTreatment
                    }
                    with(tvBackdropTreatmentPainter(size, treatment)) { draw(1f) }
                    // 放大期间的羽化: 图的左边 / 下边**每一帧都满遮盖成周边的底色**, 收掉羽化靠收窄宽度, 不降强度. 旧做法
                    // 强度按 1 − t³ 衰减, 放大到一半边上还透出一成多原图, 周边是纯黑, 就是一条亮边 (用户 2026-09-10 截图;
                    // 放大还画在详情页里的时候, 周边是带图色调的渐变底, 所以看不出). 羽化带在屏幕上保持列表页 hero 的宽度,
                    // 直到图的边离屏幕边比这宽度还近, 才按剩下的距离收窄, 边碰到屏幕边那一刻正好收没: 全程连续, 到位也不跳.
                    // 渐变矩形越过图的边再多画 TV_HERO_ZOOM_FEATHER_OUTSET_PX 个屏幕像素: 图最边上那一排是半覆盖的,
                    // 羽化自己的边若也停在同一处, 两层半透明叠起来仍会透出原图 (实测底边那一排亮度 62, 周边 0)
                    val box = ownBounds
                    val softEdge = TvPolishFlags.zoomSoftEdge
                    if (softEdge && zoomFrom != null && box != null && featherOn() &&
                        tvHeroSoftEdgeVisible(zoomFrom, box, size, t)
                    ) {
                        // 真透明软边: 四条边各擦一条窄带, 带宽随"图越出源框的距离"生长、落地收没, 四角两带相乘自然连续
                        val sx = lerp(zoomFrom.width / size.width, 1f, t)
                        val sy = lerp(zoomFrom.height / size.height, 1f, t)
                        val short = minOf(zoomFrom.width, zoomFrom.height)
                        val base = short * TV_HERO_SOFT_EDGE_FRACTION
                        // 下缘单独一档: 它本来就压着一条宽渐隐带, 需要的化开宽度与两侧不是一回事
                        val baseBottom = short * TV_HERO_SOFT_EDGE_BOTTOM_FRACTION
                        // 半径随"离满屏还有多远"长: 满屏时 0, 贴回 hero 框时最宽 —— 放大缩回同一条式子, 见 [shrinking].
                        // 过一条指数曲线 (前段慢、末段快): 线性时落位前那一小段仍看得出突变, 因为羽化要在很短的时间里
                        // 接上列表页本身很宽的那条渐隐带 (见 TvPolishFlags.zoomSoftEdgeCurve)
                        val remaining = (1f - t).coerceAtLeast(0f).pow(TV_HERO_SOFT_EDGE_CURVE)
                        fun bandFor(gap: Float, b: Float = base, scale: Float = sx) =
                            tvHeroSoftEdgeBand(gap, b, remaining, scale)
                        val w = size.width
                        val h = size.height
                        bandFor(zoomFrom.left - box.left).let { if (it > 0.5f) {
                            val e = it
                            drawRect(
                                Brush.horizontalGradient(*eraseStops, startX = 0f, endX = e),
                                size = Size(e, h), blendMode = BlendMode.DstOut,
                            )
                        } }
                        bandFor(box.right - zoomFrom.right).let { if (it > 0.5f) {
                            val e = it
                            drawRect(
                                Brush.horizontalGradient(*eraseStops, startX = w, endX = w - e),
                                topLeft = Offset(w - e, 0f), size = Size(e, h), blendMode = BlendMode.DstOut,
                            )
                        } }
                        bandFor(zoomFrom.top - box.top, scale = sy).let { if (it > 0.5f) {
                            val e = it
                            drawRect(
                                Brush.verticalGradient(*eraseStops, startY = 0f, endY = e),
                                size = Size(w, e), blendMode = BlendMode.DstOut,
                            )
                        } }
                        bandFor(box.bottom - zoomFrom.bottom, baseBottom, sy).let { if (it > 0.5f) {
                            val e = it
                            drawRect(
                                Brush.verticalGradient(*eraseStops, startY = h, endY = h - e),
                                topLeft = Offset(0f, h - e), size = Size(w, e), blendMode = BlendMode.DstOut,
                            )
                        } }
                    }
                    if (!softEdge && zoomFrom != null && t < 1f && featherOn() && box != null) {
                        val sx = lerp(zoomFrom.width / size.width, 1f, t)
                        val sy = lerp(zoomFrom.height / size.height, 1f, t)
                        val outX = TV_HERO_ZOOM_FEATHER_OUTSET_PX / sx
                        val outY = TV_HERO_ZOOM_FEATHER_OUTSET_PX / sy
                        // 左边. 距离、宽度都按屏幕像素算, 画的时候除以缩放换回本层坐标
                        val gapLeft = zoomFrom.left - box.left
                        val bandLeft = zoomFrom.width * TV_BACKDROP_LEFT_FADE_END
                        if (gapLeft > 0f && bandLeft > 0f) {
                            val k = (gapLeft * (1f - t) / minOf(bandLeft, gapLeft)).coerceAtMost(1f)
                            val end = bandLeft * k / sx
                            if (end > 0.5f) drawRect(
                                brush = Brush.horizontalGradient(*leftFeatherStops, startX = 0f, endX = end),
                                topLeft = Offset(-outX, -outY),
                                size = Size(end + outX, size.height + 2 * outY),
                            )
                        }
                        // 下边
                        val gapBottom = box.bottom - zoomFrom.bottom
                        val bandBottom = zoomFrom.height * (1f - TV_BACKDROP_BOTTOM_FADE_START)
                        if (gapBottom > 0f && bandBottom > 0f) {
                            val k = (gapBottom * (1f - t) / minOf(bandBottom, gapBottom)).coerceAtMost(1f)
                            val band = bandBottom * k / sy
                            if (band > 0.5f) drawRect(
                                brush = Brush.verticalGradient(*bottomFeatherStops, startY = size.height - band, endY = size.height),
                                topLeft = Offset(-outX, size.height - band),
                                size = Size(size.width + 2 * outX, band + outY),
                            )
                        }
                    }
                },
        ) {
            AsyncImage(
                imageUrl,
                contentDescription = null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // 与列表页 hero 同一个缓存键 (见 tvHeroBackdropDecodeAtOriginalSize): 内存命中, 不重解码
                decodeAtOriginalSize = tvHeroBackdropDecodeAtOriginalSize(imageUrl),
                onSuccess = onSuccess,
            )
            if (sharpen) HeroBackdropSharpeningOverlay(imageUrl)
        }
    }
}

/**
 * **试过并撤回: "让图的放大跑在窗口前面"** (2026-09-16).
 *
 * 动机: hero -> 全屏只有 1.515 倍 (列表页 hero 是 `fillMaxHeight(0.66)` + 16:9 = 634x356dp, 详情页 960x540),
 * 这个比例落在"最难看的区间" —— 源已占屏幕 62% 宽, 等比放大读起来像整个画面往前顿一下, 不像"这个东西打开了"
 * (用户: "直接按比例放大, 似乎不常见"; Apple 对自家 zoom 转场也劝阻"源几乎占满窗口"时用它).
 * 做法是照 Material 容器变形把"遮罩"与"内容缩放"分成两条曲线, 让图先到位、多出来的部分裁掉.
 *
 * **为什么撤回**: 两端共享**同一张图**且都要铺满各自的框, 于是三种情况互斥 ——
 * 内容缩放 = 窗口 ⇒ 等比放大 (一点不裁); 内容 > 窗口 ⇒ 必然裁掉一圈 (用户: "变大的画面超出画面");
 * 内容 < 窗口 ⇒ 填不满露底。"放大感更小"和"一点不裁"在这个前提下**不可兼得**, 不是调参能解决的.
 *
 * 想两全只有改变前提: 让两端显示的**不是同一张图** —— 即 Apple TV 的做法 (从聚焦卡片长出一个圆角容器,
 * 详情页内容在容器里淡入, 浏览页留在下面压暗不动; 2026-09-16 在 Shield 上逐帧录到). 那是另一套转场,
 * 顺带把比例从 1.5 倍变成 6~9 倍 —— zoom 在那个区间才读得出"打开". 见 memory project-tv-details-nav-stability.
 */

/**
 * 放大转场那一层 (见 [TvHeroZoomHandoff]): 从导航后第一帧起按列表页 hero 的框画同一张图, 图一上屏就转不透明
 * (列表页随之硬切) 并开始放大, 直到真页的背景接手. 挂在占位页 / 真页的切换之外 (SubjectDetailsPageVariant.Underlay),
 * 两者切换时它不重建.
 */
@Composable
fun TvHeroZoomLayer() {
    // 进详情页走的是哪条转场, 每进一次页一行: 用户说"怎么没放大 / 怎么还在放大"时, 对照视觉效果档一眼看出原因
    val visualEffects = LocalThemeSettings.current.visualEffects
    LaunchedEffect(Unit) {
        zoomLogger.info {
            val s = TvHeroZoomHandoff.session
            val kind = when {
                s == null -> "crossfade"
                s.url != s.detailsUrl -> "zoom (cross-image)"
                else -> "zoom"
            }
            "Details entry: $kind, visual effects $visualEffects"
        }
    }
    val session = TvHeroZoomHandoff.session ?: return
    var loaded by remember(session) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // 换图的放大 (继续观看: 列表页是单集剧照, 详情页是整部背景): 详情页那张也在这一层组合着 (不画); 起跑时它已加载好, 就在放大途中
    // 压暗、最暗时换过去 (tvHeroSwapDim), 落地即是详情页的图、直接接手. 半路才加载好就不换 (半路才开始压暗会一下子暗下去), 照旧落地后淡入
    val crossImage = session.url != session.detailsUrl
    var detailsLoaded by remember(session) { mutableStateOf(false) }
    var swapPlanned by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        if (!session.started) {
            withFrameNanos { }
            val ready = withTimeoutOrNull(TV_HERO_ZOOM_LOAD_BUDGET_MILLIS) { snapshotFlow { loaded }.first { it } }
            if (ready == null) {
                // 页面自己补淡入 (见 rememberTvZoomAbortFade), 否则硬切出来像闪一下
                zoomLogger.info { "Details entry: zoom abandoned (image not ready in ${TV_HERO_ZOOM_LOAD_BUDGET_MILLIS}ms), fade in" }
                TvHeroZoomHandoff.endSession(session)
                return@LaunchedEffect
            }
            // onSuccess 之后下一帧图才真正上屏: 在那一帧起跑 —— 底色转不透明、列表页硬切、放大开始都落在这一帧
            session.start(withFrameNanos { it })
        }
        swapPlanned = crossImage && detailsLoaded
        while (session.t < 1f) {
            val now = withFrameNanos { it }
            // 放大途中按了返回、缩回已开始 (TvHeroZoomHandoff.shrink): 停在当前进度等出栈 —— 缩回从这里起, 不先涨满再缩;
            // 本页也走不到接手 (进页转场中途出栈时 Nav3 会倒着把它走完, 本页还要在组合里留一阵, 见 shrinkHiddenKey)
            // 只认从**本次**放大缩回的那一个: 缩回后马上又进来的新一次放大不能被它停住 (2026-09-15 索尼: 新的放大停在起点,
            // 列表页已藏、放大层只画了起点那一小块, 整屏黑到看门狗收场 ~1.2s)
            if (TvHeroZoomHandoff.shrink?.fromSession === session) awaitCancellation()
            session.t = session.progress(now)
            if (swapPlanned && !session.swapped && session.t >= TV_HERO_SWAP_AT) session.swapped = true
        }
        // 到位后真页照常在一两帧内接手 (见真页的 handoff). 真页迟迟不来 (条目信息慢 / 加载失败 / 背景图地址后来变了)
        // 就由这一层自己收场: 会话一直在的话 covering 一直为真, 占位页的背景、底色和加载转圈也一直被压着, 页面像是
        // 冻在一张图和一个标题上
        delay(TV_HERO_ZOOM_HANDOFF_GRACE_MILLIS)
        // 真页正在淡入换图 (见真页 handoff): 由它淡完自己结束, 这里掐掉会让淡入中途跳图
        if (!session.handingOver) TvHeroZoomHandoff.endSession(session)
    }
    // 详情页离开 (返回 / 被别的页盖住) 时会话一并结束, 列表页恢复
    DisposableEffect(session) { onDispose { TvHeroZoomHandoff.endSession(session) } }
    // 放大途中按返回: 快段里真页还扣着没组合 (holdPlaceholder), 它那条缩回处理还不在, 由这一层接 —— 同样缩回列表页 hero 框.
    // 起跑前 (图还在加载) 列表页还照常画着, 不拦, 照常出栈淡出
    val navigator = LocalNavigator.current
    BackHandler(enabled = session.started) {
        if (TvHeroZoomHandoff.beginShrink(session.subjectId, session.entryKey) { navigator.popBackStack() }) {
            zoomLogger.info { "Details back: shrink begin (during zoom)" }
        } else {
            zoomLogger.info { "Details back: fade (during zoom, cannot shrink)" }
            navigator.popBackStack()
        }
    }
    // 整屏底色**渐入**而不是起跑那一帧铺满: 图是从 hero 框长起来的, 底色瞬间铺满的话按确认那一下就是"详情页的遮罩
    // 整个出现"(用户 2026-09-16), 只有中间一小块在动. 渐入这一段列表页还画在下面 (见 TvHeroZoomHandoff.scrimOpaque),
    // 于是读起来是列表页被这一层压住、图同时长大 —— 容器变形的常规做法. 比例见 TvPolishFlags.zoomScrimT (0 = 原行为)
    val scrimColor = AniThemeDefaults.pageContentBackgroundColor
    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                val a = session.scrimAlpha
                if (a > 0f) drawRect(scrimColor, alpha = a)
            },
    ) {
        val swapDim = { if (swapPlanned) tvHeroSwapDim(session.t) else 0f }
        TvHeroBackdrop(
            imageUrl = session.url,
            scrollState = scrollState,
            onSuccess = { loaded = true },
            zoomFrom = session.bounds,
            zoomT = { session.t },
            featherOn = { session.started },
            solidUnderlay = AniThemeDefaults.pageContentBackgroundColor,
            sourceTreatment = session.treatment,
            // 起跑之前只加载不画: 图加载好到起跑之间那一两帧若照常画, 就是一块没有羽化、没有底缘擦除的硬边原图压在
            // 列表页 hero 上 (2026-09-10 录屏: hero 区突然变成硬边亮矩形约 50ms). 起跑那一帧图、羽化、深色底、列表页
            // 硬切一起出现, 与列表页 hero 像素级一样
            hidden = !session.started || session.swapped,
            extraDim = swapDim,
        )
        if (crossImage) {
            TvHeroBackdrop(
                imageUrl = session.detailsUrl,
                scrollState = scrollState,
                onSuccess = { detailsLoaded = true },
                zoomFrom = session.bounds,
                zoomT = { session.t },
                featherOn = { session.started },
                solidUnderlay = AniThemeDefaults.pageContentBackgroundColor,
                sourceTreatment = session.treatment,
                hidden = !(session.started && session.swapped),
                extraDim = swapDim,
            )
        }
    }
}

/**
 * 返回缩回那一层 (见 TvHeroZoomHandoff.Shrink), 挂在导航之上 (SubjectDetailsPageVariant.Overlay).
 *
 * 平时 (没在缩回) 就把最近一次放大的那张图组合着、不画 (standby): 按返回时图已加载好, 下一帧开头就上屏 —— 不等请求状态 / 回调 /
 * 新节点首绘, 也不要求列表页还在组合里. 上屏那一帧两页一起不画、详情页移出组合 (它的活随之停下, 移出的开销落在运动之前的静止帧),
 * 然后只剩这一层在动, 按放大同一条曲线缩回列表页 hero 框. **缩到位之后才出栈** (新顺序): 列表页的恢复 / 重建 / 焦点落位都在不动的
 * 画面下做, 等它 hero 那张图就绪 (或超时) 再撤层硬切回去 —— 以前"出栈后等一帧就开缩", 那些活全撞进运动 (2026-09-15 索尼一帧 176ms,
 * 重组 131ms). 代价是弱设备在落点多停几十到一百多毫秒. [TvPolishFlags.shrinkPopFirst] 保留旧顺序给 A/B.
 */
@Composable
fun TvHeroShrinkLayer() {
    val shrink = TvHeroZoomHandoff.shrink
    // 缩到的那张 (列表页 hero 那张) 与起始那张 (缩回开始时屏幕上那张; 换图的情形 = 详情页的整部背景). standby 时两张都组合着 (不画)
    val url = shrink?.url ?: TvHeroZoomHandoff.standbyUrl ?: return
    val startUrl = shrink?.startUrl ?: TvHeroZoomHandoff.standbyDetailsUrl ?: url
    val crossImage = startUrl != url
    // 按 URL 记: standby 与随后的缩回是同一张图、同一个节点, 缩回一开始就是已加载
    var loaded by remember(url) { mutableStateOf(false) }
    var startLoaded by remember(startUrl) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    if (shrink != null) {
        LaunchedEffect(shrink) {
            val ready = withTimeoutOrNull(TV_HERO_ZOOM_LOAD_BUDGET_MILLIS) {
                snapshotFlow { loaded && (!crossImage || startLoaded) }.first { it }
            }
            if (ready == null) {
                zoomLogger.info { "Details back: shrink abandoned (image not ready), fade" }
                TvHeroZoomHandoff.endShrink(shrink)
                shrink.pop()
                return@LaunchedEffect
            }
            // 在一帧的开头 (动画阶段, 组合之前) 上屏: 两页不画是绘制阶段读的, 本层显示是组合里读的 —— 不对齐帧头的话, 那一帧两页已藏、
            // 本层还没出来, 整屏黑一两帧 (2026-09-14 录像)
            val armNanos = withFrameNanos { it }
            // 出栈时机: 列表页还在组合里, 就在上屏这一帧出栈 —— 导航转场改指向列表页, 它不会在缩回途中按时被移出 (移出一整页索尼上
            // 近百毫秒, 正落在运动中间: 2026-09-15 tailfix20 x0.6, 运动里 50~58ms 空档); 运动中它不画、不算前台, 落地后才恢复.
            // 列表页已离场就缩到位、静止后再出栈, 让它在层下重建. TvPolishFlags.shrinkPopFirst = 旧顺序 (上屏就出栈、落地即撤层), A/B 用
            val popFirst = TvPolishFlags.shrinkPopFirst
            val listAlive = TvHeroZoomHandoff.listAlive(shrink)
            // 快速路径 (TvPolishFlags.shrinkKeep): 放大早已接手 (详情页已稳定, 藏着不干活)、来源列表页常驻时, 详情页**只藏不销毁**, 缩回不等它 ——
            // 起步前那一帧不再有整页移出组合 (索尼起步前 ~110ms 静止的大头), 销毁挪到落地之后. 放大途中返回 (详情页还在分帧组合) 照旧先移出
            val keepMode = TvPolishFlags.shrinkKeep
            val keep = keepMode != 0 && !popFirst && listAlive && TvHeroZoomHandoff.session == null &&
                    TvHeroZoomHandoff.isZoomEntry(shrink.fromSession?.entryKey)
            val popAtArm = !keep && (popFirst || listAlive)
            TvHeroZoomHandoff.armShrink(shrink, keepDetails = keep)
            zoomLogger.info {
                "Details back: shrink moving from t=${shrink.fromT} (popFirst=$popFirst, popAtArm=$popAtArm, keep=${if (keep) keepMode else 0})"
            }
            if (popAtArm) shrink.pop()
            // 上屏这一帧 (详情页移出组合, 索尼上重组 30~50ms; 旧顺序还有出栈) 不计入缩回时长: 先等它过去, 再取起点 ——
            // 在它开头取起点的话, 它的耗时算进第一步, 一出来就跳一截 (2026-09-15 tailfix19 追踪).
            // 快速路径这一帧没有重活: 计时直接从上屏那一帧算起. 再等一帧取起点的话, 那一帧进度仍是 0、画面不变, 没东西可画 ——
            // 上屏与第一次动之间空一帧 (两台都是 33ms 一跳, 2026-09-15 用户: "缩小的开始似乎有掉帧"; tailfix26 追踪)
            val start = if (keep) {
                armNanos
            } else {
                withFrameNanos { }
                withFrameNanos { it }
            }
            val millis = (TV_HERO_SHRINK_MILLIS * shrink.fromT).coerceAtLeast(16f)
            val easing = tvHeroShrinkEasing()
            var lastNanos = start
            while (true) {
                lastNanos = withFrameNanos { it }
                val p = ((lastNanos - start) / 1_000_000f / millis).coerceIn(0f, 1f)
                shrink.linear = p // 底色渐出按**时间**走, 不跟已缓动的 t (见 Shrink.scrimAlpha)
                shrink.t = shrink.fromT * (1f - easing.transform(p))
                // 换图的缩回: 缩到一半 (压暗最深) 换回列表页那张
                if (crossImage && !shrink.swapped && 1f - shrink.t / shrink.fromT >= TV_HERO_SWAP_AT) shrink.swapped = true
                if (p >= 1f) break
            }
            if (popFirst) {
                zoomLogger.info { "Details back: shrink done (popFirst)" }
                TvHeroZoomHandoff.endShrink(shrink)
                return@LaunchedEffect
            }
            if (keep && keepMode == 2) {
                // 先露列表页 (缩回层不再画; 详情页仍藏着、仍在栈顶, 按键由缩回期间的吞键挡住), 下一帧再出栈 —— 详情页销毁那一帧落在列表页
                // 已上屏、还没回前台 (没有聚焦动画) 的静止画面上, 卡片不用等它
                shrink.moving = false
                shrink.revealed = true
                val shown = withFrameNanos { it }
                shrink.pop()
                withFrameNanos { }
                val torn = withFrameNanos { it }
                zoomLogger.info { "Details back: shrink done (keep, teardown ${(torn - shown) / 1_000_000}ms)" }
                TvHeroZoomHandoff.endShrink(shrink)
                return@LaunchedEffect
            }
            // 同一步里出栈并结束运动: 下一帧组合时栈顶已是列表页, 详情页不会因"不在运动了"又被当成前台重新组合
            if (!popAtArm) shrink.pop()
            shrink.moving = false
            val isReady = withTimeoutOrNull(TV_HERO_SHRINK_READY_TIMEOUT_MILLIS) {
                while (!TvHeroZoomHandoff.listReady(shrink)) withFrameNanos { }
                true
            } != null
            // 列表页在层下先画一帧, 撤层那一帧只是翻透明度
            val doneNanos = withFrameNanos { it }
            zoomLogger.info { "Details back: shrink done (${if (isReady) "ready" else "timeout"}) +${(doneNanos - armNanos) / 1_000_000}ms" }
            // 超时 = 白盖住画面 800ms, 从外面看就是"返回后黑了一秒". 把对不上的那一项记下来 (见 sourceDebug)
            if (!isReady) {
                zoomLogger.warn {
                    "Details back: listReady timeout, want=${shrink.subjectId}:${shrink.url.takeLast(32)} got ${TvHeroZoomHandoff.sourceDebug()}"
                }
            }
            TvHeroZoomHandoff.endShrink(shrink)
        }
        DisposableEffect(shrink) { onDispose { TvHeroZoomHandoff.endShrink(shrink) } }
        // 缩回期间再按返回: 吞掉. 运动中没有哪一页算在前台 (见 PageForegroundNavEntryDecorator), 放行就落到 NavDisplay / 系统 ——
        // 栈里只剩主页时等于直接退出应用
        BackHandler { }
    }
    // 整屏底色**渐出**而不是撤层那一下才消失: 原来从上屏到撤层一直不透明地盖着, 最后突然不见
    // (用户 2026-09-16). 化开这一段列表页接着画在下面 (见 TvHeroZoomHandoff.shrinkRevealing), 与放大那头对称
    val shrinkScrimColor = AniThemeDefaults.pageContentBackgroundColor
    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                val a = shrink?.scrimAlpha ?: 0f
                if (a > 0f) drawRect(shrinkScrimColor, alpha = a)
            },
    ) {
        // 上屏且还没把画面交还列表页 (快速路径落地后 revealed: 本层不再画)
        val armed = shrink?.let { it.armed && !it.revealed } == true
        val swapped = shrink?.swapped == true
        val swapDim = { if (crossImage && shrink != null) tvHeroSwapDim(1f - shrink.t / shrink.fromT) else 0f }
        // 背景亮度接着屏幕上那一刻走, 随进度化到 0 (= 列表页 hero 的全亮). 起点为 0 (绝大多数情形) 时返回 null,
        // 走 TvHeroBackdrop 的默认口径, 与改之前逐像素一致
        val startFade = { shrink?.takeIf { it.startFade > 0f }?.let { it.startFade * (it.t / it.fromT) } }
        TvHeroBackdrop(
            imageUrl = url,
            scrollState = scrollState,
            onSuccess = { loaded = true },
            // standby 时也给框: 本层的全屏框 (ownBounds) 平时就量好, 缩回第一帧就能按进度摆位
            zoomFrom = shrink?.bounds ?: TvHeroZoomHandoff.standbyBounds,
            zoomT = { shrink?.t ?: 1f },
            featherOn = { armed },
            solidUnderlay = AniThemeDefaults.pageContentBackgroundColor,
            sourceTreatment = shrink?.treatment,
            shrinking = true,
            hidden = !armed || (crossImage && !swapped),
            scrollFade = startFade,
            extraDim = swapDim,
            sharpen = false,
        )
        if (crossImage) {
            TvHeroBackdrop(
                imageUrl = startUrl,
                scrollState = scrollState,
                onSuccess = { startLoaded = true },
                zoomFrom = shrink?.bounds ?: TvHeroZoomHandoff.standbyBounds,
                zoomT = { shrink?.t ?: 1f },
                featherOn = { armed },
                solidUnderlay = AniThemeDefaults.pageContentBackgroundColor,
                sourceTreatment = shrink?.treatment,
                shrinking = true,
                hidden = !armed || swapped,
                scrollFade = startFade,
                extraDim = swapDim,
                sharpen = false,
            )
        }
    }
}

/**
 * 放大转场的快段里先不换真页 (见 SubjectDetailsPageVariant.holdPlaceholder): 本条目的会话进行中且进度未到
 * [TV_HERO_ZOOM_TAIL_T] 时为 true. **不看是否已起跑**: 起跑前真页若先到, 起跑那一刻又扣回占位页 = 真页被销毁再重建
 * (实测一次进页组合了两遍真页). 也不会把真页扣死: 本函数与放大层在同一个条件 (沉浸式) 下才组合, 放大层要么在加载
 * 预算内起跑, 要么结束会话. 派生状态: 调用方只在它翻转时重组一次.
 */
@Composable
fun tvHeroZoomHoldsPlaceholder(subjectId: Int): Boolean {
    val hold by remember(subjectId) {
        derivedStateOf {
            TvHeroZoomHandoff.session?.let { it.subjectId == subjectId && it.t < TV_HERO_ZOOM_TAIL_T } == true
        }
    }
    return hold
}

/**
 * 大标题跟着放大会话的进度, 从列表页标题的位置平移到本页的位置 (两边都是 headlineLarge, 只差位置). 会话开始 (图上屏、
 * 列表页硬切) 之前不画 —— 那时列表页自己的标题还在同一处; 自己的框量出来之前也不画, 免得头一帧出现在终点.
 */
@Composable
private fun Modifier.tvHeroZoomTitleShift(session: TvHeroZoomHandoff.Session?): Modifier {
    var own by remember { mutableStateOf<Rect?>(null) }
    val from = session?.titleBounds
    if (session == null || from == null) return this
    return this
        .onGloballyPositioned {
            val b = it.boundsInRoot()
            own = b
            session.titleTarget = b
        }
        .graphicsLayer {
            // 自己的框还没量到 (占位页刚换成真页的那一帧) 先用上一页量的: 两页标题同一位置
            val o = own ?: session.titleTarget
            if (!session.started || o == null) {
                alpha = 0f
                return@graphicsLayer
            }
            val t = session.t
            if (t < 1f) {
                translationX = lerp(from.left - o.left, 0f, t)
                translationY = lerp(from.top - o.top, 0f, t)
            }
        }
}

/** 详情页侧边栏展开遮罩的颜色: surface 向 surfaceTint 偏移, 再按日夜主题稍向黑压深 (与真页那处同一算法). */
@Composable
private fun tvDetailsRailScrimColor(): Color {
    val darken = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        TV_DETAILS_RAIL_SCRIM_DARKEN_DARK
    } else {
        TV_DETAILS_RAIL_SCRIM_DARKEN_LIGHT
    }
    return lerp(
        lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceTint, TV_DETAILS_RAIL_SCRIM_TINT),
        Color.Black,
        darken,
    )
}

/**
 * 原生 4K UI 上把 hero 背景**加清**的叠加层: 在已经画好的 w1280 那张之上再叠一张原图档,
 * 加载好了淡入顶掉它. 1080p 上、以及非 TMDB backdrop (竖版封面兜底) 上什么都不做.
 *
 * **为什么 4K 上非升不可**: TMDB backdrop 的档位只有 w300/w780/w1280/original, w1280 之上直接
 * 就是原图. 1080p 上 w1280 铺满屏是 1.5 倍放大 (可接受), 原生 4K UI 下框是 3840×2175 ——
 * **3.0 倍**, 清晰度日志里 `SOURCE_LIMITED drawScale=x3.02` 就是它.
 *
 * **为什么是叠一层而不是直接把 URL 换成原图档**: 进详情页那一帧的图, 是上一个页面 (探索/追番/
 * 搜索) 聚焦这张卡时就已经下好、还躺在内存缓存里的 w1280 —— 首帧即有图, 关联动画一进场背景就是
 * 满的 (见上面 `tmdbBackdropUrl` 的说明). 换 URL 等于把这条性质丢掉: 新键在内存缓存里没有,
 * hero 会先黑一下再淡入. 叠一层则全程有图, 顶上来的又是同一张的更清版本, 淡入几乎看不出来.
 *
 * **为什么只有详情页升**: 见 [tmdbBackdropOriginalSizeUrl] 里 2026-08-21 的实测账 —— 列表/网格
 * 页一起升是净亏. 详情页是"一次一张、停留久、没有邻居预取的乘数", 那笔代价只付一次.
 *
 * [TV_HERO_SHARPEN_DWELL] 的停留门槛: 进来就播 / 立刻返回的过路访问不必付这笔下载和解码,
 * 而它们恰好也是最挤的那几百毫秒 (关联动画 + 选集剧照一起在加载).
 */
@Composable
private fun HeroBackdropSharpeningOverlay(imageUrl: String) {
    // 只认 w1280 档的 TMDB backdrop: 升不了档 (封面兜底 / 已是原图) 时 takeIf 给出 null
    val originalUrl = remember(imageUrl) {
        tmdbBackdropOriginalSizeUrl(imageUrl).takeIf { it != imageUrl }
    }
    // 裸读非快照状态: 档位在首帧就定死了 (见 AniDisplayTier), 不需要跟着它重组
    // 视觉效果完整档才加清 (原图位图约 33MB, 见 TvVisualEffectsLevel.originalImages)
    if (originalUrl == null || !AniDisplayTier.isHighRes || !LocalThemeSettings.current.visualEffects.originalImages) return
    var sharpen by remember(originalUrl) { mutableStateOf(false) }
    LaunchedEffect(originalUrl) {
        delay(TV_HERO_SHARPEN_DWELL)
        // 停留门槛到点那一下正撞上返回: 原图约 33MB, 下载 + 解码全落在缩回那几帧里, 而缩回层画的本来就不是这一层
        // (它 sharpen = false) —— 纯白干. 本页要么随缩回销毁, 要么藏到出栈, 都不必再升档
        if (TvHeroZoomHandoff.shrinking) return@LaunchedEffect
        sharpen = true
    }
    if (!sharpen) return
    // 没有 placeholder: 在途期间这一层什么都不画, 下面那张 w1280 照常露出
    AsyncImage(
        originalUrl,
        contentDescription = null,
        Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
}



/** 放大转场没能回调 (图迟迟不到 / 背景没组合) 时放行其余 UI 的兜底. */
private const val TV_HERO_REVEAL_WATCHDOG_MILLIS = 1_000L

private val zoomLogger = logger("TvHeroZoom")

/**
 * 放大到位后等真页接手的时限 (见 TvHeroZoomLayer): 正常一两帧内就接手 (它自己的背景图是内存命中); 过了这个时限还没
 * 接手, 放大层自己结束会话, 占位页 / 错误页照常显示.
 */
private const val TV_HERO_ZOOM_HANDOFF_GRACE_MILLIS = 500L

/**
 * 放大换图接手的淡入时长 (放大的是列表页的单集剧照, 详情页是整部背景, 见 TvHeroZoomHandoff.Session.detailsUrl).
 * 淡入期间会话还没结束: 首屏信息带 (放大到 TV_HERO_ZOOM_REVEAL_T 就显示) 已经在屏上, 页面底色仍是透明的. 所以文字颜色
 * 要显式按页面底色配 (见 MultiColumnScaffold 的 contentColor), 真页背景的底缘渐隐这段也要画同色渐变 (见 solidUnderlay),
 * 否则这 250ms 里文字是黑的、底缘透出旧图, 接手那一帧一起跳 (2026-09-14). 首屏以下的区块比同图放大晚这么久.
 */
private const val TV_HERO_ZOOM_CROSS_IMAGE_FADE_MILLIS = 250

/** 首屏以下区块组合完后再等这么久才预画选集页 (见真页 episodesPrewarm): 让首批数据到达的那几次重组先过去, 挑画面真正静止的时候. */
private const val TV_DETAILS_PREWARM_DELAY_MILLIS = 400L

/** 放大没起跑就放弃时页面补的淡入 (见 rememberTvZoomAbortFade): 与常规进页的交叉淡入同量级. */
private const val TV_DETAILS_ABORT_FADE_MILLIS = 250

/**
 * 详情页纵向滚动 (页内露出 / 内嵌变体的换页): 照 Prime Video 详情页往下翻的实测 (2026-09-15 Shield 录屏逐帧相位相关,
 * scroll_fit.py): 跨区块整屏 ~300ms, 50%@67~102 / 90%@184~219ms, 头 30~50ms 先加速、减速尾巴长; 小幅 (~130px@1080p) ~150ms.
 * 原来是 animateScrollTo 的默认弹簧, 实测我们整屏 50%@51~66 / 90%@101~119ms, 快一倍, 大距离看着像瞬移 (用户 2026-09-15).
 * 时长按距离 (折算到 1080p) 在 [TV_DETAILS_SCROLL_MIN_MILLIS, TV_DETAILS_SCROLL_MAX_MILLIS] 间线性取.
 */
private suspend fun ScrollState.tvDetailsScrollTo(target: Int) {
    if (target == value) return
    val distance1080 = kotlin.math.abs(target - value) * 1080f / viewportSize.coerceAtLeast(1)
    val f = ((distance1080 - 150f) / 300f).coerceIn(0f, 1f)
    val millis = (TV_DETAILS_SCROLL_MIN_MILLIS + (TV_DETAILS_SCROLL_MAX_MILLIS - TV_DETAILS_SCROLL_MIN_MILLIS) * f).roundToInt()
    animateScrollTo(target, tween(millis, easing = TvDetailsScrollEasing))
}

/** Prime Video 详情页往下翻的拟合曲线 (见 [tvDetailsScrollTo]; 均方根误差 0.8%). */
private val TvDetailsScrollEasing = CubicBezierEasing(0.30f, 0.20f, 0.00f, 0.80f)
private const val TV_DETAILS_SCROLL_MIN_MILLIS = 160
private const val TV_DETAILS_SCROLL_MAX_MILLIS = 300

/**
 * hero ⇄ 选集页的切换, 照 Prime Video 详情页 (2026-09-15 用户: "不是整个页面滚上去, 按钮往上一段就淡出了, 包括返回的时候").
 * 录屏逐帧 (scratchpad rec/pv_updown.mp4, 《日本三国》): 离开 hero = hero 内容原地淡出 (~120ms), 下面那一行只上移一小段 (~屏高 20%,
 * ~250ms 到位), 新内容随后淡入, 背景的压暗慢慢加深 (~450ms); 回 hero = 行往下滑一小段并淡出, hero 标题 / 按钮淡入 (~220ms 到位),
 * 压暗 ~450ms 退掉. 我们原来是整页滚一屏 (hero 整块滑出顶部, 选集页整页从底部滑上来).
 *
 * 做法: 滚动位置**直接跳** (scrollTo), 画面由图层属性画出过渡 —— 各页先补回"跳之前 / 之后在屏幕上的位置", 再做短位移 +
 * 淡入淡出; 背景的淡出进度改由 [backdropProgress] 单独按时长走 (不跟滚动量, 否则跳的那一下就全变暗). 进度与滚动量都在绘制阶段读, 不重组.
 * 进到第 k 页时以 k 为界分两组 —— 往下翻: 序号 < k 的是离开方 (补回跳前位置, 上移一点, 早早淡出), ≥ k 的是进入方 (落在跳后位置,
 * 从下方一小段滑上来, 稍晚淡入); 往上翻反过来. 同组的页一起动, 所以同屏露着的下一页跟着新页走.
 *
 * **滚动量是派生值, 不是被写的状态**: 被写的只有 [current] (焦点进入某页时写) 和 [within] (页内露出, 换页归零), 各自单一写者;
 * 滚动量恒等于 `起点[current] + within`, 由 [run] 一个协程独占写入. 于是没有滚动锚定、没有不变量守卫、没有进入吸附的位置计算 ——
 * 上方内容长高时起点自己变, 滚动量跟着变. (这一条是重写的全部理由, 来龙去脉见 memory `project-tv-details-page`.)
 */
@Stable
private class TvDetailsPager(
    private val scrollState: ScrollState,
    /** 背景按滚动量淡出的距离 (px), 同 TvHeroBackdrop 的 HERO_BACKDROP_FADE_DISTANCE: 过渡里按跳前 / 跳后两个滚动量插值. */
    private val backdropFadePx: Float,
    /** 换页是否走淡入淡出过渡. 播放器内嵌变体传 false = 照常整页滚 (那边 hero 是播放画面, 淡出没有意义). */
    private val transitions: Boolean = true,
) {
    /**
     * 各页起点在**内容坐标系**里的 y (已扣掉该页要留的顶部空隙); NaN = 该页还没测过 / 不在场.
     *
     * 第 0 页 (hero) 的起点恒为 0, 不用实测: 它就是页面顶. 独立页变体的 hero 不是 [PageSection]
     * (它是整屏的 Box), 没人报起点, 所以这里先种上.
     */
    private val starts = List(TV_DETAILS_PAGE_COUNT) { mutableFloatStateOf(if (it == 0) 0f else Float.NaN) }

    /** 当前在第几页. **唯一写者是"焦点进入某页"** ([goTo]). */
    var current by mutableIntStateOf(0)
        private set

    /**
     * 屏幕上**看得见**的是第几页; 还没接管滚动 ([engaged]) 时为 null.
     *
     * 那之前 [current] 恒为 0 而页面可能停在恢复出来的位置 (从播放器返回本页, rememberScrollState 把滚动量还原到
     * 离开时那一页) —— 这时拿 0 当"显示页"会把方向键和返回键全判到第一页去. 判据与 [engaged] 完全一致:
     * 第一次 [goTo] 之后, current 才真的等于屏幕上那一页.
     */
    val displayedPage: Int? get() = if (engaged) current else null

    /** 页内露出偏移: 本页比一屏高时, 焦点往下移要露出来的那点距离. 只由 [reveal] 写, 换页归零. */
    private var within by mutableFloatStateOf(0f)

    /**
     * 第一次 [goTo] 之前不碰滚动量.
     *
     * 进页 / 返回本页的初始位置由页面自己定 (`rememberScrollState` 恢复离开时的位置, 或进页 effect 归零),
     * 而 [current] 不跨导航保存、一律从 0 起 —— 没有这道闸, 返回本页时驱动器会立刻把页面拽回顶部,
     * 把恢复出来的位置覆盖掉. 焦点恢复到哪一页, 那一页的 [goTo] 自然会把闸打开.
     */
    private var engaged by mutableStateOf(false)

    /**
     * 各页"从视口顶算起需要多高" (页起点留白 + 本页实测高度); NaN = 没测过 (如独立页的 hero, 它不是 PageSection).
     * 只用来判"这一页装不装得下", 见 [reveal].
     */
    private val extents = List(TV_DETAILS_PAGE_COUNT) { mutableFloatStateOf(Float.NaN) }

    /** 第 [page] 页实测到自己的起点 (内容坐标系) 与所需高度. 上方内容长高时起点自己会变, 滚动量随之跟上, 不需要"锚定补偿". */
    fun reportStart(page: Int, contentY: Float, extentPx: Float) {
        starts[page].floatValue = contentY
        extents[page].floatValue = extentPx
    }

    /**
     * 进入某页后**还没收到第一次露出**. 焦点落进新页后 `bringIntoView` 必定报一次, 报的是入场落点本身,
     * 不是页内导航 —— 见 [reveal]: 这一次要丢掉, 否则入场就不停在页起点上了.
     */
    private var entryReveal = false

    /** 焦点进入第 [page] 页. */
    fun goTo(page: Int) {
        engaged = true
        if (page == current) return
        current = page
        within = 0f
        entryReveal = true
    }

    /**
     * 本页内焦点落在 [itemBottom] (相对本页起点) 处, 据此算露出偏移.
     *
     * 是**纯函数**: 同一个焦点位置永远得同一个偏移, 不存在"往下滚过再往上滚回来"的路径依赖 ——
     * 旧版按"越出上缘就上滚 / 越出下缘就下滚"分支写, 于是同一个焦点在不同来路上停在不同位置.
     */
    fun reveal(page: Int, itemBottom: Float) {
        if (page != current) return
        // **入场恒定停在页起点**: 焦点落进新页后的第一次 bringIntoView 不产生露出.
        // 旧版在这里做"焦点元素在折叠线以下就继续往下滚露出它", 代价正是把本页的起点滚出屏幕 ——
        // 第三页停下来时"作品信息"在屏幕外, 第二页停在多滚一段之后 (用户 2026-09-15).
        // 起点就是落点, 没有例外; 装不下是那一页的排版问题, 不该用滚动去补.
        if (entryReveal) {
            entryReveal = false
            return
        }
        // **装得下的页面永不滚**: 页内露出只为"本页比一屏高"而存在. 末页 (评价 + 关联条目) 实测 496dp,
        // 加页顶留白 520, 视口 540 —— 余量只有 20dp; 而聚焦卡的包围盒会因示焦形变变大, 且 bringIntoView 报的是
        // **变换后**的框 (同 tailfix29), 一越过视口就触发露出, 把页顶的"评价"顶出屏幕
        // (用户 2026-09-16: 长按翻到关联条目时"评价最上面一点会跑到画面外"). 同一个余量也曾让关联条目行左右移动时画面乱动.
        // 判据用**实测高度**而不是逐项算: 排版一改就自动跟上, 不用再维护那串加法.
        val extent = extents[page].floatValue
        if (!extent.isNaN() && extent <= scrollState.viewportSize) {
            within = 0f
            return
        }
        within = (itemBottom - scrollState.viewportSize).coerceAtLeast(0f)
    }

    /** 滚动量的**唯一真值**: 当前页起点 + 页内露出. 起点没测到时返回 -1 (什么都别做). */
    private fun targetScroll(): Int {
        if (!engaged) return -1
        val s = starts[current].floatValue
        if (s.isNaN()) return -1
        return (s + within).roundToInt().coerceIn(0, scrollState.maxValue)
    }

    /**
     * 驱动器: 整页**只有这里写 scrollState**. 挂在页面作用域上跑 (见调用处的 LaunchedEffect).
     *
     * 分成两条收集是必须的, 不能合成一条: 换页的图层过渡要跑满 320ms, 而这期间起点重测、页内露出都会发射 ——
     * 合成一条 `collectLatest` 的话过渡会被这些无关发射取消, [active] 卡在 true, 图层冻在进度 0 上,
     * 表现是"滚动量跳了、画面却纹丝不动"(离开方正好补回跳前位置, 进入方透明度还是 0).
     *
     * 于是: 换页那条自己管一个 [Job] (**同向连按只改目标、不重开**, 见下), 反向或过渡已结束才起新一轮;
     * 位置校正那条不碰过渡, 只负责把滚动量
     * 对到目标上 —— 起点自己移动了 (骨架换成真区块 / 关联条目晚到, 上方内容长高) 就瞬时对齐, 画面上本页
     * 纹丝不动, 这一条取代了旧版的滚动锚定; 页内露出则平滑滚过去.
     */
    suspend fun run() = coroutineScope {
        launch {
            val scope = this
            var lastPage = current
            var swap: Job? = null
            snapshotFlow { current }.collect { page ->
                if (page == lastPage) return@collect
                lastPage = page
                // 该页可能刚组合、起点还没测到: 等它, 别退化成没有过渡的瞬跳
                val target = snapshotFlow { targetScroll() }.first { it >= 0 }
                if (target == scrollState.value) return@collect
                // **同向连按不重开一轮过渡, 只把边界与落点改到新的一页** (进度照旧跑完那一轮).
                // 重开的代价不只是时长被一次次延长: 每重开一轮就多一页停在中间透明度上 (见 alphaFrom),
                // 于是同时存在三四个整屏离屏层 —— 索尼上实测连按 26ms GPU / 正常 13ms (2026-09-15 逐帧).
                // 中间被跳过的那几页在本轮里算离开方, 位置钉回跳前的滚动量 (见 apply), 正好落在屏外, 看不见.
                // 只在**本轮还剩足够时间**时合并: 合并不重开进度, 剩余时长就是新页淡入的全部时间 ——
                // 连按三下上键时第三下常常落在进度 0.8 之后, 第二页只剩几十毫秒淡入, 观感是"闪现"
                // (用户 2026-09-16). 剩得太少就走下面的重开分支 (它带透明度续接, 不会闪回).
                if (transitions && active && swap?.isActive == true &&
                    progress.value <= TV_HERO_SWAP_MERGE_MAX_PROGRESS &&
                    (target > scrollState.value) == forward
                ) {
                    boundary = page
                    toScroll = target
                    scrollState.scrollTo(target)
                    return@collect
                }
                swap?.cancelAndJoin()
                swap = scope.launch { swapTo(page, scrollState.value, target) }
            }
        }
        launch {
            var lastPage = current
            var lastWithin = within
            snapshotFlow { Triple(current, within, targetScroll()) }
                .collectLatest { (page, w, target) ->
                    val pageChanged = page != lastPage
                    val withinChanged = w != lastWithin
                    lastPage = page
                    lastWithin = w
                    // 换页交给上面那条 (它要先读到跳之前的滚动量, 这里抢跑会让过渡的起终点重合)
                    if (pageChanged || target < 0 || target == scrollState.value) return@collectLatest
                    when {
                        // 过渡进行中: 连落点一起改, 画面上什么都不动
                        active -> alignDuringTransition(target)
                        // 页内导航的露出: 平滑滚
                        withinChanged -> scrollState.tvDetailsScrollTo(target)
                        // 起点自己移动了 (上方内容长高): 瞬时对齐, 本页在视口里纹丝不动
                        else -> scrollState.scrollTo(target)
                    }
                }
        }
    }
    /**
     * 过渡进行中改滚动量: 把落点 [toScroll] 一起改掉, 画面上什么都不会动.
     *
     * 进入方的图层画在 `layoutY - toScroll` 上 (见 [apply]), 两者同步变就等于纹丝不动; 离开方本来就钉在
     * `layoutY - fromScroll`, 与滚动量无关. 用处: 焦点落进新页后 `bringIntoView` 报回来的页内露出量总比换页
     * 晚一拍 —— 不这么做的话它会变成换页之后的第二次滚动, 观感是"翻过去以后又自己多滚了一段"(用户 2026-09-15).
     * 于是露出量只决定最终停在哪, 过程中看不见.
     */
    private suspend fun alignDuringTransition(target: Int) {
        toScroll = target
        scrollState.scrollTo(target)
    }

    /** 过渡的时间进度 0..1 (线性; 各组按自己的曲线换算). */
    private val progress = Animatable(0f)
    private val backdrop = Animatable(0f)
    private var active by mutableStateOf(false)
    private var backdropActive by mutableStateOf(false)
    private var forward = true
    private var boundary = 0
    private var fromScroll = 0
    private var toScroll = 0

    /**
     * 被打断那一刻各页的实际透明度 (下标 = 页序号, NaN = 无续接值).
     *
     * 长按连续翻页时上一轮还没走完就重来, 而新一轮会把进度归零并**重新分配角色**: 一个正在淡入到 0.1 的页
     * 会变成新一轮的"离开方", 而离开方在进度 0 处的透明度是 1 —— 于是它先闪回全不透明再淡出. 背景层早就
     * 从当前值续上 (见 backdropFrom), 页图层这里补同一件事: 离开方只准变淡、进入方只准变浓 (见 [apply]).
     */
    private val alphaFrom = FloatArray(TV_DETAILS_PAGE_COUNT) { Float.NaN }

    /** 过渡进行中 (快照状态). */
    val isActive: Boolean get() = active

    /** 本轮换页的"步进闸"起点与时长: 闸没过之前不接受下一次换页, 见 [isStepping]. */
    private var stepMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var stepWindow: Duration = Duration.ZERO

    /** 距本轮闸开还有多久 (已开 = 0). 闸期间按的那一下据此**推迟**, 而不是丢掉, 见页面的 deferStep. */
    val stepRemaining: Duration
        get() = stepMark?.let { (stepWindow - it.elapsedNow()).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO

    /**
     * 本轮换页还没走够, 不该接受下一次 —— 长按时"一页一页地走".
     *
     * 长按的连发是 6 次/秒 (167ms 一次), 而一次换页过渡 [TV_HERO_SWAP_LEAVE_MILLIS] 要 360ms: 不设闸的话后面的
     * 换页不断盖掉前面的, **中间几页一帧都没露出来**, 观感是从第一页"直接跳到"评价页 (用户 2026-09-16).
     * 闸按时长的 [TV_HERO_SWAP_STEP_GATE] 算, 于是长按 ≈ 每 (360 × 本值) ms 走一页, 每一页都真的停一下.
     *
     * **按时间而不是按 [active] 判**: active 在取消路径上不清 (见 [swapTo] 的注释), 拿它当闸一旦漏清就永久卡死导航.
     * 时间只会前进, 自愈.
     */
    val isStepping: Boolean get() = stepMark?.let { it.elapsedNow() < stepWindow } == true

    /**
     * 换页: 滚动量**直接跳**到 [target], 画面由图层补出过渡. 只由 [run] 调用 (单一写者).
     *
     * 被打断时不用 token 记账 —— [run] 用 collectLatest, 上一次的整个调用连同它的动画一起被取消.
     * 正因如此 [active] / [backdropActive] **只在正常走完时才清**: 取消路径上不清, 新一轮紧接着又置 true,
     * 中间不会漏出"一帧没有图层"的原位画面.
     */
    private suspend fun swapTo(page: Int, from: Int, target: Int) = coroutineScope {
        if (!transitions) {
            scrollState.tvDetailsScrollTo(target)
            return@coroutineScope
        }
        // 背景从此刻屏幕上的样子起 (上一次过渡还没走完就接着它的当前值)
        val backdropFrom = if (backdropActive) backdrop.value else backdropFor(from)
        // 页透明度同理: 进度停在中途 = 上一轮被打断, 记下此刻每页的实际值让新一轮续 (见 alphaFrom)
        val u0 = progress.value
        if (u0 > 0f && u0 < 1f) {
            for (i in alphaFrom.indices) alphaFrom[i] = alphaAt(i, u0)
        } else {
            alphaFrom.fill(Float.NaN)
        }
        // 先同步把进度归零, 再跳: 跳与"按进度画"在同一帧生效
        progress.snapTo(0f)
        backdrop.snapTo(backdropFrom)
        forward = target > from
        boundary = page
        fromScroll = from
        toScroll = target
        active = true
        backdropActive = true
        stepMark = TimeSource.Monotonic.markNow()
        stepWindow = ((if (forward) TV_HERO_SWAP_LEAVE_MILLIS else TV_HERO_SWAP_RETURN_MILLIS) *
                TV_HERO_SWAP_STEP_GATE).toInt().milliseconds
        scrollState.scrollTo(target)
        launch {
            val to = backdropFor(target)
            // **变亮比变暗快**: 变暗 (往下翻) 慢一点是刻意的 —— 滚动量是跳的, 骤暗会很突兀; 但变亮 (往上翻回首屏)
            // 用同样的 450ms 就比页面本身还长, 页面早停了背景还在慢慢亮, 观感是"黑遮罩赖着不走"
            // (用户 2026-09-16: 长按一路翻回第一页, 全屏黑遮罩要等一会才消失). 变亮跟换页同步收尾.
            val millis = if (to < backdrop.value) {
                if (forward) TV_HERO_SWAP_LEAVE_MILLIS else TV_HERO_SWAP_RETURN_MILLIS
            } else {
                TV_HERO_SWAP_BACKDROP_MILLIS
            }
            backdrop.animateTo(to, tween(millis, easing = LinearOutSlowInEasing))
            backdropActive = false
        }
        progress.animateTo(1f, tween(if (forward) TV_HERO_SWAP_LEAVE_MILLIS else TV_HERO_SWAP_RETURN_MILLIS, easing = LinearEasing))
        active = false
    }

    /** 第 [index] 页的图层 (graphicsLayer 里调). 过渡没在跑时不动. */
    fun apply(index: Int, layer: GraphicsLayerScope) = with(layer) {
        if (!active || index < 0) return@with
        val u = progress.value
        val e = TvDetailsScrollEasing.transform(u)
        val value = scrollState.value
        val outgoing = isOutgoing(index)
        val slide = scrollState.viewportSize * TV_HERO_SWAP_SLIDE_FRACTION
        val rise = TV_HERO_SWAP_RISE.toPx()
        translationY = if (outgoing) {
            // 补回跳前的位置, 往下翻时上移一点 / 往上翻时下滑一小段
            (value - fromScroll) + if (forward) -rise * e else slide * e
        } else {
            // 落在跳后的位置, 往下翻时从下方一小段滑上来 / 往上翻时从上方一点落下
            (value - toScroll) + if (forward) slide * (1f - e) else -rise * (1f - e)
        }
        // 本轮曲线给出的透明度 (离开方早早淡出 / 进入方稍晚淡入); 有续接值时按单调方向取,
        // 离开方只准更淡、进入方只准更浓, 连按时不会闪回 (见 alphaFrom)
        //
        // **两边都要淡, 不能只淡画在上面的那一层**: 2026-09-16 试过"只给上层 alpha, 下层恒 1"以省掉一个
        // 整屏离屏缓冲 (索尼上每层约 13ms GPU), 前提是"上层盖住下层" —— 而每一页的**背景是透明的**
        // (内容浮在共享的 backdrop 上), 上层盖不住下层. 结果: 离场页在进入页淡入的整段里一直全不透明地
        // 留着, 两页内容同时可见 (用户 2026-09-16: "上一页的还没消失下一页的就出现了"; release / debug 都有).
        // 已撤回 —— 省 GPU 的路子得另找, 不能靠"底下那层反正看不见"这个不成立的前提.
        val curve = alphaAt(index, u)
        val prev = alphaFrom.getOrElse(index) { Float.NaN }
        alpha = when {
            prev.isNaN() -> curve
            outgoing -> minOf(prev, curve)
            else -> maxOf(prev, curve)
        }
    }


    /** 第 [index] 页在本轮里是不是"离开方" (往下翻时边界以上的走, 往上翻时边界以下的走). */
    private fun isOutgoing(index: Int): Boolean =
        (if (forward) index < boundary else index <= boundary) == forward

    /** 本轮曲线在进度 [u] 处给第 [index] 页的透明度 (不含续接修正). */
    private fun alphaAt(index: Int, u: Float): Float =
        // 进入方的淡入铺满整段 (原来 0.2~0.7 在 70% 处就到顶, 后面近百毫秒画面不变, 观感是"过渡提前结束了",
        // 用户 2026-09-16: "几乎看不出过渡"). 离开方仍早早淡出, 两者重叠的那段保证中途不露底
        if (isOutgoing(index)) 1f - fadeSegment(u, 0f, if (forward) 0.45f else 0.5f)
        else if (forward) fadeSegment(u, 0.15f, 0.9f) else fadeSegment(u, 0.2f, 0.9f)

    /** 背景图的淡出进度 (覆盖按滚动量算的那份); 过渡没在跑时 null. */
    fun backdropProgress(): Float? = if (backdropActive) backdrop.value else null

    /**
     * 此刻**屏幕上**背景的淡出程度 —— 与 [backdropProgress] 的区别是没有过渡时回落到按滚动量算 (即 TvHeroBackdrop 的
     * 默认口径), 于是任何时刻都给得出一个真值. 缩回层拿它当起点, 见 `TvHeroZoomHandoff.Shrink.startFade`.
     */
    fun backdropFadeNow(): Float = if (backdropActive) backdrop.value else backdropFor(scrollState.value)

    private fun backdropFor(scroll: Int): Float = (scroll / backdropFadePx).coerceIn(0f, 1f)

    private fun fadeSegment(u: Float, from: Float, to: Float): Float =
        FastOutSlowInEasing.transform(((u - from) / (to - from)).coerceIn(0f, 1f))
}

/** 页数: hero 0 / 选集 1 / 角色制作 2 / 作品信息 + 关联 3 / 评价 4. */
private val TV_DETAILS_PAGE_COUNT = TvDetailsSection.entries.maxOf { it.page } + 1

/**
 * 换页过渡时长. 2026-09-16 重新对了一次 Prime Video 详情页往下导航 (60fps 逐帧相位相关):
 * 它整段 **~283ms**, 50%@66ms / 90%@175ms, 总位移 **188dp**.
 *
 * 我们原来是 320ms —— **比它还长**, 但观感上"几乎看不出过渡" (用户 2026-09-16), 原因是另外两件事:
 * 滑动距离只有视口的 18% (97dp, 不到它一半), 且进入方的淡入在进度 70% 处就到顶, 后面近百毫秒画面不再变化.
 * 所以这里只小幅加长, 主要改的是 [TV_HERO_SWAP_SLIDE_FRACTION] 与 [alphaAt] 的淡入区间.
 */
private const val TV_HERO_SWAP_LEAVE_MILLIS = 360
private const val TV_HERO_SWAP_RETURN_MILLIS = 340
private const val TV_HERO_SWAP_BACKDROP_MILLIS = 450

/**
 * 同向连按时"只改目标、不重开过渡"的进度上限.
 *
 * 合并省的是开销 (每重开一轮就多一页停在中间透明度, 同时存在的整屏离屏层更多), 但它**不延长时长** ——
 * 本轮剩多少, 就是新页淡入的全部时间. 超过这个进度还来新的一页就重开一轮, 保证新页有至少 (1 - 本值) 的
 * 时长淡入, 不会"啪"地出现.
 */
private const val TV_HERO_SWAP_MERGE_MAX_PROGRESS = 0.45f

/**
 * 长按连翻时每一页至少要走完本轮时长的多少 (见 TvDetailsPager.isStepping).
 *
 * 0.75 => 每页约 270ms, 长按约 3.7 页/秒: 每一页都真的露出来, 又不至于按住半天不动.
 * 调大更"一页一页", 调小更接近原来的连续跳. 取 1 就是"必须完整走完才接受下一次".
 */
private const val TV_HERO_SWAP_STEP_GATE = 0.75f

/** hero 内容离开时上移的距离 (Prime 几乎原地淡出, 只带一点方向感). */
private val TV_HERO_SWAP_RISE = 24.dp

/**
 * 换页时内容滑入 / 滑出的距离 (占视口高度).
 *
 * 0.18 = 97dp, 实测 Prime Video 同一个动作真滚了 **188dp** (2026-09-16) —— 不到它一半, 是"看不出过渡"的主因.
 * 取 0.30 = 162dp: 往它靠但不追平 (我们是跳+淡入淡出, 位移只起方向暗示作用, 给满反而像整页在滚).
 */
private const val TV_HERO_SWAP_SLIDE_FRACTION = 0.30f

/** 放大期间羽化矩形越过图的边多画的屏幕像素: 盖住图边缘那一排半覆盖的像素 (见 TvHeroBackdrop). */
private const val TV_HERO_ZOOM_FEATHER_OUTSET_PX = 2f

/** 详情页停留超过这么久, 才把 hero 背景换成原图档加清 (见 [HeroBackdropSharpeningOverlay]). */
private val TV_HERO_SHARPEN_DWELL = 800.milliseconds

/**
 * 视频背景模式的遮罩层 (TV 播放器内嵌): 与 [TvHeroBackdrop] 的视觉规则对应, 但方向相反 ——
 * 那边是"擦除背景图露出页面底色", 这边没有图, 直接对下层视频画黑色渐变:
 * 首屏只压底部 (托住选集/文字区) + 左缘 (标题可读), 滚动后整屏渐进变暗.
 * 滚动值在 draw 阶段读取, 只触发重绘不触发重组.
 */
@Composable
private fun TvVideoBackgroundScrim(scrollState: ScrollState) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // 基础整屏压暗: 首屏 (介绍页) 就是满屏文字, 视频原亮度下看不清
                drawRect(Color.Black.copy(alpha = TV_VIDEO_SCRIM_BASE_ALPHA))
                // 滚动后进一步变暗 (滚过 FADE_DISTANCE 后达到 基础 + 附加)
                val progress = (scrollState.value / HERO_BACKDROP_FADE_DISTANCE.toPx()).coerceIn(0f, 1f)
                if (progress > 0f) {
                    drawRect(Color.Black.copy(alpha = progress * TV_VIDEO_SCRIM_SCROLL_EXTRA_ALPHA))
                }
                // 底部渐变 (对应原版 backdrop 底部渐隐位置)
                drawRect(
                    brush = Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        0.98f to Color.Black.copy(alpha = TV_VIDEO_SCRIM_BOTTOM_ALPHA),
                    ),
                )
                // 左缘 scrim: 浮在视频上的标题可读性 (同原版)
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = TV_VIDEO_SCRIM_LEFT_ALPHA),
                        0.55f to Color.Transparent,
                    ),
                )
            },
    )
}

/**
 * 视频背景遮罩: 首屏基础整屏压暗 (调大更暗, 文字更清晰但视频更不可见).
 *
 * 调这四个值前先算叠加: 四层都是画在一起的黑, 观感亮度是 `1-(1-a)(1-b)` 连乘, 不是各自的值.
 * 当前一档下: 首屏 0.38, 滚到底 0.48, 底缘 0.66 (滚动后 0.71), 左缘 0.66 —— 都还能透出画面.
 * 之前一档 (0.55/0.25/0.95/0.7) 的底缘叠到 0.98, 等于纯黑, 就是"底部几乎全黑"的来源.
 */
private const val TV_VIDEO_SCRIM_BASE_ALPHA = 0.38f

/** 视频背景遮罩: 滚动后在基础之上叠加的压暗量 (滚过 FADE_DISTANCE 后满额). */
private const val TV_VIDEO_SCRIM_SCROLL_EXTRA_ALPHA = 0.16f

/**
 * 视频背景遮罩: 底部渐变的最深处不透明度.
 *
 * 这层只为对齐独立页 backdrop 的底部渐隐 (那边下面是页面底色, 这边下面是视频),
 * 不承担可读性 —— 底部那点文字已经压在基础层上了, 所以可以给得比别处松.
 */
private const val TV_VIDEO_SCRIM_BOTTOM_ALPHA = 0.45f

/** 视频背景遮罩: 左缘渐变 (标题可读性) 的最深处不透明度. */
private const val TV_VIDEO_SCRIM_LEFT_ALPHA = 0.45f

/** 滚动多远后背景图淡到 [HERO_BACKDROP_MIN_ALPHA]. */
private val HERO_BACKDROP_FADE_DISTANCE = 300.dp

/**
 * 向下滚动后背景图保留的透明度 (不完全消失).
 *
 * 与底缘擦除叠乘: 滚下去之后底部那一带看到的图 ≈ 本值 × 未被擦掉的比例, 0.3 那档在深色主题里
 * 基本等于没有 (选集卡片以下整片近黑). 调大更亮但内容区背后更花.
 */
private const val HERO_BACKDROP_MIN_ALPHA = 0.42f

/** 页内导航时焦点下缘距屏幕下缘的最小可见余量: 露出后留出该余量. */
private val SECTION_ITEM_REVEAL_MARGIN = 24.dp

/**
 * 选集页之后额外留的空白: 让第二页底部不露出"角色"标题.
 *
 * 同 [TV_LAST_PAGE_LEAD_GAP], 它**不是**实际推动的量: Spacer 进 Column 后两侧各摊一份区块间距,
 * 所以实际推动 = 本值 + 24, 最小推动量就是 24dp (本值取 0 时).
 * 实测"角色"标题原本在 524..540 (露出 16dp), 取 8 => 推 32 => 落到 556, 留 16dp 余地.
 */
private val TV_EPISODES_PAGE_TRAIL_GAP = 8.dp

/**
 * 末页之前额外留的空白: 让上一页底部只露出"评价"标题, 既不露半截评论卡, 也不让标题贴死在屏幕底缘.
 *
 * 注意它**不是**标题实际下移的量: Spacer 是 Column 的子项, 两侧各自还摊到一份区块间距 (24dp),
 * 所以实际下移 = 本值 + 24.
 *
 * 标定 (2026-09-15 实测, 圆头像 112dp 那一版: 标题原位 452dp, 标题高 24, 标题到卡片 TV_REVIEW_HEADER_GAP = 16):
 * 取 24 => 实际下移 48 => 标题落在 500..524.
 *
 * **圆头像换回 Apple 的 130dp 之后重新标定**: 两排各高 18dp, 原位被推到 488; 两排之间收窄 8dp
 * (TV_PEOPLE_ROW_GAP) 抵回来一点 => 原位 480. 于是本值取 0 (只剩 Spacer 摊到的那份 24dp):
 * 标题落在 504..528, 下方留 12dp; 评论卡上沿在 504 + 24 + 16 = 544, 仍在 540dp 视口之外, 一条边不露.
 * 再往这一页加东西就会把标题挤出屏幕 (用户 2026-09-15: "第三页的评价的字不见了").
 */
private val TV_LAST_PAGE_LEAD_GAP = 0.dp

/**
 * 角色排与制作人员排之间的间距 (只在独立页的第三页用, 比常规区块间距 24dp 窄).
 *
 * 这一页是整个详情页最挤的一页: 两排 lockup 各 130 + 40 = 170dp, 加两个标题行与间距已经 432dp,
 * 还要给下一页的"评价"标题留出露头的位置. 见 [TV_LAST_PAGE_LEAD_GAP] 的标定.
 */
private val TV_PEOPLE_ROW_GAP = 16.dp

/** 选集整页 (标题+简介+封面+轮播) 到位后距屏幕上/下边缘的空隙. */
private val EPISODES_PAGE_VERTICAL_MARGIN = 24.dp


/**
 * 选集整页"上半简介 + 轮播"这层相对整页高度的收窄量: 收窄吃掉简介文字下方留白 (文字顶对齐
 * 不动), 把轮播及其后区块整体上移. 调大上移更多, 0 则恢复占满整页. 不影响封面 (按原整页高算).
 *
 * 16 -> 28dp (用户 2026-09-17: 简介文字离选集卡太远). 同一轮里展开按钮挪到了正文末行右边
 * (见 TvTruncatedSummary 的 expandOnLastLine), 正文底内边距那 8dp 也还给了文字 —— 两处合起来
 * 文字与卡片之间收掉约一行. **这是这一页唯一该动的旋钮**: 内嵌介绍页的简介与标签之间是另一套
 * 标定 (TV_EMBEDDED_SUMMARY_HEIGHT), 别跟着一起改.
 */
private val EPISODES_PAGE_CONTENT_LIFT = 28.dp

/**
 * 选集整页右侧竖版封面高度占整页高度的比例. 封面锚定右上, 超出"标题+简介"区的部分
 * 向下延伸到聚焦集小标题/简介右侧 (这些文字均以封面宽收右边界, 不会被盖住).
 */
private const val TV_EPISODES_COVER_HEIGHT_FRACTION = 0.6f

/** Hero 主操作按钮的圆角: 比 M3 默认胶囊更尖. */
private val TV_BUTTON_SHAPE = RoundedCornerShape(8.dp)

/**
 * 圆钮 (收藏 / 选集 / 在 Bangumi 打开) 的容器直径: M3 extra-small icon button 规格
 * (32dp 容器 / 20dp 字形, 见 [TV_ICON_GLYPH_SIZE]), 与左缘侧边栏图标按钮同尺寸.
 * 聚焦填充即容器本身.
 */

/** Hero 中列左侧 (统计+连载) 的高度: 与左列 "圆钮行 (44dp) + 间距 (10dp) + 播放按钮 (38dp)" 一致. */
private val TV_HERO_MIDDLE_HEIGHT = TV_CAPSULE_SIZE + 10.dp + 38.dp

// ===== 信息带中列对齐手动微调 (在几何对齐基础上的修正量; 正值向下移, 负值向上移) =====

/** 连载信息 (两行整体) 相对左列三圆钮中心的垂直微调. */
private val TV_AIRING_ALIGN_TRIM = -10.dp

/** 统计数字 (两行整体) 相对播放按钮中心的垂直微调. */
private val TV_STATS_ALIGN_TRIM = -10.dp

/** 标签墙 (整体) 相对左列按钮块 (圆钮行+播放按钮) 整体中心的垂直微调. */
private val TV_TAGS_ALIGN_TRIM = -8.dp

/**
 * 信息带按钮玻璃底的墨色浓度.
 *
 * 信息带所在的背景图底部区域已渐隐、露出 surface 色的页面背景 (见 [TvHeroBackdrop]),
 * 因此按钮底色以 onSurface 为墨色加此透明度: 暗色主题为白色半透明,
 * 浅色主题自动变为深色半透明; 配合不透明 onSurface 内容色, 任意主题下均清晰.
 */
private const val TV_GLASS_ALPHA = GLASS_CONTAINER_ALPHA

/** 阅读模式等大面积底色再减淡一档, 避免大块墨色压住文字. */
private const val TV_GLASS_READING_ALPHA = 0.03f

/**
 * 简介块右侧为阅读模式滚动条预留的宽度. 截断态与阅读态都预留 —— 两种模式文字宽度
 * 完全一致, 换行/每行字数不变 (阅读态视口的整行量化也依赖两态排版一致).
 */
private val TV_READING_SCROLLBAR_RESERVE = 12.dp

/** 阅读模式每次按键滚动的动画时长 (ms): 越小滚得越快. */
private const val TV_READING_SCROLL_ANIM_MS = 120

/** TV 详情页玻璃底色, 见 [glassContainerColor] (选集卡片也用它, 所以放在了 ui-foundation). */
@Composable
private fun tvGlassColor(alpha: Float = TV_GLASS_ALPHA): Color = glassContainerColor(alpha)

/** 按钮聚焦时主色填充的不透明度: 留一点透明让背景透出来, 不至于一块实心色块. */

/**
 * 收藏圆钮: 平时只显示当前收藏状态的图标, 聚焦时横向展开状态文字.
 * 点击弹出五态收藏菜单 (DropdownMenu); 设为"看过"时弹出"同时标记所有剧集"对话框.
 */
@Composable
private fun TvCollectionCapsule(
    state: EditableSubjectCollectionTypeState,
    modifier: Modifier = Modifier,
) {
    EditableSubjectCollectionTypeDialogsHost(state)
    val presentation by state.presentationFlow.collectAsStateWithLifecycle()
    // 展开状态由调用方持有 (上游 #3372 起)
    var dropdownExpanded by remember { mutableStateOf(false) }
    val type = presentation.selfCollectionType
    val action = remember(type) { SubjectCollectionActionsForCollect.find { it.type == type } }
    Box(modifier) {
        TvCapsuleButton(
            // 更新进行中忽略点击但保持可聚焦: enabled=false 会让按钮失去焦点能力, 焦点会飞走
            onClick = { if (!presentation.isSetSelfCollectionTypeWorking) dropdownExpanded = true },
            icon = { action?.icon?.invoke() },
            label = {
                if (type == UnifiedCollectionType.NOT_COLLECTED) {
                    action?.title?.invoke()
                } else {
                    Text(renderCollectionTypeAsCurrent(type), softWrap = false)
                }
            },
        )
        EditCollectionTypeDropDown(
            state,
            expanded = dropdownExpanded,
            onDismissRequest = { dropdownExpanded = false },
            // 半透明容器 (详情页所有弹出菜单统一)
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = MENU_CONTAINER_ALPHA),
        )
    }
}

/**
 * Hero 主操作 (播放) 按钮: 玻璃底圆角矩形, ▶ 图标 + 文字居中,
 * 聚焦时填充主题主色 (动态主题下即封面取色). 当前要播的集有播放进度时,
 * 按钮正下方画一条与按钮同宽的细进度条 (在按钮外部, 不随聚焦反色).
 *
 * [onLongPress] 非 null 时支持长按确认键 (按住到阈值即触发, 不等松开): 详情页用来
 * 跳到当前集的选集卡片. 此时短按的点击改在 KeyUp 触发 (确认键全部在 preview 层消费).
 */
@Composable
private fun TvPlayButton(
    state: SubjectProgressState,
    onPlay: () -> Unit,
    playProgress: Float?,
    modifier: Modifier = Modifier,
    /** 作用于按钮本体 (如 focusRequester); [modifier] 作用于"按钮 + 进度条"整体. */
    buttonModifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val containerColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = TV_FOCUSED_CONTAINER_ALPHA)
        else tvGlassColor(),
    )
    val contentColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.onPrimary else onSurface,
    )
    // 长按 (同选集网格/排序格, 共用实现见 tvLongPressKey): 按住到阈值立即触发跳转 (不等松开),
    // 残余按键由目标卡片吞掉 (不是从它起手的手势, 它的 tvLongPressKey 不计数不派发).
    val strings = rememberSubjectStatusStrings()
    Column(modifier) {
        Surface(
            onClick = onPlay,
            modifier = buttonModifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (onLongPress == null) {
                        Modifier
                    } else {
                        Modifier.tvLongPressKey(onLongPress = onLongPress, onShortPress = onPlay)
                    },
                )
                .tvTouchFocusOnTap(),
            shape = TV_BUTTON_SHAPE,
            color = containerColor,
            contentColor = contentColor,
        ) {
            Row(
                // 文字 titleMedium (行高 24sp) 配 38dp 高: 文字与上下边界各留 7dp,
                Modifier.height(38.dp).fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text(
                    state.buttonText(strings),
                    style = MaterialTheme.typography.titleSmall,
                    softWrap = false,
                )
            }
        }
        val progress = playProgress?.coerceIn(0f, 1f)
        if (progress != null && progress > 0f && progress < 1f) {
            Box(
                Modifier
                    // 进度条嵌在按钮底边内侧 (负偏移整条叠上按钮, 条的下缘与按钮下缘重合);
                    // 不占布局高度 (layout 高度上报 0):
                    // 信息带三列底对齐, 左列底必须恒为播放按钮底 —— 若进度条占高度,
                    // 有观看进度的条目左列会多出高度, 中列所有中心对齐整体歪掉
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, 0) {
                            placeable.place(0, -placeable.height)
                        }
                    }
                    .fillMaxWidth()
                    // 两端缩进按钮圆角半径: 条长 = 按钮底边未被圆角削掉的直线段
                    .padding(horizontal = 8.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(onSurface.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(onSurface),
                )
            }
        }
    }
}

/**
 * Hero 首屏内容 (滚动列内): 标题浮于背景图上 (白色, 图左有暗色 scrim 保证对比);
 * 其余 (元数据 / 评分 / 简介 / 主操作) 下沉到图的底部渐变区自成一段. 背景图见 [TvHeroBackdrop].
 */
@Composable
private fun TvHeroBlock(
    state: SubjectDetailsState,
    info: SubjectInfo,
    selfInfo: SelfInfoUiState,
    onPlay: (episodeId: Int) -> Unit,
    onClickLogin: () -> Unit,
    onClickOpenExternal: () -> Unit,
    horizontalPadding: Dp,
    /** 作用于 Hero 区主操作按钮 (播放) 本体: 页面挂 HERO_PLAY 锚点请求器与到位确认. */
    primaryButtonModifier: Modifier,
    /** 信息带中列内容 (收藏统计/标签墙/连载信息), 由调用方注入 (需要页面级状态). */
    middleColumn: @Composable RowScope.() -> Unit = {},
    /** 是否有全屏横版背景图. 无图时标题用主题色, 且在右侧展示竖版封面. */
    hasBackdrop: Boolean,
    onCoverImageSuccess: (AniImageLoadSuccess) -> Unit,
    modifier: Modifier = Modifier,
    /** 当前要播的集的播放进度 (0..1), 无记录为 null; 播放按钮底部进度条用. */
    playProgress: Float? = null,
    /** 播放按钮长按 (按住确认键到阈值) 的动作; 详情页传"跳到当前集的选集卡片". */
    onLongPressPlay: (() -> Unit)? = null,
    /** 收藏钮右侧的"选集"圆钮 (含锚定其下的网格菜单), 由调用方注入. */
    episodeGridCapsule: @Composable () -> Unit = {},
    /** 展示用简介 (Bangumi 全外文时已替换为 TMDB 中文); 默认用原文. */
    displaySummary: String = info.summary,
    /** 作用于大标题本体: 放大转场时从列表页的位置平移过来 (见 tvHeroZoomTitleShift). */
    titleModifier: Modifier = Modifier,
    /**
     * 标题之外的东西 (副标题 / 信息带整条: 圆钮、播放按钮、标签墙、评分) 要不要组合: 放大转场到位前 false —— 标题
     * 要第一帧就在 (从列表页的位置平移过来), 其余全部延后, 首帧只有一个 Text. 块高由外层钉死 (heroHeight), 标题
     * 顶对齐, 信息带在不在都不挪它. 播放按钮晚组合没关系: 落点请求悬挂着, 锚点一出现就落 (TvFocusScope).
     */
    bodyComposed: Boolean = true,
    /** 组合着但不画: 放大尾段提前组合、落地途中才显示 (见真页 bodyEarly / uiEarly). 只作用于 [bodyComposed] 管的那些. */
    bodyHidden: () -> Boolean = { false },
) {
    Column(modifier.fillMaxWidth().padding(start = horizontalPadding)) {
        // 上半区: 左 = 标题 (有背景图时白色浮于图上); 右 = 无横版图时的竖版封面,
        // 高度正好撑满 "顶栏按钮之下、信息带之上", 随内容滚出屏幕
        Row(Modifier.weight(1f).fillMaxWidth().padding(end = horizontalPadding)) {
            Column(
                Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 白色标题浮于背景图上, 图亮部会看不清: 加柔和黑色阴影兜底
                val titleShadow = if (hasBackdrop) {
                    with(LocalDensity.current) {
                        Shadow(
                            color = Color.Black.copy(alpha = 0.6f),
                            offset = Offset(0f, 1.dp.toPx()),
                            blurRadius = 6.dp.toPx(),
                        )
                    }
                } else null
                Text(
                    info.displayName,
                    titleModifier,
                    style = MaterialTheme.typography.headlineLarge.copy(shadow = titleShadow),
                    color = if (hasBackdrop) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bodyComposed && info.name.isNotBlank() && info.name != info.displayName) {
                    Text(
                        info.name,
                        Modifier.graphicsLayer { alpha = if (bodyHidden()) 0f else 1f },
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = titleShadow),
                        color = if (hasBackdrop) Color.White.copy(alpha = 0.78f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (bodyComposed && !hasBackdrop && displaySummary.isNotBlank()) {
                    // 无横版图时标题下方较空: 简介填进来, 放不下省略;
                    // 完整简介看"作品信息"子页面 (此时信息带入口只显示标签, 不重复文字)
                    Text(
                        displaySummary,
                        Modifier.weight(1f, fill = false).padding(top = 8.dp, bottom = 16.dp)
                            .graphicsLayer { alpha = if (bodyHidden()) 0f else 1f },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!hasBackdrop) {
                AsyncImage(
                    info.imageLarge,
                    contentDescription = null,
                    Modifier
                        .fillMaxHeight()
                        .padding(bottom = 16.dp)
                        .aspectRatio(COVER_WIDTH_TO_HEIGHT_RATIO)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                    onSuccess = onCoverImageSuccess,
                )
            }
        }

        // 信息带: 左 = 圆钮行 / 播放按钮 上下两行; 中 = 统计+标签墙+连载信息;
        // 右 = 完整评分区. 三列底部对齐 (标签墙底缘与评分底缘齐平, 信息带整体贴底的延续).
        // 列间距显式控制 (不用 spacedBy): 左↔中 24; 中↔右 12 —— 标签墙右缘外扩一档,
        // FlowRow 换行的锯齿空白不至于叠上整份间距显得中右之间空一条
        if (bodyComposed) Row(
            Modifier.fillMaxWidth().padding(end = horizontalPadding).graphicsLayer { alpha = if (bodyHidden()) 0f else 1f },
            verticalAlignment = Alignment.Bottom,
        ) {
            // 左列整体提层: 圆钮聚焦时上方浮现的文字标签要盖在上方内容之上.
            // IntrinsicSize.Max: 播放按钮 fillMaxWidth 后与上方"圆钮行 + 连载信息"等宽.
            Column(
                Modifier.zIndex(1f).width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 上: 圆钮行 — 收藏 + 选集 + 在 Bangumi 打开 (原右上角按钮; TV 上仅详情页有该操作).
                // 间距 8dp: M3 图标按钮排布的标准相邻间距 (容器即聚焦填充, 不会互相贴上)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selfInfo.isSessionValid == false) {
                        // 未登录: 圆钮显示"收藏"图标, 聚焦浮现登录提示, 点击进登录页
                        TvCapsuleButton(
                            onClick = onClickLogin,
                            icon = { SubjectCollectionActions.Collect.icon() },
                            label = {
                                Text(stringResource(Lang.subject_details_login_to_collect), softWrap = false)
                            },
                        )
                    } else {
                        TvCollectionCapsule(state.editableSubjectCollectionTypeState)
                    }
                    // 选集快速跳转 (圆钮 + 下拉网格), 样式与相邻圆钮一致
                    episodeGridCapsule()
                    TvCapsuleButton(
                        onClick = onClickOpenExternal,
                        icon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                        label = { Text("Bangumi", softWrap = false) },
                    )
                }
                // 下: 播放按钮 (下方带播放进度条), 宽度与列同宽
                // (IntrinsicSize.Max: 取"圆钮行 / 按钮文字固有宽"中较大者).
                // offset 微微上移 (纯视觉, 不占布局, 周围组件与三列底对齐的几何全部不动)
                TvPlayButton(
                    state.subjectProgressState,
                    onPlay = { state.subjectProgressState.episodeIdToPlay?.let(onPlay) },
                    playProgress = playProgress,
                    modifier = Modifier.fillMaxWidth().offset(y = (-4).dp),
                    buttonModifier = primaryButtonModifier,
                    onLongPress = onLongPressPlay,
                )
            }
            middleColumn()
            // 右: 评分区 — 分布直方图在上, 评分摘要在下. IntrinsicSize.Max 取两者固有宽度的
            // 较大值: 直方图不会小于自身最小宽度 (压窄会让 "10" 标签折行), 摘要更宽时直方图拉伸同宽
            // 居中: 列宽 = 两者中较宽者 (IntrinsicSize.Max), 窄的一个水平居中 ——
            // 直方图与评分摘要的水平中心对齐
            Column(
                Modifier.padding(start = 12.dp).width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RatingHistogram(
                    info.ratingInfo,
                    Modifier.fillMaxWidth(),
                    barHeight = 36.dp,
                )
                // 直方图紧贴下方评分: 无额外间距 (直方图自身与刻度行间已有 6dp)
                // 只认"自己打开的那次"评分弹窗: 同一个 state 也挂在「查看全部」评论里的写评价上,
                // 不分辨来源的话从那边打开、关闭后焦点会被这里抢过来
                val ratingSource = remember { Any() }
                SubjectRatingSummary(
                    info.ratingInfo,
                    // 评分弹窗关掉之后焦点还回本按钮 (弹窗是独立窗口, 关掉后不保证还回来)
                    Modifier.restoreFocusAfter(state.editableRatingState.isEditingFrom(ratingSource)),
                    onClick = { state.editableRatingState.requestEdit(ratingSource) },
                )
            }
        }
    }
}

/**
 * 播放器内嵌变体的介绍页 (整屏吸附区块, 首屏), 左右两列:
 * - 左列 (定宽): 海报 / 收藏统计 / 评分 (直方图 + 摘要), 自上而下排满;
 * - 右列 (自适应): 标题(+原名) / 连载信息 / 简介 (阅读模式) / 标签墙 (占满简介与
 *   评论之间的空间, 整列宽) / 评论预览 (贴页底, 与下边界留正常空隙).
 *
 * 无任何操作按钮 (播放/选集/收藏/缓存/外链全部由播放器控制栏承担), 本页只展示内容.
 * 简介块带"暂无信息"兜底恒可聚焦, 是本页的保底焦点目标 (也是 EPISODES_SUMMARY
 * 锚点在内嵌变体的挂载点: 进入焦点落在这里).
 */
@Composable
private fun TvEmbeddedHeroPage(
    state: SubjectDetailsState,
    info: SubjectInfo,
    displaySummary: String,
    comments: LazyPagingItems<UIComment>,
    commentCount: Int?,
    onShowComments: (initialFocusIndex: Int) -> Unit,
    /** 正片话数, 进简介全文弹窗的底行元信息; 没有分集时为 null. */
    mainEpisodeCount: Int?,
    /** 跨区块导航路由: 本页上缘 (回播放器选集条) 与下缘 (关联条目区) 的按键接线全走它. */
    sectionNav: TvDetailsSectionNav,
    onClickTag: (Tag) -> Unit,
    browseMode: Boolean,
    onBrowseModeChange: (Boolean) -> Unit,
    focusedTagIndex: Int,
    onFocusedTagIndexChange: (Int) -> Unit,
    restorePending: Boolean,
    onRestoreConsumed: () -> Unit,
    anchors: TvFocusScope,
    wallRestoreIndex: Int,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = 8.dp)
            // 页内焦点向上越界 (简介/评分等顶部元素再按上, 页内没有更高的目标) 时
            // 回到播放器选集条; 页内的向上移动 (标签→简介等) 不触发.
            // 兜底钩子 (设备上不总触发): 主路径是下方左列/简介块的按键拦截
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Up &&
                        sectionNav.moveUp(TvDetailsSection.HERO)
                    ) {
                        cancelFocusChange()
                    }
                }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // 左列: 海报 / 收藏统计 / 评分.
        // 列内唯一焦点元素是评分摘要 (上方海报/统计不可聚焦): 按上直接回播放器选集条
        // (按键拦截比 focusProperties.onExit 钩子可靠, 与简介块同一套路)
        Column(
            Modifier.width(TV_EMBEDDED_LEFT_COLUMN_WIDTH)
                .tvSectionEdge(sectionNav, TvDetailsSection.HERO, up = true),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (info.imageLarge.isNotBlank()) {
                AsyncImage(
                    info.imageLarge,
                    contentDescription = null,
                    Modifier.fillMaxWidth()
                        .aspectRatio(COVER_WIDTH_TO_HEIGHT_RATIO)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            // 三个统计单元均匀摊开, 首末单元与海报左右边界对齐;
            // 文字大小由 TV_EMBEDDED_STATS_TEXT_SCALE 统一缩放 (数字与小字同步)
            TvCompactStatsRow(
                info.collectionStats,
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                textScale = TV_EMBEDDED_STATS_TEXT_SCALE,
            )
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RatingHistogram(
                    info.ratingInfo,
                    Modifier.fillMaxWidth(),
                    barHeight = 36.dp,
                )
                // 直方图紧贴下方评分: 无额外间距 (直方图自身与刻度行间已有 6dp)
                val ratingSource = remember { Any() }
                SubjectRatingSummary(
                    info.ratingInfo,
                    Modifier.restoreFocusAfter(state.editableRatingState.isEditingFrom(ratingSource)),
                    onClick = { state.editableRatingState.requestEdit(ratingSource) },
                )
            }
        }
        // 右列: 标题 / 连载信息 / 简介 / 标签墙
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    info.displayName,
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info.name.isNotBlank() && info.name != info.displayName) {
                    Text(
                        info.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 连载信息行 (标题与简介之间)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    renderSubjectSeason(info.airDate),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                AiringLabel(
                    state.airingLabelState,
                    style = MaterialTheme.typography.titleSmall,
                    progressColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TvTruncatedSummary(
                displaySummary.ifBlank { stringResource(Lang.subject_details_no_summary) },
                dialogTitle = info.displayName,
                // 与独立页的简介弹窗同样带上撤掉的「作品信息」(别名进正文末尾, 放送日期与话数进底行):
                // 这些内容现在**只在这个弹窗里**, 内嵌变体不给的话就彻底够不到了 (用户 2026-09-17)
                dialogExtra = subjectAliasesText(info),
                dialogMeta = subjectMetaLine(info, mainEpisodeCount),
                modifier = Modifier.height(TV_EMBEDDED_SUMMARY_HEIGHT).fillMaxWidth(),
                // 展开按钮是本页顶部唯一焦点目标 (HERO 段落的落点 + 进页初始焦点):
                // 简介短到不截断也必须渲染, 否则内嵌介绍页进去没有焦点
                alwaysShowExpand = true,
                // EPISODES_SUMMARY 锚点 (内嵌变体挂载点) + 到位确认
                expandModifier = Modifier.tvFocusAnchor(
                    anchors,
                    TvDetailsFocusAnchor.EPISODES_SUMMARY,
                ),
                // 按钮是页面顶部焦点元素: 按上回播放器选集条; 无出口时不传 (交回空间搜索)
                onNavigateUp = if (sectionNav.canMoveUp(TvDetailsSection.HERO)) {
                    { sectionNav.moveUp(TvDetailsSection.HERO) }
                } else null,
            )
            // 标签墙: 占满简介与评论区之间的全部剩余空间 (整列宽)
            TvHeroTagsWall(
                tags = info.tags,
                onClickTag = onClickTag,
                browseMode = browseMode,
                onBrowseModeChange = onBrowseModeChange,
                focusedTagIndex = focusedTagIndex,
                onFocusedTagIndexChange = onFocusedTagIndexChange,
                restorePending = restorePending,
                onRestoreConsumed = onRestoreConsumed,
                anchors = anchors,
                wallRestoreIndex = wallRestoreIndex,
                maxLines = TV_EMBEDDED_TAGS_MAX_LINES,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                flowModifier = Modifier.fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            )
            // 评价: 贴介绍页底部 (标签墙 weight 把它压到最下), 与下边界留正常空隙.
            //
            // **与独立页第四页同一个组件** ([ReviewsSummarySection]). 先前这里用的是手机端那份
            // `ReviewsPreviewSection`, 于是三处都不一样 (用户 2026-09-17): 卡片样式 (聚焦了没有框)、
            // "查看全部"的形态 (标题行右边一个文字按钮 vs 行末一张同形卡)、确认键的去处 (每张卡都落在
            // 第一条 vs 落在它自己那一条). 右列比整屏窄, 组件按可用宽度自己少排一张卡.
            //
            // 下缘接线挂整块即可: 标题行不再有可聚焦的"查看全部"按钮, 没有"把它的下键吃掉"的问题.
            if (comments.itemCount > 0) {
                ReviewsSummarySection(
                    comments = comments,
                    totalCount = commentCount,
                    onShowAll = onShowComments,
                    modifier = Modifier.fillMaxWidth()
                        .padding(bottom = TV_EMBEDDED_BOTTOM_MARGIN)
                        .tvSectionEdge(sectionNav, TvDetailsSection.HERO, down = true),
                    // **不定高**: 独立页那边卡是 124dp 定高 (整页就那一个区块), 这里评价是页底最后一块,
                    // 上面压着标签墙 —— 定高会把标签挡掉 (用户 2026-09-17). 按内容收, 正文回到 1 行,
                    // 与改版前同一个高度; 变的只是卡片样式 (两行头部 / 聚焦框) 与点击去处.
                    cardHeight = null,
                    cardTextLines = TV_EMBEDDED_REVIEW_TEXT_LINES,
                )
            }
        }
    }
}

/** 内嵌介绍页左列 (海报/收藏统计/评分) 宽度; 海报高按 2:3 从此宽推出. */
private val TV_EMBEDDED_LEFT_COLUMN_WIDTH = 200.dp

/** 内嵌介绍页简介块高度 (其下全部剩余空间给标签墙). */
private val TV_EMBEDDED_SUMMARY_HEIGHT = 150.dp

/** 内嵌介绍页评论卡的正文行数: 卡不定高, 靠它把卡收到改版前的高度. */
private const val TV_EMBEDDED_REVIEW_TEXT_LINES = 1

/** 内嵌介绍页标签墙最大行数 (空间比独立页信息带大, 多显示几行). */
private const val TV_EMBEDDED_TAGS_MAX_LINES = 6

/** 内嵌介绍页评论预览与页底边界的空隙 (页块本身已距屏幕下缘 16dp, 视觉总空隙 ≈ 两者之和). */
private val TV_EMBEDDED_BOTTOM_MARGIN = 24.dp

/** 内嵌介绍页收藏统计文字缩放 (数字与小字同步; 1 = 与独立页 Hero 信息带相同大小). */
private const val TV_EMBEDDED_STATS_TEXT_SCALE = 1f
