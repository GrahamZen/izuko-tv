/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_REDUCED_MARQUEE_ITERATIONS
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHost
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import kotlin.time.Duration.Companion.seconds

/** 面板的原生视图 ([TvSourcePanelView]): 建好交给 [controller], 视图的事件交回 [state]. */
@Composable
internal actual fun TvSourcePanelNativeView(
    state: TvSourcePanelState,
    controller: TvSourcePanelController,
    leaving: Boolean,
    resume: TvSourceResumePoint?,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val sketch = LocalSketch.current
    val currentOnClose by rememberUpdatedState(onClose)
    val style = rememberTvSourcePanelStyle()
    val currentState by rememberUpdatedState(state)
    TvNativeHost(
        factory = { context ->
            TvSourcePanelView(context, style, sketch).also { view ->
                panelViewWarmed = true
                controller.view = view
                view.listener = object : TvSourcePanelListener {
                    override fun onRailFocused(key: String) = currentState.onRailFocused(key)
                    override fun onSourceFocused(groupKey: String, sourceKey: String) = currentState.onSourceFocused(groupKey, sourceKey)
                    override fun onSelectionHeld(railKey: String, sourceKey: String?) = currentState.onSelectionHeld(railKey, sourceKey)
                    override fun onRightFocusChanged(inRight: Boolean) = currentState.onRightFocusChanged(inRight)
                    override fun onAction(action: TvSourceAction) = currentState.perform(action)
                    override fun onBackInRight(): Boolean = currentState.backInRight()
                    override fun onCloseRequested() = currentOnClose()
                }
                view.resumeAt(resume)
                view.requestEntryFocus()
            }
        },
        update = { view ->
            view.applyStyle(style)
            view.leaving = leaving
            controller.view = view
        },
        modifier = modifier,
    )
}

/** 面板的尺寸与配色 (见 [TvSourcePanelStyle]): 固定是播放器的黑白玻璃, 字号取主题的排版. */
@Composable
fun rememberTvSourcePanelStyle(): TvSourcePanelStyle {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val visualEffects = LocalThemeSettings.current.visualEffects
    val transitions = visualEffects.transitions
    val marqueeRepeat = when {
        !visualEffects.marquee -> 0
        visualEffects.ambient -> -1
        else -> TV_REDUCED_MARQUEE_ITERATIONS
    }
    val icons = TvSourceRowIcon.entries.associateWith { icon ->
        rememberTvNativeIcon(
            when (icon) {
                TvSourceRowIcon.None, TvSourceRowIcon.Source -> Icons.Rounded.Language
                TvSourceRowIcon.Playing -> Icons.Rounded.GraphicEq
                TvSourceRowIcon.Check -> Icons.Rounded.Check
                TvSourceRowIcon.Refresh -> Icons.Rounded.Refresh
                TvSourceRowIcon.Search -> Icons.Rounded.Search
                TvSourceRowIcon.Edit -> Icons.Rounded.Edit
                TvSourceRowIcon.Warning -> Icons.Rounded.ErrorOutline
                TvSourceRowIcon.Download -> Icons.Rounded.DownloadDone
                TvSourceRowIcon.Block -> Icons.Rounded.Block
                TvSourceRowIcon.Info -> Icons.Rounded.Info
                TvSourceRowIcon.Torrent -> Icons.Rounded.CloudDownload
                TvSourceRowIcon.Downloads -> Icons.Rounded.Download
                TvSourceRowIcon.Filter -> Icons.Rounded.FilterList
                TvSourceRowIcon.Local -> Icons.Rounded.Storage
                TvSourceRowIcon.Drive -> Icons.Rounded.FolderOpen
                TvSourceRowIcon.Link -> Icons.Rounded.Link
                TvSourceRowIcon.Settings -> Icons.Rounded.Settings
                TvSourceRowIcon.Pause -> Icons.Rounded.Pause
            },
            TV_SOURCE_ICON_SIZE,
        )
    }
    val badge = rememberTvSourceBadgeStyle()
    return remember(density, typography, transitions, marqueeRepeat, icons, badge) {
        with(density) {
            val white = Color.White
            val ink = Color(0xFF111111)
            TvSourcePanelStyle(
                // 与控制层的左右边距一致
                paddingStartPx = 48.dp.roundToPx(),
                paddingEndPx = 48.dp.roundToPx(),
                paddingTopPx = 24.dp.roundToPx(),
                paddingBottomPx = 24.dp.roundToPx(),
                headerGapPx = 10.dp.roundToPx(),
                railGapPx = 12.dp.roundToPx(),
                // 同新番时间表的日期胶囊
                railPillHeightPx = 34.dp.roundToPx(),
                railPillGapPx = 8.dp.roundToPx(),
                fadeLengthPx = 32.dp.roundToPx(),
                rowGapPx = 5.dp.roundToPx(),
                dividerGapPx = 12.dp.roundToPx(),
                dividerColor = white.copy(alpha = 0.12f).toArgb(),
                cornerPx = 10.dp.toPx(),
                rowPaddingHPx = 12.dp.roundToPx(),
                iconSizePx = TV_SOURCE_ICON_SIZE.roundToPx(),
                iconGapPx = 10.dp.roundToPx(),
                trailingGapPx = 8.dp.roundToPx(),
                spinnerSizePx = 14.dp.roundToPx(),
                lineRowHeightPx = 40.dp.roundToPx(),
                fileRowPaddingVPx = 8.dp.roundToPx(),
                metaGapPx = 16.dp.roundToPx(),
                metaSplitWidthPx = 150.dp.roundToPx(),
                branchMinWidthPx = 120.dp.roundToPx(),
                // 按各个源的名字定宽: 不窄于旧竖版的左栏, 最宽到上限
                columnWidthPx = 280.dp.roundToPx(),
                columnMinWidthPx = 184.dp.roundToPx(),
                columnRowHeightPx = 38.dp.roundToPx(),
                columnGapPx = 12.dp.roundToPx(),
                optionRowHeightPx = 38.dp.roundToPx(),
                cellHeightPx = 38.dp.roundToPx(),
                statusPaddingVPx = 8.dp.roundToPx(),
                pillHeightPx = 28.dp.roundToPx(),
                pillPaddingHPx = 12.dp.roundToPx(),
                pillGapPx = 6.dp.roundToPx(),
                pillsBottomGapPx = 10.dp.roundToPx(),
                drillTitleBottomGapPx = 8.dp.roundToPx(),
                focusBleedPx = 8.dp.roundToPx(),
                status = typography.bodySmall.toTvNativeTextStyle(density, white.copy(alpha = 0.8f)),
                drillTitle = typography.titleSmall.toTvNativeTextStyle(density, white.copy(alpha = 0.8f)),
                railTitle = typography.bodyMedium.toTvNativeTextStyle(density, white),
                rowTitle = typography.bodyMedium.toTvNativeTextStyle(density, white),
                rowMeta = typography.bodySmall.toTvNativeTextStyle(density, white.copy(alpha = 0.62f)),
                trailing = typography.labelMedium.toTvNativeTextStyle(density, white.copy(alpha = 0.62f)),
                pill = typography.labelLarge.toTvNativeTextStyle(density, white),
                cell = typography.labelLarge.copy(fontWeight = FontWeight.Medium).toTvNativeTextStyle(density, white),
                // 同控制层的胶囊: 半透明玻璃白字, 聚焦近白实底黑字 + 放大. 背后没有暗底, 未聚焦的底偏黑一点 (亮画面上字才压得住)
                idleBg = Color.Black.copy(alpha = 0.35f).toArgb(),
                activeBg = Color.Black.copy(alpha = 0.6f).toArgb(),
                activeBorderPx = 1.5.dp.toPx(),
                activeBorderColor = white.copy(alpha = 0.75f).toArgb(),
                focusedBg = white.copy(alpha = 0.96f).toArgb(),
                text = white.toArgb(),
                textSecondary = white.copy(alpha = 0.62f).toArgb(),
                focusedText = ink.toArgb(),
                focusedTextSecondary = ink.copy(alpha = 0.66f).toArgb(),
                error = Color(0xFFFF8A80).toArgb(),
                focusedError = Color(0xFFB3261E).toArgb(),
                dimmedAlpha = 0.5f,
                chipPaddingHPx = 12.dp.roundToPx(),
                chipPaddingVPx = 6.dp.roundToPx(),
                branchFadeMillis = 160,
                focusScale = 1.03f,
                marqueeRepeat = marqueeRepeat,
                focusInMillis = 150,
                focusOutMillis = 90,
                animated = transitions,
                icons = icons,
                badge = badge,
            )
        }
    }
}

/** 行首图标的边长. */
private val TV_SOURCE_ICON_SIZE = 16.dp

/** 进程里建过面板视图了 (类加载、量字宽等第一次的开销已经付过). 只在主线程上读写. */
private var panelViewWarmed = false

/**
 * 进程里第一次建面板的原生视图要 ~0.5 秒 (类加载、量字宽、第一次排版; Shield 实测), 都在主线程上.
 * 在播放页进来一会儿、[canPrewarmNow] 为 true (纯视频态, 画面与弹幕各有自己的表面, 主线程忙一下看不出来) 时
 * 先建一个按屏幕大小排一遍再丢掉, 第一次开面板就接近之后几次. 已经开过面板的不再做.
 */
@Composable
internal actual fun PrewarmTvSourcePanelView(canPrewarmNow: () -> Boolean) {
    val context = LocalContext.current
    val style = rememberTvSourcePanelStyle()
    val sketch = LocalSketch.current
    val currentCanPrewarm by rememberUpdatedState(canPrewarmNow)
    LaunchedEffect(Unit) {
        if (panelViewWarmed) return@LaunchedEffect
        delay(PANEL_PREWARM_DELAY)
        while (!currentCanPrewarm()) delay(1.seconds)
        if (panelViewWarmed) return@LaunchedEffect
        panelViewWarmed = true
        val metrics = context.resources.displayMetrics
        TvSourcePanelView(context, style, sketch).run {
            measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }
}

/** 进播放页后等这么久再预建: 避开找资源、开播那一阵. */
private val PANEL_PREWARM_DELAY = 8.seconds
