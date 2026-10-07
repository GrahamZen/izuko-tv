/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import me.him188.ani.app.domain.mediasource.web.SelectorAutoMatchConfig
import me.him188.ani.app.domain.mediasource.web.SelectorSearchConfig
import me.him188.ani.app.domain.mediasource.web.SelectorSearchQuery
import me.him188.ani.app.domain.mediasource.web.WebSearchSubjectInfo
import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 规则源自动匹配时打开条目页的顺序 ([orderRuleSubjects]). 搜索结果照一个固定返回一整页、按相关度排的站点:
 * 对得上的几条在最前面, 后面是只沾一两个字的 (宿主单测里不做繁简转换, 用简体).
 */
class RuleSubjectOrderTest {
    private val config = SelectorSearchConfig(autoMatch = SelectorAutoMatchConfig(preferShorterName = true))
    private val query = SelectorSearchQuery(
        subjectName = "机动战士高达",
        allSubjectNames = setOf("机动战士高达", "機動戦士ガンダム", "Mobile Suit Gundam"),
        episodeSort = EpisodeSort(1),
        episodeEp = EpisodeSort(1),
        episodeName = null,
    )

    private fun subjects(vararg names: String) = names.map { WebSearchSubjectInfo(it, it, "https://example.com/$it", "/$it", null) }

    @Test
    fun `subjects with matching names come first and short unrelated ones stay behind`() {
        val page = subjects(
            "高达创元宇宙【日语】",
            "机动战士高达（机动战士敢达、机动战士钢弹）【日语】",
            "机动战士高达ZZ【日语】",
            "战鼎 动态漫画",
            "宝宝巴士系列【国语】",
            "长歌行 动态漫画",
        )
        val ordered = config.orderRuleSubjects(page, query).map { it.name }
        assertEquals(
            listOf(
                // 对得上的按短名字优先
                "机动战士高达ZZ【日语】",
                "机动战士高达（机动战士敢达、机动战士钢弹）【日语】",
                // 对不上的保持站点的顺序, 不再按长短排到前面
                "高达创元宇宙【日语】",
                "战鼎 动态漫画",
                "宝宝巴士系列【国语】",
                "长歌行 动态漫画",
            ),
            ordered,
        )
    }

    @Test
    fun `without preferring shorter names the site order is kept within both groups`() {
        val page = subjects("战鼎 动态漫画", "机动战士高达（机动战士敢达）【日语】", "机动战士高达ZZ【日语】")
        val ordered = config.copy(autoMatch = SelectorAutoMatchConfig(preferShorterName = false)).orderRuleSubjects(page, query)
        assertEquals(listOf("机动战士高达（机动战士敢达）【日语】", "机动战士高达ZZ【日语】", "战鼎 动态漫画"), ordered.map { it.name })
    }
}
