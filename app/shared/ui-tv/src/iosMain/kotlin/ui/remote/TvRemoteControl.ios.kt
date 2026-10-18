/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.app.domain.search.SubjectSearchQuery

/** iOS 上没有 Web 控制台: 地址恒为 null, 什么都不发. */
actual object TvRemoteControl {
    actual val url: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    actual val phoneConnected: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    actual val knownHost: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    actual val hostChanged: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    actual val dialogVisible: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    internal actual val remoteNavigations: SharedFlow<Unit> = MutableSharedFlow<Unit>().asSharedFlow()
    actual var currentQueryProvider: (() -> SubjectSearchQuery)? = null
    actual var currentYearsProvider: (() -> List<Int>)? = null
    actual val submissions: SharedFlow<RemoteSearchSubmission> = MutableSharedFlow<RemoteSearchSubmission>().asSharedFlow()

    actual fun dismissDialog() {}

    actual fun acquire() {}

    actual fun release() {}

    actual fun refreshAddress() {}

    actual fun takePending(): RemoteSearchSubmission? = null

    internal actual fun registerSearchResults(source: RemoteSearchResultsSource) {}

    internal actual fun unregisterSearchResults(source: RemoteSearchResultsSource) {}

    actual fun resetAddress() {}

    actual fun showDialogOnLaunch(onHomePage: () -> Boolean) {}

    actual fun setShowOnLaunch(enabled: Boolean) {}

    internal actual fun tvVpnActive(): Boolean = false
}
