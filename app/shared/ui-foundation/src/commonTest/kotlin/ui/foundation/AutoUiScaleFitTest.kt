/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoUiScaleFitTest {
    @Test
    fun `a canvas already at the design size keeps the scale`() {
        assertNull(tvUiScaleToFitDesignCanvas(1920, 1080, 2f))
        assertNull(tvUiScaleToFitDesignCanvas(3840, 2160, 4f))
        assertNull(tvUiScaleToFitDesignCanvas(1280, 720, 1.33125f))
    }

    @Test
    fun `a larger canvas keeps the scale`() {
        assertNull(tvUiScaleToFitDesignCanvas(1920, 1080, 1.5f))
    }

    @Test
    fun `an overreported density rounds down to the 10 percent step that fits`() {
        // 1080p 报成 2.75 倍: 画布约 698 x 393, 要 72.7% 才够, 取 70%
        assertEquals(0.7f, tvUiScaleToFitDesignCanvas(1920, 1080, 2.75f))
        // 720p 报成 2 倍: 画布 640 x 360, 要 66.7%, 取 60%
        assertEquals(0.6f, tvUiScaleToFitDesignCanvas(1280, 720, 2f))
    }

    @Test
    fun `the result never goes below the minimum scale`() {
        assertEquals(0.5f, tvUiScaleToFitDesignCanvas(1280, 720, 4f))
    }

    @Test
    fun `portrait pixels are matched long side to width`() {
        assertEquals(0.7f, tvUiScaleToFitDesignCanvas(1080, 1920, 2.75f))
    }

    @Test
    fun `missing metrics keep the scale`() {
        assertNull(tvUiScaleToFitDesignCanvas(0, 1080, 2f))
        assertNull(tvUiScaleToFitDesignCanvas(1920, 1080, 0f))
    }
}
