/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.hls

/*
 * 伪装成图片的 HLS 分片: 不少站把 TS 分片放在图床上, 地址是 `….png` / `….jpg`, 内容是一张小图 (常见 1×1 的 PNG) 后面接着 TS 流.
 * ExoPlayer 与 FFmpeg 读 TS 时会往后找同步字节, 能放; AVPlayer 认不出, 报 -11850 / -11828.
 * 开了 [HlsPlaybackOptions.unwrapDisguisedSegments] 时, 这类播放列表的分片经本地代理转发, 转发时去掉 TS 前面的图片 ([unwrapDisguisedTs]).
 */

/** [playlist] 的分片要不要经本地代理: 要求代理分片或转发请求头, 或者开了去伪装且有分片地址像图片. */
internal fun HlsPlaybackOptions.proxiesSegmentsOf(playlist: String): Boolean =
    proxySegments || forwardRequestHeaders || (unwrapDisguisedSegments && playlist.hasImageLikeHlsSegments())

/** 媒体播放列表里有没有地址像图片的分片 (只看非注释行). */
internal fun String.hasImageLikeHlsSegments(): Boolean =
    lineSequence().any { line -> line.isNotBlank() && !line.startsWith("#") && line.trim().isImageLikeHlsUri() }

/** 地址的路径 (去掉查询与片段) 以图片扩展名结尾. */
internal fun String.isImageLikeHlsUri(): Boolean {
    val path = substringBefore('#').substringBefore('?').lowercase()
    return IMAGE_EXTENSIONS.any { path.endsWith(it) }
}

/**
 * 去掉 TS 流前面垫的图片: 从第一个「连着 [SYNC_RUN] 个包头都是 0x47」的位置截取. 本来就从包头开始、或者找不到 TS 包头
 * (不是 TS, 比如 fMP4 或加密的分片) 时原样返回.
 */
internal fun unwrapDisguisedTs(bytes: ByteArray): ByteArray {
    for (offset in bytes.indices) {
        if (isTsPacketRunAt(bytes, offset)) {
            return if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
        }
    }
    return bytes
}

/** [offset] 起连着 [SYNC_RUN] 个 188 字节的包头都是 0x47. 只看一个字节会撞上图片数据里的 0x47, 加密数据里连着几个撞上的概率可以忽略. */
private fun isTsPacketRunAt(bytes: ByteArray, offset: Int): Boolean {
    if (offset + (SYNC_RUN - 1) * TS_PACKET_SIZE >= bytes.size) return false
    for (i in 0 until SYNC_RUN) {
        if (bytes[offset + i * TS_PACKET_SIZE] != TS_SYNC_BYTE) return false
    }
    return true
}

private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".ico")
private const val TS_PACKET_SIZE = 188
private const val TS_SYNC_BYTE: Byte = 0x47
private const val SYNC_RUN = 5
