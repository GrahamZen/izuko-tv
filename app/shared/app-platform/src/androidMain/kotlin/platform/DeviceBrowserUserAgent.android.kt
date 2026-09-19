/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.content.Context.MODE_PRIVATE
import android.os.Build
import android.os.SystemClock
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

private val logger = logger("DeviceBrowserUserAgent")
private const val PREFS = "device_browser_user_agent"
private const val KEY_VERSION = "version"
private const val KEY_UA = "ua"

/**
 * 部分电视没有可用的 WebView, 取 UA 会抛异常, 此时返回 `null` 退回 client 自带的 UA.
 *
 * 取一次要初始化 WebView (加载它的原生库、起引擎), 低端电视上主线程会卡几百毫秒到一秒多, 而第一次要 UA 的正是起播时的数据源.
 * UA 只随系统与 WebView 的版本变, 所以算出来按「系统指纹 + WebView 包名与版本号」存下, 版本没变时直接读, 不碰 WebView;
 * 只有刚装好、系统或 WebView 更新后的第一次才真的去算.
 *
 * 要算时切到主线程: 在别的线程上调, WebView 会把活交给主线程再干等它. 后台线程这样一等就占着协程线程池的一个线程,
 * 十几个数据源同时开搜时能把池子占满; 主线程这时若在等池子里的活 (界面同步读资源文案), 两边互等, 应用无响应.
 */
actual suspend fun Context.deviceBrowserUserAgent(): String? {
    val version = webViewVersionKey()
    val prefs = withContext(Dispatchers.IO) { getSharedPreferences(PREFS, MODE_PRIVATE) }
    if (version != null && prefs.getString(KEY_VERSION, null) == version) {
        prefs.getString(KEY_UA, null)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    val ua = withContext(Dispatchers.Main) {
        val start = SystemClock.elapsedRealtime()
        runCatching { WebSettings.getDefaultUserAgent(this@deviceBrowserUserAgent) }.getOrNull()?.takeIf { it.isNotBlank() }
            .also { logger.info { "Computed device browser UA on the main thread in ${SystemClock.elapsedRealtime() - start}ms" } }
    }
    if (ua != null && version != null) {
        prefs.edit().putString(KEY_VERSION, version).putString(KEY_UA, ua).apply()
    }
    return ua
}

/**
 * 系统指纹 + 当前 WebView 的包名与版本号. 只问系统服务, 不加载 WebView; 拿不到 (Android 8.0 以前 / 没有 WebView) 时为 null, 那就不存.
 */
private fun webViewVersionKey(): String? {
    if (Build.VERSION.SDK_INT < 26) return null
    val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull() ?: return null
    @Suppress("DEPRECATION")
    val versionCode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
    return "${Build.FINGERPRINT}|${pkg.packageName}|$versionCode"
}
