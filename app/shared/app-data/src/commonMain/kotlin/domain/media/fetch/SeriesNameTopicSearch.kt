/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.take
import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.clouddrive.DriveNameParser
import me.him188.ani.app.domain.mediasource.rss.RssMediaSource
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.paging.filter
import me.him188.ani.datasources.api.paging.merge
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.TopicMediaSource
import me.him188.ani.datasources.api.topic.contains
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 查询 [request]. 按关键词搜发布标题的 BT 源 ([TopicMediaSource]、[RssMediaSource]) 查续作时, 在条目名之外再用系列名搜一次.
 *
 * 字幕组给续作的标题常不写季号, 只写系列名加系列内序号 (「药屋少女的呢喃 [49]」是第三季第 1 集) 或「S3」,
 * 用条目名 (「药屋少女的呢喃 第三季」) 搜不到. 系列名会搜出整个系列, 只留下认得出是本季这一集的 (见 [SeriesNameSearch.accepts]):
 * 选择器不核对 BT 资源的名字, 第一季的「[01]」放进来就会被当成第三季第 1 集.
 * 蜜柑按条目 id 找番剧页, 不按关键词搜, 不需要这一步. 系列名那一次搜索出错不影响条目名搜到的结果.
 */
internal suspend fun MediaSource.fetchWithSeriesName(request: MediaFetchRequest): SizedSource<MediaMatch> {
    val own = fetch(request)
    if (this !is TopicMediaSource && this !is RssMediaSource) return own
    val search = SeriesNameSearch.of(request) ?: return own
    logger.info { "$mediaSourceId: also searching series name '${search.seriesName}' for ${request.subjectNames.firstOrNull()}" }
    val series = fetch(request.copy(subjectNames = listOf(search.seriesName), fallbackSearchKeywords = emptyList()))
        .firstResults(MAX_SERIES_RESULTS, mediaSourceId)
        .filter { search.accepts(it.media) }
    return listOf(own, series).merge()
}

/**
 * BT 源用系列名搜续作 (见 [fetchWithSeriesName]) 时的系列名与取舍规则.
 */
internal class SeriesNameSearch private constructor(
    val seriesName: String,
    private val episodeSort: EpisodeSort,
    private val episodeEp: EpisodeSort?,
    /** 条目名写的季, 没写为 null. */
    private val season: Int?,
) {
    /**
     * 系列名搜到的资源是不是本季的这一集:
     * - 集号含系列内序号, 且它与条目内序号不同 (这时它在整个系列里唯一, 「[49]」只能是第三季第 1 集);
     * - 或者标题写明了本季 (「S3」「第三季」), 集号对得上任一种序号.
     * 只写了条目内序号的 (「[01]」) 分不出是哪一季, 不要. 集号只认解析出来的具体序号: 解析成「整季」的
     * (标题只写了「S3」、没有集号) 会被当成包含任何一集, 别的集的资源就进了这一集.
     */
    fun accepts(media: Media): Boolean {
        val range = media.episodeRange ?: return false
        fun has(sort: EpisodeSort) = range.contains(sort, allowSeason = false)
        if (episodeEp != null && episodeEp != episodeSort && has(episodeSort)) return true
        return season != null && season in DriveNameParser.declaredSeasons(media.originalTitle) &&
                (has(episodeSort) || (episodeEp != null && has(episodeEp)))
    }

    companion object {
        /**
         * [request] 的系列名 ([MediaFetchRequest.fallbackSearchKeywords] 里不写季、也不是条目自己名字的第一个).
         * 没有系列名, 或者系列名搜到的资源分不出季 (系列内序号与条目内序号相同, 条目名又没写季) 时为 null, 不必多搜.
         */
        fun of(request: MediaFetchRequest): SeriesNameSearch? {
            val seriesName = request.fallbackSearchKeywords.firstOrNull { name ->
                DriveNameParser.declaredSeasons(name).isEmpty() &&
                        request.subjectNames.none { MediaListFilters.specialEquals(it, name) }
            } ?: return null
            val season = request.subjectNames.firstNotNullOfOrNull { DriveNameParser.parseSubjectSeason(it) }
            val ep = request.episodeEp
            if ((ep == null || ep == request.episodeSort) && season == null) return null
            return SeriesNameSearch(seriesName, request.episodeSort, ep, season)
        }
    }
}

/**
 * 只取前 [count] 条: 系列名搜到的结果多 (動漫花園会一页页翻到上千条), 本季的新发布排在前面. 出错时只记日志, 当作没有.
 */
private fun SizedSource<MediaMatch>.firstResults(count: Int, mediaSourceId: String): SizedSource<MediaMatch> {
    val self = this
    val done = MutableStateFlow(false)
    return object : SizedSource<MediaMatch> {
        override val results = self.results
            .take(count)
            .catch { e -> logger.warn(e) { "$mediaSourceId: series name search failed" } }
            .onCompletion { done.value = true }
        override val finished = done
        override val totalSize = flowOf(null)
    }
}

/** 系列名那一次搜索最多看多少条结果. */
private const val MAX_SERIES_RESULTS = 300

private val logger = logger<SeriesNameSearch>()
