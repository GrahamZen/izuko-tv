/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.delay

/**
 * 最近解好的几张整屏模糊底 (按背景图地址, 各档模糊分开记), 海报墙 hero 态那一层与详情页共用. 两边默认的模糊档不同 (海报墙更糊,
 * 见 tvBackdropBlurSpec), 解出来的不是同一张: 详情页那一档还没解好时先铺这里同一部的那张, 自己那张解好再交叉换过去 ——
 * 进页第一帧就有底, 不先露一下底色. 只在主线程 (组合、Sketch 回调) 里读写.
 */
internal object TvBlurredBackdropMemo {
    private val entries = ArrayList<Pair<String, TvBlurredBackdropEntry>>()

    fun put(url: String, entry: TvBlurredBackdropEntry) {
        entries.removeAll { it.first == url && it.second.spec == entry.spec }
        entries.add(url to entry)
        while (entries.size > TV_BLURRED_BACKDROP_MEMO_SIZE) entries.removeAt(0)
    }

    /** [url] 按 [spec] 解的那张; null = 没记着. */
    fun get(url: String, spec: TvBackdropBlurSpec): TvBlurredBackdropEntry? =
        entries.lastOrNull { it.first == url && it.second.spec == spec }?.second

    /** [url] 最近解好的那张 (哪一档都行); null = 没记着. */
    fun latest(url: String): TvBlurredBackdropEntry? = entries.lastOrNull { it.first == url }?.second
}

/** 一张解好的模糊小图 (按 [spec] 解) 与量好的亮度 (浅色字 / 深色字各自最难看清那一处, 见 tvBackdropWorstLuminance). */
@Immutable
internal class TvBlurredBackdropEntry(
    val bitmap: ImageBitmap,
    val spec: TvBackdropBlurSpec,
    val worstForLightText: Float,
    val worstForDarkText: Float,
)

/** 记几张: 海报墙停着的那部与刚进的详情页各一两档就够 (小图最大约 0.9MB). */
private const val TV_BLURRED_BACKDROP_MEMO_SIZE = 4

/**
 * 预取: [url] 按 [spec] (详情页那一档, 见 tvDetailsBackdropBlurSpec) 解好放进 [TvBlurredBackdropMemo], 进这部的详情页第一帧就是它自己那张,
 * 不用先垫海报墙那张再交叉换过去. 平板 / iPad 的海报墙 (hero 态或轮播) 停在一部上时调; 停够 [TV_DETAILS_BACKDROP_PREFETCH_DELAY_MILLIS]
 * 才解, 调用方换走时取消. 已记着就不解. 原图在下载缓存里 (hero 正铺着它), 只花一次缩图解码与模糊. 只在主线程调 (同 [TvBlurredBackdropMemo]).
 */
internal suspend fun tvPrefetchBlurredBackdrop(
    sketch: Sketch,
    context: PlatformContext,
    url: String,
    widthPx: Int,
    heightPx: Int,
    spec: TvBackdropBlurSpec,
) {
    if (widthPx <= 0 || heightPx <= 0 || TvBlurredBackdropMemo.get(url, spec) != null) return
    delay(TV_DETAILS_BACKDROP_PREFETCH_DELAY_MILLIS)
    val image = tvFetchBlurredBackdrop(sketch, context, url, widthPx, heightPx, spec) ?: return
    TvBlurredBackdropMemo.put(url, TvBlurredBackdropEntry(image.bitmap, spec, image.worstForLightText, image.worstForDarkText))
}

/** 预取前在一部上停多久 (毫秒): 划过去的不解. */
private const val TV_DETAILS_BACKDROP_PREFETCH_DELAY_MILLIS = 300L
