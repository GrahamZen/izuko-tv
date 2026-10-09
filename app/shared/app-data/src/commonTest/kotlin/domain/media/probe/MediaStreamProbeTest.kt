/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 样本由 ffmpeg 生成: 1 帧的 1080p H.264 / 4K HEVC 取 SPS; 1 秒 720p TS 取前 24 个包, 前面拼一个最小的 PNG 冒充图片;
 * 2 秒 640x360 的 mp4, H.264 的 moov 在文件尾, HEVC 的 moov 在开头 (ffprobe: 13296 / 23948 bps).
 */
@OptIn(ExperimentalEncodingApi::class)
class MediaStreamProbeTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val startCode = byteArrayOf(0, 0, 1)

    @Test
    fun `h264 sps gives 1080p after cropping`() {
        val info = VideoSps.parseNals(startCode + hex("67640028acd940780227e5c044000003000400000300c83c60c658"), 0x1B)
        assertEquals(Triple(1920, 1080, "H.264"), info?.let { Triple(it.width, it.height, it.codec) })
    }

    @Test
    fun `hevc sps gives 4k`() {
        val sps = hex("420101016000000300900000030000030096a001e020021c596566924caf016808000003000800000300c840")
        assertEquals(Triple(3840, 2160, "HEVC"), VideoSps.parseNals(startCode + sps, 0x24)?.let { Triple(it.width, it.height, it.codec) })
        // 不知道 stream_type 时按 NAL 头认出 HEVC
        assertEquals("HEVC", VideoSps.parseNals(startCode + sps, null)?.codec)
    }

    @Test
    fun `ts behind a fake png header`() {
        val info = VideoSps.fromTs(Base64.decode(TS_WITH_PNG))
        assertEquals(Triple(1280, 720, "H.264"), info?.let { Triple(it.width, it.height, it.codec) })
    }

    @Test
    fun `mp4 with moov at the end`() = runTest {
        val result = MediaStreamProbe.probe(serve(mapOf("https://v.example.com/a.mp4" to Base64.decode(TAIL_MP4))), "https://v.example.com/a.mp4")
        assertEquals(MediaStreamProbe.Result("mp4", 640, 360, "H.264", kbps = 13, durationSeconds = 2), result)
    }

    @Test
    fun `mp4 with moov at the start`() = runTest {
        val result = MediaStreamProbe.probe(serve(mapOf("https://v.example.com/b.mp4" to Base64.decode(FAST_MP4))), "https://v.example.com/b.mp4")
        assertEquals(MediaStreamProbe.Result("mp4", 640, 360, "HEVC", kbps = 24, durationSeconds = 2), result)
    }

    @Test
    fun `hls media playlist samples segment sizes and reads sps`() = runTest {
        val segment = Base64.decode(TS_WITH_PNG)
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:4")
            repeat(10) { appendLine("#EXTINF:4.0,"); appendLine("seg$it.ts") }
            appendLine("#EXT-X-ENDLIST")
        }
        // 每片 4 秒 50000 字节 = 100 kbps; 分片内容都用同一段 (SPS 在开头)
        val files = mutableMapOf("https://v.example.com/hls/index.m3u8" to playlist.encodeToByteArray())
        repeat(10) { files["https://v.example.com/hls/seg$it.ts"] = segment + ByteArray(50000 - segment.size) }
        val result = MediaStreamProbe.probe(serve(files), "https://v.example.com/hls/index.m3u8")
        assertEquals(MediaStreamProbe.Result("hls", 1280, 720, "H.264", kbps = 100, durationSeconds = 40), result)
    }

    @Test
    fun `hls master playlist uses declared values`() = runTest {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,AVERAGE-BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L120.90,mp4a.40.2"
            high.m3u8
        """.trimIndent()
        val result = MediaStreamProbe.probe(serve(mapOf("https://v.example.com/m.m3u8" to master.encodeToByteArray())), "https://v.example.com/m.m3u8")
        assertEquals(MediaStreamProbe.Result("hls", 1920, 1080, "HEVC", kbps = 3000), result)
    }

    @Test
    fun `not a video`() = runTest {
        assertNull(MediaStreamProbe.probe(serve(mapOf("https://v.example.com/x" to "<html></html>".encodeToByteArray())), "https://v.example.com/x"))
    }

    /** 按 Range 回 206 (带 Content-Range), 没有 Range 回 200. */
    @Test
    fun `segments on an image host that refuses the page referer`() = runTest {
        val segment = Base64.decode(TS_WITH_PNG)
        val playlist = buildString {
            appendLine("#EXTM3U")
            repeat(10) { appendLine("#EXTINF:4.0,"); appendLine("https://img.example.com/$it.jpg") }
        }
        val files = mutableMapOf("https://v.example.com/p.m3u8" to playlist.encodeToByteArray())
        repeat(10) { files["https://img.example.com/$it.jpg"] = segment + ByteArray(50000 - segment.size) }
        val client = serve(files) { it.url.host == "img.example.com" && it.headers[HttpHeaders.Referrer] != null }
        val result = MediaStreamProbe.probe(client, "https://v.example.com/p.m3u8", mapOf(HttpHeaders.Referrer to "https://site.example.com/"))
        assertEquals(MediaStreamProbe.Result("hls", 1280, 720, "H.264", kbps = 100, durationSeconds = 40), result)
    }

    private fun serve(files: Map<String, ByteArray>, refuse: (HttpRequestData) -> Boolean = { false }) = HttpClient(
        MockEngine { request ->
            if (refuse(request)) return@MockEngine respondError(HttpStatusCode.Forbidden)
            val body = files[request.url.toString()] ?: return@MockEngine respondError(HttpStatusCode.NotFound)
            val range = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.split('-')
            if (range == null) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
            } else {
                val from = range[0].toInt()
                val to = minOf(range[1].toInt(), body.size - 1)
                respond(
                    body.copyOfRange(from, to + 1), HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentRange, "bytes $from-$to/${body.size}"),
                )
            }
        },
    )

    private companion object {
        const val TS_WITH_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAAAElFTkSuQmCCR0AREABC8CUAAcEAAP8B/wAB/IAUSBIBBkZGbXBlZwlTZXJ2aWNlMDF3fEPK" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "//////////////////////////////////////////////////////////////////////9HQAAQAACwDQABwQAAAAHwACqxBLL/////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "/////////////////////////////////////////////////////////////////////////////////0dQABAAArAXAAHBAADhAPAAG+EA8AAP4QHwAC9E" +
            "uZv/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////R0EAMAdQAAB7DH4AAAAB4AAAgMAK" +
            "MQAJEKERAAfYYQAAAAEJ8AAAAAFnZAAfrNlAUAW7ARAAAAMAEAAAAwMg8YMZYAAAAAFo6+PLIsAAAAEGBf//q9xF6b3m2Ui3lizYINkj7u94MjY0IC0gY29y" +
            "ZSAxNjUgcjMyMjMgMDQ4MGNiMCAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjUgLSBodHRwOi8vd3dHAQARdy52aWRlb2xh" +
            "bi5vcmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MSByZWY9MyBkZWJsb2NrPTE6MDowIGFuYWx5c2U9MHgzOjB4MTEzIG1lPWhleCBzdWJtZT03IHBz" +
            "eT0xIHBzeV9yZD0xLjAwOjAuMDAgbWl4ZWRfcmVmPTEgbWVfcmFuZ2U9MTYgY2hyb21hX21lPTEgdHJlbGxpcz0xIDh4OGRjdD0xIGNxbT0wIGRlYUcBABJk" +
            "em9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTIyIGxvb2thaGVhZF90aHJlYWRzPTMgc2xpY2VkX3RocmVhZHM9" +
            "MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MyBiX3B5cmFtaWQ9MiBi" +
            "X2FkRwEAE2FwdD0xIGJfYmlhcz0wIGRpcmVjdD0xIHdlaWdodGI9MSBvcGVuX2dvcD0wIHdlaWdodHA9MiBrZXlpbnQ9MjUwIGtleWludF9taW49MjUgc2Nl" +
            "bmVjdXQ9NDAgaW50cmFfcmVmcmVzaD0wIHJjX2xvb2thaGVhZD00MCByYz1jcmYgbWJ0cmVlPTEgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4" +
            "PTY5IHFwc3RlcD1HAQAUNCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAABZYiEADv//vdOvwKbVMIqA5JXCvbKpCZZuVJrAfKmAAADAAADAAADAAADAApb" +
            "aiiFafTJvTQAAAMAAAMBdQABVQACJgAE1AANQAAyQADiAAP4ABMgAIaAA+wAGKAA6wAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAA" +
            "AwAAAwAAAwAAAwAAAwAAAwAAA0cBADV+AP//////////////////////////////////////////////////////////////////////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////AAADAAADAAADAAADAAADAAADAAADAAADAAADAAAD" +
            "AAADAAADAAADAAADAAADAAADAAADAAADAANnR0EANnYA////////////////////////////////////////////////////////////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////AAAB4AAAgMAKMQAJgSERAAf0gQAAAAEJ8AAAAAFB" +
            "miRsQ7/+qZYAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAGDBHQQA3eRAAAIkcfgD/////////////////////////////////////////////////////////" +
            "//////////////////////////////////////////////////////////////////////////////////////////////8AAAHgAACAwAoxAAlI4REACRCh" +
            "AAAAAQnwAAAAAUGeQniF/wAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAccUdAABEAALANAAHBAAAAAfAAKrEEsv//////////////////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "////////////////////////////////////////////////////////////////////R1AAEQACsBcAAcEAAOEA8AAb4QDwAA/hAfAAL0S5m///////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "//////////////////////////////////////////////////////////////////////////////9HQQA4fgD/////////////////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "/////////////wAAAeAAAICABSEACSzBAAAAAQnwAAAAAQGeYXRCvwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAm4EdBADl5EAAAlyx+AP//////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "/////////////////wAAAeAAAIDACjEACWUBEQAJSOEAAAABCfAAAAABAZ5jakK/AAADAAADAAADAAADAAADAAADAAADAAADACbhR0EAOnAA////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "////////////////AAAB4AAAgMAKMQAJ8aERAAllAQAAAAEJ8AAAAAFBmmhJqEFomUwId//+qZYAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAGDFHQAASAACw" +
            "DQABwQAAAAHwACqxBLL/////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "/0dQABIAArAXAAHBAADhAPAAG+EA8AAP4QHwAC9EuZv/////////////////////////////////////////////////////////////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "////////////R0EAO3cQAAClPH4A////////////////////////////////////////////////////////////////////////////////////////////" +
            "/////////////////////////////////////////////////////////wAAAeAAAIDACjEACblhEQAJgSEAAAABCfAAAAABQZ6GRREsL/8AAAMAAAMAAAMA" +
            "AAMAAAMAAAMAAAMAAAMAHHFHQQA8fgD/////////////////////////////////////////////////////////////////////////////////////////" +
            "/////////////////////////////////////////////////////////////////////////////wAAAeAAAICABSEACZ1BAAAAAQnwAAAAAQGepXRCvwAA" +
            "AwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAm4UdBAD15EAAAs0x+AP//////////////////////////////////////////////////////////////////////" +
            "/////////////////////////////////////////////////////////////////////////////////wAAAeAAAIDACjEACdWBEQAJuWEAAAABCfAAAAAB" +
            "AZ6nakK/AAADAAADAAADAAADAAADAAADAAADAAADACbgR0AAEwAAsA0AAcEAAAAB8AAqsQSy////////////////////////////////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "//////////////////////////////////////////////////////9HUAATAAKwFwABwQAA4QDwABvhAPAAD+EB8AAvRLmb////////////////////////" +
            "////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////" +
            "/////////////////////////////////////////////////////////////////0dBAD5wAP//////////////////////////////////////////////" +
            "/////////////////////////////////////////////////////////////////////////////////////////////////////wAAAeAAAIDACjEAC2Ih" +
            "EQAJ1YEAAAABCfAAAAABQZqsSahBbJlMCHf//qmWAAADAAADAAADAAADAAADAAADAAADAAADABgw"

        const val TAIL_MP4 =
            "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAAB0JtZGF0AAACvQYF//+53EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NSBy" +
            "MzIyMyAwNDgwY2IwIC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyNSAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQu" +
            "aHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MzoweDExMyBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9" +
            "MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0" +
            "X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTExIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRl" +
            "PTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MyBiX3B5cmFtaWQ9MiBiX2FkYXB0PTEgYl9iaWFz" +
            "PTAgZGlyZWN0PTEgd2VpZ2h0Yj0xIG9wZW5fZ29wPTAgd2VpZ2h0cD0yIGtleWludD0yNTAga2V5aW50X21pbj0yNSBzY2VuZWN1dD00MCBpbnRyYV9yZWZy" +
            "ZXNoPTAgcmNfbG9va2FoZWFkPTQwIHJjPWFiciBtYnRyZWU9MSBiaXRyYXRlPTUwIHJhdGV0b2w9MS4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBx" +
            "cHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAAAU2WIhAAt//D5+BQKSMAGAAADAAADAAADAyWALVAMWAZEA3QAAAMAAAMAAAMAAAMAAAMAAAMA" +
            "AAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAWFAAAADUGaJGxC3wAAAwAAB6QAAAANQZ5CeIc/AAADAAAxYQAAAA0BnmF0Q98AAAMAADKgAAAADQGeY2pD" +
            "3wAAAwAAMqEAAAATQZpoSahBaJlMCFP/AAADAAAFtQAAAA9BnoZFESw1/wAAAwAALiEAAAANAZ6ldEPfAAADAAAyoQAAAA0BnqdqQ58AAAMAADFgAAAAFEGa" +
            "rEmoQWyZTAhD/wAAAwAAAwLuAAAAD0GeykUVLDH/AAADAAAsoQAAAA0Bnul0Q18AAAMAAC4gAAAADQGe62pDXwAAAwAALiAAAAAUQZrwSahBbJlMC/8AAAMA" +
            "AAMACdkAAAAOQZ8ORRUsZwAAAwAAH+EAAAANAZ8tdEJfAAADAAAmYQAAAA0Bny9qRX8AAAMAABxwAAAAFkGbNEmoQWyZTAhH//pYAAADAAADAy4AAAAOQZ9S" +
            "RRUt/wAAAwAAEDEAAAANAZ9xdEn/AAADAAAWkAAAAA0Bn3NqS/8AAAMAAA7oAAAAY0GbeEmoQWyZTAhf/3P4YAZW0sehKkc4PHVbVjU5OXWf8ox8Qyxz0wbN" +
            "T4DdvmlaVgn6FpYAALCAjoHGBeAaoGmCHAtQSQGgAAADAAADAAADAAADAAADAAADAAADAAADAABmQQAAAA5Bn5ZFFSxvAAADAAAHpAAAAA0Bn7V0RP8AAAMA" +
            "AAqZAAAADQGft2pG/wAAAwAAB6UAAAAWQZu8SahBbJlMCG///jhAAAADAAAbMAAAAA9Bn9pFFSwn/wAAAwAABH0AAAANAZ/5dEf/AAADAAAGXAAAAA0Bn/tq" +
            "Qj8AAAMAAAW1AAAAFkGb4EmoQWyZTAh///6eEAAAAwAABvUAAAAQQZ4eRRUsL/8AAAMAAAMCPgAAAA0Bnj10Qn8AAAMAAAR8AAAADgGeP2pCvwAAAwAAAwOP" +
            "AAAAF0GaJEmoQWyZTAgh//6nhAAAAwAAAwHHAAAAEEGeQkUVLDP/AAADAAADAd0AAAAOAZ5hdEL/AAADAAADAj4AAAAOAZ5jakL/AAADAAADAj8AAAAYQZpo" +
            "SahBbJlMCCP//qmWAAADAAADAOWBAAAAEEGehkUVLDv/AAADAAADAQ8AAAAOAZ6ldEM/AAADAAADAd0AAAAOAZ6nakN/AAADAAADAXcAAAAYQZqsSahBbJlM" +
            "CCX//qmWAAADAAADAOWAAAAAEUGeykUVLD//AAADAAADAMqBAAAADgGe6XRDvwAAAwAAAwEPAAAADwGe62pD/wAAAwAAAwDKgAAAABhBmvBJqEFsmUwIJf/+" +
            "qZYAAAMAAAMA5YEAAAARQZ8ORRUsEf8AAAMAAAMAcsEAAAAPAZ8tdEP/AAADAAADAMqBAAAADwGfL2pBDwAAAwAAAwCkgAAAABhBmzFJqEFsmUwII//+qZYA" +
            "AAMAAAMA5YAAAAWSbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAB9AAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAA" +
            "AABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAABL10cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAB9AAAAAAAAAAAAAAAAAAAAAA" +
            "AAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAoAAAAFoAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAfQAAAEAAABAAAAAAQ1bWRpYQAA" +
            "ACBtZGhkAAAAAAAAAAAAAAAAAAAyAAAAZABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAAD4G1pbmYAAAAUdm1o" +
            "ZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAA6BzdGJsAAAAwHN0c2QAAAAAAAAAAQAAALBhdmMxAAAAAAAAAAEA" +
            "AAAAAAAAAAAAAAAAAAAAAoABaABIAAAASAAAAAAAAAABFUxhdmM2Mi4xMS4xMDAgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAANmF2Y0MBZAAe/+EAGmdkAB6s" +
            "2UCgL/lwEQAAAwABAAADADIPFi2WAQAFaOvssiz9+PgAAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAAw1AAABzoAAAAGHN0dHMAAAAAAAAAAQAAADIA" +
            "AAIAAAAAFHN0c3MAAAAAAAAAAQAAAAEAAAGgY3R0cwAAAAAAAAAyAAAAAQAABAAAAAABAAAKAAAAAAEAAAQAAAAAAQAAAAAAAAABAAACAAAAAAEAAAoAAAAA" +
            "AQAABAAAAAABAAAAAAAAAAEAAAIAAAAAAQAACgAAAAABAAAEAAAAAAEAAAAAAAAAAQAAAgAAAAABAAAKAAAAAAEAAAQAAAAAAQAAAAAAAAABAAACAAAAAAEA" +
            "AAoAAAAAAQAABAAAAAABAAAAAAAAAAEAAAIAAAAAAQAACgAAAAABAAAEAAAAAAEAAAAAAAAAAQAAAgAAAAABAAAKAAAAAAEAAAQAAAAAAQAAAAAAAAABAAAC" +
            "AAAAAAEAAAoAAAAAAQAABAAAAAABAAAAAAAAAAEAAAIAAAAAAQAACgAAAAABAAAEAAAAAAEAAAAAAAAAAQAAAgAAAAABAAAKAAAAAAEAAAQAAAAAAQAAAAAA" +
            "AAABAAACAAAAAAEAAAoAAAAAAQAABAAAAAABAAAAAAAAAAEAAAIAAAAAAQAACgAAAAABAAAEAAAAAAEAAAAAAAAAAQAAAgAAAAABAAAEAAAAABxzdHNjAAAA" +
            "AAAAAAEAAAABAAAAMgAAAAEAAADcc3RzegAAAAAAAAAAAAAAMgAAAxgAAAARAAAAEQAAABEAAAARAAAAFwAAABMAAAARAAAAEQAAABgAAAATAAAAEQAAABEA" +
            "AAAYAAAAEgAAABEAAAARAAAAGgAAABIAAAARAAAAEQAAAGcAAAASAAAAEQAAABEAAAAaAAAAEwAAABEAAAARAAAAGgAAABQAAAARAAAAEgAAABsAAAAUAAAA" +
            "EgAAABIAAAAcAAAAFAAAABIAAAASAAAAHAAAABUAAAASAAAAEwAAABwAAAAVAAAAEwAAABMAAAAcAAAAFHN0Y28AAAAAAAAAAQAAADAAAABhdWR0YQAAAFlt" +
            "ZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYyLjMuMTAw"

        const val FAST_MP4 =
            "AAAAHGZ0eXBpc29tAAACAGlzb21pc28ybXA0MQAADwhtb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAH0AABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAA" +
            "AAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAOM3RyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAA" +
            "AAAH0AAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAACgAAAAWgAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAA" +
            "B9AAAAQAAAEAAAAADattZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAADIAAABkAFXEAAAAAAAtaGRscgAAAAAAAAAAdmlkZQAAAAAAAAAAAAAAAFZpZGVvSGFu" +
            "ZGxlcgAAAA1WbWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAANFnN0YmwAAAowc3RzZAAAAAAA" +
            "AAABAAAKIGh2YzEAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAACgAFoAEgAAABIAAAAAAAAAAEVTGF2YzYyLjExLjEwMCBsaWJ4MjY1AAAAAAAAAAAAAAAY//8A" +
            "AAmcaHZjQwEBYAAAAJAAAAAAAD/wAPz9+PgAAA8EoAABABhAAQwB//8BYAAAAwCQAAADAAADAD+VmAmhAAEAKkIBAQFgAAADAJAAAAMAAAMAP6AFAgFpZZWa" +
            "STK8BaAgAAADACAAAAMDIaIAAQAHRAHBcrRiQCcAAQkgTgEF////////////Gyyi3gm1F0fbu1Wk/n/C/E54MjY1IChidWlsZCAyMTUpIC0gNC4xKzIxMS05" +
            "ZTU1MWE5OTQ6W1dpbmRvd3NdW0dDQyAxNS4yLjBdWzY0IGJpdF0gOGJpdCsxMGJpdCsxMmJpdCAtIEguMjY1L0hFVkMgY29kZWMgLSBDb3B5cmlnaHQgMjAx" +
            "My0yMDE4IChjKSBNdWx0aWNvcmV3YXJlLCBJbmMgLSBodHRwOi8veDI2NS5vcmcgLSBvcHRpb25zOiBjcHVpZD0xMTExMDM5IGZyYW1lLXRocmVhZHM9NCBu" +
            "dW1hLXBvb2xzPTI0IHdwcCBuby1wbW9kZSBuby1wbWUgbm8tcHNuciBuby1zc2ltIGxvZy1sZXZlbD0tMSBiaXRkZXB0aD04IGlucHV0LWNzcD0xIGZwcz0y" +
            "NS8xIGlucHV0LXJlcz02NDB4MzYwIGludGVybGFjZT0wIHRvdGFsLWZyYW1lcz0wIGxldmVsLWlkYz0wIGhpZ2gtdGllcj0xIHVoZC1iZD0wIHJlZj0zIG5v" +
            "LWFsbG93LW5vbi1jb25mb3JtYW5jZSBuby1yZXBlYXQtaGVhZGVycyBhbm5leGIgbm8tYXVkIG5vLWVvYiBuby1lb3Mgbm8taHJkIGluZm8gaGFzaD0wIHRl" +
            "bXBvcmFsLWxheWVycz0wIG9wZW4tZ29wIG1pbi1rZXlpbnQ9MjUga2V5aW50PTI1MCBnb3AtbG9va2FoZWFkPTAgYmZyYW1lcz00IGItYWRhcHQ9MiBiLXB5" +
            "cmFtaWQgYmZyYW1lLWJpYXM9MCByYy1sb29rYWhlYWQ9MjAgbG9va2FoZWFkLXNsaWNlcz0wIHNjZW5lY3V0PTQwIG5vLWhpc3Qtc2NlbmVjdXQgcmFkbD0w" +
            "IG5vLXNwbGljZSBuby1pbnRyYS1yZWZyZXNoIGN0dT02NCBtaW4tY3Utc2l6ZT04IG5vLXJlY3Qgbm8tYW1wIG1heC10dS1zaXplPTMyIHR1LWludGVyLWRl" +
            "cHRoPTEgdHUtaW50cmEtZGVwdGg9MSBsaW1pdC10dT0wIHJkb3EtbGV2ZWw9MCBkeW5hbWljLXJkPTAuMDAgbm8tc3NpbS1yZCBzaWduaGlkZSBuby10c2tp" +
            "cCBuci1pbnRyYT0wIG5yLWludGVyPTAgbm8tY29uc3RyYWluZWQtaW50cmEgc3Ryb25nLWludHJhLXNtb290aGluZyBtYXgtbWVyZ2U9MyBsaW1pdC1yZWZz" +
            "PTEgbm8tbGltaXQtbW9kZXMgbWU9MSBzdWJtZT0yIG1lcmFuZ2U9NTcgdGVtcG9yYWwtbXZwIG5vLWZyYW1lLWR1cCBuby1obWUgd2VpZ2h0cCBuby13ZWln" +
            "aHRiIG5vLWFuYWx5emUtc3JjLXBpY3MgZGVibG9jaz0wOjAgc2FvIG5vLXNhby1ub24tZGVibG9jayByZD0zIHNlbGVjdGl2ZS1zYW89NCBlYXJseS1za2lw" +
            "IHJza2lwIG5vLWZhc3QtaW50cmEgbm8tdHNraXAtZmFzdCBuby1jdS1sb3NzbGVzcyBiLWludHJhIG5vLXNwbGl0cmQtc2tpcCByZHBlbmFsdHk9MCBwc3kt" +
            "cmQ9Mi4wMCBwc3ktcmRvcT0wLjAwIG5vLXJkLXJlZmluZSBuby1sb3NzbGVzcyBjYnFwb2Zmcz0wIGNycXBvZmZzPTAgcmM9Y3JmIGNyZj0yOC4wIHFjb21w" +
            "PTAuNjAgcXBzdGVwPTQgc3RhdHMtd3JpdGU9MCBzdGF0cy1yZWFkPTAgaXByYXRpbz0xLjQwIHBicmF0aW89MS4zMCBhcS1tb2RlPTIgYXEtc3RyZW5ndGg9" +
            "MS4wMCBjdXRyZWUgem9uZS1jb3VudD0wIG5vLXN0cmljdC1jYnIgcWctc2l6ZT0zMiBuby1yYy1ncmFpbiBxcG1heD02OSBxcG1pbj0wIG5vLWNvbnN0LXZi" +
            "diBzYXI9MSBvdmVyc2Nhbj0wIHZpZGVvZm9ybWF0PTUgcmFuZ2U9MCBjb2xvcnByaW09MiB0cmFuc2Zlcj0yIGNvbG9ybWF0cml4PTIgY2hyb21hbG9jPTAg" +
            "ZGlzcGxheS13aW5kb3c9MCBjbGw9MCwwIG1pbi1sdW1hPTAgbWF4LWx1bWE9MjU1IGxvZzItbWF4LXBvYy1sc2I9OCB2dWktdGltaW5nLWluZm8gdnVpLWhy" +
            "ZC1pbmZvIHNsaWNlcz0xIG5vLW9wdC1xcC1wcHMgbm8tb3B0LXJlZi1saXN0LWxlbmd0aC1wcHMgbm8tbXVsdGktcGFzcy1vcHQtcnBzIHNjZW5lY3V0LWJp" +
            "YXM9MC4wNSBuby1vcHQtY3UtZGVsdGEtcXAgbm8tYXEtbW90aW9uIG5vLWhkcjEwIG5vLWhkcjEwLW9wdCBuby1kaGRyMTAtb3B0IG5vLWlkci1yZWNvdmVy" +
            "eS1zZWkgYW5hbHlzaXMtcmV1c2UtbGV2ZWw9MCBhbmFseXNpcy1zYXZlLXJldXNlLWxldmVsPTAgYW5hbHlzaXMtbG9hZC1yZXVzZS1sZXZlbD0wIHNjYWxl" +
            "LWZhY3Rvcj0wIHJlZmluZS1pbnRyYT0wIHJlZmluZS1pbnRlcj0wIHJlZmluZS1tdj0xIHJlZmluZS1jdHUtZGlzdG9ydGlvbj0wIG5vLWxpbWl0LXNhbyBj" +
            "dHUtaW5mbz0wIG5vLWxvd3Bhc3MtZGN0IHJlZmluZS1hbmFseXNpcy10eXBlPTAgY29weS1waWM9MSBtYXgtYXVzaXplLWZhY3Rvcj0xLjAgbm8tZHluYW1p" +
            "Yy1yZWZpbmUgbm8tc2luZ2xlLXNlaSBuby1oZXZjLWFxIG5vLXN2dCBuby1maWVsZCBxcC1hZGFwdGF0aW9uLXJhbmdlPTEuMDAgc2NlbmVjdXQtYXdhcmUt" +
            "cXA9MGNvbmZvcm1hbmNlLXdpbmRvdy1vZmZzZXRzIHJpZ2h0PTAgYm90dG9tPTAgZGVjb2Rlci1tYXgtcmF0ZT0wIG5vLXZidi1saXZlLW11bHRpLXBhc3Mg" +
            "bm8tbWNzdGYgbm8tc2JyYyBuby1mcmFtZS1yY4AAAAAKZmllbAEAAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAAILwAAAAAAAAAGHN0dHMAAAAAAAAA" +
            "AQAAADIAAAIAAAAAFHN0c3MAAAAAAAAAAQAAAAEAAAA+c2R0cAAAAAAgEBAYGBgQEBgYGBAQGBgQEBgYGBAYEBAQEBgQEBgYGBAQEBgQEBgYGBAQGBgYEBAY" +
            "GAAAAWhjdHRzAAAAAAAAACsAAAABAAAEAAAAAAEAAAwAAAAAAQAABgAAAAACAAAAAAAAAAEAAAIAAAAAAQAADAAAAAABAAAGAAAAAAIAAAAAAAAAAQAAAgAA" +
            "AAABAAAKAAAAAAEAAAQAAAAAAQAAAAAAAAABAAACAAAAAAEAAAwAAAAAAQAABgAAAAACAAAAAAAAAAEAAAIAAAAAAQAABgAAAAABAAACAAAAAAIAAAQAAAAA" +
            "AQAACAAAAAABAAAEAAAAAAEAAAAAAAAAAQAADAAAAAABAAAGAAAAAAIAAAAAAAAAAQAAAgAAAAABAAAEAAAAAAEAAAgAAAAAAQAABAAAAAABAAAAAAAAAAEA" +
            "AAwAAAAAAQAABgAAAAACAAAAAAAAAAEAAAIAAAAAAQAADAAAAAABAAAGAAAAAAIAAAAAAAAAAQAAAgAAAAABAAAKAAAAAAEAAAQAAAAAAQAAAAAAAAABAAAC" +
            "AAAAABxzdHNjAAAAAAAAAAEAAAABAAAAMgAAAAEAAADcc3RzegAAAAAAAAAAAAAAMgAAAHIAAAApAAAAJgAAACcAAAAnAAAAJwAAACsAAAAoAAAAKAAAACgA" +
            "AAAnAAAALAAAACgAAAAnAAAAJwAAACwAAAAoAAAAKAAAACgAAAAnAAAAKwAAACgAAAArAAAAKwAAACsAAAAnAAAAJwAAACsAAAAoAAAAJwAAACcAAAAnAAAA" +
            "KwAAACsAAAAnAAAAJwAAACsAAAAoAAAAJwAAACcAAAAnAAAAKwAAACgAAAAoAAAAKAAAACcAAAAsAAAAKAAAACcAAAAnAAAAFHN0Y28AAAAAAAAAAQAADzQA" +
            "AABhdWR0YQAAAFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYy" +
            "LjMuMTAwAAAACGZyZWUAAAg3bWRhdAAAAG4oAa8dMWrWMXD3AjV//3Y5+xwAf0TKUFaImkgAAAcsqXHKBRiBJ4AAAAMAB8yTDZQgAAADAAADAAwYhwAAAwAA" +
            "AwAAAwAc8AAAAwAAAwAAAwAIaAAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwBwwAAAACUCAdApS+EMY4ZutuD5iJaH85IYqBoQ00qBmcMAHxAAANmAAAWMAAAA" +
            "IgIB4GSdeGEhmq2oi4RUkyvxAOeAYQAMWGUApYAAA94AGrAAAAAjAAHgJPVfosKTNVtQiYXklDfDgOCAZYALGGmAuoBdg6oAGrAAAAAjAAHgRNdfosKDNVtQ" +
            "iYXklDfDgOCAZYALGGmAuoBdg6oAGrAAAAAjAAHghrf9RhSZqtqAiYXklDfDgOCAZYALGGmAuoBdg6oAGrAAAAAnAgHQUJLV/cQwGOGbrbj5iJaH85IYqBoQ" +
            "00qBmcMAHxAAANmAAAWMAAAAJAIB4QInV19xhIZqtqCLhFSTK/EA54BhAAxYZQClgAAD3gAasAAAACQAAeDG9VX0iwpM1W1AiYXklDfDgOCAZYALGGmAuoBd" +
            "g6oAGrAAAAAkAAHg5tV19IsKDNVtQImF5JQ3w4DggGWACxhpgLqAXYOqABqwAAAAIwAB4SIt1/cYUmaraomF5JQ3w4DggGWACxhpgLqAXYOqABqwAAAAKAIB" +
            "0HCyVdfcQwGOmbrbgPmIlofzkhioGhDTSoGZwwAfEAAA2YAABYwAAAAkAgHhgiVS19xhIZqtqIuEVJMr8QDngGEADFhlAKWAAAPeABqwAAAAIwAB4Wb119Is" +
            "KTNVtYmF5JQ3w4DggGWACxhpgLqAXYOqABqwAAAAIwAB4aItV/cYUGaraomF5JQ3w4DggGWACxhpgLqAXYOqABqwAAAAKAIB0Jiy1VfcQwGOmbrbgPmIlofz" +
            "khioGhDTSoGZwwAfEAAA2YAABYwAAAAkAgHiIidSV9xhKZqtqIuEVJMr8QDngGEADFhlAKWAAAPeABqwAAAAJAAB4eb1VfSLCgzVbUCJheSUN8OA4IBlgAsY" +
            "aYC6gF2DqgAasAAAACQAAeIG1XX0iwoM1W1AiYXklDfDgOCAZYALGGmAuoBdg6oAGrAAAAAjAAHiQi3X9xhSZqtqiYXklDfDgOCAZYALGGmAuoBdg6oAGrAA" +
            "AAAnAgHQqLVXX3EMBjhm6275iJaH85IYqBoQ00qBmcMAHxAAANmAAAWMAAAAJAAB4oItS/3GFBmq2oCJheSUN8OA4IBlgAsYaYC6gF2DqgAasAAAACcCAdCw" +
            "vVS/cQwGOmbrbvmIlofzkhioGhDTSoGZwwAfEAAA2YAABYwAAAAnAgHQuL9V9xDAY4ZutuD5iJaH85IYqBoQ00qBmcMAHxAAANmAAAWMAAAAJwIB0NC38n3E" +
            "MBjpm624+YiWh/OSGKgaENNKgZnDAB8QAADZgAAFjAAAACMCAeMiJf/cYSmaraiLhFSTK/EA54BhAAxYZQClgAAD3gAasAAAACMAAeMG//0iwpM1W1CJheSU" +
            "N8OA4IBlgAsYaYC6gF2DqgAasAAAACcCAdD4svX9xDAY6ZutuPmIlofzkhioGhDTSoGZwwAfEAAA2YAABYwAAAAkAgHjoifVfcYSGaragIuEVJMr8QDngGEA" +
            "DFhlAKWAAAPeABqwAAAAIwAB42b9V9IsKTNVtYmF5JQ3w4DggGWACxhpgLqAXYOqABqwAAAAIwAB44bX19IsKTNVtYmF5JQ3w4DggGWACxhpgLqAXYOqABqw" +
            "AAAAIwAB48It/9xhSZqtqImF5JQ3w4DggGWACxhpgLqAXYOqABqwAAAAJwIB0QC9X/cQwGOmbrbg+YiWh/OSGKgaENNKgZnDAB8QAADZgAAFjAAAACcCAdEY" +
            "t9SfcQwGOmbrbvmIlofzkhioGhDTSoGZwwAfEAAA2YAABYwAAAAjAgHkQiXX9xhIZqtqi4RUkyvxAOeAYQAMWGUApYAAA94AGrAAAAAjAAHkJvf/SLCgzVbU" +
            "iYXklDfDgOCAZYALGGmAuoBdg6oAGrAAAAAnAgHRQLL1f3EMBjpm6275iJaH85IYqBoQ00qBmcMAHxAAANmAAAWMAAAAJAIB5MIn1X3GEhmq2oCLhFSTK/EA" +
            "54BhAAxYZQClgAAD3gAasAAAACMAAeSG/VfSLCgzVbWJheSUN8OA4IBlgAsYaYC6gF2DqgAasAAAACMAAeSm19fSLCkzVbWJheSUN8OA4IBlgAsYaYC6gF2D" +
            "qgAasAAAACMAAeTiLf/cYUGaraiJheSUN8OA4IBlgAsYaYC6gF2DqgAasAAAACcCAdFostX/cQwGOmbrbvmIlofzkhioGhDTSoGZwwAfEAAA2YAABYwAAAAk" +
            "AgHlYidSV9xhIZqtqIuEVJMr8QDngGEADFhlAKWAAAPeABqwAAAAJAAB5Sb1VfSLCgzVbUCJheSUN8OA4IBlgAsYaYC6gF2DqgAasAAAACQAAeVG1XX0iwpM" +
            "1W1AiYXklDfDgOCAZYALGGmAuoBdg6oAGrAAAAAjAAHlgi3X9xhQZqtqiYXklDfDgOCAZYALGGmAuoBdg6oAGrAAAAAoAgHRiLJV19xDAY6ZutuA+YiWh/OS" +
            "GKgaENNKgZnDAB8QAADZgAAFjAAAACQCAeXiJVLX3GEhmq2oi4RUkyvxAOeAYQAMWGUApYAAA94AGrAAAAAjAAHlxvXX0iwoM1W1iYXklDfDgOCAZYALGGmA" +
            "uoBdg6oAGrAAAAAjAAHmAi1X9xhSZqtqiYXklDfDgOCAZYALGGmAuoBdg6oAGrA="
    }
}
