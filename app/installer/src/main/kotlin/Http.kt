/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.os.Build
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 助手的全部网络请求: 系统自带的 HttpURLConnection (走系统代理), 不引第三方库. */
internal object Http {
    private val USER_AGENT =
        "IzukoInstaller/${BuildConfig.VERSION_CODE} (Android ${Build.VERSION.RELEASE}; +https://github.com/${BuildConfig.REPOSITORY})"

    fun open(
        url: String,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 15_000,
        followRedirects: Boolean = true,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = followRedirects
        setRequestProperty("User-Agent", USER_AGENT)
    }

    /** GET [url] 的正文; 非 2xx 抛 [HttpStatusException]. */
    fun getText(url: String, connectTimeoutMs: Int = 8_000, readTimeoutMs: Int = 15_000): String {
        val connection = open(url, connectTimeoutMs, readTimeoutMs)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}

/** 去掉协议与路径, 只留域名, 用在界面上说「从哪下的」. */
internal fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

class HttpStatusException(val code: Int) : IOException("HTTP $code")
