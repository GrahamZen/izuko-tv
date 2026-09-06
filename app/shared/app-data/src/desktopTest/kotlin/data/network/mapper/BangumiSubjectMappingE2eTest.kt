/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network.mapper

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.BangumiApiProvider
import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 拿真实响应过一遍 p1/v0 → Room 实体的映射, 把几处**换数据源时最容易悄悄变掉**的字段钉住.
 *
 * 这些都是对照 Ani 的旧响应量出来的, 不是凭空写的期望值:
 * - 评分 `score` Ani 给 "7.9" 而 p1 给 7.89, 不格式化就会变成 "7.89";
 * - `collection` 的键是数字 (1 想看 2 看过 3 在看 4 搁置 5 抛弃), 映射错了就是几个数字互换;
 * - 分集的 `ep` 不能拿 `sort` 顶替 (条目 132734 两者差 1).
 *
 * **默认跳过** (打真实网络, 会被 bangumi 按 IP 限流):
 * ```
 * ANI_BANGUMI_E2E=1 ./gradlew :app:shared:app-data:desktopTest --tests "*BangumiSubjectMappingE2e*"
 * ```
 */
class BangumiSubjectMappingE2eTest {
    private val withUserAgent: (HttpClientConfig<*>) -> Unit = { config ->
        config.install(UserAgent) { agent = "animeko-fork/1.0 (https://github.com/GrahamZen/animeko)" }
    }

    private fun subjectApi() = SubjectBangumiNextApi(BangumiApiProvider.NEXT_BASE_URL, null, withUserAgent)
    private fun v0Api() = DefaultApi(BangumiApiProvider.V0_BASE_URL, null, withUserAgent)

    private fun enabled(): Boolean {
        if (System.getenv("ANI_BANGUMI_E2E") != "1") {
            println("跳过: 需要真实网络, 设 ANI_BANGUMI_E2E=1 才跑")
            return false
        }
        return true
    }

    @Test
    fun `条目映射 - 死神千年血战篇`() = runTest(timeout = 2.minutes) {
        if (!enabled()) return@runTest
        val entity = subjectApi().getSubject(302286).body().toEntity(lastFetched = 0)

        assertEquals(302286, entity.subjectId)
        assertEquals("BLEACH 千年血戦篇", entity.name)
        assertEquals("死神 千年血战篇", entity.nameCn)
        assertEquals(2022, entity.airDate.year)
        assertEquals(10, entity.airDate.month)
        // eps = wiki 的话数 (13), 不含两个特别篇
        assertEquals(13, entity.totalEpisodes)
        assertTrue(entity.imageLarge.startsWith("https://lain.bgm.tv/"), "封面还指着别处: ${entity.imageLarge}")
        // 别名只能从 infobox 抽, Ani 那边有独立字段
        assertTrue(entity.aliases.any { it.contains("Thousand-Year Blood War") }, "别名丢了: ${entity.aliases}")
        // 一位小数, 不是 "7.89"
        assertTrue(Regex("""^\d+\.\d$""").matches(entity.ratingInfo.score), "score 没格式化: ${entity.ratingInfo.score}")
        assertTrue(entity.ratingInfo.rank > 0)
        // 看过的人远多于想看的; 键映射错了 (比如把"看过"当成"想看") 这条就会翻过来
        assertTrue(
            entity.collectionStats.done > entity.collectionStats.wish,
            "收藏统计的键映射反了: ${entity.collectionStats}",
        )
        assertTrue(entity.collectionStats.dropped in 1..entity.collectionStats.doing)
        // TV 动画不是剧场版
        assertTrue(!entity.theatrical)
    }

    @Test
    fun `分集映射 - ep 不能拿 sort 顶替`() = runTest(timeout = 2.minutes) {
        if (!enabled()) return@runTest
        // 132734 的正片 sort 是 0,1,2 而 ep 是 1,2,3
        val episodes = v0Api().getEpisodes(132734, limit = 5).body().data.orEmpty()
            .map { it.toEntity(132734, UnifiedCollectionType.NOT_COLLECTED, lastFetched = 0) }
        assertTrue(episodes.isNotEmpty())
        val first = episodes.first()
        assertEquals(0f, first.sort.number)
        assertEquals(1f, assertNotNull(first.ep, "ep 丢了").number)
    }

    @Test
    fun `分集映射 - 特别篇没有 ep`() = runTest(timeout = 2.minutes) {
        if (!enabled()) return@runTest
        val specials = v0Api().getEpisodes(302286, limit = 5, offset = 13).body().data.orEmpty()
            .map { it.toEntity(302286, UnifiedCollectionType.NOT_COLLECTED, lastFetched = 0) }
        assertTrue(specials.isNotEmpty())
        // v0 给这类分集 ep = 0, Ani 给 null; 保持 null, 否则资源匹配会拿 0 当集号
        assertTrue(specials.all { it.ep == null }, "特别篇不该有 ep: ${specials.map { it.ep }}")
    }
}
