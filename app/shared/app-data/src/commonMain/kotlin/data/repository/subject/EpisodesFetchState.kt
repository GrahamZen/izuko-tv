/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.datasources.api.EpisodeType
import kotlin.coroutines.cancellation.CancellationException

/**
 * 一个条目的分集在本进程里取过没有, 见 [SubjectCollectionRepository.episodesFetchStateFlow].
 *
 * 库里有分集时用不上它. 库里没有分集时 (想看 / 看过的番只同步了条目、刚进详情页还在取), 界面靠它分清「分集还在取」和「取完了确实没有」.
 */
sealed interface EpisodesFetchState {
    /** 本进程还没取完: 可能正在取, 也可能还没开始. */
    data object Pending : EpisodesFetchState

    /**
     * 取到了并已落库.
     *
     * @param visibleCount 按取到那时的分集类型筛选 ([GetEpisodeTypeFiltersUseCase]) 能显示几集. 0 = Bangumi 上确实没有能看的集;
     *   大于 0 时分集已经在库里, 只是界面可能还没收到.
     */
    data class Fetched(val visibleCount: Int) : EpisodesFetchState

    /** 上一次没取到 (网络错误, 或服务端不给这个条目). */
    data object Failed : EpisodesFetchState
}

/** 记下每个条目最近一次取分集的结果, 见 [EpisodesFetchState]. 只存在内存里, 一个条目一项. */
internal class EpisodesFetchTracker {
    private val states = MutableStateFlow<Map<Int, EpisodesFetchState>>(emptyMap())

    fun stateFlow(subjectId: Int): Flow<EpisodesFetchState> =
        states.map { it[subjectId] ?: EpisodesFetchState.Pending }.distinctUntilChanged()

    /**
     * 分集落库之后调用 (落库前记的话, 界面会先看到「取到了」而库里还是空的).
     *
     * @param episodes 取到的分集, `null` = 没取到
     */
    fun recordFetched(subjectId: Int, episodes: List<EpisodeCollectionEntity>?, visibleTypes: List<EpisodeType>) {
        record(
            subjectId,
            if (episodes == null) EpisodesFetchState.Failed
            else EpisodesFetchState.Fetched(episodes.count { it.episodeType in visibleTypes }),
        )
    }

    fun recordFailed(subjectId: Int) = record(subjectId, EpisodesFetchState.Failed)

    /**
     * 本进程还没有结论时, 按库里能显示的集数记一个: 这次不用取数 (库里的分集还新鲜), 库里的就是结论.
     * 库里只有不显示的类型 (PV 之类) 时, 界面没有分集、也不会再有取数来给结论, 靠这里知道不用再等.
     */
    suspend fun settleIfPending(subjectId: Int, visibleCount: suspend () -> Int) {
        if (subjectId in states.value) return
        val count = visibleCount()
        states.update { if (subjectId in it) it else it + (subjectId to EpisodesFetchState.Fetched(count)) }
    }

    /** [block] 抛异常 (不含取消) 时记为没取到再抛出. */
    inline fun <T> failedOnError(subjectId: Int, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (e !is CancellationException) recordFailed(subjectId)
        throw e
    }

    private fun record(subjectId: Int, state: EpisodesFetchState) {
        states.update { it + (subjectId to state) }
    }
}
