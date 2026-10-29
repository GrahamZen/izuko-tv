/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 重试次数与打开阶段; 坏链接真的很快报错见设备测试 OpeningFailFastTest. */
@OptIn(UnstableApi::class)
class OpeningLoadErrorPolicyTest {
    @Test
    fun `retries once while opening and uses the default afterwards`() {
        var opening = true
        val policy = OpeningLoadErrorPolicy(isOpening = { opening })
        assertEquals(1, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(1, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))

        opening = false
        val default = DefaultLoadErrorHandlingPolicy()
        assertEquals(default.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA), policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `a host that does not resolve is not worth a retry`() {
        // 状态码那几种要真的 Media3 数据规格, 在设备测试 OpeningFailFastTest 里测
        assertTrue(OpeningLoadErrorPolicy.isDefinitive(IOException(UnknownHostException("cdn.example"))))
        assertFalse(OpeningLoadErrorPolicy.isDefinitive(IOException("connection reset")))
        assertFalse(OpeningLoadErrorPolicy.isDefinitive(SocketTimeoutException()))
    }

    @Test
    fun `opening phase ends when the open returns or throws`() {
        val phase = OpeningPhase()
        assertFalse(phase.isOpening)
        phase.during { assertTrue(phase.isOpening) }
        assertFalse(phase.isOpening)

        runCatching { phase.during { error("open failed") } }
        assertFalse(phase.isOpening)
    }

    @Test
    fun `a superseded open ending does not end the newer open`() {
        val phase = OpeningPhase()
        val first = phase.begin()
        val second = phase.begin()
        phase.end(first)
        assertTrue(phase.isOpening)
        phase.end(second)
        assertFalse(phase.isOpening)
    }
}
