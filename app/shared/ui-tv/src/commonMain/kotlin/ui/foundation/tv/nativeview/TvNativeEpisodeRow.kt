/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalImageCrossfade
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_FOCUS_RING_COUNTDOWN_TRACK_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_REDUCED_MARQUEE_ITERATIONS
import me.him188.ani.app.ui.foundation.tv.TvFocusRing
import me.him188.ani.app.ui.foundation.tv.rememberTvScrollActivityReporter
import me.him188.ani.app.ui.subject.details.sections.EPISODE_CARD_CORNER
import me.him188.ani.app.ui.subject.details.sections.EPISODE_CARD_PRESS_SCALE
import me.him188.ani.app.ui.subject.details.sections.EPISODE_CARD_TEXT_GAP
import me.him188.ani.app.ui.subject.details.sections.EPISODE_DIM_FADE_MILLIS
import me.him188.ani.app.ui.subject.details.sections.EPISODE_IMAGE_NAME_ALPHA
import me.him188.ani.app.ui.subject.details.sections.EPISODE_IMAGE_TEXT_BOTTOM_PADDING
import me.him188.ani.app.ui.subject.details.sections.EPISODE_IMAGE_TEXT_SIDE_PADDING
import me.him188.ani.app.ui.subject.details.sections.EPISODE_IMAGE_TRACK_ALPHA
import me.him188.ani.app.ui.subject.details.sections.EPISODE_LEADING_ICON_NUDGE
import me.him188.ani.app.ui.subject.details.sections.EPISODE_PAST_CARD_DIM_ALPHA
import me.him188.ani.app.ui.subject.details.sections.EPISODE_PLAYING_ICON_SIZE
import me.him188.ani.app.ui.subject.details.sections.EPISODE_PLAY_ICON_SIZE
import me.him188.ani.app.ui.subject.details.sections.EPISODE_PROGRESS_BAR_HEIGHT
import me.him188.ani.app.ui.subject.details.sections.EPISODE_PROGRESS_BAR_SIDE_INSET
import me.him188.ani.app.ui.subject.details.sections.EPISODE_PROGRESS_BOTTOM_INSET
import me.him188.ani.app.ui.subject.details.sections.EPISODE_SORT_CAP_HEIGHT_FRACTION
import me.him188.ani.app.ui.subject.details.sections.EPISODE_STILL_SCRIM_ALPHA
import me.him188.ani.app.ui.subject.details.sections.EPISODE_STILL_SCRIM_START
import me.him188.ani.app.ui.subject.details.sections.EPISODE_TEXT_BOTTOM_PADDING_NO_BAR
import me.him188.ani.app.ui.subject.details.sections.EPISODE_TEXT_CARD_TOP_PADDING
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeRowController
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeRowSpec
import me.him188.ani.app.ui.subject.details.sections.PLAYING_ICON_INK_LEFT_FRACTION
import me.him188.ani.app.ui.subject.details.sections.PLAY_ICON_INK_LEFT_FRACTION
import me.him188.ani.app.ui.subject.details.sections.episodeStillImageUrl
import me.him188.ani.app.ui.subject.details.sections.focusEpisodeCardColors
import kotlin.math.ceil
import kotlin.math.min

/**
 * 选集轮播 (FocusEpisodeCarousel) 的原生卡片行, 当 rowContent 传: 详情页选集轮播与播放器选集条都用它. 轮播 (大脑) 管数据、落点、
 * 送焦通道、信息行与长按弹窗, 这里只把 [spec] 接到原生的 [TvNativeEpisodeRowLayout] 上: 卡片数据与配色在重组时交过去, 压暗分界与倒计时
 * 进度在自己的效应里读 (轮播 body 不订阅), 焦点 / 点击 / 滚动经监听报回去.
 *
 * 尺寸 (卡宽高、间距) 只在建行时读: 两处调用方的卡片尺寸都不随状态变. 配色跟主题换.
 */
@Composable
fun TvNativeEpisodeRow(spec: FocusEpisodeRowSpec) {
    val sketch = LocalSketch.current
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    val density = LocalDensity.current
    val current = rememberUpdatedState(spec)
    val currentReporter = rememberUpdatedState(rememberTvScrollActivityReporter())
    val style = rememberTvNativeEpisodeStyle(spec.cellWidth, spec.cellHeight, spec.cellSpacing, spec.monochrome, spec.glass)
    val startPx = with(density) { spec.horizontalPadding.roundToPx() }
    val bleedPx = with(density) { TV_NATIVE_EPISODE_ROW_BLEED.roundToPx() }
    val cards = remember(spec.episodes, spec.episodeStills, spec.playProgress, spec.currentEpisodeId) {
        spec.episodes.map { item ->
            val watched = item.isDoneOrDropped
            TvNativeEpisodeCard(
                id = item.episodeId,
                sort = item.sort.toString(),
                name = item.nameCn.ifBlank { item.name },
                stillUrl = spec.episodeStills[item.episodeId]?.let(::episodeStillImageUrl),
                playing = item.episodeId == spec.currentEpisodeId,
                watched = watched,
                // 已看的集固定满条 (同 Compose 版)
                progress = if (watched) 1f else spec.playProgress[item.episodeId],
            )
        }
    }
    val target = remember { TvNativeEpisodeRowTarget() }
    TvNativeRowHost(
        factory = { context ->
            TvNativeEpisodeRowLayout(context, style, sketch, startPx, bleedPx).also { layout ->
                target.layout = layout
                // 这几处在原生回调 / 建行时读轮播的状态, 不登记快照观察 (同 TvNativeFocusGate: 会在 Compose 取可聚焦性时被调到)
                layout.row.entryIndex = { Snapshot.withoutReadObservation { current.value.entryIndex() } }
                // 跨导航恢复的压暗分界: 第一次绑定就按它画, 不动画
                layout.setDimPivot(Snapshot.withoutReadObservation { current.value.dimPivotIndex() }, animate = false)
                layout.setCardAlpha(current.value.cardAlpha, animate = false)
                layout.listener = object : TvNativeEpisodeRowListener {
                    override fun onFocused(index: Int) = current.value.onCardFocused(index)

                    override fun onFocusLost(index: Int) = current.value.onCardFocusLost(index)

                    override fun onClick(index: Int) = current.value.onClick(index)

                    override fun onLongPress(index: Int) = current.value.onLongClick(index)

                    override fun onScrollingChanged(scrolling: Boolean) {
                        currentReporter.value?.setScrolling(scrolling)
                    }
                }
            }
        },
        update = { layout ->
            layout.applyStyle(style)
            layout.setStart(startPx)
            layout.row.animatedScroll = animatedScroll
            layout.row.cards.longPressEnabled = spec.longPressEnabled
            // 数据第一次到: 选中落点那张, 第一次布局就排在停靠线上 (之后落点的变化由轮播经 controller 滚)
            val first = layout.row.cards.itemCount == 0 && cards.isNotEmpty()
            layout.row.cards.submit(cards)
            if (first) layout.row.selectCard(Snapshot.withoutReadObservation { spec.entryIndex() })
            layout.setCardAlpha(spec.cardAlpha, animate = true)
            layout.setCountdown(spec.anchorCountdown != null)
            spec.controller.target = target
        },
        height = spec.cellHeight,
        bleedVertical = TV_NATIVE_EPISODE_ROW_BLEED,
        modifier = spec.modifier,
    )
    val controller = spec.controller
    DisposableEffect(controller) {
        onDispose { if (controller.target === target) controller.target = null }
    }
    // 压暗分界随轮播的状态走 (焦点换卡时行已当场挪过, 这里同值不动; 倒计时态落点换人时由这里挪)
    LaunchedEffect(target) {
        snapshotFlow { current.value.dimPivotIndex() }.collect { target.layout?.setDimPivot(it, animate = true) }
    }
    // 倒计时环的进度 (每 100ms 变一次, 只重画框)
    val countdown = spec.anchorCountdown
    LaunchedEffect(countdown) {
        if (countdown == null) return@LaunchedEffect
        snapshotFlow { countdown() }.collect { target.layout?.setCountdownProgress(it) }
    }
}

/** 轮播下给原生行的指令 (见 FocusEpisodeRowController). */
private class TvNativeEpisodeRowTarget : FocusEpisodeRowController.Target {
    var layout: TvNativeEpisodeRowLayout? = null

    override fun scrollTo(index: Int, animated: Boolean) {
        layout?.row?.scrollToCard(index, animated)
    }

    override fun focusCard(index: Int): Boolean = layout?.row?.focusCardIfLaidOut(index) == true

    override fun isOnScreen(index: Int): Boolean = layout?.row?.isCardOnScreen(index) == true
}

/** 行视图上下多出的一截: 固定聚焦框伸出卡外的那一圈 ([TvFocusRing.Gap]) 画在里面. */
private val TV_NATIVE_EPISODE_ROW_BLEED = 8.dp

/** 选集卡随集状态变的颜色 (ARGB), 取自 focusEpisodeCardColors. */
@Immutable
data class TvNativeEpisodeColors(
    val container: Int,
    val sort: Int,
    val name: Int,
    val progress: Int,
    val track: Int,
)

/**
 * 原生选集行的尺寸 (px) 与配色, 由 [rememberTvNativeEpisodeStyle] 按主题与界面缩放算好. 取值与 Compose 版 FocusEpisodeCard /
 * FocusEpisodeAnchorRing 同一组常量 (ui-subject 的 FocusEpisodesSection.kt), 各量的来历见那里.
 *
 * @property palette 四种状态的配色: [普通, 看过, 在播, 在播且看过], 见 [colors].
 * @property playInkInsetPx 没有行首图标时补的墨迹差 (一个播放三角的左内白); [playingInkInsetPx] 是声浪图标那一档.
 * @property iconBaselineOffsetPx 行首图标中心在集号基线上方多少 (半个 cap height).
 */
@Immutable
data class TvNativeEpisodeStyle(
    val cardWidthPx: Int,
    val cardHeightPx: Int,
    val spacingPx: Int,
    val cornerPx: Float,
    val palette: List<TvNativeEpisodeColors>,
    val imageNameColor: Int,
    val imageTrackColor: Int,
    val scrimStart: Float,
    val scrimColor: Int,
    val sort: TvNativeTextStyle,
    val name: TvNativeTextStyle,
    val textSidePx: Int,
    val textGapPx: Int,
    val textTopPx: Int,
    val textBottomWithBarPx: Int,
    val textBottomNoBarPx: Int,
    val playIcon: Bitmap,
    val playIconPx: Int,
    val playingIcon: Bitmap,
    val playingIconPx: Int,
    val playInkInsetPx: Int,
    val playingInkInsetPx: Int,
    val iconBaselineOffsetPx: Int,
    val barHeightPx: Int,
    val barSidePx: Int,
    val barBottomPx: Int,
    val pastDimAlpha: Float,
    val dimMillis: Long,
    val pressScale: Float,
    /** 聚焦框相对卡片外框向外探出的量, 框的尺寸、线宽、圆角, 描边渐变的两端 (黑白态两端都是白), 倒计时环底轨的不透明度. */
    val ringOutsetPx: Int,
    val ringWidthPx: Int,
    val ringHeightPx: Int,
    val ringStrokePx: Float,
    val ringCornerPx: Float,
    val ringStartColor: Int,
    val ringEndColor: Int,
    val ringCountdownTrackAlpha: Float,
    /** 集名跑马灯的圈数: 0 = 不跑 (流畅档), -1 = 一直跑. */
    val marqueeRepeat: Int,
    val crossfade: Boolean,
) {
    fun colors(playing: Boolean, watched: Boolean): TvNativeEpisodeColors =
        palette[(if (playing) 2 else 0) + (if (watched) 1 else 0)]
}

/** [TvNativeEpisodeStyle]. [monochrome] / [glass] 见 FocusEpisodeCard 的同名参数. */
@Composable
fun rememberTvNativeEpisodeStyle(
    cellWidth: Dp,
    cellHeight: Dp,
    cellSpacing: Dp,
    monochrome: Boolean,
    glass: Boolean,
): TvNativeEpisodeStyle {
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val visualEffects = LocalThemeSettings.current.visualEffects
    val crossfade = LocalImageCrossfade.current
    val palette = listOf(
        focusEpisodeCardColors(isPlaying = false, isWatched = false, monochrome = monochrome, glass = glass),
        focusEpisodeCardColors(isPlaying = false, isWatched = true, monochrome = monochrome, glass = glass),
        focusEpisodeCardColors(isPlaying = true, isWatched = false, monochrome = monochrome, glass = glass),
        focusEpisodeCardColors(isPlaying = true, isWatched = true, monochrome = monochrome, glass = glass),
    ).map {
        TvNativeEpisodeColors(it.container.toArgb(), it.sort.toArgb(), it.name.toArgb(), it.progress.toArgb(), it.track.toArgb())
    }
    val playIconSize = with(density) { EPISODE_PLAY_ICON_SIZE.toDp() }
    val playingIconSize = with(density) { EPISODE_PLAYING_ICON_SIZE.toDp() }
    val playIcon = rememberTvNativeIcon(Icons.Rounded.PlayArrow, playIconSize)
    val playingIcon = rememberTvNativeIcon(Icons.Rounded.GraphicEq, playingIconSize)
    return remember(
        density, colorScheme, typography, visualEffects, crossfade, palette, playIcon, playingIcon,
        cellWidth, cellHeight, cellSpacing, monochrome,
    ) {
        with(density) {
            val ringWidthPx = (cellWidth + TvFocusRing.Gap * 2).roundToPx()
            val ringHeightPx = (cellHeight + TvFocusRing.Gap * 2).roundToPx()
            val playInkInset = playIconSize * PLAY_ICON_INK_LEFT_FRACTION
            TvNativeEpisodeStyle(
                cardWidthPx = cellWidth.roundToPx(),
                cardHeightPx = cellHeight.roundToPx(),
                spacingPx = cellSpacing.roundToPx(),
                cornerPx = EPISODE_CARD_CORNER.toPx(),
                palette = palette,
                imageNameColor = Color.White.copy(alpha = EPISODE_IMAGE_NAME_ALPHA).toArgb(),
                imageTrackColor = Color.White.copy(alpha = EPISODE_IMAGE_TRACK_ALPHA).toArgb(),
                scrimStart = EPISODE_STILL_SCRIM_START,
                scrimColor = Color.Black.copy(alpha = EPISODE_STILL_SCRIM_ALPHA).toArgb(),
                sort = typography.titleSmall.toTvNativeTextStyle(density, Color.White),
                name = typography.bodySmall.toTvNativeTextStyle(density, Color.White),
                textSidePx = EPISODE_IMAGE_TEXT_SIDE_PADDING.roundToPx(),
                textGapPx = EPISODE_CARD_TEXT_GAP.roundToPx(),
                textTopPx = EPISODE_TEXT_CARD_TOP_PADDING.roundToPx(),
                textBottomWithBarPx = EPISODE_IMAGE_TEXT_BOTTOM_PADDING.roundToPx(),
                textBottomNoBarPx = EPISODE_TEXT_BOTTOM_PADDING_NO_BAR.roundToPx(),
                playIcon = playIcon,
                playIconPx = playIconSize.roundToPx(),
                playingIcon = playingIcon,
                playingIconPx = playingIconSize.roundToPx(),
                playInkInsetPx = playInkInset.roundToPx(),
                playingInkInsetPx = (playInkInset - playingIconSize * PLAYING_ICON_INK_LEFT_FRACTION).coerceAtLeast(0.dp).roundToPx(),
                iconBaselineOffsetPx = (typography.titleSmall.fontSize.toDp() * EPISODE_SORT_CAP_HEIGHT_FRACTION / 2 +
                        EPISODE_LEADING_ICON_NUDGE).roundToPx(),
                barHeightPx = EPISODE_PROGRESS_BAR_HEIGHT.roundToPx(),
                barSidePx = EPISODE_PROGRESS_BAR_SIDE_INSET.roundToPx(),
                barBottomPx = EPISODE_PROGRESS_BOTTOM_INSET.roundToPx(),
                pastDimAlpha = EPISODE_PAST_CARD_DIM_ALPHA,
                dimMillis = EPISODE_DIM_FADE_MILLIS.toLong(),
                pressScale = EPISODE_CARD_PRESS_SCALE,
                ringOutsetPx = TvFocusRing.Gap.roundToPx(),
                ringWidthPx = ringWidthPx,
                ringHeightPx = ringHeightPx,
                // 线宽同 tvFocusRingBorder: 向上取整到整像素, 不超过短边的一半
                ringStrokePx = min(ceil(TvFocusRing.Width.toPx()), ceil(min(ringWidthPx, ringHeightPx) / 2f)),
                ringCornerPx = (EPISODE_CARD_CORNER + TvFocusRing.Gap).toPx(),
                // 黑白态纯白, 其余主题动态色渐变 (TvFocusRing.gradientBrush)
                ringStartColor = (if (monochrome) Color.White else colorScheme.primary).toArgb(),
                ringEndColor = (if (monochrome) Color.White else colorScheme.secondary).toArgb(),
                ringCountdownTrackAlpha = TV_FOCUS_RING_COUNTDOWN_TRACK_ALPHA,
                marqueeRepeat = when {
                    !visualEffects.marquee -> 0
                    visualEffects.ambient -> -1
                    else -> TV_REDUCED_MARQUEE_ITERATIONS
                },
                crossfade = crossfade,
            )
        }
    }
}
