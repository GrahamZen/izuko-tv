/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import androidx.datastore.core.DataStore
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.recommendation.SEQUEL_SEASON_RULES
import me.him188.ani.app.data.recommendation.sequelSeasonCandidates
import me.him188.ani.app.domain.foundation.GitHubFileSources
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeBytes
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * 预先算好的「续作 → 可以换成的那几季」表, 来自
 * [GrahamZen/bangumi-sequel-seasons](https://github.com/GrahamZen/bangumi-sequel-seasons).
 *
 * 推荐里的续作要换成用户没看过的最早一季 (见 RecommendationRepository 的 SequelBatch). 运行时只能顺前传一跳一个
 * 请求地走, 没有前传的条目也要问一次才知道. 那条流水线拿 Bangumi 每周的数据导出, 用本应用同一份走链与挑选代码
 * ([walkPrequelChain] + [sequelSeasonCandidates]) 把全部动画离线算好, 只收有候选季的 (三万部里四千五左右, 一百来 KB).
 * 查表见 [SequelSeasonTable.candidates]: 表覆盖到的条目一个回溯请求都不用发.
 *
 * 表的原文存在 [tableFile], [cache] 里只有下载元数据, 同 [TmdbSubjectMapRepository]: 本地没有表文件就重新下整份.
 */
class SequelSeasonTableRepository(
    private val cache: DataStore<SequelSeasonTableCache>,
    private val tableFile: SystemPath,
    /** 惰性取, 构造期不碰 HttpClientProvider (同 [TmdbSubjectMapRepository]). */
    private val client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) {
    private val logger = logger<SequelSeasonTableRepository>()

    /** 读进来的表; 本地没有 (刚安装、还没下成) 时为 null. */
    private val table = MutableStateFlow<SequelSeasonTable?>(null)
    private val localLoaded = CompletableDeferred<Unit>()

    /** 第一轮检查 (下载或确认不用下载) 结束. 本地还没有表时 [current] 等它一小会儿. */
    private val firstCheckDone = CompletableDeferred<Unit>()

    init {
        scope.launch {
            val loaded = loadLocal()
            table.value = loaded
            localLoaded.complete(Unit)
            if (loaded != null) {
                logger.info { "sequel season table loaded: ${loaded.size} entries (${loaded.dump}, rules=${loaded.rules})" }
            }
            if (loaded != null) firstCheckDone.complete(Unit)
            while (true) {
                refreshIfStale()
                firstCheckDone.complete(Unit)
                delay(CHECK_INTERVAL)
            }
        }
    }

    /**
     * 现在能用的表: 判据版本与本应用一致才给 (见 [SEQUEL_SEASON_RULES]). 刚安装、本地还没有表时最多等第一轮下载
     * [FIRST_DOWNLOAD_WAIT]. 没有就是 `null`, 调用方照旧运行时回溯.
     */
    suspend fun current(): SequelSeasonTable? {
        localLoaded.await()
        if (table.value == null && !firstCheckDone.isCompleted) {
            withTimeoutOrNull(FIRST_DOWNLOAD_WAIT) { firstCheckDone.await() }
        }
        return table.value?.takeIf { it.rules == SEQUEL_SEASON_RULES }
    }

    private suspend fun loadLocal(): SequelSeasonTable? = withContext(Dispatchers.IO_) {
        if (tableFile.exists()) SequelSeasonTable.parse(tableFile.readBytes().decodeToString()) else null
    }

    /** 先写到旁边的临时文件再换过去, 写一半被杀不会留下半张表. */
    private suspend fun writeTableFile(bytes: ByteArray) = withContext(Dispatchers.IO_) {
        val temp = tableFile.resolveSibling(tableFile.name + ".tmp")
        temp.writeBytes(bytes)
        temp.moveTo(tableFile)
    }

    private suspend fun refreshIfStale() {
        val cached = cache.data.first()
        val current = table.value
        if (current != null && currentTimeMillis() - cached.checkedAt < REFRESH_INTERVAL.inWholeMilliseconds) {
            return
        }
        val failures = mutableListOf<String>()
        for (url in TABLE_URLS) {
            val fetched = try {
                client().use {
                    val response = get(url) {
                        expectSuccess = false
                        // ETag 各个 CDN 各算各的, 只对上次成功的那个入口带; 本地没有表时要整份, 不带
                        if (current != null && cached.etag != null && cached.source == url) {
                            header(HttpHeaders.IfNoneMatch, cached.etag)
                        }
                    }
                    when {
                        response.status == HttpStatusCode.NotModified -> Fetched.NotModified
                        response.status.isSuccess() -> Fetched.Body(response.readRawBytes(), response.headers[HttpHeaders.ETag])
                        else -> Fetched.Failed(response.status.toString())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Fetched.Failed(e::class.simpleName.orEmpty())
            }
            val (bytes, etag) = when (fetched) {
                is Fetched.Failed -> {
                    failures += "$url: ${fetched.reason}"
                    continue
                }

                Fetched.NotModified -> {
                    cache.updateData { it.copy(checkedAt = currentTimeMillis()) }
                    return
                }

                is Fetched.Body -> fetched.bytes to fetched.etag
            }
            // 远程内容, 认不出格式就不用 (例如 CDN 回了一张错误页)
            val parsed = SequelSeasonTable.parse(bytes.decodeToString())
            if (parsed == null || parsed.size == 0) {
                failures += "$url: not a table file"
                continue
            }
            // CDN 的边缘缓存可能还是更早的一版 (见 GitHubFileSources): 覆盖到的条目比本机这份还少就换下一个入口
            if (current != null && parsed.maxId < current.maxId) {
                failures += "$url: stale (max_id ${parsed.maxId} < ${current.maxId})"
                continue
            }
            writeTableFile(bytes)
            cache.updateData { SequelSeasonTableCache(etag = etag, source = url, checkedAt = currentTimeMillis()) }
            table.value = parsed
            logger.info { "sequel season table updated from $url: ${parsed.size} entries (${parsed.dump})" }
            return
        }
        logger.info { "sequel season table: no usable source (${failures.joinToString()}), keeping ${table.value?.size ?: 0} entries" }
    }

    private sealed interface Fetched {
        data object NotModified : Fetched
        class Body(val bytes: ByteArray, val etag: String?) : Fetched
        class Failed(val reason: String) : Fetched
    }

    private companion object {
        const val REPOSITORY = "GrahamZen/bangumi-sequel-seasons"
        const val PATH = "bgm-sequel-seasons.tsv"

        /** 下载入口, 按顺序试; 顺序的理由见 [GitHubFileSources]. */
        val TABLE_URLS = GitHubFileSources.urls(REPOSITORY, PATH)

        /** 多久重新检查一次. 表随 Bangumi 的导出每周变一次, 每天看一眼就够 (多数时候是 304). */
        val REFRESH_INTERVAL = 20.hours
        val CHECK_INTERVAL = 6.hours

        /** 刚安装、本地还没有表时, [current] 最多等第一轮下载多久. */
        val FIRST_DOWNLOAD_WAIT = 3.seconds
    }
}

/** 「续作 → 候选季」表的下载元数据; 表的原文在 [SequelSeasonTableRepository] 的单独文件里. */
@Serializable
data class SequelSeasonTableCache(
    val etag: String? = null,
    /** 这份表是从哪个地址下的 (ETag 只对同一个入口有效) */
    val source: String? = null,
    /** 上次检查 (下载成功或确认没变) 的时间 */
    val checkedAt: Long = 0,
) {
    companion object {
        val Empty = SequelSeasonTableCache()
    }
}

/**
 * bangumi-sequel-seasons 的 `bgm-sequel-seasons.tsv` 读进来的样子.
 *
 * 首行表头 `# bangumi-sequel-seasons v1 rules=<判据版本> max_id=<导出里最大的动画条目 id> dump=<导出文件名> …`,
 * 其后一行一个有候选季的续作: `bgm_id<TAB>候选1,候选2,…`, 候选按挑选顺序 (见 [sequelSeasonCandidates]).
 */
class SequelSeasonTable private constructor(
    /** 出表用的判据版本, 见 [SEQUEL_SEASON_RULES]. */
    val rules: Int,
    /** 表覆盖到的最大条目 id. 比它新的条目算表时还没有, 表里查不到不等于没有前传. */
    val maxId: Int,
    /** 算表用的 Bangumi 导出 (日志用). */
    val dump: String,
    private val ids: IntArray,
    /** 第 i 行的候选在 [values] 里的起点; 末尾多一个哨兵 = [values] 的长度. */
    private val starts: IntArray,
    private val values: IntArray,
) {
    val size: Int get() = ids.size

    /**
     * [subjectId] 能换成的那几季, 按挑选顺序. 表覆盖到却没有这一行 = 空 (没有可换的季, 不必回溯);
     * 比表新的条目 = `null` (表答不了, 照旧运行时回溯).
     */
    fun candidates(subjectId: Int): List<Int>? {
        if (subjectId > maxId) return null
        val index = ids.binarySearch(subjectId)
        if (index < 0) return emptyList()
        return values.copyOfRange(starts[index], starts[index + 1]).asList()
    }

    companion object {
        private const val HEADER_PREFIX = "# bangumi-sequel-seasons v1"
        private val RULES = Regex("""\brules=(\d+)""")
        private val MAX_ID = Regex("""\bmax_id=(\d+)""")
        private val DUMP = Regex("""\bdump=(\S+)""")

        /** 认不出是这张表 (表头不对、缺判据版本或覆盖范围) 时为 `null`; 认不出的行跳过. */
        fun parse(text: String): SequelSeasonTable? {
            val lines = text.lines()
            val header = lines.firstOrNull()?.takeIf { it.startsWith(HEADER_PREFIX) } ?: return null
            val rules = RULES.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val maxId = MAX_ID.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val dump = DUMP.find(header)?.groupValues?.get(1).orEmpty()
            val rows = ArrayList<Pair<Int, List<Int>>>(lines.size)
            for (i in 1 until lines.size) {
                val line = lines[i].trimEnd('\r')
                val tab = line.indexOf('\t')
                if (tab <= 0 || line.startsWith('#')) continue
                val id = line.substring(0, tab).toIntOrNull() ?: continue
                val candidates = line.substring(tab + 1).split(',').mapNotNull { it.trim().toIntOrNull() }
                if (candidates.isNotEmpty()) rows += id to candidates
            }
            rows.sortBy { it.first }
            val unique = rows.distinctBy { it.first }
            val ids = IntArray(unique.size) { unique[it].first }
            val starts = IntArray(unique.size + 1)
            val values = IntArray(unique.sumOf { it.second.size })
            var next = 0
            unique.forEachIndexed { index, (_, candidates) ->
                starts[index] = next
                candidates.forEach { values[next++] = it }
            }
            starts[unique.size] = next
            return SequelSeasonTable(rules, maxId, dump, ids, starts, values)
        }
    }
}
