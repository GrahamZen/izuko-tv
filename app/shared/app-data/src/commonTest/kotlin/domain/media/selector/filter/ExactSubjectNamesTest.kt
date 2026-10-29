/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExactSubjectNamesTest {
    /** 测试环境没有真的繁简转换, 用这几个字代替 */
    private val simplify: (String) -> String = { s ->
        s.map { mapOf('戰' to '战', '禍' to '祸', '進' to '进', '譚' to '谭')[it] ?: it }.joinToString("")
    }

    private val names = ExactSubjectNames(
        listOf("死神 千年血战篇-祸进谭-", "BLEACH 千年血戦篇-禍進譚-"),
        simplify,
    )

    @Test
    fun `a name prefixed by an English word from another name is exact`() {
        assertTrue(names.matches("BLEACH 死神 千年血戰篇-禍進譚-"))
        assertTrue(names.matches("bleach 死神 千年血战篇-祸进谭-"))
    }

    @Test
    fun `names listed side by side are compared one by one`() {
        assertTrue(names.matches("Bleach Sennen Kessenhen /  BLEACH 死神 千年血戰篇-禍進譚-"))
        assertTrue(names.matches("死神 千年血战篇-祸进谭- ／ Something Else"))
    }

    @Test
    fun `traditional characters are compared as simplified`() {
        assertTrue(names.matches("死神 千年血戰篇-禍進譚-"))
    }

    @Test
    fun `other seasons and partial names stay fuzzy`() {
        assertFalse(names.matches("BLEACH 死神 千年血戰篇"))
        assertFalse(names.matches("BLEACH 死神 千年血戰篇-訣別譚-"))
        assertFalse(names.matches("BLEACH"))
        assertFalse(names.matches("死神"))
        assertFalse(names.matches(""))
    }

    @Test
    fun `only English words from the subject names are dropped`() {
        assertFalse(names.matches("Sennen 死神 千年血战篇-祸进谭-"))
        assertFalse(names.matches("Another BLEACH 死神 千年血战篇-祸进谭- Extra"))
        assertTrue(names.matches("BLEACH BLEACH 死神 千年血战篇-祸进谭-"))
    }

    @Test
    fun `the same season written another way is exact`() {
        val edgerunners = ExactSubjectNames(listOf("赛博朋克：边缘行者 2", "Cyberpunk: Edgerunners 2"), simplify)
        assertTrue(edgerunners.matches("赛博朋克：边缘行者 第二季"))
        assertTrue(edgerunners.matches("赛博朋克：边缘行者第二季"))
        assertTrue(edgerunners.matches("Cyberpunk: Edgerunners Season 2"))
        // 别的季、系列名不同、只写了系列名的仍是模糊
        assertFalse(edgerunners.matches("赛博朋克：边缘行者 第三季"))
        assertFalse(edgerunners.matches("赛博朋克 第二季"))
        assertFalse(edgerunners.matches("赛博朋克：边缘行者"))
        // 页名末尾的数字多是集号, 不当季号
        val explicit = ExactSubjectNames(listOf("间谍过家家 第二季"), simplify)
        assertTrue(explicit.matches("间谍过家家 Season 2"))
        assertFalse(explicit.matches("间谍过家家 2"))
        // 第一季的名字不写季号, 不按季号认
        assertFalse(ExactSubjectNames(listOf("间谍过家家"), simplify).matches("间谍过家家 第一季"))
    }

    @Test
    fun `a name followed by the air year is exact`() {
        val haruhi = ExactSubjectNames(listOf("凉宫春日的忧郁", "涼宮ハルヒの憂鬱"), simplify, airYear = 2006)
        assertTrue(haruhi.matches("凉宫春日的忧郁2006"))
        assertTrue(haruhi.matches("凉宫春日的忧郁 (2006)"))
        assertTrue(haruhi.matches("凉宫春日的忧郁【2006】"))
        assertTrue(haruhi.matches("凉宫春日的忧郁 2006年版"))
        assertTrue(haruhi.matches("涼宮ハルヒの憂鬱 2006"))
        // 别的年份是另一版
        assertFalse(haruhi.matches("凉宫春日的忧郁2009"))
        assertFalse(haruhi.matches("凉宫春日的忧郁 (2009)"))
        // 年份不在名字后面的不算
        assertFalse(haruhi.matches("2006凉宫春日的忧郁"))
        // 不知道首播年份时照旧是模糊
        assertFalse(ExactSubjectNames(listOf("凉宫春日的忧郁"), simplify).matches("凉宫春日的忧郁2006"))
    }

    @Test
    fun `another title before the name and the air year after it is exact`() {
        val fate = ExactSubjectNames(listOf("Fate/stay night"), simplify, airYear = 2006)
        assertTrue(fate.matches("命运之夜 Fate/stay night 2006"))
        assertTrue(fate.matches("命运之夜 Fate/stay night (2006)"))
        // 同系列的别部、年份对不上的、没写年份的仍是模糊
        assertFalse(fate.matches("命运之夜 无限剑制 Fate/stay night [Unlimited Blade Works]"))
        assertFalse(fate.matches("命运之夜 Fate/stay night 2014"))
        assertFalse(fate.matches("命运之夜 Fate/stay night"))
        // 名字与前面的叫法连在一起的不算 (不知道哪里断开)
        assertFalse(fate.matches("命运之夜Fate/stay night 2006"))
        // 太短的名字不靠前缀认
        val k = ExactSubjectNames(listOf("K"), simplify, airYear = 2012)
        assertFalse(k.matches("Persona K 2012"))
    }

    @Test
    fun `two names of the subject written together are exact`() {
        val monster = ExactSubjectNames(listOf("怪物", "MONSTER", "魔剎"), simplify)
        assertTrue(monster.matches("怪物 MONSTER"))
        assertTrue(monster.matches("MONSTER 怪物"))
        val another = ExactSubjectNames(listOf("Another", "アナザー", "替身"), simplify)
        assertTrue(another.matches("替身 Another"))
        assertTrue(another.matches("替身Another"))
        // 还多出别的字的、同一个名字写两遍的仍是模糊
        assertFalse(monster.matches("怪物 MONSTER 国语版"))
        assertFalse(monster.matches("怪物怪物"))
        assertFalse(another.matches("心之彼端二 Another Day"))
        val tsugumomo = ExactSubjectNames(listOf("怪怪守护神", "つぐもも"), simplify)
        assertFalse(tsugumomo.matches("继怪怪守护神"))
        assertFalse(tsugumomo.matches("怪怪守护神2"))
    }

    @Test
    fun `dashes decorations and uppercase numerals written another way are exact`() {
        val kenshin = ExactSubjectNames(listOf("浪客剑心 －明治剑客浪漫谭－ 京都动乱"), simplify)
        assertTrue(kenshin.matches("浪客剑心-明治剑客浪漫谭-京都动乱"))
        assertTrue(ExactSubjectNames(listOf("摇曳露营△ 第二季"), simplify).matches("摇曳露营第二季"))
        val lover = ExactSubjectNames(listOf("我们不可能成为恋人！绝对不行。 (※似乎可行？) 〜再次闪耀！〜"), simplify)
        assertTrue(lover.matches("我们不可能成为恋人！绝对不行。 (似乎可行？) 〜再次闪耀！〜"))
        assertTrue(lover.matches("我们不可能成为恋人！绝对不行。 (※似乎可行？) 〜再次闪耀！"))
        val fireForce = ExactSubjectNames(listOf("炎炎消防队 叁之章 第2部分"), simplify)
        assertTrue(fireForce.matches("炎炎消防队 三之章 第2部分"))
        assertFalse(fireForce.matches("炎炎消防队 三之章"))
        // 「？」照旧不当标点: 辉夜第一季与第二季的名字只差这个问号
        val kaguya = ExactSubjectNames(listOf("辉夜大小姐想让我告白～天才们的恋爱头脑战～"), simplify)
        assertFalse(kaguya.matches("辉夜大小姐想让我告白？～天才们的恋爱头脑战～"))
    }

    @Test
    fun `final season written in Chinese is exact`() {
        val magia = ExactSubjectNames(
            listOf("魔法纪录 魔法少女小圆外传 Final SEASON -浅梦的黎明-", "魔法纪录 魔法少女小圆外传 Final SEASON -浅梦之晓-"),
            simplify,
        )
        assertTrue(magia.matches("魔法纪录：魔法少女小圆外传 最终季 -浅梦之晓-"))
        assertTrue(ExactSubjectNames(listOf("进击的巨人 The Final Season"), simplify).matches("进击的巨人 最终季"))
        assertTrue(ExactSubjectNames(listOf("进击的巨人 最终季"), simplify).matches("Shingeki no Kyojin / 进击的巨人 The Final Season"))
        // 最终季的另一部分仍是模糊
        assertFalse(ExactSubjectNames(listOf("进击的巨人 The Final Season"), simplify).matches("进击的巨人 最终季 Part.2"))
        assertFalse(ExactSubjectNames(listOf("进击的巨人 The Final Season Part 2"), simplify).matches("进击的巨人 最终季"))
        // 只是名字里带这两个词的不换
        assertFalse(ExactSubjectNames(listOf("Final Seasons"), simplify).matches("最终季s"))
    }

    @Test
    fun `a name one ordinary character apart is exact`() {
        val classroom = ExactSubjectNames(listOf("欢迎来到实力至上主义的教室 第二季"), simplify)
        assertTrue(classroom.matches("欢迎来到实力至上主义教室 第二季"))
        assertTrue(classroom.matches("欢迎来到实力至上主义教室第二季"))
        val bookworm = ExactSubjectNames(listOf("小书痴的下克上 〜为了成为图书管理员而不择手段〜 第二季"), simplify)
        assertTrue(bookworm.matches("小书痴的下克上：为了成为图书管理员不择手段！第二季"))
        val pillars = ExactSubjectNames(listOf("鬼灭之刃 柱合会议・蝶屋敷篇"), simplify)
        assertTrue(pillars.matches("鬼灭之刃 柱众会议・蝶屋敷篇"))
        // 差两个字的仍是模糊
        assertFalse(classroom.matches("欢迎来到实力主义教室 第二季"))
    }

    @Test
    fun `one character that marks a season part or version keeps the name fuzzy`() {
        // 季号、前后篇
        val classroom = ExactSubjectNames(listOf("欢迎来到实力至上主义的教室 第二季"), simplify)
        assertFalse(classroom.matches("欢迎来到实力至上主义的教室 第三季"))
        assertFalse(ExactSubjectNames(listOf("进击的巨人 最终季 完结篇 前篇"), simplify).matches("进击的巨人 最终季 完结篇 后篇"))
        // 续作前缀
        assertFalse(ExactSubjectNames(listOf("吊带袜天使 第一季"), simplify).matches("新 吊带袜天使 第一季"))
        assertFalse(ExactSubjectNames(listOf("怪怪守护神 第一季"), simplify).matches("继怪怪守护神 第一季"))
        // 数字与英文字母
        assertFalse(ExactSubjectNames(listOf("超时空要塞 爱还记得吗"), simplify).matches("超时空要塞7 爱还记得吗"))
        assertFalse(ExactSubjectNames(listOf("机动战士高达 水星的魔女"), simplify).matches("机动战士高达Z 水星的魔女"))
        // 名字太短的不靠差一个字认
        assertFalse(ExactSubjectNames(listOf("怪物王女"), simplify).matches("怪物王子"))
    }

    @Test
    fun `a subject without names matches nothing`() {
        assertFalse(ExactSubjectNames(emptyList(), simplify).matches("死神"))
        assertFalse(ExactSubjectNames(listOf(""), simplify).matches(""))
    }
}
