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
import me.him188.ani.app.domain.foundation.GitHubFileSources
import me.him188.ani.app.domain.mediasource.search.AnimeNames
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.delete
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
 * Bangumi 动画的前传 / 续集关系图, 来自 [GrahamZen/bangumi-sequel-seasons](https://github.com/GrahamZen/bangumi-sequel-seasons)
 * 的 `bgm-series-graph.tsv`.
 *
 * 走系列 ([SubjectSeriesIndexService]) 原本一跳一个 `/p1/subjects/{id}/relations` 请求. 那条流水线拿 Bangumi 每周的数据导出,
 * 把有前传或续集的动画 (三万部里九千出头) 的这一页关系连同节点字段原样存下, 表覆盖到的条目在本地照同一套走法算:
 * 系列索引、续作换季、TMDB 找系列主条目、数据源搜索的系列名都不再发请求. 比表新的条目照旧现场取.
 *
 * 表的原文存在 [tableFile], [cache] 里只有下载元数据, 同 [TmdbSubjectMapRepository]: 本地没有表文件就重新下整份.
 */
class SeriesGraphTableRepository(
    private val cache: DataStore<SeriesGraphTableCache>,
    private val tableFile: SystemPath,
    /** 惰性取, 构造期不碰 HttpClientProvider (同 [TmdbSubjectMapRepository]). */
    private val client: () -> ScopedHttpClient,
    scope: CoroutineScope,
    /** 已经不用的旧表文件, 启动时删掉. */
    private val obsoleteFiles: List<SystemPath> = emptyList(),
) {
    private val logger = logger<SeriesGraphTableRepository>()

    /** 读进来的表; 本地没有 (刚安装、还没下成) 时为 null. */
    private val table = MutableStateFlow<SeriesGraphTable?>(null)
    private val localLoaded = CompletableDeferred<Unit>()

    /** 第一轮检查 (下载或确认不用下载) 结束. 本地还没有表时 [current] 等它一小会儿. */
    private val firstCheckDone = CompletableDeferred<Unit>()

    init {
        scope.launch {
            val loaded = loadLocal()
            table.value = loaded
            localLoaded.complete(Unit)
            if (loaded != null) {
                logger.info { "series graph table loaded: ${loaded.size} entries (${loaded.dump})" }
                firstCheckDone.complete(Unit)
                AnimeNames.prewarm(loaded)
            }
            withContext(Dispatchers.IO_) { obsoleteFiles.forEach { it.delete() } }
            while (true) {
                refreshIfStale()
                firstCheckDone.complete(Unit)
                delay(CHECK_INTERVAL)
            }
        }
    }

    /**
     * 现在能用的表. 刚安装、本地还没有表时最多等第一轮下载 [FIRST_DOWNLOAD_WAIT]. 没有就是 `null`, 调用方照旧现场取.
     */
    suspend fun current(): SeriesGraphTable? {
        localLoaded.await()
        if (table.value == null && !firstCheckDone.isCompleted) {
            withTimeoutOrNull(FIRST_DOWNLOAD_WAIT) { firstCheckDone.await() }
        }
        return table.value
    }

    /** 已经读进来的表, 不等; 还没有时为 `null`. */
    fun loaded(): SeriesGraphTable? = table.value

    private suspend fun loadLocal(): SeriesGraphTable? = withContext(Dispatchers.IO_) {
        if (tableFile.exists()) SeriesGraphTable.parse(tableFile.readBytes()) else null
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
            val parsed = SeriesGraphTable.parse(bytes)
            if (parsed == null || parsed.size == 0) {
                failures += "$url: not a table file"
                continue
            }
            // CDN 的边缘缓存可能还是更早的一版 (见 GitHubFileSources): 覆盖到的条目比本机这份还少就换下一个入口
            if (current != null && parsed.maxId < current.maxId) {
                failures += "$url: stale (max_id ${parsed.maxId} < ${current.maxId})"
                continue
            }
            // 全部动画的表取不到、退到只有系列关系的那张时, 不拿它换掉本机已有的全部动画的表
            if (current != null && current.allAnime && !parsed.allAnime) {
                failures += "$url: series graph only"
                continue
            }
            writeTableFile(bytes)
            cache.updateData { SeriesGraphTableCache(etag = etag, source = url, checkedAt = currentTimeMillis()) }
            table.value = parsed
            logger.info { "series graph table updated from $url: ${parsed.size} entries (${parsed.dump})" }
            AnimeNames.prewarm(parsed)
            return
        }
        logger.info { "series graph table: no usable source (${failures.joinToString()}), keeping ${table.value?.size ?: 0} entries" }
    }

    private sealed interface Fetched {
        data object NotModified : Fetched
        class Body(val bytes: ByteArray, val etag: String?) : Fetched
        class Failed(val reason: String) : Fetched
    }

    private companion object {
        const val REPOSITORY = "GrahamZen/bangumi-sequel-seasons"

        /** 全部动画的表 (带别名); 取不到时退到只有系列关系的那张 ([SERIES_GRAPH_PATH]) */
        const val ANIME_PATH = "bgm-anime.tsv"
        const val SERIES_GRAPH_PATH = "bgm-series-graph.tsv"

        /** 下载入口, 按顺序试; 同一张表的各入口的顺序见 [GitHubFileSources]. */
        val TABLE_URLS = GitHubFileSources.urls(REPOSITORY, ANIME_PATH) + GitHubFileSources.urls(REPOSITORY, SERIES_GRAPH_PATH)

        /** 多久重新检查一次. 表随 Bangumi 的导出每周变一次, 每天看一眼就够 (多数时候是 304). */
        val REFRESH_INTERVAL = 20.hours
        val CHECK_INTERVAL = 6.hours

        /** 刚安装、本地还没有表时, [current] 最多等第一轮下载多久. */
        val FIRST_DOWNLOAD_WAIT = 3.seconds
    }
}

/** 系列关系图的下载元数据; 表的原文在 [SeriesGraphTableRepository] 的单独文件里. */
@Serializable
data class SeriesGraphTableCache(
    val etag: String? = null,
    /** 这份表是从哪个地址下的 (ETag 只对同一个入口有效) */
    val source: String? = null,
    /** 上次检查 (下载成功或确认没变) 的时间 */
    val checkedAt: Long = 0,
) {
    companion object {
        val Empty = SeriesGraphTableCache()
    }
}

/**
 * `bgm-anime.tsv` (全部动画) 或 `bgm-series-graph.tsv` (只有有前传或续集的) 读进来的样子. 首行表头
 * `# bangumi-anime v1` 或 `# bangumi-series-graph v1`, 带 `max_id=<导出里最大的动画条目 id> dump=<导出文件名> …`,
 * 其后一行一个条目 (制表符分隔): `bgm_id, 原名, 中文名, 首播日, 本篇集数, 类型, nsfw, 前传, 续集`, 前传 / 续集是逗号分隔的 id,
 * 即 `/p1/subjects/{id}/relations` 那一页 (只看动画, 前 [SERIES_RELATIONS_PAGE_SIZE] 条) 里的前传与续集;
 * 全部动画的表最后多一列别名 (`|` 分隔, 见 [namesOf]).
 *
 * 原文整份留在内存里, 查到哪一行才拆哪一行 (同 [TmdbSubjectMapIndex]): 三万多个节点全拆成对象要多占十几 MB.
 */
class SeriesGraphTable private constructor(
    private val header: String,
    /** 表覆盖到的最大条目 id. 比它新的条目出表时还没有, 表里查不到不等于没有关系. */
    val maxId: Int,
    /** 出表用的 Bangumi 导出 */
    val dump: String,
    private val bytes: ByteArray,
    /** 升序, 无重复 */
    private val ids: IntArray,
    /** 与 [ids] 一一对应的行首偏移 */
    private val lineStarts: IntArray,
) {
    val size: Int get() = ids.size

    /** 收了全部动画 (`bgm-anime.tsv`, 带别名); 只有有前传或续集的 (`bgm-series-graph.tsv`) 为 `false` */
    val allAnime: Boolean get() = header.startsWith(ANIME_HEADER)

    /** 表里的全部条目 id, 升序. */
    internal val subjectIds: List<Int> get() = ids.asList()

    /**
     * [subjectId] 的前传与续集, 同 `/p1/subjects/{id}/relations` 那一页. 表覆盖到却没有这一行 = 没有前传与续集;
     * 比表新的条目 = `null` (表答不了, 照旧现场取).
     */
    internal fun edgesOf(subjectId: Int): SeriesEdges? {
        if (subjectId > maxId) return null
        val row = rowOf(subjectId) ?: return SeriesEdges(prequels = emptyList(), sequels = emptyList())
        return SeriesEdges(
            prequels = row.prequels.mapNotNull { rowOf(it)?.node },
            sequels = row.sequels.mapNotNull { rowOf(it)?.node },
        )
    }

    /** 表里这个条目的节点; 不在表里为 `null`. */
    fun nodeOf(subjectId: Int): SeriesNode? = rowOf(subjectId)?.node

    /** 这个条目的全部名字: 原名、中文名与别名 (全部动画的表才有别名); 不在表里为空. */
    fun namesOf(subjectId: Int): List<String> = rowOf(subjectId)?.names.orEmpty()

    /** 逐行给出条目 id 与它的全部名字 (同 [namesOf]), 建名字索引用. */
    fun forEachNames(block: (subjectId: Int, names: List<String>) -> Unit) {
        for (index in ids.indices) {
            val start = lineStarts[index]
            var end = start
            while (end < bytes.size && bytes[end] != NEWLINE) end++
            if (end > start && bytes[end - 1] == CARRIAGE_RETURN) end--
            val fields = bytes.decodeToString(start, end).split('\t')
            if (fields.size < 3) continue
            block(ids[index], namesIn(fields))
        }
    }

    /**
     * 原名、中文名或别名里原样含有 [fragment] 的条目. 整表的字节扫一遍, 只拆命中的那几行 (同 [rowOf], 不把几万行都拆成对象);
     * 用来找名字里含着某部番的名字的别的作品.
     */
    fun nodesContaining(fragment: String): List<SeriesNode> = nodesContainingAny(listOf(fragment))[fragment].orEmpty()

    /**
     * 同 [nodesContaining], 几个片段一起找: 整表只扫一遍 (认部时一部番常有好几个主标题要找, 逐个扫在电视上是认部最慢的一步),
     * 每个片段各自的结果按表里的顺序.
     */
    fun nodesContainingAny(fragments: Collection<String>): Map<String, List<SeriesNode>> {
        val pairs = fragments.distinct().map { it to it.encodeToByteArray() }.filter { it.second.isNotEmpty() }
        if (pairs.isEmpty()) return emptyMap()
        // 最内层每个字节都要走一遍: 用数组, 不在循环里拆 Pair、建迭代器
        val needles = Array(pairs.size) { pairs[it].second }
        val found = Array(pairs.size) { LinkedHashSet<Int>() }
        val firstBytes = BooleanArray(256)
        for (needle in needles) firstBytes[needle[0].toInt() and 0xFF] = true
        val shortest = needles.minOf { it.size }
        var lineStart = 0
        while (lineStart < bytes.size && bytes[lineStart] != NEWLINE) lineStart++ // 跳过表头
        lineStart++
        var pos = lineStart
        var tabs = 0
        while (pos <= bytes.size - shortest) {
            val byte = bytes[pos]
            when (byte) {
                NEWLINE -> {
                    lineStart = pos + 1
                    tabs = 0
                }

                TAB -> tabs++
                // 原名、中文名、别名几列里命中: 记下行首的 id
                else -> if ((tabs in 1..2 || tabs == ALIASES_COLUMN) && firstBytes[byte.toInt() and 0xFF]) {
                    for (i in needles.indices) {
                        val needle = needles[i]
                        if (needle[0] == byte && pos + needle.size <= bytes.size && matchesAt(pos, needle)) idAt(lineStart)?.let(found[i]::add)
                    }
                }
            }
            pos++
        }
        return pairs.indices.associate { i -> pairs[i].first to found[i].mapNotNull { rowOf(it)?.node } }
    }

    private fun matchesAt(pos: Int, needle: ByteArray): Boolean {
        for (i in needle.indices) if (bytes[pos + i] != needle[i]) return false
        return true
    }

    private fun idAt(lineStart: Int): Int? {
        var id = 0L
        var i = lineStart
        while (i < bytes.size && bytes[i] in '0'.code.toByte()..'9'.code.toByte() && i - lineStart < 10) {
            id = id * 10 + (bytes[i] - '0'.code.toByte())
            i++
        }
        return if (i > lineStart && id <= Int.MAX_VALUE) id.toInt() else null
    }

    internal class Row(val node: SeriesNode, val prequels: List<Int>, val sequels: List<Int>, val names: List<String>)

    private fun rowOf(subjectId: Int): Row? {
        val index = ids.asList().binarySearch(subjectId)
        if (index < 0) return null
        val start = lineStarts[index]
        var end = start
        while (end < bytes.size && bytes[end] != NEWLINE) end++
        if (end > start && bytes[end - 1] == CARRIAGE_RETURN) end--
        return parseRow(bytes.decodeToString(start, end))
    }

    companion object {
        private const val ANIME_HEADER = "# bangumi-anime v1"
        private val HEADER_PREFIXES = listOf(ANIME_HEADER, "# bangumi-series-graph v1")

        /** 别名是第几列 (从 0 数) */
        private const val ALIASES_COLUMN = 11

        /** 什么都答不了的表 (覆盖到 0, 哪个条目都是「表答不了」): 表还没读进来时只按名字认部用 */
        internal val Empty: SeriesGraphTable by lazy {
            SeriesGraphTable(HEADER_PREFIXES.last() + " max_id=0", 0, "", ByteArray(0), IntArray(0), IntArray(0))
        }

        private val MAX_ID = Regex("""\bmax_id=(\d+)""")
        private val DUMP = Regex("""\bdump=(\S+)""")
        private const val NEWLINE = '\n'.code.toByte()
        private const val CARRIAGE_RETURN = '\r'.code.toByte()
        private const val TAB = '\t'.code.toByte()

        /** 认不出是这张表 (表头不对、缺覆盖范围) 时为 `null`; 认不出的行跳过. */
        fun parse(bytes: ByteArray): SeriesGraphTable? {
            var headerEnd = 0
            while (headerEnd < bytes.size && bytes[headerEnd] != NEWLINE) headerEnd++
            val header = bytes.decodeToString(0, headerEnd).trimEnd('\r')
            if (HEADER_PREFIXES.none(header::startsWith)) return null
            val maxId = MAX_ID.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val dump = DUMP.find(header)?.groupValues?.get(1).orEmpty()

            // 高 32 位 id, 低 32 位行首偏移: 按 id 排序后同 id 的取最后一行
            var keys = LongArray(1024)
            var count = 0
            var start = headerEnd + 1
            while (start < bytes.size) {
                var end = start
                var id = 0L
                var digits = 0
                while (end < bytes.size && bytes[end] != NEWLINE) end++
                var i = start
                while (i < end && bytes[i] in '0'.code.toByte()..'9'.code.toByte() && digits < 10) {
                    id = id * 10 + (bytes[i] - '0'.code.toByte())
                    digits++
                    i++
                }
                if (digits > 0 && i < end && bytes[i] == TAB && id <= Int.MAX_VALUE) {
                    if (count == keys.size) keys = keys.copyOf(keys.size * 2)
                    keys[count++] = (id shl 32) or start.toLong()
                }
                start = end + 1
            }
            keys = keys.copyOf(count)
            keys.sort()
            val ids = ArrayList<Int>(count)
            val starts = ArrayList<Int>(count)
            for (key in keys) {
                val id = (key ushr 32).toInt()
                val offset = (key and 0xFFFFFFFFL).toInt()
                if (ids.isNotEmpty() && ids.last() == id) {
                    starts[starts.size - 1] = offset
                } else {
                    ids += id
                    starts += offset
                }
            }
            return SeriesGraphTable(header, maxId, dump, bytes, ids.toIntArray(), starts.toIntArray())
        }

        /**
         * 一行: 前 9 列与客户端从 `/p1/subjects/{id}/relations` 构造的节点逐项对应 (没有封面); 第 10、11 列是本篇第一集的 sort
         * (空 = 1) 与本篇中间的特别篇数 (空 = 0), 没有这两列的旧表当作不知道; 第 12 列是别名. 格式不对为 `null`.
         */
        internal fun parseRow(line: String): Row? {
            val fields = line.split('\t')
            if (fields.size < 9) return null
            val id = fields[0].toIntOrNull() ?: return null
            val episodes = fields[4].toIntOrNull()?.takeIf { it > 0 }
            val node = SeriesNode(
                id = id,
                name = fields[1],
                nameCn = fields[2],
                imageLarge = "",
                metaTags = listOfNotNull(fields[5].ifEmpty { null }),
                nsfw = fields[6] == "1",
                airDate = PackedDate.parseFromDate(fields[3]),
                episodes = episodes,
                firstSort = if (episodes != null && fields.size > 9) fields[9].toIntOrNull() ?: 1 else null,
                inlineSpecialCount = fields.getOrNull(10)?.toIntOrNull() ?: 0,
            )
            return Row(node, fields[7].toIdList(), fields[8].toIdList(), namesIn(fields))
        }

        /** 一行的原名、中文名与别名, 去掉空的与重复的 */
        private fun namesIn(fields: List<String>): List<String> =
            (listOf(fields[1], fields[2]) + fields.getOrNull(ALIASES_COLUMN).orEmpty().split('|')).filter { it.isNotBlank() }.distinct()

        private fun String.toIdList(): List<Int> = if (isEmpty()) emptyList() else split(',').mapNotNull { it.trim().toIntOrNull() }
    }
}
