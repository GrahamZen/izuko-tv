/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException

/**
 * 在线源的资源 (网页抓取、规则源、JSON 接口、媒体服务器等; 网盘由 [me.him188.ani.app.domain.mediasource.clouddrive.DriveEpisodeContinuation] 管):
 * 顺着同一个源、站点上同一个条目、同一条线路找 ([WebSourceLineage]). 排在网盘后面, 网盘资源先被它认领.
 */
class WebSourceContinuation(private val instances: Flow<List<MediaSourceInstance>>) : EpisodeContinuation {
    override fun owns(media: Media): Boolean = media.kind == MediaSourceKind.WEB

    override suspend fun lineageOf(media: Media): PlaybackLineage? {
        val subjectName = media.properties.subjectName?.takeIf { it.isNotBlank() } ?: return null
        return WebSourceLineage(media.mediaSourceId, subjectName, media.properties.alliance)
    }

    override fun continues(lineage: PlaybackLineage, candidate: Media): Boolean =
        lineage is WebSourceLineage && candidate.kind == MediaSourceKind.WEB &&
                candidate.mediaSourceId == lineage.mediaSourceId &&
                candidate.properties.subjectName == lineage.subjectName &&
                candidate.properties.alliance == lineage.channel

    override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest): LineageResolution =
        if (lineage is WebSourceLineage) refetch(instances, lineage, request, ::continues) else LineageResolution.NotFound
}

/**
 * BT 资源: 整季包顺着同一个种子找, 单集顺着同一个字幕组、同分辨率、同字幕语言的发布找 ([TorrentLineage]).
 * 认不出字幕组的资源记不了 (没有能顺着找的特征).
 */
class TorrentContinuation(private val instances: Flow<List<MediaSourceInstance>>) : EpisodeContinuation {
    override fun owns(media: Media): Boolean = media.kind == MediaSourceKind.BitTorrent

    override suspend fun lineageOf(media: Media): PlaybackLineage? {
        val alliance = media.properties.alliance.takeIf { it.isNotBlank() } ?: return null
        // 整季包: 不止一集 (范围或整季, 整季没有列出集号)
        val isPack = media.episodeRange?.let { it.knownSorts.take(2).count() != 1 } == true
        return TorrentLineage(
            media.mediaSourceId,
            packMediaId = media.mediaId.takeIf { isPack },
            alliance = alliance,
            resolution = media.properties.resolution,
            subtitleLanguageIds = media.properties.subtitleLanguageIds.sorted(),
        )
    }

    override fun continues(lineage: PlaybackLineage, candidate: Media): Boolean {
        if (lineage !is TorrentLineage || candidate.kind != MediaSourceKind.BitTorrent) return false
        if (lineage.packMediaId != null && candidate.mediaId == lineage.packMediaId) return true
        return candidate.properties.alliance == lineage.alliance &&
                candidate.properties.resolution == lineage.resolution &&
                candidate.properties.subtitleLanguageIds.sorted() == lineage.subtitleLanguageIds
    }

    override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest): LineageResolution =
        if (lineage is TorrentLineage) refetch(instances, lineage, request, ::continues) else LineageResolution.NotFound
}

/**
 * 候选里没有 (源被暂停前没查到、超长番每集重查等): 让记下线索的那个源单独查一次这一集, 挑线索上的.
 * 源没启用、查询失败时为 [LineageResolution.NotFound] (线索留着).
 */
private suspend fun refetch(
    instances: Flow<List<MediaSourceInstance>>,
    lineage: PlaybackLineage,
    request: MediaFetchRequest,
    continues: (PlaybackLineage, Media) -> Boolean,
): LineageResolution {
    val source = instances.first().firstOrNull { it.isEnabled && it.mediaSourceId == lineage.mediaSourceId }?.source
        ?: return LineageResolution.NotFound
    val medias = try {
        source.fetch(request).results.map { it.media }.toList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Refetching ${lineage.mediaSourceId} for the playback lineage failed" }
        return LineageResolution.NotFound
    }
    val media = medias.firstOrNull { continues(lineage, it) && it.coversEpisodeOf(request) } ?: return LineageResolution.NotFound
    return LineageResolution.Found(media)
}

private val logger = logger<WebSourceContinuation>()
