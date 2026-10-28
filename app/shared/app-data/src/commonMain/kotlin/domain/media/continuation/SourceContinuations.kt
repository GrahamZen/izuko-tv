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
import me.him188.ani.app.domain.mediasource.web.SubjectPageMediaSource
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException

/**
 * 在线源的资源 (网页抓取、规则源、JSON 接口、媒体服务器等; 网盘由 [me.him188.ani.app.domain.mediasource.clouddrive.DriveEpisodeContinuation] 管):
 * 顺着同一个源、站点上同一个条目、同一条线路找 ([WebSourceLineage]). 排在网盘后面, 网盘资源先被它认领.
 * 线索记着条目页地址时 (调试开关 `lineageOpenPage`), 下一集直接打开这一页找 ([SubjectPageMediaSource]), 不等这个源整轮搜完.
 */
class WebSourceContinuation(
    private val instances: Flow<List<MediaSourceInstance>>,
    /** 某个源的一集 (播放地址) 出自哪个条目页 (搜索缓存里查, 见 SelectorMediaSourceEpisodeCacheRepository.subjectUrlOf) */
    private val subjectUrlOf: suspend (mediaSourceId: String, playUrl: String) -> String? = { _, _ -> null },
) : EpisodeContinuation {
    override fun owns(media: Media): Boolean = media.kind == MediaSourceKind.WEB

    override suspend fun lineageOf(media: Media): PlaybackLineage? {
        val subjectName = media.properties.subjectName?.takeIf { it.isNotBlank() } ?: return null
        return WebSourceLineage(
            media.mediaSourceId, subjectName, media.properties.alliance,
            subjectUrl = media.originalUrl.takeIf { it.isNotEmpty() }?.let { subjectUrlOf(media.mediaSourceId, it) },
        )
    }

    override suspend fun updated(current: PlaybackLineage, playing: Media): PlaybackLineage? {
        if (current !is WebSourceLineage || !continues(current, playing)) return null
        val playingLineage = lineageOf(playing) as? WebSourceLineage ?: return null
        return playingLineage.takeIf { it.subjectUrl != null && it.subjectUrl != current.subjectUrl }
    }

    override fun isQuickToResolve(lineage: PlaybackLineage): Boolean =
        lineage is WebSourceLineage && lineage.subjectUrl != null && DevSwitches.lineageOpenPage.value

    override fun continues(lineage: PlaybackLineage, candidate: Media): Boolean =
        lineage is WebSourceLineage && candidate.kind == MediaSourceKind.WEB &&
                candidate.mediaSourceId == lineage.mediaSourceId &&
                candidate.properties.subjectName == lineage.subjectName &&
                candidate.properties.alliance == lineage.channel

    override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest): LineageResolution {
        if (lineage !is WebSourceLineage) return LineageResolution.NotFound
        // 选中的条目页一般不会变 (连载中的番页上多出新的集): 直接打开记下的那一页找这一集.
        // 页上没有这一集 (新的一集还没更新进来) 时这个源整轮搜多半还是这一页, 不再搜; 页打不开时交给查询会话里这个源的结果
        return if (isQuickToResolve(lineage)) openSubjectPage(lineage, request) else refetch(instances, lineage, request, ::continues)
    }

    private suspend fun openSubjectPage(lineage: WebSourceLineage, request: MediaFetchRequest): LineageResolution {
        val subjectUrl = lineage.subjectUrl ?: return LineageResolution.Unavailable
        val source = instances.first().firstOrNull { it.isEnabled && it.mediaSourceId == lineage.mediaSourceId }?.source as? SubjectPageMediaSource
            ?: return LineageResolution.Unavailable
        val media = try {
            source.mediaOnSubjectPage(subjectUrl, lineage.subjectName, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Opening the subject page $subjectUrl of ${lineage.mediaSourceId} for the playback lineage failed" }
            return LineageResolution.Unavailable
        }
        val found = media.firstOrNull { continues(lineage, it) && it.coversEpisodeOf(request) } ?: run {
            logger.info { "Subject page $subjectUrl of ${lineage.mediaSourceId} has no episode ${request.episodeSort} on the lineage" }
            return LineageResolution.NotFound
        }
        return LineageResolution.Found(found)
    }
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
