/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isIos

/**
 * 响应里的 `Set-Cookie`, 一条 Cookie 一项. 读 Cookie 的地方都用它, 不直接 `headers.getAll(HttpHeaders.SetCookie)`:
 * iOS 的网络库 (Ktor 的 Darwin 引擎从 NSHTTPURLResponse.allHeaderFields 取头) 把同名的几个头并成一行、用逗号连起来, 直接读只认得出第一条.
 */
fun HttpResponse.setCookieHeaders(): List<String> = splitSetCookieHeaders(headers.getAll(HttpHeaders.SetCookie).orEmpty())

/**
 * 把并成一行的 `Set-Cookie` 拆回一条一项: 按「逗号后面紧跟 `名字=`」拆 (Expires 日期里的逗号后面是空格和日期, 不拆).
 * 只有 iOS 的网络库会并成一行 ([joined]); 别的平台一个头一条, 值里的逗号不拆.
 */
fun splitSetCookieHeaders(values: List<String>, joined: Boolean = setCookieHeadersJoined): List<String> =
    if (joined) values.flatMap { it.split(SET_COOKIE_JOINED) } else values

/** 本平台的网络库会不会把同名的几个 `Set-Cookie` 并成一行. */
val setCookieHeadersJoined: Boolean get() = currentPlatform().isIos()

private val SET_COOKIE_JOINED = Regex(""",\s*(?=[^;,\s=]+=)""")
