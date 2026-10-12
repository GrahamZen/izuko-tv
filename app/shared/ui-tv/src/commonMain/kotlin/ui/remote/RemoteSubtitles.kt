/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.app.videoplayer.media.decodeSubtitleText
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.source.MediaData
import java.io.File

/**
 * 电视手里的外挂字幕 (本机文件: 缓存时一并存下的网盘字幕等) 转成 WebVTT, 给全屏同步播放的浏览器挂成字幕轨
 * (iPhone 原生全屏只显示这种); ASS 转过去只留文字, 位置与特效丢掉。字幕组常用 GBK / Big5, 先按字幕的编码解出来。
 *
 * 只发本机文件 (`file://`): 网上的外挂字幕要带视频的请求头, 手机拿不到。
 */
internal object RemoteSubtitles {
    /** 外挂字幕最大多大 (再大就不是字幕了) */
    private const val MAX_BYTES = 8L shl 20

    class Local(val index: Int, val file: File, val label: String?, val language: String?) {
        val ext: String get() = file.extension.lowercase().ifEmpty { "srt" }
    }

    /** 电视此刻在播的媒体带的本机字幕 (缓存时存下的, 见 CacheSidecarSubtitles); 序号是它在全部外挂字幕里的位置 */
    fun localOf(data: MediaData?): List<Local> =
        data?.extraFiles?.subtitles.orEmpty().mapIndexedNotNull { i, s ->
            if (!s.uri.startsWith("file:")) return@mapIndexedNotNull null
            val file = File(s.uri.removePrefix("file://").removePrefix("file:"))
            if (file.isFile) Local(i, file, s.label, s.language) else null
        }

    /**
     * 电视上选着的那条外挂字幕; 选的是封在视频里的或关着字幕时为 null; 不知道选了哪条 ([known] = false, 播放器还没报) 时给第一条。
     *
     * 认法: 轨道的 internalId 是 media3 的 TrackGroup id `<子源序号>:<组>`。外挂字幕按 extraFiles 的顺序挂成子源
     * (见 LibassExoPlayerMediampPlayer 的 setSubtitleConfigurations), media3 把视频本身排第 0 个, 第 i 条外挂字幕是第 i + 1 个;
     * 封在视频里的轨道都是 `0:…`。
     */
    fun selectedOf(subs: List<Local>, selected: SubtitleTrack?, known: Boolean): Local? {
        if (subs.isEmpty()) return null
        if (!known) return subs.first()
        val child = selected?.internalId?.substringBefore(':', "")?.toIntOrNull() ?: return null
        if (child < 1) return null
        return subs.firstOrNull { it.index == child - 1 }
    }

    /** 发一条字幕, 转成 WebVTT */
    fun serveVtt(sub: Local): LanHttpResponse {
        if (sub.file.length() > MAX_BYTES) return LanHttpResponse.status(413, "Payload Too Large")
        val text = decodeSubtitleText(sub.file.readBytes())
        return LanHttpResponse.bytes(toWebVtt(text, sub.ext).toByteArray(), "text/vtt; charset=utf-8")
    }

    /** 路径末尾 `<序号>.vtt` → 序号 */
    fun parseVttName(name: String): Int? =
        if (name.endsWith(".vtt", ignoreCase = true)) name.substringBeforeLast('.').toIntOrNull() else null

    fun vttNameOf(sub: Local): String = "${sub.index}.vtt"

    // ---------------------------- 转 WebVTT ----------------------------

    internal fun toWebVtt(text: String, ext: String): String = when (ext) {
        "vtt" -> text
        "ass", "ssa" -> assToWebVtt(text)
        else -> srtToWebVtt(text)
    }

    private val SRT_TIME = Regex("(\\d{1,2}:\\d{2}:\\d{2})[,.](\\d{1,3})")
    private val FONT_TAG = Regex("</?font[^>]*>", RegexOption.IGNORE_CASE)

    internal fun srtToWebVtt(text: String): String = buildString {
        append("WEBVTT\n\n")
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            if ("-->" in line) {
                append(SRT_TIME.replace(line) { m -> m.groupValues[1].padStart(8, '0') + "." + m.groupValues[2].padEnd(3, '0') })
            } else {
                // <i> <b> <u> WebVTT 也认; <font> 不认
                append(FONT_TAG.replace(line, ""))
            }
            append('\n')
        }
    }

    private class Cue(val start: Long, val end: Long, val text: String)

    internal fun assToWebVtt(text: String): String {
        var inEvents = false
        var fields: List<String> = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        val cues = mutableListOf<Cue>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                fields = line.substringAfter(':').split(',').map { it.trim() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val values = line.substringAfter(':').split(',', limit = fields.size).map { it.trim() }
            if (values.size < fields.size) continue
            val start = assTime(values[fields.indexOf("Start")]) ?: continue
            val end = assTime(values[fields.indexOf("End")]) ?: continue
            val body = values[fields.indexOf("Text").takeIf { it >= 0 } ?: (fields.size - 1)]
            // 绘图 (\p1 起) 是图形, 不是文字
            if (DRAWING.containsMatchIn(body)) continue
            val plain = OVERRIDE.replace(body, "")
                .replace("\\N", "\n").replace("\\n", " ").replace("\\h", " ")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .trim()
            if (plain.isEmpty() || end <= start) continue
            cues += Cue(start, end, plain)
        }
        return buildString {
            append("WEBVTT\n\n")
            // 同一句常为描边 / 阴影叠好几层, 只留一份
            cues.sortedWith(compareBy({ it.start }, { it.end })).distinctBy { Triple(it.start, it.end, it.text) }.forEach { c ->
                append(vttTime(c.start)).append(" --> ").append(vttTime(c.end)).append('\n')
                append(c.text).append("\n\n")
            }
        }
    }

    private val OVERRIDE = Regex("\\{[^}]*\\}")
    private val DRAWING = Regex("\\{[^}]*\\\\p[1-9]")
    private val ASS_TIME = Regex("(\\d+):(\\d{1,2}):(\\d{1,2})[.:](\\d{1,3})")

    /** `H:MM:SS.cc` → 毫秒 */
    private fun assTime(s: String): Long? {
        val m = ASS_TIME.matchEntire(s) ?: return null
        val (h, mi, sec, frac) = m.destructured
        val ms = when (frac.length) {
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return ((h.toLong() * 60 + mi.toLong()) * 60 + sec.toLong()) * 1000 + ms
    }

    private fun vttTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms % 1000)
    }
}

/** 给手机的字幕列表 (JSON 里的 `subs`): 序号、名字与 WebVTT 的地址 */
internal fun RemoteSubtitles.Local.toJson(prefix: String): JsonObject = buildJsonObject {
    put("i", index)
    label?.let { put("label", it) }
    language?.let { put("lang", it) }
    put("vtt", "$prefix/" + RemoteSubtitles.vttNameOf(this@toJson))
}
