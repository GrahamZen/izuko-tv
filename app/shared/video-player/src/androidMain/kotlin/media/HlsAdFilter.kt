/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.io.ByteArrayInputStream

/**
 * 去掉 HLS 点播列表里插进正片的广告段.
 *
 * 资源站在正片中间插十几秒的广告: 前后用 `#EXT-X-DISCONTINUITY` 隔开, 分片放在另一个目录 (常常还是另一个域名、
 * 另一个日期的路径). 判据: 按时长算, 正片分片所在的目录占大头; 一个断点块里的分片全都不在这个目录、且块不长, 就是广告.
 *
 * 只动点播列表 (有 `#EXT-X-ENDLIST`), 不动直播与多码率列表. 要删的总时长超过全片两成时不删 —— 那多半是正片本来就分在几个目录里.
 * 广告块里的 `#EXT-X-KEY` / `#EXT-X-MAP` 保留, 后面的分片解密方式与原列表一致.
 */
internal object HlsAdFilter {
    class Result(val playlist: String, val removedSegments: Int, val removedSeconds: Double)

    /** 一个广告块最长多少秒. */
    private const val MAX_AD_BLOCK_SECONDS = 90.0

    /** 删掉的总时长最多占全片多少. */
    private const val MAX_REMOVED_SHARE = 0.2

    /** 正片目录至少占全片多少, 才敢判别的目录是广告. */
    private const val MIN_MAIN_SHARE = 0.5

    private val SEGMENT_TAGS = listOf("#EXTINF:", "#EXT-X-BYTERANGE", "#EXT-X-PROGRAM-DATE-TIME", "#EXT-X-GAP", "#EXT-X-BITRATE")

    private class Segment(val block: Int, val directory: String, val duration: Double, val lines: List<Int>)

    fun strip(playlist: String, playlistUri: String): Result {
        val unchanged = Result(playlist, 0, 0.0)
        if ("#EXT-X-DISCONTINUITY" !in playlist || "#EXT-X-ENDLIST" !in playlist || "#EXT-X-STREAM-INF" in playlist) {
            return unchanged
        }
        val lines = playlist.lines()
        val segments = ArrayList<Segment>()
        val blockOpeners = HashMap<Int, Int>()
        var block = 0
        var duration = 0.0
        val pendingTags = ArrayList<Int>()
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> {
                    block++
                    blockOpeners[block] = index
                }

                SEGMENT_TAGS.any { line.startsWith(it) } -> {
                    if (line.startsWith("#EXTINF:")) {
                        duration = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    }
                    pendingTags += index
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments += Segment(block, directoryOf(line, playlistUri), duration, pendingTags + index)
                    pendingTags.clear()
                    duration = 0.0
                }
            }
        }
        val total = segments.sumOf { it.duration }
        if (total <= 0) return unchanged
        val (mainDirectory, mainDuration) = segments.groupBy { it.directory }
            .mapValues { (_, list) -> list.sumOf { it.duration } }
            .maxBy { it.value }
        if (mainDuration < total * MIN_MAIN_SHARE) return unchanged

        val adBlocks = segments.groupBy { it.block }.filterValues { list ->
            list.none { it.directory == mainDirectory } && list.sumOf { it.duration } <= MAX_AD_BLOCK_SECONDS
        }
        val removed = adBlocks.values.flatten()
        val removedSeconds = removed.sumOf { it.duration }
        if (removed.isEmpty() || removedSeconds > total * MAX_REMOVED_SHARE) return unchanged

        val dropped = HashSet<Int>()
        for ((adBlock, list) in adBlocks) {
            blockOpeners[adBlock]?.let { dropped += it }
            list.forEach { dropped += it.lines }
        }
        val output = lines.filterIndexed { index, _ -> index !in dropped }.joinToString("\n")
        return Result(output, removed.size, removedSeconds)
    }

    /** 分片地址所在的目录 (只看路径, 不看域名): 相对地址按列表地址补全. */
    internal fun directoryOf(segmentUri: String, playlistUri: String): String {
        val path = pathOf(segmentUri)
        val absolute = if (path.startsWith("/")) path else pathOf(playlistUri).substringBeforeLast('/') + "/" + path
        return absolute.substringBeforeLast('/')
    }

    private fun pathOf(uri: String): String {
        val noQuery = uri.substringBefore('?').substringBefore('#')
        val schemeEnd = noQuery.indexOf("://")
        return when {
            schemeEnd >= 0 -> noQuery.substring(schemeEnd + 3).let { rest -> rest.indexOf('/').let { if (it < 0) "/" else rest.substring(it) } }
            noQuery.startsWith("//") -> noQuery.substring(2).let { rest -> rest.indexOf('/').let { if (it < 0) "/" else rest.substring(it) } }
            else -> noQuery
        }
    }
}

/**
 * 解析 HLS 列表前先过一遍 [HlsAdFilter].
 */
@OptIn(UnstableApi::class)
internal class AdFilteringHlsPlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = filtering(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = filtering(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun filtering(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser { uri: Uri, input ->
        val bytes = input.readBytes()
        val result = HlsAdFilter.strip(bytes.decodeToString(), uri.toString())
        if (result.removedSegments == 0) {
            parser.parse(uri, ByteArrayInputStream(bytes))
        } else {
            logger.info {
                "Removed ${result.removedSegments} ad segments (${"%.1f".format(result.removedSeconds)}s) from HLS playlist on ${uri.host}"
            }
            parser.parse(uri, ByteArrayInputStream(result.playlist.encodeToByteArray()))
        }
    }

    private companion object {
        private val logger = logger<AdFilteringHlsPlaylistParserFactory>()
    }
}
