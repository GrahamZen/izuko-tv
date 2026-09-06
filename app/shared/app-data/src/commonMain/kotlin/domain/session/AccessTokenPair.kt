/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * 捆绑 Bangumi 和 Ani 的 accessTokens, 简化授权逻辑
 */
data class AccessTokenPair(
    val aniAccessToken: String,
    val expiresAtMillis: Long,
    val bangumiAccessToken: String?,
) {
    override fun toString(): String {
        // 日志不打印 token
        return "AccessTokenPair(bangumiAccessToken.hashCode=${bangumiAccessToken.hashCode()}, aniAccessToken.hashCode=${aniAccessToken.hashCode()}, expiresAtMillis=$expiresAtMillis)"
    }
}

/** 提前这么久就把 token 当作过期, 避免交给服务器时已经失效 (403). */
private val EXPIRY_MARGIN = 1.hours

fun AccessTokenPair.isExpired(clock: Clock = Clock.System): Boolean {
    return expiresAtMillis <= clock.now().toEpochMilliseconds() + EXPIRY_MARGIN.inWholeMilliseconds
}

/** 离 [isExpired] 变成 `true` 还有多久; 已经过期时为 0. */
fun AccessTokenPair.timeUntilExpired(clock: Clock = Clock.System): Duration {
    return (expiresAtMillis - EXPIRY_MARGIN.inWholeMilliseconds - clock.now().toEpochMilliseconds())
        .coerceAtLeast(0).milliseconds
}
