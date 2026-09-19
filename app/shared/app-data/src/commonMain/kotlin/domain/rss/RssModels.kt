/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.rss

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.Unspecified
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.guessFromUrl
import me.him188.ani.datasources.api.topic.isVideoFileName
import me.him188.ani.datasources.api.topic.isVideoMimeType
import me.him188.ani.utils.xml.Element


// See  me.him188.ani.app.tools.rss.RssParserTest.dmhy
@Immutable
@Serializable // for testing
data class RssChannel(
    val title: String,
    val description: String = "",
    val link: String = "",
    val ttl: Int = 0,
    val items: List<RssItem>,
    /**
     * 原始 XML. 仅在测试时才有值, 其他时候为 `null` 以避免保持内存占用.
     */
    @Transient val origin: Element? = null,
)

@Immutable
@Serializable // for testing
data class RssItem(
    val title: String,
    val description: String = "",
    val pubDate: LocalDateTime?,
    val link: String,
    val guid: String,
    val enclosure: RssEnclosure?,
    /**
     * 原始 XML. 仅在测试时才有值, 其他时候为 `null` 以避免保持内存占用.
     */
    @Transient val origin: Element? = null,
)

fun RssItem.guessResourceLocation(): ResourceLocation? {
    val url = this.enclosure?.url ?: this.link.takeIf { it.isNotBlank() } ?: return null
    ResourceLocation.guessFromUrl(url)?.let { return it }
    // 有的源的视频直链不带扩展名 (例如 ".../foo?d=mp4"), 用 MIME 和标题里的文件名兜底
    if (!url.startsWith("http", ignoreCase = true)) return null
    this.enclosure?.type?.let { mimeType ->
        if (isVideoMimeType(mimeType)) return ResourceLocation.HttpStreamingFile(url)
    }
    if (isVideoFileName(this.title)) return ResourceLocation.HttpStreamingFile(url)
    return null
}

fun RssItem.getMediaSize(): FileSize {
    if (enclosure == null || enclosure.length <= 1L) {
        //有的源会返回 1
        return Unspecified
    }
    return enclosure.length.bytes
}

@Immutable
@Serializable // for testing
data class RssEnclosure(
    val url: String,
    val length: Long = 0,
    val type: String,// application/x-bittorrent
)
