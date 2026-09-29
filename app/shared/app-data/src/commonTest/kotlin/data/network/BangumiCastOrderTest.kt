/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.data.models.subject.CharacterRole
import me.him188.ani.app.data.models.subject.RelatedCharacterInfo
import me.him188.ani.app.data.network.mapper.toCharacterInfo
import me.him188.ani.app.data.persistent.database.entity.CharacterActorEntity
import me.him188.ani.app.data.repository.subject.RELATIONS_VALID_SINCE_MILLIS
import me.him188.ani.app.data.repository.subject.characterActorRelations
import me.him188.ani.app.data.repository.subject.relationsFresh
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCharacterCast
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCharacterCastType
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCharacterCastType.CV
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCharacterCastType.ChineseDub
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCharacterCastType.JapaneseDub
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSlimCharacter
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSlimPerson
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * 角色的声优按「原版优先」: 各国作品按自己的原语种 (Bangumi 标 CV 的那位), 没有原版记录才用配音 (先中配, 再日配).
 * 用例的配音与先后照 2026-09-28 p1 接口实际返回的 (limit=100): 接口顺序没有规律, 原版常常不在第一个.
 */
class BangumiCastOrderTest {
    private fun cast(relation: BangumiNextCharacterCastType, id: Int, name: String) = BangumiNextCharacterCast(
        person = BangumiNextSlimPerson(
            id = id, name = name, nameCN = name, type = 1, info = "", career = listOf("seiyu"),
            comment = 0, lock = false, nsfw = false,
        ),
        relation = relation,
        summary = "",
    )

    private fun character(id: Int, name: String, vararg casts: BangumiNextCharacterCast) = BangumiNextSubjectCharacter(
        character = BangumiNextSlimCharacter(
            id = id, name = name, nameCN = name, role = 1, info = "", comment = 0, lock = false, nsfw = false,
        ),
        casts = casts.toList(),
        type = 1,
        order = 0,
    )

    private fun firstActor(character: BangumiNextSubjectCharacter): String? =
        character.toCharacterInfo().actors.firstOrNull()?.name

    @Test
    fun `a Japanese anime shows the Japanese original cast rather than a Chinese dub`() {
        // 孤独摇滚! 伊地知虹夏: 接口里中配排在原版前后都有
        val nijika = character(
            1, "伊地知虹夏",
            cast(ChineseDub, 79310, "楊慧玉"), cast(CV, 32687, "鈴代紗弓"), cast(ChineseDub, 79171, "李昀晴"),
        )
        assertEquals(listOf("鈴代紗弓", "楊慧玉", "李昀晴"), nijika.toCharacterInfo().actors.map { it.name })
    }

    @Test
    fun `a Chinese anime shows the Chinese original cast rather than a Japanese dub`() {
        // 魔道祖师 蓝湛, 罗小黑战记 罗小白: 日配排在原版前面
        assertEquals("边江", firstActor(character(2, "蓝湛", cast(JapaneseDub, 1, "立花慎之介"), cast(CV, 2, "边江"))))
        assertEquals("山新", firstActor(character(3, "罗小白", cast(JapaneseDub, 3, "佐倉綾音"), cast(CV, 4, "山新"))))
    }

    @Test
    fun `a western work shows its original cast when one is recorded`() {
        // 双城之战 金克丝: 原版英语演员排在日配和两位中配之后
        val jinx = character(
            4, "金克丝",
            cast(JapaneseDub, 5, "上坂すみれ"), cast(ChineseDub, 6, "聂曦映"), cast(ChineseDub, 7, "刘校妤"), cast(CV, 8, "Ella Purnell"),
        )
        assertEquals("Ella Purnell", firstActor(jinx))
    }

    @Test
    fun `without an original cast a Chinese dub comes before a Japanese dub`() {
        // 双城之战 杰斯: 只录了日配和中配; 同一档里保持接口的先后
        val jayce = character(5, "杰斯", cast(JapaneseDub, 9, "宮崎遊"), cast(ChineseDub, 10, "张福正"), cast(ChineseDub, 11, "蔡海婷"))
        assertEquals(listOf("张福正", "蔡海婷", "宮崎遊"), jayce.toCharacterInfo().actors.map { it.name })
    }

    @Test
    fun `each character stores exactly its first actor`() {
        // 声优表的主键只有角色: 一个角色只能存一位, 存原版那位 (全写进去的话后写的覆盖先写的)
        val nijika = character(1, "伊地知虹夏", cast(ChineseDub, 79310, "楊慧玉"), cast(CV, 32687, "鈴代紗弓"))
        val silent = character(6, "路人")
        val batch = BatchSubjectRelations(
            subjectId = 328609,
            relatedCharacterInfoList = listOf(nijika, silent).mapIndexed { index, it ->
                RelatedCharacterInfo(index, it.toCharacterInfo(), CharacterRole.MAIN)
            },
            relatedPersonInfoList = emptyList(),
        )
        assertEquals(listOf(CharacterActorEntity(1, 32687)), batch.characterActorRelations().toList())
    }

    @Test
    fun `relations fetched before the fix count as stale once`() {
        val now = RELATIONS_VALID_SINCE_MILLIS + 1.hours.inWholeMilliseconds
        assertTrue(relationsFresh(updated = now - 10_000, now = now, expiry = 3.days), "修好之后取的, 没过期")
        assertFalse(relationsFresh(updated = RELATIONS_VALID_SINCE_MILLIS - 60_000, now = now, expiry = 3.days), "修好之前取的要重取")
        assertFalse(relationsFresh(updated = now - 4.days.inWholeMilliseconds, now = now, expiry = 3.days), "过了缓存期")
    }

    @Test
    fun `a device clock behind the fix does not refetch forever`() {
        // 设备时钟早于那个时刻: 取完盖的章也早于它, 照「修好之前」判就会每次都重取
        val now = RELATIONS_VALID_SINCE_MILLIS - 30.days.inWholeMilliseconds
        assertTrue(relationsFresh(updated = now - 10_000, now = now, expiry = 3.days))
    }
}
