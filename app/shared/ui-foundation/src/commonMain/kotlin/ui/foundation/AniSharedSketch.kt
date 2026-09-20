/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.github.panpf.sketch.LocalPlatformContext
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.platform.files
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.ScopedHttpClient
import okio.Path
import okio.Path.Companion.toPath

/**
 * **进程级共享的 [Sketch] 实例.**
 *
 * 同一进程里有两个地方要图片加载器: 主应用的组合树, 以及 TV 屏保服务 ([AniDreamService], 它在
 * 没有 Activity 的情况下自己起一套组合). 两个实例指向**同一个磁盘缓存目录**时, 两份 LRU journal
 * 并发读写会把缓存写坏 (coil 时代实测过), 而分开目录就意味着屏保下过的 backdrop 主应用命不中,
 * 同一张图要下两遍 —— 电视上这两件事都很贵.
 *
 * 共享同一个实例把两个问题一起解决. 代价是**不能关闭**: 上游的 `rememberAniSketchInstance` 会在
 * 组合销毁时 `shutdown()`, 对共享实例来说那是错的 (屏保可能正在用它), 所以这里不挂
 * `DisposableEffect` —— 生命周期跟进程走, 进程死了自然回收.
 */
private val sharedSketchLock = SynchronizedObject()
private var sharedSketch: Sketch? = null

/**
 * 取进程级共享的 [Sketch]; 第一次调用时按 [appCacheDirectory] 建好.
 *
 * @param appCacheDirectory 应用缓存根目录 (图片缓存会落在它下面的子目录, 见 [ANI_IMAGE_CACHE_DIRECTORY]).
 */
fun aniSharedSketch(
    context: PlatformContext,
    client: ScopedHttpClient,
    appCacheDirectory: Path,
): Sketch = synchronized(sharedSketchLock) {
    sharedSketch ?: run {
        cleanUpLegacyCoilDiskCacheAsync(appCacheDirectory)
        createDefaultSketch(
            context = context,
            client = client,
            cacheDirectory = appCacheDirectory.resolve(ANI_IMAGE_CACHE_DIRECTORY),
        )
    }.also { sharedSketch = it }
}

/** [aniSharedSketch] 的组合版: 供应用根部 provide 给 [LocalSketch]. */
@Composable
fun rememberAniSharedSketch(client: ScopedHttpClient): Sketch {
    val context = LocalPlatformContext.current
    val appCacheDirectory = LocalContext.current.files.cacheDir.absolutePath.toPath()
    return remember(context, client, appCacheDirectory) {
        aniSharedSketch(context, client, appCacheDirectory)
    }
}
