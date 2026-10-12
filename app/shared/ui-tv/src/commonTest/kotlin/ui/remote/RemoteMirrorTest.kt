/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.player.data.AniSystemFileMediaData
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SystemFileMediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemoteMirrorTest {
    private fun unavailable(data: MediaData?): MirrorVerdict.Unavailable = assertIs(judgeMirror(data))
    private fun playable(data: MediaData): MirrorVerdict.Playable = assertIs(judgeMirror(data))

    @Test
    fun `no media yet asks the phone to come back later`() {
        val v = unavailable(null)
        assertEquals(MirrorVerdict.REASON_NOT_PLAYING, v.reason)
        assertTrue(v.pending)
    }

    @Test
    fun `local files and loopback proxies stay on the tv`() {
        assertEquals(MirrorVerdict.REASON_LOCAL, unavailable(UriMediaData("file:///sdcard/a.mp4")).reason)
        assertEquals(MirrorVerdict.REASON_LOCAL, unavailable(UriMediaData("content://media/1")).reason)
        // HLS 去广告这类本机代理: 手机连的是它自己的 127.0.0.1
        for (url in listOf("http://127.0.0.1:41234/hls/index.m3u8", "http://localhost:8080/a.m3u8", "http://[::1]:9000/a.mp4")) {
            assertEquals(MirrorVerdict.REASON_LOCAL_PROXY, unavailable(UriMediaData(url)).reason, url)
        }
    }

    @OptIn(ExperimentalMediampApi::class)
    @Test
    fun `finished local files are served by the tv itself`() {
        // mediamp 造 SystemFileMediaData 时要解析路径, 文件得真的在
        val dir = createTempDirectory("izuko-mirror").toFile().apply { deleteOnExit() }
        val file = File(dir, "孤独摇滚 01.mkv").apply { writeBytes(ByteArray(16)); deleteOnExit() }
        val data = AniSystemFileMediaData(SystemFileMediaData(Path(file.path), MediaExtraFiles.EMPTY, emptyList()))
        val v = playable(data)
        assertTrue(v.local)
        assertEquals("mkv", v.kind)
        assertEquals(mirrorKeyOf(data), v.key)
        // 只发正在播的那一个: 地址里带着 key, 文件名只给对方当标题
        assertEquals("${RemoteMirror.FILE_PATH}/${v.key}/%E5%AD%A4%E7%8B%AC%E6%91%87%E6%BB%9A%2001.mkv", v.url)
        assertEquals(file.canonicalFile, localFileOf(data)?.canonicalFile)
        file.delete()
        dir.delete()
    }

    @Test
    fun `credentials a browser cannot send rule the source out`() {
        val drive = UriMediaData("https://drive.example.com/file?fid=1", mapOf("cookie" to "a=b", "User-Agent" to "x"))
        assertEquals(MirrorVerdict.REASON_CREDENTIALS, unavailable(drive).reason)
        val token = UriMediaData("https://api.example.com/v.mp4", mapOf("Authorization" to "Bearer t"))
        assertEquals(MirrorVerdict.REASON_CREDENTIALS, unavailable(token).reason)
    }

    @Test
    fun `everything else is left for the phone to try`() {
        // Referer / UA 只是记下名字: 浏览器带不了, 但不少 CDN 认空 Referer, 得手机真去加载才知道
        val web = playable(UriMediaData("https://cdn.example.com/v/index.m3u8", mapOf("User-Agent" to "x", "Referer" to "https://site/")))
        assertEquals("hls", web.kind)
        assertEquals("cdn.example.com", web.host)
        assertEquals(listOf("Referer", "User-Agent"), web.headerNames)
        assertEquals("mp4", playable(UriMediaData("https://cdn.example.com/a.MP4?token=1")).kind)
        assertEquals("mkv", playable(UriMediaData("https://cdn.example.com/a.mkv")).kind)
        assertEquals("other", playable(UriMediaData("https://cdn.example.com/play?id=3")).kind)
        // 局域网里的服务器 (如 Jellyfin) 不是本机代理
        assertEquals("10.0.0.5", playable(UriMediaData("http://10.0.0.5:8096/Videos/1/stream.mp4?api_key=k")).host)
    }

    @Test
    fun `key follows the url`() {
        val a = playable(UriMediaData("https://cdn.example.com/a.m3u8"))
        val b = playable(UriMediaData("https://cdn.example.com/b.m3u8"))
        assertTrue(a.key != b.key)
        assertEquals(a.key, playable(UriMediaData("https://cdn.example.com/a.m3u8")).key)
        // 播放状态里带的 mediaKey 与接口回的 key 是同一个算法: 手机靠两者相等认出「全屏里放的还是电视在播的那份」
        assertEquals(a.key, mirrorKeyOf(UriMediaData("https://cdn.example.com/a.m3u8")))
        assertEquals("", mirrorKeyOf(null))
        val drive = UriMediaData("https://drive.example.com/f", mapOf("Cookie" to "a=b"))
        assertEquals(mirrorKeyOf(drive), unavailable(drive).key)
    }

    @Test
    fun `page wires the mirror into the fullscreen button`() {
        val page = renderRemoteControlPage(initialTab = "player", searchFormHtml = "", requestSectionHtml = "")
        assertContains(page, MIRROR_SCRIPT)
        assertContains(page, "if (window.mirrorEnterFullscreen && window.mirrorEnterFullscreen()) return;")
        assertContains(page, "if (window.mirrorOnPlayback) window.mirrorOnPlayback(pb);")
        assertContains(page, "id=\"pb-mirror-note\"")
        // 本机文件的外挂字幕挂成字幕轨; 浏览器播不了这个格式 (如 iPhone 上的 mkv) 时直接说不支持
        assertContains(MIRROR_SCRIPT, "t.src = s.vtt;")
        assertContains(MIRROR_SCRIPT, "T('手机不支持播放这个格式（{0}）', info.kind)")
        // 苹果的浏览器不播 mkv 又不一定报错: 不去试, 直接说; 本机文件迟迟加载不出来也算不支持
        assertContains(MIRROR_SCRIPT, "if (APPLE_WEBKIT && APPLE_UNPLAYABLE.indexOf(r.kind) >= 0) {")
        assertContains(MIRROR_SCRIPT, "}, LOCAL_LOAD_TIMEOUT_MS);")
        assertContains(MIRROR_SCRIPT, "window.fetchT('${RemoteMirror.PATH}')")
        // 换集时位置先归零: 先认 mediaKey 退出, 再对表
        assertContains(MIRROR_SCRIPT, "if (presentedKey && (p && p.mediaKey) !== presentedKey) {")
    }

    @Test
    fun `mirror texts are translated`() {
        val keys = REMOTE_I18N_TABLE.map { it.zh }.toSet()
        val texts = Regex("""(?<![\w.])T\('([^']+)'""").findAll(MIRROR_SCRIPT).map { it.groupValues[1] }.toList() +
            listOf(
                "全屏（同步播放）",
                MirrorVerdict.REASON_NOT_PLAYING,
                MirrorVerdict.REASON_LOCAL,
                MirrorVerdict.REASON_LOCAL_PROXY,
                MirrorVerdict.REASON_CREDENTIALS,
            )
        assertTrue(texts.size > 10)
        for (t in texts) assertTrue(t in keys, "没有译文：$t")
        // 本功能的原文不与表里已有的重复 (重复时后登记的那份译文会盖掉先前的)
        val counts = REMOTE_I18N_TABLE.groupingBy { it.zh }.eachCount()
        for (t in mirrorTexts()) assertEquals(1, counts[t.zh], "重复的原文：${t.zh}")
    }
}
