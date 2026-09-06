/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SeriesKeyTest {
    private fun same(vararg names: String) {
        val keys = names.map { seriesKeyOf(it) }
        keys.forEach { assertEquals(keys.first(), it, "应当归一到同一系列: ${names.toList()} -> $keys") }
    }

    @Test
    fun `汉字数字与阿拉伯数字的季号都要认`() {
        same("某作品", "某作品 第二季", "某作品 第2季", "某作品 第2期")
    }

    @Test
    fun `拉丁写法的季号`() {
        same("Some Show", "Some Show Season 2", "Some Show 2nd Season", "Some Show Part 3")
    }

    @Test
    fun `季号后面还挂着副标题时一并削掉`() {
        same("鬼灭之刃", "鬼灭之刃 第二季 游郭篇")
    }

    @Test
    fun `结尾的罗马数字`() {
        same("命运石之门", "命运石之门 Ⅱ", "命运石之门 II")
    }

    @Test
    fun `汉字假名后面紧跟的结尾 S 是季号`() {
        same("小林家的龙女仆", "小林家的龙女仆S")
        same("とある科学の超電磁砲", "とある科学の超電磁砲S")
        // 拉丁字母后面的 S 是词的一部分
        assertNotEquals(seriesKeyOf("BEASTAR"), seriesKeyOf("BEASTARS"))
    }

    @Test
    fun `括号里的补充说明不作数`() {
        same("某作品", "某作品（TV版）", "某作品 (2015)")
    }

    @Test
    fun `标点与空白写法不同也算同一部`() {
        same("Re:从零开始的异世界生活", "Re从零开始的异世界生活", "Re：从零开始的异世界生活")
    }

    @Test
    fun `不相干的作品不能被并到一起`() {
        assertNotEquals(seriesKeyOf("进击的巨人"), seriesKeyOf("进击的巨婴"))
        assertNotEquals(seriesKeyOf("某作品"), seriesKeyOf("另一部作品"))
    }

    @Test
    fun `结尾数字不是季号时不许削（年份 编号）`() {
        // 「攻壳机动队 2045」削成「攻壳机动队」会把两部不同作品并到一起
        assertNotEquals(seriesKeyOf("攻壳机动队"), seriesKeyOf("攻壳机动队 2045"))
        assertNotEquals(seriesKeyOf("高达"), seriesKeyOf("高达 00"))
    }

    @Test
    fun `总集编与分章剧场版的「…篇」副标题算同一系列`() {
        // 2026-09-07 真机: 「换换口味」12 个里 3 个是天元突破 (正片 + 红莲篇 + 螺岩篇)
        assertEquals(seriesKeyOf("天元突破 红莲螺岩"), seriesKeyOf("天元突破红莲螺岩 螺岩篇"))
        assertEquals(seriesKeyOf("天元突破 红莲螺岩"), seriesKeyOf("天元突破红莲螺岩 红莲篇"))
        assertEquals(seriesKeyOf("浪客剑心"), seriesKeyOf("浪客剑心 追忆篇"))
    }

    @Test
    fun `没有分隔空白时不许当副标题削掉`() {
        // 「动画短篇」削成「动画」会把一批不相干的东西并到一起
        assertNotEquals(seriesKeyOf("某作品"), seriesKeyOf("某作品动画短篇"))
    }

    @Test
    fun `空名字给空键（调用方据此跳过去重）`() {
        assertEquals("", seriesKeyOf(""))
        assertEquals("", seriesKeyOf("   "))
    }

    @Test
    fun `明确的续作写法是强迹象`() {
        listOf(
            "某作品 第二季", "某作品 第2期", "某作品 第３期", "某作品 第十季", "某作品（第二季）",
            "Some Show Season 2", "Some Show 2nd Season", "Some Show Part 2",
            "命运石之门 Ⅱ", "某作品 III",
            "鬼灭之刃 游郭篇", "进击的巨人 最终季", "Attack on Titan The Final Season",
        ).forEach { assertEquals(SEQUEL_HINT_STRONG, sequelHint(it), it) }
    }

    @Test
    fun `只带数字或结尾 S 是弱迹象`() {
        listOf(
            "超时空要塞7", "机动警察剧场版2 和平保卫战", "攻壳机动队 2045", "高达 00",
            "某作品 第1期", "Some Show Season 1", "小林家的龙女仆S", "とある科学の超電磁砲S",
        ).forEach { assertEquals(SEQUEL_HINT_WEAK, sequelHint(it), it) }
    }

    @Test
    fun `汉字数字与没有标记的名字看不出来`() {
        listOf(
            "某作品", "某作品 第一季", "某作品 I", "某作品动画短篇", "石纪元 新世界",
            "一拳超人", "三月的狮子", "七大罪", "BEASTARS",
        ).forEach { assertEquals(SEQUEL_HINT_NONE, sequelHint(it), it) }
    }
}
