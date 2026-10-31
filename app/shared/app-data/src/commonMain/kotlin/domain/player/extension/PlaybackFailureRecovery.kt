/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle
import me.him188.ani.app.domain.player.SeekPreviewDecoderFault
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.player.httpErrorStatus
import me.him188.ani.app.domain.player.isDecoderPreempted
import me.him188.ani.app.domain.player.isNetworkFailure
import me.him188.ani.app.domain.player.isNotVideoResponse
import me.him188.ani.app.platform.NetworkStatus
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediaStatus
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** 播放失败后怎么办, 由 [decidePlaybackFailure] 决定. */
internal enum class PlaybackFailureAction {
    /** 不用管: 不是失败 (加载被取消), 换源也没用 (网页引擎不可用), 或跟这条资源无关 (离开播放页时释放 Surface 超时). */
    IGNORE,

    /** 拖动预览换输出弄坏了解码器: 等它缓过来后原地重载, 不拉黑. */
    RELOAD_AFTER_SEEK_PREVIEW,

    /** 解码器被抢走: 原地重载一次, 不拉黑. */
    RELOAD_DECODER,

    /** 系统没网: 换哪个在线源都一样连不上. 不拉黑、不换源、不限时长, 恢复后原地重新取一次地址. */
    WAIT_FOR_NETWORK,

    /** 播起来过的资源的地址被拒绝了, 多半是签名过期: 原地重新取一次地址, 不拉黑. */
    REFRESH_LINK,

    /** 这条资源放不了: 拉黑并换下一个. 认不出的都走这里, 放不出来比换源更糟. */
    SWITCH_SOURCE,
}

/**
 * @property canRefreshLink 这条资源播起来过, 而且最近没为它重新取过地址
 * @property canReloadDecoder 这条资源还没因为解码器被抢重载过
 * @property networkDown 系统没网, 或这次失败发生在刚断网的时候 (见 [isNetworkDownFor])
 */
internal class PlaybackFailureFacts(
    val canRefreshLink: Boolean,
    val canReloadDecoder: Boolean,
    val networkDown: Boolean,
)

/**
 * 所有「播放失败后怎么办」的判断都在这里. 换源、数据源画像 ([SourceProfileExtension]) 都按它的结论: 只有 [PlaybackFailureAction.SWITCH_SOURCE] 算这条资源的错.
 *
 * 先后有讲究: 等网前面的几种与网络无关; 断网排在链接失效前面, 断网时取不到新地址.
 */
internal fun decidePlaybackFailure(error: PlayerLoadError, facts: PlaybackFailureFacts): PlaybackFailureAction = when {
    error.state == VideoLoadingState.WaitingForNetwork -> PlaybackFailureAction.WAIT_FOR_NETWORK
    (error.state as? VideoLoadingState.Failed)?.let { PlayerLoadErrorHandler.canRecoverBySwitching(it) } == false -> PlaybackFailureAction.IGNORE
    error.isPlayerLifecycleError() -> PlaybackFailureAction.IGNORE
    SeekPreviewDecoderFault.isFault(error.cause) -> PlaybackFailureAction.RELOAD_AFTER_SEEK_PREVIEW
    isDecoderPreempted(error.cause) && facts.canReloadDecoder -> PlaybackFailureAction.RELOAD_DECODER
    facts.networkDown -> PlaybackFailureAction.WAIT_FOR_NETWORK
    facts.canRefreshLink && error.cause?.isLinkRejected() == true -> PlaybackFailureAction.REFRESH_LINK
    else -> PlaybackFailureAction.SWITCH_SOURCE
}

/** 断网恢复后这么久之内的网络类失败也算断网造成的: 解析、嗅探在断网时开始, 要等到超时才报 (实测恢复后才超时). */
private val RECENT_LOSS = 20.seconds

/** 网络没了之后, 连接先报「网络不可达」(路由先没), 系统稍后才报没网 (实测差不到一秒). */
private val SYSTEM_REPORT_LAG = 1500.milliseconds

/**
 * 拖动预览换输出弄坏了解码器之后, 等这么久再原地重载: 坏掉的硬解要一会儿才缓得过来, 马上重开多半还是坏的.
 * 解码器还没缓过来时重开的也会坏, 认出的那一轮 ([SeekPreviewDecoderFault.FAULT_WINDOW]) 里每次都这样重试, 过了那一轮才换源.
 */
private val SEEK_PREVIEW_RELOAD_DELAY = 3.seconds

/** 恢复后等一下再取地址: 刚连上时 DNS 可能还没好. */
private val NETWORK_SETTLE = 1.seconds

/**
 * 同一条资源至少隔这么久才再重新取一次地址. 签名地址的有效期以小时计, 刚取的就被拒绝说明不是过期;
 * 不限的话放到坏掉的那一段 (分片没了) 会被拒绝、重取、放到同一处又被拒绝, 没完没了.
 */
private val LINK_REFRESH_INTERVAL = 30.minutes

/** 地址本身被拒绝的状态码; 取新地址可能有用. 服务器出错 (5xx) 与限流不算. */
private val REJECTED_LINK_STATUSES = setOf(401, 403, 404, 410, 412)

/** 地址被服务器拒绝, 或回的不是视频 (站点把过期的地址转到了网页). */
private fun Throwable.isLinkRejected(): Boolean = httpErrorStatus() in REJECTED_LINK_STATUSES || isNotVideoResponse()

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
 * 这次失败看起来是断网造成的: 系统没网, 连接报了网络不可达, 或者刚断过网而这次是网络类失败.
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
    if (!isAvailable.value) return true
    if (trustUnreachable && error.cause?.isNetworkUnreachable() == true) return true
    val lost = lastLostAt ?: return false
    return lost !== handledLoss && error.isNetworkKind() && lost.elapsedNow() <= RECENT_LOSS
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

    private var openMediaId: String? = null
    private var openPositionMillis: Long? = null
    private var openPlayWhenReady = true

    /**
     * 记下哪条资源播起来过, 以及它打开着时的进度与播放意图.
     * 要在出错前记: 播放器一进入 [MediaStatus.Error] 就把进度清零、播放意图清成不播, 出错后读到的不算数, 重试途中也一样.
     * 换了查询会话 (如重新搜索) 后选中项跟着换.
     */
    suspend fun trackPlayback(bundles: Flow<MediaFetchSelectBundle?>) {
        bundles.collectLatest { bundle ->
            combine(context.player.state, context.player.currentPositionMillis) { state, position -> state to position }.collect { (state, position) ->
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
        )
        val action = decidePlaybackFailure(error, facts)
        logger.info {
            "Player errored (${error.description}): $action, media=${media?.mediaId}, hasPlayed=$hasPlayed, " +
                "networkDown=${facts.networkDown}, networkAvailable=${network.isAvailable.value}"
        }
        return when (action) {
            PlaybackFailureAction.IGNORE -> true
            PlaybackFailureAction.RELOAD_AFTER_SEEK_PREVIEW -> media != null && reloadAfterSeekPreview(media, error)
            PlaybackFailureAction.RELOAD_DECODER -> media != null && reloadDecoder(media, error)
            PlaybackFailureAction.WAIT_FOR_NETWORK -> media != null && waitForNetworkAndReload(media, error)
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
        logger.warn(error.cause) { "Player errored (${error.description}), link rejected, resolving ${media.mediaId} again" }
        return true
    }

    private suspend fun waitForNetworkAndReload(media: Media, error: PlayerLoadError): Boolean {
        logger.warn(error.cause) { "Player errored (${error.description}), no network, waiting to resume ${media.mediaId}" }
        val waitStarted = timeSource.markNow()
        context.stopForNetwork()
        network.isAvailable.first { it }
        delay(NETWORK_SETTLE)
        handledLoss = network.lastLostAt
        logger.info { "Network is back after ${waitStarted.elapsedNow()}, resolving ${media.mediaId} again" }
        return reload(media)
    }

    private companion object {
        val logger = logger<PlaybackFailureRecovery>()
    }
}
