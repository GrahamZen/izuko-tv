/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.mediasource.clouddrive.DriveMediaFolders
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind

/**
 * 同一个站点页面上, 当前集的系列内序号 (sort) 和条目内序号 (ep) 各对上一集时, 按页面的编号方式只留一条.
 *
 * 看第二季第 1 集 (系列内序号 13) 时, 本季内部编号的页面 (1~13) 上的第 13 集是本季最后一集; 从第一季连续编号的页面
 * (「XX【第一季+第二季】」1~25、只收本季却接着编号的 13~25) 上与条目内序号同号的那一集是前面的季的.
 * 排序不看资源是按哪种编号对上的, 两条都放行时自动选源可能选到错的那条.
 *
 * - 连续编号: 页名把本季和之前的季写在一起, 或页面不从第 1 集编起, 或最大集号远超本季集数 → 去掉按条目内序号对上的那条;
 * - 本季内部编号: 页名只写了本季的季号, 从第 1 集编起, 最大集号不超过本季集数 (容许多两集) → 去掉按系列内序号对上的那条;
 * - 其余 (页名没写季号、集号落在两者之间) 分不清, 两条都留.
 *
 * 按集 (而非整条目) 查询时站点源只产出对得上的那两集, 页面上只有这两个集号时不看从第几集编起.
 * 结论依赖整页的集号 (结果陆续到达时页面在变), 所以在逐条筛选 (结果可能被记住) 之后对整张表算.
 */
internal object EpisodeNumberingPages {
    /** 拆分季另有页面规则 ([SplitSeasonEpisodeMatcher]), 不经过这里. */
    @OptIn(UnsafeOriginalMediaAccess::class)
    fun resolve(context: MediaSelectorContext, list: List<Media>, filtered: List<MaybeExcludedMedia>): List<MaybeExcludedMedia> {
        val episode = context.episodeInfo?.takeIf { context.hasEpisode } ?: return filtered
        val sort = episode.sort.integerOrNull() ?: return filtered
        val ep = episode.ep?.integerOrNull() ?: return filtered
        if (sort == ep) return filtered

        val bySort = HashSet<PageKey>()
        val byEp = HashSet<PageKey>()
        for (item in filtered) {
            if (item !is MaybeExcludedMedia.Included) continue
            when (item.original.pageNumberOrNull()) {
                sort -> bySort += pageKeyOf(item.original)
                ep -> byEp += pageKeyOf(item.original)
            }
        }
        bySort.retainAll(byEp)
        if (bySort.isEmpty()) return filtered

        val pages = HashMap<PageKey, PageNumbers>()
        for (media in list) {
            val number = media.pageNumberOrNull() ?: continue
            val key = pageKeyOf(media)
            if (key in bySort) pages.getOrPut(key) { PageNumbers() }.add(number)
        }
        val names = SeasonNumberConflict.of(context)
        val episodeCount = context.subjectInfo?.totalEpisodes?.takeIf { it > 0 }
        val wrongNumbers = HashMap<PageKey, Int>()
        for ((key, page) in pages) {
            val continuous = names?.labelsEarlierSeasonsWithSelf(key.subjectName) == true ||
                    (page.count > 2 && page.min > 1) ||
                    (episodeCount != null && page.countAbove(episodeCount + maxOf(2, episodeCount / 2)) >= BEYOND_SEASON_MIN)
            val withinSeason = !continuous && episodeCount != null && page.min <= 1 &&
                    page.max <= episodeCount + 2 && names?.labelsOnlySelf(key.subjectName) == true
            when {
                continuous -> wrongNumbers[key] = ep
                withinSeason -> wrongNumbers[key] = sort
            }
        }
        if (wrongNumbers.isEmpty()) return filtered

        return filtered.map { item ->
            val media = item.original
            if (item is MaybeExcludedMedia.Included && media.pageNumberOrNull()?.let { wrongNumbers[pageKeyOf(media)] == it } == true) {
                MaybeExcludedMedia.Excluded(media, MediaExclusionReason.EpisodeMismatch(media.episodeRange))
            } else {
                item
            }
        }
    }

    /** 站点页面 (网盘是所在文件夹) 上的一集的集号; 不是单集或不是整数时为 `null`. */
    private fun Media.pageNumberOrNull(): Int? {
        if (kind != MediaSourceKind.WEB || properties.subjectName == null) return null
        return episodeRange?.knownSorts?.take(2)?.toList()?.singleOrNull()?.integerOrNull()
    }

    private fun pageKeyOf(media: Media) = PageKey(
        media.mediaSourceId,
        media.properties.subjectName.orEmpty(),
        media.properties.alliance,
        DriveMediaFolders.of(media.mediaId)?.key,
    )

    private data class PageKey(val mediaSourceId: String, val subjectName: String, val channel: String?, val folder: String?)

    /**
     * 远超本季集数的集号至少要有这么多个, 才算这一页从前面的季连续编号. 站点偶尔在本季内部编号的页面里混进一两集按连续编号写的
     * (一页 1~8、11~13 再加 22、23), 只看最大集号会把整页错判成连续编号.
     */
    private const val BEYOND_SEASON_MIN = 3

    private class PageNumbers {
        private val numbers = HashSet<Int>()
        var min = Int.MAX_VALUE
            private set
        var max = Int.MIN_VALUE
            private set
        val count get() = numbers.size

        fun add(number: Int) {
            if (!numbers.add(number)) return
            min = minOf(min, number)
            max = maxOf(max, number)
        }

        fun countAbove(limit: Int): Int = numbers.count { it > limit }
    }
}
