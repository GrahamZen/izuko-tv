/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON 与 protobuf 的统一视图, 使得同一套路径语法能取两种响应里的值.
 */
internal sealed interface DataNode {
    /**
     * 取子节点. 数组与 repeated 字段会被展开成多个节点, 所以返回列表.
     * 对 JSON 来说 [key] 是字段名, 对 protobuf 来说是字段号.
     */
    fun select(key: String): List<DataNode>

    fun asStringOrNull(): String?
}

internal class JsonNode(private val element: JsonElement) : DataNode {
    override fun select(key: String): List<DataNode> = when (element) {
        is JsonObject -> element[key]?.let { expand(it) }.orEmpty()
        is JsonArray -> element.flatMap { JsonNode(it).select(key) }
        else -> emptyList()
    }

    override fun asStringOrNull(): String? = (element as? JsonPrimitive)?.content

    private fun expand(value: JsonElement): List<DataNode> =
        if (value is JsonArray) value.map { JsonNode(it) } else listOf(JsonNode(value))
}

internal class ProtoNode(private val value: Any) : DataNode {
    override fun select(key: String): List<DataNode> {
        val field = key.toIntOrNull() ?: return emptyList()
        val message = when (value) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                value as ProtoFields
            }

            is ByteArray -> parseProto(value)
            else -> return emptyList()
        }
        return message[field]?.map { ProtoNode(it) }.orEmpty()
    }

    override fun asStringOrNull(): String? = when (value) {
        is ByteArray -> value.decodeToString()
        is Long -> value.toString()
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Float -> if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        else -> null
    }
}

/**
 * 把响应字节按 [format] 解析成可取值的根节点. 无法解析时返回 `null`.
 */
internal fun parseResponse(bytes: ByteArray, format: ResponseFormat, json: Json): DataNode? = when (format) {
    ResponseFormat.Json -> runCatching { JsonNode(json.parseToJsonElement(bytes.decodeToString())) }.getOrNull()
    ResponseFormat.Protobuf -> ProtoNode(parseProto(bytes))
    ResponseFormat.ProtobufInJsonBytes -> ProtoNode(parseProto(unwrapJsonBytes(bytes, json)))
}

/**
 * 有的接口把 protobuf 字节包成 JSON 数字数组返回 (例如 `[10,140,2,...]`). 不是这种形式就原样返回.
 */
internal fun unwrapJsonBytes(raw: ByteArray, json: Json): ByteArray {
    val text = raw.decodeToString()
    if (!text.trimStart().startsWith("[")) return raw
    val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return raw
    return runCatching { ByteArray(array.size) { array[it].jsonPrimitive.int.toByte() } }.getOrElse { raw }
}

/**
 * 按路径取值.
 *
 * 路径是用 `.` 分隔的若干段, 每段可以带一个筛选:
 * - `sites.id` 或 `1.2`: 逐层往下取, 数组与 repeated 字段会自动展开
 * - `sites[site=bangumi].id` 或 `5[1=bangumi].2`: 只要某个子字段等于指定值的那些节点
 * - `items[0].url`: 按下标取
 *
 * 路径为空表示节点自身.
 */
internal fun DataNode.selectByPath(path: String): List<DataNode> {
    if (path.isBlank()) return listOf(this)
    var current = listOf(this)
    for (segment in parsePath(path)) {
        var next = if (segment.key.isEmpty()) current else current.flatMap { it.select(segment.key) }
        val filter = segment.filter
        if (filter != null) {
            next = next.filter { node -> node.select(filter.first).any { it.asStringOrNull() == filter.second } }
        }
        val index = segment.index
        if (index != null) {
            next = listOfNotNull(next.getOrNull(index))
        }
        current = next
        if (current.isEmpty()) return emptyList()
    }
    return current
}

internal fun DataNode.stringByPath(path: String): String? =
    selectByPath(path).firstOrNull()?.asStringOrNull()

private class PathSegment(
    val key: String,
    val filter: Pair<String, String>?,
    val index: Int?,
)

private fun parsePath(path: String): List<PathSegment> {
    val segments = ArrayList<PathSegment>()
    var i = 0
    while (i < path.length) {
        val key = StringBuilder()
        while (i < path.length && path[i] != '.' && path[i] != '[') {
            key.append(path[i])
            i++
        }
        var filter: Pair<String, String>? = null
        var index: Int? = null
        if (i < path.length && path[i] == '[') {
            val close = path.indexOf(']', i)
            if (close < 0) break
            val inside = path.substring(i + 1, close)
            val eq = inside.indexOf('=')
            if (eq >= 0) {
                filter = inside.substring(0, eq).trim() to inside.substring(eq + 1).trim()
            } else {
                index = inside.trim().toIntOrNull()
            }
            i = close + 1
        }
        segments.add(PathSegment(key.toString().trim(), filter, index))
        if (i < path.length && path[i] == '.') i++
    }
    return segments
}
