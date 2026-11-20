/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle
import me.him188.ani.app.domain.player.SeekPreviewDecoderFault
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.player.httpErrorStatus
import me.him188.ani.app.domain.player.isDecoderPreempted
import me.him188.ani.app.domain.player.isNetworkFailure
import me.him188.ani.app.domain.player.isNotVideoResponse
import me.him188.ani.app.domain.player.isStreamInterrupted
import me.him188.ani.app.platform.NETWORK_SETTLE
import me.him188.ani.app.platform.NetworkStatus
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.coroutines.REJECTED_LINK_STATUSES
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.Buffering
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** 播放失败后怎么办, 由 [decidePlaybackFailure] 决定. */
internal enum class PlaybackFailureAction {
    /**
     * 不用管: 不是失败 (加载被取消), 换源也没用 (网页引擎不可用), 或跟这条资源无关 (离开播放页时释放 Surface 超时、
     * 在后台换源时旧的缓冲放完报的错).
     */
    IGNORE,

    /** 拖动预览换输出弄坏了解码器: 等它缓过来后原地重载, 不拉黑. */
    RELOAD_AFTER_SEEK_PREVIEW,

    /** 解码器被抢走: 原地重载一次, 不拉黑. */
    RELOAD_DECODER,

    /** 系统没网: 换哪个在线源都一样连不上. 不拉黑、不换源、不限时长, 恢复后原地重新取一次地址; 等的时候也隔一会试一次, 防系统误报. */
    WAIT_FOR_NETWORK,

    /**
     * 播起来过的资源中途读不下去了: 地址被拒绝 (多半是签名过期) 或连接断了 (数据源接着读也没接上).
     * 原地重新取一次地址从原位置接着放, 不拉黑.
     */
    REFRESH_LINK,

    /** 这条资源放不了: 拉黑并换下一个. 认不出的都走这里, 放不出来比换源更糟. */
    SWITCH_SOURCE,
}

/**
 * @property canRefreshLink 这条资源播起来过, 而且最近没为它重新取过地址
 * @property canReloadDecoder 这条资源还没因为解码器被抢重载过
 * @property networkDown 系统没网, 或这次失败发生在刚断网的时候 (见 [isNetworkDownFor])
 * @property unreachable 数据源已经确认这条资源接不上了 (断流后接着读、换地址都没读到数据, 见 PlaybackHandover.unreachable), 并为它在后台换过源
 * @property handingOver 正在后台换源 (见 PlaybackHandover): 播放器里还是旧的那条, 选中的已经是新的了
 */
internal class PlaybackFailureFacts(
    val canRefreshLink: Boolean,
    val canReloadDecoder: Boolean,
    val networkDown: Boolean,
    val unreachable: Boolean = false,
    val handingOver: Boolean = false,
)

/**
 * 所有「播放失败后怎么办」的判断都在这里. 换源、数据源画像 ([SourceProfileExtension]) 都按它的结论: 只有 [PlaybackFailureAction.SWITCH_SOURCE] 算这条资源的错.
 *
 * 先后有讲究: 等网前面的几种与网络无关.
 */
internal fun decidePlaybackFailure(error: PlayerLoadError, facts: PlaybackFailureFacts): PlaybackFailureAction = when {
    // 后台换源时播放器报的错是旧的那条的 (缓冲放完了), 新的已经在装: 不算新的失败, 也不再换一次
    facts.handingOver && error.state == null -> PlaybackFailureAction.IGNORE
    error.state == VideoLoadingState.WaitingForNetwork -> PlaybackFailureAction.WAIT_FOR_NETWORK
    (error.state as? VideoLoadingState.Failed)?.let { PlayerLoadErrorHandler.canRecoverBySwitching(it) } == false -> PlaybackFailureAction.IGNORE
    error.isPlayerLifecycleError() -> PlaybackFailureAction.IGNORE
    SeekPreviewDecoderFault.isFault(error.cause) -> PlaybackFailureAction.RELOAD_AFTER_SEEK_PREVIEW
    isDecoderPreempted(error.cause) && facts.canReloadDecoder -> PlaybackFailureAction.RELOAD_DECODER
    facts.networkDown -> PlaybackFailureAction.WAIT_FOR_NETWORK
    // 数据源已经接着读、换地址都试过了, 也已经为它在后台换过源 (没换成, 比如当时没有别的资源): 原地重新加载也接不上, 接着换
    facts.unreachable -> PlaybackFailureAction.SWITCH_SOURCE
    facts.canRefreshLink && error.cause?.let { it.isLinkRejected() || it.isStreamInterrupted() } == true -> PlaybackFailureAction.REFRESH_LINK
    else -> PlaybackFailureAction.SWITCH_SOURCE
}

/**
 * 确认接不上后等缓冲只剩这么多再换源 (见 [PlaybackFailureRecovery.trackPlayback]): 留给找下一条、解析它 (直链即时, 网盘几秒, 要开网页现抓的十秒上下).
 */
private val SWITCH_AHEAD = 20.seconds

/** 断网恢复后这么久之内的网络类失败也算断网造成的: 解析、嗅探在断网时开始, 要等到超时才报 (实测恢复后才超时). */
private val RECENT_LOSS = 20.seconds

/** 网络没了之后, 连接先报「网络不可达」(路由先没), 系统稍后才报没网 (实测差不到一秒). */
private val SYSTEM_REPORT_LAG = 1500.milliseconds

/**
 * 等网时隔这么久不管系统怎么说都试一次: 系统误报没网时不至于一直等下去. 真没网时这一次很快失败, 接着等.
 */
private val NETWORK_RECHECK = 30.seconds

/**
 * 拖动预览换输出弄坏了解码器之后, 等这么久再原地重载: 坏掉的硬解要一会儿才缓得过来, 马上重开多半还是坏的.
 * 解码器还没缓过来时重开的也会坏, 认出的那一轮 ([SeekPreviewDecoderFault.FAULT_WINDOW]) 里每次都这样重试, 过了那一轮才换源.
 */
private val SEEK_PREVIEW_RELOAD_DELAY = 3.seconds

/**
 * 同一条资源至少隔这么久才再重新取一次地址. 签名地址的有效期以小时计, 刚取的就被拒绝说明不是过期; 断流也一样, 刚重取过又断说明不是暂时的.
 * 不限的话放到坏掉的那一段 (分片没了、文件残缺) 会出错、重取、放到同一处又出错, 没完没了.
 */
private val LINK_REFRESH_INTERVAL = 30.minutes

/** 地址被服务器拒绝, 或回的不是视频 (站点把过期的地址转到了网页). */
private fun Throwable.isLinkRejected(): Boolean = httpErrorStatus() in REJECTED_LINK_STATUSES || isNotVideoResponse()

/** 服务器回了 HTTP 错误状态或别的内容, 说明连得上. */
private fun Throwable.isServerResponse(): Boolean = httpErrorStatus() != null || isNotVideoResponse()

/** 连接层报了网络不可达 (`ENETUNREACH`). 网络好的时候也可能出现: 连 IPv6 地址而这个网络没有 IPv6 路由. */
private fun Throwable.isNetworkUnreachable(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < MAX_CAUSE_DEPTH) {
        val message = current.message.orEmpty()
        if (message.contains("ENETUNREACH") || message.contains("Network is unreachable")) return true
        current = current.cause?.takeIf { it !== current }
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 12

private fun PlayerLoadError.isNetworkKind(): Boolean =
    state == VideoLoadingState.ResolutionTimedOut || state == VideoLoadingState.NetworkError || cause?.isNetworkFailure() == true

/**
 * 这次失败看起来是断网造成的: 系统没网, 连接报了网络不可达, 或者刚断过网而这次是网络类失败. 服务器回了话的一律不算.
 * 系统有网时的网络类失败 (解析不出域名、连不上) 多半是那个站点的事, 算这条资源的错.
 *
 * @param handledLoss 已经为它等过网的那次断网, 不再算
 * @param trustUnreachable 单凭网络不可达就算断网
 */
internal fun NetworkStatus.isNetworkDownFor(
    error: PlayerLoadError,
    handledLoss: TimeMark? = null,
    trustUnreachable: Boolean = true,
): Boolean {
    // 服务器回了话, 网就是通的: 系统报的状态可能是误报, 不能只听它的
    if (error.cause?.isServerResponse() == true) return false
    if (!isAvailable.value) return true
    if (trustUnreachable && error.cause?.isNetworkUnreachable() == true) return true
    val lost = lastLostAt ?: return false
    return lost !== handledLoss && error.isNetworkKind() && lost.elapsedNow() <= RECENT_LOSS
}

/**
 * 同 [isNetworkDownFor], 用在不急着下结论的地方 (记数据源的失败、忘掉记住的源): 网络类失败先等系统跟上,
 * 这样断网刚开始、系统还没报的那一下也认得出.
 */
internal suspend fun NetworkStatus.confirmNetworkDownFor(error: PlayerLoadError): Boolean {
    if (isAvailable.value && error.isNetworkKind()) delay(SYSTEM_REPORT_LAG)
    return isNetworkDownFor(error, trustUnreachable = false)
}

/**
 * 按 [decidePlaybackFailure] 的结论处理一次播放失败. 换源之外的处理都在这里, 换源由调用方做 ([recover] 返回 `false`).
 * 一个对象对应一集: 记着这一集里哪条资源播起来过、重新取过地址没有.
 */
internal class PlaybackFailureRecovery(
    private val context: PlayerExtensionContext,
    private val network: NetworkStatus,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class LinkRefresh(val mediaId: String, val at: TimeMark)

    private var playedMediaId: String? = null
    private var decoderReloadedMediaId: String? = null
    private var lastLinkRefresh: LinkRefresh? = null

    /** 已经为它等过网、重载过的那次断网: 恢复后这条资源再失败就不能再怪断网了. */
    private var handledLoss: TimeMark? = null

    /** 数据源确认接不上、已经为它在后台换过源的那条 (见 [trackUnreachable]) */
    private var unreachableMediaId: String? = null

    private var openMediaId: String? = null
    private var openPositionMillis: Long? = null
    private var openPlayWhenReady = true

    /**
     * 记下哪条资源播起来过, 以及它打开着时的进度与播放意图.
     * 要在出错前记: 播放器一进入 [MediaStatus.Error] 就把进度清零、播放意图清成不播, 出错后读到的不算数, 重试途中也一样.
     * 换了查询会话 (如重新搜索) 后选中项跟着换.
     *
     * @param canSwitch 这条资源接不上时能不能自动换 (拖入的文件、别处交来的地址、不优先在线源时不换)
     * @param switchNow 正在播的确认接不上了, 网也通着: 换源 (见 [trackUnreachable])
     */
    suspend fun trackPlayback(
        bundles: Flow<MediaFetchSelectBundle?>,
        canSwitch: suspend (Media) -> Boolean = { false },
        switchNow: suspend (MediaFetchSelectBundle) -> Unit = {},
    ) {
        bundles.collectLatest { bundle ->
            coroutineScope {
                if (bundle != null) {
                    launch { trackUnreachable(bundle, canSwitch, switchNow) }
                    launch { trackRejected(bundle, canSwitch, switchNow) }
                }
                trackProgress(bundle)
            }
        }
    }

    /**
     * 数据源说正在播的这条地址被拒了、它又换不了 (要开网页现抓的源暂停很久后地址过期): 不等缓冲放完报错再原地重载, 趁缓冲还在播就重新解析它
     * (同原地重载, 只是旧的接着放, 拿到新地址再换下来, 见 PlaybackHandover). 规矩同 [PlaybackFailureAction.REFRESH_LINK]:
     * [LINK_REFRESH_INTERVAL] 内重新取过地址又被拒说明不是过期, 能自动换的趁缓冲还在播换源, 不能的照旧等它报错.
     */
    private suspend fun trackRejected(
        bundle: MediaFetchSelectBundle,
        canSwitch: suspend (Media) -> Boolean,
        switchNow: suspend (MediaFetchSelectBundle) -> Unit,
    ) {
        val rejectedFlow = context.playbackHandover?.rejected ?: return
        rejectedFlow.collectLatest { rejected ->
            if (!rejected) return@collectLatest
            val selected = bundle.mediaSelector.selected.value ?: return@collectLatest
            if (selected.mediaId != playedMediaId) return@collectLatest
            val lastRefresh = lastLinkRefresh?.takeIf { it.mediaId == selected.mediaId }
            if (lastRefresh != null && lastRefresh.at.elapsedNow() < LINK_REFRESH_INTERVAL) {
                if (!canSwitch(selected)) return@collectLatest
                unreachableMediaId = selected.mediaId
                logger.info { "Link of ${selected.mediaId} was rejected again soon after resolving it, switching while its buffer plays" }
                switchNow(bundle)
                return@collectLatest
            }
            lastLinkRefresh = LinkRefresh(selected.mediaId, timeSource.markNow())
            logger.info { "Link of ${selected.mediaId} was rejected, resolving it again while its buffer plays" }
            reload(selected)
        }
    }

    /**
     * 数据源确认正在播的这条接不上了 (断流后接着读、换地址都没读到数据, 跟第一次播就打不开差不多): 不等缓冲放完报错, 趁缓冲还在播就换源
     * ([switchNow]): 旧的接着放已缓冲的, 新的在后台装好再换下来 (见 PlaybackHandover). 换过的这条之后报错直接换源, 不再原地重载.
     *
     * 不马上换, 等缓冲只剩 [SWITCH_AHEAD] 再换: 外网断了一会儿而系统没报 (路由器连着、上游在重拨) 时数据源也会判成接不上,
     * 这期间播放器自己一直在重试, 网回来就接着读、数据源撤销「接不上」, 这里跟着作罢. 剩的这些留给找下一条、解析它.
     * 网断着或刚断过 ([RECENT_LOSS] 内) 时是断网的事, 不换 (等网那条路管); 断网刚开始时系统要过一会儿才报 ([SYSTEM_REPORT_LAG]), 等它跟上再看.
     * 不能自动换的 ([canSwitch]) 照旧等它报错, 按原来的办法处理 (原地重载).
     */
    private suspend fun trackUnreachable(
        bundle: MediaFetchSelectBundle,
        canSwitch: suspend (Media) -> Boolean,
        switchNow: suspend (MediaFetchSelectBundle) -> Unit,
    ) {
        val unreachableFlow = context.playbackHandover?.unreachable ?: return
        unreachableFlow.collectLatest { unreachable ->
            val selected = bundle.mediaSelector.selected.value
            if (!unreachable) {
                unreachableMediaId = null
                return@collectLatest
            }
            if (selected == null || selected.mediaId != playedMediaId || !canSwitch(selected)) return@collectLatest
            awaitBufferRunningLow()
            delay(SYSTEM_REPORT_LAG)
            val lostRecently = network.lastLostAt?.let { it.elapsedNow() <= RECENT_LOSS } == true
            if (!network.isAvailable.value || lostRecently) {
                logger.info { "Playing source ${selected.mediaId} is unreachable while the network is down or just came back, not switching now" }
                return@collectLatest
            }
            if (bundle.mediaSelector.selected.value != selected) return@collectLatest
            unreachableMediaId = selected.mediaId
            logger.info { "Playing source ${selected.mediaId} is unreachable, switching while its buffer plays" }
            switchNow(bundle)
        }
    }

    /** 等到往前的缓冲只剩 [SWITCH_AHEAD]; 播放器不报缓冲位置时不等 */
    private suspend fun awaitBufferRunningLow() {
        val buffered = context.player.features[Buffering]?.bufferedPositionMillis ?: return
        combine(buffered, context.player.currentPositionMillis) { end, position -> end - position }
            .first { it <= SWITCH_AHEAD.inWholeMilliseconds }
    }

    private suspend fun trackProgress(bundle: MediaFetchSelectBundle?) {
        combine(context.player.state, context.player.currentPositionMillis) { state, position -> state to position }.collect { (state, position) ->
            // 后台换源时播放器里还是旧的那条, 选中的已经是新的: 旧的在播不算新的播起来过, 进度也不是新的
            if (context.playbackHandover?.preparing?.value != null) return@collect
            val mediaId = bundle?.mediaSelector?.selected?.value?.mediaId ?: return@collect
            if (state.isPlaying) playedMediaId = mediaId
            if (state.mediaStatus == MediaStatus.Ready) {
                if (openMediaId != mediaId) openPositionMillis = null
                openMediaId = mediaId
                if (position > 0) openPositionMillis = position
                openPlayWhenReady = state.playWhenReady
            }
        }
    }

    /**
     * 处理这次失败.
     *
     * @param media 当前选中的资源
     * @return `true` 表示处理了, 不用换源
     */
    suspend fun recover(error: PlayerLoadError, media: Media?): Boolean {
        val hasPlayed = media != null && media.mediaId == playedMediaId
        val lastRefresh = lastLinkRefresh?.takeIf { it.mediaId == media?.mediaId }
        val facts = PlaybackFailureFacts(
            canRefreshLink = hasPlayed && (lastRefresh == null || lastRefresh.at.elapsedNow() >= LINK_REFRESH_INTERVAL),
            canReloadDecoder = media != null && media.mediaId != decoderReloadedMediaId,
            networkDown = isNetworkDown(error),
            unreachable = media != null && media.mediaId == unreachableMediaId,
            handingOver = context.playbackHandover?.preparing?.value != null,
        )
        val action = decidePlaybackFailure(error, facts)
        logger.info {
            "Player errored (${error.description}): $action, media=${media?.mediaId}, hasPlayed=$hasPlayed, " +
                "networkDown=${facts.networkDown}, unreachable=${facts.unreachable}, handingOver=${facts.handingOver}, " +
                "networkAvailable=${network.isAvailable.value}"
        }
        return when (action) {
            PlaybackFailureAction.IGNORE -> true
            PlaybackFailureAction.RELOAD_AFTER_SEEK_PREVIEW -> media != null && reloadAfterSeekPreview(media, error)
            PlaybackFailureAction.RELOAD_DECODER -> media != null && reloadDecoder(media, error)
            // 还没选中资源时重新搜索那边在等 (SearchAfterNetworkOutageExtension), 这时自动选源也选不出什么
            PlaybackFailureAction.WAIT_FOR_NETWORK -> media == null || waitForNetworkAndReload(media, error)
            PlaybackFailureAction.REFRESH_LINK -> media != null && refreshLink(media, error)
            PlaybackFailureAction.SWITCH_SOURCE -> false
        }
    }

    private suspend fun isNetworkDown(error: PlayerLoadError): Boolean {
        // 报了网络不可达而系统还说有网: 等系统跟上再看, 这时还说有网就不是断网 (见 isNetworkUnreachable)
        if (network.isAvailable.value && error.cause?.isNetworkUnreachable() == true) delay(SYSTEM_REPORT_LAG)
        return network.isNetworkDownFor(error, handledLoss, trustUnreachable = false)
    }

    /** 重载 [media]: 回到它打开着时最后的进度与播放意图; 这条资源还没打开过时回到记忆的进度, 照常开播. */
    private suspend fun reload(media: Media): Boolean =
        if (openMediaId == media.mediaId) {
            context.reloadCurrentMedia(openPositionMillis, openPlayWhenReady)
        } else {
            context.reloadCurrentMedia(null, true)
        }

    private suspend fun reloadAfterSeekPreview(media: Media, error: PlayerLoadError): Boolean {
        delay(SEEK_PREVIEW_RELOAD_DELAY)
        if (!reload(media)) return false
        logger.warn(error.cause) { "Player errored (${error.description}), decoder broken by the seek preview, reloading ${media.mediaId} in place" }
        return true
    }

    private suspend fun reloadDecoder(media: Media, error: PlayerLoadError): Boolean {
        if (!reload(media)) return false
        decoderReloadedMediaId = media.mediaId
        logger.warn(error.cause) { "Player errored (${error.description}), decoder preempted, reloading ${media.mediaId} in place" }
        return true
    }

    private suspend fun refreshLink(media: Media, error: PlayerLoadError): Boolean {
        if (!reload(media)) return false
        lastLinkRefresh = LinkRefresh(media.mediaId, timeSource.markNow())
        val why = if (error.cause?.isStreamInterrupted() == true) "stream cut" else "link rejected"
        logger.warn(error.cause) { "Player errored (${error.description}), $why, resolving ${media.mediaId} again" }
        return true
    }

    private suspend fun waitForNetworkAndReload(media: Media, error: PlayerLoadError): Boolean {
        logger.warn(error.cause) { "Player errored (${error.description}), no network, waiting to resume ${media.mediaId}" }
        val waitStarted = timeSource.markNow()
        context.stopForNetwork()
        withTimeoutOrNull(NETWORK_RECHECK) { network.isAvailable.first { it } }
        delay(NETWORK_SETTLE)
        handledLoss = network.lastLostAt
        logger.info { "Waited ${waitStarted.elapsedNow()} for the network (available=${network.isAvailable.value}), resolving ${media.mediaId} again" }
        return reload(media)
    }

    private companion object {
        val logger = logger<PlaybackFailureRecovery>()
    }
}
