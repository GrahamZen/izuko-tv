/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web.captcha

import me.him188.ani.app.platform.PlaybackActivity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundBrowserGateTest {
    private val player = Any()
    private val recognizer = ImageCaptchaRecognizer { "1234" }
    private val httpSolver = MacCmsImageCaptchaSolver(recognizer)
    private val browserSolver = BrowserImageCaptchaSolver(recognizer)

    @AfterTest
    fun tearDown() {
        PlaybackActivity.report(player, false)
        BackgroundBrowserGate.autoSolveWithBrowser = true
    }

    @Test
    fun `browser solvers are usable when nothing is playing`() {
        assertTrue(BackgroundBrowserGate.allowed())
        assertEquals(listOf(httpSolver, browserSolver), BackgroundBrowserGate.usable(listOf(httpSolver, browserSolver)))
    }

    @Test
    fun `only http solvers are usable while a video plays`() {
        PlaybackActivity.report(player, true)
        assertFalse(BackgroundBrowserGate.allowed())
        assertEquals(listOf(httpSolver), BackgroundBrowserGate.usable(listOf(httpSolver, browserSolver)))
    }

    @Test
    fun `only http solvers are usable when browser auto solve is turned off`() {
        BackgroundBrowserGate.autoSolveWithBrowser = false
        assertTrue(BackgroundBrowserGate.allowed())
        assertEquals(listOf(httpSolver), BackgroundBrowserGate.usable(listOf(httpSolver, browserSolver)))
    }

    @Test
    fun `browser is usable again after playback stops`() {
        PlaybackActivity.report(player, true)
        PlaybackActivity.report(player, false)
        assertTrue(BackgroundBrowserGate.allowed())
    }
}
