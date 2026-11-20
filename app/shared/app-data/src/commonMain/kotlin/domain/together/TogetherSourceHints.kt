/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle
import me.him188.ani.app.domain.media.ExternalLink
import me.him188.ani.app.domain.media.ExternalLinkMedia
import me.him188.ani.app.domain.media.continuation.DriveFolderLineage
import me.him188.ani.app.domain.media.continuation.DriveShareLineage
import me.him188.ani.app.domain.media.continuation.EpisodeContinuation
import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import me.him188.ani.app.domain.media.continuation.PlaybackLineageSelection
import me.him188.ani.app.domain.media.continuation.TorrentLineage
import me.him188.ani.app.domain.media.continuation.WebSourceLineage
import me.him188.ani.app.domain.media.continuation.continuationTarget
import me.him188.ani.app.domain.media.resolver.WebViewDeferral
import me.him188.ani.app.domain.media.selector.SelectOrigin
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.source.MediaData

/**
 * 一个人交给房间的片源: 正在播的地址 ([link], 别人可以直接播) 与播放线索 ([lineage], 别人在自己的同名数据源里找同一个资源)。
 * 两样各自能交才有。
 */
internal data class TogetherSourceHint(val link: ExternalLink?, val lineage: PlaybackLineage?)

/** 一起看里交来的播放地址在 [ExternalLinkMedia] 里的来源标记 (界面按它给数据源名) */
const val TOGETHER_LINK_ORIGIN_ID = "together"

/**
 * 一起看的片源共享 (docs/contributing/code/together.md「片源共享」): 各人的数据源不一样, 同一个源在不同网络下也不一定都拿得到播放地址。
 * 所以谁的片源先装好, 就把它交给房间 ([TogetherWatchingInfo.link] / [TogetherWatchingInfo.lineage]); 别人这一集的片源还没装好时拿来用:
 *
 * - **播放地址** ([ExternalLink], 见 [ExternalLinkMedia]): 直接播, 不需要本机有那个数据源, 也不走「开站点页面、解析出地址」那一步。
 *   自己还没选上源、或自动选上的那条还没装好 (还在解析、解析失败) 时就换成它; 它播不了 (地址绑了 IP、过期了) 就回到原来那条,
 *   原来没选上的照常选源 ([ExternalLinkMedia.OnError.FALL_BACK])。每个地址只试一次; 一个站点的地址在本机播不了过, 之后一阵 (换了集也是)
 *   不再试那个站点交来的 ([ExternalLinkMedia.failedHereRecently]): 多半是本机的网络连不上它。
 * - **播放线索** ([PlaybackLineage]): 没有能交的地址时 (网盘分享: 地址绑着账号) 的退路, 自己还没选上源时顺着它在自己的同名数据源里找
 *   ([PlaybackLineageSelection.selectAlong])。
 *
 * 不用别人的: 自己选的是本机缓存 (最快最稳)、用户自己手动选的、已经装好在播的、已经在用别人交来的地址的。
 * 不交的: BT (一起看时速度不稳)、网盘里自己的文件与取出来的地址、自建媒体服务器、本机代理与局域网地址 (见 [ExternalLinkMedia.shareableOf])。
 * 房主的在前, 再看其他人的。顺带两边播的是同一个版本, 片头长短、插没插广告都一样, 按秒对齐才对得准。
 */
internal class TogetherSourceHints(
    private val subjectId: Int,
    private val selection: PlaybackLineageSelection?,
    private val continuation: EpisodeContinuation?,
) {
    /**
     * 这一集正在播的资源能交给房间的; 片源装好之后才有, 没装好或两样都不能交时为 null.
     * 后台换源时 ([handingOver], 见 PlaybackHandover) 选中的已经是新的、播放器里还是旧的, 两样凑不成一对: 不变, 换下来之后再按新的算.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shared(
        episodeSession: EpisodeSession,
        player: MediampPlayer,
        loadingStates: Flow<VideoLoadingState>,
        handingOver: Flow<Boolean> = flowOf(false),
    ): Flow<TogetherSourceHint?> =
        combine(
            episodeSession.fetchSelectFlow.flatMapLatest { it?.mediaSelector?.selected ?: flowOf(null) },
            loadingStates,
            player.mediaData,
        ) { media, loading, data -> if (media != null && data != null && loading is VideoLoadingState.Succeed) media to data else null }
            .combine(handingOver) { playing, busy -> playing.takeUnless { busy } to busy }
            .filter { (_, busy) -> !busy }
            .map { (playing, _) -> playing }
            .distinctUntilChanged()
            .mapLatest { playing -> playing?.let { (media, data) -> hintOf(media, data) } }

    private suspend fun hintOf(media: Media, data: MediaData): TogetherSourceHint? {
        val link = ExternalLinkMedia.shareableOf(media, data)
        // 别处交来的地址不是本机的数据源, 记不出线索
        val lineage = if (ExternalLinkMedia.isExternalLink(media)) null else lineageOf(media)?.shareableForTogether()
        return if (link == null && lineage == null) null else TogetherSourceHint(link, lineage)
    }

    private suspend fun lineageOf(media: Media): PlaybackLineage? = try {
        continuation?.lineageOf(media.continuationTarget)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Together: could not tell the lineage of ${media.mediaId}" }
        null
    }

    /** 自己这一集的片源还没装好时, 用房间里别人交来的 (见类说明) */
    suspend fun follow(episodeSession: EpisodeSession, manager: TogetherManager, loadingStates: Flow<VideoLoadingState>) {
        episodeSession.fetchSelectFlow.collectLatest { fetchSelect ->
            if (fetchSelect == null) return@collectLatest
            val episode = episodeSession.infoBundleFlow.filterNotNull().first().episodeInfo
            val selector = fetchSelect.mediaSelector
            coroutineScope {
                val loading = loadingStates.stateIn(this)
                // 用户自己手动选上的那一条: 不顶
                val manual = MutableStateFlow<Media?>(null)
                launch { selector.events.onSelect.collect { event -> manual.value = event.media.takeIf { event.origin == SelectOrigin.MANUAL } } }
                val tried = HashSet<Any>()
                combine(hintsOf(manager, episodeSession.episodeId), selector.selected, loading) { hints, _, _ -> hints }
                    .conflate()
                    .collect { hints -> use(hints, tried, episode, fetchSelect, loading, manual) }
            }
        }
    }

    /** 照 [hints] (房主的在前) 试一遍: 能用别人交来的地址就换上, 否则还没选上源时顺着线索找。[tried] 记着试过的, 每个只试一次 */
    internal suspend fun use(
        hints: List<TogetherSourceHint>,
        tried: MutableSet<Any>,
        episode: EpisodeInfo,
        fetchSelect: MediaFetchSelectBundle,
        loading: StateFlow<VideoLoadingState>,
        manual: StateFlow<Media?>,
    ) {
        val selector = fetchSelect.mediaSelector
        for (hint in hints) {
            val link = hint.link
            val current = selector.selected.value
            if (link != null && link !in tried && ExternalLinkMedia.failedHereRecently(link)) {
                tried += link
                logger.info { "Together: skipping the link another member handed over, its site failed to play here recently" }
            }
            if (link != null && link !in tried && ExternalLinkMedia.accepts(link) && replaceable(current, loading.value, manual.value)) {
                // 顶掉的那条没坏 (只是还没装好): 别让换源把它拉黑, 交来的地址播不了时还要换回它
                if (current != null) WebViewDeferral.markSwitchingAway(current.mediaId)
                val media = ExternalLinkMedia.create(TOGETHER_LINK_ORIGIN, link, replaced = current)
                if (selector.selectAutomatically(media, expectedSelection = current) != null) {
                    tried += link
                    logger.info { "Together: playing the link another member handed over" + if (current != null) ", instead of ${current.mediaId}" else "" }
                    return
                }
                if (current != null) WebViewDeferral.takeSwitchingAway(current.mediaId)
            }
            val lineage = hint.lineage
            if (lineage != null && selection != null && selector.selected.value == null && tried.add(lineage)) {
                val followed = try {
                    selection.selectAlong(lineage, subjectId, episode, fetchSelect.mediaFetchSession, selector)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Together: following the lineage of another member failed" }
                    false
                }
                if (followed) {
                    logger.info { "Together: picked the release another member is playing (${lineage.mediaSourceId})" }
                    return
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun hintsOf(manager: TogetherManager, episodeId: Int): Flow<List<TogetherSourceHint>> =
        manager.state.flatMapLatest { state ->
            val session = (state as? TogetherState.InRoom)?.session ?: return@flatMapLatest flowOf(emptyList())
            session.snapshot.map { sourceHintsOf(it, session.selfId, subjectId, episodeId) }
        }.distinctUntilChanged()

    private companion object {
        private val logger = logger<TogetherSourceHints>()
        private val TOGETHER_LINK_ORIGIN = ExternalLinkMedia.Origin(TOGETHER_LINK_ORIGIN_ID, ExternalLinkMedia.OnError.FALL_BACK)
    }
}

/**
 * 现在选着的 [current] 能不能换成别人交来的地址 (见 [TogetherSourceHints]): 没选上的能; 自动选上、还没装好的 (还在解析、解析失败) 能;
 * 本机缓存、用户手动选的 ([manual])、已经装好的、本来就是别人交来的不能。
 */
internal fun replaceable(current: Media?, loading: VideoLoadingState, manual: Media?): Boolean = when {
    current == null -> true
    current.kind == MediaSourceKind.LocalCache -> false
    ExternalLinkMedia.isExternalLink(current) -> false
    current == manual -> false
    loading is VideoLoadingState.Succeed -> false
    else -> true
}

/** 能交给房间里别人的线索 (见 [TogetherSourceHints]): BT 不交, 自己网盘里的文件夹不交 */
internal fun PlaybackLineage.shareableForTogether(): PlaybackLineage? = when (this) {
    is TorrentLineage, is DriveFolderLineage -> null
    is WebSourceLineage, is DriveShareLineage -> this
}

/** 房间里别人 (不算自己与掉线的) 在 [subjectId] 的 [episodeId] 上交出来的片源, 房主的在前, 重复的去掉 */
internal fun sourceHintsOf(snapshot: TogetherRoomSnapshot, selfId: String, subjectId: Int, episodeId: Int): List<TogetherSourceHint> =
    snapshot.members
        .filter { it.memberId != selfId && it.state != TogetherMemberState.DISCONNECTED }
        .sortedByDescending { it.isHost }
        .mapNotNull { member ->
            val watching = member.watching?.takeIf { it.subjectId == subjectId && it.episodeId == episodeId } ?: return@mapNotNull null
            if (watching.link == null && watching.lineage == null) null else TogetherSourceHint(watching.link, watching.lineage)
        }
        .distinct()
