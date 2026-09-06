/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [tvPlayerTrailingTime]: 进度条右边平时是「-剩余」, 拖拽预览中是总长.
 */
class TvPlayerTrailingTimeTest {
    @Test
    fun `playing shows remaining time with a minus sign`() {
        assertEquals("-12:49", tvPlayerTrailingTime(positionMillis = 11 * 60_000 + 22_000, totalMillis = 24 * 60_000 + 11_000, previewing = false))
    }

    @Test
    fun `remaining time over an hour keeps the hour`() {
        assertEquals("-1:05:00", tvPlayerTrailingTime(positionMillis = 25 * 60_000, totalMillis = 90 * 60_000, previewing = false))
    }

    @Test
    fun `scrubbing shows the total duration`() {
        assertEquals("24:11", tvPlayerTrailingTime(positionMillis = 11 * 60_000, totalMillis = 24 * 60_000 + 11_000, previewing = true))
    }

    @Test
    fun `unknown duration shows zero without a minus sign`() {
        assertEquals("00:00", tvPlayerTrailingTime(positionMillis = 5_000, totalMillis = 0, previewing = false))
    }

    @Test
    fun `position past the end does not go negative`() {
        assertEquals("-00:00", tvPlayerTrailingTime(positionMillis = 25 * 60_000, totalMillis = 24 * 60_000, previewing = false))
    }
}
