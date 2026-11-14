/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media

import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceKind.WEB
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.ResourceLocation
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExternalLinkMediaTest {
    private val page = ResourceLocation.WebVideo("https://site.example/play/1")
    private val fallBack = ExternalLinkMedia.Origin("test-fall-back", ExternalLinkMedia.OnError.FALL_BACK)
    private val keep = ExternalLinkMedia.Origin("test-keep", ExternalLinkMedia.OnError.KEEP)

    private fun media(download: ResourceLocation, kind: MediaSourceKind = WEB): Media = DefaultMedia(
        mediaId = "m:${download.uri}", mediaSourceId = "site", originalUrl = download.uri, download = download,
        originalTitle = "第 1 集", publishedTime = 0,
        properties = MediaProperties(
            subjectName = null, episodeName = null, subtitleLanguageIds = emptyList(), resolution = "", alliance = "",
            size = FileSize.Unspecified, subtitleKind = null,
        ),
        episodeRange = null, location = MediaSourceLocation.Online, kind = kind,
    )

    private fun data(url: String, headers: Map<String, String> = emptyMap()) = UriMediaData(url, headers, MediaExtraFiles.EMPTY)

    // ---- 本机正在播的地址能不能交出去 ----

    @Test
    fun `a link resolved from a web page is handed over with its headers`() {
        val headers = mapOf("Referer" to "https://site.example/", "User-Agent" to "UA", "X-Empty" to " ")
        val link = assertNotNull(ExternalLinkMedia.shareableOf(media(page), data("https://cdn.example/v/1.m3u8?sign=abc", headers)))
        assertEquals("https://cdn.example/v/1.m3u8?sign=abc", link.url)
        assertEquals(mapOf("Referer" to "https://site.example/", "User-Agent" to "UA"), link.headers)
        assertEquals("第 1 集", link.title)
    }

    @Test
    fun `links bound to an account or to this device are not handed over`() {
        val ok = data("https://cdn.example/v/1.m3u8")
        // 网盘、媒体服务器给的是直接的地址 (不是网页解析出来的), BT, 本地文件
        assertNull(ExternalLinkMedia.shareableOf(media(ResourceLocation.HttpStreamingFile("https://drive.example/f/1")), ok))
        assertNull(ExternalLinkMedia.shareableOf(media(ResourceLocation.MagnetLink("magnet:?xt=urn:btih:1"), MediaSourceKind.BitTorrent), ok))
        assertNull(ExternalLinkMedia.shareableOf(media(ResourceLocation.LocalFile("/sdcard/1.mkv")), ok))
        // 没装进播放器
        assertNull(ExternalLinkMedia.shareableOf(media(page), null))
        // 本机代理、局域网
        assertNull(ExternalLinkMedia.shareableOf(media(page), data("http://127.0.0.1:8080/hls/1.m3u8")))
        assertNull(ExternalLinkMedia.shareableOf(media(page), data("http://192.168.1.20/v/1.mp4")))
        // 带登录信息
        assertNull(ExternalLinkMedia.shareableOf(media(page), data("https://cdn.example/v/1.m3u8", mapOf("cookie" to "sid=1"))))
        assertNull(ExternalLinkMedia.shareableOf(media(page), data("https://cdn.example/v/1.m3u8", mapOf("Authorization" to "Bearer x"))))
        // 太长 (房间消息有大小上限)
        assertNull(ExternalLinkMedia.shareableOf(media(page), data("https://cdn.example/v/1.m3u8?sign=" + "a".repeat(2_000))))
    }

    @Test
    fun `public http urls are told by the literal host`() {
        for (url in listOf(
            "https://cdn.example/v.m3u8", "http://cdn.example:8080/v.m3u8?x=1", "https://8.8.8.8/v", "https://[2001:db8::1]/v", "https://172.32.0.1/v",
        )) assertTrue(isPublicHttpUrl(url), url)
        for (url in listOf(
            "ftp://cdn.example/v", "file:///sdcard/v.mkv", "cdn.example/v", "https:///v",
            "http://localhost:8080/v", "http://127.0.0.1/v", "http://10.0.0.5/v", "http://192.168.1.2:8096/v", "http://172.16.0.1/v", "http://169.254.1.1/v",
            "http://nas.local/v", "http://[::1]/v", "http://[fe80::1]/v", "http://[fd00::1]/v", "https://user:pass@cdn.example/v",
        )) assertFalse(isPublicHttpUrl(url), url)
    }

    @Test
    fun `a link handed over by others is accepted only if it is public and carries no credentials`() {
        assertTrue(ExternalLinkMedia.accepts(ExternalLink("https://cdn.example/v/1.m3u8", mapOf("Referer" to "https://site.example/"))))
        // 不替别人去取本机、局域网里的东西
        assertFalse(ExternalLinkMedia.accepts(ExternalLink("http://192.168.1.1/admin")))
        assertFalse(ExternalLinkMedia.accepts(ExternalLink("http://localhost:41893/api/player")))
        assertFalse(ExternalLinkMedia.accepts(ExternalLink("file:///sdcard/1.mkv")))
        assertFalse(ExternalLinkMedia.accepts(ExternalLink("https://cdn.example/v/1.m3u8", mapOf("Cookie" to "sid=1"))))
        assertFalse(ExternalLinkMedia.accepts(ExternalLink("https://cdn.example/v/1.m3u8", (1..9).associate { "X-$it" to "v" })))
    }

    // ---- 别处交来的地址 ----

    @Test
    fun `a handed-over link becomes a media that carries its headers and origin`() {
        val link = ExternalLink("https://cdn.example/v/2.m3u8", mapOf("Referer" to "https://site.example/", "X-Empty" to ""), "第 2 集")
        val media = ExternalLinkMedia.create(fallBack, link)
        assertTrue(ExternalLinkMedia.isExternalLink(media))
        assertEquals("test-fall-back", ExternalLinkMedia.originIdOf(media))
        assertEquals(mapOf("Referer" to "https://site.example/"), ExternalLinkMedia.headersOf(media))
        assertEquals(ResourceLocation.HttpStreamingFile("https://cdn.example/v/2.m3u8"), media.download)
        assertEquals("第 2 集", media.originalTitle)
        assertEquals(WEB, media.kind)

        // 没给标题时用地址里的文件名
        assertEquals("3.m3u8", ExternalLinkMedia.create(fallBack, ExternalLink("https://cdn.example/v/3.m3u8?sign=1")).originalTitle)
        // 本机数据源的资源不是
        assertFalse(ExternalLinkMedia.isExternalLink(media(page)))
        assertNull(ExternalLinkMedia.originIdOf(media(page)))
        assertEquals(emptyMap(), ExternalLinkMedia.headersOf(media(page)))
    }

    @Test
    fun `a handed-over link can be handed on`() {
        val link = ExternalLink("https://cdn.example/v/4.m3u8", mapOf("Referer" to "https://site.example/"), "第 4 集")
        val media = ExternalLinkMedia.create(fallBack, link)
        assertEquals(link, ExternalLinkMedia.shareableOf(media, data(link.url, ExternalLinkMedia.headersOf(media))))
    }

    // ---- 播放出错 ----

    @Test
    fun `a failed link falls back to the media it replaced`() = runFetchMediaSelectorTestSuite {
        // 选源器要先有查询会话才建得出来
        initSubject("Example Series")
        configureFetchSession {
            object {
                val site by web()
            }
        }
        val own = media(kind = WEB, alliance = "自己选的")
        assertNotNull(selector.selectAutomatically(own, expectedSelection = null))
        val link = ExternalLinkMedia.create(fallBack, ExternalLink("https://cdn.example/v/5.m3u8"), replaced = own)
        assertNotNull(selector.selectAutomatically(link, expectedSelection = own))

        var asUsual = 0
        ExternalLinkMedia.recoverFromError(link, selector) { asUsual++ }
        assertSame(own, selector.selected.value)
        assertEquals(0, asUsual)
        // 出错的站点记下了
        assertTrue(ExternalLinkMedia.failedHereRecently(ExternalLink("https://cdn.example/v/5b.m3u8")))
    }

    @Test
    fun `a site whose link failed here is not tried again for a while`() {
        val failedAt = 1_000_000L
        ExternalLinkMedia.rememberFailure("https://failing.example/v/1/playlist.m3u8?sign=1", failedAt)
        // 同一个站点的别的地址 (下一集的), 主机名不分大小写、不看端口
        assertTrue(ExternalLinkMedia.failedHereRecently(ExternalLink("https://FAILING.example:443/v/2/playlist.m3u8"), nowMillis = failedAt + 29 * 60_000))
        assertFalse(ExternalLinkMedia.failedHereRecently(ExternalLink("https://other-site.example/v/2/playlist.m3u8"), nowMillis = failedAt))
        // 过了一阵再试
        assertFalse(ExternalLinkMedia.failedHereRecently(ExternalLink("https://failing.example/v/3/playlist.m3u8"), nowMillis = failedAt + 31 * 60_000))
    }

    @Test
    fun `a failed link that replaced nothing clears the selection and selects as usual`() = runFetchMediaSelectorTestSuite {
        // 选源器要先有查询会话才建得出来
        initSubject("Example Series")
        configureFetchSession {
            object {
                val site by web()
            }
        }
        val link = ExternalLinkMedia.create(fallBack, ExternalLink("https://cdn.example/v/6.m3u8"))
        assertNotNull(selector.selectAutomatically(link, expectedSelection = null))

        var asUsual = 0
        ExternalLinkMedia.recoverFromError(link, selector) { asUsual++ }
        assertNull(selector.selected.value)
        assertEquals(1, asUsual)
    }

    @Test
    fun `a failed link of an origin that keeps it stays selected`() = runFetchMediaSelectorTestSuite {
        // 选源器要先有查询会话才建得出来
        initSubject("Example Series")
        configureFetchSession {
            object {
                val site by web()
            }
        }
        val link = ExternalLinkMedia.create(keep, ExternalLink("https://cdn.example/v/7.m3u8"))
        assertNotNull(selector.selectAutomatically(link, expectedSelection = null))

        var asUsual = 0
        ExternalLinkMedia.recoverFromError(link, selector) { asUsual++ }
        assertSame(link, selector.selected.value)
        assertEquals(0, asUsual)
    }

    @Test
    fun `a failed link is left alone once the selection has moved on`() = runFetchMediaSelectorTestSuite {
        // 选源器要先有查询会话才建得出来
        initSubject("Example Series")
        configureFetchSession {
            object {
                val site by web()
            }
        }
        val link = ExternalLinkMedia.create(fallBack, ExternalLink("https://cdn.example/v/8.m3u8"))
        assertNotNull(selector.selectAutomatically(link, expectedSelection = null))
        val chosen = media(kind = WEB, alliance = "用户选的")
        assertNotNull(selector.selectAutomatically(chosen, expectedSelection = link))

        var asUsual = 0
        ExternalLinkMedia.recoverFromError(link, selector) { asUsual++ }
        assertSame(chosen, selector.selected.value)
        assertEquals(0, asUsual)
    }
}
