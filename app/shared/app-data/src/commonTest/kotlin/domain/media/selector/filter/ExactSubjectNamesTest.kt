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
    fun `a subject without names matches nothing`() {
        assertFalse(ExactSubjectNames(emptyList(), simplify).matches("死神"))
        assertFalse(ExactSubjectNames(listOf(""), simplify).matches(""))
    }
}
