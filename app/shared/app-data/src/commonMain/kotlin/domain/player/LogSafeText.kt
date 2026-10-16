/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import me.him188.ani.datasources.api.matcher.WebViewConfig
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData

/*
 * 写进日志的描述: 地址照写, 请求头与 Cookie 只写名字不写值.
 * 请求头里有网盘登录的 Cookie、站点的会话, 用户把 app.log 发出来排查问题时不该带出去; 这些类自己的 toString 会把值全写出来.
 */

/** 媒体的日志描述: 地址、请求头的名字、外挂字幕的个数. */
fun MediaData.toLogText(): String = when (this) {
    is UriMediaData -> buildString {
        append("UriMediaData(uri=").append(uri)
        append(", headers=").append(headers.keys)
        val subtitles = extraFiles.subtitles.size
        if (subtitles > 0) append(", subtitles=").append(subtitles)
        append(')')
    }

    else -> toString()
}

/** 网页解析配置的日志描述: Cookie 只写名字. */
fun WebViewConfig.toLogText(): String =
    "WebViewConfig(cookies=${cookies.map { it.substringBefore('=').trim() }})"
