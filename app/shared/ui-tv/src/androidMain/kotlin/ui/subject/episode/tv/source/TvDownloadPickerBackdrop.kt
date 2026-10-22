/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import com.github.panpf.sketch.Sketch
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBlurredBackdropEntry
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropBlurSpec
import me.him188.ani.app.ui.subject.details.layout.loadTvBlurredBackdrop

/**
 * 按下载时背景图还没好, 选源面板最多再等这么久才出来 (期间停在缓存页上, 不先变黑). 等不到就先出面板, 背后是压暗的缓存页, 图好了再淡入.
 */
internal val TV_DOWNLOAD_PICKER_BACKDROP_WAIT = 300.milliseconds

/**
 * 缓存页选源背后的模糊背景 (见 [TvDownloadSourcePanel]): 这部番横版背景图的模糊版, 只记最近一部.
 *
 * 程度用详情页那一档: 详情页一进来就在后台解好了同一张图 (TvDetailsBlurredBackdrop, 地址、尺寸、程度相同就是同一个内存缓存键),
 * 从详情页进的缓存页直接命中. 缓存页一打开就开始准备 ([PrepareTvDownloadPickerBackdrop]), 按下载时多半已经好了;
 * 解的协程挂在进程级作用域, 离开页面不取消, 换了一部才取消上一部的.
 * 图地址只取已经解析过的 (热表 / 磁盘缓存), 不为它另发请求; 没有就只铺底色.
 */
internal object TvDownloadPickerBackdrop {
    private val scope = MainScope()
    private var pending: Pair<Key, Deferred<Unit>>? = null

    data class Key(val subjectId: Int, val size: IntSize, val spec: TvBackdropBlurSpec)

    /** 准备好的那一部. [image] 为 null = 这部没有可用的横版图. */
    class Ready(val key: Key, val image: TvBlurredBackdropEntry?)

    var ready: Ready? by mutableStateOf(null)
        private set

    fun prepare(key: Key, sketch: Sketch, context: Context): Deferred<Unit> {
        pending?.let { (current, job) ->
            if (current == key) return job
            job.cancel()
        }
        val job = scope.async {
            if (ready?.key == key) return@async
            val tmdb = GlobalKoin.get<TmdbImageService>()
            val url = tmdb.peekBackdropUrl(key.subjectId) ?: tmdb.peekCachedBackdropUrl(key.subjectId)
            val image = url?.let { loadTvBlurredBackdrop(sketch, context, it, key.size, key.spec) }
            ready = Ready(key, image)
        }
        pending = key to job
        return job
    }
}

/** 这一部的选源背景按什么解: 整屏尺寸 + 详情页那一档模糊. 窗口还没量出来时 null. */
@Composable
internal fun rememberTvDownloadPickerBackdropKey(subjectId: Int?): TvDownloadPickerBackdrop.Key? {
    val size = LocalWindowInfo.current.containerSize
    val spec = tvBackdropBlurSpec(LocalThemeSettings.current.tvDetailsBackdropBlur)
    if (subjectId == null || size.width <= 0 || size.height <= 0) return null
    return TvDownloadPickerBackdrop.Key(subjectId, size, spec)
}

/**
 * 缓存页 (单独的条目缓存页与缓存总页的右栏) 打开时调用: 提前准备这一部的选源背景, 按下载时不用再等.
 * 经 LocalTvDownloadPickerPrewarm 注入.
 */
@Composable
fun PrepareTvDownloadPickerBackdrop(subjectId: Int) {
    val key = rememberTvDownloadPickerBackdropKey(subjectId) ?: return
    val sketch = LocalSketch.current
    val context = LocalContext.current.applicationContext
    LaunchedEffect(key) { TvDownloadPickerBackdrop.prepare(key, sketch, context) }
}
