/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.subject

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvTextLanguageTest {
    @Test
    fun `simplified and traditional chinese are both chinese`() {
        for (language in listOf("zh", "zh-CN", "zh-TW", "zh-HK", "zh_HK", "ZH")) {
            assertTrue(isChineseUiLanguage(language), language)
        }
    }

    @Test
    fun `other languages are not chinese`() {
        for (language in listOf("en", "en-US", "ja", "ja-JP", "ko", "")) {
            assertFalse(isChineseUiLanguage(language), language)
        }
    }

    @Test
    fun `chinese ui follows the setting for titles`() {
        assertFalse(tvUseOriginalTitle(useOriginalTitle = false, uiLanguage = "zh-CN"))
        assertTrue(tvUseOriginalTitle(useOriginalTitle = true, uiLanguage = "zh-CN"))
    }

    @Test
    fun `other ui languages always use the original title`() {
        assertTrue(tvUseOriginalTitle(useOriginalTitle = false, uiLanguage = "en"))
        assertTrue(tvUseOriginalTitle(useOriginalTitle = true, uiLanguage = "ja"))
    }

    @Test
    fun `only non chinese ui prefers tmdb text`() {
        assertFalse(tvPrefersTmdbText("zh-CN"))
        assertFalse(tvPrefersTmdbText("zh-TW"))
        assertTrue(tvPrefersTmdbText("en"))
        assertTrue(tvPrefersTmdbText("ja-JP"))
    }
}
