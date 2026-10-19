/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.cache

/**
 * 「边下边播」存在本机的视频数据. 只有安卓有 (Koin 里注册); 其他平台没有, 设置页不显示这一项.
 */
interface PlaybackDiskCacheStorage {
    /** 已占用与此刻的上限. */
    suspend fun usage(): PlaybackDiskCacheUsage

    /** 删掉存下的全部数据. 正在播放的视频照常播, 之后要用的部分重新下载. */
    suspend fun clear()
}

/**
 * @property usedBytes 已占用的字节数
 * @property maxBytes 此刻的上限 (随剩余空间变); 0 = 剩余空间不够, 不存
 */
data class PlaybackDiskCacheUsage(
    val usedBytes: Long,
    val maxBytes: Long,
)
