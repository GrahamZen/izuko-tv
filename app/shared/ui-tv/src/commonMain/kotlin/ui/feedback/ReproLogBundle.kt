/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.zip.GZIPOutputStream

/**
 * 故障工单的附件: 附上的性能诊断报告原文 (调用方给的 preface) 加上几段时间里的应用日志, 前面几行设备与版本信息, gzip 压缩.
 *
 * - 日志在 [logsDir] 下: 当天的 `app.log` 与按天滚动的 `app.<yyyy-MM-dd>.log` (保留几天); 每行以本地时间 `yyyy-MM-dd HH:mm:ss` 开头,
 *   堆栈之类不带时间的续行跟着上一行走 (上一行在时间段里它就在).
 * - 时间段可以有好几段 (复现录制、各份性能报告各一段), 重叠的合并; 只按时间截, 不扫也不改内容 (日志本身已去掉 Cookie 之类).
 * - 截出来超过 [MAX_BYTES] 时丢前面的、留后面的: 离出问题的那一刻最近的最有用.
 */
object ReproLogBundle {
    /** [gzip] 压缩后的日志; [lines] 截到的行数; [truncated] = 太长丢了前面一部分. */
    class Bundle(val gzip: ByteArray, val lines: Int, val truncated: Boolean)

    /** 截出来最多多少字节 (压缩前); 压缩后一般不到十分之一. */
    const val MAX_BYTES = 16 * 1024 * 1024

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val ROLLED = Regex("""app\.(\d{4}-\d{2}-\d{2})\.log""")

    /** 截 [from]..[to] 一段, 见另一个 [build]. */
    fun build(
        logsDir: File,
        from: LocalDateTime,
        to: LocalDateTime,
        header: List<Pair<String, String>>,
        maxBytes: Int = MAX_BYTES,
        preface: String? = null,
    ): Bundle = build(logsDir, listOf(from to to), header, maxBytes, preface)

    /**
     * 截 [windows] 里各段 (本地时间, 两头都含; 重叠的合并) 的日志. [header] 是放在最前面的几行 (`# 键: 值`), 如版本号、设备型号、用户的描述;
     * [preface] 原样放在表头之后、日志之前 (如性能诊断报告的原文). 没有时间段时只有表头与 [preface].
     */
    fun build(
        logsDir: File,
        windows: List<Pair<LocalDateTime, LocalDateTime>>,
        header: List<Pair<String, String>>,
        maxBytes: Int = MAX_BYTES,
        preface: String? = null,
    ): Bundle {
        val merged = merge(windows)
        val kept = ArrayDeque<String>()
        var size = 0L
        var truncated = false
        if (merged.isNotEmpty()) {
            for (file in filesCovering(logsDir, merged.first().first.toLocalDate(), merged.last().second.toLocalDate())) {
                var inWindow = false
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val time = timeOf(line)
                        if (time != null) inWindow = merged.any { (from, to) -> !time.isBefore(from) && !time.isAfter(to) }
                        if (!inWindow) continue
                        kept.addLast(line)
                        size += line.length + 1
                        while (size > maxBytes && kept.isNotEmpty()) {
                            size -= kept.removeFirst().length + 1
                            truncated = true
                        }
                    }
                }
            }
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).bufferedWriter().use { w ->
            for ((key, value) in header) w.append("# ").append(key).append(": ").append(value.replace('\n', ' ')).append('\n')
            if (preface != null) w.append(preface).append('\n')
            for ((from, to) in merged) w.append("# window: ").append(from.format(TIME)).append(" .. ").append(to.format(TIME)).append('\n')
            if (truncated) w.append("# truncated, earliest lines dropped\n")
            for (line in kept) w.append(line).append('\n')
        }
        return Bundle(out.toByteArray(), kept.size, truncated)
    }

    /** 按开始排好, 重叠或相接的合成一段. */
    private fun merge(windows: List<Pair<LocalDateTime, LocalDateTime>>): List<Pair<LocalDateTime, LocalDateTime>> {
        val result = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        for ((from, to) in windows.filter { !it.first.isAfter(it.second) }.sortedBy { it.first }) {
            val last = result.lastOrNull()
            if (last != null && !from.isAfter(last.second)) {
                if (to.isAfter(last.second)) result[result.lastIndex] = last.first to to
            } else {
                result += from to to
            }
        }
        return result
    }

    /** 还留着的滚动日志里最早的一天; 只有当天的 `app.log` 时为 `null`. 早于这一天的时间段已经截不到了. */
    fun earliestRolledDay(logsDir: File): LocalDate? =
        logsDir.listFiles().orEmpty()
            .mapNotNull { f -> ROLLED.matchEntire(f.name)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }
            .minOrNull()

    /** 时间段涉及的日志文件, 按时间先后: 日期落在 [fromDay]..[toDay] 的滚动文件, 最后是 `app.log` (当天的). */
    private fun filesCovering(dir: File, fromDay: LocalDate, toDay: LocalDate): List<File> {
        val rolled = dir.listFiles().orEmpty().mapNotNull { f ->
            val day = ROLLED.matchEntire(f.name)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (day == null || day.isBefore(fromDay) || day.isAfter(toDay)) null else day to f
        }.sortedBy { it.first }.map { it.second }
        return rolled + listOfNotNull(File(dir, "app.log").takeIf { it.isFile })
    }

    private fun timeOf(line: String): LocalDateTime? {
        if (line.length < 19 || line[4] != '-' || line[13] != ':') return null
        return try {
            LocalDateTime.parse(line.substring(0, 19), TIME)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
