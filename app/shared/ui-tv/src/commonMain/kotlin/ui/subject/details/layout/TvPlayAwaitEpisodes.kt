/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.subject.EpisodesFetchState
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsState
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** 播放键在分集还没出来时按下, 等到的结论, 见 [awaitEpisodesForPlay]. */
internal enum class TvPlayEpisodesOutcome {
    /** 分集到了. */
    READY,

    /** 取完了, Bangumi 上确实没有能看的集. */
    NO_EPISODES,

    /** 分集没取到 (按下时先重取了一次, 还是没取到). */
    FETCH_FAILED,
}

/**
 * 播放键在分集还没出来时按下: 等分集到界面上, 或等仓库给出「取完了确实没有 / 没取到」.
 * 上一次没取到的, 先重取一次再看.
 *
 * @param hasEpisodes 界面上有没有分集
 * @param fetchState 这个条目取分集的结果 ([me.him188.ani.app.data.repository.subject.SubjectCollectionRepository.episodesFetchStateFlow])
 * @param refetch 连同分集重取这个条目, 落库后才返回
 */
internal suspend fun awaitEpisodesForPlay(
    hasEpisodes: Flow<Boolean>,
    fetchState: Flow<EpisodesFetchState>,
    refetch: suspend () -> Unit,
): TvPlayEpisodesOutcome {
    if (!hasEpisodes.first() && fetchState.first() == EpisodesFetchState.Failed) {
        try {
            refetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 结论照样看下面: 仓库已把这次记成没取到
            logger.warn(e) { "Refetching episodes for play failed" }
        }
    }
    return combine(hasEpisodes, fetchState) { has, fetch ->
        when {
            has -> TvPlayEpisodesOutcome.READY
            fetch is EpisodesFetchState.Fetched && fetch.visibleCount == 0 -> TvPlayEpisodesOutcome.NO_EPISODES
            fetch == EpisodesFetchState.Failed -> TvPlayEpisodesOutcome.FETCH_FAILED
            // 还在取; 或已经落库 (visibleCount > 0), 界面还没收到
            else -> null
        }
    }.filterNotNull().first()
}

/**
 * 详情页播放键的按下逻辑: 有要播的集就播. 分集还没出来时 (想看 / 看过的番只同步了条目, 进详情页才取分集, 走镜像常要一两秒)
 * 按下去按钮转圈 ([waiting]), 分集一到直接进播放页; 取完了确实没有、或重取了还是没取到才弹说明 ([dialog], 见 [TvSubjectNoEpisodesDialog]).
 * 焦点离开播放键 ([cancel]) 就不等了.
 */
@Stable
internal class TvPlayEpisodesGate(
    private val scope: CoroutineScope,
    private val subjectId: Int,
    private val fetchState: Flow<EpisodesFetchState>,
    private val refetch: suspend () -> Unit,
    /** 条目信息出来了 (不是占位). 占位时按播放什么都不做. */
    private val loaded: State<Boolean>,
    private val hasEpisodes: State<Boolean>,
    private val episodeIdToPlay: State<Int?>,
    private val onPlay: State<(episodeId: Int) -> Unit>,
) {
    /** 分集还没出来, 按下后在等. */
    var waiting by mutableStateOf(false)
        private set

    /** 要弹的说明 ([TvPlayEpisodesOutcome.NO_EPISODES] / [TvPlayEpisodesOutcome.FETCH_FAILED]); `null` = 不弹. */
    var dialog by mutableStateOf<TvPlayEpisodesOutcome?>(null)
        private set

    private var job: Job? = null

    fun press() {
        episodeIdToPlay.value?.let {
            onPlay.value(it)
            return
        }
        // 有分集却没有要播的集 (如还没开播): 同原来一样不响应
        if (!loaded.value || hasEpisodes.value || job?.isActive == true) return
        job = scope.launch {
            waiting = true
            val start = TimeSource.Monotonic.markNow()
            try {
                // hasEpisodes 与 episodeIdToPlay 在同一次组合里更新: 看到有分集时要播的集也已经是新的
                val outcome = awaitEpisodesForPlay(snapshotFlow { hasEpisodes.value }, fetchState, refetch)
                logger.info { "Play pressed before episodes arrived: subject $subjectId -> $outcome after ${start.elapsedNow()}" }
                if (outcome == TvPlayEpisodesOutcome.READY) {
                    episodeIdToPlay.value?.let { onPlay.value(it) }
                } else {
                    dialog = outcome
                }
            } finally {
                waiting = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismissDialog() {
        dialog = null
    }
}

@Composable
internal fun rememberTvPlayEpisodesGate(
    state: SubjectDetailsState,
    loaded: Boolean,
    hasEpisodes: Boolean,
    episodeIdToPlay: Int?,
    onPlay: (episodeId: Int) -> Unit,
): TvPlayEpisodesGate {
    val scope = rememberCoroutineScope()
    val loadedState = rememberUpdatedState(loaded)
    val hasEpisodesState = rememberUpdatedState(hasEpisodes)
    val episodeIdToPlayState = rememberUpdatedState(episodeIdToPlay)
    val onPlayState = rememberUpdatedState(onPlay)
    return remember(state, scope) {
        TvPlayEpisodesGate(
            scope, state.subjectId, state.episodesFetchStateFlow, state.refetchEpisodes,
            loadedState, hasEpisodesState, episodeIdToPlayState, onPlayState,
        )
    }
}

private val logger = logger("TvPlayEpisodes")
