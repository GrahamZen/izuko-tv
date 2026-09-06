/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.user

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

/**
 * @since 5.0
 */
@Serializable
data class SelfInfo(
    /**
     * bangumi 的用户 id (`/p1/me` 的 `id`).
     *
     * 直连之前这里是 Ani 自己的用户 UUID. 存档里旧值解析不了正好 —— selfInfoStore 配了
     * `ReplaceFileCorruptionHandler { null }`, 解析失败即当没缓存, 登录后重新拉一次.
     */
    val id: Int,
    val nickname: String,
    val email: String?,
    val hasPassword: Boolean,
    val avatarUrl: String?,
    val bangumiUsername: String?,
    val isBangumiSessionValid: Boolean? = null,
)

data class SelfInfoDisplay(
    val title: String,
    val subtitle: String, // can be ""
)

/**
 * 计算用于显示的昵称和 email.
 */
@Stable
fun SelfInfo?.calculateDisplay(): SelfInfoDisplay {
    val selfInfo = this
    if (selfInfo == null) {
        return SelfInfoDisplay("加载中...", "") // placeholder, no need to be localized
    }

    if (selfInfo.nickname.isNotEmpty()) {
        return SelfInfoDisplay(selfInfo.nickname, selfInfo.email ?: "")
    }

    // 如果没有昵称, 则使用 email 作为显示名
    return (selfInfo.email ?: selfInfo.bangumiUsername)?.let { SelfInfoDisplay(it, "") }
        ?: SelfInfoDisplay(selfInfo.id.toString(), "")
}
