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
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.isFinal
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaAutoSelectSnapshot
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.MediaSourceSelectionSnapshot
import me.him188.ani.datasources.api.Media
import kotlin.time.Duration

/**
 * 这一集的候选 (已按集与排除条件过滤、按既有规则排好): 查完了的数据源给的结果.
 * 选源器的上下文 (剧集、完结状态) [timeout] 内没准备好时为 null.
 */
internal suspend fun candidatesOf(session: MediaFetchSession, selector: MediaSelector, timeout: Duration): List<Media>? =
    candidatesOf(sourceSnapshots(session), selector, timeout)

internal suspend fun candidatesOf(sources: List<MediaSourceSelectionSnapshot>, selector: MediaSelector, timeout: Duration): List<Media>? =
    selectionSnapshotOf(sources, selector, timeout)?.candidates?.map(MaybeExcludedMedia.Included::result)

internal suspend fun selectionSnapshotOf(
    sources: List<MediaSourceSelectionSnapshot>,
    selector: MediaSelector,
    timeout: Duration,
): MediaAutoSelectSnapshot? =
    withTimeoutOrNull(timeout) {
        selector.autoSelectSnapshots(flowOf(sources)).first { it.context.hasEpisode && it.context.allFieldsLoaded() }
    }

/** 会话里各数据源此刻的状态, 只带查完了的结果. */
internal suspend fun sourceSnapshots(session: MediaFetchSession): List<MediaSourceSelectionSnapshot> =
    session.mediaSourceResults.map { source ->
        val state = source.state.value
        MediaSourceSelectionSnapshot(source.mediaSourceId, source.kind, state, if (state.isFinal) source.results.first() else emptyList())
    }
