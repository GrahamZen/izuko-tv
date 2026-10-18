/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.focus.TV_SCROLL_STIFFNESS
import me.him188.ani.app.ui.foundation.focus.TvNoBringIntoViewSpec
import me.him188.ani.app.ui.foundation.focus.TvScrollAnimator
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FADE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PAST_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogram
import me.him188.ani.app.ui.foundation.tv.nativeview.withNsfw
import me.him188.ani.app.ui.foundation.tv.tvAnimatedScroll
import me.him188.ani.app.ui.foundation.tv.tvPosterWallCardWidth
import me.him188.ani.app.ui.foundation.tv.tvPosterWallColumns
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_view_all
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_FOCUS_RING
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_FOCUS_SCALE
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_LINE_GAP
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_BAR_CORNER
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_NAME_WIDTH
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_SUBTITLE_WIDTH
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_SIZE
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_SPACING
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_SUBTITLE_ALPHA
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_TEXT_GAP
import me.him188.ani.app.ui.subject.details.sections.tvPeoplePlaceholderColor
import org.jetbrains.compose.resources.stringResource

/*
 * Compose 版的单独横滑行 (安卓电视上是原生行, 见 TvNativeStrip.kt): 详情页关联条目 / 人物预览弹窗的海报行, 详情页演职人员的圆头像行.
 * 两种行同一套导航: 按需挪 ([tvStripLeftIndex]) —— 焦点在屏上完整露出的几张之间走时整行不动, 走到露一截的那张才整行挪到它刚好完整露出,
 * 不循环; 行首按左、行尾按右吞掉, 上下键交给页面; 进行落点 = 上次聚焦那张 (首次进入是行首那张), 跨导航保存.
 */

/**
 * 横滑行按需挪: 焦点走到第 [target] 张 (整行 [count] 张, 屏上完整放得下 [columns] 张) 后, 行首该是第几张. [current] = 现在的行首.
 */
internal fun tvStripLeftIndex(current: Int, target: Int, count: Int, columns: Int): Int {
    val maxLeft = (count - columns).coerceAtLeast(0)
    var left = current.coerceIn(0, maxLeft)
    if (target < left) left = target
    if (target > left + columns - 1) left = target - columns + 1
    return left.coerceIn(0, maxLeft)
}

/** 横滑行里第 [index] 张卡越过行首停靠线后的压暗: 在 [fadeDistancePx] 内渐暗到 [TV_CARD_PAST_DIM_ALPHA] 并保持. 绘制阶段调用. */
internal fun tvPastAnchorDim(listState: LazyListState, index: Int, fadeDistancePx: Float): Float {
    val x = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: 0
    if (x >= 0) return 1f
    val t = (-x / fadeDistancePx).coerceIn(0f, 1f)
    return 1f - t * (1f - TV_CARD_PAST_DIM_ALPHA)
}

/** 横滑行的导航与滚动状态: 上次聚焦那张、行首 (跨导航保存), 焦点在不在行里. */
private class TvWallStripNav {
    var focusedIndex by mutableIntStateOf(-1)
    var leftIndex by mutableIntStateOf(0)
    var hasFocus by mutableStateOf(false)

    /** 设备有触摸屏 (LocalTvTouchInputEnabled). */
    var touchInput = false

    /** 最近一次输入是手指 (见 tvWallInputMode). */
    var lastInputTouch by mutableStateOf(true)

    /**
     * 此刻按触屏的规矩走 (同上游的平板界面): 行只由手指滚, 焦点到哪都不挪行、不画聚焦效果, 按下不动焦点 (点一下就是点击).
     * 平板接着键盘 / 遥控器按方向键时回到电视的规矩.
     */
    val touchMode: Boolean get() = touchInput && lastInputTouch
}

@Composable
private fun rememberTvWallStripNav(): TvWallStripNav {
    var savedFocused by rememberSaveable { mutableIntStateOf(-1) }
    var savedLeft by rememberSaveable { mutableIntStateOf(0) }
    val nav = remember { TvWallStripNav().apply { focusedIndex = savedFocused; leftIndex = savedLeft } }
    LaunchedEffect(nav) {
        snapshotFlow { nav.focusedIndex to nav.leftIndex }.collect { (f, l) ->
            savedFocused = f
            savedLeft = l
        }
    }
    return nav
}

/**
 * 一条横滑行的外壳: 行首换了就滚过去 (焦点离开时不打断正在跑的那段, 回来时补上), 从行外进来落回上次聚焦那张, 两头按左右吞掉.
 * [cell] 画第 index 格: enter 挂在格子的焦点目标上 (落点那格是它的请求器), 拿到焦点时调 onFocused, dim 是越过行首停靠线的压暗.
 */
@Composable
private fun TvWallStripRow(
    nav: TvWallStripNav,
    itemCount: Int,
    columns: Int,
    step: Dp,
    startPadding: Dp,
    endPadding: Dp,
    modifier: Modifier = Modifier,
    cell: @Composable (index: Int, enter: Modifier, onFocused: () -> Unit, dim: () -> Float) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = nav.leftIndex)
    val animatedScroll = tvAnimatedScroll()
    val animator = remember(animatedScroll) { TvScrollAnimator(animated = animatedScroll) }
    val touchInput = LocalTvTouchInputEnabled.current
    nav.touchInput = touchInput
    LaunchedEffect(animator, state) {
        snapshotFlow { nav.leftIndex to nav.hasFocus }
            .filter { (_, hasFocus) -> hasFocus && !nav.touchMode }
            .collectLatest { (left, _) -> animator.animateScrollToItem(state, left) }
    }
    // 触屏: 手指把行拖停了, 行首照它停下的位置记下 (行不动), 之后按键挪行从这里算
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling && nav.touchMode) nav.leftIndex = tvRowFirstFullIndex(state)
        }
    }
    val enterRequester = remember { FocusRequester() }
    val currentCount by rememberUpdatedState(itemCount)
    val currentColumns by rememberUpdatedState(columns)
    val fadeDistancePx = with(LocalDensity.current) { TV_CARD_FADE_DISTANCE.toPx() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvNoBringIntoViewSpec) {
        LazyRow(
            modifier
                .fillMaxWidth()
                // 触屏设备: 手指按下 / 按方向键分别切到触屏 / 电视的规矩 (见 TvWallStripNav.touchMode)
                .tvWallInputMode(touchInput) { nav.lastInputTouch = it }
                .onFocusChanged { nav.hasFocus = it.hasFocus }
                // 从行外进来落回上次聚焦那张. onEnter 只在焦点组节点上生效, 所以补 focusGroup()
                .focusProperties { onEnter = { runCatching { enterRequester.requestFocus() } } }
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // 两头都没有目标: 吞掉, 不交给系统按屏幕位置找
                    when (event.key) {
                        Key.DirectionLeft -> nav.focusedIndex == 0
                        Key.DirectionRight -> nav.focusedIndex == currentCount - 1
                        else -> false
                    }
                },
            state = state,
            horizontalArrangement = Arrangement.spacedBy(step),
            contentPadding = PaddingValues(start = startPadding, end = endPadding),
        ) {
            items(itemCount) { index ->
                val isEnterTarget = index == (nav.focusedIndex.takeIf { it in 0 until itemCount } ?: nav.leftIndex)
                Box(Modifier.zIndex(if (isEnterTarget) 1f else 0f)) {
                    cell(
                        index,
                        if (isEnterTarget) Modifier.focusRequester(enterRequester) else Modifier,
                        {
                            nav.focusedIndex = index
                            if (!nav.touchMode) nav.leftIndex = tvStripLeftIndex(nav.leftIndex, index, currentCount, currentColumns)
                        },
                        { tvPastAnchorDim(state, index, fadeDistancePx) },
                    )
                }
            }
        }
    }
}

/**
 * 海报墙的一条横滑行, 单独成行 (详情页关联条目、人物预览弹窗的作品), 参数见 TvNativePosterStrip. 一屏完整放得下几张按 tvPosterWallColumns 算,
 * 卡宽铺满 ([cardWidth] 给了就用它); 越过行首停靠线的卡压暗着出屏.
 */
@Composable
internal fun TvWallPosterStrip(
    cards: List<TvNativeCard?>,
    onClick: (index: Int) -> Unit,
    modifier: Modifier,
    startPadding: Dp,
    endPadding: Dp,
    onFocused: (index: Int) -> Unit,
    cardWidth: Dp?,
    onBind: (index: Int) -> Unit,
    holdFocusLookOnClick: Boolean,
) {
    val nav = rememberTvWallStripNav()
    // NSFW 设为模糊时按卡片的条目 id 打码: 调用方只管给卡片填 subjectId
    val nsfw = rememberNsfwPolicy()
    val shownCards = remember(cards, nsfw.snapshot) { cards.withNsfw(nsfw) }
    // 点卡 (导航出去) 时按住这张的聚焦态, 跨导航记住: 返回时这张第一帧就是放大的
    var heldIndex by rememberSaveable { mutableIntStateOf(-1) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnFocused by rememberUpdatedState(onFocused)
    val currentOnBind by rememberUpdatedState(onBind)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val available = (maxWidth - startPadding - endPadding).coerceAtLeast(0.dp)
        val columns: Int
        val width: Dp
        if (cardWidth != null) {
            width = cardWidth
            columns = ((available + TV_POSTER_WALL_COLUMN_SPACING) / (cardWidth + TV_POSTER_WALL_COLUMN_SPACING)).toInt().coerceAtLeast(1)
        } else {
            columns = with(density) { tvPosterWallColumns(available) }
            width = tvPosterWallCardWidth(available, columns).coerceAtLeast(0.dp)
        }
        // 给了卡宽时行尾留白按整数张补齐 (按需挪时恰好一张一张地挪)
        val end = if (cardWidth != null) {
            max(endPadding, maxWidth - startPadding - (width + TV_POSTER_WALL_COLUMN_SPACING) * columns + TV_POSTER_WALL_COLUMN_SPACING)
        } else {
            endPadding
        }
        val style = rememberTvWallCardStyle(width)
        TvWallStripRow(nav, shownCards.size, columns, TV_POSTER_WALL_COLUMN_SPACING, startPadding, end) { index, enter, focused, dim ->
            LaunchedEffect(index) { currentOnBind(index) }
            TvWallCard(
                shownCards.getOrNull(index),
                style,
                onClick = {
                    if (holdFocusLookOnClick) heldIndex = index
                    currentOnClick(index)
                },
                modifier = enter,
                onFocusChanged = { has ->
                    if (has) {
                        if (heldIndex != index) heldIndex = -1
                        focused()
                        currentOnFocused(index)
                    }
                },
                dimAlpha = dim,
                holdFocusLook = index == heldIndex,
                // 按触屏的规矩走时不画聚焦放大, 按下也不动焦点
                showFocusLook = !nav.touchMode,
                touchFocusOnPress = !nav.touchMode,
            )
        }
    }
}

/**
 * 详情页演职人员的圆头像横滑行 (参数见 TvDetailsPeopleStrip): 圆 + 姓名 + 一行副标题, 居中竖排. 聚焦时只放大圆 (绕圆心, 邻居不动) 并描一圈
 * 主题色细环, 两行字不放大、跟着圆的下缘下移; 副标题聚焦时提亮. [items] 为 null = 数据还没到: 一排占位格 (铺到屏幕右缘), 照样接得住焦点.
 */
@Composable
internal fun TvWallPeopleStrip(
    items: List<TvNativeMonogram?>?,
    onClick: (index: Int) -> Unit,
    onLongPress: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier,
) {
    val nav = rememberTvWallStripNav()
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnBind by rememberUpdatedState(onBind)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val step = TV_MONOGRAM_SIZE + TV_MONOGRAM_SPACING
        val available = (maxWidth - horizontalPadding * 2).coerceAtLeast(0.dp)
        val columns = ((available + TV_MONOGRAM_SPACING) / step).toInt().coerceAtLeast(1)
        // 行尾留白按整数格补齐
        val end = max(horizontalPadding, maxWidth - horizontalPadding - step * columns + TV_MONOGRAM_SPACING)
        // 占位铺到屏幕右缘: 最后一格被裁掉一截
        val placeholderCount = ((maxWidth - horizontalPadding) / step).toInt() + 1
        val count = items?.size ?: placeholderCount
        TvWallStripRow(nav, count, columns, TV_MONOGRAM_SPACING, horizontalPadding, end) { index, enter, focused, _ ->
            if (items != null) LaunchedEffect(index) { currentOnBind(index) }
            TvWallMonogramCell(
                items?.getOrNull(index),
                onClick = { if (items != null) currentOnClick(index) },
                onLongPress = { if (items != null) currentOnLongPress(index) },
                onFocused = {
                    // 占位格上的焦点不记: 跨导航恢复的是真数据里的位置
                    if (items != null) focused()
                },
                modifier = enter,
                touchMode = nav.touchMode,
            )
        }
    }
}

/**
 * 一格圆头像: 照片 (没有照片写姓名首字) / 「查看全部」的箭头 / 数据没到的占位. [touchMode] = 按触屏的规矩走 (见 TvWallStripNav.touchMode):
 * 不画聚焦效果, 按下不动焦点.
 */
@Composable
private fun TvWallMonogramCell(
    item: TvNativeMonogram?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    touchMode: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    // 弹簧而不是缓动: 连按方向键时新动画从当前值和当前速度接着走; 刚度同行滚动, 放大与滚动同相
    val progress by animateFloatAsState(
        if (focused && !touchMode) 1f else 0f,
        spring(dampingRatio = 0.8f, stiffness = TV_SCROLL_STIFFNESS),
    )
    val scale = 1f + (TV_MONOGRAM_FOCUS_SCALE - 1f) * progress
    val placeholder = tvPeoplePlaceholderColor()
    val content = LocalContentColor.current
    val typography = MaterialTheme.typography
    Column(
        modifier
            .width(TV_MONOGRAM_SIZE)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
            }
            .tvLongPressKey(onLongPress = onLongPress, onShortPress = onClick)
            .then(if (touchMode) Modifier else Modifier.tvTouchFocusOnTap())
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                // 示焦完全由圆的放大承担
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TV_MONOGRAM_TEXT_GAP),
    ) {
        Box(
            Modifier
                .size(TV_MONOGRAM_SIZE)
                // 只改绘制不改布局: 邻居不动, 行高也不变
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(
                    if (progress > 0f) {
                        Modifier.border(TV_MONOGRAM_FOCUS_RING, MaterialTheme.colorScheme.primary.copy(alpha = progress.coerceIn(0f, 1f)), CircleShape)
                    } else {
                        Modifier
                    },
                )
                .clip(CircleShape)
                .background(if (item is TvNativeMonogram.Person && item.imageUrl == null) MaterialTheme.colorScheme.surfaceContainerHighest else placeholder),
            contentAlignment = Alignment.Center,
        ) {
            when (item) {
                is TvNativeMonogram.Person -> if (item.imageUrl.isNullOrBlank()) {
                    Text(item.initials, style = typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                } else {
                    AsyncImage(
                        item.imageUrl,
                        contentDescription = item.name,
                        Modifier.size(TV_MONOGRAM_SIZE),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                    )
                }

                is TvNativeMonogram.ViewAll -> Box(
                    Modifier.size(TV_MONOGRAM_SIZE).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                null -> Unit
            }
        }
        // 两行字整体跟着圆的下缘下移 (圆绕中心放大, 下缘位移 = 半径 × (scale − 1)), 字号不变
        Column(
            Modifier.graphicsLayer { translationY = TV_MONOGRAM_SIZE.toPx() / 2f * (scale - 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TV_MONOGRAM_LINE_GAP),
        ) {
            if (item == null) {
                TvWallTextBar(TV_MONOGRAM_PLACEHOLDER_NAME_WIDTH, with(LocalDensity.current) { typography.titleSmall.lineHeight.toDp() })
                TvWallTextBar(TV_MONOGRAM_PLACEHOLDER_SUBTITLE_WIDTH, with(LocalDensity.current) { typography.bodySmall.lineHeight.toDp() })
            } else {
                val name = when (item) {
                    is TvNativeMonogram.Person -> item.name
                    is TvNativeMonogram.ViewAll -> stringResource(Lang.subject_details_view_all)
                }
                val subtitle = when (item) {
                    is TvNativeMonogram.Person -> item.subtitle
                    // 数得出剩余人数就写出来, 数不出留空不塌行
                    is TvNativeMonogram.ViewAll -> item.remaining?.takeIf { it > 0 }?.let { "+$it" }.orEmpty()
                }
                Text(
                    name,
                    style = typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    subtitle,
                    style = typography.bodySmall,
                    color = content.copy(alpha = if (focused) 1f else TV_MONOGRAM_SUBTITLE_ALPHA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 占位格里的一条字位. */
@Composable
private fun TvWallTextBar(width: Dp, lineHeight: Dp) {
    Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(width).height((lineHeight - 6.dp).coerceAtLeast(2.dp))
                .clip(RoundedCornerShape(TV_MONOGRAM_PLACEHOLDER_BAR_CORNER))
                .background(tvPeoplePlaceholderColor()),
        )
    }
}
