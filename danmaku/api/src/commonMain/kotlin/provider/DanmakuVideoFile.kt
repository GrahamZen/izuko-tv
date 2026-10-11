/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.api.provider

import kotlin.time.Duration

/** 这一集正在播放的视频文件, 弹幕源按文件匹配时用. */
class DanmakuVideoFile(
    val filename: String,
    val fileHash: String?,
    val fileSize: Long?,
    val videoDuration: Duration,
)

/**
 * 按文件匹配用的视频文件: 请求里带着文件名时就是它; 否则等 [DanmakuFetchRequest.awaitVideoFile] (播放器打开这一集的视频之后才有).
 * 两样都没有为 `null`.
 */
suspend fun DanmakuFetchRequest.videoFileForMatching(): DanmakuVideoFile? {
    filename?.let { return DanmakuVideoFile(it, fileHash, fileSize, videoDuration) }
    return awaitVideoFile?.invoke()
}
