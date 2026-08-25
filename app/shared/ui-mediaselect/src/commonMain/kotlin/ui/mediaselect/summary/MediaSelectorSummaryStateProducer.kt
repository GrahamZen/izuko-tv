/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.summary

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.fetch.MediaSourceInfoWithId
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.isPerfectMatch
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.platform.collections.tupleOf
import kotlin.time.Duration.Companion.seconds


/**
 * @see MediaSelectorSummary
 * @see me.him188.ani.app.domain.media.selector.MediaSelector.selectedMaybeExcludedMediaFlow
 */
class MediaSelectorSummaryStateProducer(
    selectedMaybeExcludedMediaFlow: Flow<MaybeExcludedMedia?>,
    mediaSourceResultsFlow: Flow<List<MediaSourceFetchResult>>,
    mediaSelectorSettingsFlow: Flow<MediaSelectorSettings>,
    mediaSources: Flow<List<MediaSourceInfoWithId>>,
) {
    private val sourceSummariesFlow = combine(mediaSourceResultsFlow, mediaSources) { results, sources ->
        tupleOf(results, sources)
    }.flatMapLatest { (results, sourcesSorted) ->
        combine(
            results.map { result ->
                result.state.map { it is MediaSourceFetchState.Completed }.distinctUntilChanged()
            },
        ) { states ->
            results
                .asSequence()
                .filter { !it.sourceInfo.isSpecial }
                .filter { it.kind == MediaSourceKind.WEB } // FIXME: BT 的图标是 iconResourceId 展示的, 目前 UI 还没支持, 所以这里只显示 WEB 的
                .filter { states[results.indexOf(it)] }
                .sortedWith(
                    compareBy { source ->
                        sourcesSorted.indexOfFirst { it.instanceId == source.instanceId }
                    },
                )
                .map {
                    it.sourceInfo.toSummary()
                }
                .toList()
        }
    }

    @OptIn(UnsafeOriginalMediaAccess::class)
    val flow = combine(
        sourceSummariesFlow,
        mediaSelectorSettingsFlow,
        mediaSources,
    ) { sourceSummaries, mediaSelectorSettings, mediaSourceInstancesSorted ->
        tupleOf(sourceSummaries, mediaSelectorSettings, mediaSourceInstancesSorted)
    }.transformLatest { (sourceSummaries, mediaSelectorSettings, mediaSourceInstances) ->
        emitAll(
            selectedMaybeExcludedMediaFlow.map { selected ->
                when {
                    selected != null -> {
                        MediaSelectorSummary.Selected(
                            mediaSourceInstances.find { it.mediaSourceId == selected.original.mediaSourceId }
                                ?.info
                                ?.toSummary()
                                ?: MediaSelectorSourceSummary(
                                    sourceName = selected.original.mediaSourceId,
                                    sourceIconUrl = "",
                                ),
                            selected.original.originalTitle,
                            isPerfectMatch = selected.isPerfectMatch(),
                            exclusionReason = selected.exclusionReason,
                        )
                    }

                    mediaSelectorSettings.preferKind == MediaSourceKind.WEB -> {
                        MediaSelectorSummary.AutoSelecting(
                            sources = sourceSummaries,
                            estimate = if (mediaSelectorSettings.fastSelectWebKind) mediaSelectorSettings.fastSelectWebLowTierToleranceDuration
                            else 10.seconds,
                        )
                    }

                    else -> {
                        MediaSelectorSummary.RequiresManualSelection(
                            sources = sourceSummaries,
                        )
                    }
                }
            },
        )
    }.distinctUntilChanged()
}

@OptIn(UnsafeOriginalMediaAccess::class)
val MediaSelector.selectedMaybeExcludedMediaFlow: Flow<MaybeExcludedMedia?>
    get() = this.selected.flatMapLatest { selected ->
        if (selected == null) {
            flowOf(null)
        } else {
            // 必须持续订阅, 不能只在 selected 变化时取一次快照:
            // 条目信息还没到位时 MediaSelectorFilterSortAlgorithm 不构造 MediaListFilterContext,
            // 条目名校验一次都不跑, 那一份快照里所有资源都是 Included (exclusionReason 全为 null).
            // 只取一次的话, "条目名不匹配" / "不是精确匹配" 就再也不会显示 —— 除非 selected 又变一次,
            // 或者用户打开数据源菜单再返回, 让这个 flow 重新被收集一遍 (2026-08-25 实测到的正是这两种).
            filteredCandidates.map { list ->
                list.firstOrNull { it.original === selected } // identity check is enough and fast
            }
        }
    }

private fun MediaSourceInfo.toSummary() =
    MediaSelectorSourceSummary(
        displayName,
        iconUrl ?: "",
    )
