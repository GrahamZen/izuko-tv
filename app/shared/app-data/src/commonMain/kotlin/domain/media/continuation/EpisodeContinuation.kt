/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.contains

/**
 * 一类资源怎么「顺着上一集往下播」: 记下什么线索 ([lineageOf])、候选里哪个是线索上的 ([continues])、候选里没有时怎么直接取 ([resolve]).
 * 各类资源各有一个实现, 由 [EpisodeContinuations] 汇总; 传进来的本地缓存一律换成它的来源 (见 [continuationTarget]).
 */
interface EpisodeContinuation {
    /** [media] 是不是这一类资源 (由这个实现记线索). [EpisodeContinuations] 按顺序交给第一个认领的. */
    fun owns(media: Media): Boolean

    /** 正在播的 [media] (这一类资源) 的线索; 信息不够记不了时为 null. */
    suspend fun lineageOf(media: Media): PlaybackLineage?

    /**
     * [candidate] 是不是 [lineage] 上的资源. 候选已经按集过滤过, 线索上的就是要接着播的那一集. 只看手上的信息, 不发请求;
     * 不归这一类管的线索返回 false.
     */
    fun continues(lineage: PlaybackLineage, candidate: Media): Boolean

    /** 候选里没有时按 [lineage] 直接去取 [request] 那一集; 不归这一类管的线索返回 [LineageResolution.NotFound]. */
    suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest): LineageResolution
}

sealed interface LineageResolution {
    data class Found(val media: Media) : LineageResolution

    /** 线索还在, 只是没有这一集 (新的一集还没更新进来、数据源停用了等) */
    data object NotFound : LineageResolution

    /** 线索彻底失效 (分享被取消、文件夹被删), 之后不用再顺着它找 */
    data object Gone : LineageResolution
}

/** 本地缓存按它的来源认线索: 缓存下来的就是那个版本. */
val Media.continuationTarget: Media get() = (this as? CachedMedia)?.origin ?: this

/** [request] 那一集在不在这条资源里 (单集或合集都看集号范围). */
internal fun Media.coversEpisodeOf(request: MediaFetchRequest): Boolean {
    val range = episodeRange ?: return false
    return request.episodeSort in range || request.episodeEp?.let { it in range } == true
}

/** 各类资源的 [EpisodeContinuation] 汇总: 资源交给第一个认领的 ([owns]), 线索由认得它的那个处理. */
class EpisodeContinuations(private val continuations: List<EpisodeContinuation>) : EpisodeContinuation {
    override fun owns(media: Media): Boolean = continuations.any { it.owns(media.continuationTarget) }

    override suspend fun lineageOf(media: Media): PlaybackLineage? {
        val target = media.continuationTarget
        return continuations.firstOrNull { it.owns(target) }?.lineageOf(target)
    }

    override fun continues(lineage: PlaybackLineage, candidate: Media): Boolean =
        continuations.any { it.continues(lineage, candidate.continuationTarget) }

    override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest): LineageResolution {
        for (continuation in continuations) {
            val resolution = continuation.resolve(lineage, request)
            if (resolution != LineageResolution.NotFound) return resolution
        }
        return LineageResolution.NotFound
    }
}
