/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.data.repository.media.PlaybackLineageRepository
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.isFinal
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.MediaSourceSelectionSnapshot
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.Duration.Companion.seconds

/**
 * 选源的第一步: 顺着这部番的播放线索 ([PlaybackLineage]) 找这一集, 先于本地缓存检查、浏览记忆与自动选源.
 *
 * 1. 等线索的数据源查完 (被暂停的照用暂停前拿到的), 在这一集的候选里找线索上的资源: 同一条线索的缓存最先 (缓存下来的就是这个版本),
 *    再是网络上的 —— 它有下载好的缓存时用缓存;
 * 2. 候选里没有: 按线索直接去取 ([EpisodeContinuation.resolve]); 线索彻底失效时忘掉它.
 *
 * 找到就自动选上 (不改选源偏好) 并返回 true; 找不到返回 false, 由调用方照常选 —— 选源设置只在这时才起作用.
 * 播放出错换源时也先在线索上换 ([switchOnError]).
 */
class PlaybackLineageSelection(
    private val repository: PlaybackLineageRepository,
    private val continuation: EpisodeContinuation,
) {
    suspend fun select(subjectId: Int, episode: EpisodeInfo, session: MediaFetchSession, selector: MediaSelector): Boolean {
        if (selector.selected.value != null) return false
        val lineage = repository.lineageOf(subjectId) ?: return false
        withTimeoutOrNull(SOURCE_TIMEOUT) {
            session.mediaSourceResults.firstOrNull { it.mediaSourceId == lineage.mediaSourceId }?.state?.first { it.isFinal }
        }
        val candidates = candidatesOf(session, selector) ?: return false
        val media = pick(lineage, candidates) ?: run {
            // 查询会话按条目复用, 记的是进播放页那一集的请求: 换成这一集
            val request = session.latestRequest.first().forEpisode(episode)
            when (val resolution = withTimeoutOrNull(RESOLVE_TIMEOUT) { continuation.resolve(lineage, request) }) {
                is LineageResolution.Found -> cacheOf(resolution.media, candidates) ?: resolution.media
                LineageResolution.Gone -> {
                    logger.info { "Playback lineage of subject $subjectId is gone, forgetting it: $lineage" }
                    repository.setLineage(subjectId, null)
                    null
                }

                LineageResolution.NotFound, null -> null
            }
        } ?: run {
            logger.info { "Episode ${episode.sort} of subject $subjectId is not on its playback lineage, selecting as usual" }
            return false
        }
        if (selector.selectAutomatically(media, expectedSelection = null) == null) return false
        logger.info { "Continued playback lineage of subject $subjectId: ${media.mediaId}" }
        return true
    }

    /**
     * 播放出错换源的第一步: 手上的候选里还有线索上别的资源 (没拉黑的, 例如同一个种子被另一个站搜到) 就换它; 不直接去取, 不等数据源.
     * 换了返回 true; 否则由调用方照常换源.
     */
    suspend fun switchOnError(subjectId: Int, session: MediaFetchSession, selector: MediaSelector, blacklist: Set<String>, failed: Media?): Boolean {
        val lineage = repository.lineageOf(subjectId) ?: return false
        val candidates = candidatesOf(session, selector)?.filter { it.mediaId !in blacklist && it != failed } ?: return false
        val media = pick(lineage, candidates) ?: return false
        if (selector.selectAutomatically(media, expectedSelection = failed) == null) return false
        logger.info { "Player errored, switched along the playback lineage of subject $subjectId: ${media.mediaId}" }
        return true
    }

    /**
     * 这一集的候选 (已按集与排除条件过滤、按既有规则排好): 查完了的数据源给的结果. 选源器的上下文没准备好 (剧集、完结状态) 时为 null.
     */
    private suspend fun candidatesOf(session: MediaFetchSession, selector: MediaSelector): List<Media>? {
        val sources = session.mediaSourceResults.map { source ->
            val state = source.state.value
            MediaSourceSelectionSnapshot(source.mediaSourceId, source.kind, state, if (state.isFinal) source.results.first() else emptyList())
        }
        val snapshot = withTimeoutOrNull(SOURCE_TIMEOUT) {
            selector.autoSelectSnapshots(flowOf(sources)).first { it.context.hasEpisode && it.context.allFieldsLoaded() }
        } ?: return null
        return snapshot.candidates.map(MaybeExcludedMedia.Included::result)
    }

    private fun pick(lineage: PlaybackLineage, candidates: List<Media>): Media? {
        val onLineage = candidates.filter { continuation.continues(lineage, it) }
        onLineage.firstOrNull { it.kind == MediaSourceKind.LocalCache }?.let { return it }
        // 同一个文件可能被几个数据源搜到 (同一个分享): 记下线索的那个源优先
        val network = onLineage.firstOrNull { it.mediaSourceId == lineage.mediaSourceId } ?: onLineage.firstOrNull() ?: return null
        return cacheOf(network, candidates) ?: network
    }

    /** [media] 下载好的缓存 (候选里来源就是它的那条). */
    private fun cacheOf(media: Media, candidates: List<Media>): Media? =
        candidates.firstOrNull { it is CachedMedia && it.origin.mediaId == media.mediaId }

    private fun MediaFetchRequest.forEpisode(episode: EpisodeInfo): MediaFetchRequest = copy(
        episodeId = episode.episodeId.toString(),
        episodeSort = episode.sort,
        episodeName = episode.displayName,
        episodeEp = episode.ep,
    )

    private companion object {
        val logger = logger<PlaybackLineageSelection>()

        /** 等线索的数据源查完、选源器准备好: 网盘列一个文件夹几秒内; 等不到就用手上的 */
        val SOURCE_TIMEOUT = 10.seconds

        /** 直接去取那一集 (列一个文件夹) */
        val RESOLVE_TIMEOUT = 10.seconds
    }
}
