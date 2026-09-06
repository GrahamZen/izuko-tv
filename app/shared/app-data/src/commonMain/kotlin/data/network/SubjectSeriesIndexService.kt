/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.utils.platform.currentTimeMillis
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSlimSubject
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.CoroutineContext

/**
 * 一个条目的**系列**索引: 同一条主线上的全部条目, 以及它之后的续作.
 *
 * 用途见 #1324「查询第一季时自动排除第二季的资源」, 以及 TMDB 匹配时找系列主条目名.
 */
data class SubjectRelationIndex(
    /** 主线上的全部条目 (前传…自己…续集), 按时间先后 */
    val seriesMainSubjectIds: List<Int>,
    /** [seriesMainSubjectIds] 里每个条目的原名与中文名 (各算一个) */
    val seriesMainSubjectNames: List<String>,
    /**
     * 可以当作「系列主条目名」去 TMDB 搜的候选, 最可能的在最前 (原名与中文名各算一个).
     * 一个候选都没有时调用方回落到自己逐跳找 —— **宁可没有, 也不能给错的**.
     *
     * 三类候选, 都是锚点测试 (`ANI_TMDB_E2E=1`) 逼出来的, 改这里必须重跑:
     * 1. **祖先** (前传链). 系列主条目按定义往前找.
     * 2. **名字被自己包含的续集**. 前导篇/序章的本传恰恰是它的续集 ——
     *    `ef - a tale of memories. ~prologue~` 的本传是 `ef - a tale of memories.`,
     *    `THE IDOLM@STER Prologue SideM` 的是 `偶像大师 SideM`. 排掉它们这些条目一张图都没有.
     *
     * **兄弟续集不算**. 1600「BLEACH」是系列起点, 没有前传只有续集「千年血战篇」, 把续集当母条目
     * 会让它去搜续集、hero 与单集背景全变成续集的图. 同理 299802「総集編」的兄弟是另一部総集編.
     *
     * **「主线故事」出边也不算**, 尽管它确实指向本篇. 番外/特辑 (高达 FRAG.、艾伦特辑、
     * 哆啦A梦短片) 要走下游那条 bangumi 逐跳回溯: 那条路除了名字还会判出"这是衍生作"
     * (`BgmLineage.isDerivative`), 而这里给不出这个判断. 从索引提前给名字会把它们截走, 图就错了.
     */
    val seriesRootNames: List<String>,
    /** 自己之后的续作 (传递闭包) */
    val sequelSubjects: List<Int>,
    val sequelSubjectNames: List<String>,
) {
    companion object {
        val Empty = SubjectRelationIndex(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * 系列索引的客户端实现, 取代 Ani 服务端算好的那份 (`/v1/subject-relations/{id}`).
 *
 * bangumi 没有等价物, 只能顺着 `/p1/subjects/{id}/relations` 的**前传 (2) / 续集 (3)** 出边
 * 自己走一遍传递闭包. 对照 302286 (死神 千年血战篇), 走出来的结果与 Ani 那份逐条一致:
 * 主线 `[1600, 302286, 412916, 457326, 530725]`, 续作 `[412916, 457326, 530725]`.
 *
 * 代价是**每个节点一个请求** (上面那个例子 5 个), 所以结果按 subjectId 缓存在内存里 ——
 * 详情页与 TMDB 匹配会对同一个条目反复问.
 */
class SubjectSeriesIndexService(
    private val bangumiSubjectApi: ApiInvoker<SubjectBangumiNextApi>,
    /**
     * BFS 的归属作用域: **不能挂在调用方协程上**.
     *
     * 这条 BFS 是直连之后新增的开销 (Ani 把 `seriesMainSubjectIds` 随条目一起下发, 零请求),
     * 长系列最多走 [MAX_NODES] 跳、每跳一个 `/p1/subjects/{id}/relations`. 而调用它的两条路
     * (详情页关联数据 / TMDB 匹配的 root 档) 都活在 `collectLatest` 底下 —— 焦点一挪就取消,
     * 十几个请求全废且什么都没缓存, 下次进来从零再来一遍. Re:Zero 家族 19 个节点, 正是重灾区.
     *
     * 同时补上**在途去重**: 原先并发问同一个条目会各跑一遍完整 BFS.
     */
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    private val scope = scope
    private val logger = logger<SubjectSeriesIndexService>()
    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<Int, SubjectRelationIndex>()

    /**
     * 上一次为某条目算索引的开销 (请求数 + 耗时), 只给日志用 (见 `TmdbImageService` 的背景图那行).
     *
     * 这条 BFS 是直连之后新增的: Ani 把 `seriesMainSubjectIds` 随条目一起下发, 零请求;
     * 现在长系列最多要走 [MAX_NODES] 跳, 每跳一个 `/p1/subjects/{id}/relations`。
     * 命中内存缓存时不记 (那次没有开销).
     */
    class ComputeStats(val requests: Int, val millis: Long)

    private val statsLock = SynchronizedObject()
    private val stats = LinkedHashMap<Int, ComputeStats>()

    fun lastStatsOf(subjectId: Int): ComputeStats? = synchronized(statsLock) { stats[subjectId] }

    /** subjectId -> 在途的 BFS. 与 [cache] 共用 [cacheLock], 临界区里不含挂起工作. */
    private val inFlight = mutableMapOf<Int, Deferred<SubjectRelationIndex>>()

    suspend fun getSubjectRelationIndex(subjectId: Int): SubjectRelationIndex {
        cacheLock.withLock { cache[subjectId] }?.let { return it }
        var created: Deferred<SubjectRelationIndex>? = null
        val task = cacheLock.withLock {
            // 二次查: 等锁期间前一个任务可能已经算完写进缓存了
            cache[subjectId]?.let { return it }
            inFlight[subjectId] ?: newComputeTask(subjectId).also {
                inFlight[subjectId] = it
                created = it
            }
        }
        // LAZY + 出锁再 start: 任务的 finally 要拿同一把锁摘除自己
        created?.start()
        // 调用者被取消只取消这个 await, BFS 照跑完并落进缓存
        return task.await()
    }

    private fun newComputeTask(subjectId: Int): Deferred<SubjectRelationIndex> =
        scope.async(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                val startMillis = currentTimeMillis()
                val requestCount = atomic(0)
                val computed = try {
                    compute(subjectId, requestCount)
                } catch (e: Exception) {
                    throw RepositoryException.wrapOrThrowCancellation(e)
                }
                synchronized(statsLock) {
                    stats[subjectId] = ComputeStats(requestCount.value, currentTimeMillis() - startMillis)
                    while (stats.size > CACHE_SIZE) stats.remove(stats.keys.first())
                }
                logger.info {
                    "bgm-direct: seriesIndex subject=$subjectId -> main=${computed.seriesMainSubjectIds} " +
                            "sequels=${computed.sequelSubjects} rootNames=${computed.seriesRootNames.take(2)}"
                }
                cacheLock.withLock {
                    cache[subjectId] = computed
                    while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
                }
                computed
            } finally {
                cacheLock.withLock { inFlight.remove(subjectId) }
            }
        }

    private suspend fun compute(subjectId: Int, requestCount: AtomicInt): SubjectRelationIndex {
        val edges = HashMap<Int, Edges>()

        suspend fun edgesOf(id: Int): Edges = edges.getOrPut(id) {
            requestCount.incrementAndGet()
            fetchEdges(id)
        }

        // 两个方向各走一遍传递闭包. 用 LinkedHashSet 保持发现顺序 (= 时间先后)
        suspend fun walk(direction: (Edges) -> List<BangumiNextSlimSubject>): LinkedHashSet<Int> {
            val result = LinkedHashSet<Int>()
            var frontier = direction(edgesOf(subjectId))
            while (frontier.isNotEmpty() && result.size < MAX_NODES) {
                val next = mutableListOf<BangumiNextSlimSubject>()
                for (subject in frontier) {
                    if (!result.add(subject.id)) continue
                    next.addAll(direction(edgesOf(subject.id)))
                }
                frontier = next
            }
            return result
        }

        val sequels = walk { it.sequels }
        val prequels = walk { it.prequels }

        // 主线按时间先后: 最早的前传在最前, 自己在中间
        val mainLine: List<Int> = prequels.toList().reversed() + subjectId + sequels.toList()
        val ancestors = prequels.toList().reversed()
        val selfNames = namesOf(subjectId, edges).map { it.normalizeForNameMatch() }
        // 名字被自己包含的续集 = 本传 (见 seriesRootNames 的说明)
        val parentWorks = sequels.filter { sequel ->
            namesOf(sequel, edges).any { name ->
                val normalized = name.normalizeForNameMatch()
                normalized.isNotEmpty() && selfNames.any { it != normalized && it.contains(normalized) }
            }
        }
        return SubjectRelationIndex(
            seriesMainSubjectIds = mainLine,
            seriesMainSubjectNames = mainLine.flatMap { namesOf(it, edges) },
            seriesRootNames = ancestors.flatMap { namesOf(it, edges) } +
                    parentWorks.flatMap { namesOf(it, edges) },
            sequelSubjects = sequels.toList(),
            sequelSubjectNames = sequels.toList().flatMap { namesOf(it, edges) },
        )
    }

    /**
     * 一个条目的名字只能从**别人的**关系列表里拿到 (`/relations` 不包含条目自己).
     * 走完闭包后每个节点都至少被某个邻居提到过, 除非它是孤立的.
     */
    private fun namesOf(id: Int, edges: Map<Int, Edges>): List<String> {
        val subject = edges.values.asSequence()
            .flatMap { (it.sequels + it.prequels).asSequence() }
            .firstOrNull { it.id == id }
            ?: return emptyList()
        return listOfNotNull(
            subject.name.takeIf { it.isNotBlank() },
            subject.nameCN.takeIf { it.isNotBlank() && it != subject.name },
        )
    }

    private suspend fun fetchEdges(subjectId: Int): Edges = bangumiSubjectApi {
        val relations = getSubjectRelations(
            subjectID = subjectId,
            type = BangumiNextSubjectType.Anime,
            limit = RELATIONS_PAGE_SIZE,
        ).body().data
        Edges(
            // 2 = 前传, 3 = 续集. 特别篇/衍生 (6/11) 不算主线, 排除资源时也不该带上
            prequels = relations.filter { it.relation.id == RELATION_PREQUEL }.map { it.subject },
            sequels = relations.filter { it.relation.id == RELATION_SEQUEL }.map { it.subject },
        )
    }

    /** 只留字母数字并小写: 同系列条目名常只差标点与空格. */
    private fun String.normalizeForNameMatch(): String = lowercase().filter { it.isLetterOrDigit() }

    private class Edges(
        val prequels: List<BangumiNextSlimSubject>,
        val sequels: List<BangumiNextSlimSubject>,
    )

    private companion object {
        const val RELATION_PREQUEL = 2
        const val RELATION_SEQUEL = 3
        const val RELATIONS_PAGE_SIZE = 50

        /** 单个方向最多走多少个节点. 长寿系列 (高达) 的关系图很大, 而这里只关心主线. */
        const val MAX_NODES = 20

        const val CACHE_SIZE = 128
    }
}
