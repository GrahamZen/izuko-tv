/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.debugconsole

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DebugConsoleHttpTest {
    @Test
    fun `path under the token is parsed with query and form`() {
        val request = assertNotNull(
            parseDebugConsoleRequest("POST /abc123/api/player/control?action=seek HTTP/1.1", "ms=90000&v=1.5", "abc123"),
        )
        assertEquals("POST", request.method)
        assertEquals("api/player/control", request.path)
        assertEquals(mapOf("action" to "seek", "ms" to "90000", "v" to "1.5"), request.params)
    }

    @Test
    fun `form overrides query of the same name`() {
        val request = assertNotNull(parseDebugConsoleRequest("POST /t0k3n/api/x?id=1 HTTP/1.1", "id=2", "t0k3n"))
        assertEquals("2", request.params["id"])
    }

    @Test
    fun `token root maps to the empty path`() {
        assertEquals("", assertNotNull(parseDebugConsoleRequest("GET /t0k3n HTTP/1.1", "", "t0k3n")).path)
        assertEquals("", assertNotNull(parseDebugConsoleRequest("GET /t0k3n/ HTTP/1.1", "", "t0k3n")).path)
    }

    @Test
    fun `wrong or prefixed token is rejected`() {
        assertNull(parseDebugConsoleRequest("GET /other/api/player HTTP/1.1", "", "t0k3n"))
        assertNull(parseDebugConsoleRequest("GET /t0k3nX/api/player HTTP/1.1", "", "t0k3n"))
        assertNull(parseDebugConsoleRequest("GET /api/player HTTP/1.1", "", "t0k3n"))
        assertNull(parseDebugConsoleRequest("garbage", "", "t0k3n"))
    }

    @Test
    fun `percent encoded utf8 and plus are decoded`() {
        assertEquals("中文 a+b", decodeFormComponent("%E4%B8%AD%E6%96%87+a%2Bb"))
        assertEquals("a+b", decodeFormComponent("a+b", plusIsSpace = false))
        assertEquals("日本語のタイトル", decodeFormComponent("日本語のタイトル"))
        assertEquals("x 日本 y", decodeFormComponent("x+日本%20y"))
    }

    @Test
    fun `broken escapes are kept as they are`() {
        assertEquals("100%", decodeFormComponent("100%"))
        assertEquals("%zz1", decodeFormComponent("%zz1"))
        assertEquals("%-1", decodeFormComponent("%-1"))
    }
}
