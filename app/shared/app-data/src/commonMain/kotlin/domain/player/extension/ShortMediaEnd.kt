/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent

/**
 * 正片至少这么长. 网页源的规则偶尔抓到站点的公告、广告片 (几秒), 分享里也有十几秒的花絮被当成这一集:
 * 比它短的视频播完不算「这一集播完」, 不换下一集 ([SwitchNextEpisodeExtension]), 当作这个源失败换下一个源 ([observeShortMediaEnd]).
 */
internal const val MIN_EPISODE_DURATION_MILLIS = 60_000L

/** 播完的是不是太短、不像正片的视频; 时长不明时不算. */
internal fun PlaybackEvent.MediaEnded.isTooShortForEpisode(): Boolean =
    durationMillis?.let { it in 1 until MIN_EPISODE_DURATION_MILLIS } == true

/** 太短的视频 ([isTooShortForEpisode]) 播完时按播放失败处理: 拉黑这个资源并换下一个源. */
internal suspend fun PlayerLoadErrorHandler.observeShortMediaEnd(player: MediampPlayer, bundles: Flow<MediaFetchSelectBundle?>) {
    bundles.collectLatest { bundle ->
        if (bundle == null) return@collectLatest
        player.events.filterIsInstance<PlaybackEvent.MediaEnded>().filter { it.isTooShortForEpisode() }.collect { event ->
            handleError(
                bundle.mediaFetchSession,
                bundle.mediaSelector,
                PlayerLoadError("media ended after ${event.durationMillis}ms, too short for an episode", null),
            )
        }
    }
}
