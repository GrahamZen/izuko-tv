/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network.schedule

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 时间表这三个端点的 DTO 是**手写**的 (响应形状生成器处理不了, 或者是第三方数据).
 * 手写 DTO 的字段类型写错只在真去调那个端点的时候才炸, 编译与其它单测都拦不住 ——
 * 时间表页整页空白就是这么来的: `sort`/`ep` 在 v0 里是数字, 而我按字符串声明,
 * 整页分集反序列化直接抛异常, 被 catch 掉之后只剩一行 warn.
 *
 * 所以这里用**真实响应的片段**逐个过一遍.
 */
class BangumiScheduleDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `v0 分集 - sort 与 ep 是数字`() {
        // 取自 https://api.bgm.tv/v0/episodes?subject_id=528828&type=0
        val body = """
            {"total":12,"limit":100,"offset":0,"data":[
              {"airdate":"2026-07-05","name":"","name_cn":"第一话","duration":"","desc":"総作画監督：鈴木 光",
               "ep":1,"sort":1,"id":1704900,"subject_id":528828,"comment":0,"type":0,"disc":0,"duration_seconds":0},
              {"airdate":"2026-07-12","name":"Ep2","name_cn":"","duration":"","desc":"",
               "ep":2,"sort":2,"id":1704901,"subject_id":528828,"comment":0,"type":0,"disc":0,"duration_seconds":0}
            ]}
        """.trimIndent()

        val page = json.decodeFromString(V0EpisodePage.serializer(), body)
        assertEquals(2, page.data.size)
        assertEquals(1704900, page.data[0].id)
        assertEquals("第一话", page.data[0].nameCn)
        assertEquals("2026-07-05", page.data[0].airdate)
        assertEquals("1", page.data[0].sort.toSortString())
        assertEquals("2", page.data[1].ep?.toSortString())
    }

    @Test
    fun `v0 分集 - 补录的半集不能丢小数`() {
        val body = """{"data":[{"id":1,"ep":5.5,"sort":5.5,"airdate":"2026-01-01"}]}"""
        val page = json.decodeFromString(V0EpisodePage.serializer(), body)
        assertEquals("5.5", page.data.single().sort.toSortString())
    }

    @Test
    fun `每日放送 - 键是星期几的字符串`() {
        // 取自 https://next.bgm.tv/p1/calendar
        val body = """
            {"1":[{"subject":{"id":528828,"name":"ヒロイン？","nameCN":"女主角？","type":2,
                   "images":{"large":"https://lain.bgm.tv/pic/cover/l/aa/bb/528828.jpg"}},"watchers":100}],
             "7":[{"subject":{"id":302286,"name":"BLEACH","nameCN":"死神","type":2},"watchers":200}]}
        """.trimIndent()

        val calendar = json.decodeFromString(CalendarResponseSerializer, body)
        assertEquals(setOf("1", "7"), calendar.keys)
        val monday = calendar.getValue("1").single().subject
        assertEquals(528828, monday.id)
        assertEquals("女主角？", monday.nameCN)
        assertTrue(monday.images?.large.orEmpty().startsWith("https://"))
        // 没有 images 字段的条目也要能解析 (封面回落到空串)
        assertNull(calendar.getValue("7").single().subject.images)
    }

    @Test
    fun `bangumi-data - broadcast 只认 R 起始时刻 P n D`() {
        // 取自 data/items/2026/07.json
        val body = """
            [{"title":"ヒロイン？","type":"tv","broadcast":"R/2026-07-01T13:00:00.000Z/P7D",
              "sites":[{"site":"bangumi","id":"558064"},{"site":"bilibili","id":"12345"}]},
             {"title":"没有播出规则的","type":"tv","sites":[{"site":"bangumi","id":"1"}]}]
        """.trimIndent()

        val items = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(BangumiDataItem.serializer()),
            body,
        )
        assertEquals(2, items.size)
        assertEquals("558064", items[0].sites.first { it.site == "bangumi" }.id)
        val rule = items[0].broadcast?.toBroadcastRuleOrNull()
        assertEquals("2026-07-01T13:00:00.000Z", rule?.startTime)
        assertEquals(7, rule?.intervalDays)
        assertNull(items[1].broadcast)
    }

    @Test
    fun `bangumi-data - P0D 必须被拒掉`() {
        // 下游拿 interval 当除数算"第几集", 0 会直接崩
        assertNull("R/2026-07-01T13:00:00.000Z/P0D".toBroadcastRuleOrNull())
        assertNull("R/2026-07-01T13:00:00.000Z/P90D".toBroadcastRuleOrNull())
        assertNull("2026-07-01T13:00:00.000Z".toBroadcastRuleOrNull())
        assertEquals(1, "R/2026-07-01T13:00:00.000Z/P1D".toBroadcastRuleOrNull()?.intervalDays)
    }
}
