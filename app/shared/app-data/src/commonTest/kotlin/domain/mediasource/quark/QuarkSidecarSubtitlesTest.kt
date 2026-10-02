/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 网盘里视频旁边的外挂字幕: 配给哪个视频、菜单里叫什么、谁排前面.
 */
class QuarkSidecarSubtitlesTest {
    private fun isVideo(name: String) = name.substringAfterLast('.') in setOf("mkv", "mp4")

    private fun match(video: String, files: List<String>, byEpisode: Boolean = true) =
        QuarkSidecarSubtitles.match(video, files, { it }, ::isVideo, byEpisode)

    @Test
    fun `same name subtitles with language tags, simplified first`() {
        val video = "[VCB-Studio] Bocchi the Rock! [01][Ma10p_1080p][x265_flac].mkv"
        val files = listOf(
            video,
            "[VCB-Studio] Bocchi the Rock! [01][Ma10p_1080p][x265_flac].tc.ass",
            "[VCB-Studio] Bocchi the Rock! [01][Ma10p_1080p][x265_flac].sc.ass",
            "[VCB-Studio] Bocchi the Rock! [02][Ma10p_1080p][x265_flac].mkv",
            "[VCB-Studio] Bocchi the Rock! [02][Ma10p_1080p][x265_flac].sc.ass",
        )
        val matches = match(video, files)
        assertEquals(listOf("简体中文", "繁体中文"), matches.map { it.label })
        assertEquals(listOf("zh-Hans", "zh-Hant"), matches.map { it.language })
        assertEquals(listOf("text/x-ssa", "text/x-ssa"), matches.map { it.mimeType })
        assertTrue(matches.first().file.endsWith("[01][Ma10p_1080p][x265_flac].sc.ass"))
    }

    @Test
    fun `labels from common tags`() {
        val video = "Frieren.S01E01.mkv"
        fun labelOf(tag: String) = match(video, listOf(video, "Frieren.S01E01.$tag")).single().label
        assertEquals("简日双语", labelOf("chs&jpn.ass"))
        assertEquals("简日双语", labelOf("JPSC.ass"))
        assertEquals("繁日双语", labelOf("cht_jp.ass"))
        assertEquals("简体中文", labelOf("zh-Hans.srt"))
        assertEquals("繁体中文", labelOf("zh-TW.srt"))
        assertEquals("简日双语", labelOf("简日双语.ass"))
        assertEquals("日文", labelOf("ja.vtt"))
        assertEquals("英文", labelOf("eng.srt"))
        assertEquals("中文", labelOf("zh.srt"))
        assertEquals("外挂字幕", labelOf("ass"))
        // 认不出的短标记原样显示
        assertEquals("signs", labelOf("signs.ass"))
        assertEquals("application/x-subrip", match(video, listOf(video, "Frieren.S01E01.srt")).single().mimeType)
    }

    @Test
    fun `same labels are numbered`() {
        val video = "01.mkv"
        val matches = QuarkSidecarSubtitles.numbered(match(video, listOf(video, "01.sc.ass", "01.chs.srt")))
        assertEquals(listOf("简体中文", "简体中文 2"), matches.map { it.label })
    }

    @Test
    fun `picked subtitles are named by language tags or file name`() {
        assertEquals("简日双语", QuarkSidecarSubtitles.picked(Unit, "[Nekomoe] Bocchi [03][JPSC].ass")?.label)
        assertEquals("[MAI] Sora no Otoshimono", QuarkSidecarSubtitles.picked(Unit, "[MAI] Sora no Otoshimono [01][Ma10p_1080p].ass")?.label)
        assertEquals("text/x-ssa", QuarkSidecarSubtitles.picked(Unit, "a.ass")?.mimeType)
        assertEquals(null, QuarkSidecarSubtitles.picked(Unit, "a.mkv"))
    }

    @Test
    fun `subtitle goes to the longest matching video name`() {
        val files = listOf("Movie.mkv", "Movie.extended.mkv", "Movie.extended.sc.ass", "Movie.sc.ass")
        assertEquals(listOf("Movie.sc.ass"), match("Movie.mkv", files).map { it.file })
        assertEquals(listOf("Movie.extended.sc.ass"), match("Movie.extended.mkv", files).map { it.file })
    }

    @Test
    fun `different names are matched by episode`() {
        val video = "[VCB-Studio] Bocchi the Rock! [03][Ma10p_1080p][x265_flac].mkv"
        val files = listOf(
            "[VCB-Studio] Bocchi the Rock! [02][Ma10p_1080p][x265_flac].mkv",
            video,
            "[Nekomoe kissaten] Bocchi the Rock! [02][JPSC].ass",
            "[Nekomoe kissaten] Bocchi the Rock! [03][JPSC].ass",
            "[Nekomoe kissaten] Bocchi the Rock! [03][JPTC].ass",
        )
        val matches = match(video, files)
        assertEquals(
            listOf("[Nekomoe kissaten] Bocchi the Rock! [03][JPSC].ass", "[Nekomoe kissaten] Bocchi the Rock! [03][JPTC].ass"),
            matches.map { it.file },
        )
        assertEquals(listOf("简日双语", "繁日双语"), matches.map { it.label })
        // 转存文件夹里不按集号配
        assertTrue(match(video, files, byEpisode = false).isEmpty())
    }

    @Test
    fun `episode matching needs a single video of that episode`() {
        val files = listOf("孤独摇滚 01 1080p.mkv", "孤独摇滚 01 720p.mp4", "孤独摇滚 01.ass")
        assertTrue(match("孤独摇滚 01 1080p.mkv", files).isEmpty())
    }

    @Test
    fun `episode matching skips subtitles named after another video`() {
        val files = listOf("Title - 01.mkv", "Title - 01 [v2].mkv", "Title - 01 [v2].sc.ass")
        // 那条字幕按名字归了 v2; 而 v1 与 v2 是同一集, 也不按集号配
        assertTrue(match("Title - 01.mkv", files).isEmpty())
    }

    @Test
    fun `title characters are not mistaken for language tags`() {
        val video = "[Group] 日常 - 05 [1080p].mkv"
        val matches = match(video, listOf(video, "[Group] 日常 - 05.ass"))
        assertEquals(listOf("外挂字幕"), matches.map { it.label })
    }

    @Test
    fun `subtitle files and folders`() {
        assertTrue(QuarkSidecarSubtitles.isSubtitle("a.ASS"))
        assertTrue(QuarkSidecarSubtitles.isSubtitle("a.sc.srt"))
        assertFalse(QuarkSidecarSubtitles.isSubtitle("a.nfo"))
        assertFalse(QuarkSidecarSubtitles.isSubtitle("ass"))
        assertTrue(QuarkSidecarSubtitles.isSubtitleFolder("Subs"))
        assertTrue(QuarkSidecarSubtitles.isSubtitleFolder("外挂字幕"))
        assertTrue(QuarkSidecarSubtitles.isSubtitleFolder("字幕 (简繁)"))
        assertFalse(QuarkSidecarSubtitles.isSubtitleFolder("SPs"))
    }

    @Test
    fun `at most a few subtitles per video`() {
        val video = "01.mkv"
        val files = listOf(video) + (1..12).map { "01.part$it.ass" }
        assertEquals(QuarkSidecarSubtitles.MAX_PER_VIDEO, match(video, files).size)
    }
}
