/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.mediasource.web.LoadedPage
import me.him188.ani.app.domain.mediasource.web.captcha.BrowserCookie
import me.him188.ani.app.domain.mediasource.web.captcha.CaptchaBrowser
import me.him188.ani.app.domain.mediasource.web.captcha.InterceptDecision
import me.him188.ani.app.domain.mediasource.web.captcha.TvWebInputMode
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager.Stage
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager.State

class BangumiOAuthStageTest {
    @Test
    fun `in-app browser is opening until it is created`() {
        assertEquals(Stage.OpeningBrowser, State.Authorizing("https://bgm.tv/oauth/authorize", browser = null).stage)
        assertEquals(
            Stage.AwaitingAuthorization,
            State.Authorizing("https://bgm.tv/oauth/authorize", browser = NoopBrowser).stage,
        )
    }

    @Test
    fun `system browser waits for authorization right away`() {
        assertEquals(
            Stage.AwaitingAuthorization,
            State.Authorizing("https://bgm.tv/oauth/authorize", browser = null, viaExternalBrowser = true).stage,
        )
    }

    @Test
    fun `exchanging has its own stage and finished states have none`() {
        assertEquals(Stage.Exchanging, State.Exchanging.stage)
        assertNull(State.Idle.stage)
        assertNull(State.NotConfigured.stage)
        assertNull(State.Success.stage)
        assertNull(State.Failed(LoadError.UnknownError(null)).stage)
    }

    private object NoopBrowser : CaptchaBrowser {
        override val userAgent: String get() = "test"
        override val pageLoads: SharedFlow<LoadedPage> = MutableSharedFlow()
        override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun navigate(url: String) {}
        override suspend fun currentPage(): LoadedPage? = null
        override suspend fun executeJavaScript(script: String) {}
        override suspend fun collectCookies(urls: List<String>): List<BrowserCookie> = emptyList()
        override fun setResourceInterceptor(handler: ((url: String) -> InterceptDecision)?) {}

        @Composable
        override fun View(
            modifier: Modifier,
            onExitRequest: (() -> Unit)?,
            onConfirmRequest: (() -> Unit)?,
            tvInputMode: TvWebInputMode,
        ) {
        }

        override fun close() {}
    }
}
