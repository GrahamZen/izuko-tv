/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * 钉住「拖动预览换输出弄坏了解码器」的认定 (真机现场: 2026-10-09 Amlogic 盒子 `OMX.amlogic.avc.decoder.awesome`, Android 9):
 * 认出来才会原地重载、改成画在全屏上, 而不是把能播的源逐个拉黑; 认宽了则会让片源本身解不了的情况多重试一阵才换源.
 */
class SeekPreviewDecoderFaultTest {
    private open class FakeThrowable(
        override val message: String?,
        override val cause: Throwable? = null,
    ) : Exception()

    /** 安卓的 MediaCodec.CodecException: commonMain 里只能按 toString 认. */
    private class FakeCodecException(message: String) : FakeThrowable(message) {
        override fun toString(): String = "android.media.MediaCodec\$CodecException: $message"
    }

    private val time = TestTimeSource()

    @BeforeTest
    fun setUp() {
        SeekPreviewDecoderFault.timeSource = time
        SeekPreviewDecoderFault.reset()
    }

    @AfterTest
    fun tearDown() {
        SeekPreviewDecoderFault.timeSource = TimeSource.Monotonic
        SeekPreviewDecoderFault.reset()
    }

    /** 真机日志里的链: mediamp 的 PlaybackException → ExoPlaybackException → MediaCodecVideoDecoderException → 没有消息的 IllegalStateException */
    private fun decodingFailed() = FakeThrowable(
        "ExoPlayer playback failed: ERROR_CODE_DECODING_FAILED (4003): MediaCodecVideoRenderer error, index=0",
        FakeThrowable(
            "MediaCodecVideoRenderer error, index=0",
            FakeThrowable("Decoder failed: OMX.amlogic.avc.decoder.awesome", IllegalStateException()),
        ),
    )

    /** 解码器坏着时跳转: flush 失败, 报成运行时检查失败, 链上是 CodecException */
    private fun flushFailed() = FakeThrowable(
        "ExoPlayer playback failed: ERROR_CODE_FAILED_RUNTIME_CHECK (1004): Unexpected runtime error",
        FakeThrowable("Unexpected runtime error", FakeCodecException("Error 0xffffffc2")),
    )

    private fun networkFailed() = FakeThrowable(
        "ExoPlayer playback failed: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001): Source error",
        FakeThrowable("Failed to connect to cdn.example.com"),
    )

    @Test
    fun `换输出之后不久的解码失败算`() {
        SeekPreviewDecoderFault.markOutputSwitched()
        time += 4.seconds
        assertTrue(SeekPreviewDecoderFault.isFault(decodingFailed()))
    }

    @Test
    fun `没换过输出的解码失败不算`() {
        assertFalse(SeekPreviewDecoderFault.isFault(decodingFailed()))
    }

    @Test
    fun `换输出很久之后的解码失败不算`() {
        SeekPreviewDecoderFault.markOutputSwitched()
        time += SeekPreviewDecoderFault.SWITCH_WINDOW + 1.seconds
        assertFalse(SeekPreviewDecoderFault.isFault(decodingFailed()))
    }

    @Test
    fun `换输出之后的网络错误不算`() {
        SeekPreviewDecoderFault.markOutputSwitched()
        assertFalse(SeekPreviewDecoderFault.isFault(networkFailed()))
        assertFalse(SeekPreviewDecoderFault.isFault(null))
    }

    @Test
    fun `认出之后解码器还坏着的那一阵里, 换了源的解码失败和跳转失败也算`() {
        SeekPreviewDecoderFault.markOutputSwitched()
        assertTrue(SeekPreviewDecoderFault.isFault(decodingFailed()))
        assertTrue(SeekPreviewDecoderFault.recordFault())

        // 原地重载、换源之后都不再换输出, 换输出的那一下已经过去很久
        time += SeekPreviewDecoderFault.SWITCH_WINDOW + 10.seconds
        assertTrue(SeekPreviewDecoderFault.isFault(decodingFailed()))
        assertTrue(SeekPreviewDecoderFault.isFault(flushFailed()))
        assertFalse(SeekPreviewDecoderFault.isFault(networkFailed()))
    }

    @Test
    fun `一轮只从第一次认出时算起, 过了就照常换源`() {
        SeekPreviewDecoderFault.markOutputSwitched()
        assertTrue(SeekPreviewDecoderFault.recordFault())
        time += 30.seconds
        assertFalse(SeekPreviewDecoderFault.recordFault()) // 同一轮: 不延长, 也不算新的一轮
        time += 31.seconds
        assertFalse(SeekPreviewDecoderFault.isFault(decodingFailed()))
    }

    @Test
    fun `上一轮过了之后再换输出出错是新的一轮`() {
        SeekPreviewDecoderFault.markOutputSwitched()
        assertTrue(SeekPreviewDecoderFault.recordFault())
        time += SeekPreviewDecoderFault.FAULT_WINDOW + 1.seconds
        SeekPreviewDecoderFault.markOutputSwitched()
        assertTrue(SeekPreviewDecoderFault.isFault(decodingFailed()))
        assertTrue(SeekPreviewDecoderFault.recordFault())
    }
}
