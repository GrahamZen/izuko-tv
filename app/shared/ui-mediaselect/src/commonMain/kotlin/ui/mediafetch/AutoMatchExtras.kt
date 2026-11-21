/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediafetch

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.isPerfectMatch
import me.him188.ani.app.ui.mediaselect.selector.WebSource
import me.him188.ani.app.ui.mediaselect.selector.WebSourceChannel
import me.him188.ani.app.ui.mediaselect.selector.WebSourceInexact
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind

/**
 * 自动匹配页 (触屏) 在选择器给的源列表之外要补的, 同电视的选源面板 (ui-tv TvSourcePanelModel). 选择器给的
 * ([MediaSelectorState.Presentation.webSources]) 只列有条目名与集数都对上的线路的源, 每条线路一个芯片:
 * - [sources]: 接在后面的源 —— 只有没完全对上的结果 ([WebSource.inexact]), 结果全被排除 ([WebSource.excludedReasons], 点了照样能播),
 *   开播后暂停了还没结果 ([WebSource.isPausedWithoutResults], 点了接着查)
 * - [fileChannels]: 一个文件一行的源 (网盘类, 线路名就是数据源名) 每个文件一个芯片, 不然按线路名去重后只剩一个
 * - [hiddenCounts]: 列出来的源里没当芯片的结果数 (没对上的、被排除的), 卡片上写「显示其余 N 条」, 点了进这个源的全部结果
 * - [emptySources]: 查完了一条能列的都没有的源, 列表最后写一行
 *
 * 属于别的集的、这台设备放不了的 (iOS 上的 mkv 等) 都不算, 也不列.
 * 键都是 [MediaSourceFetchResult.instanceId].
 */
@Immutable
data class AutoMatchExtras(
    val sources: List<WebSource>,
    val fileChannels: Map<String, List<WebSourceChannel>>,
    val hiddenCounts: Map<String, Int>,
    val emptySources: List<EmptySource>,
) {
    @Immutable
    data class EmptySource(val instanceId: String, val name: String)

    companion object {
        val Empty = AutoMatchExtras(emptyList(), emptyMap(), emptyMap(), emptyList())
    }
}

/** 选择器给的源 [normal] 换上 [extras] 里的文件芯片与其余条数, 再接上补列的源 (已经在 [normal] 里的不重复列). */
internal fun mergeAutoMatchSources(normal: List<WebSource>, extras: AutoMatchExtras): List<WebSource> =
    normal.map { source ->
        source.copy(
            channels = extras.fileChannels[source.instanceId] ?: source.channels,
            hiddenCount = extras.hiddenCounts[source.instanceId] ?: 0,
        )
    } + extras.sources.filter { extra -> normal.none { it.instanceId == extra.instanceId } }

internal fun autoMatchExtrasFlow(
    results: Flow<List<MediaSourceFetchResult>>,
    candidates: Flow<List<MaybeExcludedMedia>>,
): Flow<AutoMatchExtras> = results.flatMapLatest { list ->
    val web = list.filter { it.kind == MediaSourceKind.WEB }
    if (web.isEmpty()) return@flatMapLatest flowOf(AutoMatchExtras.Empty)
    combine(combine(web.map { it.state }) { it.toList() }, candidates) { states, candidates ->
        autoMatchExtrasOf(web.zip(states), candidates)
    }
}.distinctUntilChanged()

@OptIn(UnsafeOriginalMediaAccess::class)
internal fun autoMatchExtrasOf(
    sources: List<Pair<MediaSourceFetchResult, MediaSourceFetchState>>,
    candidates: List<MaybeExcludedMedia>,
): AutoMatchExtras {
    val listable = candidates.filter { it.isListableInAutoMatch() }.groupBy { it.original.mediaSourceId }
    val extraSources = mutableListOf<WebSource>()
    val fileChannels = mutableMapOf<String, List<WebSourceChannel>>()
    val hiddenCounts = mutableMapOf<String, Int>()
    val emptySources = mutableListOf<AutoMatchExtras.EmptySource>()
    for ((source, state) in sources) {
        if (state is MediaSourceFetchState.Disabled) continue
        val mine = listable[source.mediaSourceId].orEmpty()
        val perfect = mine.filter { it.isPerfectMatch() }
        if (perfect.isNotEmpty()) {
            // 选择器已经列了: 网盘类换成每个文件一个芯片, 其余的记个数
            val medias = perfect.map { it.original }
            if (isOneFilePerRow(source, medias)) fileChannels[source.instanceId] = fileChannelsOf(medias)
            if (mine.size > perfect.size) hiddenCounts[source.instanceId] = mine.size - perfect.size
            continue
        }
        val searched = state is MediaSourceFetchState.Succeed || state is MediaSourceFetchState.Paused
        if (!searched) continue // 在查、要验证、失败的, 选择器那边会列
        val paused = state is MediaSourceFetchState.Paused
        val included = mine.filterIsInstance<MaybeExcludedMedia.Included>()
        val excluded = mine.filterIsInstance<MaybeExcludedMedia.Excluded>()
        when {
            included.isNotEmpty() -> extraSources += webSourceOf(
                source,
                channels = channelsOf(source, included.map { it.original }),
                hiddenCount = excluded.size,
                inexact = WebSourceInexact(
                    fuzzyTitle = included.any { it.metadata.subjectMatchKind != MatchMetadata.SubjectMatchKind.EXACT },
                    episodeUnknown = included.any { it.metadata.episodeMatchKind < MatchMetadata.EpisodeMatchKind.EP },
                ),
            )

            excluded.isNotEmpty() -> extraSources += webSourceOf(
                source,
                channels = channelsOf(source, excluded.map { it.original }),
                excludedReasons = excluded.map { it.exclusionReason }.distinct(),
            )

            paused -> extraSources += webSourceOf(source, channels = emptyList(), isPausedWithoutResults = true)
            else -> emptySources += AutoMatchExtras.EmptySource(source.instanceId, source.sourceInfo.displayName)
        }
    }
    return AutoMatchExtras(extraSources, fileChannels, hiddenCounts, emptySources)
}

/** 自动匹配页里列不列: 属于别的集的、这台设备放不了的不列. */
private fun MaybeExcludedMedia.isListableInAutoMatch(): Boolean = when (exclusionReason) {
    is MediaExclusionReason.EpisodeMismatch, MediaExclusionReason.UnsupportedByPlatformPlayer -> false
    else -> true
}

private fun webSourceOf(
    source: MediaSourceFetchResult,
    channels: List<WebSourceChannel>,
    hiddenCount: Int = 0,
    inexact: WebSourceInexact? = null,
    excludedReasons: List<MediaExclusionReason> = emptyList(),
    isPausedWithoutResults: Boolean = false,
) = WebSource(
    instanceId = source.instanceId,
    mediaSourceId = source.mediaSourceId,
    iconUrl = source.sourceInfo.iconUrl ?: "",
    name = source.sourceInfo.displayName,
    channels = channels,
    isLoading = false,
    isError = false,
    isPreferred = false,
    inexact = inexact,
    excludedReasons = excludedReasons,
    hiddenCount = hiddenCount,
    isPausedWithoutResults = isPausedWithoutResults,
)

/** 每条线路一个芯片 (同一线路取排序靠前的一个); 一个文件一行的源每个文件一个. */
private fun channelsOf(source: MediaSourceFetchResult, medias: List<Media>): List<WebSourceChannel> =
    if (isOneFilePerRow(source, medias)) {
        fileChannelsOf(medias)
    } else {
        medias.distinctBy { it.properties.alliance }.map { WebSourceChannel(it.properties.alliance, original = it) }
    }

/**
 * 一个文件一行的源 (网盘、网盘分享搜索、添加的分享): 结果的线路名写的是数据源名, 或同一线路有好几条 (同电视选源面板的认法).
 */
internal fun isOneFilePerRow(source: MediaSourceFetchResult, medias: List<Media>): Boolean =
    medias.any { it.properties.alliance == source.sourceInfo.displayName } ||
        medias.distinctBy { it.properties.alliance }.size < medias.size

/** 每个文件一个芯片, 芯片上写清晰度与大小; 写出来撞了 (或都没有) 的用文件名. */
internal fun fileChannelsOf(medias: List<Media>): List<WebSourceChannel> {
    val labels = medias.map { media ->
        listOfNotNull(
            media.properties.resolution.takeIf { it.isNotBlank() },
            media.properties.size.takeIf { !it.isUnspecified && it.inBytes > 0 }?.toString(),
        ).joinToString(" · ")
    }
    return medias.mapIndexed { index, media ->
        val label = labels[index]
        val unique = label.isNotEmpty() && labels.count { it == label } == 1
        WebSourceChannel(if (unique) label else fileNameOf(media), original = media)
    }
}

private fun fileNameOf(media: Media): String =
    media.originalTitle.substringAfterLast(" / ").substringAfterLast('/').trim().let {
        if (it.length > FILE_CHIP_MAX_CHARS) it.take(FILE_CHIP_MAX_CHARS) + "…" else it
    }

private const val FILE_CHIP_MAX_CHARS = 28
