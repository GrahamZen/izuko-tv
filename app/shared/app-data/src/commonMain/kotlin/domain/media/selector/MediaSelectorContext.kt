/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.RepositoryUnknownException
import me.him188.ani.app.domain.media.selector.MediaSelectorContext.Companion.Initial
import me.him188.ani.app.domain.mediasource.codec.MediaSourceTier
import me.him188.ani.utils.coroutines.retryWithBackoffDelay
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

data class MediaSelectorContext(
    /**
     * 该条目已经完结了一段时间了. `null` 表示该信息还正在查询中
     */
    val subjectFinished: Boolean?,
    /**
     * 在执行自动选择时, 需要按此顺序使用数据源.
     * 为 `null` 表示无偏好, 可以按任意顺序选择.
     *
     * 当使用完所有偏好的数据源后都没有筛选到资源时, 将会 fallback 为选择任意数据源的资源
     */
    val mediaSourcePrecedence: List<String>?,
    /**
     * 用于针对各个平台的播放器缺陷，调整选择资源的优先级
     */
    val subtitlePreferences: MediaSelectorSubtitlePreferences?,
    val subjectSeriesInfo: SubjectSeriesInfo?,
    val subjectInfo: SubjectInfo?,
    val episodeInfo: EpisodeInfo?,
    val mediaSourceTiers: MediaSelectorSourceTiers?,
    /**
     * 当前**不能播放**的本地缓存 (下载还没完成) 的 [me.him188.ani.datasources.api.Media.mediaId].
     *
     * `null` = **还没查到**, 不是"没有". 只有 [MediaSelectorContextFlowProducer] 会短暂发出 null
     * (它先发一发好让 context 不必等缓存那条流); 直接构造 context 的地方 (测试、预览) 默认
     * 已知为空集 —— 否则那些场景会永远停在"未知", 让依赖它的判断一直走兜底.
     *
     * 这是一条实时流的快照: 缓存下完的那一刻本 context 会重新 emit, 筛选随之重算, 界面上的
     * "缓存未完成"警告当场消失. 见 MediaCacheManager.unplayableCacheMediaIds.
     */
    val unplayableCacheMediaIds: Set<String>? = emptySet(),
) {
    fun allFieldsLoaded() = subjectFinished != null
            && mediaSourcePrecedence != null
            && subtitlePreferences != null
            && subjectSeriesInfo != null
            && subjectInfo != null
            && episodeInfo != null
            && mediaSourceTiers != null

    companion object {
        /**
         * 刚开始查询时的默认值
         */
        val Initial = MediaSelectorContext(null, null, null, null, null, null, null)

        val EmptyForPreview
            get() = MediaSelectorContext(
                false,
                emptyList(),
                MediaSelectorSubtitlePreferences.AllNormal,
                SubjectSeriesInfo.Fallback,
                SubjectInfo.Empty,
                EpisodeInfo.Empty,
                mediaSourceTiers = MediaSelectorSourceTiers.Empty,
            )


        internal val logger = logger<MediaSelectorContext>()
    }
}

class MediaSelectorContextFlowProducer(
    subjectCompleted: Flow<Boolean>,
    mediaSourcePrecedence: Flow<List<String>>,
    subjectSeriesInfo: Flow<SubjectSeriesInfo>,
    subjectInfoFlow: Flow<SubjectInfo>,
    episodeInfoFlow: Flow<EpisodeInfo>,
    mediaSourceTiersFlow: Flow<MediaSelectorSourceTiers>,
    subtitleKindFilters: Flow<MediaSelectorSubtitlePreferences> = flowOf(MediaSelectorSubtitlePreferences.CurrentPlatform),
    /** 见 [MediaSelectorContext.unplayableCacheMediaIds]; 默认为空 (测试与预览不关心缓存). */
    unplayableCacheMediaIds: Flow<Set<String>> = flowOf(emptySet()),
) {
    val flow = me.him188.ani.utils.coroutines.flows.combine(
        // 都 emit null, debug 时能知道是谁没 emit
        subjectCompleted.onStart<Boolean?> { emit(null) },
        mediaSourcePrecedence.onStart<List<String>?> { emit(null) },
        subtitleKindFilters.onStart<MediaSelectorSubtitlePreferences?> { emit(null) },
        subjectSeriesInfo.retryWithBackoffDelay { e, _ ->
            val wrapped = RepositoryException.wrapOrThrowCancellation(e)
            if (wrapped is RepositoryUnknownException) {
                MediaSelectorContext.Companion.logger.warn { "Failed to load related subject names due to $wrapped" }
            } else {
                MediaSelectorContext.Companion.logger.error(wrapped) { "Failed to load related subject names" }
            }
            emit(SubjectSeriesInfo.Fallback)
            true
        }.onStart<SubjectSeriesInfo?> { emit(null) },
        subjectInfoFlow.onStart<SubjectInfo?> { emit(null) },
        episodeInfoFlow.onStart<EpisodeInfo?> { emit(null) },
        mediaSourceTiersFlow.onStart<MediaSelectorSourceTiers?> { emit(null) },
    ) { completed, instances, filters, seriesInfo, subjectInfo, episodeInfo, mediaSourceTiers ->
        MediaSelectorContext(
            subjectFinished = completed,
            mediaSourcePrecedence = instances,
            subtitlePreferences = filters,
            subjectSeriesInfo = seriesInfo,
            subjectInfo = subjectInfo,
            episodeInfo = episodeInfo,
            mediaSourceTiers = mediaSourceTiers,
        )
    }
        // 缓存可播性单独 combine 一层, 不挤进上面那个 7 元 combine: 它与其余字段的来源和更新
        // 频率都不同 (缓存下载进度), 而且这样每次下载状态变化只重算这一步
        .combine(unplayableCacheMediaIds.onStart<Set<String>?> { emit(null) }) { context, ids ->
            context.copy(unplayableCacheMediaIds = ids)
        }
        .onStart {
            emit(Initial) // 否则如果一直没获取到剧集信息, 就无法选集, #385
        }
}


/**
 * 所有已知数据源的 tiers.
 *
 * @since 4.7
 */
data class MediaSelectorSourceTiers(
    /**
     * Key is [me.him188.ani.datasources.api.source.MediaSource.mediaSourceId]
     */
    val tiers: Map<String, MediaSourceTier>,
    /**
     * Channel 级别的 tier 覆盖. 外层 key 是 [me.him188.ani.datasources.api.source.MediaSource.mediaSourceId],
     * 内层 key 是 channel 名称, 即 [me.him188.ani.datasources.api.MediaProperties.alliance].
     *
     * 若一个 media 的 channel 在此表中有记录, 则以该 channel tier 为准, 否则回退到数据源本身的 tier.
     *
     * @since 4.9
     */
    val channelTiers: Map<String, Map<String, MediaSourceTier>> = emptyMap(),
    val fallback: (mediaSourceId: String) -> MediaSourceTier = { MediaSourceTier.Fallback },
) {
    operator fun get(mediaSourceId: String): MediaSourceTier {
        return tiers[mediaSourceId] ?: fallback(mediaSourceId)
    }

    /**
     * 获取一个资源的有效 tier: 优先使用其 channel ([channel], 即 alliance) 的 tier, 否则使用数据源的 tier.
     *
     * @since 4.9
     */
    fun get(mediaSourceId: String, channel: String?): MediaSourceTier {
        if (!channel.isNullOrEmpty()) {
            channelTiers[mediaSourceId]?.get(channel)?.let { return it }
        }
        return get(mediaSourceId)
    }

    /**
     * 数据源能达到的最优 (数值最低) tier, 即数据源自身 tier 与其所有 channel tier 中的最小值.
     *
     * 用于快速选择: 只要数据源存在一个足够低 tier 的 channel, 该数据源就有可能立即提供低 tier 资源.
     *
     * @since 4.9
     */
    fun getBestTier(mediaSourceId: String): MediaSourceTier {
        val sourceTier = get(mediaSourceId)
        val channelMin = channelTiers[mediaSourceId]?.values?.minOrNull() ?: return sourceTier
        return minOf(sourceTier, channelMin)
    }

    companion object {
        val Empty = MediaSelectorSourceTiers(emptyMap())
    }
}
