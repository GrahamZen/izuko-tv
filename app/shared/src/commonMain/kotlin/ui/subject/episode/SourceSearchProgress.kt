/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.runtime.Immutable
import me.him188.ani.app.domain.media.fetch.isFinal
import me.him188.ani.app.ui.mediaselect.summary.MediaSelectorSummary
import me.him188.ani.datasources.api.source.MediaSourceKind

/** 数据源搜索层面的"再等也没用". */
internal enum class SelectionProblem {
    None,

    /** 有搜到结果, 但不会自动选 (偏好不是 WEB), 在等用户挑. */
    NeedsManualSelection,

    /** 全部源都查完了, 一个可播的结果都没有. */
    NoMedia,
}

internal fun selectionProblemOf(state: EpisodePageState?): SelectionProblem {
    // 页面状态还没算出来 (刚进页面) 或还是占位数据: 什么都判断不了
    if (state == null || state.isPlaceholder) return SelectionProblem.None
    // 已经选中了就不是选择层面的问题 (解析/播放能不能成另说, 那是 problemOf 的前两条)
    if (state.mediaSelectorSummary is MediaSelectorSummary.Selected) return SelectionProblem.None
    val results = state.mediaSourceResultListPresentation
    // 源还没登记上来 (刚进页面) 或还有源在查 —— 等着就行, 这才是这套机制的正常用途.
    // 有源被暂停 (上一次开播后暂停的) 同样要等: 自动选源用不上已有的结果时会马上把它们放开
    if (results.list.isEmpty() || results.anyLoading || results.list.any { it.isPaused }) return SelectionProblem.None
    return if (results.list.any { it.totalCount > 0 }) SelectionProblem.NeedsManualSelection
    else SelectionProblem.NoMedia
}

/**
 * 起播自动选源这一步的进展. 播放页的加载提示与后台会话的状态行据此写「正在搜索数据源 9/14 · 已找到 37 条」,
 * 查完却没选中时说清是没结果还是要手选.
 *
 * 只留显示要用的几个数, 方便去重: 整份 [EpisodePageState] 随弹幕统计等刷得很勤, 加载提示直接读它的话每刷一次都要重组.
 */
@Immutable
data class SourceSearchProgress(
    /** 查完了的源 (成功 / 失败 / 要验证 / 被限流都算). */
    val finished: Int,
    /** 参与搜索的源: 启用的, 不含本地缓存. */
    val total: Int,
    /** 通过筛选 (属于这一集等) 的资源条数. */
    val found: Int,
    /** 还在查的源名, 按列表顺序, 最多 [MAX_PENDING_SOURCE_NAMES] 个. */
    val pendingNames: List<String>,
    /** 还在查的源一共几个 (可能比 [pendingNames] 多). */
    val pendingCount: Int,
    /** 全部查完却没选中时的原因; 还在查或已经选中时为 `null`. */
    val stuck: SourceSearchStuck?,
)

/** 全部数据源查完却没选中时的两种情况, 与后台会话的 NeedsSelection / NoMedia 同一判据. */
enum class SourceSearchStuck {
    /** 有结果, 但不会自动选 (偏好不是 WEB), 要用户到「选择数据源」里挑. */
    NeedsManualSelection,

    /** 全部查完, 一个可播的结果都没有. */
    NoMedia,
}

/**
 * 算出 [SourceSearchProgress]. 占位数据、或一个参与搜索的源都没有时为 `null` (加载提示照旧写「正在自动选择数据源」).
 */
fun EpisodePageState.sourceSearchProgress(): SourceSearchProgress? {
    if (isPlaceholder) return null
    val sources = mediaSourceResultListPresentation.list
        .filter { !it.isDisabled && it.kind != MediaSourceKind.LocalCache }
    if (sources.isEmpty()) return null
    // 暂停的算没查完: 自动选源用不上已有结果时会把它们放开重查 (与 selectionProblemOf 同一判据)
    val pending = sources.filter { !it.state.isFinal || it.isPaused }
    return SourceSearchProgress(
        finished = sources.size - pending.size,
        total = sources.size,
        found = sources.sumOf { it.totalCount },
        pendingNames = pending.take(MAX_PENDING_SOURCE_NAMES).map { it.info.displayName },
        pendingCount = pending.size,
        stuck = when (selectionProblemOf(this)) {
            SelectionProblem.None -> null
            SelectionProblem.NeedsManualSelection -> SourceSearchStuck.NeedsManualSelection
            SelectionProblem.NoMedia -> SourceSearchStuck.NoMedia
        },
    )
}

private const val MAX_PENDING_SOURCE_NAMES = 3
