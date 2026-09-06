/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network.schedule

import androidx.datastore.core.DataStore
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * 时间表的三份数据都在这里, 且**一律先读缓存, 过期了才发请求**:
 *
 * 1. **在播名册**: `next.bgm.tv/p1/calendar` (bangumi 自己的每日放送), 按星期分 7 组.
 *    手写请求而不是走生成代码: 它的响应是 `Map<String, List<...>>`, 生成器对这个形状会产出
 *    不带类型参数的 `List` 而编译不过 (见 `datasource/bangumi/build.gradle.kts` 的 keepPaths).
 * 2. **播出时刻**: bangumi-data 的按月文件里的 `broadcast` (`R/<起始时刻>/P7D`). bangumi 自己
 *    只给日期不给时刻, 而时间表页是按时刻排序并画"现在"这条线的.
 *    按月取: 条目的首播月由它的分集播出日期反推, 不需要把整个 bangumi-data (5.8 MB) 拉下来.
 * 3. **分集**: v0 的公开分集接口. 一个条目一次, 之后按 [EPISODES_TTL] 复用.
 */
class BangumiScheduleSource(
    private val client: ScopedHttpClient,
    private val store: DataStore<AnimeScheduleCache>,
) {
    private val logger = logger<BangumiScheduleSource>()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /**
     * 在播名册, 星期一到星期日 (1..7).
     */
    suspend fun calendar(): Map<Int, List<ScheduleSubject>> {
        cached { it.calendar.takeIf { c -> c.isNotEmpty() && !it.calendarFetchedAt.isStale(CALENDAR_TTL) } }
            ?.let { return it }

        val fetched = try {
            val body = client.use { get("$NEXT_BASE_URL/p1/calendar").bodyAsText() }
            json.decodeFromString(CalendarResponseSerializer, body)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch bangumi calendar" }
            // 拉不到就用旧的 (哪怕过期), 总比空白强
            return store.data.first().calendar
        }

        val roster = fetched.mapNotNull { (key, items) ->
            val weekday = key.toIntOrNull()?.takeIf { it in 1..7 } ?: return@mapNotNull null
            weekday to items.map { item ->
                ScheduleSubject(
                    id = item.subject.id,
                    name = item.subject.name,
                    nameCn = item.subject.nameCN,
                    imageLarge = item.subject.images?.large.orEmpty(),
                    weekday = weekday,
                )
            }
        }.toMap()
        update { it.copy(calendar = roster, calendarFetchedAt = currentTimeMillis()) }
        return roster
    }

    /**
     * 缓存里已有的分集与播出时刻, **不发任何请求**.
     *
     * 时间表进页面时先用它把整屏画出来: 热启动 (一天内来过) 时全部命中, 一个请求都不发.
     */
    suspend fun cachedEpisodesAndRules(subjectIds: Collection<Int>): CachedScheduleData {
        val cache = store.data.first()
        val ids = subjectIds.toSet()
        return CachedScheduleData(
            episodes = ids.mapNotNull { id ->
                cache.episodes[id]?.takeIf { !it.fetchedAt.isStale(EPISODES_TTL) }?.let { id to it.list }
            }.toMap(),
            rules = ids.mapNotNull { id -> cache.broadcastRules[id]?.let { id to it } }.toMap(),
        )
    }

    /**
     * 一批条目的分集. 缓存命中的直接给, 其余**并发**回源 ([FETCH_CONCURRENCY] 条并行) 再**一次性**落盘.
     *
     * 一天的名册有十几部, 逐个串行取要十几个来回 (电视上够看见一天一天慢慢填); 而逐个落盘意味着
     * 每取一部就把整份缓存重写一遍 —— 一次冷加载写上百次, 全在 TV 那块慢闪存上.
     */
    suspend fun episodesOfMany(subjectIds: Collection<Int>): Map<Int, List<ScheduleEpisode>> = coroutineScope {
        val cache = store.data.first()
        val result = mutableMapOf<Int, List<ScheduleEpisode>>()
        val missing = mutableListOf<Int>()
        for (id in subjectIds.toSet()) {
            val hit = cache.episodes[id]?.takeIf { !it.fetchedAt.isStale(EPISODES_TTL) }
            if (hit != null) result[id] = hit.list else missing += id
        }
        if (missing.isEmpty()) return@coroutineScope result

        val semaphore = Semaphore(FETCH_CONCURRENCY)
        val fetched = missing
            .map { id -> async { id to semaphore.withPermit { fetchEpisodes(id) } } }
            .awaitAll()

        val now = currentTimeMillis()
        val toStore = fetched.mapNotNull { (id, list) -> list?.let { id to CachedEpisodes(it, now) } }.toMap()
        if (toStore.isNotEmpty()) {
            update { it.copy(episodes = it.episodes + toStore) }
        }
        for ((id, list) in fetched) {
            // 取失败的退回旧缓存 (哪怕过期), 总比这一部整天不出现强
            result[id] = list ?: cache.episodes[id]?.list.orEmpty()
        }
        result
    }

    /** 回源取一个条目的分集; 失败返回 `null` (与"确实没有分集"区分开, 后者是空列表). */
    private suspend fun fetchEpisodes(subjectId: Int): List<ScheduleEpisode>? {
        return try {
            val body = client.use {
                get("$V0_BASE_URL/v0/episodes") {
                    url.parameters.append("subject_id", subjectId.toString())
                    url.parameters.append("type", "0") // 只要正片
                    url.parameters.append("limit", "100")
                }.bodyAsText()
            }
            json.decodeFromString(V0EpisodePage.serializer(), body).data.map {
                ScheduleEpisode(
                    id = it.id,
                    name = it.name,
                    nameCn = it.nameCn,
                    sort = it.sort,
                    ep = it.ep,
                    airDate = it.airdate,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch episodes of subject $subjectId for schedule" }
            null
        }
    }

    /**
     * 条目的播出周期 (起始时刻 + 间隔). 找不到返回 `null`.
     *
     * [firstAirDate] 是条目第一集的播出日期 (`yyyy-MM-dd`), 用来定位 bangumi-data 的月文件.
     */
    suspend fun recurrenceOf(subjectId: Int, firstAirDate: String?): SubjectRecurrence? {
        val rule = broadcastRuleOf(subjectId, firstAirDate) ?: return null
        return SubjectRecurrence(
            startTime = Instant.parse(rule.startTime),
            interval = rule.intervalDays.days,
        )
    }

    suspend fun broadcastRuleOf(subjectId: Int, firstAirDate: String?): BroadcastRule? {
        cached { it.broadcastRules[subjectId] }?.let { return it }
        val month = firstAirDate?.toYearMonthOrNull() ?: return null
        loadBangumiDataMonth(month)
        return store.data.first().broadcastRules[subjectId]
    }

    /**
     * 把 bangumi-data 一个月的文件读进缓存. 月文件不大 (季度首月 ~150 KB, 其余几 KB),
     * 而且几乎不变, 所以 TTL 给得很长.
     */
    private suspend fun loadBangumiDataMonth(month: YearMonth) {
        val key = month.key
        cached { cache -> cache.bangumiDataMonths[key]?.takeIf { !it.isStale(BANGUMI_DATA_TTL) } }?.let { return }

        val items = try {
            val body = client.use { get(month.bangumiDataUrl()).bodyAsText() }
            json.decodeFromString(ListSerializer(BangumiDataItem.serializer()), body)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch bangumi-data for $key" }
            return
        }

        val rules = buildMap {
            for (item in items) {
                val id = item.sites.firstOrNull { it.site == "bangumi" }?.id?.toIntOrNull() ?: continue
                val rule = item.broadcast?.toBroadcastRuleOrNull() ?: continue
                put(id, rule)
            }
        }
        logger.info { "Loaded ${rules.size} broadcast rules from bangumi-data $key" }
        update {
            it.copy(
                broadcastRules = it.broadcastRules + rules,
                bangumiDataMonths = it.bangumiDataMonths + (key to currentTimeMillis()),
            )
        }
    }

    private suspend fun <T : Any> cached(select: (AnimeScheduleCache) -> T?): T? = select(store.data.first())

    private suspend fun update(block: (AnimeScheduleCache) -> AnimeScheduleCache) {
        lock.withLock { store.updateData { block(it) } }
    }

    private fun Long.isStale(ttl: Duration): Boolean = currentTimeMillis() - this > ttl.inWholeMilliseconds

    companion object {
        private const val NEXT_BASE_URL = "https://next.bgm.tv"
        private const val V0_BASE_URL = "https://api.bgm.tv"

        /** 每日放送变化不快, 但新番开播那天要能当天看到. */
        private val CALENDAR_TTL = 6.hours

        /** 分集列表变化很慢 (补录/改名), 一天一次足够. */
        private val EPISODES_TTL = 1.days

        /** bangumi-data 的月文件基本不动. */
        private val BANGUMI_DATA_TTL = 7.days

        /**
         * 一批分集同时在飞的上限. 往大了调能更快填满一天, 但 bgm 会开始回 429 ——
         * 那比慢更糟 (429 会被当成"这部没有分集"缓存进去).
         */
        private const val FETCH_CONCURRENCY = 6
    }
}

// region 缓存模型

@Serializable
data class AnimeScheduleCache(
    val calendar: Map<Int, List<ScheduleSubject>> = emptyMap(),
    val calendarFetchedAt: Long = 0,
    val episodes: Map<Int, CachedEpisodes> = emptyMap(),
    val broadcastRules: Map<Int, BroadcastRule> = emptyMap(),
    /** bangumi-data 已经读过的月份 -> 读取时间, 键是 `"2026-07"`. */
    val bangumiDataMonths: Map<String, Long> = emptyMap(),
) {
    companion object {
        val Empty = AnimeScheduleCache()
    }
}

@Serializable
data class ScheduleSubject(
    val id: Int,
    val name: String,
    val nameCn: String,
    val imageLarge: String,
    /** 1 = 星期一 */
    val weekday: Int,
)

@Serializable
data class ScheduleEpisode(
    val id: Int,
    val name: String,
    val nameCn: String,
    val sort: String,
    val ep: String?,
    /** `yyyy-MM-dd`, 可能是空串 (未定档) */
    val airDate: String,
)

/** @see BangumiScheduleSource.cachedEpisodesAndRules */
class CachedScheduleData(
    val episodes: Map<Int, List<ScheduleEpisode>>,
    val rules: Map<Int, BroadcastRule>,
)

@Serializable
data class CachedEpisodes(
    val list: List<ScheduleEpisode>,
    val fetchedAt: Long,
)

@Serializable
data class BroadcastRule(
    /** ISO 8601 时刻 */
    val startTime: String,
    val intervalDays: Int,
)

// endregion

// region 网络 DTO (只声明用得上的字段)

@Serializable
private class CalendarItem(val subject: CalendarSubject)

@Serializable
private class CalendarSubject(
    val id: Int,
    val name: String,
    val nameCN: String,
    val images: CalendarImages? = null,
)

@Serializable
private class CalendarImages(val large: String? = null)

/** 响应是 `{"1": [...], ..., "7": [...]}`, 键是星期几. */
private val CalendarResponseSerializer = MapSerializer(
    String.serializer(),
    ListSerializer(CalendarItem.serializer()),
)

@Serializable
private class V0EpisodePage(val data: List<V0Episode> = emptyList())

@Serializable
private class V0Episode(
    val id: Int,
    val name: String = "",
    @kotlinx.serialization.SerialName("name_cn") val nameCn: String = "",
    val sort: String = "",
    val ep: String? = null,
    val airdate: String = "",
)

@Serializable
private class BangumiDataItem(
    val broadcast: String? = null,
    val sites: List<BangumiDataSite> = emptyList(),
)

@Serializable
private class BangumiDataSite(val site: String = "", val id: String = "")

// endregion

// region 解析

private class YearMonth(val year: Int, val month: Int) {
    val key get() = "$year-${month.toString().padStart(2, '0')}"
    fun bangumiDataUrl() =
        "https://cdn.jsdelivr.net/gh/bangumi-data/bangumi-data@master/data/items/$year/${
            month.toString().padStart(2, '0')
        }.json"
}

private fun String.toYearMonthOrNull(): YearMonth? {
    val parts = split('-')
    if (parts.size < 2) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    if (month !in 1..12) return null
    return YearMonth(year, month)
}

/**
 * `R/2026-07-01T13:00:00.000Z/P7D` -> 起始时刻 + 间隔天数.
 *
 * **间隔必须落在 1..60**: bangumi-data 里存在 `P0D` (播出规则不明的条目), 而下游会拿 interval
 * 当除数算"第几集", 0 会直接崩. 解析不出来就当没有这条规则, 让调用方回落.
 */
internal fun String.toBroadcastRuleOrNull(): BroadcastRule? {
    val match = BROADCAST_REGEX.matchEntire(this) ?: return null
    val days = match.groupValues[2].toIntOrNull() ?: return null
    if (days !in 1..60) return null
    return BroadcastRule(startTime = match.groupValues[1], intervalDays = days)
}

private val BROADCAST_REGEX = Regex("""^R/(.+)/P(\d+)D$""")

// endregion
