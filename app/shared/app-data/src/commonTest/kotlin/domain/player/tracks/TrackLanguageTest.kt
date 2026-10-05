/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.tracks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackLanguageTest {
    private fun key(label: String?, code: String? = null) = TrackLanguage.of(label, code)?.key

    @Test
    fun `track names`() {
        assertEquals("zh-Hans", key("简体中文"))
        assertEquals("zh-Hans", key("中文（简体）"))
        assertEquals("zh-Hans", key("Chinese (Simplified)"))
        assertEquals("zh-Hans", key("CHS"))
        assertEquals("zh-Hant", key("繁體中文"))
        assertEquals("zh-Hant", key("Traditional Chinese"))
        assertEquals("zh-Hant", key("CHT"))
        assertEquals("zh-Hans+ja", key("简日双语"))
        assertEquals("zh-Hans+ja", key("CHS&JPN"))
        assertEquals("zh-Hant+ja", key("繁日雙語"))
        assertEquals("zh", key("中文"))
        assertEquals("zh", key("国语"))
        assertEquals("ja", key("日本語"))
        assertEquals("ja", key("Japanese"))
        assertEquals("en", key("English"))
        assertNull(key("Signs & Songs"))
        assertNull(key("字幕 1"))
    }

    @Test
    fun `language codes`() {
        assertEquals("ja", key(null, "jpn"))
        assertEquals("ja", key(null, "ja"))
        assertEquals("zh", key(null, "chi"))
        assertEquals("zh-Hans", key(null, "zh-Hans"))
        assertEquals("zh-Hans", key(null, "zh-CN"))
        assertEquals("zh-Hant", key(null, "zh-TW"))
        assertEquals("zh-Hant", key(null, "zh-hant-hk"))
        assertEquals("en", key(null, "eng"))
        assertEquals("ko", key(null, "ko"))
        assertNull(key(null, "und"))
        assertNull(key(null, null))
    }

    /** 名字优先 (分得出简繁); 名字只说了中文时用语言码补简繁; 名字认不出时只看语言码 */
    @Test
    fun `name and code together`() {
        assertEquals("zh-Hant", key("繁體中文", "chi"))
        assertEquals("zh-Hant", key("中文", "zh-TW"))
        assertEquals("ja", key("Signs", "jpn"))
        assertEquals("zh-Hans", key("简体", "zh-TW"))
    }

    @Test
    fun `keys round trip`() {
        for (k in listOf("zh-Hans", "zh-Hant+ja", "zh", "zh+ja", "ja", "en", "ko")) {
            assertEquals(k, TrackLanguage.parseKey(k)?.key)
        }
        assertNull(TrackLanguage.parseKey(""))
    }
}
