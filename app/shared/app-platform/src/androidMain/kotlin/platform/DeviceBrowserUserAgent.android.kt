/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.content.ComponentCallbacks2
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.util.concurrent.atomic.AtomicBoolean

private val logger = logger("DeviceBrowserUserAgent")
private const val PREFS = "device_browser_user_agent"
private const val KEY_VERSION = "version"
private const val KEY_UA = "ua"

/**
 * 部分电视没有可用的 WebView, 取 UA 会抛异常, 此时返回 `null` 退回 client 自带的 UA.
 *
 * 取一次要初始化 WebView (加载它的原生库、起引擎), 低端电视上主线程会卡一秒到几秒, 而第一次要 UA 的正是起播时的数据源.
 * UA 只随系统与 WebView 的版本变, 所以算出来按「系统指纹 + WebView 包名与版本号」存下, 版本没变时直接读, 不碰 WebView;
 * 版本变了 (系统或 WebView 自动更新后) 先接着用存下的旧 UA, 等界面退到后台再算新的 (见 [StoredBrowserUserAgent]);
 * 只有刚装好、一次都没算过时才在要用的时候现算.
 *
 * 要算时切到主线程: 在别的线程上调, WebView 会把活交给主线程再干等它. 后台线程这样一等就占着协程线程池的一个线程,
 * 十几个数据源同时开搜时能把池子占满; 主线程这时若在等池子里的活 (界面同步读资源文案), 两边互等, 应用无响应.
 */
actual suspend fun Context.deviceBrowserUserAgent(): String? {
    val version = webViewVersionKey()
    val prefs = withContext(Dispatchers.IO) { getSharedPreferences(PREFS, MODE_PRIVATE) }
    when (val stored = storedBrowserUserAgent(prefs.getString(KEY_VERSION, null), prefs.getString(KEY_UA, null), version)) {
        is StoredBrowserUserAgent.Current -> return stored.userAgent
        is StoredBrowserUserAgent.Outdated -> {
            refreshWhenUiHidden(prefs)
            return stored.userAgent
        }

        StoredBrowserUserAgent.Missing -> Unit
    }
    val ua = withContext(Dispatchers.Main) { computeOnMainThread() }
    if (ua != null && version != null) prefs.store(version, ua)
    return ua
}

private fun Context.computeOnMainThread(): String? {
    val start = SystemClock.elapsedRealtime()
    return runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()?.takeIf { it.isNotBlank() }
        .also { logger.info { "Computed device browser UA on the main thread in ${SystemClock.elapsedRealtime() - start}ms" } }
}

private fun SharedPreferences.store(version: String, userAgent: String) {
    edit().putString(KEY_VERSION, version).putString(KEY_UA, userAgent).apply()
}

private val refreshScheduled = AtomicBoolean(false)

/**
 * 界面全部退到后台时 (系统在主线程上发 [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]) 重算一次存下: 这时主线程卡几秒用户看不见.
 * 这一进程里仍用旧的, 下次启动读到新的. 一直没退到后台就一直用旧的, 下次启动再排.
 */
private fun Context.refreshWhenUiHidden(prefs: SharedPreferences) {
    if (!refreshScheduled.compareAndSet(false, true)) return
    val app = applicationContext
    app.registerComponentCallbacks(
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
                app.unregisterComponentCallbacks(this)
                // 等这次回调返回再算, 不压在系统的回调里
                Handler(Looper.getMainLooper()).post {
                    val version = webViewVersionKey() ?: return@post
                    app.computeOnMainThread()?.let { prefs.store(version, it) }
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
            }
        },
    )
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
