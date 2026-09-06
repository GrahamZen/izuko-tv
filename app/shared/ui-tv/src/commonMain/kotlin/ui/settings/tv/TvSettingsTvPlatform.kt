/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.ui.diagnostics.TvPerfDiagnostics
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_perf_diag_checking
import me.him188.ani.app.ui.lang.tv_perf_diag_recording
import me.him188.ani.app.ui.onboarding.TvOnboardingGate
import me.him188.ani.app.ui.remote.TvBangumiRelayLogin
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.whatsnew.TvWhatsNewSettingsPage

/** 电视版给设置清单的东西 (见 [TvSettingsPlatform]). */
internal object TvSettingsTvPlatform : TvSettingsPlatform {
    override val consoleUrl: Flow<String?> get() = TvRemoteControl.url

    override val resetOnboarding: (Context) -> Unit get() = TvOnboardingGate::reset

    /** 录制时每半秒更新剩余秒数. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val perfStatus: Flow<TvText?>
        get() = TvPerfDiagnostics.status.flatMapLatest { status ->
            when (status) {
                is TvPerfDiagnostics.Status.Recording -> flow {
                    while (true) {
                        val left = ((status.endsAtMillis - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
                        emit(tvText(Lang.tv_perf_diag_recording, left))
                        delay(500)
                    }
                }

                TvPerfDiagnostics.Status.CheckingHealth -> flowOf(tvText(Lang.tv_perf_diag_checking))
                TvPerfDiagnostics.Status.Idle -> flowOf(null)
            }
        }

    override val perfLatest: Flow<String?> get() = TvPerfDiagnostics.latestHeadline

    override val startRelayLogin: () -> TvRelayLoginStart
        get() = {
            when (val start = TvBangumiRelayLogin.start()) {
                is TvBangumiRelayLogin.Start.Ready -> TvRelayLoginStart.Ready(start.url)
                TvBangumiRelayLogin.Start.Unsupported -> TvRelayLoginStart.Unsupported
                TvBangumiRelayLogin.Start.NoLan -> TvRelayLoginStart.NoLan
            }
        }

    override val whatsNewPage: @Composable (dismiss: () -> Unit) -> Unit
        get() = { dismiss -> TvWhatsNewSettingsPage(onDismiss = dismiss) }
}
