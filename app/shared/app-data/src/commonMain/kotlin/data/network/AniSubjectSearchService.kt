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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.models.subject.PersonPosition
import me.him188.ani.app.data.models.subject.RatingCounts
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.network.mapper.toEntity
import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.search.SearchSort
import me.him188.ani.app.domain.search.SubjectType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.datasources.bangumi.models.BangumiItem
import me.him188.ani.datasources.bangumi.models.BangumiSearchSubjectsRequest
import me.him188.ani.datasources.bangumi.models.BangumiSearchSubjectsRequestFilter
import me.him188.ani.datasources.bangumi.models.BangumiSubject
import me.him188.ani.datasources.bangumi.models.BangumiSubjectType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import kotlin.coroutines.CoroutineContext

/**
 * 搜索走 v0 的 `POST /v0/search/subjects`, 不用 p1 的搜索: p1 只返回 `SlimSubject`,
 * 没有 `date`/`tags`/`summary`, 搜索卡片就没得展示了.
 */
class AniSubjectSearchService(
    private val bangumiV0Api: ApiInvoker<DefaultApi>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    suspend fun searchSubjects(
        keyword: String,
        offset: Int? = null,
        limit: Int? = null,

        sort: SearchSort = SearchSort.MATCH,
        filters: SubjectSearchFilters? = null,
    ): List<BatchSubjectDetails> = withContext(ioDispatcher) {
        val result = bangumiV0Api.invoke {
            searchSubjects(
                limit = limit,
                offset = offset,
                bangumiSearchSubjectsRequest = BangumiSearchSubjectsRequest(
                    keyword = keyword,
                    sort = sort.toBangumiSort(),
                    filter = BangumiSearchSubjectsRequestFilter(
                        type = listOf(BangumiSubjectType.Anime),
                        tag = filters?.tags,
                        airDate = filters?.airDates,
                        rating = filters?.ratings,
                        // bangumi 把"无排名"记作 rank 0, 排行榜必须显式排除, 否则一堆没排名的排最前.
                        // 与 Ani 那边 `ranks=">=1"` 是同一件事.
                        rank = filters?.ranks,
                        nsfw = filters?.nsfw,
                    ),
                ),
            )
        }.body()

        result.data.orEmpty().map { it.toBatchSubjectDetails() }
    }

    companion object {
        fun sanitizeKeyword(keyword: String): String {
            return buildString(keyword.length) {
                for (c in keyword) {
                    if (MediaListFilters.charsToDeleteForSearch.contains(c.code)) {
                        append(' ')
                    } else {
                        append(c)
                    }
                }
            }
        }
    }

    private fun BangumiSubject.toBatchSubjectDetails(): BatchSubjectDetails {
        return BatchSubjectDetails(
            subjectInfo = SubjectInfo(
                subjectId = id,
                subjectType = SubjectType.ANIME,
                name = name,
                nameCn = nameCn,
                summary = summary,
                nsfw = nsfw,
                imageLarge = images.large,
                totalEpisodes = eps,
                airDate = PackedDate.parseFromDate(date ?: ""),
                tags = tags.map { Tag(it.name, it.count) },
                aliases = emptyList(),
                ratingInfo = RatingInfo(
                    rank = rating.rank,
                    total = rating.total,
                    count = RatingCounts.Zero,
                    score = rating.score.toString().toOneDecimalScoreOrSelf(),
                ),
                collectionStats = SubjectCollectionStats(
                    wish = collection.wish,
                    doing = collection.doing,
                    done = collection.collect,
                    onHold = collection.onHold,
                    dropped = collection.dropped,
                ),
                completeDate = PackedDate.Invalid,
            ),
            mainEpisodeCount = eps,
            lightSubjectRelations = LightSubjectRelations(
                // Ani 是靠 `LIGHT_RELATED_PERSON_INFO` 这个 field 单独下发导演/原作的;
                // bangumi 的搜索结果自带 infobox, 从里面抽同样的两项.
                lightRelatedPersonInfoList = infobox.orEmpty().toLightRelatedPersonInfoList(),
                lightRelatedCharacterInfoList = emptyList(),
            ),
        )
    }
}

/**
 * infobox 的键 -> 职位. 搜索卡片上那行"制作: …"只显示 `RoleSet.Default` 里的四个职位
 * (动画制作 / 导演 / 脚本 / 音乐), 少映射一个就会在卡片上少一个名字.
 *
 * 「原作」不在那四个里, 留着是因为它是这份数据的另一半语义 (调用方换 RoleSet 就能用上),
 * 而且成本只是一次 map 查找.
 */
private val LIGHT_PERSON_KEYS = mapOf(
    "原作" to PersonPosition.OriginalWork,
    "导演" to PersonPosition.Director,
    "脚本" to PersonPosition.Script,
    "系列构成" to PersonPosition.SeriesComposition,
    "音乐" to PersonPosition.Music,
    "动画制作" to PersonPosition.AnimationWork,
)

private fun List<BangumiItem>.toLightRelatedPersonInfoList(): List<LightRelatedPersonInfo> =
    asSequence()
        .mapNotNull { item -> LIGHT_PERSON_KEYS[item.key]?.let { position -> item to position } }
        .flatMap { (item, position) ->
            item.value.flattenInfoboxValues().asSequence()
                .flatMap { it.splitInfoboxNames() }
                .map { LightRelatedPersonInfo(it, position) }
        }
        .toList()

/**
 * 一个职位可能挂着好几个人: `"中川幸太郎、黒石ひとみ"`. 卡片上是一个个名字用 `·` 串起来的,
 * 整串塞进去会变成"制作: 中川幸太郎、黒石ひとみ · …".
 *
 * 分号后面通常是补充说明 (`"david production、スタジオガッツ；作画协力：GONZO"`), 一并丢掉;
 * 带冒号的同理 —— 那是"某某：某人"的角色注释, 不是人名.
 */
private fun String.splitInfoboxNames(): List<String> =
    substringBefore('；').substringBefore(';')
        .split('、', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() && '：' !in it && ':' !in it }

/**
 * v0 的 infobox 值可能是一个字符串, 也可能是 `[{"v": "..."}, ...]` 这种数组, 两种都要认.
 */
private fun kotlinx.serialization.json.JsonElement.flattenInfoboxValues(): List<String> = when (this) {
    is JsonPrimitive -> listOfNotNull(contentOrNull?.takeIf { it.isNotBlank() })
    is JsonArray -> mapNotNull { element ->
        (element as? JsonObject)?.get("v")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    else -> emptyList()
}

private fun SearchSort.toBangumiSort(): BangumiSearchSubjectsRequest.Sort = when (this) {
    SearchSort.MATCH -> BangumiSearchSubjectsRequest.Sort.MATCH
    SearchSort.RANK -> BangumiSearchSubjectsRequest.Sort.RANK
    SearchSort.COLLECTION -> BangumiSearchSubjectsRequest.Sort.HEAT
    // bangumi 没有按日期排序 (只有 match/heat/rank/score). 现有实现本来就是在
    // SubjectSearchRepository 里对当页结果自己按日期排的, 这里保持 match.
    SearchSort.DATE -> BangumiSearchSubjectsRequest.Sort.MATCH
}

/** 与条目详情那边同一个规则: bangumi 给原始分 (7.89), 显示要一位小数. */
private fun String.toOneDecimalScoreOrSelf(): String {
    val value = toDoubleOrNull() ?: return this
    val rounded = kotlin.math.round(value * 10) / 10
    val intPart = rounded.toInt()
    return "$intPart.${kotlin.math.round((rounded - intPart) * 10).toInt()}"
}
