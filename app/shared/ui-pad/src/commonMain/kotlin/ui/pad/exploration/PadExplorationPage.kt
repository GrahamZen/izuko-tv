/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.exploration

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.delay
import me.him188.ani.app.data.models.subject.subjectInfo
import me.him188.ani.app.data.models.trending.TrendingSubjectInfo
import me.him188.ani.app.data.network.TmdbTitleLogo
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.ui.exploration.ExplorationPageState
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.NsfwPolicy
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.tv.tvTitleLogoBox
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_continue_watching
import me.him188.ani.app.ui.lang.exploration_rec_loading
import me.him188.ani.app.ui.lang.exploration_rec_more
import me.him188.ani.app.ui.lang.exploration_schedule
import me.him188.ani.app.ui.lang.exploration_tv_air_date
import me.him188.ani.app.ui.lang.exploration_tv_watch_now
import me.him188.ani.app.ui.pad.foundation.PAD_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.pad.foundation.PAD_POSTER_WALL_PAGE_MARGIN
import me.him188.ani.app.ui.pad.foundation.PadPosterMoreCard
import me.him188.ani.app.ui.pad.foundation.PadPosterPlaceholder
import me.him188.ani.app.ui.pad.foundation.PadPosterWallLayout
import me.him188.ani.app.ui.pad.foundation.PadPosterWallTheme
import me.him188.ani.app.ui.pad.foundation.PadSectionHeader
import me.him188.ani.app.ui.pad.foundation.PadSubjectCard
import me.him188.ani.app.ui.pad.foundation.padPosterCardWidth
import me.him188.ani.app.ui.pad.foundation.rememberPadCollectionMenu
import me.him188.ani.app.ui.pad.foundation.rememberPadHeroMedia
import me.him188.ani.app.ui.pad.foundation.rememberPadPosterWallLayout
import me.him188.ani.app.ui.pad.foundation.rememberPadTitleLogo
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil

/**
 * 平板的探索页, 照电视版海报墙 (ui-tv 的 TvExplorationPage) 的样子: 顶上热门轮播 —— 右上角是羽化的横版背景图, 底下铺同一张图的
 * 模糊版; 下面「继续观看」一行、推荐每组一行, 卡片是海报 + 下方番名, 尺寸与配色同电视版.
 *
 * 交互是触屏: 没有焦点与 hero 态, 点卡片直接进详情页, 长按弹收藏菜单; 轮播与各行左右滑, 行松手就近停在一张卡的边上.
 */
@Composable
fun PadExplorationPage(state: ExplorationPageState, modifier: Modifier = Modifier) {
    PadPosterWallTheme {
        PadExplorationPageContent(state, modifier)
    }
}

@Composable
private fun PadExplorationPageContent(state: ExplorationPageState, modifier: Modifier) {
    val navigator = LocalNavigator.current
    val playHistoryRepository = remember { GlobalKoin.get<EpisodePlayHistoryRepository>() }
    val playHistories by playHistoryRepository.flow.collectAsStateWithLifecycle(emptyList())
    val recGroups by state.recommendationGroups.collectAsStateWithLifecycle()
    val extendingGroups by state.extendingRecommendationGroups.collectAsStateWithLifecycle()
    val recRefreshing by state.recommendationsRefreshing.collectAsStateWithLifecycle()
    val followed = state.followedSubjectsPager
    val nsfw = rememberNsfwPolicy()
    val collectionMenu = rememberPadCollectionMenu()
    val openSubject: (subjectId: Int, name: String, cover: String) -> Unit = remember(navigator) {
        { subjectId, name, cover ->
            navigator.navigateSubjectDetails(
                subjectId,
                SubjectDetailPlaceholder(id = subjectId, name = name, coverUrl = cover),
            )
        }
    }
    val moreLabel = stringResource(Lang.exploration_rec_more)

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wall = rememberPadPosterWallLayout(maxWidth)
        val pageWidth = maxWidth
        val carouselHeight = maxHeight * PAD_CAROUSEL_HEIGHT_FRACTION
        val recRows = remember(recGroups, wall.columns) { padRecRowsOf(recGroups, wall.columns) }
        val recItems = remember(recGroups) { recGroups.flatMap { it.items } }
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "carousel", contentType = "carousel") {
                PadTrendingCarousel(
                    items = state.trendingSubjectInfoPager,
                    pageWidth = pageWidth,
                    height = carouselHeight,
                    nsfw = nsfw,
                    onOpen = { openSubject(it.bangumiId, it.nameCn, it.imageLarge) },
                    onSchedule = { navigator.navigateSchedule() },
                )
            }
            if (followed.itemCount > 0) {
                item(key = "followed-header", contentType = "header") {
                    PadSectionHeader(stringResource(Lang.exploration_continue_watching))
                }
                item(key = "followed-row", contentType = "row") {
                    PadPosterRow(count = followed.itemCount, layout = wall) { index ->
                        // 按下标读一次 = 向分页报告访问位置, 继续观看往后滑到哪就取到哪
                        val item = followed[index]
                        if (item == null) {
                            PadPosterPlaceholder(Modifier.padPosterCardWidth(wall))
                        } else {
                            val info = item.subjectInfo
                            PadSubjectCard(
                                subjectId = info.subjectId,
                                title = info.displayName,
                                coverUrl = info.imageLarge,
                                nsfw = nsfw,
                                menu = collectionMenu,
                                onClick = { openSubject(info.subjectId, info.displayName, info.imageLarge) },
                                modifier = Modifier.padPosterCardWidth(wall),
                                progress = padFollowedCardProgress(item, playHistories),
                            )
                        }
                    }
                }
            }
            recRows.forEachIndexed { rowIndex, row ->
                if (row.header) {
                    item(key = "rec-header-$rowIndex", contentType = "header") {
                        PadSectionHeader(padRecGroupTitle(row.group))
                    }
                }
                val withMore = row.last && row.group.extendable
                item(key = "rec-row-$rowIndex", contentType = "row") {
                    PadPosterRow(count = row.size + if (withMore) 1 else 0, layout = wall) { index ->
                        if (index < row.size) {
                            val item = recItems[row.start + index]
                            val title = item.nameCn.ifBlank { item.name }
                            PadSubjectCard(
                                subjectId = item.bangumiId,
                                title = title,
                                coverUrl = item.imageLarge,
                                nsfw = nsfw,
                                menu = collectionMenu,
                                onClick = { openSubject(item.bangumiId, title, item.imageLarge) },
                                modifier = Modifier.padPosterCardWidth(wall),
                            )
                        } else {
                            PadPosterMoreCard(
                                label = moreLabel,
                                loading = row.group.key in extendingGroups,
                                onClick = { state.extendRecommendationGroup(row.group.key) },
                                modifier = Modifier.padPosterCardWidth(wall),
                            )
                        }
                    }
                }
            }
            if (recGroups.isEmpty() && recRefreshing) {
                item(key = "rec-loading", contentType = "header") {
                    PadSectionHeader(stringResource(Lang.exploration_rec_loading))
                }
            }
            item(key = "bottom-space", contentType = "spacer") {
                Spacer(Modifier.navigationBarsPadding().height(PAD_EXPLORATION_BOTTOM_SPACE))
            }
        }
    }
}

/** 一行海报: 行首对齐页面留白, 横滑松手就近停在一张卡的左边 (电视上「拖完就近停」同一个意思). */
@Composable
private fun PadPosterRow(
    count: Int,
    layout: PadPosterWallLayout,
    item: @Composable (index: Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyRow(
        Modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = layout.margin),
        horizontalArrangement = Arrangement.spacedBy(PAD_POSTER_WALL_COLUMN_SPACING),
        flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Start),
    ) {
        items(count) { index -> item(index) }
    }
}

/**
 * 热门轮播, 操作同电视版装在平板上的触屏: 左右滑一下就是一步 (左滑 = 下一部, 同遥控器的右键; 到头滑不动), 换过去的过渡自己播完 ——
 * 新的一部在旧的上面淡入 (旧的不动、一直不透明, 中间不会变暗), 不跟着手指拖的程度变. 不动时每 [PAD_CAROUSEL_AUTO_ADVANCE_MILLIS]
 * 自动翻下一部 (页面不在前台时不翻). 文字照电视版 hero 的顺序: 先背景后文字、旧的先走. 两颗按钮与圆点固定不动.
 * 点画面或「立即观看」进当前这一部的详情页, 「新番时间表」进时间表 —— 同电视版轮播.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun PadTrendingCarousel(
    items: LazyPagingItems<TrendingSubjectInfo>,
    pageWidth: Dp,
    height: Dp,
    nsfw: NsfwPolicy,
    onOpen: (TrendingSubjectInfo) -> Unit,
    onSchedule: () -> Unit,
) {
    val count = items.itemCount.coerceAtMost(PAD_CAROUSEL_MAX_ITEMS)
    Box(Modifier.fillMaxWidth().height(height)) {
        if (count == 0) return@Box
        var selected by rememberSaveable { mutableIntStateOf(0) }
        val index = selected.coerceIn(0, count - 1)
        val foreground by LocalPageIsForeground.current
        LaunchedEffect(index, foreground, count) {
            if (!foreground || count <= 1) return@LaunchedEffect
            delay(PAD_CAROUSEL_AUTO_ADVANCE_MILLIS)
            selected = (index + 1) % count
        }
        val swipeThresholdPx = with(LocalDensity.current) { PAD_CAROUSEL_SWIPE_THRESHOLD.toPx() }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(count, swipeThresholdPx) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            val from = selected.coerceIn(0, count - 1)
                            when {
                                total <= -swipeThresholdPx && from < count - 1 -> selected = from + 1
                                total >= swipeThresholdPx && from > 0 -> selected = from - 1
                            }
                        },
                        onDragCancel = { total = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        total += dragAmount
                    }
                }
                .clickable(interactionSource = null, indication = null) {
                    items.peek(index)?.let(onOpen)
                },
        ) {
            // 前后各备两部 (看不见): 条目信息与横版图先取好, 换过去时直接出图
            for (page in (index - PAD_CAROUSEL_PREPARED_PAGES)..(index + PAD_CAROUSEL_PREPARED_PAGES)) {
                if (page == index || page !in 0 until count) continue
                val item = items.peek(page) ?: continue
                key(page) {
                    PadCarouselBackdrop(item = item, nsfw = nsfw, modifier = Modifier.graphicsLayer { alpha = 0f })
                }
            }
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    fadeIn(tween(PAD_CAROUSEL_CROSSFADE_MILLIS, easing = LinearEasing)) togetherWith
                            ExitTransition.KeepUntilTransitionsFinished
                },
                label = "padCarouselBackdrop",
            ) { page ->
                val item = items.peek(page)
                if (item != null) {
                    PadCarouselBackdrop(item = item, nsfw = nsfw)
                }
            }
        }
        AnimatedContent(
            targetState = index,
            modifier = Modifier.align(Alignment.BottomStart)
                .padding(start = PAD_POSTER_WALL_PAGE_MARGIN, bottom = PAD_CAROUSEL_TEXT_BOTTOM)
                .width(pageWidth * PAD_CAROUSEL_TITLE_WIDTH_FRACTION),
            transitionSpec = {
                val forward = targetState > initialState || (initialState == count - 1 && targetState == 0)
                val direction = if (forward) 1 else -1
                val textIn = fadeIn(
                    tween(PAD_CAROUSEL_TEXT_IN_MILLIS, delayMillis = PAD_CAROUSEL_CROSSFADE_MILLIS / 2, easing = LinearEasing),
                ) + slideInHorizontally(tween(PAD_CAROUSEL_CROSSFADE_MILLIS)) { width ->
                    direction * width / PAD_CAROUSEL_TEXT_SHIFT_DIVISOR
                }
                textIn togetherWith fadeOut(tween(PAD_CAROUSEL_TEXT_OUT_MILLIS, easing = LinearEasing)) using
                        SizeTransform(clip = false)
            },
            contentAlignment = Alignment.BottomStart,
            label = "padCarouselText",
        ) { page ->
            val item = items.peek(page)
            if (item != null) {
                PadCarouselText(item, pageWidth)
            }
        }
        val contentColor = MaterialTheme.colorScheme.onSurface
        Row(
            Modifier.align(Alignment.BottomStart)
                .padding(start = PAD_POSTER_WALL_PAGE_MARGIN, bottom = PAD_CAROUSEL_BUTTONS_BOTTOM),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { items.peek(index)?.let(onOpen) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = contentColor,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Text(stringResource(Lang.exploration_tv_watch_now), fontWeight = FontWeight.SemiBold)
            }
            FilledTonalButton(
                onClick = onSchedule,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = contentColor.copy(alpha = PAD_CAROUSEL_SECONDARY_BUTTON_ALPHA),
                    contentColor = contentColor,
                ),
            ) {
                Text(stringResource(Lang.exploration_schedule), fontWeight = FontWeight.SemiBold)
            }
        }
        PadCarouselDots(
            count = count,
            current = { index },
            modifier = Modifier.align(Alignment.BottomStart)
                .padding(start = PAD_POSTER_WALL_PAGE_MARGIN, bottom = PAD_CAROUSEL_DOTS_BOTTOM),
        )
    }
}

/** 轮播一部的画面: 模糊底 + 右上角羽化的横版图 + 顶边压暗. */
@Composable
private fun PadCarouselBackdrop(
    item: TrendingSubjectInfo,
    nsfw: NsfwPolicy,
    modifier: Modifier = Modifier,
) {
    val media by rememberPadHeroMedia(item.bangumiId)
    // 横版图都没有 (已有结论) 才拿竖版封面顶上, 同电视版 hero 的兜底
    val image = media.backdropUrl ?: item.imageLarge.takeIf { media.backdropResolved && it.isNotBlank() }
    val wallColor = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize()) {
        // 底下铺同一张图的模糊版 (电视版热门轮播开着海报墙模糊时也是这样), 越往下越接近墙的底色. 横版图还没到时先拿竖版封面糊着垫底:
        // 封面随热门数据就有, 刚滑到的这一部不会露出一整块底色 (前一部淡出时看着像黑了一下)
        AsyncImage(
            media.backdropUrl ?: item.imageLarge.takeIf { it.isNotBlank() },
            contentDescription = null,
            Modifier.fillMaxSize().blur(PAD_CAROUSEL_BLUR_RADIUS),
            contentScale = ContentScale.Crop,
            downsampleLongEdgePx = PAD_CAROUSEL_BLUR_DECODE_LONG_EDGE_PX,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to wallColor.copy(alpha = PAD_CAROUSEL_BLUR_DIM_ALPHA),
                    PAD_CAROUSEL_FLOOR_START to wallColor.copy(alpha = PAD_CAROUSEL_BLUR_DIM_ALPHA),
                    1f to wallColor,
                ),
            ),
        )
        // 右上角清晰的横版图, 左缘与下缘羽化进模糊底
        if (image != null) {
            AsyncImage(
                image,
                contentDescription = null,
                Modifier.align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    .padHeroFeather(),
                contentScale = ContentScale.Crop,
                downsampleLongEdgePx = nsfw.backdropDownsample(item.bangumiId),
            )
        }
        // 顶边压一点暗, 给状态栏垫底
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(PAD_CAROUSEL_TOP_SCRIM_FRACTION).background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = PAD_CAROUSEL_TOP_SCRIM_ALPHA), 1f to Color.Transparent),
            ),
        )
    }
}

/** 轮播的文字: 标题 (或片名 logo)、评分与开播年月、三行简介. */
@Composable
private fun PadCarouselText(item: TrendingSubjectInfo, pageWidth: Dp) {
    val media by rememberPadHeroMedia(item.bangumiId)
    val info = media.info?.subjectInfo
    val contentColor = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth()) {
        PadHeroTitle(
            title = info?.displayName ?: item.nameCn,
            logo = rememberPadTitleLogo(item.bangumiId, info?.name),
            titleWidth = pageWidth * PAD_CAROUSEL_TITLE_WIDTH_FRACTION,
            color = contentColor,
        )
        val score = info?.ratingInfo?.score?.takeIf { (it.toFloatOrNull() ?: 0f) > 0f }
        val airDate = info?.airDate?.takeIf { it.isValid }
        if (score != null || airDate != null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (score != null) {
                    Text("★ $score", color = contentColor, style = MaterialTheme.typography.labelLarge)
                }
                if (airDate != null) {
                    Text(
                        stringResource(Lang.exploration_tv_air_date, airDate.year, airDate.month),
                        color = contentColor.copy(alpha = PAD_CAROUSEL_SECONDARY_ALPHA),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        // 简介原文的分段换行在三行里只会空出一行, 压成一段
        val summary = info?.summary?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" ").orEmpty()
        if (summary.isNotEmpty()) {
            Text(
                summary,
                Modifier.padding(top = 8.dp).fillMaxWidth(PAD_CAROUSEL_SUMMARY_WIDTH_FRACTION / PAD_CAROUSEL_TITLE_WIDTH_FRACTION),
                color = contentColor.copy(alpha = PAD_CAROUSEL_SECONDARY_ALPHA),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * hero 的标题: 有片名 logo 就画 logo (大小照电视版的 logo 框: 面积大致相同、越宽越矮, 底对齐在两行标题高的槽底), 否则文字标题.
 */
@Composable
private fun PadHeroTitle(title: String, logo: TmdbTitleLogo?, titleWidth: Dp, color: Color) {
    val style = MaterialTheme.typography.headlineMedium
    val density = LocalDensity.current
    val box = remember(style, density, titleWidth) {
        with(density) {
            val lineHeightPx = if (style.lineHeight.isSpecified) ceil(style.lineHeight.toPx()).toInt() else 0
            tvTitleLogoBox(lineHeightPx, titleWidth.roundToPx())
        }
    }
    if (logo != null && box != null) {
        val size = box.sizeOf(logo.aspectRatio)
        with(density) {
            Box(Modifier.height(box.maxHeightPx.toDp()), contentAlignment = Alignment.BottomStart) {
                AsyncImage(
                    logo.url(size.widthPx),
                    contentDescription = title,
                    Modifier.size(size.widthPx.toDp(), size.heightPx.toDp()),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    } else {
        Text(
            title,
            color = color,
            style = style,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PadCarouselDots(count: Int, current: () -> Int, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    val selected = current()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(width = if (index == selected) 16.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (index == selected) 1f else PAD_CAROUSEL_DOT_INACTIVE_ALPHA)),
            )
        }
    }
}

/**
 * 横版图左缘与下缘羽化成透明, 露出底下的模糊层: 遮罩画在离屏层里 (DstIn), 不离屏会把底下的模糊层一起擦掉.
 */
private fun Modifier.padHeroFeather(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.horizontalGradient(
                PAD_HERO_LEFT_FADE_START to Color.Transparent,
                PAD_HERO_LEFT_FADE_END to Color.Black,
            ),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.verticalGradient(
                PAD_HERO_BOTTOM_FADE_START to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** 轮播占页面高度的比例: 第一屏底下露出「继续观看」那一行. */
private const val PAD_CAROUSEL_HEIGHT_FRACTION = 0.62f

/** 轮播最多几部 (同电视版圆点上限). */
private const val PAD_CAROUSEL_MAX_ITEMS = 20

/** 自动翻页间隔, 同电视版. */
private const val PAD_CAROUSEL_AUTO_ADVANCE_MILLIS = 6_000L

/** 当前这一部前后各提前准备几部的画面 (条目信息、横版图). */
private const val PAD_CAROUSEL_PREPARED_PAGES = 2

/** 模糊底: 先只解一张长边这么多像素的小图 (同电视版「重」那一档), 再叠一层模糊去掉放大后的块感. */
private const val PAD_CAROUSEL_BLUR_DECODE_LONG_EDGE_PX = 160
private val PAD_CAROUSEL_BLUR_RADIUS = 24.dp

/** 模糊底上整层压的一层墙色, 让文字在任何画面上都读得清. */
private const val PAD_CAROUSEL_BLUR_DIM_ALPHA = 0.35f

/** 从轮播高度的这个位置起往下渐变到墙的底色. */
private const val PAD_CAROUSEL_FLOOR_START = 0.45f

private const val PAD_CAROUSEL_TOP_SCRIM_FRACTION = 0.18f
private const val PAD_CAROUSEL_TOP_SCRIM_ALPHA = 0.35f

/** 标题与简介的宽度占页面宽度的比例, 同电视版热门轮播 (标题 0.46、简介 0.40). */
private const val PAD_CAROUSEL_TITLE_WIDTH_FRACTION = 0.46f
private const val PAD_CAROUSEL_SUMMARY_WIDTH_FRACTION = 0.40f

private const val PAD_CAROUSEL_SECONDARY_ALPHA = 0.75f
private const val PAD_CAROUSEL_SECONDARY_BUTTON_ALPHA = 0.16f
private const val PAD_CAROUSEL_DOT_INACTIVE_ALPHA = 0.4f
/** 圆点、按钮、文字块的下沿离轮播底边多远: 自下而上圆点 → 按钮 (高 40) → 文字, 文字块往上长, 按钮的位置不随文字长短变. */
private val PAD_CAROUSEL_DOTS_BOTTOM = 18.dp
private val PAD_CAROUSEL_BUTTONS_BOTTOM = 40.dp
private val PAD_CAROUSEL_TEXT_BOTTOM = 96.dp

/**
 * 换一部的过渡, 照电视版 (Apple TV 的分段交叉淡入, 线性): 背景图 [PAD_CAROUSEL_CROSSFADE_MILLIS] 淡入; 旧文字先淡出,
 * 新文字过半再淡入, 从翻页方向挪进来宽度的几分之一.
 */
private const val PAD_CAROUSEL_CROSSFADE_MILLIS = 600
private const val PAD_CAROUSEL_TEXT_OUT_MILLIS = 150
private const val PAD_CAROUSEL_TEXT_IN_MILLIS = 300
private const val PAD_CAROUSEL_TEXT_SHIFT_DIVISOR = 12

/** 横滑超过这么远松手才算翻一步. */
private val PAD_CAROUSEL_SWIPE_THRESHOLD = 48.dp

/** 横版图左缘羽化的起止 (占图宽), 下缘从这个高度起淡到透明; 同电视版 hero 图的羽化. */
private const val PAD_HERO_LEFT_FADE_START = 0.02f
private const val PAD_HERO_LEFT_FADE_END = 0.35f
private const val PAD_HERO_BOTTOM_FADE_START = 0.6f

private val PAD_EXPLORATION_BOTTOM_SPACE = 32.dp
