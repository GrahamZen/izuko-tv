/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleCharsetGuesserTest {
    /** mkv 里一条 SRT 字幕的样子: 序号、时间轴、正文. */
    private fun cue(text: String, charset: Charset): ByteArray =
        "1\r\n00:00:12,000 --> 00:00:21,000\r\n$text\r\n".toByteArray(charset)

    private fun SubtitleCharsetGuesser.convert(bytes: ByteArray): String? = toUtf8OrNull(bytes, 0, bytes.size)?.toString(Charsets.UTF_8)

    @Test
    fun `utf-8 and utf-16 are left as they are`() {
        val guesser = SubtitleCharsetGuesser()
        assertNull(guesser.convert(cue("第一句：字幕编码测试", Charsets.UTF_8)))
        assertNull(guesser.convert(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "1".toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun `gbk subtitles become utf-8`() {
        val guesser = SubtitleCharsetGuesser()
        val text = "第四句：标点符号——「引号」、《书名号》！鲁迪乌斯·格雷拉特"
        assertEquals(String(cue(text, Charsets.UTF_8)), guesser.convert(cue(text, Charset.forName("GBK"))))
    }

    @Test
    fun `big5 subtitles become utf-8`() {
        val guesser = SubtitleCharsetGuesser()
        val text = "第一句：字幕編碼測試，看得清楚就對了。"
        assertEquals(String(cue(text, Charsets.UTF_8)), guesser.convert(cue(text, Charset.forName("Big5"))))
    }

    @Test
    fun `big5 is recognized from the whole track`() {
        // 第一条的几个字低位都在 0xA1 以上, 单看它会当成 GBK (解成「材辊代刚」); 后面的字幕攒够证据后都按 Big5
        val guesser = SubtitleCharsetGuesser()
        val big5 = Charset.forName("Big5")
        guesser.convert(cue("第幕測試", big5))
        val text = "第二句：繁體中文 Big5 編碼。最後一句，結束。"
        assertEquals(String(cue(text, Charsets.UTF_8)), guesser.convert(cue(text, big5)))
        assertEquals(String(cue("第幕測試", Charsets.UTF_8)), guesser.convert(cue("第幕測試", big5)))
    }
}
