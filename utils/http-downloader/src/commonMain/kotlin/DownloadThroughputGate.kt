/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

/**
 * 一个 [KtorHttpDownloader] 上所有下载共用的总吞吐闸门, 每读一块数据之前要一次; 需要让路时挂起 (暂停或限速),
 * 例如播放时给播放让出网速. 与每个下载自己的 [DownloadOptions.maxBytesPerSecond] 叠加.
 */
fun interface DownloadThroughputGate {
    /** 读 [bytes] 个字节之前调用. 不让读时挂起到可以读为止; 可被取消. */
    suspend fun acquire(bytes: Int)
}
