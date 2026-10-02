/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.matcher

import kotlin.test.Test
import kotlin.test.assertEquals

class WebVideoSniffTimeoutTest {
    private class Matcher(override val sniffTimeoutMillis: Long?) : WebVideoMatcher, WebVideoSniffTimeout {
        override fun match(url: String, context: WebVideoMatcherContext) = WebVideoMatcher.MatchResult.Continue
    }

    private val plain = WebVideoMatcher { _, _ -> WebVideoMatcher.MatchResult.Continue }

    @Test
    fun `source timeout extends the global one up to the cap`() {
        assertEquals(21_000L, listOf(plain, Matcher(21_000)).sniffTimeoutMillis(8_000))
        // 源写得再长也只算到上限
        assertEquals(MAX_SOURCE_SNIFF_TIMEOUT_MILLIS, listOf(Matcher(10_000), Matcher(60_000)).sniffTimeoutMillis(8_000))
    }

    @Test
    fun `global setting is never shortened`() {
        assertEquals(20_000L, listOf(Matcher(10_000)).sniffTimeoutMillis(20_000))
        assertEquals(8_000L, listOf(plain, Matcher(null)).sniffTimeoutMillis(8_000))
        assertEquals(8_000L, emptyList<WebVideoMatcher>().sniffTimeoutMillis(8_000))
    }
}
