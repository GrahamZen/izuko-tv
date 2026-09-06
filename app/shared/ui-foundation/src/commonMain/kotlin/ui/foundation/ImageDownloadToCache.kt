/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.cache.CachePolicy
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.source.DataFrom
import com.github.panpf.sketch.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.utils.coroutines.IO_

/**
 * 只把 [url] 的原始字节落进下载缓存, 不解码; 返回数据从哪来 ([DataFrom.NETWORK] = 真走了网络, 磁盘上已有则是
 * [DataFrom.DOWNLOAD_CACHE]). 失败时抛出.
 *
 * 给「只要磁盘上有这份字节」的场合用 (背景图预热、离场卡片的补下): 显示端之后按自己的尺寸请求, 从下载缓存读.
 * 走普通图片请求的话哪怕 `size(1, 1)` 也要解码 —— JPEG 缩小解码照样把整张的熵编码过一遍, 渐进式尤甚: 索尼上 TMDB w1280
 * 一张约 30ms CPU, 还占着屏上卡片要用的解码队列 (实测这类 1×1 解码排队 0.8~1.2 秒, 同时在排的封面跟着晚出), 解出来的
 * 1×1 位图也没人用. `Sketch.executeDownload` 只取不解, 但返回值里没有来源 (背景图预热拿它给网速档位取样), 所以这里与它
 * 走同一条路 —— 同一个抓取器、同一把下载缓存锁 —— 只是把来源带回来.
 */
suspend fun Sketch.downloadToCache(context: PlatformContext, url: String): DataFrom =
    withContext(Dispatchers.IO_) {
        val request = ImageRequest(context, url) {
            downloadCachePolicy(CachePolicy.ENABLED)
        }
        val fetcher = components.newFetcherOrThrow(RequestContext(this@downloadToCache, request, Size.Empty))
        fetcher.fetch().getOrThrow().dataFrom
    }
