/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 选源面板的发布日期 ([formatPublishedDate]): 这一年的只写月日, 别的年份带上年.
 */
class TvSourcePublishedDateTest {
    private val shanghai = TimeZone.of("Asia/Shanghai")

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 20, timeZone: TimeZone = shanghai): Long =
        LocalDateTime(year, month, day, hour, 0).toInstant(timeZone).toEpochMilliseconds()

    @Test
    fun `a date in the current year shows month and day`() {
        assertEquals("11-26", formatPublishedDate(millis(2026, 11, 26), shanghai, currentYear = 2026))
    }

    @Test
    fun `a date in another year shows the year`() {
        assertEquals("2024-11-26", formatPublishedDate(millis(2024, 11, 26), shanghai, currentYear = 2026))
        assertEquals("2025-12-31", formatPublishedDate(millis(2025, 12, 31), shanghai, currentYear = 2026))
    }

    @Test
    fun `the year is taken in the given time zone`() {
        // 2025-12-31 20:00 UTC is already 2026-01-01 in Shanghai
        val newYearInShanghai = millis(2025, 12, 31, timeZone = TimeZone.UTC)
        assertEquals("01-01", formatPublishedDate(newYearInShanghai, shanghai, currentYear = 2026))
        assertEquals("2025-12-31", formatPublishedDate(newYearInShanghai, TimeZone.UTC, currentYear = 2026))
    }

    @Test
    fun `an unknown date is empty`() {
        assertEquals("", formatPublishedDate(0, shanghai, currentYear = 2026))
    }
}
