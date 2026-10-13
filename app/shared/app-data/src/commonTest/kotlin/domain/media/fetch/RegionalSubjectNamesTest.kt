/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.network.TmdbSubjectMapEntry
import me.him188.ani.app.data.network.TmdbSubjectMapRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionalSubjectNamesTest {
    private fun entry(type: String, vararg names: Pair<String, String>) = TmdbSubjectMapEntry(
        backdrop = TmdbSubjectMapRef(type, 1),
        backdropPath = null,
        stills = emptyList(),
        manual = false,
        regionalNames = names.toMap(),
    )

    private val apothecary = entry("tv", "hk" to "藥屋少女的呢喃", "tw" to "藥師少女的獨語")

    @Test
    fun `search name - taiwan name qualified with the own season of the subject`() {
        assertEquals("藥師少女的獨語 第二季", regionalSearchName(apothecary, listOf("药屋少女的呢喃 第二季", "薬屋のひとりごと 第2期")))
        assertEquals("藥師少女的獨語", regionalSearchName(apothecary, listOf("药屋少女的呢喃")))
        // 没有台湾译名用香港的
        assertEquals("藥屋少女的呢喃", regionalSearchName(entry("tv", "hk" to "藥屋少女的呢喃"), listOf("药屋少女的呢喃")))
    }

    @Test
    fun `search name - season marker prefers the 季 form`() {
        val tw = entry("tv", "tw" to "藥師少女的獨語")
        assertEquals("藥師少女的獨語 第二季", regionalSearchName(tw, listOf("薬屋のひとりごと 第2期", "药屋少女的呢喃 第二季")))
        assertEquals("藥師少女的獨語 第2期", regionalSearchName(tw, listOf("薬屋のひとりごと 第2期")))
        // 译名自带季号的原样
        assertEquals("進擊的巨人 第四季", regionalSearchName(entry("tv", "tw" to "進擊的巨人 第四季"), listOf("进击的巨人 第四季")))
    }

    @Test
    fun `search name - movies as is - collections never`() {
        val movie = entry("movie", "tw" to "劇場版 鏈鋸人 蕾潔篇")
        assertEquals("劇場版 鏈鋸人 蕾潔篇", regionalSearchName(movie, listOf("电锯人 剧场版 蕾塞篇 第二季")))
        assertNull(regionalSearchName(entry("collection", "tw" to "鬼滅之刃 系列"), listOf("鬼灭之刃")))
    }

    @Test
    fun `series names - other seasons get the regional name with their own season marker`() {
        val spy = entry("tv", "tw" to "SPY×FAMILY 間諜家家酒")
        // 第三季: 第一季的繁体页面名 (译名本身) 与第二季的都算别季, 选择器按页名相等排除
        val third = regionalSeriesNames(
            spy,
            listOf("间谍过家家 第三季"),
            SubjectSeriesInfo(
                seasonSort = 3,
                sequelSubjectNames = emptySet(),
                seriesSubjectNamesWithoutSelf = setOf("间谍过家家", "间谍过家家 第二季"),
            ),
        )
        assertEquals(setOf("SPY×FAMILY 間諜家家酒", "SPY×FAMILY 間諜家家酒 第二季"), third.series)
        assertEquals(emptySet(), third.sequels)
        // 第一季: 译名本身是自己的别名, 不算别季; 续集补上各自的季号
        val first = regionalSeriesNames(
            spy,
            listOf("间谍过家家"),
            SubjectSeriesInfo(
                seasonSort = 1,
                sequelSubjectNames = setOf("间谍过家家 第二季", "间谍过家家 第三季"),
                seriesSubjectNamesWithoutSelf = setOf("间谍过家家 第二季", "间谍过家家 第三季", "间谍过家家 代号：白"),
            ),
        )
        assertEquals(setOf("SPY×FAMILY 間諜家家酒 第二季", "SPY×FAMILY 間諜家家酒 第三季"), first.series)
        assertEquals(setOf("SPY×FAMILY 間諜家家酒 第二季", "SPY×FAMILY 間諜家家酒 第三季"), first.sequels)
        // 电影的译名只是它自己的
        val movie = regionalSeriesNames(entry("movie", "tw" to "劇場版"), listOf("剧场版"), SubjectSeriesInfo.Fallback)
        assertEquals(emptySet(), movie.series)
    }

    @Test
    fun `aliases - later seasons need a season marker`() {
        val frieren = entry("tv", "tw" to "葬送的芙莉蓮")
        // 第一季 (或不属于系列): 系列名就是它的名字
        assertEquals(listOf("葬送的芙莉蓮"), regionalSubjectAliases(frieren, listOf("葬送的芙莉莲"), seasonSort = 1))
        // 后面的季有季号: 补上
        assertEquals(listOf("葬送的芙莉蓮 第二季"), regionalSubjectAliases(frieren, listOf("葬送的芙莉莲 第二季"), seasonSort = 2))
        // 后面的季没有季号: 系列名会把第一季也认成本季, 不用
        assertEquals(emptyList(), regionalSubjectAliases(frieren, listOf("更多 葬送的芙莉莲"), seasonSort = 2))
        // 译名自带季号的原样
        val final = entry("tv", "tw" to "進擊的巨人 第四季")
        assertEquals(listOf("進擊的巨人 第四季"), regionalSubjectAliases(final, listOf("进击的巨人 最终季"), seasonSort = 4))
        assertEquals(emptyList(), regionalSubjectAliases(entry("collection", "tw" to "鬼滅之刃 系列"), listOf("鬼灭之刃"), 1))
    }
}
