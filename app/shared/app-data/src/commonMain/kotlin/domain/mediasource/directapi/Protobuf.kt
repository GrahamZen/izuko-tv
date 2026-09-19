/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

/**
 * 无 schema 的 protobuf 解析结果: 字段号 -> 该字段出现过的所有值.
 *
 * 值的类型: varint 为 [Long], 定长 64 位为 [Double], 定长 32 位为 [Float], 变长为 [ByteArray]
 * (可能是字符串, 也可能是嵌套消息, 由取值时按路径决定怎么解释).
 */
internal typealias ProtoFields = Map<Int, List<Any>>

/**
 * 解析 protobuf 字节流.
 *
 * 数据源接口通常没有公开 .proto, 字段含义只能从实际响应推断, 所以这里不做 schema 校验:
 * 遇到不认识的 wire type 就停止并返回已解析出的部分.
 */
internal fun parseProto(bytes: ByteArray): ProtoFields {
    val reader = ProtoReader(bytes)
    val result = LinkedHashMap<Int, MutableList<Any>>()
    while (reader.hasMore()) {
        val key = reader.readVarint()
        val field = (key ushr 3).toInt()
        if (field == 0) break
        val value: Any = when ((key and 7L).toInt()) {
            0 -> reader.readVarint()
            1 -> Double.fromBits(reader.readFixed64())
            2 -> reader.readBytes(reader.readVarint().toInt())
            5 -> Float.fromBits(reader.readFixed32())
            else -> break // 3/4 是已废弃的 group
        }
        result.getOrPut(field) { ArrayList(1) }.add(value)
    }
    return result
}

private class ProtoReader(private val bytes: ByteArray) {
    private var pos = 0

    fun hasMore() = pos < bytes.size

    fun readVarint(): Long {
        var shift = 0
        var value = 0L
        while (pos < bytes.size && shift < 64) {
            val b = bytes[pos++].toInt() and 0xff
            value = value or ((b and 0x7f).toLong() shl shift)
            if (b < 0x80) break
            shift += 7
        }
        return value
    }

    fun readFixed64(): Long {
        var value = 0L
        for (i in 0 until 8) {
            if (pos + i >= bytes.size) break
            value = value or ((bytes[pos + i].toLong() and 0xff) shl (i * 8))
        }
        pos = minOf(pos + 8, bytes.size)
        return value
    }

    fun readFixed32(): Int {
        var value = 0
        for (i in 0 until 4) {
            if (pos + i >= bytes.size) break
            value = value or ((bytes[pos + i].toInt() and 0xff) shl (i * 8))
        }
        pos = minOf(pos + 4, bytes.size)
        return value
    }

    fun readBytes(length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val end = minOf(pos + length, bytes.size)
        val result = bytes.copyOfRange(pos, end)
        pos = end
        return result
    }
}
