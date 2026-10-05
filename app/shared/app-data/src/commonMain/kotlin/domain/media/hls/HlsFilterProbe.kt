/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.hls

import io.ktor.http.Url
import me.him188.ani.app.videoplayer.diagnostics.PlayerProbes
import me.him188.ani.utils.platform.currentTimeMillis

/**
 * 给 Web 控制台调试区记一笔这次去广告的结果 (见 [PlayerProbes]): 本地代理处理完一个媒体播放列表时调用.
 *
 * @param playlistUri 远端播放列表的地址
 * @param original 远端播放列表的原文
 */
internal fun recordHlsFilterProbe(playlistUri: String, original: String, result: HlsManifestFilterResult) {
    PlayerProbes.recordHls(
        host = runCatching { Url(playlistUri).host }.getOrDefault(""),
        totalSegments = original.lineSequence().count { it.trimStart().startsWith("#EXTINF:") },
        removedSegments = result.removedGroups.sumOf { it.segmentCount },
        removedSeconds = result.removedGroups.sumOf { it.duration },
        skippedReason = if (result.status == HlsManifestFilterStatus.Filtered) {
            null
        } else {
            result.reason ?: result.status.name
        },
        atMillis = currentTimeMillis(),
    )
}
