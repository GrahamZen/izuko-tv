/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.data.repository.media.PlaybackLineageRepository
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.fetch.awaitCompletedResults
import me.him188.ani.app.domain.media.fetch.isFinal
import me.him188.ani.app.domain.media.fetch.isPaused
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaAutoSelectSnapshot
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.MediaSourceSelectionSnapshot
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.Duration.Companion.seconds

/**
 * 选源的第一步: 顺着这部番的播放线索 ([PlaybackLineage]) 找这一集, 先于浏览记忆与自动选源.
 *
 * 1. 这一集有能播的缓存时用缓存: 线索上的缓存直接选上; 只有别的版本的缓存时返回 false, 由自动选源选它 (它第一步就选缓存).
 *    从缓存页点进来的就是要播缓存, 「有缓存时直接播, 不搜索」也只等缓存. 先看本机有没有这一集的缓存记录 ([EpisodeCacheRecords],
 *    只读元数据): 没有就不等本地缓存源, 直接往下; 有才等它查完 (平时毫秒级). 等不到 (没下完的 BT 缓存要等 BT 服务连上)
 *    或选源器没准备好时也返回 false, 交给自动选源: 有缓存时就该用缓存, BT 缓存没下完也能边下边播;
 * 2. 在查完了的数据源的候选里找线索上的资源 (查询会话按条目复用, 切集时多半已经在手上);
 * 3. 还没有: 快的线索 ([EpisodeContinuation.isQuickToResolve], 网盘只列一个文件夹, 在线源只开记下的条目页) 马上去取,
 *    同时看它的数据源在会话里边查边交出的, 谁先给出线索上的资源用谁; 直接去取说没有这一集、线索失效都就此定下, 不再另起一轮查询,
 *    这回取不成 ([LineageResolution.Unavailable]: 页打不开) 时只等数据源那一路;
 *    别的线索等它的数据源: 它边查边交出的里有线索上的资源就用 (拆分季除外), 否则等它查完 (被暂停的照用暂停前拿到的) 再找一次, 这一轮正常查完了还没有就不再重搜 (重搜也是同样的结果),
 *    数据源被暂停、出错或等不到时才直接去取 ([EpisodeContinuation.resolve]);
 * 4. 直接取回来的资源不在候选里, 先按选源器的过滤规则判一次 (排除的字幕组、早于本集开播、条目名、别的季…), 被排除就当没找到;
 *    线索彻底失效时忘掉它; 自动选源记住的源就是线索所在的源时一并忘掉 ([rememberedSource]): 那个版本已经没了,
 *    接着等这个源从头搜完 (固定分享的合集要重列十几个分享) 多半还是找不到, 交给自动选源照常选, 选上的再记成记住的源.
 *
 * 找到就自动选上 (不改选源偏好) 并返回 true; 找不到返回 false, 由调用方照常选 —— 选源设置只在这时才起作用.
 * 自动选上别的版本的缓存不换线索 (见 PlaybackLineageExtension), 下一集没有缓存时照样顺着线索找.
 * 播放出错换源时也先在线索上换 ([switchOnError]).
 */
class PlaybackLineageSelection(
    private val repository: PlaybackLineageRepository,
    private val continuation: EpisodeContinuation,
    private val cacheRecords: EpisodeCacheRecords,
    private val rememberedSource: RememberedSourceForgetter = RememberedSourceForgetter.None,
) {
    suspend fun select(subjectId: Int, episode: EpisodeInfo, session: MediaFetchSession, selector: MediaSelector): Boolean {
        if (selector.selected.value != null) return false
        val lineage = repository.lineageOf(subjectId) ?: return false
        val caches = playableCaches(subjectId, episode, session, selector) ?: return false
        if (caches.isNotEmpty()) {
            val onLineage = caches.firstOrNull { continuation.continues(lineage, it) } ?: run {
                logger.info { "Episode ${episode.sort} of subject $subjectId has a cache of another version, selecting the cache as usual" }
                return false
            }
            if (selector.selectAutomatically(onLineage, expectedSelection = null) == null) return false
            logger.info { "Continued playback lineage of subject $subjectId with its cache: ${onLineage.mediaId}" }
            return true
        }
        val media = findOnLineage(subjectId, lineage, episode, session, selector) ?: run {
            logger.info { "Episode ${episode.sort} of subject $subjectId is not on its playback lineage, selecting as usual" }
            return false
        }
        if (selector.selectAutomatically(media, expectedSelection = null) == null) return false
        logger.info { "Continued playback lineage of subject $subjectId: ${media.mediaId}" }
        return true
    }

    /** 这一集在线索上的网络资源 (见类说明 2~4); 找不到为 null. */
    private suspend fun findOnLineage(
        subjectId: Int,
        lineage: PlaybackLineage,
        episode: EpisodeInfo,
        session: MediaFetchSession,
        selector: MediaSelector,
    ): Media? {
        pick(lineage, candidatesOf(session, selector) ?: return null)?.let { return it }
        // 查询会话按条目复用, 记的是进播放页那一集的请求: 换成这一集
        val request = session.latestRequest.first().forEpisode(episode)
        val source = session.mediaSourceResults.firstOrNull { it.mediaSourceId == lineage.mediaSourceId }
        if (continuation.isQuickToResolve(lineage)) return quickOrFromSession(subjectId, lineage, request, source, session, selector)
        if (source != null && !source.state.value.isFinal) awaitOnSource(lineage, source, session, selector)?.let { return it }
        if (source?.state?.value is MediaSourceFetchState.Succeed) return null
        // 还在会话里查: 直接去取是把同一套请求再发一遍 (开播时两边一起抢 CPU), 交给自动选源接着等它
        if (source != null && !source.state.value.isFinal && !source.state.value.isPaused) {
            logger.info { "Lineage source ${lineage.mediaSourceId} of subject $subjectId is still searching after $SOURCE_TIMEOUT, leaving it to auto select" }
            return null
        }
        return accepted(subjectId, lineage, withTimeoutOrNull(RESOLVE_TIMEOUT) { continuation.resolve(lineage, request) }, session, selector)
    }

    /**
     * 快取的线索: 直接去取与查询会话里这个源 [source] 的结果一起等, 谁先给出线索上的资源用谁, 另一路取消
     * (会话读搜索缓存时常比开页快; 记下的页打开慢时也不必干等它). 直接去取说没有这一集、线索失效或取回的被选源器排除时就此定下;
     * 这回取不成 ([LineageResolution.Unavailable]) 或等不到时只等会话那一路. 都没有时为 null, 不另起一轮查询 (交给自动选源).
     */
    private suspend fun quickOrFromSession(
        subjectId: Int,
        lineage: PlaybackLineage,
        request: MediaFetchRequest,
        source: MediaSourceFetchResult?,
        session: MediaFetchSession,
        selector: MediaSelector,
    ): Media? = coroutineScope {
        val quick = async { withTimeoutOrNull(RESOLVE_TIMEOUT) { continuation.resolve(lineage, request) } }
        val fromSession = async {
            if (source != null && !source.state.value.isFinal) awaitOnSource(lineage, source, session, selector) else null
        }
        var quickPending = true
        var sessionPending = true
        try {
            while (quickPending || sessionPending) {
                val outcome = select<QuickOutcome> {
                    if (quickPending) quick.onAwait { resolution ->
                        quickPending = false
                        if (resolution == LineageResolution.Unavailable || resolution == null) {
                            logger.info { "Quick lineage of subject $subjectId is unavailable this time, waiting for its source" }
                            QuickOutcome.Pending
                        } else {
                            QuickOutcome.Settled(accepted(subjectId, lineage, resolution, session, selector))
                        }
                    }
                    if (sessionPending) fromSession.onAwait { media ->
                        sessionPending = false
                        if (media != null) QuickOutcome.Settled(media) else QuickOutcome.Pending
                    }
                }
                if (outcome is QuickOutcome.Settled) return@coroutineScope outcome.media
            }
            null
        } finally {
            quick.cancel()
            fromSession.cancel()
        }
    }

    /** [quickOrFromSession] 里一路的结果: 定下了 (可能是没有), 或还要等另一路 */
    private sealed interface QuickOutcome {
        data class Settled(val media: Media?) : QuickOutcome
        data object Pending : QuickOutcome
    }

    /**
     * 等查询会话里线索的数据源 [source] (最多 [SOURCE_TIMEOUT]): 它边查边交出的里有线索上的资源就用 ([pickFromWorking]),
     * 否则等它查完 (被暂停的照用暂停前拿到的) 再在候选里找一次. 没有为 null.
     */
    private suspend fun awaitOnSource(
        lineage: PlaybackLineage,
        source: MediaSourceFetchResult,
        session: MediaFetchSession,
        selector: MediaSelector,
    ): Media? {
        var early: Media? = null
        withTimeoutOrNull(SOURCE_TIMEOUT) {
            combine(source.state, source.results) { state, results -> state to results }.first { (state, results) ->
                state.isFinal || DevSwitches.lineageWorkingResults.value && results.isNotEmpty() &&
                        pickFromWorking(lineage, source, session, selector)?.also { early = it } != null
            }
        }
        early?.let { return it }
        return pick(lineage, candidatesOf(session, selector) ?: return null)
    }

    /** 直接取回的结果: 选源器排除的当没找到, 线索彻底失效时忘掉它. */
    private suspend fun accepted(
        subjectId: Int,
        lineage: PlaybackLineage,
        resolution: LineageResolution?,
        session: MediaFetchSession,
        selector: MediaSelector,
    ): Media? =
        when (resolution) {
            is LineageResolution.Found -> resolution.media.takeIf { passesFilters(it, session, selector) } ?: run {
                logger.info { "Playback lineage of subject $subjectId resolved ${resolution.media.mediaId}, but the selector excludes it" }
                null
            }

            LineageResolution.Gone -> {
                logger.info { "Playback lineage of subject $subjectId is gone, forgetting it: $lineage" }
                repository.setLineage(subjectId, null)
                rememberedSource.forgetIf(subjectId, lineage.mediaSourceId)
                null
            }

            LineageResolution.NotFound, LineageResolution.Unavailable, null -> null
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
     * 这一集能播的缓存 (不等网络); 拿不准时为 null, 交给自动选源. 本机没有这一集的缓存记录时不等本地缓存源;
     * 有记录才等它查完, 等不到或选源器没准备好算拿不准. 没下完不能播的缓存 (在线源的 m3u8) 被选源器排除, 不在里面.
     */
    private suspend fun playableCaches(
        subjectId: Int,
        episode: EpisodeInfo,
        session: MediaFetchSession,
        selector: MediaSelector,
    ): List<Media>? {
        val cacheSources = session.mediaSourceResults.filter { it.kind == MediaSourceKind.LocalCache }
        if (cacheSources.isEmpty() || !cacheRecords.hasRecord(subjectId, episode)) return emptyList()
        withTimeoutOrNull(CACHE_TIMEOUT) { cacheSources.forEach { it.awaitCompletedResults() } } ?: run {
            val pending = cacheSources.filter { !it.state.value.isFinal }.map { it.mediaSourceId }
            logger.info { "Episode ${episode.sort} of subject $subjectId has a cache, but $pending did not finish in $CACHE_TIMEOUT, leaving it to auto select" }
            return null
        }
        val candidates = candidatesOf(session, selector) ?: run {
            logger.info { "Episode ${episode.sort} of subject $subjectId has a cache, but the selector was not ready in $SOURCE_TIMEOUT, leaving it to auto select" }
            return null
        }
        return candidates.filter { it.kind == MediaSourceKind.LocalCache }
    }

    /**
     * 这一集的候选 (已按集与排除条件过滤、按既有规则排好): 查完了的数据源给的结果. 选源器的上下文没准备好 (剧集、完结状态) 时为 null.
     */
    private suspend fun candidatesOf(session: MediaFetchSession, selector: MediaSelector): List<Media>? =
        candidatesOf(sourceSnapshots(session), selector)

    private suspend fun candidatesOf(sources: List<MediaSourceSelectionSnapshot>, selector: MediaSelector): List<Media>? =
        snapshotOf(sources, selector)?.candidates?.map(MaybeExcludedMedia.Included::result)

    private suspend fun snapshotOf(sources: List<MediaSourceSelectionSnapshot>, selector: MediaSelector): MediaAutoSelectSnapshot? =
        withTimeoutOrNull(SOURCE_TIMEOUT) {
            selector.autoSelectSnapshots(flowOf(sources)).first { it.context.hasEpisode && it.context.allFieldsLoaded() }
        }

    /**
     * 还在查的线索数据源 [source] 已经交出的 (每开完一页交一页, 见 KeywordSearchScope.emitEarly) 里线索上的资源, 没有为 null:
     * 不等它把别的页开完. 拆分季要看整季各页的集号才认得准哪一集, 不用没查完的 (同 MediaAutoSelector).
     */
    private suspend fun pickFromWorking(
        lineage: PlaybackLineage,
        source: MediaSourceFetchResult,
        session: MediaFetchSession,
        selector: MediaSelector,
    ): Media? {
        val partial = source.results.first()
        val sources = sourceSnapshots(session).map { if (it.mediaSourceId == source.mediaSourceId) it.copy(results = partial) else it }
        val snapshot = snapshotOf(sources, selector) ?: return null
        if (snapshot.context.subjectSeriesInfo?.splitSeason != null) return null
        return pick(lineage, snapshot.candidates.map(MaybeExcludedMedia.Included::result))
    }

    private suspend fun sourceSnapshots(session: MediaFetchSession): List<MediaSourceSelectionSnapshot> =
        session.mediaSourceResults.map { source ->
            val state = source.state.value
            MediaSourceSelectionSnapshot(source.mediaSourceId, source.kind, state, if (state.isFinal) source.results.first() else emptyList())
        }

    /** 直接取回来的 [media] (不在候选里) 和候选一起过选源器的过滤规则, 还在不在. */
    private suspend fun passesFilters(media: Media, session: MediaFetchSession, selector: MediaSelector): Boolean {
        val resolved = MediaSourceSelectionSnapshot(media.mediaSourceId, media.kind, MediaSourceFetchState.Succeed(0), listOf(media))
        val candidates = candidatesOf(sourceSnapshots(session) + resolved, selector) ?: return false
        return candidates.any { it.mediaId == media.mediaId }
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

        /** 有缓存记录时等本地缓存的数据源查完 (平时毫秒级; 同 AutoSelectExtension 的缓存检查) */
        val CACHE_TIMEOUT = 2.seconds
    }
}
