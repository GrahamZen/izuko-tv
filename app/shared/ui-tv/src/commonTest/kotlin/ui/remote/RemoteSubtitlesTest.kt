/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteSubtitlesTest {
    private val dir = createTempDirectory("izuko-subs").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `srt becomes webvtt`() {
        val vtt = RemoteSubtitles.toWebVtt("1\r\n00:00:01,500 --> 0:00:03,25\r\n<font color=\"#fff\">你好</font>\r\n<i>世界</i>\r\n", "srt")
        assertEquals("WEBVTT\n\n1\n00:00:01.500 --> 00:00:03.250\n你好\n<i>世界</i>\n\n", vtt)
    }

    @Test
    fun `ass becomes plain webvtt cues`() {
        val ass = """
            [Script Info]
            Title: test

            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.10,0:00:07.00,Default,,0,0,0,,{\fad(200,200)\pos(10,10)}第二句, 带逗号\N换行
            Dialogue: 1,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\bord3}第一句 <强调> & 符号
            Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\bord3}第一句 <强调> & 符号
            Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100{\p0}
            Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,注释不要
        """.trimIndent()
        assertEquals(
            "WEBVTT\n\n" +
                "00:00:01.000 --> 00:00:02.500\n第一句 &lt;强调&gt; &amp; 符号\n\n" +
                "00:00:05.100 --> 00:00:07.000\n第二句, 带逗号\n换行\n\n",
            RemoteSubtitles.toWebVtt(ass, "ass"),
        )
    }

    @Test
    fun `only local subtitle files are handed to the phone`() {
        val file = File(dir, "0.ass").apply { writeText("x") }
        val data = UriMediaData(
            "https://drive/v.mkv",
            emptyMap(),
            MediaExtraFiles(
                listOf(
                    Subtitle(uri = "https://drive/sc.ass", mimeType = null, language = null, label = "网上的"),
                    Subtitle(uri = "file://" + file.path, mimeType = null, language = "zh-Hans", label = "简体"),
                    Subtitle(uri = "file://" + File(dir, "gone.srt").path, mimeType = null, language = null, label = null),
                ),
            ),
        )
        val subs = RemoteSubtitles.localOf(data)
        assertEquals(1, subs.size)
        // 序号是它在全部外挂字幕里的位置 (同播放器里外挂字幕轨的顺序)
        assertEquals(1, subs[0].index)
        assertEquals("ass", subs[0].ext)
        assertEquals("1.vtt", RemoteSubtitles.vttNameOf(subs[0]))
        assertEquals(1, RemoteSubtitles.parseVttName("1.vtt"))
        assertNull(RemoteSubtitles.parseVttName("1.ass"))
        assertNull(RemoteSubtitles.parseVttName("x.vtt"))
    }

    @OptIn(InternalMediampApi::class)
    @Test
    fun `the tv selection decides which subtitle goes along`() {
        val a = RemoteSubtitles.Local(0, File(dir, "0.ass"), null, null)
        val b = RemoteSubtitles.Local(1, File(dir, "1.srt"), null, null)
        // mediamp 的轨道: id = "<TrackGroup id>-<轨道序号>", internalId = TrackGroup id; 第 i 条外挂字幕是 media3 的第 i + 1 个子源
        fun track(group: String) = SubtitleTrack("$group-0", group, null, emptyList())
        assertEquals(a, RemoteSubtitles.selectedOf(listOf(a, b), track("1:0"), known = true))
        assertEquals(b, RemoteSubtitles.selectedOf(listOf(a, b), track("2:0"), known = true))
        // 选的是封在视频里的 / 关着字幕: 不带
        assertNull(RemoteSubtitles.selectedOf(listOf(a, b), track("0:2"), known = true))
        // 只有一部分外挂字幕是本机文件时按原序号对 (b 之前那条网上的字幕没发给手机)
        assertNull(RemoteSubtitles.selectedOf(listOf(b), track("1:0"), known = true))
        assertNull(RemoteSubtitles.selectedOf(listOf(a, b), null, known = true))
        // 播放器还没报: 给第一条
        assertEquals(a, RemoteSubtitles.selectedOf(listOf(a, b), null, known = false))
    }

    @Test
    fun `gbk subtitles go out as utf-8 webvtt`() {
        val gbk = Charset.forName("GB18030")
        val file = File(dir, "0.srt").apply { writeBytes("1\n00:00:01,000 --> 00:00:02,000\n简体中文字幕\n".toByteArray(gbk)) }
        val sub = RemoteSubtitles.Local(0, file, null, null)
        val vtt = ByteArrayOutputStream().also { RemoteSubtitles.serveVtt(sub).writeBody(it) }.toByteArray().toString(Charsets.UTF_8)
        assertTrue(vtt.startsWith("WEBVTT") && "00:00:01.000 --> 00:00:02.000\n简体中文字幕" in vtt, vtt)
    }
}
