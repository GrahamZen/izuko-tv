/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import me.him188.ani.app.domain.session.AccessTokenPair

/**
 * 一次授权 (或刷新) 的结果.
 *
 * 直连 bangumi 之前这里还有一整套走 Ani 服务器的东西 (拿注册/绑定链接、按 requestId 轮询结果),
 * 那是因为 secret 在服务端、客户端只能等它换完 token. 现在 secret 随包发, 客户端自己换,
 * 所以只剩这个结果模型. 换/刷新见 [BangumiOAuthClient].
 */
data class OAuthResult(
    val tokens: AccessTokenPair,
    val expiresInSeconds: Long,
    val refreshToken: String,
)
