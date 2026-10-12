/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

/**
 * 按 API 级别给出 Android 版本号. 系统自报的版本号 (`Build.VERSION.RELEASE`) 不可信: 部分运营商盒子把它改得更高
 * (如 API 19 的 Android 4.4 报「9.0」), 而系统安装器判断装不装得上看的是 API 级别, 显示也以它为准.
 */
object AndroidVersions {
    private val NAMES = mapOf(
        19 to "4.4", 20 to "4.4W", 21 to "5.0", 22 to "5.1", 23 to "6.0", 24 to "7.0", 25 to "7.1",
        26 to "8.0", 27 to "8.1", 28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12L",
        33 to "13", 34 to "14", 35 to "15", 36 to "16",
    )

    /** API 级别对应的版本号; 比表里还新的系统用它自报的版本号. */
    fun name(sdk: Int, release: String): String = NAMES[sdk] ?: release

    /**
     * 系统自报的版本号与 API 级别对得上: 表里版本号的每一段都相同, 自报的多出来的段不算 (「9.0」对 9、「4.4.2」对 4.4);
     * 表里没有的 API 级别不判断, 当作对得上.
     */
    fun releaseMatches(sdk: Int, release: String): Boolean {
        val expected = numbers(NAMES[sdk] ?: return true)
        val reported = numbers(release)
        if (reported.isEmpty()) return false
        return expected.indices.all { i -> reported.getOrNull(i)?.let { it == expected[i] } ?: (expected[i] == 0) }
    }

    /** 「4.4W.2」→ [4, 4, 2]: 每段只取开头的数字, 遇到不以数字开头的段就停. */
    private fun numbers(version: String): List<Int> =
        version.split('.').map { part -> part.takeWhile { it.isDigit() } }.takeWhile { it.isNotEmpty() }.map { it.toInt() }
}
