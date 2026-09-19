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

/**
 * 部分电视没有可用的 WebView, 取 UA 会抛异常, 此时返回 `null` 退回 client 自带的 UA.
 */
actual fun Context.deviceBrowserUserAgent(): String? =
    runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()?.takeIf { it.isNotBlank() }
