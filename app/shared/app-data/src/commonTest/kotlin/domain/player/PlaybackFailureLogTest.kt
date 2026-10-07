/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.player.PlaybackFailureLog.Reason
import me.him188.ani.app.domain.player.PlaybackFailureLog.Stage
import me.him188.ani.app.domain.player.extension.PlaybackFailureReportExtension
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackFailureLogTest {
    private class FakeThrowable(override val message: String?, override val cause: Throwable? = null) : Exception()
    private class UnknownHostException(message: String) : Exception(message)

    private val media = createTestDefaultMedia(
        mediaId = "m1",
        mediaSourceId = "girigiri",
        originalUrl = "https://example.com/play/1",
        download = ResourceLocation.HttpStreamingFile("https://cdn.video.test:8443/1/index.m3u8?sign=secret"),
        originalTitle = "第 1 集",
        publishedTime = 1L,
        properties = createTestMediaProperties(subjectName = "S", episodeName = "1"),
        episodeRange = EpisodeRange.single(EpisodeSort(1)),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )

    @Test
    fun `exoplayer connection failure is a network failure`() {
        val error = FakeThrowable(
            "ExoPlayer playback failed: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001): Source error",
            FakeThrowable("Source error"),
        )
        assertTrue(error.isNetworkFailure())
        assertEquals(Reason.NETWORK, PlaybackFailureReportExtension.reasonOf(VideoLoadingState.UnknownError(error)))
    }

    @Test
    fun `unresolvable host deep in the chain is a network failure`() {
        val error = FakeThrowable("Failed to prepare HLS", FakeThrowable("io", UnknownHostException("Unable to resolve host \"cdn.video.test\"")))
        assertTrue(error.isNetworkFailure())
    }

    @Test
    fun `bad http status and decoding errors are not network failures`() {
        assertFalse(FakeThrowable("ExoPlayer playback failed: ERROR_CODE_IO_BAD_HTTP_STATUS (2004): Response code: 403").isNetworkFailure())
        assertFalse(FakeThrowable("ExoPlayer playback failed: ERROR_CODE_DECODING_FAILED (4003)").isNetworkFailure())
        assertEquals(Reason.UNKNOWN, PlaybackFailureReportExtension.reasonOf(VideoLoadingState.UnknownError(FakeThrowable("boom"))))
    }

    @Test
    fun `http error status from exoplayer and ktor messages`() {
        val exo = FakeThrowable(
            "ExoPlayer playback failed: ERROR_CODE_IO_BAD_HTTP_STATUS (2004): Source error",
            FakeThrowable("Source error", FakeThrowable("Response code: 403")),
        )
        assertEquals(403, exo.httpErrorStatus())
        assertEquals(Reason.HTTP_ERROR, PlaybackFailureReportExtension.reasonOf(VideoLoadingState.UnknownError(exo)))
        assertEquals(404, FakeThrowable("Client request(GET https://cdn.video.test/a.m3u8) invalid: 404 Not Found. Text: \"\"").httpErrorStatus())
        assertEquals(502, FakeThrowable("Server error(GET https://cdn.video.test/a.m3u8: 502 Bad Gateway. Text: \"\"").httpErrorStatus())
        // 2xx / 3xx 与没有状态码的不算
        assertNull(FakeThrowable("Response code: 206").httpErrorStatus())
        assertNull(FakeThrowable("ExoPlayer playback failed: ERROR_CODE_DECODING_FAILED (4003)").httpErrorStatus())
    }

    @Test
    fun `entries belong to the current episode and reset on a new one`() {
        PlaybackFailureLog.start(1, 10)
        PlaybackFailureLog.record(1, 10, media, Stage.OPEN, Reason.NETWORK, FakeThrowable("Unable to connect to https://cdn.video.test/a.ts?token=abc"))
        // 后台保留的另一部番的失败不记进来
        PlaybackFailureLog.record(2, 20, media, Stage.OPEN, Reason.UNKNOWN, null)
        PlaybackFailureLog.start(1, 10)
        val entry = PlaybackFailureLog.report.value!!.entries.single()
        assertEquals("girigiri", entry.mediaSourceId)
        assertEquals("cdn.video.test", entry.host)
        // 网址里的参数 (签名、令牌) 不进报告
        assertEquals(listOf("FakeThrowable: Unable to connect to https://cdn.video.test/a.ts?…"), entry.causes)
        assertFalse(entry.stackTrace!!.contains("token=abc"))

        PlaybackFailureLog.start(1, 11)
        assertEquals(emptyList(), PlaybackFailureLog.report.value!!.entries)
    }

    @Test
    fun `host named in the error goes first`() {
        // 经本地 HLS 代理播放: 播放器报代理地址, 真正解析不出的是视频域名
        val error = FakeThrowable(
            "Source error at http://127.0.0.1:41000/proxy/index.m3u8",
            UnknownHostException("Unable to resolve host \"cdn.wlcdn88.com\": No address associated with hostname"),
        )
        assertEquals("cdn.wlcdn88.com", PlaybackFailureLog.hostInError(error))
        assertEquals("cdn.video.test", PlaybackFailureLog.hostInError(FakeThrowable("Failed to connect to cdn.video.test/1.2.3.4:443")))
        assertNull(PlaybackFailureLog.hostInError(FakeThrowable("boom http://127.0.0.1:41000/x")))
    }

    @Test
    fun `previous episode failures are kept after switching episodes`() {
        PlaybackFailureLog.start(5, 50)
        PlaybackFailureLog.record(5, 50, media, Stage.PLAYBACK, Reason.TOO_SHORT, null, mediaDurationMillis = 2_000)
        // 太短的视频播完, 被带着换到下一集
        PlaybackFailureLog.start(5, 51)
        assertEquals(emptyList(), PlaybackFailureLog.report.value!!.entries)
        val previous = PlaybackFailureLog.previousReport.value!!
        assertEquals(50, previous.episodeId)
        assertEquals(2_000L, previous.entries.single().mediaDurationMillis)

        // 下一集没失败过: 再换集时留着的还是 50 那一集的
        PlaybackFailureLog.start(5, 52)
        assertEquals(50, PlaybackFailureLog.previousReport.value!!.episodeId)

        // 换回 50: 接着用它的记录
        PlaybackFailureLog.start(5, 50)
        assertEquals(1, PlaybackFailureLog.report.value!!.entries.size)
        assertNull(PlaybackFailureLog.previousReport.value)
    }

    @Test
    fun `resolve failure is taken once`() {
        val error = FakeThrowable("网盘拒绝转存")
        PlaybackFailureLog.noteResolveFailure("m1", error)
        assertEquals(error, PlaybackFailureLog.takeResolveFailure("m1"))
        assertNull(PlaybackFailureLog.takeResolveFailure("m1"))
    }

    @Test
    fun `cause chain stops at a self reference`() {
        class Loop : Exception("loop") {
            override val cause: Throwable get() = this
        }
        assertEquals(1, PlaybackFailureLog.causeLines(Loop()).size)
    }
}
