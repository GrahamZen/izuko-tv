/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ios

import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.platform.BackgroundRefresh
import me.him188.ani.app.platform.IosBackgroundRefresh
import me.him188.ani.app.platform.IosSystemNotifier
import me.him188.ani.app.ui.notice.TvSystemNotices
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.resolve

/**
 * iOS 的系统通知与后台刷新 (见 TvSystemNotices、IosBackgroundRefresh): 设好通知代理、登记后台刷新的处理函数, 都要在应用启动完之前,
 * 所以在 startIosApp 里 (Koin 起来之后、界面组合之前) 调. 记录存在数据目录: 发过哪些系统通知、最近几次后台刷新.
 */
internal fun startIosSystemNotices(dataDir: SystemPath, scope: CoroutineScope) {
    IosSystemNotifier.install()
    BackgroundRefresh.install(dataDir.resolve("background-refresh.log"))
    TvSystemNotices.start(IosSystemNotifier, dataDir.resolve("system-notices.txt"))
    IosBackgroundRefresh.install(scope)
}
