/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.settings

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.BangumiRouteKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SettingAdjustmentsTest {
    private val skipOrigin = SettingAdjustment.SkipBangumiOrigin(BangumiRouteKind.ECH)

    @Test
    fun `告知 — 发给界面`() = runTest {
        val adjustments = SettingAdjustments(backgroundScope)
        val received = async { adjustments.notices.first() }
        runCurrent()
        adjustments.notify(SettingAdjustment.VideoEnhancementTooSlow)
        assertEquals(SettingAdjustment.VideoEnhancementTooSlow, received.await())
    }

    @Test
    fun `询问 — 选了才改，问完收起`() = runTest {
        val adjustments = SettingAdjustments(backgroundScope)
        var applied = 0
        val choice = SettingAdjustmentChoice("skip-origin") { applied++ }
        adjustments.ask(skipOrigin, listOf(choice))

        val question = adjustments.question.value!!
        assertSame(skipOrigin, question.adjustment)
        assertEquals(0, applied)

        adjustments.answer(question, choice)
        runCurrent()
        assertEquals(1, applied)
        assertNull(adjustments.question.value)
    }

    @Test
    fun `同一件事在等回答时再报 — 不重复问`() = runTest {
        val adjustments = SettingAdjustments(backgroundScope)
        adjustments.ask(skipOrigin, emptyList())
        val first = adjustments.question.value
        adjustments.ask(SettingAdjustment.SkipBangumiOrigin(BangumiRouteKind.MIRRORS), emptyList())
        assertSame(first, adjustments.question.value)

        adjustments.answer(first!!, null)
        assertNull(adjustments.question.value)
    }

    @Test
    fun `选了暂不 — 这次运行不再问`() = runTest {
        val adjustments = SettingAdjustments(backgroundScope)
        adjustments.ask(skipOrigin, emptyList())
        adjustments.answer(adjustments.question.value!!, null)

        adjustments.ask(skipOrigin, emptyList())
        assertNull(adjustments.question.value)
    }

    @Test
    fun `弹之前判出不必问 — 不算暂不，以后再报还会问`() = runTest {
        val adjustments = SettingAdjustments(backgroundScope)
        adjustments.ask(skipOrigin, emptyList())
        adjustments.drop(adjustments.question.value!!)
        assertNull(adjustments.question.value)

        adjustments.ask(skipOrigin, emptyList())
        assertSame(skipOrigin, adjustments.question.value?.adjustment)
    }

    @Test
    fun `几件事一起要问 — 一次问一件，问完下一件`() = runTest {
        val adjustments = SettingAdjustments(backgroundScope)
        adjustments.ask(skipOrigin, emptyList())
        adjustments.ask(SettingAdjustment.FullSearchStutter, emptyList())

        assertSame(skipOrigin, adjustments.question.value?.adjustment)
        adjustments.answer(adjustments.question.value!!, null)
        assertSame(SettingAdjustment.FullSearchStutter, adjustments.question.value?.adjustment)
    }
}
