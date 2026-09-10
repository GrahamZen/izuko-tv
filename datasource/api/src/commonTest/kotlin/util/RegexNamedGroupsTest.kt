/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [namedGroupIndex] 是 Android 8.0 以下按名字取分组的退路, 这里拿平台自己的 `groups[name]` 对拍.
 */
class RegexNamedGroupsTest {
    private fun check(pattern: String, input: String, name: String, expectedIndex: Int) {
        assertEquals(expectedIndex, namedGroupIndex(pattern, name), "index of <$name> in $pattern")
        val match = Regex(pattern).find(input) ?: error("$pattern does not match $input")
        assertEquals(match.groups[name]?.range, match.groups[expectedIndex]?.range, "group <$name> in $pattern")
    }

    @Test
    fun `title parser brackets`() {
        val pattern = """\[(?<v1>.+?)\]|\((?<v2>.+?)\)|\{(?<v3>.+?)\}|【(?<v4>.+?)】|（(?<v5>.+?)）|「(?<v6>.+?)」|『(?<v7>.+?)』"""
        check(pattern, "[a]", "v1", 1)
        check(pattern, "(a)", "v2", 2)
        check(pattern, "『a』", "v7", 7)
    }

    @Test
    fun `title parser collection`() {
        val pattern = """(?<start>(?:SP)?\d{1,4})\s?(?:-{1,2}|~|～)\s?(?<end>\d{1,4})(?:TV|BDrip|BD)?(?<extra>\+?.+)?"""
        check(pattern, "01-12+SP", "start", 1)
        check(pattern, "01-12+SP", "end", 2)
        check(pattern, "01-12+SP", "extra", 3)
    }

    @Test
    fun `plain groups before named`() {
        check("""^(?<ch>.+?)(\d+)?$""", "线路2", "ch", 1)
        check("""(\d+)-(?<ep>\d+)""", "1-2", "ep", 2)
    }

    @Test
    fun `lookaround and non-capturing are not counted`() {
        check("""(?:a)(?<=a)(?<x>b)(?!c)""", "ab", "x", 1)
    }

    @Test
    fun `parens in escapes, quotes and classes are not counted`() {
        check("""\((?<x>b)\)""", "(b)", "x", 1)
        check("""[(](?<x>b)""", "(b", "x", 1)
        check("""[a-c&&[^(]](?<x>\d)""", "a1", "x", 1)
        check("""\Q(\E(?<x>b)""", "(b", "x", 1)
    }

    @Test
    fun `name is matched exactly`() {
        check("""(?<ab>a)(?<a>b)""", "ab", "a", 2)
        assertNull(namedGroupIndex("""(?<ab>a)""", "a"))
        assertNull(namedGroupIndex("""(a)(b)""", "a"))
    }

    @Test
    fun `namedGroup matches platform lookup`() {
        val regex = Regex("""第\s*(?<ep>.+)\s*[话集]""")
        val match = regex.find("第 3 集")!!
        assertEquals(match.groups["ep"]?.value, match.namedGroup(regex, "ep")?.value)
    }
}
