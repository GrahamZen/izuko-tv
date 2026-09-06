/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterestProfileTest {
    private val now = 1_700_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun entity(
        id: Int,
        type: UnifiedCollectionType,
        score: Int = 0,
        tags: List<Pair<String, Int>> = emptyList(),
        daysAgo: Long = 0,
        name: String = "subject-$id",
    ) = SubjectCollectionEntity(
        subjectId = id,
        name = name,
        nameCn = name,
        summary = "",
        nsfw = false,
        imageLarge = "",
        totalEpisodes = 12,
        airDate = PackedDate.Invalid,
        aliases = emptyList(),
        tags = tags.map { (n, c) -> Tag(n, c) },
        collectionStats = SubjectCollectionStats.Zero,
        ratingInfo = RatingInfo.Empty,
        completeDate = PackedDate.Invalid,
        selfRatingInfo = SelfRatingInfo(score, null, emptyList(), false),
        collectionType = type,
        recurrence = null,
        lastUpdated = now - daysAgo * day,
        lastFetched = now,
        cachedStaffUpdated = 0,
        cachedCharactersUpdated = 0,
    )

    @Test
    fun `登录前后画像身份串必须不同 (否则缓存不会作废)`() {
        val loggedOut = computeInterestProfile(emptyList(), now)
        val loggedIn = computeInterestProfile(
            listOf(entity(1, UnifiedCollectionType.DONE, score = 9, tags = listOf("治愈" to 100))),
            now,
        )
        assertTrue(loggedOut.key != loggedIn.key, "两边都是 ${loggedOut.key}")
    }

    @Test
    fun `身份串不含收藏条数, 分页进来一批但决策没变时不该触发重算`() {
        // 收藏是分页进来的, 真机上 50 分钟里 3→5→16→18→23; 每来一批就重算的话标题一直在抖
        val base = listOf(
            entity(1, UnifiedCollectionType.DONE, score = 9, tags = listOf("治愈" to 100)),
        )
        val more = base + entity(2, UnifiedCollectionType.WISH, tags = listOf("治愈" to 100))
        // 新来的那条既没进画像方向 (想看权重低到不改变排序), 也当不了种子 -> 决策没变
        assertEquals(
            computeInterestProfile(base, now).key,
            computeInterestProfile(more, now).key,
        )
    }

    @Test
    fun `身份串不含权重, 看一集导致的衰减漂移不该触发重算`() {
        fun profileAt(daysAgo: Long) = computeInterestProfile(
            listOf(
                entity(1, UnifiedCollectionType.DONE, score = 9, tags = listOf("治愈" to 100), daysAgo = daysAgo),
            ),
            now,
        )
        // 同一批收藏, 只是时间往前走了 10 天: 权重变了但决策没变
        assertEquals(profileAt(0).key, profileAt(10).key)
    }

    @Test
    fun `没有收藏时画像为空`() {
        assertTrue(computeInterestProfile(emptyList(), now).isEmpty)
    }

    @Test
    fun `只认公共标签, 过宽的地区与分类不算兴趣方向`() {
        val profile = computeInterestProfile(
            listOf(
                entity(1, UnifiedCollectionType.DONE, score = 9, tags = listOf("日本" to 100, "TV" to 100, "治愈" to 90)),
            ),
            now,
        )
        assertEquals(listOf("治愈"), profile.tags.map { it.name })
    }

    @Test
    fun `票数太少的标签不作数`() {
        val profile = computeInterestProfile(
            listOf(
                // 校园只有 5 票, 相对最高票 100 只有 5%, 低于阈值
                entity(1, UnifiedCollectionType.DONE, score = 9, tags = listOf("治愈" to 100, "校园" to 5)),
            ),
            now,
        )
        assertEquals(listOf("治愈"), profile.tags.map { it.name })
    }

    @Test
    fun `看完给高分比想看更有说明力`() {
        val profile = computeInterestProfile(
            listOf(
                entity(1, UnifiedCollectionType.DONE, score = 9, tags = listOf("治愈" to 100)),
                entity(2, UnifiedCollectionType.WISH, tags = listOf("机战" to 100)),
            ),
            now,
        )
        assertEquals("治愈", profile.tags.first().name)
    }

    @Test
    fun `看完给低分会把标签压成负的, 不进画像`() {
        val profile = computeInterestProfile(
            listOf(
                entity(1, UnifiedCollectionType.DONE, score = 3, tags = listOf("后宫" to 100)),
                entity(2, UnifiedCollectionType.DONE, score = 9, tags = listOf("治愈" to 100)),
            ),
            now,
        )
        assertEquals(listOf("治愈"), profile.tags.map { it.name })
    }

    @Test
    fun `越近期的行为权重越高`() {
        val recent = computeInterestProfile(
            listOf(
                entity(1, UnifiedCollectionType.DONE, score = 9, tags = listOf("治愈" to 100), daysAgo = 0),
                entity(2, UnifiedCollectionType.DONE, score = 9, tags = listOf("机战" to 100), daysAgo = 720),
            ),
            now,
        )
        assertEquals("治愈", recent.tags.first().name)
    }

    @Test
    fun `种子只取有正面表态的, 想看的不算`() {
        val profile = computeInterestProfile(
            listOf(
                entity(1, UnifiedCollectionType.WISH, tags = listOf("治愈" to 100), name = "想看的"),
                entity(2, UnifiedCollectionType.DONE, score = 9, tags = listOf("治愈" to 100), name = "看完的"),
            ),
            now,
        )
        assertEquals(listOf(2), profile.seeds.map { it.subjectId })
        assertEquals("看完的", profile.seeds.single().name)
        assertTrue(profile.seeds.single().explicitlyLiked)
    }

    @Test
    fun `打了高分的老作品要排在正在看没打分的前面`() {
        // 之前种子按兴趣权重排 + 180 天衰减, 于是"在看没打分"(新鲜) 压过"打了 10 分"(两年前),
        // 界面上就成了"因为你喜欢《正在看的那部》" —— 用户 2026-09-06 实测指出的
        val profile = computeInterestProfile(
            listOf(
                entity(1, UnifiedCollectionType.DOING, tags = listOf("战斗" to 100), name = "在看没打分"),
                entity(2, UnifiedCollectionType.DONE, score = 10, tags = listOf("治愈" to 100), daysAgo = 730, name = "两年前打10分"),
            ),
            now,
        )
        assertEquals("两年前打10分", profile.seeds.first().name)
    }

    @Test
    fun `在看且没打分不能当种子`() {
        val profile = computeInterestProfile(
            listOf(entity(1, UnifiedCollectionType.DOING, tags = listOf("战斗" to 100))),
            now,
        )
        assertEquals(emptyList(), profile.seeds.map { it.subjectId })
        // 但它照样贡献兴趣方向 —— "正在追"是有效的口味信号, 只是不配说"你喜欢"
        assertEquals(listOf("战斗"), profile.tags.map { it.name })
    }

    @Test
    fun `看完没打分能当种子但不算明确表态`() {
        val profile = computeInterestProfile(
            listOf(entity(1, UnifiedCollectionType.DONE, tags = listOf("治愈" to 100))),
            now,
        )
        assertEquals(listOf(1), profile.seeds.map { it.subjectId })
        assertTrue(!profile.seeds.single().explicitlyLiked, "看完没打分不该说「你喜欢」")
    }

    @Test
    fun `累计分相同时, 出现得更集中的标签区分度更高`() {
        // 两个标签的累计权重一样 (4.0), 但"赛博朋克"只出现在 2 部里、"奇幻"摊在 4 部里.
        // 前者更能说明这个用户的独特口味, 应当排在前面.
        val collections = listOf(
            entity(1, UnifiedCollectionType.DONE, score = 9, tags = listOf("赛博朋克" to 100)),
            entity(2, UnifiedCollectionType.DONE, score = 9, tags = listOf("赛博朋克" to 100)),
            entity(3, UnifiedCollectionType.DONE, score = 7, tags = listOf("奇幻" to 100)),
            entity(4, UnifiedCollectionType.DONE, score = 7, tags = listOf("奇幻" to 100)),
            entity(5, UnifiedCollectionType.DONE, score = 7, tags = listOf("奇幻" to 100)),
            entity(6, UnifiedCollectionType.DONE, score = 7, tags = listOf("奇幻" to 100)),
        )
        val names = computeInterestProfile(collections, now).tags.map { it.name }
        assertEquals(listOf("赛博朋克", "奇幻"), names)
    }

    @Test
    fun `没有收藏时间时不衰减, 不能把画像压成空`() {
        // lastUpdated=0 (拿不到收藏时间) 按 1970 年算会让权重变成 1e-33, 画像静默变空
        val profile = computeInterestProfile(
            listOf(
                entity(1, UnifiedCollectionType.DONE, score = 9, tags = listOf("治愈" to 100))
                    .copy(lastUpdated = 0),
            ),
            now,
        )
        assertEquals(listOf("治愈"), profile.tags.map { it.name })
        assertEquals(listOf(1), profile.seeds.map { it.subjectId })
    }

    @Test
    fun `只是浏览过 (NOT_COLLECTED) 的条目不进画像`() {
        val profile = computeInterestProfile(
            listOf(
                entity(1, UnifiedCollectionType.NOT_COLLECTED, tags = listOf("治愈" to 100)),
            ),
            now,
        )
        assertTrue(profile.isEmpty, "实得 $profile")
    }

    @Test
    fun `绝大多数收藏都带的标签仍然是最强兴趣`() {
        // IDF 只是"同分时更罕见的靠前", 不该把真实偏好压下去: 6 部里 5 部是奇幻, 那它就是口味
        val collections = (1..5).map {
            entity(it, UnifiedCollectionType.DONE, score = 8, tags = listOf("奇幻" to 100))
        } + entity(6, UnifiedCollectionType.DONE, score = 8, tags = listOf("治愈" to 100))
        val names = computeInterestProfile(collections, now).tags.map { it.name }
        assertEquals(listOf("奇幻", "治愈"), names)
    }
}
