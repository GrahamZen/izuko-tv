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
 * 解析器放在播放请求头里、给播放器 (与缓存下载) 看的提示. 它们读到后从发出去的请求头里去掉 ([strip]), 不会发给服务器.
 */
object PlaybackRequestHints {
    /**
     * 按多个连接并发分块下载这个地址, 值是连接数.
     *
     * 网盘直链 (夸克) 每个连接有速度上限, 一个连接常常跟不上视频码率, 几路并发才够.
     */
    const val PARALLEL_RANGE_HEADER = "X-Izuko-Parallel-Range"

    /**
     * 这个地址指向的文件内容的固定标识, 播放器按它把下过的数据存在本机磁盘上: 往回拖、拖动预览、再看同一个文件时从盘上读.
     * 直链会过期、每次取的都不一样, 不能拿地址当标识. 只和 [PARALLEL_RANGE_HEADER] 一起给.
     */
    const val CACHE_KEY_HEADER = "X-Izuko-Cache-Key"

    /** 去掉 [headers] 里的全部提示, 剩下的才是发给服务器的请求头. */
    fun strip(headers: Map<String, String>): Map<String, String> = headers - listOf(PARALLEL_RANGE_HEADER, CACHE_KEY_HEADER)
}
