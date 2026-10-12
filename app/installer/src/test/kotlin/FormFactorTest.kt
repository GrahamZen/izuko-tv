/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormFactorTest {
    private fun isPhone(
        televisionMode: Boolean = false,
        leanback: Boolean = false,
        touchscreen: Boolean = true,
        smallestWidthDp: Int = 411,
        naturalWidth: Int = 1080,
        naturalHeight: Int = 2400,
    ) = FormFactor.isPhone(televisionMode, leanback, touchscreen, smallestWidthDp, naturalWidth, naturalHeight)

    @Test
    fun `a phone is a phone`() {
        assertTrue(isPhone())
    }

    @Test
    fun `an Android TV is not a phone`() {
        assertFalse(isPhone(televisionMode = true, leanback = true, touchscreen = false, smallestWidthDp = 540, naturalWidth = 1920, naturalHeight = 1080))
    }

    @Test
    fun `a TV box claiming a touchscreen without leanback is not a phone`() {
        assertFalse(isPhone(smallestWidthDp = 540, naturalWidth = 1920, naturalHeight = 1080))
    }

    @Test
    fun `a tablet whose natural orientation is portrait is not a phone`() {
        assertFalse(isPhone(smallestWidthDp = 800, naturalWidth = 1600, naturalHeight = 2560))
    }

    @Test
    fun `a tablet whose natural orientation is landscape is not a phone`() {
        assertFalse(isPhone(smallestWidthDp = 800, naturalWidth = 2560, naturalHeight = 1600))
    }

    @Test
    fun `a portrait device without a touchscreen is not a phone`() {
        assertFalse(isPhone(touchscreen = false))
    }

    @Test
    fun `an unknown screen width is not a phone`() {
        assertFalse(isPhone(smallestWidthDp = 0))
    }
}
