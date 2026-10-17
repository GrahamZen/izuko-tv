/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.models.subject.CanonicalTagKind
import me.him188.ani.app.data.models.subject.RatingCounts
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.network.BatchSubjectDetails
import me.him188.ani.app.data.network.LightSubjectRelations
import me.him188.ani.app.data.network.SubjectRecommendationItem
import me.him188.ani.app.data.network.SubjectSearchFilters
import me.him188.ani.app.data.network.SubjectSearchPage
import me.him188.ani.app.data.network.mapper.orBangumiPlaceholder
import me.him188.ani.app.domain.search.SearchSort
import me.him188.ani.app.domain.search.SubjectType
import me.him188.ani.datasources.api.PackedDate
import kotlin.math.abs

/**
 * 推荐目录: 探索页推荐按排名 / 热度搜候选的那几种查询 (见 RecommendationRepository 的 searchPage), 在本地按 bangumi 搜索同样的条件、
 * 同样的排序与翻页答出来, 推荐算法本身一行不变. 数据由 [bangumi-sequel-seasons](https://github.com/GrahamZen/bangumi-sequel-seasons)
 * 的 `scripts/rec.py` 用 bangumi 搜索接口生成 (每周一次主目录, 每天一次近期分片), 下载与缓存见 RecommendationCatalogRepository.
 *
 * 收录范围决定能答哪些查询 ([search] 答不了时返回 `null`, 调用方照旧问 bangumi):
 * - 主目录: 有排名、评分人数 >= [RecommendationCatalogManifest.Catalog.minVotes] 的全部动画 ⇒「有排名 + 评分人数下限」的排行榜查询全能答;
 *   另含「没收藏时的老番榜」按热度要翻到的那些 (有排名、首播早于半年前).
 * - 近期分片: [RecommendationCatalogManifest.RecentScope.since] 以来开播的, 按热度取到收藏总数 [RecommendationCatalogManifest.RecentScope.minCollection]
 *   为止 ⇒ 按热度排、首播在那之后的查询 (本季、大家最近在看、新番榜) 头几页能答; 再往后的低于本季那道观众规模地板, 调用方本来也不要.
 *
 * 与现场搜索的差别 (2026-10-17 用一个账号的 15 路查询逐路对照): 各路前 20 名重合 19~20 个; bangumi 按标签匹配的是每部约 30 个标签,
 * 目录里只存了推荐认的标签词表 ([RecommendationCatalogManifest.tags]) 里的那些, 查询的标签本来就都在词表里. 热度序 bangumi 用的是它
 * 搜索索引里的收藏总数, 与接口当下给的数字略有出入 (相邻两部偶尔对调), 这里按目录里的收藏总数排.
 */
class RecommendationCatalog internal constructor(
    val manifest: RecommendationCatalogManifest,
    /** 主目录与近期分片合在一起 (同一部两边都有时取近期分片的, 那份每天更新). */
    private val entries: Map<Int, Entry>,
    private val recs: Map<Int, IntArray>,
) {
    val size: Int get() = entries.size

    internal class Entry(
        val id: Int,
        val name: String,
        val nameCn: String,
        /** `YYYY-MM-DD`; 没有为空. */
        val date: String,
        val episodes: Int,
        val metaTags: List<String>,
        /** 词表里的标签: 高票的票数记 [CONFIDENT_COUNT], 其余记 1, 另有一个空名字的标签占着最高票 (见 [tagsOf]). */
        val tags: List<Tag>,
        val tagNames: Set<String>,
        val cover: String,
        val score10: Int,
        val votes: Int,
        val rank: Int,
        val stats: SubjectCollectionStats,
    ) {
        val imageLarge: String get() = cover.takeIf { it.isNotEmpty() }?.let { COVER_PREFIX + it }.orBangumiPlaceholder()
    }

    /**
     * 按 bangumi 搜索的条件与排序取一页; 目录答不了 (条件超出收录范围、数据过期、词表里没有查询的标签) 时为 `null`.
     * [today] 是今天 (`YYYY-MM-DD`, UTC+8, 同 bangumi), 判断目录与近期分片还新不新.
     */
    fun search(
        keyword: String,
        filters: SubjectSearchFilters?,
        sort: SearchSort,
        offset: Int,
        limit: Int,
        today: String,
    ): SubjectSearchPage? {
        val f = filters ?: return null
        if (keyword.isNotEmpty() || !f.excludeCollectionTypes.isNullOrEmpty() || f.nsfw != false) return null
        val ranks = bounds(f.ranks) ?: return null
        val ratingCounts = bounds(f.ratingCounts) ?: return null
        val ratings = bounds(f.ratings) ?: return null
        val airDates = bounds(f.airDates) ?: return null
        val tags = f.tags.orEmpty()
        val metaTags = f.metaTags.orEmpty()
        if (tags.any { it !in vocabulary }) return null
        if (!covers(sort, ranks, ratingCounts, airDates, tags, metaTags, ratings, today)) return null

        val matched = entries.values.asSequence()
            .filter { e -> ranks.all { it.matches(e.rank.toDouble()) } }
            .filter { e -> ratingCounts.all { it.matches(e.votes.toDouble()) } }
            .filter { e -> ratings.all { it.matches(e.score10 / 10.0) } }
            .filter { e -> airDates.isEmpty() || (e.date.isNotEmpty() && airDates.all { it.matches(e.date) }) }
            .filter { e -> tags.all { it in e.tagNames } }
            .filter { e -> metaTags.all { it in e.metaTags } }
            .toMutableList()
        when (sort) {
            SearchSort.RANK -> matched.sortWith(compareBy<Entry> { it.rank }.thenBy { it.id })
            SearchSort.COLLECTION -> matched.sortWith(compareByDescending<Entry> { it.stats.collect }.thenBy { it.id })
            else -> return null
        }
        val total = minOf(matched.size, HIT_CAP)
        val page = if (offset >= total) emptyList() else matched.subList(offset, minOf(offset + limit, total))
        return SubjectSearchPage(page.map { it.toDetails() }, total)
    }

    /** [subjectId] 的「看过的人还看了」(动画, bangumi 原序); 目录里没有这一部为 `null` (空表 = bangumi 没有这份数据). */
    fun recsOf(subjectId: Int): List<Int>? = recs[subjectId]?.asList()

    /** 目录里的这一部, 给「看过的人还看了」画卡片; 不在目录里为 `null`. */
    internal fun entryOf(subjectId: Int): Entry? = entries[subjectId]

    /**
     * 播放器 / 详情页「相关推荐」整份从目录取: 这一部有 recs 记录、推荐到的每一部也都在目录里时才给 (缺一部就得问 bangumi, 返回 `null`).
     */
    fun recommendationItemsOf(subjectId: Int): List<SubjectRecommendationItem>? {
        val targets = recs[subjectId] ?: return null
        return targets.map { id ->
            val e = entries[id] ?: return null
            SubjectRecommendationItem(subjectId = e.id, name = e.name, nameCn = e.nameCn.ifEmpty { null }, imageUrl = e.imageLarge)
        }
    }

    private fun Entry.toDetails(): BatchSubjectDetails = BatchSubjectDetails(
        subjectInfo = SubjectInfo(
            subjectId = id,
            subjectType = SubjectType.ANIME,
            name = name,
            nameCn = nameCn,
            summary = "",
            nsfw = false,
            imageLarge = imageLarge,
            totalEpisodes = episodes,
            airDate = PackedDate.parseFromDate(date),
            tags = tags,
            aliases = emptyList(),
            ratingInfo = RatingInfo(rank = rank, total = votes, count = RatingCounts.Zero, score = "${score10 / 10}.${score10 % 10}"),
            collectionStats = stats,
            completeDate = PackedDate.Invalid,
        ),
        mainEpisodeCount = episodes,
        lightSubjectRelations = LightSubjectRelations(emptyList(), emptyList()),
        metaTags = metaTags,
    )

    private val vocabulary = manifest.tags.toHashSet()

    /** 这个查询落在收录范围里、数据也还新. */
    private fun covers(
        sort: SearchSort,
        ranks: List<Bound>,
        ratingCounts: List<Bound>,
        airDates: List<Bound>,
        tags: List<String>,
        metaTags: List<String>,
        ratings: List<Bound>,
        today: String,
    ): Boolean {
        val catalogFresh = daysBetween(manifest.catalog.date, today)?.let { it in 0..CATALOG_MAX_AGE_DAYS } == true
        val recentFresh = daysBetween(manifest.recentScope.date, today)?.let { it in 0..RECENT_MAX_AGE_DAYS } == true
        val ranked = ranks.any { (it.op == ">=" && it.number >= 1.0) || (it.op == ">" && it.number >= 0.0) }
        // 排行榜: 有排名 + 评分人数下限不低于收录线, 主目录收全了
        val minVotes = ratingCounts.filter { it.op == ">=" || it.op == ">" }
            .maxOfOrNull { if (it.op == ">") it.number + 1 else it.number }
        if (ranked && minVotes != null && minVotes >= manifest.catalog.minVotes) return catalogFresh
        // 没收藏时的老番榜: 有排名、首播早于某天、按热度; 主目录照客户端那样翻到了用得着的深度
        if (sort == SearchSort.COLLECTION && ranked && tags.isEmpty() && metaTags.isEmpty() && ratings.isEmpty() &&
            ratingCounts.isEmpty() && airDates.isNotEmpty() && airDates.all { it.op == "<" || it.op == "<=" }
        ) {
            return catalogFresh && recentFresh
        }
        // 按热度、首播在近期分片的起点之后: 本季、大家最近在看、新番榜
        val since = airDates.filter { it.op == ">=" || it.op == ">" }.maxOfOrNull { it.value }
        if (sort == SearchSort.COLLECTION && since != null && since >= manifest.recentScope.since && ranks.isEmpty() &&
            ratingCounts.isEmpty() && ratings.isEmpty()
        ) {
            return recentFresh
        }
        return false
    }

    internal class Bound(val op: String, val value: String) {
        val number: Double = value.toDoubleOrNull() ?: Double.NaN

        fun matches(x: Double): Boolean = when (op) {
            ">=" -> x >= number - EPSILON
            ">" -> x > number + EPSILON
            "<=" -> x <= number + EPSILON
            "<" -> x < number - EPSILON
            else -> abs(x - number) < EPSILON
        }

        fun matches(date: String): Boolean = when (op) {
            ">=" -> date >= value
            ">" -> date > value
            "<=" -> date <= value
            "<" -> date < value
            else -> date == value
        }
    }

    companion object {
        /** 搜索接口一个查询最多翻到的条数: 超过的部分 bangumi 不给, 这里照样不给 (随机取页按它算页数). */
        const val HIT_CAP = 1000

        /** 主目录多旧还用: 每周出一次, 生成那边出了问题时再宽限一周. */
        const val CATALOG_MAX_AGE_DAYS = 14

        /** 近期分片多旧还用: 每天出一次. */
        const val RECENT_MAX_AGE_DAYS = 3

        /** 高票标签的票数: 与 [NON_CONFIDENT_COUNT] 和占着最高票的那个空名字标签 (票数同它) 一起还原「票数 / 最高票」够不够线. */
        internal const val CONFIDENT_COUNT = 100
        internal const val NON_CONFIDENT_COUNT = 1

        private const val COVER_PREFIX = "https://lain.bgm.tv/pic/cover/l/"
        private const val EPSILON = 1e-6
        private val BOUND = Regex("""^(>=|<=|>|<|=)?(.+)$""")

        /** 条件串 (`>=1`、`<7.5`、`>=2026-04-01`) 拆开; 认不出的返回 `null` (这个查询目录不答). */
        private fun bounds(list: List<String>?): List<Bound>? = list.orEmpty().map { raw ->
            val m = BOUND.matchEntire(raw.trim()) ?: return null
            Bound(m.groupValues[1].ifEmpty { "=" }, m.groupValues[2])
        }

        /** 两个 `YYYY-MM-DD` 之间差几天; 认不出为 `null`. */
        internal fun daysBetween(from: String, to: String): Int? {
            val a = PackedDate.parseFromDate(from).takeIf { it.isValid } ?: return null
            val b = PackedDate.parseFromDate(to).takeIf { it.isValid } ?: return null
            return (b.toEpochDays() - a.toEpochDays()).toInt()
        }

        private fun PackedDate.toEpochDays(): Long = LocalDate(year, month, day).toEpochDays().toLong()

        /**
         * 把下载来的几份文件拼成目录. 主目录里名字空着的 (系列关系图里有的那些) 由 [names] 补, 补不上的那一部不收
         * (系列关系图还没下来或比目录旧时会这样, 查询少一部, 下次更新就齐了).
         */
        fun build(
            manifest: RecommendationCatalogManifest,
            staticFiles: List<String>,
            stats: String,
            recent: String,
            recs: List<String>,
            names: (Int) -> Pair<String, String>?,
        ): RecommendationCatalog {
            val statsById = HashMap<Int, List<String>>()
            rows(stats).forEach { r -> r.getOrNull(0)?.toIntOrNull()?.let { statsById[it] = r } }
            val entries = HashMap<Int, Entry>(statsById.size + 512)
            for (file in staticFiles) {
                for (row in rows(file)) {
                    // 末尾空着的列 (没有封面) 有的编辑器会把行尾的制表符删掉: 补齐
                    val r = if (row.size < STATIC_COLUMNS) row + List(STATIC_COLUMNS - row.size) { "" } else row
                    val id = r.getOrNull(0)?.toIntOrNull() ?: continue
                    val s = statsById[id] ?: continue
                    val (name, nameCn) = if (r[1].isEmpty() && r[2].isEmpty()) names(id)?.let { it.first to it.second } ?: continue
                    else r[2] to r[1]
                    entryOf(id, name, nameCn, r, s.drop(1), manifest)?.let { entries[id] = it }
                }
            }
            // 近期分片整行都在 (名字也在), 盖掉主目录里同一部的旧数据
            for (r in rows(recent)) {
                val id = r.getOrNull(0)?.toIntOrNull() ?: continue
                if (r.size < STATIC_COLUMNS + 4) continue  // 标签列空时行尾的制表符可能被删掉, 少一列也认
                entryOf(id, r[2], r[1], r, r.drop(STATIC_COLUMNS), manifest)?.let { entries[id] = it }
            }
            val recsById = HashMap<Int, IntArray>()
            for (r in recs.asSequence().flatMap { rows(it) }) {
                val id = r.getOrNull(0)?.toIntOrNull() ?: continue
                recsById[id] = r.getOrNull(1).orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
            }
            return RecommendationCatalog(manifest, entries, recsById)
        }

        /** 主目录 / 近期分片的前几列: id, 中文名, 原名, 首播日, 集数, 公共标签, 封面. */
        private const val STATIC_COLUMNS = 7

        private fun entryOf(
            id: Int,
            name: String,
            nameCn: String,
            r: List<String>,
            /** 分数 x10, 评分人数, 排名, 想看/看过/在看/搁置/抛弃, 标签 (每周变, 所以在统计那份里) */
            s: List<String>,
            manifest: RecommendationCatalogManifest,
        ): Entry? {
            if (r.size < STATIC_COLUMNS || s.size < 4) return null
            val counts = s[3].split(',').map { it.toIntOrNull() ?: 0 }
            if (counts.size < 5) return null
            val tags = tagsOf(s.getOrNull(4).orEmpty(), manifest.tags)
            return Entry(
                id = id,
                name = name,
                nameCn = nameCn,
                date = r[3],
                episodes = r[4].toIntOrNull() ?: 0,
                metaTags = r[5].split(',').filter { it.isNotEmpty() },
                tags = tags,
                tagNames = tags.mapTo(HashSet()) { it.name },
                cover = r[6],
                score10 = s[0].toIntOrNull() ?: 0,
                votes = s[1].toIntOrNull() ?: 0,
                rank = s[2].toIntOrNull() ?: 0,
                // 列序同 bangumi 的 collection: 想看 / 看过 / 在看 / 搁置 / 抛弃
                stats = SubjectCollectionStats(wish = counts[0], doing = counts[2], done = counts[1], onHold = counts[3], dropped = counts[4]),
            )
        }

        /**
         * 标签列 (`12*,3,40`: 词表下标, `*` = 高票) 还原成 [Tag]: 推荐只拿「票数 / 这部的最高票」判高不高 (TAG_CONFIDENCE),
         * 所以高票记 [CONFIDENT_COUNT]、其余记 [NON_CONFIDENT_COUNT], 再放一个空名字的标签占着最高票 —— 它不在任何词表里, 不会被当成口味.
         */
        private fun tagsOf(column: String, vocabulary: List<String>): List<Tag> {
            val tags = ArrayList<Tag>()
            tags += Tag("", CONFIDENT_COUNT)
            for (item in column.split(',')) {
                if (item.isEmpty()) continue
                val confident = item.endsWith('*')
                val name = vocabulary.getOrNull(item.trimEnd('*').toIntOrNull() ?: continue) ?: continue
                tags += Tag(name, if (confident) CONFIDENT_COUNT else NON_CONFIDENT_COUNT)
            }
            return tags
        }

        private fun rows(text: String): Sequence<List<String>> = text.lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.trimEnd('\r').split('\t') }
    }
}

/** 推荐目录的清单 (`rec/manifest.json`), 字段同 `scripts/rec.py` 写出的. */
@Serializable
data class RecommendationCatalogManifest(
    val format: Int,
    val generated: String,
    /** 标签字典: 主目录 / 近期分片的标签列存的是这里的下标. 客户端的词表 ([CanonicalTagKind]) 里有、这里没有的标签, 查询交给 bangumi. */
    val tags: List<String>,
    /** 生成时「高票」的线 (票数 / 最高票), 须与推荐那边的 TAG_CONFIDENCE 一致, 否则目录不用. */
    @SerialName("tag_confidence") val tagConfidence: Double,
    /** 主目录里名字空着的那些按哪一版系列关系图省掉的. */
    @SerialName("names_from_graph") val namesFromGraph: String = "",
    val catalog: Catalog,
    val static: List<FileEntry>,
    val stats: FileEntry,
    val recent: FileEntry,
    @SerialName("recent_scope") val recentScope: RecentScope,
    /** 「看过的人还看了」, 同主目录按 id 分片. */
    val recs: List<FileEntry>,
) {
    @Serializable
    data class Catalog(
        val date: String,
        val rows: Int,
        @SerialName("min_votes") val minVotes: Int,
    )

    @Serializable
    data class RecentScope(
        val since: String,
        @SerialName("min_collection") val minCollection: Int,
        val date: String,
    )

    @Serializable
    data class FileEntry(val path: String, val rows: Int, val sha256: String)

    /** 清单引用的全部文件. */
    val files: List<FileEntry> get() = static + stats + recent + recs
}
