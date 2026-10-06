/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

/**
 * 安卓原生崩溃 (SIGABRT / SIGSEGV 等, Java 层的未捕获异常处理器接不到) 现场的文字摘要, 由安卓端启动时写进日志 (androidMain 的 `NativeCrashTrace`).
 * 系统的退出记录只说「CRASH_NATIVE, status=6」, 有了调用栈才知道是哪个库崩的.
 *
 * 两种来源: Android 12 起退出记录里带 tombstone (protobuf, [describeTombstone]); 更早的系统读 crash 日志缓冲区 ([crashLogLinesAfter]).
 * 纯数据处理, 放在 commonMain 便于测试.
 */
internal object NativeCrashReport {
    /** 崩溃线程的调用栈最多写这么多层. */
    const val MAX_FRAMES = 32

    /** 崩溃前本进程的系统日志最多写这么多行. */
    const val MAX_LOG_LINES = 40

    /** crash 缓冲区一次最多写这么多行. */
    const val MAX_CRASH_LOG_LINES = 200

    /**
     * 把 tombstone 解成几行字: 信号与 abort 消息、崩溃线程名与调用栈、崩溃前本进程最后几行系统日志 (厂商解码器、显卡驱动的报错多半在这里).
     * 字段编号见 AOSP `system/core/debuggerd/proto/tombstone.proto`, 只解用得到的几个.
     */
    fun describeTombstone(bytes: ByteArray): String {
        val tombstone = ProtoMessage(bytes)
        val tid = tombstone.long(TOMBSTONE_TID)
        val signal = tombstone.message(TOMBSTONE_SIGNAL)
        val thread = tombstone.messages(TOMBSTONE_THREADS)
            .firstOrNull { it.long(MAP_KEY) == tid }
            ?.message(MAP_VALUE)
        return buildString {
            append(signal?.string(SIGNAL_NAME) ?: "unknown signal")
            signal?.string(SIGNAL_CODE_NAME)?.takeIf { it.isNotEmpty() }?.let { append(" (").append(it).append(')') }
            if (signal?.long(SIGNAL_HAS_FAULT_ADDRESS) == 1L) {
                append(", fault addr 0x").append(signal.long(SIGNAL_FAULT_ADDRESS)?.toULong()?.toString(16))
            }
            tombstone.string(TOMBSTONE_ABORT_MESSAGE)?.takeIf { it.isNotEmpty() }?.let { append(", abort message: '").append(it).append('\'') }
            for (cause in tombstone.messages(TOMBSTONE_CAUSES)) {
                cause.string(CAUSE_HUMAN_READABLE)?.let { append(", cause: ").append(it) }
            }
            append("; thread '").append(thread?.string(THREAD_NAME) ?: "?").append("' (tid ").append(tid).append(')')

            thread?.messages(THREAD_BACKTRACE)?.take(MAX_FRAMES)?.forEachIndexed { index, frame ->
                append("\n  #").append(index.toString().padStart(2, '0'))
                append(" pc ").append((frame.long(FRAME_REL_PC) ?: 0L).toULong().toString(16).padStart(8, '0'))
                append("  ").append(frame.string(FRAME_FILE_NAME).orEmpty())
                frame.string(FRAME_FUNCTION_NAME)?.takeIf { it.isNotEmpty() }?.let { function ->
                    append(" (").append(function).append('+').append(frame.long(FRAME_FUNCTION_OFFSET) ?: 0L).append(')')
                }
            }

            val logs = tombstone.messages(TOMBSTONE_LOG_BUFFERS)
                .flatMap { it.messages(LOG_BUFFER_LOGS) }
                .sortedBy { it.string(LOG_TIMESTAMP).orEmpty() }
                .takeLast(MAX_LOG_LINES)
            if (logs.isNotEmpty()) append("\n  last log lines of the process:")
            for (log in logs) {
                append("\n  ").append(log.string(LOG_TIMESTAMP).orEmpty())
                append(' ').append(log.long(LOG_PID) ?: 0L).append(' ').append(log.long(LOG_TID) ?: 0L)
                append(' ').append(priorityLetter(log.long(LOG_PRIORITY)))
                append(' ').append(log.string(LOG_TAG).orEmpty()).append(": ").append(log.string(LOG_MESSAGE).orEmpty())
            }
        }
    }

    /**
     * `logcat -v epoch -b crash` 输出里时间晚于 [afterMillis] 的行 (最多最后 [MAX_CRASH_LOG_LINES] 行), 以及其中最晚的时间 (没有时为 [afterMillis]).
     * 每行以「秒.毫秒」开头; 认不出时间的行 (缓冲区分隔行) 跳过.
     */
    fun crashLogLinesAfter(lines: List<String>, afterMillis: Long): Pair<List<String>, Long> {
        var newest = afterMillis
        val result = lines.filter { line ->
            val millis = epochMillisOf(line) ?: return@filter false
            if (millis <= afterMillis) return@filter false
            if (millis > newest) newest = millis
            true
        }
        return result.takeLast(MAX_CRASH_LOG_LINES) to newest
    }

    private fun epochMillisOf(line: String): Long? {
        val token = line.trimStart().substringBefore(' ')
        val seconds = token.substringBefore('.').toLongOrNull() ?: return null
        val millis = token.substringAfter('.', "").padEnd(3, '0').take(3).toLongOrNull() ?: return null
        return seconds * 1000 + millis
    }

    private fun priorityLetter(priority: Long?): Char = when (priority) {
        2L -> 'V'
        3L -> 'D'
        4L -> 'I'
        5L -> 'W'
        6L -> 'E'
        7L -> 'F'
        else -> '?'
    }

    // tombstone.proto 里的字段编号
    private const val TOMBSTONE_TID = 6
    private const val TOMBSTONE_SIGNAL = 10
    private const val TOMBSTONE_ABORT_MESSAGE = 14
    private const val TOMBSTONE_CAUSES = 15
    private const val TOMBSTONE_THREADS = 16
    private const val TOMBSTONE_LOG_BUFFERS = 18
    private const val MAP_KEY = 1
    private const val MAP_VALUE = 2
    private const val SIGNAL_NAME = 2
    private const val SIGNAL_CODE_NAME = 4
    private const val SIGNAL_HAS_FAULT_ADDRESS = 8
    private const val SIGNAL_FAULT_ADDRESS = 9
    private const val CAUSE_HUMAN_READABLE = 1
    private const val THREAD_NAME = 2
    private const val THREAD_BACKTRACE = 4
    private const val FRAME_REL_PC = 1
    private const val FRAME_FUNCTION_NAME = 4
    private const val FRAME_FUNCTION_OFFSET = 5
    private const val FRAME_FILE_NAME = 6
    private const val LOG_BUFFER_LOGS = 2
    private const val LOG_TIMESTAMP = 1
    private const val LOG_PID = 2
    private const val LOG_TID = 3
    private const val LOG_PRIORITY = 4
    private const val LOG_TAG = 5
    private const val LOG_MESSAGE = 6
}

/**
 * 最小的 protobuf 读取, 只为 [NativeCrashReport.describeTombstone]: 不需要 schema, 数值字段读成 [Long], 长度前缀字段读成子消息 (也可当字符串读).
 * 用到哪层才解哪层.
 */
internal class ProtoMessage(
    private val bytes: ByteArray,
    private val from: Int = 0,
    private val to: Int = bytes.size,
) {
    private val fields: Map<Int, List<Any>> by lazy(LazyThreadSafetyMode.NONE) { parse() }

    fun long(field: Int): Long? = fields[field]?.lastOrNull() as? Long
    fun message(field: Int): ProtoMessage? = fields[field]?.lastOrNull() as? ProtoMessage
    fun messages(field: Int): List<ProtoMessage> = fields[field].orEmpty().filterIsInstance<ProtoMessage>()
    fun string(field: Int): String? = message(field)?.text()
    fun text(): String = bytes.decodeToString(from, to)

    private fun parse(): Map<Int, List<Any>> {
        val result = HashMap<Int, MutableList<Any>>()
        var pos = from

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                check(pos < to) { "truncated varint" }
                val byte = bytes[pos++].toInt()
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
                check(shift < 64) { "varint too long" }
            }
        }

        fun fixed(size: Int): Long {
            check(pos + size <= to) { "truncated fixed field" }
            var value = 0L
            for (i in 0 until size) value = value or ((bytes[pos + i].toLong() and 0xff) shl (8 * i))
            pos += size
            return value
        }

        while (pos < to) {
            val key = varint()
            val field = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> varint()
                1 -> fixed(8)
                2 -> {
                    val length = varint().toInt()
                    check(length >= 0 && pos + length <= to) { "truncated field $field" }
                    ProtoMessage(bytes, pos, pos + length).also { pos += length }
                }

                5 -> fixed(4)
                else -> error("unsupported wire type in field $field")
            }
            result.getOrPut(field) { ArrayList(1) }.add(value)
        }
        return result
    }
}
