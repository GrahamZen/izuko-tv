/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 部分电视没有可用的 WebView, 取 UA 会抛异常, 此时返回 `null` 退回 client 自带的 UA.
 *
 * 切到主线程取: 在别的线程上调, WebView 会把活交给主线程再干等它. 后台线程这样一等就占着协程线程池的一个线程,
 * 十几个数据源同时开搜时能把池子占满; 主线程这时若在等池子里的活 (界面同步读资源文案), 两边互等, 应用无响应.
 */
actual suspend fun Context.deviceBrowserUserAgent(): String? = withContext(Dispatchers.Main) {
    runCatching { WebSettings.getDefaultUserAgent(this@deviceBrowserUserAgent) }.getOrNull()?.takeIf { it.isNotBlank() }
}
