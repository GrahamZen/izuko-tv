/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network.mapper

import me.him188.ani.app.data.models.subject.RatingCounts
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.app.data.persistent.database.dao.SubjectRelations
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCollectionType
import me.him188.ani.datasources.bangumi.next.models.BangumiNextInfoboxItem
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubject
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectInterest
import kotlin.time.Duration.Companion.seconds

/**
 * p1 的条目 DTO 到 Room 实体的映射.
 *
 * 与 Ani 那份 (`AniSubjectCollection.toEntity`) 的对照, 逐字段实测过 302286:
 * - `name`/`nameCn`/`summary`/`nsfw`/`tags`/`metaTags`/`infobox`/`rank` 一致;
 * - 封面从 `static.myani.org` 拼地址改成 payload 自带的 `images.large`;
 * - `favorite{wish,doing,...}` 变成 `collection` 的数字键 (1 想看 2 看过 3 在看 4 搁置 5 抛弃);
 * - `scoreDetails` 的 map 变成 `rating.count` 的**数组** (下标 0 是 1 分);
 * - `score` Ani 给的是保留一位小数的字符串 ("7.9"), p1 给的是数字 (7.89), 这里格式化回一位;
 * - **`recurrence` 与 `relations` p1 没有等价物**, 见 [toEntity] 的两个参数.
 */
fun BangumiNextSubject.toEntity(
    lastFetched: Long,
    /**
     * 播出周期. p1 没有这个数据 (Ani 是服务端算的), 要等 bangumi-data 那条路接上;
     * 在那之前传库里已有的值, 免得把之前取到的抹掉.
     */
    recurrence: SubjectRecurrence? = null,
    /**
     * 系列关系. 同上, p1 没有等价物 (Ani 是服务端算的系列主条目/续作索引), 要等客户端 BFS 那条路接上.
     */
    relations: SubjectRelations = SubjectRelations.Empty,
): SubjectCollectionEntity {
    val airDate = PackedDate.parseFromDate(airtime.date)
    return SubjectCollectionEntity(
        subjectId = id,
        name = name,
        nameCn = nameCN,
        summary = summary,
        nsfw = nsfw,
        imageLarge = images?.large.orEmpty().ifBlank { BANGUMI_NO_ICON_IMAGE },
        // Ani 用的是它内联的 episodes.size; p1 的列表接口不内联分集, 用 wiki 的话数
        totalEpisodes = eps,
        airDate = airDate,
        // p1 没有独立的 aliases 字段. 实测 302286: Ani 的 aliases 与 infobox「别名」逐条相同,
        // 所以只取 infobox 不丢东西 (Ani 那边本来也要再并一次 infobox).
        aliases = infobox.aliases(),
        tags = tags.map { Tag(name = it.name, count = it.count) },
        collectionStats = collection.toSubjectCollectionStats(),
        ratingInfo = RatingInfo(
            rank = rating.rank,
            total = rating.total,
            count = rating.count.toRatingCounts(),
            score = rating.score.toString().toOneDecimalScore(),
        ),
        completeDate = PackedDate.Invalid,
        selfRatingInfo = interest.toSelfRatingInfo(),
        collectionType = interest?.type.toUnifiedCollectionType(),
        recurrence = recurrence,
        relations = relations,
        screeningYear = infobox.screeningYearOrNull(airDate.year),
        theatrical = infobox.isTheatricalOnly(),
        lastUpdated = interest?.updatedAt?.let { it.toLong().seconds.inWholeMilliseconds } ?: 0,
        lastFetched = lastFetched,
        cachedStaffUpdated = 0,
        cachedCharactersUpdated = 0,
    )
}

private fun List<Int>.toRatingCounts(): RatingCounts = RatingCounts(
    s1 = getOrElse(0) { 0 },
    s2 = getOrElse(1) { 0 },
    s3 = getOrElse(2) { 0 },
    s4 = getOrElse(3) { 0 },
    s5 = getOrElse(4) { 0 },
    s6 = getOrElse(5) { 0 },
    s7 = getOrElse(6) { 0 },
    s8 = getOrElse(7) { 0 },
    s9 = getOrElse(8) { 0 },
    s10 = getOrElse(9) { 0 },
)

/**
 * Ani 给的 score 是保留一位小数的字符串 ("7.9"), p1 给的是原始值 (7.89). 显示的地方直接用这个串,
 * 不截的话会变成 "7.89".
 */
private fun String.toOneDecimalScore(): String {
    val value = toDoubleOrNull() ?: return this
    val rounded = kotlin.math.round(value * 10) / 10
    val intPart = rounded.toInt()
    val decimal = kotlin.math.round((rounded - intPart) * 10).toInt()
    return "$intPart.$decimal"
}

private fun Map<String, Int>.toSubjectCollectionStats(): SubjectCollectionStats = SubjectCollectionStats(
    wish = this[BangumiNextCollectionType.Wish.value.toString()] ?: 0,
    done = this[BangumiNextCollectionType.Collect.value.toString()] ?: 0,
    doing = this[BangumiNextCollectionType.Doing.value.toString()] ?: 0,
    onHold = this[BangumiNextCollectionType.OnHold.value.toString()] ?: 0,
    dropped = this[BangumiNextCollectionType.Dropped.value.toString()] ?: 0,
)

private fun BangumiNextSubjectInterest?.toSelfRatingInfo(): SelfRatingInfo {
    if (this == null) return SelfRatingInfo.Empty
    return SelfRatingInfo(
        score = rate,
        comment = comment.takeIf { it.isNotBlank() },
        tags = tags,
        isPrivate = `private`,
    )
}

private fun BangumiNextCollectionType?.toUnifiedCollectionType(): UnifiedCollectionType = when (this) {
    BangumiNextCollectionType.Wish -> UnifiedCollectionType.WISH
    BangumiNextCollectionType.Collect -> UnifiedCollectionType.DONE
    BangumiNextCollectionType.Doing -> UnifiedCollectionType.DOING
    BangumiNextCollectionType.OnHold -> UnifiedCollectionType.ON_HOLD
    BangumiNextCollectionType.Dropped -> UnifiedCollectionType.DROPPED
    null -> UnifiedCollectionType.NOT_COLLECTED
}

// region infobox

private const val ALIAS_KEY = "别名"

private fun List<BangumiNextInfoboxItem>.aliases(): List<String> =
    asSequence()
        .filter { it.key == ALIAS_KEY }
        .flatMap { item -> item.propertyValues.asSequence().map { it.v } }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

/** infobox 里表示"影院上映日期"的字段名. */
private val SCREENING_DATE_KEYS = setOf("上映年度", "上映日期", "其他上映日期", "其他上映年度")

private val YEAR_REGEX = Regex("""(?:19|20)\d{2}""")

/**
 * infobox 「上映年度」里**最早**的那个年份; 没有该字段, **或 [airYear] 本来就在这些年份里**,
 * 都返回 `null` —— 后者说明 `airDate` 记的就是上映日, 没必要换个年份去判.
 *
 * 只取最早那个: 老片的 infobox 会把重映年也列上 (攻殻機動隊 是 `[1995, 2025]`, 2025 是 4K 重映),
 * 全盘接受会让 2026 年的新片「The Ghost in the Shell」也过年份判据、顶掉 1995 那部正解.
 */
private fun List<BangumiNextInfoboxItem>.screeningYearOrNull(airYear: Int?): Int? {
    val years = asSequence()
        .filter { it.key in SCREENING_DATE_KEYS }
        .flatMap { item -> item.propertyValues.asSequence().map { it.v } }
        .mapNotNull { YEAR_REGEX.find(it)?.value?.toIntOrNull() }
        .toList()
    if (years.isEmpty() || airYear in years) return null
    return years.min()
}

/**
 * 是否**只在影院放映**: 有上映日期而没有「放送开始」. 见 [SubjectCollectionEntity.theatrical].
 */
private fun List<BangumiNextInfoboxItem>.isTheatricalOnly(): Boolean {
    val keys = mapTo(mutableSetOf()) { it.key }
    return keys.any { it in SCREENING_DATE_KEYS } && "放送开始" !in keys
}

// endregion
