/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.bangumi.auth

class HttpBearerAuth(private val scheme: String?) : Authentication {
    var bearerToken: String? = null

    override fun apply(query: MutableMap<String, List<String>>, headers: MutableMap<String, String>) {
        val token: String = bearerToken ?: return
        headers["Authorization"] = (if (scheme != null) upperCaseBearer(scheme)!! + " " else "") + token
    }

    private fun upperCaseBearer(scheme: String): String? {
        return if ("bearer".equals(scheme, ignoreCase = true)) "Bearer" else scheme
    }
}
