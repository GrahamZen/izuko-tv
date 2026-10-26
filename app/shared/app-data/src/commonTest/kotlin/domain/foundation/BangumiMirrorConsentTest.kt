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
import me.him188.ani.app.data.models.preference.BangumiRouteKind
import me.him188.ani.app.domain.foundation.BangumiMirrorConsent.Question
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 已登录的人改成只剩第三方镜像前要不要问 ([BangumiMirrorConsent]). 自动切换时的询问经 SettingAdjustments.
 *
 * 用旧版的「连接方式」造设置 (见 [BangumiEndpointSettings.mode]): 「用镜像」= 关掉官方, 开着加密直连与镜像.
 * 平台不支持加密直连时就只剩镜像, 与旧版一样要问; 支持的话凭证走加密直连, 不用问.
 */
class BangumiMirrorConsentTest {
    private fun s(mode: BangumiEndpointMode, cred: Boolean = false) =
        BangumiEndpointSettings(mode = mode, allowCredentialsViaMirror = cred)

    @Test
    fun `没登录 — 怎么改都不问`() {
        for (from in BangumiEndpointMode.entries) {
            assertNull(BangumiMirrorConsent.check(s(from), s(BangumiEndpointMode.MIRROR), loggedIn = false, echAvailable = false))
        }
    }

    @Test
    fun `登录着改成用镜像、凭证不经过镜像 — 问`() {
        for (from in listOf(BangumiEndpointMode.AUTO, BangumiEndpointMode.DIRECT, BangumiEndpointMode.CUSTOM)) {
            assertEquals(Question.SwitchToMirror, BangumiMirrorConsent.check(s(from), s(BangumiEndpointMode.MIRROR), loggedIn = true, echAvailable = false))
        }
    }

    @Test
    fun `登录着改成用镜像、凭证已经允许经过镜像 — 不问`() {
        assertNull(BangumiMirrorConsent.check(s(BangumiEndpointMode.AUTO, cred = true), s(BangumiEndpointMode.MIRROR, cred = true), loggedIn = true, echAvailable = false))
    }

    @Test
    fun `登录着用镜像时关掉凭证 — 提醒`() {
        assertEquals(
            Question.CredentialsOff,
            BangumiMirrorConsent.check(s(BangumiEndpointMode.MIRROR, cred = true), s(BangumiEndpointMode.MIRROR), loggedIn = true, echAvailable = false),
        )
    }

    @Test
    fun `改成不经第三方镜像的几档 — 不问`() {
        for (to in listOf(BangumiEndpointMode.AUTO, BangumiEndpointMode.DIRECT, BangumiEndpointMode.CUSTOM)) {
            assertNull(BangumiMirrorConsent.check(s(BangumiEndpointMode.MIRROR, cred = true), s(to), loggedIn = true, echAvailable = false))
        }
    }

    @Test
    fun `自动切换 — 官方连不上时用镜像要改成用镜像，登录着且凭证不经过镜像才问`() {
        val auto = s(BangumiEndpointMode.AUTO)
        assertEquals(Question.SwitchToMirror, BangumiMirrorConsent.check(auto, auto.afterOriginUnreachable(), loggedIn = true, echAvailable = false))
        val allowed = s(BangumiEndpointMode.AUTO, cred = true)
        assertNull(BangumiMirrorConsent.check(allowed, allowed.afterOriginUnreachable(), loggedIn = true, echAvailable = false))
        assertNull(BangumiMirrorConsent.check(auto, auto.afterOriginUnreachable(), loggedIn = false, echAvailable = false))
    }

    @Test
    fun `能加密直连 — 关掉官方后凭证走加密直连，不问`() {
        val auto = s(BangumiEndpointMode.AUTO)
        assertNull(BangumiMirrorConsent.check(auto, s(BangumiEndpointMode.MIRROR), loggedIn = true, echAvailable = true))
        assertNull(BangumiMirrorConsent.check(auto, auto.afterOriginUnreachable(), loggedIn = true, echAvailable = true))
    }

    @Test
    fun `能加密直连但把它也关了 — 只剩镜像，问`() {
        val auto = s(BangumiEndpointMode.AUTO)
        val target = auto.withRoute(BangumiRouteKind.ORIGIN, false).withRoute(BangumiRouteKind.ECH, false)
        assertEquals(Question.SwitchToMirror, BangumiMirrorConsent.check(auto, target, loggedIn = true, echAvailable = true))
    }

    @Test
    fun `开着填好了的自建地址 — 凭证走自建，不问`() {
        val auto = s(BangumiEndpointMode.AUTO).copy(customBaseUrl = "my.example")
        val target = auto.withRoute(BangumiRouteKind.ORIGIN, false).withRoute(BangumiRouteKind.CUSTOM, true)
        assertNull(BangumiMirrorConsent.check(auto, target, loggedIn = true, echAvailable = false))
        // 地址没填好等于没开
        val broken = target.copy(customBaseUrl = "not a host")
        assertEquals(Question.SwitchToMirror, BangumiMirrorConsent.check(auto, broken, loggedIn = true, echAvailable = false))
    }
}
