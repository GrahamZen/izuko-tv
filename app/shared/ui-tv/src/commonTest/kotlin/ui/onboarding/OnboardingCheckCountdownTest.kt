/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class OnboardingCheckCountdownTest {
    @Test
    fun `seconds left rounds up and never shows zero`() {
        assertEquals(8, onboardingSecondsLeft(deadlineMillis = 8_000, nowMillis = 0))
        assertEquals(8, onboardingSecondsLeft(deadlineMillis = 8_000, nowMillis = 1))
        assertEquals(5, onboardingSecondsLeft(deadlineMillis = 8_000, nowMillis = 3_000))
        assertEquals(1, onboardingSecondsLeft(deadlineMillis = 8_000, nowMillis = 7_999))
        // 到点还没出结论: 停在 1 秒
        assertEquals(1, onboardingSecondsLeft(deadlineMillis = 8_000, nowMillis = 8_000))
        assertEquals(1, onboardingSecondsLeft(deadlineMillis = 8_000, nowMillis = 9_500))
    }

    @Test
    fun `a visible run sets the deadline and a quiet rerun keeps it`() = runTest {
        var now = 1_000L
        val check = OnboardingCheck(
            backgroundScope,
            initial = false,
            completed = { it },
            run = { flow<Boolean> { awaitCancellation() } },
            remembered = OnboardingCheck.Remembered(),
            maxDurationMillis = 8_000,
            clock = { now },
        )
        assertEquals(0L, check.deadlineMillis.value)

        check.restart(quiet = false)
        assertEquals(9_000L, check.deadlineMillis.value)

        // 静默重测时页面摆着上次的结论, 不显示「检测中」, 也就不改倒计时
        now = 5_000L
        check.restart(quiet = true)
        assertEquals(9_000L, check.deadlineMillis.value)

        check.restart(quiet = false)
        assertEquals(13_000L, check.deadlineMillis.value)
    }
}
