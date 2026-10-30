/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import me.him188.ani.app.domain.player.SecondaryDecoderActivity
import me.him188.ani.app.domain.player.isDecoderPreempted
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * 钉住「主播放器的解码器被抢走」的认定: 认出来才会原地重载, 而不是把好源拉黑换掉;
 * 认宽了则会让片源本身解不了的情况多等一次才换源.
 * 真机现场: 2026-09-26 测试包用户的 RESOURCES_RECLAIMED, issue #10 (OMX 抢占, Java 层只剩解码失败),
 * 以及暂停后进屏保、回前台接回画面时解码器已不在运行 (MediaTek 安卓 14).
 */
class DecoderPreemptedErrorTest {
    private class FakeThrowable(
        override val message: String?,
        override val cause: Throwable? = null,
    ) : Exception()

    private val time = TestTimeSource()

    @BeforeTest
    fun setUp() {
        SecondaryDecoderActivity.timeSource = time
        SecondaryDecoderActivity.reset()
    }

    @AfterTest
    fun tearDown() {
        SecondaryDecoderActivity.timeSource = TimeSource.Monotonic
        SecondaryDecoderActivity.reset()
    }

    /** 真机上的链: mediamp 的 PlaybackException → ExoPlaybackException → MediaCodecVideoDecoderException → 底层异常 */
    private fun playbackError(codeName: String, code: Int, innermost: Throwable) = FakeThrowable(
        "ExoPlayer playback failed: $codeName ($code): MediaCodecVideoRenderer error, index=0",
        FakeThrowable("MediaCodecVideoRenderer error, index=0", FakeThrowable("Decoder failed: c2.sec.avc.decoder", innermost)),
    )

    @Test
    fun `系统收回解码器一定算`() {
        val error = playbackError("ERROR_CODE_DECODING_RESOURCES_RECLAIMED", 4006, FakeThrowable("Error 0xffffffe0"))
        assertTrue(isDecoderPreempted(error))
        assertFalse(PlayerLoadError("code=DECODING", error).isPlayerLifecycleError())
    }

    @Test
    fun `回前台接回画面时解码器已经不在运行算`() {
        // 用户日志原文: 解码器在后台被系统收走, 播放器没收到收回的错误, 回前台 setOutputSurface 才撞上, 报成普通的运行时错误
        val error = FakeThrowable(
            "ExoPlayer playback failed: ERROR_CODE_FAILED_RUNTIME_CHECK (1004): Unexpected runtime error",
            FakeThrowable(
                "Unexpected runtime error",
                IllegalStateException("setSurface() is valid only at Executing states; currently at Uninitialized state"),
            ),
        )
        assertTrue(isDecoderPreempted(error))
    }

    @Test
    fun `别的运行时错误不算`() {
        val error = FakeThrowable(
            "ExoPlayer playback failed: ERROR_CODE_FAILED_RUNTIME_CHECK (1004): Unexpected runtime error",
            FakeThrowable("Unexpected runtime error", IllegalStateException("Player is accessed on the wrong thread")),
        )
        assertFalse(isDecoderPreempted(error))
    }

    @Test
    fun `刚取过缩略图时的解码失败算`() {
        // issue #10: OMX 抢占在 Java 层只剩一个没有消息的 IllegalStateException
        val error = playbackError("ERROR_CODE_DECODING_FAILED", 4003, IllegalStateException())
        SecondaryDecoderActivity.markUsed()
        time += 5.seconds
        assertTrue(isDecoderPreempted(error))
    }

    @Test
    fun `最近没取缩略图时的解码失败不算`() {
        val error = playbackError("ERROR_CODE_DECODING_FAILED", 4003, IllegalStateException())
        assertFalse(isDecoderPreempted(error))

        SecondaryDecoderActivity.markUsed()
        time += SecondaryDecoderActivity.PREEMPTION_WINDOW + 1.seconds
        assertFalse(isDecoderPreempted(error))
    }

    @Test
    fun `刚取过缩略图时别的解码错误也不算`() {
        SecondaryDecoderActivity.markUsed()
        val unsupported = playbackError("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", 4005, IllegalStateException())
        assertFalse(isDecoderPreempted(unsupported))
        assertFalse(isDecoderPreempted(null))
    }
}
