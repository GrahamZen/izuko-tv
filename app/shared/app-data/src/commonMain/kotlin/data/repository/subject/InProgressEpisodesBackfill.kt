/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 给一批条目补分集 (收藏列表接口只给条目不给分集): 同时最多 [concurrency] 个请求, 每补完 [flushEvery] 部把攒下的分集
 * 一次写进库 ([save]), 剩下的最后一起写.
 *
 * 一部一部串着补的话, 镜像慢时一部就要几十秒, 六十多部在看要十几分钟; 每部各写一次库, 「继续观看」那一行又会跟着新进度
 * 重排六十多次. 某一部取失败只跳过它 ([onFailed]), 下次刷新再补; [save] 失败照常抛出.
 */
internal suspend fun <T> backfillEpisodesConcurrently(
    subjectIds: List<Int>,
    fetch: suspend (subjectId: Int) -> List<T>,
    save: suspend (List<T>) -> Unit,
    onFetched: (subjectId: Int, count: Int) -> Unit = { _, _ -> },
    onFailed: (subjectId: Int, error: Exception) -> Unit = { _, _ -> },
    concurrency: Int = IN_PROGRESS_EPISODES_CONCURRENCY,
    flushEvery: Int = IN_PROGRESS_EPISODES_FLUSH_EVERY,
) {
    require(concurrency > 0) { "concurrency must be positive" }
    require(flushEvery > 0) { "flushEvery must be positive" }
    if (subjectIds.isEmpty()) return
    coroutineScope {
        val results = Channel<List<T>>(Channel.UNLIMITED)
        val permits = Semaphore(concurrency)
        val workers = subjectIds.map { subjectId ->
            launch {
                permits.withPermit {
                    val episodes = try {
                        fetch(subjectId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onFailed(subjectId, e)
                        return@withPermit
                    }
                    onFetched(subjectId, episodes.size)
                    results.send(episodes)
                }
            }
        }
        launch {
            workers.joinAll()
            results.close()
        }
        val pending = ArrayList<T>()
        var collected = 0
        for (episodes in results) {
            pending += episodes
            collected++
            if (collected % flushEvery == 0 && pending.isNotEmpty()) {
                save(pending.toList())
                pending.clear()
            }
        }
        if (pending.isNotEmpty()) save(pending)
    }
}

/**
 * 在看 / 搁置的条目, 库里的分集过了缓存期 (1 小时) 是否仍可以照用、不必重取.
 *
 * 收藏列表本身带着这一部收藏的更新时间与看到第几集 ([epStatus]): 两样都和库里对得上、正片也已经播完的话, 库里的分集就是现在的样子 ——
 * 在别处 (网页、别的设备) 改看过状态会改收藏的更新时间与看到第几集, 本机改的先写本地; 播完的番不会再加集. 于是「继续观看」每次冷启动 /
 * 每小时的刷新只重取真的可能变了的几部.
 *
 * 照旧重取的: 库里没有分集或分集是登录前取的 (lastFetched 为 0); 之前没有这一行或收藏的更新时间变了; 看到第几集对不上;
 * 总集数比库里的正片多; 正片可能还在播 ([mayStillAir]); 分集超过 [maxAge] 没取过 (兜底 Bangumi 上有人改了分集信息).
 *
 * @param previous 这次写库之前库里的那一行
 * @param current 这次收藏列表里的这一部
 * @param epStatus 收藏列表里的看到第几集 (已看的正片集数)
 */
internal fun inProgressEpisodesUnchanged(
    cached: List<EpisodeCollectionEntity>,
    previous: SubjectCollectionEntity?,
    current: SubjectCollectionEntity,
    epStatus: Int?,
    now: Long,
    today: PackedDate,
    maxAge: Duration = UNCHANGED_EPISODES_MAX_AGE,
): Boolean {
    if (cached.isEmpty() || cached.any { it.lastFetched <= 0 || now - it.lastFetched >= maxAge.inWholeMilliseconds }) return false
    if (previous == null || current.lastUpdated <= 0 || previous.lastUpdated != current.lastUpdated) return false
    val mainStory = cached.filter { it.episodeType == EpisodeType.MainStory }
    if (epStatus == null || epStatus != mainStory.count { it.selfCollectionType == UnifiedCollectionType.DONE }) return false
    if (current.totalEpisodes > mainStory.size) return false
    return !mayStillAir(cached, current.airDate.takeIf { it.isValid }?.toString(), today)
}

/** 播完的在看条目, 收藏没变时库里的分集最多照用多久, 见 [inProgressEpisodesUnchanged]. */
private val UNCHANGED_EPISODES_MAX_AGE = 7.days

/** 补分集时同时发几个请求. */
private const val IN_PROGRESS_EPISODES_CONCURRENCY = 4

/** 补够几部写一次库. */
private const val IN_PROGRESS_EPISODES_FLUSH_EVERY = 8
