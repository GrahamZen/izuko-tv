/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.utils.ktor.ApiInvoker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 系列关系推出的「系列名 第N季」([seriesSeasons], [seasonNamesOf]). 表里的行取自系列关系图 (2026-10-20 导出).
 */
class SeriesSeasonNamesTest {
    private val table = assertNotNull(
        SeriesGraphTable.parse(
            listOf(
                "# bangumi-series-graph v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
                "192428\tつぐもも\t怪怪守护神\t2017-04-02\t12\tTV\t\t\t276792\t\t",
                "276792\t継つぐもも\t继 怪怪守护神\t2020-04-05\t12\tTV\t\t192428\t\t\t",
                "849\tTo LOVEる -とらぶる-\t出包王女\t2008-04-03\t26\tTV\t\t\t3325\t\t",
                "3325\tTo LOVEる－とらぶる－ OVA\t出包王女 OVA\t2009-04-03\t6\tOVA\t\t849\t8546\t\t",
                "8546\tもっとTo LOVEる -とらぶる-\t更多 出包王女\t2010-10-05\t12\tTV\t\t3325\t37694\t\t",
                "37694\tTo LOVEる-とらぶる-ダークネス\t出包王女Darkness\t2012-10-05\t12\tTV\t\t8546\t120763\t\t",
                "120763\tTo LOVEる -とらぶる- ダークネス 2nd\t出包王女Darkness 第二季\t2015-07-06\t14\tTV\t\t37694\t\t\t1",
                "2585\tとある科学の超電磁砲\t某科学的超电磁炮\t2009-10-02\t24\tTV\t\t\t51928\t\t",
                "51928\tとある科学の超電磁砲S\t某科学的超电磁炮S\t2013-04-12\t24\tTV\t\t2585\t262940\t\t1",
                "262940\tとある科学の超電磁砲T\t某科学的超电磁炮T\t2020-01-10\t25\tTV\t\t51928\t537743\t\t2",
                "537743\tとある科学の超電磁砲 第4期\t某科学的超电磁炮 第四季\t\t\tTV\t\t262940\t\t\t",
                "245665\t鬼滅の刃\t鬼灭之刃\t2019-04-06\t26\tTV\t\t\t350764\t\t",
                "350764\t鬼滅の刃 無限列車編\t鬼灭之刃 无限列车篇\t2021-10-10\t7\tTV\t\t245665\t328195\t\t",
                "328195\t鬼滅の刃 遊郭編\t鬼灭之刃 游郭篇\t2021-12-05\t11\tTV\t\t350764\t\t\t",
            ).joinToString("\n").encodeToByteArray(),
        ),
    )

    private suspend fun TestScope.indexOf(subjectId: Int): SubjectRelationIndex =
        SubjectSeriesIndexService(noNetwork(), scope = backgroundScope, graph = { table }).getSubjectRelationIndex(subjectId)

    @Test
    fun `a sequel named after the first season plus a mark is the next season`() = runTest {
        val index = indexOf(276792)
        assertEquals(mapOf(192428 to 1, 276792 to 2), index.seriesSeasons())
        val names = index.seasonNamesOf(276792)
        assertEquals(listOf("怪怪守护神 第2季"), names.own)
        assertEquals(listOf("怪怪守护神 第1季"), names.others)
        // 第一季也有自己的季名, 第二季的季名算续作
        val first = indexOf(192428).seasonNamesOf(192428)
        assertEquals(listOf("怪怪守护神 第1季"), first.own)
        assertEquals(listOf("怪怪守护神 第2季"), first.sequels)
    }

    @Test
    fun `inference stops at a sequel with a subtitle of its own`() = runTest {
        // 「出包王女Darkness」站点按它自己起名, 后面的「Darkness 第二季」也不再按整个系列编号
        assertEquals(mapOf(849 to 1, 8546 to 2), indexOf(8546).seriesSeasons())
        assertEquals(emptyList(), indexOf(120763).seasonNamesOf(120763).own)
    }

    @Test
    fun `single letter marks and declared seasons continue the numbering`() = runTest {
        assertEquals(mapOf(2585 to 1, 51928 to 2, 262940 to 3, 537743 to 4), indexOf(262940).seriesSeasons())
    }

    @Test
    fun `a series of arcs gets no season names`() = runTest {
        assertEquals(emptyMap(), indexOf(328195).seriesSeasons())
    }

    @Test
    fun `season names join the series info without repeating own names`() = runTest {
        val info = SubjectSeriesInfo(
            seasonSort = 4,
            sequelSubjectNames = emptySet(),
            seriesSubjectNamesWithoutSelf = setOf("某科学的超电磁炮", "某科学的超电磁炮S", "某科学的超电磁炮T"),
        ).withSeasonNames(indexOf(537743), 537743, listOf("某科学的超电磁炮 第四季", "とある科学の超電磁砲 第4期"))
        // 自己写了第四季: 推出的「第4季」与它相同, 不另作别名
        assertEquals(emptyList(), info.seasonAliases)
        assertEquals(
            setOf("某科学的超电磁炮", "某科学的超电磁炮S", "某科学的超电磁炮T", "某科学的超电磁炮 第1季", "某科学的超电磁炮 第2季", "某科学的超电磁炮 第3季"),
            info.seriesSubjectNamesWithoutSelf,
        )
    }

    private fun noNetwork() = object : ApiInvoker<SubjectBangumiNextApi> {
        override suspend fun <R> invoke(action: suspend SubjectBangumiNextApi.() -> R): R = error("不应发请求")
    }
}
