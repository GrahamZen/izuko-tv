/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import me.him188.ani.app.domain.foundation.BangumiMirrorConsent.Question
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 已登录的人改用第三方镜像前要不要问 ([BangumiMirrorConsent]), 以及自动切换时的询问口子 ([BangumiMirrorConsentRequests]).
 */
class BangumiMirrorConsentTest {
    private fun s(mode: BangumiEndpointMode, cred: Boolean = false) =
        BangumiEndpointSettings(mode = mode, allowCredentialsViaMirror = cred)

    @Test
    fun `没登录 — 怎么改都不问`() {
        for (from in BangumiEndpointMode.entries) {
            assertNull(BangumiMirrorConsent.check(s(from), s(BangumiEndpointMode.MIRROR), loggedIn = false))
        }
    }

    @Test
    fun `登录着改成用镜像、凭证不经过镜像 — 问`() {
        for (from in listOf(BangumiEndpointMode.AUTO, BangumiEndpointMode.DIRECT, BangumiEndpointMode.CUSTOM)) {
            assertEquals(Question.SwitchToMirror, BangumiMirrorConsent.check(s(from), s(BangumiEndpointMode.MIRROR), loggedIn = true))
        }
    }

    @Test
    fun `登录着改成用镜像、凭证已经允许经过镜像 — 不问`() {
        assertNull(BangumiMirrorConsent.check(s(BangumiEndpointMode.AUTO, cred = true), s(BangumiEndpointMode.MIRROR, cred = true), loggedIn = true))
    }

    @Test
    fun `登录着用镜像时关掉凭证 — 提醒`() {
        assertEquals(
            Question.CredentialsOff,
            BangumiMirrorConsent.check(s(BangumiEndpointMode.MIRROR, cred = true), s(BangumiEndpointMode.MIRROR), loggedIn = true),
        )
    }

    @Test
    fun `改成不经第三方镜像的几档 — 不问`() {
        for (to in listOf(BangumiEndpointMode.AUTO, BangumiEndpointMode.DIRECT, BangumiEndpointMode.CUSTOM)) {
            assertNull(BangumiMirrorConsent.check(s(BangumiEndpointMode.MIRROR, cred = true), s(to), loggedIn = true))
        }
    }

    @Test
    fun `自动切换 — 官方连不上时用镜像要改成用镜像，登录着且凭证不经过镜像才问`() {
        val auto = s(BangumiEndpointMode.AUTO)
        assertEquals(Question.SwitchToMirror, BangumiMirrorConsent.check(auto, auto.afterOriginUnreachable(), loggedIn = true))
        val allowed = s(BangumiEndpointMode.AUTO, cred = true)
        assertNull(BangumiMirrorConsent.check(allowed, allowed.afterOriginUnreachable(), loggedIn = true))
        assertNull(BangumiMirrorConsent.check(auto, auto.afterOriginUnreachable(), loggedIn = false))
    }

    @Test
    fun `询问口子 — 选了暂不这次运行不再问`() {
        val requests = BangumiMirrorConsentRequests()
        requests.request()
        assertTrue(requests.pending.value)
        requests.resolve(declined = false)
        assertFalse(requests.pending.value)

        requests.request()
        assertTrue(requests.pending.value)
        requests.resolve(declined = true)
        requests.request()
        assertFalse(requests.pending.value)
    }
}
