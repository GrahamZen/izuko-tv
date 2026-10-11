/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuEpisodeWithSubject
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.dandanplay.data.DandanplayBangumiDetails
import me.him188.ani.danmaku.dandanplay.data.DandanplayDanmaku
import me.him188.ani.danmaku.dandanplay.data.toDanmakuOrNull
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException

/**
 * 知道 [DanmakuFetchRequest.anidbId] 时定位弹弹 play 的分集 (弹弹 play 的作品编号就是 AniDB 条目编号).
 *
 * 弹弹 play 的分集编号是「作品编号 × 10000 + AniDB 正片集号」, 能直接算出来时 ([directDandanplayEpisodeId]) 弹幕与作品分集列表一起取,
 * 列表只用来核对, 对不上换成列表里对上的那集 ([pickAnidbEpisode]); 列表被限额拒了时, 算出来的那集有弹幕就用它.
 * 分集列表记在 [DandanplayBangumiCalls] 里, 同一部番看下一集不再取; 记着的列表里没有这一集 (新播出的) 时重取一次.
 *
 * 定位不到返回 `null`, 调用方照旧按 Bangumi 映射与名字搜.
 */
internal class DandanplayEpisodeLocator(
    private val client: DandanplayClient,
) {
    private val logger = logger<DandanplayEpisodeLocator>()

    private class Located(
        val episodeId: Long,
        val method: DanmakuMatchMethod,
        /** 定位时已经取到的弹幕; `null` 时再取. */
        val comments: List<DandanplayDanmaku>?,
    )

    private sealed interface Pick {
        class Found(val episode: DanmakuEpisodeWithSubject) : Pick
        data object NotInAnime : Pick
        data object Unavailable : Pick
    }

    /** 定位到时取这一集的弹幕; 定位不到为 `null`. */
    suspend fun fetch(request: DanmakuFetchRequest, providerId: DanmakuProviderId): DanmakuFetchResult? {
        val located = locate(request) ?: return null
        val list = located.comments ?: client.getDanmakuList(located.episodeId)
        logger.info { "${DanmakuServiceId.Dandanplay} Fetched danmaku list: ${list.size}" }
        return DanmakuFetchResult(
            providerId = providerId,
            // 带上弹幕库 id: 发弹幕要发到这个库 (见 DanmakuMatchInfo.sourceEpisodeId)
            matchInfo = DanmakuMatchInfo(DanmakuServiceId.Dandanplay, list.size, located.method, located.episodeId),
            list = list.mapNotNull { it.toDanmakuOrNull() },
        )
    }

    private suspend fun locate(request: DanmakuFetchRequest): Located? = coroutineScope {
        val animeId = request.anidbId ?: return@coroutineScope null
        val direct = directDandanplayEpisodeId(animeId, request)
        // 当场发出请求, 不等调度: 刚进播放页时计算线程常被选资源占满
        val prefetched = direct?.let { id ->
            logger.info { "Prefetching danmaku of computed Dandanplay episode $id" }
            async(start = CoroutineStart.UNDISPATCHED) { fetchCommentsOrNull(id) }
        }
        when (val pick = pickInAnime(animeId, request)) {
            is Pick.Found -> {
                val id = pick.episode.id.toLong()
                logger.info { "Matched episode by AniDB id $animeId: ${pick.episode.subjectName} - ${pick.episode.episodeName}" }
                val comments = if (id == direct) prefetched?.await() else null.also { prefetched?.cancel() }
                Located(id, DanmakuMatchMethod.Exact(pick.episode.subjectName, pick.episode.episodeName), comments)
            }

            Pick.NotInAnime -> {
                prefetched?.cancel()
                logger.info { "No episode matched in Dandanplay anime $animeId (AniDB id) for ${request.subjectPrimaryName}" }
                null
            }

            Pick.Unavailable -> {
                val id = direct ?: return@coroutineScope null
                // 没弹幕时不认: 可能这部其实不在弹弹 play 上, 让调用方按名字再找
                val comments = prefetched?.await()?.takeIf { it.isNotEmpty() } ?: return@coroutineScope null
                logger.info { "Dandanplay anime $animeId unavailable, using computed episode $id (${comments.size} danmaku)" }
                Located(id, DanmakuMatchMethod.Exact(request.subjectPrimaryName, request.episodeName), comments)
            }
        }
    }

    private suspend fun pickInAnime(animeId: Int, request: DanmakuFetchRequest): Pick {
        client.bangumiCalls.cachedAnime(animeId)?.bangumi?.let { cached ->
            pickAnidbEpisode(request, cached.toEpisodes(request))?.let { return Pick.Found(it) }
            client.bangumiCalls.forgetAnime(animeId)
        }
        val bangumi = try {
            client.getBangumiEpisodes(animeId).bangumi
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch Dandanplay anime $animeId by AniDB id" }
            null
        } ?: return Pick.Unavailable
        return pickAnidbEpisode(request, bangumi.toEpisodes(request))?.let { Pick.Found(it) } ?: Pick.NotInAnime
    }

    private suspend fun fetchCommentsOrNull(episodeId: Long): List<DandanplayDanmaku>? = try {
        client.getDanmakuList(episodeId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Failed to prefetch Dandanplay danmaku of episode $episodeId" }
        null
    }

    private fun DandanplayBangumiDetails.toEpisodes(request: DanmakuFetchRequest): List<DanmakuEpisodeWithSubject> =
        episodes.orEmpty().map { episode ->
            DanmakuEpisodeWithSubject(
                id = episode.episodeId.toString(),
                subjectName = animeTitle ?: request.subjectPrimaryName,
                episodeName = episode.episodeTitle ?: "",
                epOrSort = episode.episodeNumber?.let { EpisodeSort(it) },
            )
        }
}

/**
 * 不看分集列表能算出的弹弹 play 分集编号: 作品编号 × 10000 + AniDB 正片集号 (特别篇等从 9001 起编, 不算).
 * 拆分季 ([DanmakuFetchRequest.seasonNumbering]) 那部作品可能把两段并成一部、接着编号; 条目内序号 (ep) 与系列内序号 (sort) 不同时
 * 那部作品也可能是从 1 编也可能接着编 —— 这两种算不准, 为 `null`.
 */
internal fun directDandanplayEpisodeId(animeId: Int, request: DanmakuFetchRequest): Long? {
    if (request.seasonNumbering != null) return null
    val sort = request.episodeSort.positiveIntOrNull() ?: return null
    val ep = request.episodeEp
    if (ep != null && ep.positiveIntOrNull() != sort) return null
    if (sort >= 9000) return null
    return animeId * 10000L + sort
}

private fun EpisodeSort.positiveIntOrNull(): Int? {
    val number = (this as? EpisodeSort.Normal)?.number ?: return null
    if (number % 1f != 0f || number < 1f) return null
    return number.toInt()
}
