/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉住「离开播放页时 Surface 释放超时」不被当成源的问题: 认错了会当场中断播放换源, 还把好源
 * 拉进整个会话只增不减的黑名单 (真机现场见 2026-09-17 的 demo 日志).
 */
class PlayerLifecycleErrorTest {
    private class FakeThrowable(
        override val message: String?,
        override val cause: Throwable? = null,
    ) : Exception()

    /** cause 指向自己, 用来确认遍历不会转不出来. */
    private class SelfCausedThrowable : Exception("loop") {
        override val cause: Throwable get() = this
    }

    @Test
    fun `surface detach timeout is a lifecycle error`() {
        // 真机上的三层链: mediamp 的 PlaybackException → ExoPlaybackException → ExoTimeoutException
        val error = PlayerLoadError(
            "code=ERROR_CODE_TIMEOUT",
            FakeThrowable(
                "ExoPlayer playback failed: ERROR_CODE_TIMEOUT (1003): Unexpected runtime error",
                FakeThrowable(
                    "Unexpected runtime error",
                    FakeThrowable("Detaching surface timed out."),
                ),
            ),
        )
        assertTrue(error.isPlayerLifecycleError())
    }

    @Test
    fun `release 包里类名被混淆也能认出来`() {
        // R8 会把 ExoTimeoutException 改名, 但异常消息是字符串常量, 不会变 —— 判定不能只靠类名
        assertTrue(PlayerLoadError("code=1003", FakeThrowable("Detaching surface timed out.")).isPlayerLifecycleError())
    }

    @Test
    fun `network failure is not a lifecycle error`() {
        val error = PlayerLoadError(
            "code=ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            FakeThrowable("Unable to connect to https://example.com/1.mp4", FakeThrowable("timeout")),
        )
        assertFalse(error.isPlayerLifecycleError())
    }

    @Test
    fun `error without cause is not a lifecycle error`() {
        assertFalse(PlayerLoadError("playing cache deleted (mediaId=x)", null).isPlayerLifecycleError())
    }

    @Test
    fun `self-referencing cause chain terminates`() {
        assertFalse(PlayerLoadError("boom", SelfCausedThrowable()).isPlayerLifecycleError())
    }
}
