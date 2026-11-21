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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.tv.AniOutlinedTextField
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
import me.him188.ani.app.ui.lang.tv_source_group_local
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
import me.him188.ani.app.ui.lang.tv_source_full_search_paused
import me.him188.ani.app.ui.lang.tv_source_paused_hint
import me.him188.ani.app.ui.lang.tv_source_resume_source
import me.him188.ani.app.ui.lang.tv_source_search_paused
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
import org.jetbrains.compose.resources.rememberResourceEnvironment
import org.jetbrains.compose.resources.stringResource

/**
 * 面板视图的把手: 播放器根路由经它问「面板里有焦点吗」、把返回键交给面板、把焦点送进面板. 视图建出来之前全是空操作.
 */
@Stable
class TvSourcePanelController {
    internal var view: TvSourcePanelViewHandle? = null

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
 * @param strings 面板文案; 建 [state] 时已经取过的传进来 (同一份, 不再逐条取一遍).
 */
@Composable
fun TvSourcePanel(
    state: TvSourcePanelState,
    controller: TvSourcePanelController,
    leaving: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    resume: TvSourceResumePoint? = null,
    strings: TvSourceStrings = rememberTvSourceStrings(),
) {
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(state, strings) { state.setStrings(strings) }
    TvSourcePanelNativeView(state, controller, leaving, resume, onClose = { currentOnClose() }, modifier)
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
    Lang.tv_source_group_local,
    Lang.tv_source_group_drive,
    Lang.tv_source_group_online,
    Lang.tv_source_group_direct,
    Lang.tv_source_empty_sources,
    Lang.tv_source_hide_empty_sources,
    Lang.tv_source_playback_failed,
    Lang.tv_source_playback_buffering,
    Lang.tv_source_settings,
    Lang.tv_source_search_paused,
    Lang.tv_source_full_search_paused,
    Lang.tv_source_resume_source,
    Lang.tv_source_paused_hint,
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
            groupLocal = next(),
            groupDrive = next(),
            groupOnline = next(),
            groupDirect = next(),
            emptySources = next(),
            hideEmptySources = next(),
            playbackFailed = next(),
            playbackBuffering = next(),
            settings = next(),
            searchPaused = next(),
            fullSearchPaused = next(),
            resumeSource = next(),
            pausedHint = next(),
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
        val values = withContext(Dispatchers.IO_) {
            for (resource in TV_SOURCE_OTHER_STRING_RESOURCES) getStringOffMain(environment, resource)
            TV_SOURCE_STRING_RESOURCES.map { getStringOffMain(environment, it) }
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


/**
 * 面板的原生视图在控制器眼里的样子 (安卓的 TvSourcePanelView 实现它): 交内容、问焦点、交返回键、记下 / 回到停的地方、送焦点.
 */
interface TvSourcePanelViewHandle {
    fun submit(content: TvSourcePanelContent)
    fun hasPanelFocus(): Boolean
    fun handleBack(): Boolean
    fun resumePoint(): TvSourceResumePoint?
    fun resumeAt(point: TvSourceResumePoint?)
    fun requestEntryFocus()
    fun focusRow(railKey: String?, id: String, inRail: Boolean)
}

/**
 * 面板本体: 安卓是原生视图 (建好交给 [controller], 视图的事件交回 [state]); iOS 上遥控器的选源面板不出现, 什么都不画.
 */
@Composable
internal expect fun TvSourcePanelNativeView(
    state: TvSourcePanelState,
    controller: TvSourcePanelController,
    leaving: Boolean,
    resume: TvSourceResumePoint?,
    onClose: () -> Unit,
    modifier: Modifier,
)

/** 进播放页一会儿后先把面板的原生视图建一个丢掉 (第一次开面板不卡); iOS 上没有. */
@Composable
internal expect fun PrewarmTvSourcePanelView(canPrewarmNow: () -> Boolean)
