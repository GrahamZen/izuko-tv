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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.foundation.GitHubFileSources
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 预先算好的「Bangumi 条目 → TMDB 条目」对应表, 来自 [GrahamZen/bangumi-tmdb-map](https://github.com/GrahamZen/bangumi-tmdb-map).
 *
 * 那个仓库每天用本应用的 TMDB 匹配器 (与 [TmdbImageService] 同一份代码、详情页的输入口径) 离线跑一遍全部动画条目,
 * 连同人工修正一起发布成一张表. 查得到的条目直接用表里的结果: 省掉设备上逐个别名搜 TMDB (难的条目要十几个请求、
 * 好几秒) 与为拿别名先请求的 bgm 条目详情; 背景图连 TMDB 接口都不用请求, 大陆接口不通时只要图床通就能出图.
 *
 * 查不到的条目 (新条目还没算到、TMDB 上没有、表没下载成功) 照旧由 [TmdbImageService] 自己搜.
 */
class TmdbSubjectMapRepository(
    private val cache: DataStore<TmdbSubjectMapCache>,
    /** 惰性取, 与 [me.him188.ani.app.domain.foundation.BangumiMirrorListRepository] 同理: 构造期不碰 HttpClientProvider. */
    private val client: () -> ScopedHttpClient,
    /** 设置里关了 TMDB 图片就不下载 */
    private val enabled: Flow<Boolean>,
    scope: CoroutineScope,
) {
    private val logger = logger<TmdbSubjectMapRepository>()

    /** 解析好的表; null = 还没从本地读出来. */
    private val entries = MutableStateFlow<Map<Int, TmdbSubjectMapEntry>?>(null)

    /**
     * 第一轮检查 (下载或确认不用下载) 结束. 本地还没有表 (刚安装) 时, [lookup] 会等它一小会儿,
     * 免得刚装好的头几个条目全都自己去搜.
     */
    private val firstCheckDone = CompletableDeferred<Unit>()

    init {
        scope.launch {
            val start = TimeSource.Monotonic.markNow()
            val cached = cache.data.first()
            val parsed = parseTmdbSubjectMap(cached.tsv)
            entries.value = parsed
            logger.info { "tmdb subject map loaded: ${parsed.size} entries in ${start.elapsedNow().inWholeMilliseconds}ms" }
            if (cached.tsv.isNotEmpty()) firstCheckDone.complete(Unit)
            while (true) {
                if (enabled.first()) refreshIfStale()
                firstCheckDone.complete(Unit)
                delay(CHECK_INTERVAL)
            }
        }
    }

    /** 同步看一眼: 表已读进来且有这个条目才返回, 否则 null (不等读盘, 不等下载). */
    fun peek(subjectId: Int): TmdbSubjectMapEntry? = entries.value?.get(subjectId)

    /** 表里这个条目的结果; 表里没有返回 null. */
    suspend fun lookup(subjectId: Int): TmdbSubjectMapEntry? {
        val map = entries.value ?: entries.filterNotNull().first()
        if (map.isEmpty() && !firstCheckDone.isCompleted) {
            withTimeoutOrNull(FIRST_DOWNLOAD_WAIT) { firstCheckDone.await() }
            return entries.value?.get(subjectId)
        }
        return map[subjectId]
    }

    private suspend fun refreshIfStale() {
        val cached = cache.data.first()
        if (cached.tsv.isNotEmpty() && currentTimeMillis() - cached.checkedAt < REFRESH_INTERVAL.inWholeMilliseconds) {
            return
        }
        for (url in MAP_URLS) {
            val fetched = try {
                client().use {
                    val response = get(url) {
                        expectSuccess = false
                        // ETag 各个 CDN 各算各的, 只对上次成功的那个入口带
                        if (cached.etag != null && cached.source == url) header(HttpHeaders.IfNoneMatch, cached.etag)
                    }
                    when {
                        response.status == HttpStatusCode.NotModified -> Fetched.NotModified
                        response.status.isSuccess() -> Fetched.Body(response.bodyAsText(), response.headers[HttpHeaders.ETag])
                        else -> Fetched.Failed(response.status.toString())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Fetched.Failed(e::class.simpleName.orEmpty())
            }
            val (tsv, etag) = when (fetched) {
                is Fetched.Failed -> {
                    logger.info { "tmdb subject map: $url unreachable (${fetched.reason})" }
                    continue
                }

                Fetched.NotModified -> {
                    cache.updateData { it.copy(checkedAt = currentTimeMillis()) }
                    logger.info { "tmdb subject map: not modified ($url)" }
                    return
                }

                is Fetched.Body -> fetched.tsv to fetched.etag
            }
            // 远程内容, 认不出格式就不用 (例如 CDN 回了一张错误页)
            val parsed = parseTmdbSubjectMap(tsv)
            if (!tsv.startsWith(HEADER_PREFIX) || parsed.isEmpty()) {
                logger.warn { "tmdb subject map from $url is not a map file, ignoring" }
                continue
            }
            // CDN 的边缘缓存可能还是很早的一版 (gcore 不认主动刷新, 最长 12 小时): 条目数不到本机这份的一半就当它过时, 换下一个入口
            val current = entries.value
            if (current != null && parsed.size < current.size / 2) {
                logger.warn { "tmdb subject map from $url has ${parsed.size} entries, cached one has ${current.size}; looks stale, ignoring" }
                continue
            }
            cache.updateData { TmdbSubjectMapCache(tsv = tsv, etag = etag, source = url, checkedAt = currentTimeMillis()) }
            entries.value = parsed
            logger.info { "tmdb subject map updated from $url: ${parsed.size} entries" }
            return
        }
        logger.info { "tmdb subject map: all sources unreachable, keeping ${entries.value?.size ?: 0} entries" }
    }

    private sealed interface Fetched {
        data object NotModified : Fetched
        class Body(val tsv: String, val etag: String?) : Fetched
        class Failed(val reason: String) : Fetched
    }

    private companion object {
        const val REPOSITORY = "GrahamZen/bangumi-tmdb-map"
        const val PATH = "map/bgm-tmdb.tsv"
        const val HEADER_PREFIX = "# bangumi-tmdb-map v1"

        /** 下载入口, 按顺序试. 表每天更新一次, 推送后会主动刷新 jsDelivr 的缓存, 顺序的理由见 [GitHubFileSources]. */
        val MAP_URLS = GitHubFileSources.urls(REPOSITORY, PATH)

        /** 多久重新检查一次. 表每天更新, 电视上 app 常常一开几天, 所以运行中也按这个间隔再看. */
        val REFRESH_INTERVAL = 20.hours
        val CHECK_INTERVAL = 6.hours

        /** 刚安装、本地还没有表时, 查询最多等第一轮下载多久. */
        val FIRST_DOWNLOAD_WAIT = 3.seconds
    }
}

/** 对应表的本地缓存: 原文照存, 读出来再解析. */
@Serializable
data class TmdbSubjectMapCache(
    val tsv: String = "",
    val etag: String? = null,
    /** 这份表是从哪个地址下的 (ETag 只对同一个入口有效) */
    val source: String? = null,
    /** 上次检查 (下载成功或确认没变) 的时间 */
    val checkedAt: Long = 0,
) {
    companion object {
        val Empty = TmdbSubjectMapCache()
    }
}

/** TMDB 上的一个条目或剧集的一季, 即 TMDB API 路径 `tv/1`、`movie/2`、`collection/3`、`tv/1/season/0`. */
data class TmdbSubjectMapRef(val type: String, val id: Int, val season: Int? = null) {
    companion object {
        private val REGEX = Regex("""^(tv|movie|collection)/(\d+)(?:/season/(\d+))?$""")

        fun parse(text: String): TmdbSubjectMapRef? {
            val m = REGEX.matchEntire(text.trim()) ?: return null
            val (type, id, season) = m.destructured
            if (season.isNotEmpty() && type != "tv") return null
            return TmdbSubjectMapRef(type, id.toIntOrNull() ?: return null, season.toIntOrNull())
        }
    }
}

/**
 * 表里一个条目的结果.
 *
 * @property backdrop 整部背景图所属的 TMDB 条目; null = 没有整部背景图
 * @property backdropPath 背景图路径 (`/xxx.jpg`); 人工修正可能只给条目不给图, 此时为 null
 * @property stills 剧照链当时最后用的出处: `tv/<id>` (整部剧, 客户端照同样的规则全量索引)、
 *   `tv/<id>/season/0` (只索引 S0, 衍生作挂在本篇 S0 下的情形)、`movie/<id>` 或 `collection/<id>`
 * @property manual 人工修正过的. 人工修正且什么都没给 = 确认 TMDB 上没有对应
 * @property episodes 每一集对应 TMDB 第几季第几集 (编码见 [TmdbEpisodeMap]); 没有则客户端照 [stills] 全量索引、自己对集
 */
data class TmdbSubjectMapEntry(
    val backdrop: TmdbSubjectMapRef?,
    val backdropPath: String?,
    val stills: List<TmdbSubjectMapRef>,
    val manual: Boolean,
    val episodes: String? = null,
) {
    /** 剧照出处的原文. */
    val stillsKey: String get() = stills.joinToString(",") { it.text() }

    /**
     * 标记分集缓存是照哪一版表建的, 人工修正改了就要重建. 没给剧照出处的人工修正, 分集跟着背景图条目走,
     * 所以改背景图条目也算改了.
     */
    val stillsBuildKey: String get() = stillsKey.ifEmpty { "follow:" + (backdrop?.text() ?: "none") }

    private fun TmdbSubjectMapRef.text() = "$type/$id" + (season?.let { "/season/$it" } ?: "")
}

/** 解析对应表 (制表符分隔: bgm_id, backdrop, backdrop_path, stills, source, episodes). 认不出的行跳过. */
internal fun parseTmdbSubjectMap(tsv: String): Map<Int, TmdbSubjectMapEntry> {
    val out = HashMap<Int, TmdbSubjectMapEntry>()
    for (line in tsv.lineSequence()) {
        if (line.isEmpty() || line[0] == '#') continue
        val cols = line.split('\t')
        val id = cols[0].toIntOrNull() ?: continue
        val backdropText = cols.getOrNull(1).orEmpty()
        val backdrop = if (backdropText.isEmpty()) null else TmdbSubjectMapRef.parse(backdropText)
        if (backdropText.isNotEmpty() && (backdrop == null || backdrop.season != null)) continue
        val path = cols.getOrNull(2)?.takeIf { backdrop != null && it.startsWith("/") && '/' !in it.substring(1) }
        val stillTexts = cols.getOrNull(3).orEmpty().split(',').filter { it.isNotEmpty() }
        val stills = stillTexts.mapNotNull { TmdbSubjectMapRef.parse(it) }
        if (stills.size != stillTexts.size) continue
        // 逐集对位认不出就当没有 (照旧全量索引), 不连累整行
        val episodes = cols.getOrNull(5)?.takeIf { it.isNotBlank() && TmdbEpisodeMap.parse(it) != null }
        out[id] = TmdbSubjectMapEntry(backdrop, path, stills, manual = cols.getOrNull(4) == "manual", episodes = episodes)
    }
    return out
}
