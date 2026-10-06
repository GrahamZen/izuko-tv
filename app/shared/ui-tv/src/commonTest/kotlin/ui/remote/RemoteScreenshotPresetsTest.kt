/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RemoteScreenshotPresetsTest {
    private fun sanitize(json: String): JsonObject? = RemoteScreenshotPresets.sanitize(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `a text preset keeps its fields`() {
        val p = sanitize(
            """{"id":"abc1","name":" 右下角文字 ","kind":"text","x":0.87,"y":0.92,"deg":-15,"scale":1.4,
               "preset":"br","align":"r","title":false,"ep":true,"time":true}""",
        )!!
        assertEquals("右下角文字", p["name"]!!.jsonPrimitive.content)
        assertEquals(-15.0, p["deg"]!!.jsonPrimitive.content.toDouble())
        assertEquals("br", p["preset"]!!.jsonPrimitive.content)
        assertEquals("r", p["align"]!!.jsonPrimitive.content)
        assertEquals("true", p["ep"]!!.jsonPrimitive.content)
        assertEquals("false", p["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `values are clamped and unknown fields dropped`() {
        val p = sanitize("""{"id":"x1","name":"n","kind":"logo","x":3,"y":-1,"deg":500,"scale":0,"preset":"zz","evil":"<b>"}""")!!
        assertEquals(1.0, p["x"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.0, p["y"]!!.jsonPrimitive.content.toDouble())
        assertEquals(180.0, p["deg"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.05, p["scale"]!!.jsonPrimitive.content.toDouble())
        assertFalse("preset" in p)
        assertFalse("evil" in p)
        // 文字才有的字段 logo 上不留
        assertFalse("title" in p)
    }

    @Test
    fun `presets without id name or kind are refused`() {
        assertNull(sanitize("""{"name":"n","kind":"logo"}"""))
        assertNull(sanitize("""{"id":"x1","name":"   ","kind":"logo"}"""))
        assertNull(sanitize("""{"id":"x1","name":"n","kind":"video"}"""))
        assertNull(sanitize("""{"id":"Bad Id","name":"n","kind":"logo"}"""))
    }

    @Test
    fun `long names are cut to 40 characters`() {
        val p = sanitize("""{"id":"x1","name":"${"名".repeat(60)}","kind":"logo"}""")!!
        assertEquals(40, p["name"]!!.jsonPrimitive.content.length)
    }
}
