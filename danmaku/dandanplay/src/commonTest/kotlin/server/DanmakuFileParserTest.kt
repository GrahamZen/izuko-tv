/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay.server

import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DanmakuFileParserTest {
    private fun parse(text: String) = DanmakuFileParser.parse(text.encodeToByteArray())

    @Test
    fun `reads xml with font size and color`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?><i><chatserver>chat.example.com</chatserver>
            <d p="23.826,1,25,16777215,1422201084,0,057075e9,757076900">hello</d>
            <d p="1.5,4,25,255,1422201084,0,abc,1">bottom</d>
            <d p="2,5,18,65280,1422201084,0,abc,2">top</d>
            </i>
        """.trimIndent()
        assertEquals(
            listOf(
                DanmakuContent(23826, 16777215, "hello", DanmakuLocation.NORMAL),
                DanmakuContent(1500, 255, "bottom", DanmakuLocation.BOTTOM),
                DanmakuContent(2000, 65280, "top", DanmakuLocation.TOP),
            ),
            parse(xml),
        )
    }

    @Test
    fun `xml entities are decoded`() {
        val xml = """<i><d p="1,1,25,1,0,0,a,1">a &amp; b &lt;c&gt; &#20320;&#x597D;</d></i>"""
        assertEquals("a & b <c> 你好", parse(xml)?.single()?.text)
    }

    @Test
    fun `advanced and special danmaku are skipped`() {
        val xml = """
            <i>
            <d p="1,7,25,1,0,0,a,1">advanced</d>
            <d p="1,8,25,1,0,0,a,2">code</d>
            <d p="1,1,25,1,0,2,a,3">special pool</d>
            <d p="1,6,25,1,0,0,a,4">reverse</d>
            </i>
        """.trimIndent()
        assertEquals(listOf("reverse"), parse(xml)?.map { it.text })
    }

    @Test
    fun `empty xml file is an empty list`() {
        assertEquals(emptyList(), parse("""<?xml version="1.0"?><i></i>"""))
    }

    @Test
    fun `xml that is not danmaku is null`() {
        assertNull(parse("""<html><body>nope</body></html>"""))
    }

    @Test
    fun `reads dandanplay json`() {
        val json = """{"count":2,"comments":[{"cid":1,"p":"2.69,1,15106878,[source]","m":"hi"},{"cid":2,"p":"3,5,255,123","m":"top"}]}"""
        assertEquals(
            listOf(
                DanmakuContent(2690, 15106878, "hi", DanmakuLocation.NORMAL),
                DanmakuContent(3000, 255, "top", DanmakuLocation.TOP),
            ),
            parse(json),
        )
    }

    @Test
    fun `reads bare json array`() {
        assertEquals(listOf("a"), parse("""[{"p":"1,1,255,u","m":"a"}]""")?.map { it.text })
    }

    @Test
    fun `json that is not danmaku is null`() {
        assertNull(parse("""{"uiSettings":{}}"""))
        assertNull(parse("""{broken"""))
    }

    @Test
    fun `json escapes are decoded and other fields skipped`() {
        val json = """
            {"count":2,"meta":{"a":[1,{"b":"]}"}]},"comments":[
              {"cid":1,"t":0.06,"like":2,"x":null,"nested":{"p":"9,9,9,9"},"p":"1,1,255,[a]","m":"q\"uote \\ \n \u4f60\u597d \/"},
              {"m":"second","p":"2,5,255,u"},
              {"p":"3,1,255,u"},
              {}
            ],"success":true}
        """.trimIndent()
        assertEquals(
            listOf("q\"uote \\ \n 你好 /", "second"),
            parse(json)?.map { it.text },
        )
    }

    @Test
    fun `encoded file reads back the same`() {
        val contents = listOf(
            DanmakuContent(1000, 16777215, "普通 \"引号\" \\ \n", DanmakuLocation.NORMAL),
            DanmakuContent(2500, 255, "底部", DanmakuLocation.BOTTOM),
            DanmakuContent(1234000, 65280, "顶部", DanmakuLocation.TOP),
        )
        assertEquals(contents, DanmakuFileParser.parse(DanmakuFileParser.encode(contents).encodeToByteArray()))
    }

    @Test
    fun `plain text is null`() {
        assertNull(parse("just some text"))
        assertNull(parse(""))
    }

    @Test
    fun `utf8 bom is skipped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                """<i><d p="1,1,25,1,0,0,a,1">x</d></i>""".encodeToByteArray()
        assertEquals("x", DanmakuFileParser.parse(bytes)?.single()?.text)
    }

    @Test
    fun `utf16 with bom is decoded`() {
        val text = """<i><d p="1,1,25,1,0,0,a,1">弹幕</d></i>"""
        val bytes = ArrayList<Byte>().apply {
            add(0xFF.toByte())
            add(0xFE.toByte())
            for (c in text) {
                add((c.code and 0xFF).toByte())
                add((c.code shr 8).toByte())
            }
        }.toByteArray()
        assertEquals("弹幕", DanmakuFileParser.parse(bytes)?.single()?.text)
    }
}
