/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import io.ktor.http.decodeURLPart
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import me.him188.ani.datasources.api.MediaExtraFiles
import me.him188.ani.datasources.api.Subtitle
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.writeBytes
import me.him188.ani.utils.io.writeText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 种子里配给视频的外挂字幕: 按种子里的路径找同一个文件夹 (与字幕子文件夹) 里的, 规则同网盘; 下好的缓存按磁盘上视频旁边的文件找,
 * 没下完的字幕不挂.
 */
class TorrentSidecarSubtitlesTest {
    private val base = Path(SystemTemporaryDirectory, "izuko-torrent-sidecar-test-" + Random.nextLong().toULong())

    @AfterTest
    fun cleanup() {
        base.inSystem.takeIf { it.exists() }?.deleteRecursively()
    }

    private fun matched(video: String, files: List<String>) =
        TorrentSidecarSubtitles.match(video, files) { it }.map { Triple(it.file, it.label, it.language) }

    @Test
    fun `a bdrip keeps the chinese subtitles next to the video with the same name`() {
        val dir = "[Group&VCB-Studio] Title [1080p]"
        val files = listOf(
            "$dir/[Group&VCB-Studio] Title [01][Hi10p_1080p][x264_2flac].mkv",
            "$dir/[Group&VCB-Studio] Title [01][Hi10p_1080p][x264_2flac].tc.ass",
            "$dir/[Group&VCB-Studio] Title [01][Hi10p_1080p][x264_2flac].sc.ass",
            "$dir/[Group&VCB-Studio] Title [02][Hi10p_1080p][x264_flac].mkv",
            "$dir/[Group&VCB-Studio] Title [02][Hi10p_1080p][x264_flac].sc.ass",
            "$dir/[Group&VCB-Studio] Title [fonts].rar",
            "$dir/CDs/OP/track.flac",
        )
        assertEquals(
            listOf(
                Triple("$dir/[Group&VCB-Studio] Title [01][Hi10p_1080p][x264_2flac].sc.ass", "简体中文", "zh-Hans"),
                Triple("$dir/[Group&VCB-Studio] Title [01][Hi10p_1080p][x264_2flac].tc.ass", "繁体中文", "zh-Hant"),
            ),
            matched(files[0], files),
        )
    }

    @Test
    fun `subtitles in a subtitle folder next to the video count but other folders do not`() {
        val files = listOf(
            "Show/Show 01.mkv",
            "Show/Subs/Show 01.chs&jpn.ass",
            "Show/SPs/Show 01.sc.ass",
            "Other/Show 01.sc.ass",
        )
        assertEquals(listOf(Triple("Show/Subs/Show 01.chs&jpn.ass", "简日双语", "zh-Hans")), matched(files[0], files))
    }

    @Test
    fun `a single file torrent at the root works too`() {
        val files = listOf("Movie.mkv", "Movie.srt")
        assertEquals(listOf(Triple("Movie.srt", "外挂字幕", null)), matched(files[0], files))
    }

    @Test
    fun `a finished cache gets the downloaded subtitles next to its file but not unfinished ones`() {
        val dir = Path(base, "[G&V] Title [1080p]")
        Path(dir, "Subs").inSystem.createDirectories()
        val video = Path(dir, "Title [01].mkv").inSystem.also { it.writeBytes(ByteArray(64) { 1 }) }
        Path(dir, "Title [01].sc.ass").inSystem.writeText("[Script Info]\n简体")
        // 没下完: 没下到的地方是 0
        Path(dir, "Title [01].tc.ass").inSystem.writeBytes("[Script Info]".encodeToByteArray() + ByteArray(64))
        Path(dir, "Subs/Title [01].jpn.ass").inSystem.writeText("[Script Info]\n日本語")
        Path(dir, "Title [02].sc.ass").inSystem.writeText("[Script Info]\n第二集")
        val origin = MediaExtraFiles(listOf(Subtitle("https://example.com/origin.ass")))

        val attached = TorrentSidecarSubtitles.onDisk(video, origin).subtitles

        assertEquals(listOf("简体中文", "日文", null), attached.map { it.label })
        assertEquals(listOf("zh-Hans", "ja", null), attached.map { it.language })
        assertEquals("[Script Info]\n简体", Path(attached[0].uri.removePrefix("file://").decodeURLPart()).inSystem.readText())
    }

    @Test
    fun `a cache without subtitles next to it keeps the original extra files`() {
        val video = Path(base, "Movie.mkv").inSystem.also {
            base.inSystem.createDirectories()
            it.writeBytes(ByteArray(8))
        }
        val origin = MediaExtraFiles(listOf(Subtitle("https://example.com/origin.ass")))
        assertSame(origin, TorrentSidecarSubtitles.onDisk(video, origin))
    }

    @Test
    fun `the file address escapes what a torrent file name may contain`() {
        assertEquals(
            "file:///storage/A1/%5BG%26V%5D%20Title%20%231/a%3Fb.sc.ass",
            TorrentSidecarSubtitles.fileUriOf("/storage/A1/[G&V] Title #1/a?b.sc.ass"),
        )
    }
}
