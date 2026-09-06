/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSlimSubject
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
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
     *
     * = 全部**祖先** + 名字被自己包含的**续集**. 后半条是为前导篇/序章准备的: 它们的本传恰恰是
     * 续集 (`ef - a tale of memories. ~prologue~` 的本传是 `ef - a tale of memories.`,
     * `THE IDOLM@STER Prologue SideM` 的是 `偶像大师 SideM`), 排掉续集这些条目就一张图都拿不到.
     *
     * 但**不能无条件收下续集**: 299802「ふたりはプリキュア総集編…2020edition」的续集是另一部
     * 総集編「Max Heart 総集編…2021edition」, 拿它当母条目会搜回毫不相干的海报. 名字包含关系
     * 正好把两类分开 —— 前导篇的名字是"本传名 + 后缀", 而两部総集編只是同系列的兄弟.
     *
     * 兄弟续集 (既不是祖先也不是本传的那些) 排在最后当末位候选: 它们多半搜不到东西, 但"搜不到"
     * 会让调用方走二次回落, 结果反而比一开始就没有名字更好 (177998「Re:ゼロから始める休憩時間」).
     * **総集編除外** —— 它的兄弟是另一部総集編, 不是本传, 拿去搜会取回毫不相干的图 (299802).
     *
     * 这三条都是锚点测试抓出来的 (`ANI_TMDB_E2E=1`), 改这里必须重跑.
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
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<Int, SubjectRelationIndex>()

    suspend fun getSubjectRelationIndex(subjectId: Int): SubjectRelationIndex {
        cacheLock.withLock { cache[subjectId] }?.let { return it }
        val computed = withContext(ioDispatcher) {
            try {
                compute(subjectId)
            } catch (e: Exception) {
                throw RepositoryException.wrapOrThrowCancellation(e)
            }
        }
        cacheLock.withLock {
            cache[subjectId] = computed
            while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
        return computed
    }

    private suspend fun compute(subjectId: Int): SubjectRelationIndex {
        val edges = HashMap<Int, Edges>()

        suspend fun edgesOf(id: Int): Edges = edges.getOrPut(id) { fetchEdges(id) }

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
        // 総集編/合集类条目不收兄弟名 (见 seriesRootNames)
        val isCompilation = selfNames.any { name -> COMPILATION_MARKERS.any { name.contains(it) } }
        val siblingSequels = if (isCompilation) emptyList() else sequels.filter { it !in parentWorks }
        return SubjectRelationIndex(
            seriesMainSubjectIds = mainLine,
            seriesMainSubjectNames = mainLine.flatMap { namesOf(it, edges) },
            seriesRootNames = ancestors.flatMap { namesOf(it, edges) } +
                    parentWorks.flatMap { namesOf(it, edges) } +
                    siblingSequels.flatMap { namesOf(it, edges) },
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

        /** 归一化后的総集編标记 (标点已去掉). */
        val COMPILATION_MARKERS = listOf("総集編", "総集篇", "总集篇", "总集编")
    }
}
