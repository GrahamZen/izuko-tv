/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.topic

import kotlinx.serialization.Serializable


@Serializable
sealed class ResourceLocation {
    abstract val uri: String

    /**
     * BT 磁力链, 需要使用 BT 引擎下载.
     *
     * `magnet:?xt=urn:btih:...`
     */
    @Serializable
    data class MagnetLink(override val uri: String) : ResourceLocation() {
        init {
            require(uri.startsWith("magnet:")) {
                "MagnetLink uri must start with magnet:"
            }
        }
    }

    /**
     * 需要通过 HTTP 下载的 BT 种子文件. 得到种子文件后还需要通过 BT 引擎下载.
     *
     * `https://example.com/a.torrent`.
     */
    @Serializable
    data class HttpTorrentFile(override val uri: String) : ResourceLocation() {
        init {
            require(uri.startsWith("https://") || uri.startsWith("http://")) {
                "HttpTorrentFile uri must start with http:// or https://"
            }
        }
    }

    /**
     * 流式传输视频文件, 例如 m3u8
     * `*.mkv`, `*.mp4` form `http://`, `https://`.
     */
    @Serializable
    data class HttpStreamingFile(override val uri: String) : ResourceLocation() {
        init {
            require(
                uri.startsWith("https://") ||
                        uri.startsWith("http://") ||
                        uri.startsWith("file://"),
            ) {
                "HttpStreamingFile uri must start with 'http://' or 'https://', but was $uri"
            }
        }
    }

    /**
     * 需要 WebView 去里面解析视频链接
     */
    @Serializable
    data class WebVideo(
        /**
         * Web 页面地址
         */
        override val uri: String,
    ) : ResourceLocation() {
        init {
            require(uri.startsWith("https://") || uri.startsWith("http://")) {
                "WebVideo uri must start with 'http://' or 'https://', but was $uri"
            }
        }
    }

    /**
     * 本地文件路径
     */
    @Serializable
    data class LocalFile(
        val filePath: String, // absolute
        /**
         * Hint to help the player to determine the file type.
         *
         * `null` for unknown.
         */
        val fileType: FileType? = null,
        /**
         * m3u8 原始地址.
         */
        val originalUri: String? = null,
    ) : ResourceLocation() {
        /**
         * `file://`
         */
        override val uri: String by lazy {
            "file://${filePath}"
        }

        @Serializable
        enum class FileType {
            /**
             *  MPEG Transport Stream
             */
            MPTS,

            /**
             * Contained in a container format, such as MKV, MP4, etc.
             */
            CONTAINED,
        }
    }
}

@Suppress("HttpUrlsUsage")
fun ResourceLocation.Companion.guessTorrentFromUrl(uri: String): ResourceLocation? {
    val isHttp = uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)
    return when {
        uri.startsWith("magnet:") -> ResourceLocation.MagnetLink(uri)
        isHttp && (uri.endsWith(".torrent") || uri.contains("uploadbt.com")) -> ResourceLocation.HttpTorrentFile(uri)
        else -> null
    }
}

/**
 * 猜测 [uri] 指向的资源类型. 支持磁力链、种子文件以及 HTTP 视频直链 (mp4/mkv/m3u8 等).
 *
 * 与 [guessTorrentFromUrl] 的区别是本函数还会识别视频直链, 新数据源应当优先使用本函数.
 */
fun ResourceLocation.Companion.guessFromUrl(uri: String): ResourceLocation? {
    guessTorrentFromUrl(uri)?.let { return it }
    return guessHttpStreamingFromUrl(uri)
}

/**
 * 当 [uri] 是 HTTP(S) 视频直链时返回 [ResourceLocation.HttpStreamingFile], 否则返回 `null`.
 *
 * 判断依据是 URL path 的扩展名 (忽略 query 和 fragment). 有些站点的直链不带扩展名
 * (例如 `https://example.com/foo?d=mp4`), 此时调用方可以用 MIME ([isVideoMimeType])
 * 或文件名 ([isVideoFileName]) 等其他信息判断后自行构造.
 */
fun ResourceLocation.Companion.guessHttpStreamingFromUrl(uri: String): ResourceLocation? {
    if (!isHttpUrl(uri)) return null
    return if (isVideoFileName(uri.substringBefore('#').substringBefore('?'))) {
        ResourceLocation.HttpStreamingFile(uri)
    } else {
        null
    }
}

/**
 * [name] 是否为视频文件名 (按扩展名判断). 可用于从 RSS 标题等信息中判断资源类型.
 */
fun isVideoFileName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext.isNotEmpty() && ext in VIDEO_FILE_EXTENSIONS
}

/**
 * [mimeType] 是否为视频或 HLS 播放列表的 MIME type.
 */
fun isVideoMimeType(mimeType: String): Boolean {
    val type = mimeType.substringBefore(';').trim().lowercase()
    return type.startsWith("video/") || type in HLS_MIME_TYPES
}

@Suppress("HttpUrlsUsage")
private fun isHttpUrl(uri: String) =
    uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)

private val VIDEO_FILE_EXTENSIONS = setOf(
    "mp4", "mkv", "m3u8", "flv", "webm", "m4v", "mov", "avi", "ts", "mpg", "mpeg", "wmv", "rmvb", "rm", "3gp",
)

private val HLS_MIME_TYPES = setOf(
    "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
)
