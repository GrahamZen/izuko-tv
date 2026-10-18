/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 追番页「追更」页的数据: 在看与想看两条本地 flow (库里整类都在, 见 [sync]), 每分钟按当前时刻重新分组 (跨过零点、某一集到点播出, 组跟着变).
 * 只读库, 不发请求; 同步与补分集见 [sync] / [backfillWishEpisodes].
 */
@Stable
internal class TvCollectionProgressState(
    private val repository: SubjectCollectionRepository,
    private val scope: CoroutineScope,
) {
    /** 分好的组; null = 本地库还没读出来. */
    var sections: List<TvProgressSection>? by mutableStateOf(null)
        private set

    /** 最近一次同步失败的原因 (没登录 / 连不上); 同步成功后清掉. */
    var error: LoadError? by mutableStateOf(null)
        private set

    /** 页面上一共几部 (标签上的数字); 本地库还没读出来时 null. */
    val count: Int? get() = sections?.sumOf { it.entries.size }

    private val syncLock = Mutex()

    /** 最近一次读出来的在看 / 想看整类 (算长按跳过去时那一部在网格里排第几用, 见 [gridIndexOf]); 只在主线程读写. */
    private var doing: List<SubjectCollectionInfo> = emptyList()
    private var wish: List<SubjectCollectionInfo> = emptyList()

    suspend fun collect() {
        combine(
            repository.mostRecentlyUpdatedSubjectCollectionsFlow(TV_PROGRESS_DOING_LIMIT, listOf(UnifiedCollectionType.DOING)),
            repository.mostRecentlyUpdatedSubjectCollectionsFlow(TV_PROGRESS_WISH_LIMIT, listOf(UnifiedCollectionType.WISH)),
            minuteTicker(),
        ) { doing, wish, now ->
            TvProgressSnapshot(doing, wish, tvProgressSections(doing, wish, now, TimeZone.currentSystemDefault()))
        }.flowOn(Dispatchers.Default).collect {
            doing = it.doing
            wish = it.wish
            sections = it.sections
            backfillWishEpisodes(it.sections)
        }
    }

    /**
     * [info] 在它那一类 (在看 / 想看) 的网格里排第几张, 按网格分页的排序算 (`filterByCollectionTypePaging`: 最后改动时间倒序、同时间按条目号倒序,
     * NSFW 设为隐藏时不含这类条目); 不在本地那份里时 null. 长按跳过去之前先把网格定位到这一张, 换过去不用再滚 —— 算错了 (库里刚变过) 页面会按
     * 实际列表再找一遍.
     */
    fun gridIndexOf(info: SubjectCollectionInfo): Int? {
        val list = when (info.collectionType) {
            UnifiedCollectionType.DOING -> doing
            UnifiedCollectionType.WISH -> wish
            else -> return null
        }
        val index = tvGridOrder(list).indexOfFirst { it.subjectId == info.subjectId }
        return index.takeIf { it >= 0 }
    }

    private class TvProgressSnapshot(
        val doing: List<SubjectCollectionInfo>,
        val wish: List<SubjectCollectionInfo>,
        val sections: List<TvProgressSection>,
    )

    /**
     * 从服务端同步在看与想看两类 (同追番页那两个标签的刷新: 第一页对得上就停, 对不上整类补齐, 在看的分集随之补齐). [force] = false 时离上次
     * 同步不到几分钟就不同步, 与那两个标签共用节流 (见 [SubjectCollectionRepository.claimCollectionListRefresh]); 动作面板「刷新本页」传 true.
     */
    suspend fun sync(force: Boolean) = syncLock.withLock {
        try {
            for (type in TV_PROGRESS_SYNC_TYPES) {
                if (force || repository.claimCollectionListRefresh(type)) repository.refreshCollectionList(type)
            }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "TvCollectionProgress: sync failed: $e" }
            error = LoadError.fromException(e)
        }
    }

    fun syncInBackground(force: Boolean) {
        scope.launch { sync(force) }
    }

    /**
     * 想看的番在收藏列表里只有条目、没有分集: 放进页面的那几部 (开播了的、30 天内开播的) 单独取一次分集, 才知道播到第几集、下一集几点.
     * 每个进程每部只取一次 (分集的日期不常变, 已播几集按日期现算).
     */
    private fun backfillWishEpisodes(sections: List<TvProgressSection>) {
        val missing = sections.asSequence()
            .flatMap { it.entries }
            .filter { it.info.collectionType == UnifiedCollectionType.WISH && it.info.episodes.isEmpty() }
            .map { it.subjectId }
            .filter { synchronized(wishEpisodesLock) { wishEpisodesRequested.add(it) } }
            .toList()
        if (missing.isEmpty()) return
        logger.info { "TvCollectionProgress: 补想看条目的分集 $missing" }
        scope.launch {
            for (subjectId in missing) {
                try {
                    repository.refreshSubjectCollection(subjectId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn { "TvCollectionProgress: 补分集失败 subject=$subjectId: $e" }
                }
            }
        }
    }

    private companion object {
        val logger = logger("TvCollectionProgress")

        /** 本进程里取过分集的想看条目, 读写都在 [wishEpisodesLock] 里. */
        val wishEpisodesRequested = HashSet<Int>()
        val wishEpisodesLock = SynchronizedObject()
    }
}

/** 追番页持有的追更页数据: 组合期间一直收集本地库 (标签上的数字也要它), 进页同步一次. */
@Composable
internal fun rememberTvCollectionProgress(): TvCollectionProgressState {
    val scope = rememberCoroutineScope()
    val state = remember { TvCollectionProgressState(GlobalKoin.get<SubjectCollectionRepository>(), scope) }
    LaunchedEffect(state) { state.collect() }
    LaunchedEffect(state) { state.sync(force = false) }
    return state
}

/** 网格分页的排序 (同 `SubjectCollectionDao.filterByCollectionTypePaging`): 最后改动时间倒序、同时间按条目号倒序, NSFW 设为隐藏时去掉这类条目. */
internal fun tvGridOrder(list: List<SubjectCollectionInfo>): List<SubjectCollectionInfo> =
    list.filterNot { it.isHiddenNsfw() }
        .sortedWith(compareByDescending<SubjectCollectionInfo> { it.lastUpdated }.thenByDescending { it.subjectId })

/** 每分钟一次的当前时刻 (分组按它算). */
private fun minuteTicker(): Flow<Instant> = flow {
    while (true) {
        emit(Clock.System.now())
        delay(TV_PROGRESS_TICK_MILLIS)
    }
}

private val TV_PROGRESS_SYNC_TYPES = listOf(UnifiedCollectionType.DOING, UnifiedCollectionType.WISH)

/** 本地在看 / 想看各取多少部来分组: 整类都在库里, 取够全部 (想看的大多没有分集, 一行很轻). */
private const val TV_PROGRESS_DOING_LIMIT = 500
private const val TV_PROGRESS_WISH_LIMIT = 2000

private const val TV_PROGRESS_TICK_MILLIS = 60_000L
