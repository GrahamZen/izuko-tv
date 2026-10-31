/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuLocation
import me.him188.ani.danmaku.dandanplay.data.DandanplayDanmaku
import me.him188.ani.danmaku.dandanplay.data.toDanmakuOrNull

/**
 * 解析用户导入的弹幕文件: XML (`<d p="时间,模式,字号,颜色,…">内容</d>`) 或弹弹 play 的 JSON (`{"comments":[{"p":…,"m":…}]}`).
 */
object DanmakuFileParser {
    /** @return 弹幕列表; 不是这两种格式时为 `null`. 格式对但一条弹幕也没有时为空列表. */
    fun parse(bytes: ByteArray): List<DanmakuContent>? {
        val text = decodeText(bytes).trimStart()
        return when (text.firstOrNull()) {
            '<' -> parseXml(text)
            '{', '[' -> parseJson(text)
            else -> null
        }
    }

    private fun parseXml(text: String): List<DanmakuContent>? {
        val result = ArrayList<DanmakuContent>()
        var found = false
        var cursor = 0
        while (true) {
            val start = text.indexOf("<d ", cursor)
            if (start < 0) break
            val tagEnd = text.indexOf('>', start)
            if (tagEnd < 0) break
            val close = text.indexOf("</d>", tagEnd)
            if (close < 0) break
            cursor = close + 4
            found = true
            val p = attributeOf(text.substring(start, tagEnd), "p") ?: continue
            parseDanmakuAttributes(p, unescapeXml(text.substring(tagEnd + 1, close)))?.let(result::add)
        }
        // 一条 <d> 都没有时, 只有带 <i> 根节点的才是空的弹幕文件; 别的 XML 不是弹幕
        return if (found || text.contains("<i>")) result else null
    }

    private fun attributeOf(tag: String, name: String): String? {
        for (quote in listOf('"', '\'')) {
            val marker = " $name=$quote"
            val at = tag.indexOf(marker)
            if (at < 0) continue
            val valueStart = at + marker.length
            val valueEnd = tag.indexOf(quote, valueStart)
            if (valueEnd > 0) return tag.substring(valueStart, valueEnd)
        }
        return null
    }

    /** 写成 [parse] 认得的 JSON. 导入的弹幕就这样存. */
    fun encode(contents: List<DanmakuContent>): String = danmakuServerJson.encodeToString(
        ServerCommentList.serializer(),
        ServerCommentList(
            comments = contents.map {
                val mode = when (it.location) {
                    DanmakuLocation.NORMAL -> 1
                    DanmakuLocation.BOTTOM -> 4
                    DanmakuLocation.TOP -> 5
                }
                ServerComment("${it.playTimeMillis / 1000.0},$mode,${it.color},", it.text)
            },
        ),
    )

    private fun parseJson(text: String): List<DanmakuContent>? {
        val comments = try {
            if (text.startsWith("[")) {
                danmakuServerJson.decodeFromString(ListSerializer(ServerComment.serializer()), text)
            } else {
                danmakuServerJson.decodeFromString(ServerCommentList.serializer(), text).comments
            }
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        // 任意一个 JSON 对象都能解出空列表, 要有 comments 字段才算弹幕文件
        if (comments.isEmpty() && !text.contains("\"comments\"")) return null
        return comments.mapNotNull { it.toContentOrNull() }
    }

    private fun decodeText(bytes: ByteArray): String = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> bytes.decodeToString(startIndex = 3)
        bytes.startsWith(0xFF, 0xFE) -> decodeUtf16(bytes, littleEndian = true)
        bytes.startsWith(0xFE, 0xFF) -> decodeUtf16(bytes, littleEndian = false)
        else -> bytes.decodeToString()
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

    private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean): String = buildString((bytes.size - 2) / 2) {
        var i = 2
        while (i + 1 < bytes.size) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            append((if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar())
            i += 2
        }
    }

    private fun unescapeXml(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) return value.substring(9, value.length - 3)
        if ('&' !in value) return value
        return ENTITY.replace(value) { m ->
            val body = m.groupValues[1]
            when {
                body == "amp" -> "&"
                body == "lt" -> "<"
                body == "gt" -> ">"
                body == "quot" -> "\""
                body == "apos" -> "'"
                body.startsWith("#x") || body.startsWith("#X") -> charOf(body.substring(2).toIntOrNull(16)) ?: m.value
                body.startsWith("#") -> charOf(body.substring(1).toIntOrNull()) ?: m.value
                else -> m.value
            }
        }
    }

    private fun charOf(code: Int?): String? = if (code == null || code !in 0..0xFFFF) null else code.toChar().toString()

    private val ENTITY = Regex("""&(#?[0-9a-zA-Z]+);""")
}

/**
 * 解析一条弹幕的 `p` 属性. 两种写法都有:
 * - 弹弹 play: `时间,模式,颜色,用户`
 * - XML 与部分服务器: `时间,模式,字号,颜色,…`
 *
 * 模式 1~3、6 是滚动, 4 底部, 5 顶部; 其他 (高级弹幕、代码弹幕) 不支持. XML 第 6 项是弹幕池, 2 是特殊弹幕, 也不支持.
 *
 * 这里只把各种写法规整成弹弹 play 的写法, 真正的解析都走 [toDanmakuOrNull].
 */
internal fun parseDanmakuAttributes(p: String, text: String): DanmakuContent? {
    val parts = p.split(',')
    if (parts.size < 3) return null
    val mode = when (val raw = parts[1].toIntOrNull()) {
        1, 2, 3, 6 -> SCROLL_MODE
        4, 5 -> raw
        else -> return null
    }
    val hasFontSize = parts.size >= 4 && parts[2].toIntOrNull() in FONT_SIZES && parts[3].toIntOrNull() != null
    if (hasFontSize && parts.size >= 8 && parts[5] == "2") return null
    val color = (if (hasFontSize) parts[3] else parts[2]).toIntOrNull() ?: WHITE
    return DandanplayDanmaku(0, "${parts[0]},$mode,$color,", text).toDanmakuOrNull()?.content
}

private const val SCROLL_MODE = 1

private val FONT_SIZES = 12..64
private const val WHITE = 0xFFFFFF
