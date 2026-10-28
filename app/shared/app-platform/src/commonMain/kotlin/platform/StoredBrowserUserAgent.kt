/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

/**
 * 存下的本机浏览器 UA 这次怎么用. 存的是 UA 与算出它时的版本 (系统指纹 + WebView 包名与版本号).
 *
 * 算 UA 要在主线程上把 WebView 加载起来, 系统或 WebView 刚更新完的第一次最慢 (索尼上 4.4 s),
 * 而第一个要 UA 的正是起播时开搜的数据源. 版本变了时新旧 UA 只差浏览器版本号, 站点那边没有区别,
 * 所以先接着用旧的, 新的留到界面不在前台时再算 ([Outdated]).
 */
internal sealed interface StoredBrowserUserAgent {
    /** 版本没变: 直接用. */
    data class Current(val userAgent: String) : StoredBrowserUserAgent

    /** 系统或 WebView 更新过: 先用这个旧的, 再找界面不在前台的时候重算. */
    data class Outdated(val userAgent: String) : StoredBrowserUserAgent

    /** 没存过, 或拿不到当前版本: 现在就算. */
    data object Missing : StoredBrowserUserAgent
}

/**
 * @param storedVersion 存 UA 时的版本
 * @param currentVersion 现在的版本, 拿不到时为 `null`
 */
internal fun storedBrowserUserAgent(
    storedVersion: String?,
    storedUserAgent: String?,
    currentVersion: String?,
): StoredBrowserUserAgent {
    val userAgent = storedUserAgent?.takeIf { it.isNotBlank() } ?: return StoredBrowserUserAgent.Missing
    if (currentVersion == null) return StoredBrowserUserAgent.Missing
    return if (storedVersion == currentVersion) {
        StoredBrowserUserAgent.Current(userAgent)
    } else {
        StoredBrowserUserAgent.Outdated(userAgent)
    }
}
