/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import kotlinx.io.RawSink
import me.him188.ani.utils.io.WriteBackCounter

/**
 * 打开一个每写满 [syncEveryBytes] 字节就 fsync 一次的文件 sink; 平台不支持则返回 null (调用方回落
 * 到普通 sink). 为什么要边写边落盘见 [WriteBackCounter].
 *
 * 曾经试过换 OkHttp 直接落盘 (堆分配 7.6x -> 1.0x), 实测**照样冻** —— GC 从来不是主因; 而自建 client
 * 会绕开 Ktor 的代理/Cookie/UA/重定向, 对代理用户是静默故障. 所以只换写文件这一步, 请求仍走 Ktor.
 */
expect fun openPeriodicSyncSink(absolutePath: String, syncEveryBytes: Long): RawSink?

/** 下载分段时的 fsync 间隔, 见 [WriteBackCounter.SYNC_EVERY_BYTES_DOWNLOAD]. */
const val SYNC_EVERY_BYTES_DOWNLOAD: Long = WriteBackCounter.SYNC_EVERY_BYTES_DOWNLOAD

/**
 * 合并 (拼接分段) 时的 fsync 间隔, 比下载放宽到默认的 [WriteBackCounter.SYNC_EVERY_BYTES].
 *
 * 拼接是纯磁盘顺序读写, 每 1MB 就同步等一次会让写盘完全串行 (413MB 实测 38 秒, 不 sync 是 20 秒).
 * 放到 4MB 让内核有余地流水 (实测 27.9 秒); 拼接期间没有别的写在抢, 别人的 fsync 最多等这 4MB.
 */
const val SYNC_EVERY_BYTES_MERGE: Long = WriteBackCounter.SYNC_EVERY_BYTES
