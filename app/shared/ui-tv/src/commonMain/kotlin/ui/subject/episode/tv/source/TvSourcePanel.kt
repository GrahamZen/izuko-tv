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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.AniOutlinedTextField
import me.him188.ani.app.ui.foundation.tv.TV_REDUCED_MARQUEE_ITERATIONS
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHost
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_unknown
import me.him188.ani.app.ui.lang.media_selector_bt_filter
import me.him188.ani.app.ui.lang.media_selector_bt_reason_below_preference
import me.him188.ani.app.ui.lang.media_selector_bt_reason_episode_mismatch
import me.him188.ani.app.ui.lang.media_selector_filter_alliance
import me.him188.ani.app.ui.lang.media_selector_filter_resolution
import me.him188.ani.app.ui.lang.media_selector_filter_subtitle
import me.him188.ani.app.ui.lang.media_selector_full_search
import me.him188.ani.app.ui.lang.media_selector_item_cache_not_ready
import me.him188.ani.app.ui.lang.media_selector_item_excluded_alliance
import me.him188.ani.app.ui.lang.media_selector_item_published_before_airing
import me.him188.ani.app.ui.lang.media_selector_item_no_subtitle
import me.him188.ani.app.ui.lang.media_selector_item_season_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_single_episode_resource
import me.him188.ani.app.ui.lang.media_selector_item_subject_title_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_unsupported_playback
import me.him188.ani.app.ui.lang.media_subtitle_kind_closed
import me.him188.ani.app.ui.lang.media_subtitle_kind_closed_or_external_discovered
import me.him188.ani.app.ui.lang.media_subtitle_kind_embedded
import me.him188.ani.app.ui.lang.media_subtitle_kind_external_discovered
import me.him188.ani.app.ui.lang.media_subtitle_kind_external_provided
import me.him188.ani.app.ui.lang.media_subtitle_language_chinese_cantonese
import me.him188.ani.app.ui.lang.media_subtitle_language_chinese_simplified
import me.him188.ani.app.ui.lang.media_subtitle_language_chinese_traditional
import me.him188.ani.app.ui.lang.media_subtitle_language_english
import me.him188.ani.app.ui.lang.media_subtitle_language_japanese
import me.him188.ani.app.ui.lang.subject_episode_cache
import me.him188.ani.app.ui.lang.subject_episode_cached
import me.him188.ani.app.ui.lang.tv_settings_drive_not_logged_in
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
import me.him188.ani.app.ui.lang.tv_source_disabled
import me.him188.ani.app.ui.lang.tv_source_drive_login_action
import me.him188.ani.app.ui.lang.tv_source_drive_login_hint
import me.him188.ani.app.ui.lang.tv_source_download_caching
import me.him188.ani.app.ui.lang.tv_source_downloads_empty
import me.him188.ani.app.ui.lang.tv_source_downloads_empty_disk_cache
import me.him188.ani.app.ui.lang.tv_source_downloads_open
import me.him188.ani.app.ui.lang.tv_source_empty_sources
import me.him188.ani.app.ui.lang.tv_source_episode_filter
import me.him188.ani.app.ui.lang.tv_source_episodes_season
import me.him188.ani.app.ui.lang.tv_source_episodes_whole_season
import me.him188.ani.app.ui.lang.tv_source_excluded_toast
import me.him188.ani.app.ui.lang.tv_source_failed
import me.him188.ani.app.ui.lang.tv_source_failed_state
import me.him188.ani.app.ui.lang.tv_source_group_direct
import me.him188.ani.app.ui.lang.tv_source_group_drive
import me.him188.ani.app.ui.lang.tv_source_group_online
import me.him188.ani.app.ui.lang.tv_source_hide_empty_sources
import me.him188.ani.app.ui.lang.tv_source_hide_excluded
import me.him188.ani.app.ui.lang.tv_source_hidden_by_filter
import me.him188.ani.app.ui.lang.tv_source_hide_others
import me.him188.ani.app.ui.lang.tv_source_keywords
import me.him188.ani.app.ui.lang.tv_source_kind_bt
import me.him188.ani.app.ui.lang.tv_source_kind_cache
import me.him188.ani.app.ui.lang.tv_source_kind_web
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
import me.him188.ani.app.ui.lang.tv_source_manual_pick_failed
import me.him188.ani.app.ui.lang.tv_source_manual_remember
import me.him188.ani.app.ui.lang.tv_source_manual_searching
import me.him188.ani.app.ui.lang.tv_source_manual_source
import me.him188.ani.app.ui.lang.tv_source_menu_cache
import me.him188.ani.app.ui.lang.tv_source_menu_exclude
import me.him188.ani.app.ui.lang.tv_source_no_result
import me.him188.ani.app.ui.lang.tv_source_off
import me.him188.ani.app.ui.lang.tv_source_on
import me.him188.ani.app.ui.lang.tv_source_pack
import me.him188.ani.app.ui.lang.tv_source_playback_buffering
import me.him188.ani.app.ui.lang.tv_source_playback_failed
import me.him188.ani.app.ui.lang.tv_source_playing
import me.him188.ani.app.ui.lang.tv_source_rail_others
import me.him188.ani.app.ui.lang.tv_source_rate_limited
import me.him188.ani.app.ui.lang.tv_source_rate_limited_expired
import me.him188.ani.app.ui.lang.tv_source_rate_limited_expired_hint
import me.him188.ani.app.ui.lang.tv_source_rate_limited_hint
import me.him188.ani.app.ui.lang.tv_source_reason_episode_unknown
import me.him188.ani.app.ui.lang.tv_source_reason_fuzzy_title
import me.him188.ani.app.ui.lang.tv_source_refresh
import me.him188.ani.app.ui.lang.tv_source_resolving_captcha
import me.him188.ani.app.ui.lang.tv_source_retry
import me.him188.ani.app.ui.lang.tv_source_retry_hint
import me.him188.ani.app.ui.lang.tv_source_searched
import me.him188.ani.app.ui.lang.tv_source_searching
import me.him188.ani.app.ui.lang.tv_source_settings
import me.him188.ani.app.ui.lang.tv_source_show_excluded
import me.him188.ani.app.ui.lang.tv_source_show_others
import me.him188.ani.app.ui.lang.tv_source_site_ip_blocked
import me.him188.ani.app.ui.lang.tv_source_source
import me.him188.ani.app.ui.lang.tv_source_sources_count
import me.him188.ani.app.ui.lang.tv_source_status_done
import me.him188.ani.app.ui.lang.tv_source_status_searching
import me.him188.ani.app.ui.lang.tv_source_web_no_exact
import me.him188.ani.app.ui.media.rememberMediaDetailsStrings
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.rememberResourceEnvironment
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/**
 * 面板视图的把手: 播放器根路由经它问「面板里有焦点吗」、把返回键交给面板、把焦点送进面板. 视图建出来之前全是空操作.
 */
@Stable
class TvSourcePanelController {
    internal var view: TvSourcePanelView? = null

    val hasFocus: Boolean get() = view?.hasPanelFocus() == true

    /** 返回键. true = 面板自己处理了 (退了一层); false = 已经退到头了, 该关面板了 (见 [TvSourcePanelView.handleBack]). */
    fun back(): Boolean = view?.handleBack() == true

    /**
     * 上次按返回隐藏时停的地方 (见 [rememberPosition]): 下次打开原样回去. 用一次就清 ([takeResume]);
     * 别的方式关的 (触屏点画面、选完自动关、长按返回) 不记, 下次照常先停在「正在播放」.
     */
    private var resume: TvSourceResume? = null

    /** 按返回关面板前记下停的地方 (见 [TvSourcePanelView.resumePoint]). [episodeId] = 此刻播的那一集, 换了集就不落回去. */
    fun rememberPosition(episodeId: Int?) {
        resume = view?.resumePoint()?.let { TvSourceResume(it, episodeId) }
    }

    fun forgetPosition() {
        resume = null
    }

    /** 取走记下的位置 (还是同一集才算). */
    fun takeResume(episodeId: Int?): TvSourceResume? = resume.also { resume = null }?.takeIf { it.episodeId == episodeId }

    fun requestEntryFocus() {
        view?.requestEntryFocus()
    }

    /**
     * 退场途中又打开 (按返回关上马上又按上键): 还是正在淡出的那个视图, 不重建, 建视图时那一下落焦点也就没有 —— 照新打开那样再落一次,
     * 落回 [resume] (null = 照常落).
     */
    fun reopen(resume: TvSourceResumePoint?) {
        val view = view ?: return
        view.resumeAt(resume)
        view.requestEntryFocus()
    }

    /** 焦点送到一行 (详情弹窗换条 / 关掉时), 见 [TvSourcePanelView.focusRow]. */
    fun focusRow(railKey: String?, id: String, inRail: Boolean) {
        view?.focusRow(railKey, id, inRail)
    }
}

/** 面板隐藏时停的地方 ([point]); 当时播的是 [episodeId]. */
data class TvSourceResume(val point: TvSourceResumePoint, val episodeId: Int?)

/**
 * 选源面板 (原生视图, 见 [TvSourcePanelView]): 把 [state] 拼好的内容交给视图, 视图的事件交回 [state]. 内容的变化不经过重组 ——
 * 搜索途中上游状态一秒变好几次, 走重组就是每次把整棵 AndroidView 的 update 跑一遍.
 *
 * @param leaving 面板正在退场 (见 [TvSourcePanelView.leaving]).
 * @param onClose 要关面板: 触屏点了面板下面的画面.
 * @param resume 打开时回到上次按返回关时停的地方, 见 [TvSourcePanelView.resumeAt].
 */
@Composable
fun TvSourcePanel(
    state: TvSourcePanelState,
    controller: TvSourcePanelController,
    leaving: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    resume: TvSourceResumePoint? = null,
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
                panelViewWarmed = true
                controller.view = view
                view.listener = object : TvSourcePanelListener {
                    override fun onRailFocused(key: String) = currentState.onRailFocused(key)
                    override fun onSourceFocused(groupKey: String, sourceKey: String) = currentState.onSourceFocused(groupKey, sourceKey)
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
    LaunchedEffect(state) {
        state.content.filterNotNull().collect { controller.view?.submit(it) }
    }
    val detailsTarget by state.detailsTarget.collectAsState()
    detailsTarget?.let { TvSourceDetailsHost(state, it, controller) }
    val filterOpen by state.filterOpen.collectAsState()
    if (filterOpen) TvSourceFilterDialog(state, onDismiss = { state.filterOpen.value = false })
    val driveLogin by state.driveLogin.collectAsState()
    driveLogin?.let { driveId ->
        TvDriveLoginDialog(driveId, onLoggedIn = { state.onDriveLoggedIn(driveId) }, onDismiss = { state.driveLogin.value = null })
    }
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
                TvSourceRowIcon.Drive -> Icons.Rounded.FolderOpen
                TvSourceRowIcon.Link -> Icons.Rounded.Link
                TvSourceRowIcon.Settings -> Icons.Rounded.Settings
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

/** [TvSourceStrings] 的各个字段的字符串资源, 顺序同字段. */
private val TV_SOURCE_STRING_RESOURCES: List<StringResource> = listOf(
    Lang.tv_source_searching,
    Lang.tv_source_searched,
    Lang.tv_source_bt,
    Lang.tv_source_manual,
    Lang.tv_source_failed,
    Lang.tv_source_refresh,
    Lang.media_selector_full_search,
    Lang.tv_source_on,
    Lang.tv_source_off,
    Lang.tv_source_keywords,
    Lang.tv_source_playing,
    Lang.tv_source_line,
    Lang.tv_source_loading,
    Lang.tv_source_no_result,
    Lang.tv_source_captcha_required,
    Lang.tv_source_captcha_action,
    Lang.tv_source_captcha_hint,
    Lang.tv_source_captcha_unsupported,
    Lang.tv_source_resolving_captcha,
    Lang.tv_source_rate_limited,
    Lang.tv_source_rate_limited_hint,
    Lang.tv_source_rate_limited_expired,
    Lang.tv_source_rate_limited_expired_hint,
    Lang.tv_source_failed_state,
    Lang.tv_source_retry,
    Lang.tv_source_retry_hint,
    Lang.tv_source_disabled,
    Lang.tv_source_episode_filter,
    Lang.tv_source_all_episodes,
    Lang.media_selector_filter_resolution,
    Lang.media_selector_filter_subtitle,
    Lang.media_selector_filter_alliance,
    Lang.tv_source_source,
    Lang.tv_source_all,
    Lang.tv_source_show_excluded,
    Lang.tv_source_hide_excluded,
    Lang.tv_source_hidden_by_filter,
    Lang.tv_source_sources_count,
    Lang.subject_episode_cached,
    Lang.tv_source_pack,
    Lang.media_selector_item_no_subtitle,
    Lang.tv_source_bt_loading,
    Lang.tv_source_bt_empty,
    Lang.tv_source_menu_cache,
    Lang.tv_source_menu_exclude,
    Lang.tv_source_detail_pick,
    Lang.tv_source_detail_open,
    Lang.tv_source_detail_nav_hint,
    Lang.tv_source_detail_status,
    Lang.tv_source_detail_lines,
    Lang.tv_source_detail_source,
    Lang.tv_source_detail_size,
    Lang.tv_source_detail_date,
    Lang.tv_source_detail_episodes,
    Lang.tv_source_detail_sources,
    Lang.tv_source_detail_kind,
    Lang.tv_source_detail_link,
    Lang.tv_source_detail_error,
    Lang.tv_source_detail_episode,
    Lang.tv_source_status_done,
    Lang.tv_source_status_searching,
    Lang.tv_source_kind_web,
    Lang.tv_source_kind_bt,
    Lang.tv_source_kind_cache,
    Lang.tv_source_manual_source,
    Lang.tv_source_manual_keyword,
    Lang.tv_source_manual_searching,
    Lang.tv_source_manual_failed,
    Lang.tv_source_manual_empty,
    Lang.tv_source_manual_no_sources,
    Lang.tv_source_manual_channel,
    Lang.tv_source_manual_remember,
    Lang.tv_source_manual_back,
    Lang.tv_source_manual_loading_episodes,
    Lang.tv_source_manual_no_episodes,
    Lang.media_selector_bt_reason_episode_mismatch,
    Lang.media_selector_bt_reason_below_preference,
    Lang.media_selector_item_no_subtitle,
    Lang.media_selector_item_single_episode_resource,
    Lang.media_selector_item_unsupported_playback,
    Lang.media_selector_item_season_mismatch,
    Lang.media_selector_item_subject_title_mismatch,
    Lang.media_selector_item_cache_not_ready,
    Lang.media_selector_item_excluded_alliance,
    Lang.media_selector_item_published_before_airing,
    Lang.subject_episode_cache,
    Lang.tv_source_downloads_open,
    Lang.tv_source_download_caching,
    Lang.tv_source_downloads_empty,
    Lang.tv_source_downloads_empty_disk_cache,
    Lang.tv_source_reason_fuzzy_title,
    Lang.tv_source_reason_episode_unknown,
    Lang.tv_source_show_others,
    Lang.tv_source_hide_others,
    Lang.tv_source_rail_others,
    Lang.tv_source_web_no_exact,
    Lang.tv_source_episodes_season,
    Lang.tv_source_episodes_whole_season,
    Lang.media_selector_bt_filter,
    Lang.tv_settings_drive_not_logged_in,
    Lang.tv_source_drive_login_action,
    Lang.tv_source_drive_login_hint,
    Lang.tv_source_site_ip_blocked,
    Lang.tv_source_group_drive,
    Lang.tv_source_group_online,
    Lang.tv_source_group_direct,
    Lang.tv_source_empty_sources,
    Lang.tv_source_hide_empty_sources,
    Lang.tv_source_playback_failed,
    Lang.tv_source_playback_buffering,
    Lang.tv_source_settings,
)

/** 面板的全部文案 (见 [TvSourceStrings]). */
@Composable
fun rememberTvSourceStrings(): TvSourceStrings {
    val details = rememberMediaDetailsStrings()
    // 预读过 (同一资源环境) 就直接用, 不再逐条 stringResource: 每条都要 runBlocking 一次, ~100 条合计 ~50ms
    val environment = rememberResourceEnvironment()
    val values = prewarmedTvSourceStrings?.takeIf { it.first == environment }?.second
        ?: TV_SOURCE_STRING_RESOURCES.map { stringResource(it) }
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
            hiddenByFilter = next(),
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
            reasonPublishedBeforeAiring = next(),
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
            filter = next(),
            driveNotLoggedIn = next(),
            driveLoginAction = next(),
            driveLoginHint = next(),
            siteIpBlocked = next(),
            groupDrive = next(),
            groupOnline = next(),
            groupDirect = next(),
            emptySources = next(),
            hideEmptySources = next(),
            playbackFailed = next(),
            playbackBuffering = next(),
            settings = next(),
            details = details,
            timeZone = TimeZone.currentSystemDefault(),
        )
    }
}

/**
 * 在后台先把选源面板的文案读一遍. CMP 资源的 `stringResource` 在 Android 上同步读, 每条字符串第一次用都在主线程从安装包里读,
 * 进程里第一次开面板 (~110 条) 主线程要卡 0.3~0.4 秒; 读好的面板直接用 (见 [rememberTvSourceStrings]), 其余的读进缓存. 播放页里组合一次.
 * 用组合里的资源环境读 (应用内语言设置与系统不同时, 缓存的是同一份).
 */
@Composable
internal fun PrewarmTvSourcePanelStrings() {
    val environment = rememberResourceEnvironment()
    LaunchedEffect(environment) {
        val values = withContext(Dispatchers.IO) {
            for (resource in TV_SOURCE_OTHER_STRING_RESOURCES) getString(environment, resource)
            TV_SOURCE_STRING_RESOURCES.map { getString(environment, it) }
        }
        prewarmedTvSourceStrings = environment to values
    }
}

/** 预读好的 [TvSourceStrings] 各字段 (按 [TV_SOURCE_STRING_RESOURCES] 的顺序) 与读时的资源环境. 只在主线程上读写. */
private var prewarmedTvSourceStrings: Pair<ResourceEnvironment, List<String>>? = null

/** 面板里另外用到的: 字幕说明 ([rememberMediaDetailsStrings]), 宿主的提示, 输入框与详情弹窗. */
private val TV_SOURCE_OTHER_STRING_RESOURCES: List<StringResource> = listOf(
    Lang.media_subtitle_kind_embedded,
    Lang.media_subtitle_kind_closed,
    Lang.media_subtitle_kind_external_provided,
    Lang.media_subtitle_kind_external_discovered,
    Lang.media_subtitle_kind_closed_or_external_discovered,
    Lang.cache_unknown,
    Lang.media_subtitle_language_chinese_cantonese,
    Lang.media_subtitle_language_chinese_simplified,
    Lang.media_subtitle_language_chinese_traditional,
    Lang.media_subtitle_language_japanese,
    Lang.media_subtitle_language_english,
    Lang.tv_source_excluded_toast,
    Lang.tv_source_manual_pick_failed,
    Lang.tv_source_detail_nav_hint,
    Lang.tv_source_confirm,
    Lang.tv_source_cancel,
)

/** 进程里建过面板视图了 (类加载、量字宽等第一次的开销已经付过). 只在主线程上读写. */
private var panelViewWarmed = false

/**
 * 进程里第一次建面板的原生视图要 ~0.5 秒 (类加载、量字宽、第一次排版; Shield 实测), 都在主线程上.
 * 在播放页进来一会儿、[canPrewarmNow] 为 true (纯视频态, 画面与弹幕各有自己的表面, 主线程忙一下看不出来) 时
 * 先建一个按屏幕大小排一遍再丢掉, 第一次开面板就接近之后几次. 已经开过面板的不再做.
 */
@Composable
internal fun PrewarmTvSourcePanelView(canPrewarmNow: () -> Boolean) {
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
