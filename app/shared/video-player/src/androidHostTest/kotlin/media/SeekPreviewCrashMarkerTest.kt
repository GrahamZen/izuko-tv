/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.app.ApplicationExitInfo
import me.him188.ani.app.videoplayer.media.SeekPreviewCrashMarker.Marker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 小画面预览的标记: 读写格式, 以及下次启动时怎么判「上次死在小画面预览里」.
 */
class SeekPreviewCrashMarkerTest {
    private val onPreview = Marker(pid = 1234, backAtMillis = 0L)
    private val backAt = 1_000_000L
    private val backOnFullScreen = Marker(pid = 1234, backAtMillis = backAt)

    @Test
    fun `标记写出去再读回来不变, 认不出的内容当没有`() {
        assertEquals(onPreview, Marker.parse(onPreview.serialize()))
        assertEquals(backOnFullScreen, Marker.parse(backOnFullScreen.serialize() + "\n"))
        assertNull(Marker.parse(""))
        assertNull(Marker.parse("1234"))
        assertNull(Marker.parse("x 0"))
    }

    @Test
    fun `有退出记录时只认原生崩溃`() {
        assertTrue(isCrashDuringPreview(onPreview, exitInfoAvailable = true, ApplicationExitInfo.REASON_CRASH_NATIVE, 0L))
        assertFalse(isCrashDuringPreview(onPreview, exitInfoAvailable = true, ApplicationExitInfo.REASON_LOW_MEMORY, 0L))
        assertFalse(isCrashDuringPreview(onPreview, exitInfoAvailable = true, ApplicationExitInfo.REASON_USER_REQUESTED, 0L))
        assertFalse(isCrashDuringPreview(onPreview, exitInfoAvailable = true, exitReason = null, 0L))
    }

    @Test
    fun `接回全屏不久的原生崩溃也算, 过了很久的不算`() {
        val justAfter = backAt + SeekPreviewCrashMarker.AFTER_FULL_SCREEN_MILLIS
        assertTrue(isCrashDuringPreview(backOnFullScreen, exitInfoAvailable = true, ApplicationExitInfo.REASON_CRASH_NATIVE, justAfter))
        assertFalse(isCrashDuringPreview(backOnFullScreen, exitInfoAvailable = true, ApplicationExitInfo.REASON_CRASH_NATIVE, justAfter + 1))
    }

    @Test
    fun `没有退出记录时凭输出还在小画面上`() {
        assertTrue(isCrashDuringPreview(onPreview, exitInfoAvailable = false, exitReason = null, 0L))
        assertFalse(isCrashDuringPreview(backOnFullScreen, exitInfoAvailable = false, exitReason = null, backAt + 1))
    }
}
