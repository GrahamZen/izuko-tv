/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SeriesGraphTableRepository
import me.him188.ani.app.domain.mediasource.clouddrive.DriveNameParser
import me.him188.ani.app.domain.mediasource.toSimplifiedChinese
import me.him188.ani.app.domain.player.diagnostics.PlayTimeline
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * 网页类数据源认部 ([SubjectSearchPlan.identify]) 用的全部动画的名字表. 请求 ([MediaFetchRequest]) 要能序列化, 带不了表, 所以从这里取:
 * app 启动时把 [source] 接到 [SeriesGraphTableRepository.loaded]; 没接上 (单测) 或表还没读进来时只按名字认 ([TitleIdentity.namesOnly]).
 */
object AnimeNames {
    @Volatile
    var source: (() -> SeriesGraphTable?)? = null

    private val lock = SynchronizedObject()
    private val cache = LinkedHashMap<List<Any?>, TitleIdentity>()
    private val building = HashMap<List<Any?>, CompletableDeferred<TitleIdentity>>()
    private var cacheTable: SeriesGraphTable? = null

    /** 按名字表给 [request] 认部 ([TitleIdentity]), 见 [identityOf]. 没有表时只按名字认. */
    internal fun identityOf(request: MediaFetchRequest): TitleIdentity = identityOf(request, source?.invoke())

    /** 同 [identityOf], 别人正在算同一个时挂起等它, 见 [awaitIdentityOf]. */
    internal suspend fun awaitIdentityOf(request: MediaFetchRequest): TitleIdentity = awaitIdentityOf(request, source?.invoke())

    /**
     * 按 [table] 给 [request] 认部. 网页与网盘 ([TitleIdentities.of])、一次查询的各个数据源、几次切集共用, 记最近几个条目, 换了表就清掉.
     *
     * 开播时十几个源同时来认部 (每份都要扫一遍整张表, 电视上一两百毫秒): 能挂起的调用方用 [awaitIdentityOf] 等先来的那份;
     * 这里是给不能挂起的调用方的 (都在搜到结果之后, 那时多半已经算好), 正巧有人在算时自己再算一份, 不占着线程干等.
     */
    internal fun identityOf(request: MediaFetchRequest, table: SeriesGraphTable?): TitleIdentity {
        val key = keyOf(request)
        val claim = claim(key, table)
        return claim.cached ?: build(request, table, key, claim.mine)
    }

    /**
     * 同 [identityOf], 但别人正在算同一个时挂起等它算完 (不各算一份, 也不像加锁那样占着线程干等: 开播时网盘源同时来认部,
     * 锁住的话 `Dispatchers.Default` 的几个线程会一起卡住一两百毫秒, 别的源的协程都排在后面).
     */
    internal suspend fun awaitIdentityOf(request: MediaFetchRequest, table: SeriesGraphTable?): TitleIdentity {
        val key = keyOf(request)
        val claim = claim(key, table)
        claim.cached?.let { return it }
        claim.pending?.let { return it.await() }
        return build(request, table, key, claim.mine)
    }

    private fun keyOf(request: MediaFetchRequest): List<Any?> = listOf(request.subjectId, request.subjectNames, request.nameHints)

    /** 算好的 ([cached]), 别人正在算的 ([pending]), 或者由调用方来算 ([mine], 算完交给等着的人). */
    private class Claim(
        val cached: TitleIdentity? = null,
        val pending: Deferred<TitleIdentity>? = null,
        val mine: CompletableDeferred<TitleIdentity>? = null,
    )

    private fun claim(key: List<Any?>, table: SeriesGraphTable?): Claim = synchronized(lock) {
        if (table !== cacheTable) {
            cache.clear()
            building.clear()
            cacheTable = table
        }
        cache[key]?.let { return Claim(cached = it) }
        building[key]?.let { return Claim(pending = it) }
        Claim(mine = CompletableDeferred<TitleIdentity>().also { building[key] = it })
    }

    private fun build(
        request: MediaFetchRequest,
        table: SeriesGraphTable?,
        key: List<Any?>,
        mine: CompletableDeferred<TitleIdentity>?,
    ): TitleIdentity {
        val start = TimeSource.Monotonic.markNow()
        val identity = try {
            if (table == null) TitleIdentity.namesOnly(request) else TitleIdentity.of(request, table, AnimeNameIndex.of(table))
        } catch (e: Throwable) {
            synchronized(lock) { if (mine != null && building[key] === mine) building.remove(key) }
            mine?.completeExceptionally(e)
            throw e
        }
        synchronized(lock) {
            if (mine != null && building[key] === mine) building.remove(key)
            if (cacheTable === table) {
                cache[key] = identity
                while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
            }
        }
        mine?.complete(identity)
        PlayTimeline.mark("identity_built", "${request.subjectId} ${start.elapsedNow().inWholeMilliseconds}ms")
        return identity
    }

    /**
     * 表一读进来就把认部要现建的先建好: 名字索引 ([AnimeNameIndex])、繁转简的转换器 (Android 上第一次用要建 ICU 的规则表)
     * 与解析文件名、季号的那一堆正则 ([DriveNameParser] 第一次用到时整个对象一起编译), 再拿表里一部有续作的番完整认一次部、
     * 排一次条目页 ([SubjectSearchPlan.pagesToOpen]), 把这一路用到的类与正则都初始化掉. 电视上第一次认部要多花半秒多, 都花在这些上.
     */
    fun prewarm(table: SeriesGraphTable) {
        // 各段用时一起记进日志, 看出慢在哪一段
        val parts = mutableListOf<String>()
        val took = measureTime {
            parts += "index=" + measureTime { AnimeNameIndex.of(table) }.inWholeMilliseconds
            parts += "icu=" + measureTime { "臺灣".toSimplifiedChinese() }.inWholeMilliseconds
            parts += "regex=" + measureTime { DriveNameParser.parseSubjectSeason("") }.inWholeMilliseconds
            sampleRequest(table)?.let { request ->
                parts += "identity=" + measureTime { identityOf(request, table) }.inWholeMilliseconds
                parts += "pages=" + measureTime {
                    SubjectSearchPlan.of(request).pagesToOpen(listOf(request.subjectNames.first() + " 第二季"), preferShorterName = false) { it }
                }.inWholeMilliseconds
            }
        }
        logger.info { "Anime name index for ${table.size} entries ready in $took (${parts.joinToString(" ")})" }
    }

    /** 表里第一部有续作的番, 当作开播的请求. */
    private fun sampleRequest(table: SeriesGraphTable): MediaFetchRequest? {
        val subjectId = table.subjectIds.firstOrNull { table.edgesOf(it)?.sequels?.isNotEmpty() == true } ?: return null
        val names = table.namesOf(subjectId).ifEmpty { return null }
        return MediaFetchRequest(
            subjectId = subjectId.toString(),
            episodeId = "0",
            subjectNames = names,
            episodeSort = EpisodeSort(1),
            episodeName = "",
        )
    }

    private const val CACHE_SIZE = 8
    private val logger = logger<AnimeNames>()
}
