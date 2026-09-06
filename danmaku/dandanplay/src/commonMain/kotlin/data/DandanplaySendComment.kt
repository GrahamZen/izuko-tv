/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /api/v2/comment/{episodeId}/app` 的请求体 (开放弹幕网络).
 */
@Serializable
class DandanplaySendCommentRequest(
    /** 弹幕出现时间, 单位**秒** (注意不是毫秒). */
    val time: Double,
    /** 1 = 普通, 4 = 顶部, 5 = 底部. */
    val mode: Int,
    /** R*255*255 + G*255 + B (dandanplay 的算法, 不是常见的 R<<16). */
    val color: Int,
    /** 不能超过 100 个字符. */
    val comment: String,
    /** 发送者昵称, 由应用自己指定 (这条路不需要 dandanplay 账号). */
    val userName: String,
)

@Serializable
class DandanplaySendCommentResponse(
    /** 这条弹幕在该弹幕库里的 id; 出错时为 0. */
    val cid: Long = 0,
    @SerialName("errorCode") val errorCode: Int = 0,
    @SerialName("success") val success: Boolean = false,
    @SerialName("errorMessage") val errorMessage: String = "",
)
