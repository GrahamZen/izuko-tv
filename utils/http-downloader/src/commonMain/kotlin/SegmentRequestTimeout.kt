/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import io.ktor.client.plugins.HttpTimeoutConfig

/**
 * 下一个分段的请求从发出到读完最多给多久 (毫秒).
 *
 * - 按字节范围下的分段: 不少于 client 默认的 5 分钟, 再按这一段的大小以 [SLOWEST_BYTES_PER_SECOND] 的速度算.
 *   网盘按连接限速时一条连接每秒十几二十 KB, 5 MB 的一段要四五分钟, 固定 5 分钟的话慢一点就到点从头重来, 永远下不完;
 *   但也不能不限: 挂在一条每秒只有几 KB 的连接上时, 到点断开重连才有机会换到正常的节点.
 * - 不带范围的分段 (整条下、m3u8 的分片) 不知道多大, 不限; 卡住由读超时管.
 */
internal fun segmentRequestTimeoutMillis(segment: SegmentInfo): Long {
    val start = segment.rangeStart
    val end = segment.rangeEnd
    if (start == null || end == null) return HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    return maxOf(MIN_SEGMENT_TIMEOUT_MILLIS, (end - start + 1) * 1000 / SLOWEST_BYTES_PER_SECOND)
}

private const val MIN_SEGMENT_TIMEOUT_MILLIS = 300_000L

/** 比这还慢的连接不值得等: 断开重连 */
private const val SLOWEST_BYTES_PER_SECOND = 5 * 1024L
