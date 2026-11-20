/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.ui.geometry.Rect
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 详情页首屏没有大图 ([TvHeroZoomHandoff.detailsHasHero] 为假, 触屏的详情页) 时点卡片不放大; 有大图时照旧.
 */
class TvHeroZoomDetailsHeroTest {
    private val owner = Any()

    private fun publishCard() {
        TvHeroZoomHandoff.publish(owner, SUBJECT, "list-backdrop", Rect(0f, 0f, 100f, 50f))
        TvHeroZoomHandoff.detailsUrlProvider = { "details-backdrop" }
    }

    @AfterTest
    fun reset() {
        TvHeroZoomHandoff.session?.let { TvHeroZoomHandoff.endSession(it) }
        TvHeroZoomHandoff.retract(owner)
        TvHeroZoomHandoff.detailsUrlProvider = null
        TvHeroZoomHandoff.detailsHasHero = true
    }

    @Test
    fun `opening a card zooms when the details page has a hero image`() {
        publishCard()
        assertTrue(TvHeroZoomHandoff.willZoom(SUBJECT))
    }

    @Test
    fun `opening a card does not zoom when the details page has no hero image`() {
        publishCard()
        TvHeroZoomHandoff.detailsHasHero = false
        assertFalse(TvHeroZoomHandoff.willZoom(SUBJECT))
        assertNull(TvHeroZoomHandoff.session)
    }

    private companion object {
        const val SUBJECT = 42
    }
}
