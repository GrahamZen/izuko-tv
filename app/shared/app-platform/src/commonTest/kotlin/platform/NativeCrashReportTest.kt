/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * tombstone (protobuf) 解成文字, 与 crash 缓冲区按时间取行. 测试数据按 AOSP tombstone.proto 的字段编号手工编码.
 */
class NativeCrashReportTest {
    @Test
    fun `tombstone 写出信号、abort 消息、崩溃线程的调用栈与最后几行日志`() {
        val frame0 = proto {
            varint(1, 0x4d2a8) // rel_pc
            string(4, "abort") // function_name
            varint(5, 168) // function_offset
            string(6, "/apex/com.android.runtime/lib/bionic/libc.so") // file_name
        }
        val frame1 = proto {
            varint(1, 0x1234)
            string(6, "/vendor/lib/libgpu.so")
        }
        val crashThread = proto {
            varint(1, 4321)
            string(2, "RenderThread")
            bytes(4, frame0)
            bytes(4, frame1)
        }
        val otherThread = proto {
            varint(1, 1234)
            string(2, "main")
        }
        val tombstone = proto {
            varint(5, 1234) // pid
            varint(6, 4321) // tid
            bytes(10, proto { string(2, "SIGABRT"); string(4, "SI_TKILL") })
            string(14, "GL error")
            bytes(16, proto { varint(1, 1234); bytes(2, otherThread) }) // threads 是 map: 键 1, 值 2
            bytes(16, proto { varint(1, 4321); bytes(2, crashThread) })
            bytes(
                18,
                proto {
                    string(1, "main")
                    bytes(2, proto { string(1, "10-14 21:08:12.900"); varint(2, 1234); varint(3, 4321); varint(4, 6); string(5, "BufferQueue"); string(6, "late") })
                    bytes(2, proto { string(1, "10-14 21:08:12.100"); varint(2, 1234); varint(3, 4321); varint(4, 5); string(5, "ACodec"); string(6, "early") })
                },
            )
        }

        val text = NativeCrashReport.describeTombstone(tombstone)

        assertEquals(
            """
            SIGABRT (SI_TKILL), abort message: 'GL error'; thread 'RenderThread' (tid 4321)
              #00 pc 0004d2a8  /apex/com.android.runtime/lib/bionic/libc.so (abort+168)
              #01 pc 00001234  /vendor/lib/libgpu.so
              last log lines of the process:
              10-14 21:08:12.100 1234 4321 W ACodec: early
              10-14 21:08:12.900 1234 4321 E BufferQueue: late
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `不认识的字段跳过, 截断的数据报错`() {
        val tombstone = proto {
            fixed64(3, 7) // 不用的 64 位字段
            fixed32(7, 9) // 不用的 32 位字段
            varint(6, 1)
            bytes(10, proto { string(2, "SIGSEGV"); varint(8, 1); varint(9, 0xdead) })
        }
        assertTrue(NativeCrashReport.describeTombstone(tombstone).startsWith("SIGSEGV, fault addr 0xdead; thread '?' (tid 1)"))

        assertFailsWith<IllegalStateException> { NativeCrashReport.describeTombstone(tombstone.copyOf(tombstone.size - 2)) }
    }

    @Test
    fun `crash 缓冲区只取上次写过之后的行`() {
        val lines = listOf(
            "--------- beginning of crash",
            "  1792016000.100  1234  4321 F libc    : Fatal signal 6 (SIGABRT)",
            "  1792016093.250  1234  4321 F libc    : Fatal signal 11 (SIGSEGV)",
            "  1792016093.300  5678  5678 F DEBUG   : backtrace:",
        )
        val (recent, newest) = NativeCrashReport.crashLogLinesAfter(lines, afterMillis = 1792016000100)
        assertEquals(lines.drop(2), recent)
        assertEquals(1792016093300, newest)

        val (none, unchanged) = NativeCrashReport.crashLogLinesAfter(lines, afterMillis = 1792016093300)
        assertEquals(emptyList(), none)
        assertEquals(1792016093300, unchanged)
    }

    private fun proto(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).toByteArray()

    private class ProtoWriter {
        private val out = ArrayList<Byte>()

        fun varint(field: Int, value: Long) {
            key(field, 0)
            writeVarint(value)
        }

        fun fixed64(field: Int, value: Long) {
            key(field, 1)
            repeat(8) { out += (value ushr (8 * it)).toByte() }
        }

        fun fixed32(field: Int, value: Int) {
            key(field, 5)
            repeat(4) { out += (value ushr (8 * it)).toByte() }
        }

        fun string(field: Int, value: String) = bytes(field, value.encodeToByteArray())

        fun bytes(field: Int, value: ByteArray) {
            key(field, 2)
            writeVarint(value.size.toLong())
            value.forEach { out += it }
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun key(field: Int, wireType: Int) = writeVarint((field shl 3 or wireType).toLong())

        private fun writeVarint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7fL.inv() == 0L) {
                    out += v.toByte()
                    return
                }
                out += ((v and 0x7f) or 0x80).toByte()
                v = v ushr 7
            }
        }
    }
}
