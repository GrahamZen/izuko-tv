/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.repository.danmaku.SearchDanmakuRequest
import me.him188.ani.app.domain.danmaku.fetchKey
import me.him188.ani.app.domain.media.player.data.filenameOrNull
import me.him188.ani.danmaku.api.provider.DanmakuVideoFile
import me.him188.ani.datasources.api.Media
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.metadata.duration
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 播放页的弹幕请求: 条目与分集信息一到就发, 和找资源、开播同时进行. 弹幕源按条目与分集匹配用不着视频文件;
 * 只有最后一步按文件匹配要等播放器打开这一集的视频 ([SearchDanmakuRequest.awaitVideoFile]). 换资源不重新查.
 *
 * 连续切集时只查停下来的那一集 (稍等一下再发).
 */
internal fun episodeDanmakuRequests(
    bundleFlow: Flow<SubjectEpisodeInfoBundle>,
    player: MediampPlayer,
    selectedMedia: Flow<Media?>,
): Flow<SearchDanmakuRequest> = bundleFlow
    .map { info ->
        SearchDanmakuRequest(
            info.subjectInfo,
            info.episodeInfo,
            info.episodeId,
            splitSeason = info.seriesInfo.splitSeason,
            awaitVideoFile = awaitVideoFile(player, selectedMedia),
        )
    }
    .distinctUntilChangedBy { it.fetchKey }
    .debounce(300.milliseconds)

/**
 * 等播放器换上新视频再取它的文件信息: 建请求时播放器里还挂着的 (上一集的) 不算.
 *
 * 从建请求起最多等 [VIDEO_FILE_WAIT], 等不到为 `null` (不按文件匹配): `DanmakuFetcher` 给整个匹配 60 秒, 超时会把整条匹配重来一遍.
 */
private fun awaitVideoFile(
    player: MediampPlayer,
    selectedMedia: Flow<Media?>,
): suspend () -> DanmakuVideoFile? {
    val previous = player.mediaData.value
    val requestedAt = TimeSource.Monotonic.markNow()
    return {
        withTimeoutOrNull(VIDEO_FILE_WAIT - requestedAt.elapsedNow()) {
            videoFileOf(player.mediaData.first { it != null && it !== previous }!!, player, selectedMedia)
        }
    }
}

private val VIDEO_FILE_WAIT = 40.seconds

private suspend fun videoFileOf(
    mediaData: MediaData,
    player: MediampPlayer,
    selectedMedia: Flow<Media?>,
): DanmakuVideoFile? {
    val filename = mediaData.filenameOrNull ?: selectedMedia.first()?.originalTitle ?: return null
    return DanmakuVideoFile(
        filename = filename,
        fileHash = "aa".repeat(16),
        fileSize = when (mediaData) {
            is SeekableInputMediaData -> mediaData.fileLength()
            is UriMediaData -> null
        },
        videoDuration = player.mediaProperties.first { it != null }?.duration ?: 0.milliseconds,
    )
}
