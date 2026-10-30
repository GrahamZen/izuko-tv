/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class SubjectSearchPlanSiteAliasesTest {
    private fun plan(names: List<String>, aliases: List<String> = emptyList(), otherNames: List<String> = emptyList()) =
        SubjectSearchPlan.of(
            MediaFetchRequest(
                subjectId = "1",
                episodeId = "1",
                subjectNameCN = names.first(),
                subjectNames = names,
                episodeSort = EpisodeSort(1),
                episodeName = "",
                nameHints = MediaFetchNameHints(ownNames = names + aliases, otherWorkNames = otherNames),
            ),
        )

    private val umamusume2 = plan(
        names = listOf("赛马娘 Pretty Derby 第二季", "ウマ娘 プリティーダービー Season 2"),
        otherNames = listOf("赛马娘 Pretty Derby", "赛马娘"),
    )

    @Test
    fun `a page the site names its own way is this subject by an alias`() {
        assertEquals(
            "ウマ娘 プリティーダービー Season 2",
            umamusume2.nameWithSiteAliases("赛马娘 第二季", listOf("Pretty Derby", "ウマ娘 プリティーダービー Season 2", "马娘")),
        )
        val clannad = plan(listOf("CLANNAD", "クラナド"), otherNames = listOf("CLANNAD ～AFTER STORY～"))
        assertEquals("CLANNAD", clannad.nameWithSiteAliases("团子大家族Clannad", listOf("クラナド", "CLANNAD")))
        // 页名只有一个字, 别名是繁体、横线紧跟着字
        val karas = plan(listOf("鸦 -KARAS-", "鴉 -KARAS-"))
        assertEquals("鴉-KARAS-", karas.nameWithSiteAliases("鸦", listOf("鴉-KARAS-")))
    }

    @Test
    fun `an alias that only contains the name does not replace the page name`() {
        // 剧场版的页, 别名含着 TV 版的名字: 选择器照样只算模糊, 不换
        val clannad = plan(listOf("CLANNAD", "クラナド"), otherNames = listOf("CLANNAD ～AFTER STORY～"))
        assertEquals("团子大家族 剧场版", clannad.nameWithSiteAliases("团子大家族 剧场版", listOf("劇場版 CLANNAD -クラナド-")))
    }

    @Test
    fun `the page name stays when it is this subject or no alias is`() {
        assertEquals("赛马娘 Pretty Derby 第二季", umamusume2.nameWithSiteAliases("赛马娘 Pretty Derby 第二季", listOf("ウマ娘 プリティーダービー Season 2")))
        assertEquals("赛马娘 第二季", umamusume2.nameWithSiteAliases("赛马娘 第二季", listOf("Pretty Derby", "马娘")))
        assertEquals("赛马娘 第二季", umamusume2.nameWithSiteAliases("赛马娘 第二季", emptyList()))
    }

    @Test
    fun `an alias does not turn a page of another season into this one`() {
        // 第四季的页: 站点别名里有写明第四季的, 也有系列总名 (正是第一季的日文名)
        val page = "小书痴的下克上 领主的养女"
        val aliases = listOf("小书痴的下克上～为了成为图书管理员而不择手段～ 第四季", "本好きの下剋上～司書になるためには手段を選んでいられません～")
        val first = plan(listOf("小书痴的下克上 〜为了成为图书管理员而不择手段〜", "本好きの下剋上 〜司書になるためには手段を選んでいられません〜"))
        assertEquals(page, first.nameWithSiteAliases(page, aliases))
        val fourth = plan(
            names = listOf("小书痴的下克上 〜为了成为图书管理员而不择手段〜 领主的养女", "本好きの下剋上 〜司書になるためには手段を選んでいられません〜 領主の養女"),
            aliases = listOf("小书痴的下克上 〜为了成为图书管理员而不择手段〜 第四季"),
            otherNames = listOf("小书痴的下克上 〜为了成为图书管理员而不择手段〜"),
        )
        assertEquals(aliases.first(), fourth.nameWithSiteAliases(page, aliases))
        // 第一季要的时候, 第二季的页名写着第二季, 别名是第一季的名字也不换
        val umamusume = plan(listOf("赛马娘 Pretty Derby", "ウマ娘 プリティーダービー"))
        assertEquals("赛马娘 第二季", umamusume.nameWithSiteAliases("赛马娘 第二季", listOf("ウマ娘 プリティーダービー")))
    }
}
