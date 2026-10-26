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
    fun `a subject without names matches nothing`() {
        assertFalse(ExactSubjectNames(emptyList(), simplify).matches("死神"))
        assertFalse(ExactSubjectNames(listOf(""), simplify).matches(""))
    }
}
