/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.probe

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.him188.ani.app.domain.media.hls.TsPacketReader
import me.him188.ani.utils.ktor.UrlHelpers
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * 只取几十 KB 估出一个视频地址的分辨率、编码与平均码率, 给数据源画像用 (见 `SourceProfiles`).
 *
 * - mp4: 取开头 64KB, `Content-Range` 给出文件总大小, moov 给出时长、宽高与编码; moov 在文件尾时按 mdat 末尾再取一次.
 *   码率是精确的平均值.
 * - HLS 主列表: 读最高一档的 `BANDWIDTH` / `RESOLUTION` / `CODECS`, 缺什么再进那一档的媒体列表补.
 * - HLS 媒体列表: 带 `EXT-X-BYTERANGE` 的直接按长度算; 否则均匀抽 [SAMPLED_SEGMENTS] 个分片各取 1 字节,
 *   由 `Content-Range` 得分片大小. 分片之间码率起伏很大, 估出来只够分档.
 *   宽高与编码: fMP4 读 `EXT-X-MAP` 的 moov; TS 从中间一个分片的开头解视频的 SPS (分片前常拼着伪装用的图片头, 按包间距找同步字节).
 *
 * 源站不支持 Range 时只读开头若干字节就断开, 不会把整个视频下完.
 */
object MediaStreamProbe {
    data class Result(
        /** `mp4` 或 `hls`. */
        val container: String,
        val width: Int? = null,
        val height: Int? = null,
        /** `H.264` / `HEVC` / `AV1` / `VP9`; 认不出为 null. */
        val codec: String? = null,
        /** 平均码率 (视频 + 音频). */
        val kbps: Int? = null,
        val durationSeconds: Int? = null,
    )

    private const val HEAD_BYTES = 64 * 1024
    private const val SEGMENT_HEAD_BYTES = 32 * 1024
    private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    private const val MAX_MOOV_BYTES = 8 * 1024 * 1024
    private const val SAMPLED_SEGMENTS = 8

    suspend fun probe(client: HttpClient, url: String, headers: Map<String, String> = emptyMap()): Result? {
        val head = client.fetch(url, headers, 0L until HEAD_BYTES, HEAD_BYTES) ?: return null
        return when {
            head.bytes.looksLikePlaylist() -> {
                val full = if (head.isPartial) client.fetch(url, headers, null, MAX_PLAYLIST_BYTES) ?: return null else head
                probeHls(client, full.text(), full.finalUrl, headers, depth = 0)
            }

            Mp4.looksLikeMp4(head.bytes) -> probeMp4(client, url, headers, head)
            else -> null
        }
    }

    // region mp4

    private suspend fun probeMp4(client: HttpClient, url: String, headers: Map<String, String>, head: Fetched): Result {
        val total = head.totalLength
        var info = Mp4.findMoov(head.bytes)?.let { Mp4.parseMoov(head.bytes, it.first, it.second) }
        if (info == null && total != null) {
            // moov 不在开头: 跳过 mdat 去找
            val next = Mp4.offsetAfterVisibleBoxes(head.bytes)
            if (next != null && next < total) {
                val probe = client.fetch(url, headers, next until minOf(total, next + HEAD_BYTES), HEAD_BYTES)
                val box = probe?.bytes?.let { Mp4.boxAt(it, 0) }
                if (probe != null && box != null && box.type == "moov") {
                    val bytes = if (box.size <= probe.bytes.size) {
                        probe.bytes
                    } else if (box.size <= MAX_MOOV_BYTES) {
                        client.fetch(url, headers, next until next + box.size, box.size.toInt())?.bytes
                    } else {
                        null
                    }
                    if (bytes != null) info = Mp4.parseMoov(bytes, box.headerSize, minOf(box.size, bytes.size.toLong()).toInt())
                }
            }
        }
        val duration = info?.durationSeconds
        return Result(
            container = "mp4",
            width = info?.width,
            height = info?.height,
            codec = info?.codec,
            kbps = if (total != null && duration != null && duration > 0) (total * 8 / duration / 1000).roundToInt() else null,
            durationSeconds = duration?.roundToInt(),
        )
    }

    // endregion

    // region HLS

    private suspend fun probeHls(client: HttpClient, playlist: String, baseUrl: String, headers: Map<String, String>, depth: Int): Result? {
        val lines = playlist.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
            val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:") }.mapNotNull { i ->
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapNotNull null
                Hls.attributes(lines[i].substringAfter(':')) to uri
            }
            val (attrs, uri) = variants.maxByOrNull { it.first["BANDWIDTH"]?.toLongOrNull() ?: 0 } ?: return null
            val (w, h) = attrs["RESOLUTION"]?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
                ?.let { it[0] to it[1] } ?: (null to null)
            val declared = Result(
                container = "hls",
                width = w,
                height = h,
                codec = attrs["CODECS"]?.let(Hls::codecOf),
                kbps = (attrs["AVERAGE-BANDWIDTH"] ?: attrs["BANDWIDTH"])?.toLongOrNull()?.let { (it / 1000).toInt() },
            )
            if (declared.height != null && declared.codec != null && declared.kbps != null || depth > 0) return declared
            val variantUrl = UrlHelpers.computeAbsoluteUrlOrNull(baseUrl, uri) ?: return declared
            val media = client.fetch(variantUrl, headers, null, MAX_PLAYLIST_BYTES) ?: return declared
            val inner = probeHls(client, media.text(), media.finalUrl, headers, depth + 1) ?: return declared
            return declared.copy(
                width = declared.width ?: inner.width,
                height = declared.height ?: inner.height,
                codec = declared.codec ?: inner.codec,
                kbps = declared.kbps ?: inner.kbps,
                durationSeconds = inner.durationSeconds,
            )
        }

        val media = Hls.parseMediaPlaylist(lines, baseUrl)
        if (media.segments.isEmpty()) return null
        val totalSeconds = media.segments.sumOf { it.duration }
        val middle = media.segments[media.segments.size / 2]
        val segmentHeaders = segmentHeaders(client, media.mapUri ?: middle.uri, headers)
        val kbps = if (media.segments.all { it.byteLength != null }) {
            media.segments.sumOf { it.byteLength!! } * 8 / totalSeconds / 1000
        } else {
            val n = media.segments.size
            val picked = (0 until minOf(SAMPLED_SEGMENTS, n)).map { media.segments[(n * (2 * it + 1)) / (2 * minOf(SAMPLED_SEGMENTS, n))] }.distinct()
            val limit = Semaphore(4)
            val sizes = coroutineScope {
                picked.map { segment ->
                    async { limit.withPermit { segment.byteLength ?: client.fetch(segment.uri, segmentHeaders, 0L..0L, 1)?.totalLength } }
                }.awaitAll()
            }
            val known = picked.zip(sizes).filter { it.second != null }
            if (known.isEmpty()) null else known.sumOf { it.second!! } * 8 / known.sumOf { it.first.duration } / 1000
        }

        var width: Int? = null
        var height: Int? = null
        var codec: String? = null
        val map = media.mapUri
        if (map != null) {
            client.fetch(map, segmentHeaders, null, 1024 * 1024)?.bytes?.let { init ->
                Mp4.findMoov(init)?.let { Mp4.parseMoov(init, it.first, it.second) }?.let { width = it.width; height = it.height; codec = it.codec }
            }
        } else if (!media.encrypted) {
            client.fetch(middle.uri, segmentHeaders, 0L until SEGMENT_HEAD_BYTES, SEGMENT_HEAD_BYTES)?.bytes?.let { bytes ->
                VideoSps.fromTs(bytes)?.let { width = it.width; height = it.height; codec = it.codec }
            }
        }
        return Result(
            container = "hls",
            width = width,
            height = height,
            codec = codec,
            kbps = kbps?.roundToInt(),
            durationSeconds = totalSeconds.roundToInt(),
        )
    }

    /**
     * 取分片用的请求头. 分片常放在别的主机 (图床), 带着播放页的 Referer 会被防盗链拒绝, 而播放器取分片时不带它:
     * 先按原样取 [sample] 的 1 字节, 被拒就去掉 `Referer` / `Origin`; 都不行仍用原样的.
     */
    private suspend fun segmentHeaders(client: HttpClient, sample: String, headers: Map<String, String>): Map<String, String> {
        val stripped = headers.filterKeys { !it.equals(HttpHeaders.Referrer, ignoreCase = true) && !it.equals(HttpHeaders.Origin, ignoreCase = true) }
        if (stripped.size == headers.size || client.fetch(sample, headers, 0L..0L, 1) != null) return headers
        return if (client.fetch(sample, stripped, 0L..0L, 1) != null) stripped else headers
    }

    // endregion

    // region 网络

    private class Fetched(val bytes: ByteArray, val totalLength: Long?, val isPartial: Boolean, val finalUrl: String) {
        fun text(): String = bytes.decodeToString()
    }

    private fun ByteArray.looksLikePlaylist(): Boolean {
        val start = if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) 3 else 0
        return decodeToString(start, minOf(size, start + 16)).trimStart().startsWith("#EXTM3U")
    }

    /** 取 [range] 那一段, 最多读 [max] 字节就断开 (源站不认 Range 时也不会多读). 失败返回 null. */
    private suspend fun HttpClient.fetch(url: String, headers: Map<String, String>, range: LongRange?, max: Int): Fetched? = try {
        prepareGet(url) {
            expectSuccess = false
            headers.forEach { (name, value) -> header(name, value) }
            if (range != null) header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
        }.execute { response ->
            if (response.status.value !in 200..299) return@execute null
            val partial = response.status.value == 206
            val total = response.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
                ?: response.contentLength().takeIf { !partial }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(max)
            var length = 0
            while (length < max) {
                val read = channel.readAvailable(buffer, length, max - length)
                if (read < 0) break
                length += read
            }
            Fetched(buffer.copyOf(length), total, partial && total != null && total > length, response.request.url.toString())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // endregion
}

/** HLS 播放列表的解析. */
internal object Hls {
    class Segment(val uri: String, val duration: Double, val byteLength: Long?)
    class MediaPlaylist(val segments: List<Segment>, val mapUri: String?, val encrypted: Boolean)

    /** `KEY=VALUE,KEY="VALUE,带逗号"` 形式的属性列表. */
    fun attributes(text: String): Map<String, String> = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(text)
        .associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

    fun codecOf(codecs: String): String? = codecs.split(',').map { it.trim().lowercase() }.firstNotNullOfOrNull {
        when {
            it.startsWith("avc1") || it.startsWith("avc3") -> "H.264"
            it.startsWith("hvc1") || it.startsWith("hev1") -> "HEVC"
            it.startsWith("av01") -> "AV1"
            it.startsWith("vp09") -> "VP9"
            else -> null
        }
    }

    fun parseMediaPlaylist(lines: List<String>, baseUrl: String): MediaPlaylist {
        val segments = mutableListOf<Segment>()
        var duration: Double? = null
        var byteLength: Long? = null
        var mapUri: String? = null
        var encrypted = false
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                line.startsWith("#EXT-X-BYTERANGE:") -> byteLength = line.substringAfter(':').substringBefore('@').trim().toLongOrNull()
                line.startsWith("#EXT-X-MAP:") -> mapUri = attributes(line.substringAfter(':'))["URI"]
                    ?.let { UrlHelpers.computeAbsoluteUrlOrNull(baseUrl, it) }

                line.startsWith("#EXT-X-KEY:") -> encrypted = attributes(line.substringAfter(':'))["METHOD"]?.equals("NONE", true) == false
                line.isNotEmpty() && !line.startsWith("#") && duration != null -> {
                    UrlHelpers.computeAbsoluteUrlOrNull(baseUrl, line)?.let { segments += Segment(it, duration!!, byteLength) }
                    duration = null
                    byteLength = null
                }
            }
        }
        return MediaPlaylist(segments.filter { it.duration > 0 }, mapUri, encrypted)
    }
}

/** ISO BMFF (mp4) 的盒子. */
internal object Mp4 {
    class Box(val type: String, val start: Long, val headerSize: Int, val size: Long)
    class Info(val durationSeconds: Double?, val width: Int?, val height: Int?, val codec: String?)

    fun looksLikeMp4(b: ByteArray): Boolean = b.size >= 8 && b.decodeToString(4, 8) in setOf("ftyp", "moov", "styp", "free", "mdat")

    fun boxAt(b: ByteArray, at: Int): Box? {
        if (at + 8 > b.size) return null
        var size = u32(b, at)
        val type = b.decodeToString(at + 4, at + 8)
        var header = 8
        if (size == 1L) {
            if (at + 16 > b.size) return null
            size = u64(b, at + 8)
            header = 16
        } else if (size == 0L) {
            size = (b.size - at).toLong()
        }
        if (size < header) return null
        return Box(type, at.toLong(), header, size)
    }

    /** 开头这段里完整的 moov 的内容范围 (不含盒子头). */
    fun findMoov(b: ByteArray): Pair<Int, Int>? {
        var at = 0
        while (true) {
            val box = boxAt(b, at) ?: return null
            if (box.type == "moov") return if (box.start + box.size <= b.size) (at + box.headerSize) to (at + box.size.toInt()) else null
            if (box.start + box.size >= b.size) return null
            at = (box.start + box.size).toInt()
        }
    }

    /** 开头这段里看得到的最后一个顶层盒子的结尾, 即下一个盒子的位置. */
    fun offsetAfterVisibleBoxes(b: ByteArray): Long? {
        var at = 0L
        while (at < b.size) {
            val box = boxAt(b, at.toInt()) ?: return null
            at = box.start + box.size
        }
        return at
    }

    fun parseMoov(b: ByteArray, start: Int, end: Int): Info {
        var duration: Double? = null
        var width: Int? = null
        var height: Int? = null
        var codec: String? = null
        children(b, start, end) { box, s, e ->
            when (box.type) {
                "mvhd" -> duration = mvhdDuration(b, s)
                "trak" -> {
                    val track = parseTrak(b, s, e)
                    if (track.handler == "vide" && width == null) {
                        width = track.width; height = track.height; codec = track.codec
                    }
                }
            }
        }
        return Info(duration, width, height, codec)
    }

    private class Track(var handler: String? = null, var width: Int? = null, var height: Int? = null, var codec: String? = null)

    private fun parseTrak(b: ByteArray, start: Int, end: Int): Track {
        val track = Track()
        fun walk(s: Int, e: Int) {
            children(b, s, e) { box, cs, ce ->
                when (box.type) {
                    "tkhd" -> {
                        val off = cs + if (b[cs].toInt() == 1) 88 else 76
                        if (off + 8 <= ce) {
                            track.width = (u32(b, off) shr 16).toInt()
                            track.height = (u32(b, off + 4) shr 16).toInt()
                        }
                    }

                    "hdlr" -> if (cs + 12 <= ce) track.handler = b.decodeToString(cs + 8, cs + 12)
                    "stsd" -> if (cs + 16 <= ce) track.codec = when (b.decodeToString(cs + 12, cs + 16)) {
                        "avc1", "avc3" -> "H.264"
                        "hvc1", "hev1" -> "HEVC"
                        "av01" -> "AV1"
                        "vp09" -> "VP9"
                        else -> null
                    }

                    "mdia", "minf", "stbl" -> walk(cs, ce)
                }
            }
        }
        walk(start, end)
        return track
    }

    private fun mvhdDuration(b: ByteArray, s: Int): Double? {
        val version = b[s].toInt()
        val (timescale, duration) = if (version == 1) {
            if (s + 32 > b.size) return null
            u32(b, s + 20) to u64(b, s + 24)
        } else {
            if (s + 20 > b.size) return null
            u32(b, s + 12) to u32(b, s + 16)
        }
        return if (timescale > 0) duration.toDouble() / timescale else null
    }

    private inline fun children(b: ByteArray, start: Int, end: Int, block: (Box, Int, Int) -> Unit) {
        var at = start
        while (at + 8 <= minOf(end, b.size)) {
            val box = boxAt(b, at) ?: return
            val boxEnd = minOf(box.start + box.size, minOf(end, b.size).toLong()).toInt()
            block(box, at + box.headerSize, boxEnd)
            at = (box.start + box.size).toInt()
        }
    }

    fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)
}

/** 从 TS 里视频的 SPS 解出宽高与编码 (H.264 / HEVC). */
internal object VideoSps {
    class Info(val width: Int, val height: Int, val codec: String)

    private const val PACKET = 188

    fun fromTs(bytes: ByteArray): Info? {
        val es = videoElementaryStream(bytes) ?: return null
        return parseNals(es.first, es.second)
    }

    /** 视频那一路的 ES 数据与 PMT 里的 stream_type (没读到 PMT 为 null). */
    private fun videoElementaryStream(b: ByteArray): Pair<ByteArray, Int?>? {
        val start = syncOffset(b) ?: return null
        var pmtPid: Int? = null
        var videoPid: Int? = null
        var streamType: Int? = null
        val es = ArrayList<Byte>()
        var base = start
        while (base + PACKET <= b.size && b[base] == TsPacketReader.SYNC_BYTE) {
            val pusi = (b[base + 1].toInt() shr 6) and 1
            val pid = ((b[base + 1].toInt() and 0x1F) shl 8) or (b[base + 2].toInt() and 0xFF)
            val afc = (b[base + 3].toInt() shr 4) and 3
            var payload = base + 4
            if (afc and 2 != 0) payload += 1 + (b[base + 4].toInt() and 0xFF)
            if (afc and 1 != 0 && payload < base + PACKET) {
                when {
                    pid == 0 && pusi == 1 -> pmtPid = patProgramMapPid(b, payload, base + PACKET) ?: pmtPid
                    pid == pmtPid && pusi == 1 -> pmtVideo(b, payload, base + PACKET)?.let { videoPid = it.first; streamType = it.second }
                    else -> {
                        val pes = if (pusi == 1) TsPacketReader.pesHeaderOffset(b, base) else null
                        if (videoPid == null && pes != null && (b[pes + 3].toInt() and 0xFF) in 0xE0..0xEF) videoPid = pid
                        if (pid == videoPid) {
                            val from = if (pes != null) pes + 9 + (b[pes + 8].toInt() and 0xFF) else payload
                            for (i in from until base + PACKET) es += b[i]
                        }
                    }
                }
            }
            base += PACKET
        }
        return if (es.isEmpty()) null else es.toByteArray() to streamType
    }

    private fun syncOffset(b: ByteArray): Int? {
        var base = 0
        while (base + 2 * PACKET < b.size) {
            if (b[base] == TsPacketReader.SYNC_BYTE && b[base + PACKET] == TsPacketReader.SYNC_BYTE && b[base + 2 * PACKET] == TsPacketReader.SYNC_BYTE) {
                return base
            }
            base++
        }
        return null
    }

    private fun patProgramMapPid(b: ByteArray, payload: Int, end: Int): Int? {
        val table = payload + 1 + (b[payload].toInt() and 0xFF)
        if (table + 8 > end || b[table].toInt() != 0) return null
        val sectionEnd = minOf(end, table + 3 + (((b[table + 1].toInt() and 0x0F) shl 8) or (b[table + 2].toInt() and 0xFF)) - 4)
        var at = table + 8
        while (at + 4 <= sectionEnd) {
            val program = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
            val pid = ((b[at + 2].toInt() and 0x1F) shl 8) or (b[at + 3].toInt() and 0xFF)
            if (program != 0) return pid
            at += 4
        }
        return null
    }

    private fun pmtVideo(b: ByteArray, payload: Int, end: Int): Pair<Int, Int>? {
        val table = payload + 1 + (b[payload].toInt() and 0xFF)
        if (table + 12 > end || b[table].toInt() != 2) return null
        val sectionEnd = minOf(end, table + 3 + (((b[table + 1].toInt() and 0x0F) shl 8) or (b[table + 2].toInt() and 0xFF)) - 4)
        val programInfo = ((b[table + 10].toInt() and 0x0F) shl 8) or (b[table + 11].toInt() and 0xFF)
        var at = table + 12 + programInfo
        while (at + 5 <= sectionEnd) {
            val type = b[at].toInt() and 0xFF
            val pid = ((b[at + 1].toInt() and 0x1F) shl 8) or (b[at + 2].toInt() and 0xFF)
            val info = ((b[at + 3].toInt() and 0x0F) shl 8) or (b[at + 4].toInt() and 0xFF)
            if (type == 0x1B || type == 0x24) return pid to type
            at += 5 + info
        }
        return null
    }

    /** 在 Annex B 字节流里找 SPS. [streamType] 0x1B = H.264, 0x24 = HEVC; 不知道时两种都试. */
    fun parseNals(es: ByteArray, streamType: Int?): Info? {
        var i = 0
        while (i + 4 < es.size) {
            if (es[i].toInt() == 0 && es[i + 1].toInt() == 0 && es[i + 2].toInt() == 1) {
                val nal = i + 3
                val next = nextStartCode(es, nal)
                val header = es[nal].toInt() and 0xFF
                val hevc = streamType == 0x24 || streamType == null && (header shr 1) and 0x3F == 33 && (es[nal + 1].toInt() and 0xFF) == 1
                if (hevc && (header shr 1) and 0x3F == 33) {
                    runCatching { hevcSps(unescape(es, nal + 2, next)) }.getOrNull()?.let { return it }
                } else if (streamType != 0x24 && header and 0x1F == 7) {
                    runCatching { avcSps(unescape(es, nal + 1, next)) }.getOrNull()?.let { return it }
                }
                i = nal
            } else {
                i++
            }
        }
        return null
    }

    private fun nextStartCode(b: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && (b[i + 2].toInt() == 1 || b[i + 2].toInt() == 0)) return i
            i++
        }
        return b.size
    }

    /** 去掉防竞争字节 `00 00 03`. */
    private fun unescape(b: ByteArray, from: Int, to: Int): ByteArray {
        val out = ArrayList<Byte>(to - from)
        var zeros = 0
        for (i in from until to) {
            val v = b[i]
            if (zeros >= 2 && v.toInt() == 3) {
                zeros = 0
                continue
            }
            zeros = if (v.toInt() == 0) zeros + 1 else 0
            out += v
        }
        return out.toByteArray()
    }

    private class Bits(private val b: ByteArray) {
        private var pos = 0
        fun bit(): Int {
            if (pos / 8 >= b.size) throw IndexOutOfBoundsException()
            val v = (b[pos / 8].toInt() shr (7 - pos % 8)) and 1
            pos++
            return v
        }

        fun bits(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 1) or bit().toLong() }
            return v
        }

        fun skip(n: Int) {
            repeat(n) { bit() }
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros++
                if (zeros > 31) throw IllegalStateException("bad exp-golomb")
            }
            return ((1L shl zeros) - 1 + bits(zeros)).toInt()
        }

        fun se(): Int {
            val v = ue()
            return if (v % 2 == 0) -(v / 2) else (v + 1) / 2
        }
    }

    private fun avcSps(sps: ByteArray): Info {
        val r = Bits(sps)
        val profile = r.bits(8).toInt()
        r.skip(16) // constraint flags + level
        r.ue() // seq_parameter_set_id
        var chroma = 1
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.skip(1)
            r.ue()
            r.ue()
            r.skip(1)
            if (r.bit() == 1) {
                repeat(if (chroma == 3) 12 else 8) { list ->
                    if (r.bit() == 1) {
                        var last = 8
                        var next = 8
                        repeat(if (list < 6) 16 else 64) {
                            if (next != 0) next = (last + r.se() + 256) % 256
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) {
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.se()
                r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.skip(1)
        val widthMbs = r.ue() + 1
        val heightUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.skip(1)
        r.skip(1)
        var width = widthMbs * 16
        var height = (2 - frameMbsOnly) * heightUnits * 16
        if (r.bit() == 1) {
            val (subW, subH) = when (chroma) {
                0, 3 -> 1 to 1
                2 -> 2 to 1
                else -> 2 to 2
            }
            val cropX = if (chroma == 0) 1 else subW
            val cropY = (if (chroma == 0) 1 else subH) * (2 - frameMbsOnly)
            width -= cropX * (r.ue() + r.ue())
            height -= cropY * (r.ue() + r.ue())
        }
        return Info(width, height, "H.264")
    }

    private fun hevcSps(sps: ByteArray): Info {
        val r = Bits(sps)
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3).toInt()
        r.skip(1)
        // profile_tier_level: general 部分 2+1+5+32+4+43+1 位, 再 8 位 level
        r.skip(88)
        r.skip(8)
        val profilePresent = IntArray(maxSubLayersMinus1)
        val levelPresent = IntArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = r.bit()
            levelPresent[i] = r.bit()
        }
        if (maxSubLayersMinus1 > 0) repeat(8 - maxSubLayersMinus1) { r.skip(2) }
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i] == 1) r.skip(88)
            if (levelPresent[i] == 1) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        var width = r.ue()
        var height = r.ue()
        if (r.bit() == 1) {
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            width -= subW * (r.ue() + r.ue())
            height -= subH * (r.ue() + r.ue())
        }
        return Info(width, height, "HEVC")
    }
}
