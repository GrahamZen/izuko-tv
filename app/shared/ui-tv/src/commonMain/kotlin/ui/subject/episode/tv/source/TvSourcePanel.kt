/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.datetime.TimeZone
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.AniOutlinedTextField
import me.him188.ani.app.ui.foundation.tv.TV_REDUCED_MARQUEE_ITERATIONS
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHost
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_source_episodes_whole_season
import me.him188.ani.app.ui.lang.tv_source_episodes_season
import me.him188.ani.app.ui.lang.tv_source_web_no_exact
import me.him188.ani.app.ui.lang.tv_source_rail_others
import me.him188.ani.app.ui.lang.tv_source_hide_others
import me.him188.ani.app.ui.lang.tv_source_show_others
import me.him188.ani.app.ui.lang.tv_source_reason_episode_unknown
import me.him188.ani.app.ui.lang.tv_source_reason_fuzzy_title
import me.him188.ani.app.ui.lang.tv_source_downloads_empty_disk_cache
import me.him188.ani.app.ui.lang.tv_source_downloads_empty
import me.him188.ani.app.ui.lang.tv_source_download_caching
import me.him188.ani.app.ui.lang.tv_source_downloads_open
import me.him188.ani.app.ui.lang.tv_source_detail_date
import me.him188.ani.app.ui.lang.tv_source_detail_episode
import me.him188.ani.app.ui.lang.tv_source_detail_episodes
import me.him188.ani.app.ui.lang.tv_source_detail_error
import me.him188.ani.app.ui.lang.tv_source_detail_kind
import me.him188.ani.app.ui.lang.tv_source_detail_lines
import me.him188.ani.app.ui.lang.tv_source_detail_link
import me.him188.ani.app.ui.lang.tv_source_detail_nav_hint
import me.him188.ani.app.ui.lang.tv_source_detail_open
import me.him188.ani.app.ui.lang.tv_source_detail_pick
import me.him188.ani.app.ui.lang.tv_source_detail_size
import me.him188.ani.app.ui.lang.tv_source_detail_source
import me.him188.ani.app.ui.lang.tv_source_detail_sources
import me.him188.ani.app.ui.lang.tv_source_detail_status
import me.him188.ani.app.ui.lang.tv_source_kind_bt
import me.him188.ani.app.ui.lang.tv_source_kind_cache
import me.him188.ani.app.ui.lang.tv_source_kind_web
import me.him188.ani.app.ui.lang.tv_source_status_done
import me.him188.ani.app.ui.lang.tv_source_status_searching
import me.him188.ani.app.ui.lang.media_selector_bt_reason_below_preference
import me.him188.ani.app.ui.lang.media_selector_bt_reason_episode_mismatch
import me.him188.ani.app.ui.lang.media_selector_filter_alliance
import me.him188.ani.app.ui.lang.media_selector_filter_resolution
import me.him188.ani.app.ui.lang.media_selector_filter_subtitle
import me.him188.ani.app.ui.lang.media_selector_full_search
import me.him188.ani.app.ui.lang.media_selector_item_cache_not_ready
import me.him188.ani.app.ui.lang.media_selector_item_excluded_alliance
import me.him188.ani.app.ui.lang.media_selector_item_no_subtitle
import me.him188.ani.app.ui.lang.media_selector_item_season_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_single_episode_resource
import me.him188.ani.app.ui.lang.media_selector_item_subject_title_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_unsupported_playback
import me.him188.ani.app.ui.lang.subject_episode_cache
import me.him188.ani.app.ui.lang.subject_episode_cached
import me.him188.ani.app.ui.lang.tv_source_all
import me.him188.ani.app.ui.lang.tv_source_all_episodes
import me.him188.ani.app.ui.lang.tv_source_bt
import me.him188.ani.app.ui.lang.tv_source_bt_empty
import me.him188.ani.app.ui.lang.tv_source_bt_loading
import me.him188.ani.app.ui.lang.tv_source_cancel
import me.him188.ani.app.ui.lang.tv_source_captcha_action
import me.him188.ani.app.ui.lang.tv_source_captcha_hint
import me.him188.ani.app.ui.lang.tv_source_captcha_required
import me.him188.ani.app.ui.lang.tv_source_captcha_unsupported
import me.him188.ani.app.ui.lang.tv_source_confirm
import me.him188.ani.app.ui.lang.tv_source_disabled
import me.him188.ani.app.ui.lang.tv_source_episode_filter
import me.him188.ani.app.ui.lang.tv_source_failed
import me.him188.ani.app.ui.lang.tv_source_failed_state
import me.him188.ani.app.ui.lang.tv_source_hide_excluded
import me.him188.ani.app.ui.lang.tv_source_keywords
import me.him188.ani.app.ui.lang.tv_source_line
import me.him188.ani.app.ui.lang.tv_source_loading
import me.him188.ani.app.ui.lang.tv_source_manual
import me.him188.ani.app.ui.lang.tv_source_manual_back
import me.him188.ani.app.ui.lang.tv_source_manual_channel
import me.him188.ani.app.ui.lang.tv_source_manual_empty
import me.him188.ani.app.ui.lang.tv_source_manual_failed
import me.him188.ani.app.ui.lang.tv_source_manual_keyword
import me.him188.ani.app.ui.lang.tv_source_manual_loading_episodes
import me.him188.ani.app.ui.lang.tv_source_manual_no_episodes
import me.him188.ani.app.ui.lang.tv_source_manual_no_sources
import me.him188.ani.app.ui.lang.tv_source_manual_remember
import me.him188.ani.app.ui.lang.tv_source_manual_searching
import me.him188.ani.app.ui.lang.tv_source_manual_source
import me.him188.ani.app.ui.lang.tv_source_menu_cache
import me.him188.ani.app.ui.lang.tv_source_menu_exclude
import me.him188.ani.app.ui.lang.tv_source_no_result
import me.him188.ani.app.ui.lang.tv_source_off
import me.him188.ani.app.ui.lang.tv_source_on
import me.him188.ani.app.ui.lang.tv_source_pack
import me.him188.ani.app.ui.lang.tv_source_playing
import me.him188.ani.app.ui.lang.tv_source_rate_limited
import me.him188.ani.app.ui.lang.tv_source_rate_limited_expired
import me.him188.ani.app.ui.lang.tv_source_rate_limited_expired_hint
import me.him188.ani.app.ui.lang.tv_source_rate_limited_hint
import me.him188.ani.app.ui.lang.tv_source_refresh
import me.him188.ani.app.ui.lang.tv_source_resolving_captcha
import me.him188.ani.app.ui.lang.tv_source_retry
import me.him188.ani.app.ui.lang.tv_source_retry_hint
import me.him188.ani.app.ui.lang.tv_source_searched
import me.him188.ani.app.ui.lang.tv_source_searching
import me.him188.ani.app.ui.lang.tv_source_show_excluded
import me.him188.ani.app.ui.lang.tv_source_source
import me.him188.ani.app.ui.lang.tv_source_sources_count
import me.him188.ani.app.ui.media.rememberMediaDetailsStrings
import org.jetbrains.compose.resources.stringResource

/**
 * 面板视图的把手: 播放器根路由经它问「面板里有焦点吗」、把返回键交给面板、把焦点送进面板. 视图建出来之前全是空操作.
 */
@Stable
class TvSourcePanelController {
    internal var view: TvSourcePanelView? = null

    val hasFocus: Boolean get() = view?.hasPanelFocus() == true

    /** 返回键. true = 面板自己处理了 (退出进去的那一层); false = 该关面板了 (见 [TvSourcePanelView.handleBack]). */
    fun back(): Boolean = view?.handleBack() == true

    /**
     * 上次按返回隐藏时焦点停的位置 (见 [rememberPosition]): 下次打开落回那里. 用一次就清 ([takeResume]);
     * 别的方式关的 (左栏按左、选完自动关) 不记, 下次照常落在正在播放的源上.
     */
    private var resume: TvSourceResume? = null

    /** 关面板前记下焦点停在哪 (左栏那一项, 或右栏那一行). [episodeId] = 此刻播的那一集, 换了集就不落回去. */
    fun rememberPosition(episodeId: Int?) {
        resume = view?.focusPosition()?.let { (railKey, rowId) -> TvSourceResume(railKey, rowId, episodeId) }
    }

    fun forgetPosition() {
        resume = null
    }

    /** 取走记下的位置 (还是同一集才算). */
    fun takeResume(episodeId: Int?): TvSourceResume? = resume.also { resume = null }?.takeIf { it.episodeId == episodeId }

    fun requestEntryFocus() {
        view?.requestEntryFocus()
    }

    /** 焦点送到一行 (详情弹窗换条 / 关掉时), 见 [TvSourcePanelView.focusRow]. */
    fun focusRow(railKey: String?, id: String, inRail: Boolean) {
        view?.focusRow(railKey, id, inRail)
    }
}

/** 面板隐藏时焦点停的位置: 左栏 [railKey] 那一项 ([rowId] 为 null), 或它右栏里的 [rowId] 那一行; 当时播的是 [episodeId]. */
data class TvSourceResume(val railKey: String, val rowId: String?, val episodeId: Int?)

/**
 * 选源面板 (原生视图, 见 [TvSourcePanelView]): 把 [state] 拼好的内容交给视图, 视图的事件交回 [state]. 内容的变化不经过重组 ——
 * 搜索途中上游状态一秒变好几次, 走重组就是每次把整棵 AndroidView 的 update 跑一遍.
 *
 * @param leaving 面板正在退场 (见 [TvSourcePanelView.leaving]).
 * @param onClose 要关面板: 在左栏按了左键, 或触屏点了面板外面的画面.
 * @param resumeRow 打开时落回的右栏那一行 (左栏项, 行 id), 见 [TvSourcePanelView.resumeRow].
 */
@Composable
fun TvSourcePanel(
    state: TvSourcePanelState,
    controller: TvSourcePanelController,
    leaving: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    resumeRow: Pair<String, String>? = null,
) {
    val sketch = LocalSketch.current
    val currentOnClose by rememberUpdatedState(onClose)
    val style = rememberTvSourcePanelStyle()
    val strings = rememberTvSourceStrings()
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(state, strings) { state.setStrings(strings) }
    TvNativeHost(
        factory = { context ->
            TvSourcePanelView(context, style, sketch).also { view ->
                controller.view = view
                view.listener = object : TvSourcePanelListener {
                    override fun onRailFocused(key: String) = currentState.onRailFocused(key)
                    override fun onRightFocusChanged(inRight: Boolean) = currentState.onRightFocusChanged(inRight)
                    override fun onAction(action: TvSourceAction) = currentState.perform(action)
                    override fun onBackInRight(): Boolean = currentState.backInRight()
                    override fun onCloseRequested() = currentOnClose()
                }
                view.resumeRow = resumeRow
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
    LaunchedEffect(state) {
        state.content.filterNotNull().collect { controller.view?.submit(it) }
    }
    val detailsTarget by state.detailsTarget.collectAsState()
    detailsTarget?.let { TvSourceDetailsHost(state, it, controller) }
}

/**
 * 输入框弹窗 (改搜索词 / 手动查找的关键词). [multiline] 时一行一个名字, 提交时去掉空行.
 * [onReset] 非 null 时多一颗「恢复 Bangumi 名称」.
 */
@Composable
fun TvSourceTextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    multiline: Boolean = false,
    hint: String? = null,
    resetLabel: String? = null,
    onReset: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    AniAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AniOutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !multiline,
                    minLines = if (multiline) 3 else 1,
                    maxLines = if (multiline) 6 else 1,
                    supportingText = hint?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().tvWindowInitialFocus(),
                )
                if (onReset != null && resetLabel != null) {
                    AniTextButton(onClick = onReset) { Text(resetLabel) }
                }
            }
        },
        confirmButton = {
            AniTextButton(onClick = { onConfirm(text) }) { Text(stringResource(Lang.tv_source_confirm)) }
        },
        dismissButton = {
            AniTextButton(onClick = onDismiss) { Text(stringResource(Lang.tv_source_cancel)) }
        },
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
    // 右栏宽度的下限按「完成验证」那一行量 (见 TvSourcePanelStyle.rightSampleTitle)
    val rightSampleTitle = stringResource(Lang.tv_source_captcha_action)
    val rightSampleMeta = stringResource(Lang.tv_source_captcha_hint)
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
            },
            TV_SOURCE_ICON_SIZE,
        )
    }
    return remember(density, typography, transitions, marqueeRepeat, icons, rightSampleTitle, rightSampleMeta) {
        with(density) {
            val white = Color.White
            val ink = Color(0xFF111111)
            val rowTitle = typography.bodyMedium.toTvNativeTextStyle(density, white)
            val rowMeta = typography.bodySmall.toTvNativeTextStyle(density, white.copy(alpha = 0.62f))
            val textGapPx = 2.dp.roundToPx()
            TvSourcePanelStyle(
                rightSampleTitle = rightSampleTitle,
                rightSampleMeta = rightSampleMeta,
                rightMinWidthPx = 200.dp.roundToPx(),
                rightSlackPx = 12.dp.roundToPx(),
                maxRightWidthPx = 520.dp.roundToPx(),
                // 与控制层的左边距一致
                paddingStartPx = 48.dp.roundToPx(),
                paddingEndPx = 48.dp.roundToPx(),
                paddingTopPx = 36.dp.roundToPx(),
                paddingBottomPx = 24.dp.roundToPx(),
                headerGapPx = 12.dp.roundToPx(),
                railWidthPx = 184.dp.roundToPx(),
                columnGapPx = 12.dp.roundToPx(),
                rowGapPx = 6.dp.roundToPx(),
                dividerGapPx = 14.dp.roundToPx(),
                dividerColor = white.copy(alpha = 0.12f).toArgb(),
                cornerPx = 12.dp.toPx(),
                rowPaddingHPx = 14.dp.roundToPx(),
                iconSizePx = TV_SOURCE_ICON_SIZE.roundToPx(),
                iconGapPx = 12.dp.roundToPx(),
                trailingGapPx = 10.dp.roundToPx(),
                spinnerSizePx = 16.dp.roundToPx(),
                railRowHeightPx = 44.dp.roundToPx(),
                lineRowHeightPx = 58.dp.roundToPx(),
                // 标题三行 + 信息一行 + 上下留白
                resourceRowHeightPx = lineHeightPx(rowTitle) * 3 + lineHeightPx(rowMeta) + textGapPx + 24.dp.roundToPx(),
                optionRowHeightPx = 46.dp.roundToPx(),
                cellHeightPx = 46.dp.roundToPx(),
                statusPaddingVPx = 10.dp.roundToPx(),
                textGapPx = textGapPx,
                pillHeightPx = 34.dp.roundToPx(),
                pillPaddingHPx = 14.dp.roundToPx(),
                pillGapPx = 8.dp.roundToPx(),
                pillsBottomGapPx = 12.dp.roundToPx(),
                drillTitleBottomGapPx = 10.dp.roundToPx(),
                focusBleedPx = 8.dp.roundToPx(),
                status = typography.bodySmall.toTvNativeTextStyle(density, white.copy(alpha = 0.8f)),
                drillTitle = typography.titleSmall.toTvNativeTextStyle(density, white.copy(alpha = 0.8f)),
                railTitle = typography.bodyMedium.toTvNativeTextStyle(density, white),
                rowTitle = rowTitle,
                rowMeta = rowMeta,
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
                branchShiftPx = 16.dp.roundToPx(),
                branchFadeMillis = 160,
                focusScale = 1.03f,
                marqueeRepeat = marqueeRepeat,
                focusInMillis = 150,
                focusOutMillis = 90,
                animated = transitions,
                icons = icons,
            )
        }
    }
}

/** 行首图标的边长. */
private val TV_SOURCE_ICON_SIZE = 20.dp

/** 一行字的高度 (px): 排版给了行高用行高, 没给按字号 1.4 倍估. */
private fun lineHeightPx(style: TvNativeTextStyle): Int =
    if (style.lineHeightPx > 0) style.lineHeightPx else (style.sizePx * 1.4f).toInt()

/** 面板的全部文案 (见 [TvSourceStrings]). */
@Composable
fun rememberTvSourceStrings(): TvSourceStrings {
    val details = rememberMediaDetailsStrings()
    val values = listOf(
        stringResource(Lang.tv_source_searching),
        stringResource(Lang.tv_source_searched),
        stringResource(Lang.tv_source_bt),
        stringResource(Lang.tv_source_manual),
        stringResource(Lang.tv_source_failed),
        stringResource(Lang.tv_source_refresh),
        stringResource(Lang.media_selector_full_search),
        stringResource(Lang.tv_source_on),
        stringResource(Lang.tv_source_off),
        stringResource(Lang.tv_source_keywords),
        stringResource(Lang.tv_source_playing),
        stringResource(Lang.tv_source_line),
        stringResource(Lang.tv_source_loading),
        stringResource(Lang.tv_source_no_result),
        stringResource(Lang.tv_source_captcha_required),
        stringResource(Lang.tv_source_captcha_action),
        stringResource(Lang.tv_source_captcha_hint),
        stringResource(Lang.tv_source_captcha_unsupported),
        stringResource(Lang.tv_source_resolving_captcha),
        stringResource(Lang.tv_source_rate_limited),
        stringResource(Lang.tv_source_rate_limited_hint),
        stringResource(Lang.tv_source_rate_limited_expired),
        stringResource(Lang.tv_source_rate_limited_expired_hint),
        stringResource(Lang.tv_source_failed_state),
        stringResource(Lang.tv_source_retry),
        stringResource(Lang.tv_source_retry_hint),
        stringResource(Lang.tv_source_disabled),
        stringResource(Lang.tv_source_episode_filter),
        stringResource(Lang.tv_source_all_episodes),
        stringResource(Lang.media_selector_filter_resolution),
        stringResource(Lang.media_selector_filter_subtitle),
        stringResource(Lang.media_selector_filter_alliance),
        stringResource(Lang.tv_source_source),
        stringResource(Lang.tv_source_all),
        stringResource(Lang.tv_source_show_excluded),
        stringResource(Lang.tv_source_hide_excluded),
        stringResource(Lang.tv_source_sources_count),
        stringResource(Lang.subject_episode_cached),
        stringResource(Lang.tv_source_pack),
        stringResource(Lang.media_selector_item_no_subtitle),
        stringResource(Lang.tv_source_bt_loading),
        stringResource(Lang.tv_source_bt_empty),
        stringResource(Lang.tv_source_menu_cache),
        stringResource(Lang.tv_source_menu_exclude),
        stringResource(Lang.tv_source_detail_pick),
        stringResource(Lang.tv_source_detail_open),
        stringResource(Lang.tv_source_detail_nav_hint),
        stringResource(Lang.tv_source_detail_status),
        stringResource(Lang.tv_source_detail_lines),
        stringResource(Lang.tv_source_detail_source),
        stringResource(Lang.tv_source_detail_size),
        stringResource(Lang.tv_source_detail_date),
        stringResource(Lang.tv_source_detail_episodes),
        stringResource(Lang.tv_source_detail_sources),
        stringResource(Lang.tv_source_detail_kind),
        stringResource(Lang.tv_source_detail_link),
        stringResource(Lang.tv_source_detail_error),
        stringResource(Lang.tv_source_detail_episode),
        stringResource(Lang.tv_source_status_done),
        stringResource(Lang.tv_source_status_searching),
        stringResource(Lang.tv_source_kind_web),
        stringResource(Lang.tv_source_kind_bt),
        stringResource(Lang.tv_source_kind_cache),
        stringResource(Lang.tv_source_manual_source),
        stringResource(Lang.tv_source_manual_keyword),
        stringResource(Lang.tv_source_manual_searching),
        stringResource(Lang.tv_source_manual_failed),
        stringResource(Lang.tv_source_manual_empty),
        stringResource(Lang.tv_source_manual_no_sources),
        stringResource(Lang.tv_source_manual_channel),
        stringResource(Lang.tv_source_manual_remember),
        stringResource(Lang.tv_source_manual_back),
        stringResource(Lang.tv_source_manual_loading_episodes),
        stringResource(Lang.tv_source_manual_no_episodes),
        stringResource(Lang.media_selector_bt_reason_episode_mismatch),
        stringResource(Lang.media_selector_bt_reason_below_preference),
        stringResource(Lang.media_selector_item_no_subtitle),
        stringResource(Lang.media_selector_item_single_episode_resource),
        stringResource(Lang.media_selector_item_unsupported_playback),
        stringResource(Lang.media_selector_item_season_mismatch),
        stringResource(Lang.media_selector_item_subject_title_mismatch),
        stringResource(Lang.media_selector_item_cache_not_ready),
        stringResource(Lang.media_selector_item_excluded_alliance),
        stringResource(Lang.subject_episode_cache),
        stringResource(Lang.tv_source_downloads_open),
        stringResource(Lang.tv_source_download_caching),
        stringResource(Lang.tv_source_downloads_empty),
        stringResource(Lang.tv_source_downloads_empty_disk_cache),
        stringResource(Lang.tv_source_reason_fuzzy_title),
        stringResource(Lang.tv_source_reason_episode_unknown),
        stringResource(Lang.tv_source_show_others),
        stringResource(Lang.tv_source_hide_others),
        stringResource(Lang.tv_source_rail_others),
        stringResource(Lang.tv_source_web_no_exact),
        stringResource(Lang.tv_source_episodes_season),
        stringResource(Lang.tv_source_episodes_whole_season),
    )
    return remember(values, details) {
        var i = 0
        fun next() = values[i++]
        TvSourceStrings(
            searching = next(),
            searched = next(),
            bt = next(),
            manual = next(),
            failed = next(),
            refresh = next(),
            fullSearch = next(),
            on = next(),
            off = next(),
            keywords = next(),
            playing = next(),
            line = next(),
            loading = next(),
            noResult = next(),
            captchaRequired = next(),
            captchaAction = next(),
            captchaHint = next(),
            captchaUnsupported = next(),
            resolvingCaptcha = next(),
            rateLimited = next(),
            rateLimitedHint = next(),
            rateLimitedExpired = next(),
            rateLimitedExpiredHint = next(),
            failedState = next(),
            retry = next(),
            retryHint = next(),
            disabled = next(),
            episodeFilter = next(),
            allEpisodes = next(),
            resolution = next(),
            subtitle = next(),
            alliance = next(),
            source = next(),
            all = next(),
            showExcluded = next(),
            hideExcluded = next(),
            sourcesCount = next(),
            cached = next(),
            pack = next(),
            noSubtitle = next(),
            btLoading = next(),
            btEmpty = next(),
            menuCache = next(),
            menuExclude = next(),
            pick = next(),
            open = next(),
            navHint = next(),
            labelStatus = next(),
            labelLines = next(),
            labelSource = next(),
            labelSize = next(),
            labelDate = next(),
            labelEpisodes = next(),
            labelSources = next(),
            labelKind = next(),
            labelLink = next(),
            labelError = next(),
            labelEpisode = next(),
            statusDone = next(),
            statusSearching = next(),
            kindWeb = next(),
            kindBt = next(),
            kindCache = next(),
            manualSource = next(),
            manualKeyword = next(),
            manualSearching = next(),
            manualFailed = next(),
            manualEmpty = next(),
            manualNoSources = next(),
            manualChannel = next(),
            manualRemember = next(),
            manualBack = next(),
            manualLoadingEpisodes = next(),
            manualNoEpisodes = next(),
            reasonEpisodeMismatch = next(),
            reasonBelowPreference = next(),
            reasonNoSubtitle = next(),
            reasonSingleEpisode = next(),
            reasonUnsupported = next(),
            reasonSeasonMismatch = next(),
            reasonTitleMismatch = next(),
            reasonCacheNotReady = next(),
            reasonExcludedAlliance = next(),
            downloads = next(),
            downloadsOpen = next(),
            downloadCaching = next(),
            downloadsEmpty = next(),
            downloadsEmptyDiskCache = next(),
            reasonFuzzyTitle = next(),
            reasonEpisodeUnknown = next(),
            showOthers = next(),
            hideOthers = next(),
            railOthers = next(),
            webNoExact = next(),
            episodesSeason = next(),
            episodesWholeSeason = next(),
            details = details,
            timeZone = TimeZone.currentSystemDefault(),
        )
    }
}
