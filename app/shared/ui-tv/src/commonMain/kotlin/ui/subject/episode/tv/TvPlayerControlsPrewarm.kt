/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.comment_reply_to
import me.him188.ani.app.ui.lang.episode_comments
import me.him188.ani.app.ui.lang.episode_danmaku
import me.him188.ani.app.ui.lang.episode_send_danmaku_failed
import me.him188.ani.app.ui.lang.episode_send_danmaku_quota_exceeded
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.playback_up_next_start
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.lang.subject_episode_cache
import me.him188.ani.app.ui.lang.subject_episode_danmaku_list_empty
import me.him188.ani.app.ui.lang.subject_episode_danmaku_settings_title
import me.him188.ani.app.ui.lang.subject_episode_details
import me.him188.ani.app.ui.lang.subject_episode_external_links
import me.him188.ani.app.ui.lang.subject_episode_fast_forward_seconds
import me.him188.ani.app.ui.lang.subject_episode_related_recommendations
import me.him188.ani.app.ui.lang.subject_episode_related_recommendations_empty
import me.him188.ani.app.ui.lang.subject_episode_select_media_source
import me.him188.ani.app.ui.lang.subject_episode_strip_loading
import me.him188.ani.app.ui.lang.video_player_aspect_fit
import me.him188.ani.app.ui.lang.video_player_audio_track
import me.him188.ani.app.ui.lang.video_player_audio_track_unplayable_toast
import me.him188.ani.app.ui.lang.video_player_enable_danmaku
import me.him188.ani.app.ui.lang.video_player_next_episode
import me.him188.ani.app.ui.lang.video_player_select_episode
import me.him188.ani.app.ui.lang.video_player_speed
import me.him188.ani.app.ui.lang.video_player_stats_title_show
import me.him188.ani.app.ui.lang.video_player_subtitle
import me.him188.ani.app.ui.lang.video_player_tv_chrome_item_aspect_ratio
import me.him188.ani.app.ui.lang.video_player_tv_chrome_item_divider
import me.him188.ani.app.ui.lang.video_player_tv_chrome_item_spacer
import me.him188.ani.app.ui.lang.video_player_tv_collection
import me.him188.ani.app.ui.lang.video_player_tv_comment_deleted
import me.him188.ani.app.ui.lang.video_player_tv_episodes
import me.him188.ani.app.ui.lang.video_player_tv_restart
import me.him188.ani.app.ui.lang.video_player_tv_up_next_in
import me.him188.ani.app.ui.lang.watch_together_title
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.rememberResourceEnvironment
import kotlin.time.Duration.Companion.seconds

/**
 * 控制层第一次唤出不迟: 进程里第一次组合控制层要 ~0.3 秒 (索尼), 按确定暂停时控制层迟这么久才出来.
 * - 一进播放页就在后台线程把控制层与浮出面板的文案读一遍: CMP 资源的 stringResource 在 Android 上同步读, 每条第一次用都在主线程从安装包里读;
 * - [canPrewarmNow] (纯视频态且在播: 画面与弹幕各在自己的表面上, 主线程忙一下看不出) 连着 [SETTLE] 都为 true 时调 [prewarm],
 *   由播放页把控制层以休眠态 (全透明、按钮不可聚焦, 同收起之后) 先组合进来; 之后第一次唤出只是从休眠里淡入.
 *   要等开播之后这么久: 刚开播时主线程还在忙 (出首帧、取弹幕、选源收尾), 这时组合会和它们抢, 卡得比按暂停时第一次组合还久.
 *
 * 控制层收起后本来就留在组合里 (见 TvEpisodeScreen 的 controlsRetained), 这里只是把「第一次组合」挪到空闲时.
 */
@Composable
internal fun PrewarmTvPlayerControls(
    canPrewarmNow: () -> Boolean,
    prewarm: () -> Unit,
) {
    val environment = rememberResourceEnvironment()
    LaunchedEffect(environment) {
        withContext(Dispatchers.IO) {
            for (resource in TV_PLAYER_CONTROLS_STRING_RESOURCES) getString(environment, resource)
        }
    }
    val currentCanPrewarm by rememberUpdatedState(canPrewarmNow)
    val currentPrewarm by rememberUpdatedState(prewarm)
    LaunchedEffect(Unit) {
        var since = 0L
        while (true) {
            delay(1.seconds)
            since = if (currentCanPrewarm()) since + 1 else 0
            if (since >= SETTLE.inWholeSeconds) break
        }
        logger.info { "Prewarming player controls (dormant)" }
        currentPrewarm()
    }
}

private val logger = logger("TvPlayerControlsPrewarm")

/** 连着在播这么久之后再预组合. */
private val SETTLE = 10.seconds

/** 控制层 (胶囊、图标行、选集条) 与浮出面板用到的文案; 漏了的只是第一次用到时在主线程读. */
private val TV_PLAYER_CONTROLS_STRING_RESOURCES: List<StringResource> = listOf(
    Lang.subject_episode_related_recommendations,
    Lang.subject_details_staff,
    Lang.subject_details_characters,
    Lang.episode_comments,
    Lang.episode_danmaku,
    Lang.video_player_tv_restart,
    Lang.video_player_next_episode,
    Lang.subject_episode_fast_forward_seconds,
    Lang.video_player_select_episode,
    Lang.subject_episode_details,
    Lang.subject_episode_select_media_source,
    Lang.video_player_enable_danmaku,
    Lang.subject_episode_danmaku_settings_title,
    Lang.watch_together_title,
    Lang.video_player_subtitle,
    Lang.video_player_audio_track,
    Lang.video_player_speed,
    Lang.video_player_aspect_fit,
    Lang.video_player_tv_chrome_item_aspect_ratio,
    Lang.video_player_tv_collection,
    Lang.video_player_stats_title_show,
    Lang.subject_episode_external_links,
    Lang.subject_episode_cache,
    Lang.video_player_tv_chrome_item_divider,
    Lang.video_player_tv_chrome_item_spacer,
    Lang.subject_episode_strip_loading,
    Lang.playback_history_episode_label,
    Lang.video_player_tv_episodes,
    Lang.playback_up_next_start,
    Lang.video_player_tv_up_next_in,
    Lang.subject_episode_danmaku_list_empty,
    Lang.subject_episode_related_recommendations_empty,
    Lang.comment_reply_to,
    Lang.video_player_tv_comment_deleted,
    Lang.episode_send_danmaku_failed,
    Lang.episode_send_danmaku_quota_exceeded,
    Lang.video_player_audio_track_unplayable_toast,
)
