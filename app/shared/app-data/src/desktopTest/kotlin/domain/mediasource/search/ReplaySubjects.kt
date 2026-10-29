/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.models.subject.createTestSubjectCollection
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SubjectSeriesIndexService
import me.him188.ani.app.data.network.TmdbSubjectMapEntry
import me.him188.ani.app.data.network.TmdbSubjectMapIndex
import me.him188.ani.app.domain.episode.PlaybackSeriesInfo
import me.him188.ani.app.domain.media.fetch.create
import me.him188.ani.app.domain.media.fetch.regionalSeriesNames
import me.him188.ani.app.domain.media.fetch.regionalSubjectAliases
import me.him188.ani.app.domain.media.fetch.withRegionalNames
import me.him188.ani.app.domain.media.fetch.withSeasonAliases
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.utils.ktor.ApiInvoker

/**
 * 离线录放 ([WebSearchReplayTest]、网盘的 `DriveSeasonReplayTest`) 照开播时的样子建查询请求与选择器要的条目信息
 * (见 `CreateMediaFetchSelectBundleFlowUseCase`): 系列信息由系列关系图 [table] 算, 港台译名取自对应表 [tmdb] (没有时不加).
 * 样例 (`cases.json` 的一项) 是 `{id, names, sort, ep, episodes: [{sort, ep, duration}]}`, names 按条目服务的顺序 (中文名、原名、别名), sort / ep 是要的那一集;
 * 分集的 ep 为空时同 sort; duration (条目服务记的秒数) 与 airdate (播出日期 `yyyy-MM-dd`, 开播年份由它算) 可以没有.
 */
internal class ReplaySubjects(table: SeriesGraphTable, private val tmdb: TmdbSubjectMapIndex?, scope: CoroutineScope) {
    private val service = SubjectSeriesIndexService(noNetwork(), scope = scope, graph = { table })

    class Prepared(
        val id: Int,
        val name: String,
        val request: MediaFetchRequest,
        /** 要的那一集 */
        val episode: EpisodeInfo,
        /** 选择器的条目信息 (带推出的季名与港台译名) */
        val selectorSubject: SubjectInfo,
        /** 选择器的系列信息 (带别季的港台名字) */
        val selectorSeries: SubjectSeriesInfo,
    )

    suspend fun prepare(case: JsonObject): Prepared {
        val id = case["id"]!!.jsonPrimitive.int
        val names = case["names"]!!.jsonArray.map { it.jsonPrimitive.content }
        val subjectInfo = SubjectInfo.Empty.copy(
            subjectId = id,
            nameCn = names.first(),
            name = names.getOrElse(1) { names.first() },
            aliases = names.drop(2),
            nsfw = false,
            // 条目服务的话数: 选择器按它判断一页是不是本季内部编号
            totalEpisodes = case["episodes"]!!.jsonArray.size,
        )
        val episodes = case["episodes"]!!.jsonArray.mapIndexed { index, element ->
            val o = element.jsonObject
            EpisodeCollectionInfo(
                EpisodeInfo(
                    episodeId = id * 1000 + index,
                    type = EpisodeType.MainStory,
                    sort = EpisodeSort(o["sort"]!!.jsonPrimitive.content),
                    ep = EpisodeSort(o["ep"]?.jsonPrimitive?.contentOrNull ?: o["sort"]!!.jsonPrimitive.content),
                    durationSeconds = o["duration"]?.jsonPrimitive?.intOrNull,
                    airDate = o["airdate"]?.jsonPrimitive?.contentOrNull?.let(PackedDate::parseFromDate) ?: PackedDate.Invalid,
                ),
                UnifiedCollectionType.NOT_COLLECTED,
            )
        }
        // 条目的首播日期 (选择器认「名字 + 首播年份」的页名要用): 样例里记了 Bangumi 条目的 date 就用它, 同开播时;
        // 没记的按最早一集的播出日期 (与条目的 date 大多同年, 先行放映、按发售日记的不同)
        val firstAired = case["date"]?.jsonPrimitive?.contentOrNull?.let(PackedDate::parseFromDate)?.takeIf { it.isValid }
            ?: episodes.map { it.episodeInfo.airDate }.filter { it.isValid }.minOrNull() ?: PackedDate.Invalid
        val collection = createTestSubjectCollection(id, episodes, UnifiedCollectionType.NOT_COLLECTED)
            .copy(subjectInfo = subjectInfo.copy(airDate = firstAired))
        val playback = PlaybackSeriesInfo(id) { service.getSubjectRelationIndex(it) }
        val attached = playback.attach(flowOf(collection)).first()
        val seriesInfo = playback.seriesInfoOf(attached)
        val wanted = EpisodeSort(case["sort"]!!.jsonPrimitive.content)
        val episode = episodes.firstOrNull { it.episodeInfo.sort == wanted }?.episodeInfo ?: episodes.first().episodeInfo
        val entry = tmdb?.get(id)?.takeIf { it.regionalNames.isNotEmpty() }

        val request = MediaFetchRequest.create(attached.subjectInfo, episode, episodes.map { it.episodeInfo }, seriesInfo)
            .let { request -> if (entry == null) request else withRegionalNames(request, entry, seriesInfo) }
        val selectorSubject = attached.subjectInfo.withSeasonAliases(seriesInfo)
        return Prepared(
            id, names.first(), request, episode,
            selectorSubject = selectorSubjectInfo(selectorSubject, seriesInfo, entry),
            selectorSeries = selectorSeriesInfo(seriesInfo, selectorSubject, entry),
        )
    }

    /** 同 `withRegionalAliases`. */
    private fun selectorSubjectInfo(subject: SubjectInfo, seriesInfo: SubjectSeriesInfo, entry: TmdbSubjectMapEntry?): SubjectInfo {
        entry ?: return subject
        val aliases = regionalSubjectAliases(entry, subject.allNames, seriesInfo.seasonSort).filter { alias -> subject.allNames.none { it == alias } }
        return if (aliases.isEmpty()) subject else subject.copy(aliases = subject.aliases + aliases)
    }

    /** 同 `withRegionalSeriesNames`. */
    private fun selectorSeriesInfo(seriesInfo: SubjectSeriesInfo, subject: SubjectInfo, entry: TmdbSubjectMapEntry?): SubjectSeriesInfo {
        entry ?: return seriesInfo
        val names = regionalSeriesNames(entry, subject.allNames, seriesInfo)
        if (names.series.isEmpty() && names.sequels.isEmpty()) return seriesInfo
        return seriesInfo.copy(
            sequelSubjectNames = seriesInfo.sequelSubjectNames + names.sequels,
            seriesSubjectNamesWithoutSelf = seriesInfo.seriesSubjectNamesWithoutSelf + names.series,
        )
    }

    private fun noNetwork() = object : ApiInvoker<SubjectBangumiNextApi> {
        override suspend fun <R> invoke(action: suspend SubjectBangumiNextApi.() -> R): R = error("不应发请求")
    }
}
