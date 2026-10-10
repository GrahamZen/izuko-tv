/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 网盘视频的平均码率 (文件大小 ÷ 网盘给的时长, 见 [DriveFileFields.duration]), 按资源 id 记下, 选源列表显示用.
 *
 * 资源 ([me.him188.ani.datasources.api.Media]) 里没有放码率的字段, 所以另记一份. 只在内存里: 网盘资源每次搜索都重新生成、重新登记;
 * 网盘没给时长的文件 (协议没配时长字段、用户挑过的文件) 不登记, 列表里就不显示码率.
 */
object DriveVideoBitrates {
    private val lock = SynchronizedObject()
    private val bitrates = LinkedHashMap<String, Long>()

    /** 记下资源 [mediaId] (网盘文件 [file]) 的平均码率. */
    fun record(mediaId: String, file: DriveFile) {
        if (file.size <= 0 || file.durationSeconds <= 0) return
        val bitsPerSecond = file.size * 8 / file.durationSeconds
        synchronized(lock) {
            bitrates.remove(mediaId)
            bitrates[mediaId] = bitsPerSecond
            while (bitrates.size > MAX_ENTRIES) bitrates.remove(bitrates.keys.first())
        }
    }

    /** 资源 [mediaId] 的平均码率 (bit/s), 没登记时为 null. */
    fun of(mediaId: String): Long? = synchronized(lock) { bitrates[mediaId] }

    /** 显示用的码率 (`8.1 Mbps`, 不到 1 Mbps 的写 `850 kbps`), 没登记时为 null. */
    fun label(mediaId: String): String? = of(mediaId)?.let { format(it) }

    fun format(bitsPerSecond: Long): String =
        if (bitsPerSecond >= 1_000_000) {
            val tenths = (bitsPerSecond + 50_000) / 100_000
            "${tenths / 10}.${tenths % 10} Mbps"
        } else {
            "${(bitsPerSecond + 500) / 1000} kbps"
        }

    /** 最多记多少条, 旧的先丢: 一次搜索几十到几百条, 留够几部番的量. */
    private const val MAX_ENTRIES = 5000
}
