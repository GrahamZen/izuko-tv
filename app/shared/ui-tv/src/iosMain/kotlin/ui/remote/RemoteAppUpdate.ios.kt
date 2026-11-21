/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.app.ui.foundation.widgets.Toaster

/** iOS 上装不了安装包 (测试包是 APK, 见 pickTestPackageUrl 在 iOS 上不挑): 一直空闲, 什么都不做. */
internal actual object RemoteAppUpdate {
    actual val status: StateFlow<AppInstallStatus> = MutableStateFlow(AppInstallStatus.Idle).asStateFlow()

    actual val playerShowing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    actual suspend fun installTestPackage(url: String, sha256: String?, issue: Int, toaster: Toaster) {}

    actual fun cancel() {}

    actual fun retryInstall(): Boolean = false
}
