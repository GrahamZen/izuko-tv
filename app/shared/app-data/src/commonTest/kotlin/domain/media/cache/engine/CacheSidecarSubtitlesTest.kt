/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import me.him188.ani.datasources.api.MediaExtraFiles
import me.him188.ani.datasources.api.Subtitle
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.readText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.openani.mediamp.source.Subtitle as MediampSubtitle

class CacheSidecarSubtitlesTest {
    private val base = Path(SystemTemporaryDirectory, "izuko-sidecar-test-" + Random.nextLong().toULong())
    private val saveDir = Path(base, "web-cache")
    private val fetched = mutableListOf<Pair<String, Map<String, String>>>()
    private val subs = CacheSidecarSubtitles(saveDir) { url, headers ->
        fetched += url to headers
        if ("broken" in url) error("HTTP 403")
        "content of $url".encodeToByteArray()
    }

    @AfterTest
    fun cleanup() {
        base.inSystem.takeIf { it.exists() }?.deleteRecursively()
    }

    private fun localFile(uri: String) = Path(uri.removePrefix("file://")).inSystem

    @Test
    fun `saved subtitles are attached in front of the original ones`() = runTest {
        val cookie = mapOf("Cookie" to "a=b")
        subs.save(
            "http-v2-1",
            listOf(
                MediampSubtitle("https://drive/sc.ass?token=1", "text/x-ssa", "zh-Hans", "简体"),
                MediampSubtitle("https://drive/broken.srt", "application/x-subrip", "zh-Hant", "繁体"),
                MediampSubtitle("https://drive/download?fid=3", "application/x-subrip", null, null),
            ),
            cookie,
        )
        // 用视频那套请求头去下 (网盘要 Cookie)
        assertEquals(listOf(cookie, cookie, cookie), fetched.map { it.second })

        val origin = MediaExtraFiles(listOf(Subtitle("https://remote/x.vtt")))
        val files = subs.extraFilesOf("http-v2-1", origin).subtitles
        // 下坏的那条不留; 本地的排在原有的前面
        assertEquals(3, files.size)
        assertEquals(listOf("简体", null, null), files.map { it.label })
        assertEquals("text/x-ssa", files[0].mimeType)
        assertEquals("zh-Hans", files[0].language)
        assertTrue(files[0].uri.startsWith("file://") && files[0].uri.endsWith("0.ass"), files[0].uri)
        // 地址里看不出扩展名时按类型给
        assertTrue(files[1].uri.endsWith("2.srt"), files[1].uri)
        assertEquals("content of https://drive/sc.ass?token=1", localFile(files[0].uri).readText())
        assertEquals("https://remote/x.vtt", files[2].uri)
        // 字幕不进缓存目录 (那里的无主文件会被清掉)
        assertFalse(files[0].uri.contains("web-cache"), files[0].uri)
    }

    @Test
    fun `nothing saved keeps the original extra files`() = runTest {
        val origin = MediaExtraFiles(listOf(Subtitle("https://remote/x.vtt")))
        assertSame(origin, subs.extraFilesOf("http-v2-none", origin))
        subs.save("http-v2-bad", listOf(MediampSubtitle("https://drive/broken.ass", null, null, null)), emptyMap())
        assertSame(origin, subs.extraFilesOf("http-v2-bad", origin))
    }

    @Test
    fun `delete and prune remove the subtitle folders`() = runTest {
        val one = listOf(MediampSubtitle("https://drive/a.ass", null, null, null))
        subs.save("keep", one, emptyMap())
        subs.save("gone", one, emptyMap())
        subs.save("deleted", one, emptyMap())
        val keepFile = localFile(subs.extraFilesOf("keep", MediaExtraFiles.EMPTY).subtitles.single().uri)
        val goneFile = localFile(subs.extraFilesOf("gone", MediaExtraFiles.EMPTY).subtitles.single().uri)

        subs.delete("deleted")
        assertTrue(subs.extraFilesOf("deleted", MediaExtraFiles.EMPTY).subtitles.isEmpty())

        subs.prune(setOf("keep"))
        assertTrue(keepFile.exists())
        assertFalse(goneFile.exists())
    }
}
