/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.episode.EpisodeCompletionContext
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.session.InvalidSessionReason
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台缓存标签最下面「挑番缓存」的数据: 在看 / 想看的番 (`api/collections?type=DOING|WISH`), 各最近 [LIMIT] 部.
 * 想提前缓存某部番时, 不用先搜名字、也不用先进一次播放器.
 *
 * - 收藏走 Ani 服务器, 只要 Ani 账号的会话有效 (邮箱登录、没连 Bangumi 也行). 先同步一次最近的收藏 (同追番页刷新),
 *   同步失败就用本地已有的并标 `stale`.
 * - 每部番的剧集 (播出日期、看到哪集) 跟着收藏一起存在本地库, 「有几集新的」在电视本地算, 不另外联网; 口径同
 *   `SubjectProgressInfo`: 看到的那一集之后已经播出的 (前面漏标看过的老集不算). 想看的番没看过, 就是全部已播出的.
 * - 「几集没缓存」对照本地缓存记录 (按集记账: 合集只算在选它的那一集, 同缓存页).
 */
internal object RemoteCollections {
    private val logger = logger<RemoteCollections>()
    private val repository: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()
    private val sessionStateProvider: SessionStateProvider get() = KoinPlatform.getKoin().get()
    private val cacheManager: MediaDownloadManager get() = KoinPlatform.getKoin().get()

    /** 每种收藏上次同步成功的时刻. */
    private val lastSynced = ConcurrentHashMap<UnifiedCollectionType, Long>()

    fun handle(request: LanHttpRequest): JsonObject? {
        if (request.path != "api/collections" || (request.method != "GET" && request.method != "HEAD")) return null
        val type = if ("type=WISH" in request.query.split('&')) UnifiedCollectionType.WISH else UnifiedCollectionType.DOING
        return runCatching { list(type) }.getOrElse {
            logger.warn(it) { "Remote collections request failed" }
            result(false, tr("读取失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    private fun list(type: UnifiedCollectionType): JsonObject {
        val session = runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() } }
        if (session !is SessionState.Valid) {
            val offline = session is SessionState.Invalid && session.reason == InvalidSessionReason.NETWORK_ERROR
            return buildJsonObject {
                put("ok", false)
                put("needLogin", !offline)
                put("message", if (offline) tr("电视连不上 Animeko 服务器，稍后再试") else tr("电视还没登录，登录后才能看到在看 / 想看"))
            }
        }
        // 同一种收藏一分钟内同步过就不再联网 (网页上切来切去、缓存面板关上重读), 直接读本地库
        val last = lastSynced[type]
        val fresh = last != null && System.currentTimeMillis() - last < SYNC_FRESH_MILLIS
        val synced = fresh || runCatching {
            runBlocking { withTimeoutOrNull(SYNC_TIMEOUT) { repository.updateRecentlyUpdatedSubjectCollections(LIMIT, type); true } }
        }.onFailure { logger.warn(it) { "Remote collections: sync $type failed, using the local copy" } }
            .getOrNull() == true
        if (synced && !fresh) lastSynced[type] = System.currentTimeMillis()
        val list = runBlocking {
            withTimeoutOrNull(READ_TIMEOUT) { repository.mostRecentlyUpdatedSubjectCollectionsFlow(LIMIT, listOf(type)).first() }
        }.orEmpty()
        val cached = cachedEpisodes()
        val rows = list.mapNotNull { row(it, type, cached[it.subjectInfo.subjectId].orEmpty()) }
            // 在看: 有新集的排前面 (稳定排序, 同一档里仍按最近动过的在前)
            .let { if (type == UnifiedCollectionType.DOING) it.sortedByDescending { r -> r.fresh } else it }
        return buildJsonObject {
            put("ok", true)
            if (!synced) put("stale", true)
            putJsonArray("items") { rows.forEach { add(it.json) } }
        }
    }

    private class Row(val json: JsonObject, val fresh: Boolean)

    private fun row(info: SubjectCollectionInfo, type: UnifiedCollectionType, cachedEpisodes: Set<Int>): Row? {
        val subject = info.subjectInfo
        // NSFW 跟电视网格同一个设置: 隐藏就不列, 模糊就给标记 (同搜索结果)
        if (subject.nsfw && info.nsfwMode == NsfwMode.HIDE) return null
        val normal = info.episodes.filter { it.episodeInfo.sort is EpisodeSort.Normal }
        val aired = with(EpisodeCompletionContext) { normal.filter { it.episodeInfo.isKnownCompleted(info.recurrence) } }
        val lastWatched = normal.indexOfLast {
            it.collectionType == UnifiedCollectionType.DONE || it.collectionType == UnifiedCollectionType.DROPPED
        }
        val pending = aired.filter { normal.indexOf(it) > lastWatched }
        val uncached = pending.count { it.episodeId !in cachedEpisodes }
        val fresh = type == UnifiedCollectionType.DOING && pending.isNotEmpty()
        val status = info.progressInfo.continueWatchingStatus
        val line = when {
            fresh -> tr("有 {0} 集新的", pending.size) + cacheText(uncached, pending.size)
            type == UnifiedCollectionType.WISH && aired.isNotEmpty() -> tr("已播 {0} 集", aired.size) + cacheText(uncached, pending.size)
            status is ContinueWatchingStatus.NotOnAir -> tr("还没开播")
            status is ContinueWatchingStatus.Done -> tr("已看完")
            type == UnifiedCollectionType.DOING -> tr("已追到最新")
            else -> null
        }
        val json = buildJsonObject {
            put("id", subject.subjectId)
            put("title", subject.displayName)
            line?.let { put("line", it) }
            if (fresh) put("fresh", true)
            if (subject.nsfw && info.nsfwMode == NsfwMode.BLUR) put("blur", true)
            putJsonArray("cover") { remoteCoverCandidates(subject.subjectId, subject.imageLarge).forEach { add(it) } }
        }
        return Row(json, fresh)
    }

    private fun cacheText(uncached: Int, total: Int): String = when {
        uncached == 0 -> tr(" · 都已缓存")
        uncached == total -> tr(" · 都没缓存")
        else -> tr(" · {0} 集没缓存", uncached)
    }

    /** subjectId → 已缓存 (没删的, 含正在下载的) 各集 episodeId. 本地读, 不联网. */
    private fun cachedEpisodes(): Map<Int, Set<Int>> = runBlocking {
        withTimeoutOrNull(READ_TIMEOUT) {
            cacheManager.downloads.first()
                .filter { !it.cache.isDeleted.value }
                .mapNotNull { d ->
                    val s = d.metadata.subjectId.toIntOrNull() ?: return@mapNotNull null
                    val e = d.metadata.episodeId.toIntOrNull() ?: return@mapNotNull null
                    s to e
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
        }
    }.orEmpty()

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    /** 每段拉多少部: 挑番用, 不做完整翻页 (那是电视追番页的事). */
    private const val LIMIT = 50
    private val STATE_TIMEOUT = 3.seconds

    /** 同步一次最近的收藏 (网络) 最多等这么久, 超时就用本地已有的. */
    private val SYNC_TIMEOUT = 12.seconds
    private val READ_TIMEOUT = 3.seconds

    /** 同步过之后这么久内不再联网同步. */
    private const val SYNC_FRESH_MILLIS = 60_000L
}
