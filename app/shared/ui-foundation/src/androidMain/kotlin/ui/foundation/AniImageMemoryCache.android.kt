/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import kotlin.concurrent.thread

internal actual fun aniImageMemoryCacheSize(context: PlatformContext): Long? {
    val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    return imageMemoryCacheSizeForTotalMemory(memoryInfo.totalMem)
}

internal actual fun Sketch.clearMemoryCacheOnCriticalMemory(context: PlatformContext): () -> Unit {
    val appContext = context.applicationContext
    val callbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // Android 14 起前台不再收到这一级; 要救的正是 Android 10~12 的低内存电视
            @Suppress("DEPRECATION")
            if (level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return
            memoryCache.clear()
            // 像素在 native 内存里, 要等引用它的 Bitmap 对象被回收才还给系统; 空闲时不一定很快有 GC
            thread(name = "ImageMemoryTrim") { Runtime.getRuntime().gc() }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }
    appContext.registerComponentCallbacks(callbacks)
    return { appContext.unregisterComponentCallbacks(callbacks) }
}
