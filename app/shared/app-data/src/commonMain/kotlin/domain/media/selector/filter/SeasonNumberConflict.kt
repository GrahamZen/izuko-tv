/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.mediasource.MediaListFilters

/**
 * 站点页名写明的季号与当前条目的季号冲突: 页面是本系列另一季的.
 *
 * 选择器认别季页面原本只靠「页名去掉标点后与本系列另一季同名」. 站点常把第一季标成「XX第一季」(Bangumi 的第一季只叫「XX」),
 * 或只写「XX第三季」(Bangumi 叫「XX 第三季 袭击篇」), 名字对不上就漏过去; 而它与本季的名字只差一个字, 名字相似度照样够,
 * 页上的第 01 集就按 ep 对上了本季第 1 集.
 *
 * 判据: 页名写了季号 ([SplitSeason.seasonNumbersOf]), 当前条目的名字与别名也写了季号, 两者没有交集, 且页名的季号都是
 * 本系列别的季: 本系列别的条目用过的季号 (名字没写季号的条目算第 1 季), 或当前季之前的季 (看第六季时第一至五季总是有的,
 * 而 Bangumi 上那几季的名字未必写了季号). 只有一边写了季号、或页面同时写着本季 (跨季合集) 时不判断.
 * 「Part Ⅱ」「Part 2」是分段不是季, 先去掉再认季号 (「叁之章 PartⅡ」不是第二季).
 * 条目名末尾隔着空格的一位数也算季号 (Bangumi 的「赛博朋克：边缘行者 2」), 只对条目名这样认: 页名末尾的数字多是集号.
 *
 * 另一种: 当前是第二季或之后, 页名正是本条目去掉季号后的系列名、自己没写季号 ([isSeasonlessEarlierSeason]) —— 站点上第一季的页面
 * 多半就叫系列名. Bangumi 没把两季连成前传 / 续集 (只标「相同世界观」) 时系列信息里没有第一季, 只能这样认.
 */
internal class SeasonNumberConflict private constructor(
    private val selfNumbers: Set<Int>,
    private val otherNumbers: Set<Int>,
    private val seasonlessSelfNames: Set<String>,
) {
    fun isOtherSeason(pageName: String): Boolean {
        val numbers = seasonNumbersOf(pageName)
        return numbers.isNotEmpty() && numbers.none { it in selfNumbers } && otherNumbers.containsAll(numbers)
    }

    /**
     * 页名没写季号, 去掉标点后正是本条目去掉季号后的系列名, 而本条目是第二季或之后: 页面是前面的季 (多是第一季).
     * 站点把新一季放进不带季号的老页面、又从 01 重新编集时会误判 (仍可手动选), 这种少见: 一般新开「第二季」页面, 或接着往下编集号.
     */
    fun isSeasonlessEarlierSeason(pageName: String): Boolean =
        selfNumbers.min() >= 2 && seasonNumbersOf(pageName).isEmpty() &&
                MediaListFilters.nameForSeasonlessCompare(pageName) in seasonlessSelfNames

    /** 页名同时写着本季和本季之前的季 (「【第一季+第二季】」): 跨季合集, 通常从第一季连续编号. */
    fun labelsEarlierSeasonsWithSelf(pageName: String): Boolean {
        val numbers = seasonNumbersOf(pageName)
        val latest = selfNumbers.max()
        return numbers.any { it in selfNumbers } && numbers.any { it !in selfNumbers && it < latest }
    }

    /** 页名写了季号, 且只写了本季的. */
    fun labelsOnlySelf(pageName: String): Boolean {
        val numbers = seasonNumbersOf(pageName)
        return numbers.isNotEmpty() && selfNumbers.containsAll(numbers)
    }

    companion object {
        private val lock = SynchronizedObject()
        private var last: Pair<MediaSelectorContext, SeasonNumberConflict?>? = null

        /** 当前条目没写季号 (或只是第一季又不知道系列) 时为 `null`. 同一个选择器上下文只算一次 (逐条筛选时会反复问). */
        fun of(context: MediaSelectorContext): SeasonNumberConflict? = synchronized(lock) {
            last?.takeIf { it.first === context }?.let { return it.second }
            compute(context).also { last = context to it }
        }

        private fun compute(context: MediaSelectorContext): SeasonNumberConflict? {
            val selfNames = context.subjectInfo?.allNames.orEmpty()
            val self = selfNames.flatMapTo(HashSet()) { subjectSeasonNumbersOf(it) }
            if (self.isEmpty()) return null
            val others = HashSet<Int>()
            for (name in context.subjectSeriesInfo?.seriesSubjectNamesWithoutSelf.orEmpty()) {
                val numbers = seasonNumbersOf(name)
                if (numbers.isEmpty()) others += 1 else others += numbers
            }
            others += 1 until self.max()
            if (others.isEmpty()) return null
            val seasonless = selfNames.mapNotNullTo(HashSet()) { seasonlessNameOrNull(it) }
            return SeasonNumberConflict(self, others, seasonless)
        }

        private val PART = Regex("""part\s*\.?\s*[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ0-9IVX一二三四五六七八九十]+""", RegexOption.IGNORE_CASE)

        private fun seasonNumbersOf(name: String): Set<Int> = SplitSeason.seasonNumbersOf(PART.replace(name, " "))

        /** 条目名末尾隔着空格的一位数 (「XX 2」): Bangumi 有的续作这样起名. */
        private val TRAILING_SEASON = Regex("""\s+([2-9])\s*$""")

        /** 写季号的几种方式, 去掉后剩下系列名 (见 [seasonlessNameOrNull]). */
        private val SEASON_LABELS = listOf(
            TRAILING_SEASON,
            Regex("""第\s*[0-9０-９一二三四五六七八九十]+\s*[季期]"""),
            Regex("""(?:season|シーズン)\s*[0-9]+""", RegexOption.IGNORE_CASE),
            Regex("""[0-9]+(?:st|nd|rd|th)\s*season""", RegexOption.IGNORE_CASE),
        )

        private fun subjectSeasonNumbersOf(name: String): Set<Int> {
            val numbers = seasonNumbersOf(name)
            val trailing = TRAILING_SEASON.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return numbers
            return numbers + trailing
        }

        /** 条目名去掉季号后的系列名 (按 [MediaListFilters.nameForSeasonlessCompare] 规范化); 名字没写季号时为 `null`. */
        private fun seasonlessNameOrNull(name: String): String? {
            if (subjectSeasonNumbersOf(name).isEmpty()) return null
            val stripped = SEASON_LABELS.fold(name) { acc, label -> label.replace(acc, " ") }
            return MediaListFilters.nameForSeasonlessCompare(stripped).takeIf { it.isNotEmpty() }
        }
    }
}
