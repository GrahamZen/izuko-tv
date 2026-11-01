/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlinx.datetime.LocalDateTime
import kotlinx.io.files.Path
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.inSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 复现日志: 按时间段截 (跨天的滚动文件也算), 续行跟着上一行, 太长留后面的. */
class ReproLogBundleTest {
    private val dir: File = Files.createTempDirectory("repro-logs").toFile()
    private val logs: SystemPath get() = Path(dir.path).inSystem

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun write(name: String, vararg lines: String) = File(dir, name).writeText(lines.joinToString("\n", postfix = "\n"))

    private fun ReproLogBundle.Bundle.text() = GZIPInputStream(gzip.inputStream()).bufferedReader().readText()

    private fun at(text: String) = LocalDateTime.parse(text.replace(' ', 'T'))

    @Test
    fun `截时间段里的行，跨天的滚动文件也算，续行跟着上一行`() {
        write(
            "app.2026-10-14.log",
            "2026-10-14 23:59:00 INFO  a 早于时间段",
            "2026-10-14 23:59:58 INFO  b 头一天最后",
            "java.lang.IllegalStateException: boom",
            "\tat x.y(Z.kt:1)",
        )
        write("app.2026-10-10.log", "2026-10-10 12:00:00 INFO  z 不相干的一天")
        write(
            "app.log",
            "2026-10-15 00:00:01 INFO  c 当天",
            "2026-10-15 00:00:09 WARN  d 晚于时间段",
            "\tat 不要的续行",
        )
        val bundle = ReproLogBundle.build(logs, at("2026-10-14 23:59:30"), at("2026-10-15 00:00:05"), listOf("app" to "1.0.7"))
        val lines = bundle.text().lines().filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "# app: 1.0.7",
                "# window: 2026-10-14 23:59:30 .. 2026-10-15 00:00:05",
                "2026-10-14 23:59:58 INFO  b 头一天最后",
                "java.lang.IllegalStateException: boom",
                "\tat x.y(Z.kt:1)",
                "2026-10-15 00:00:01 INFO  c 当天",
            ),
            lines,
        )
        assertEquals(4, bundle.lines)
        assertFalse(bundle.truncated)
    }

    @Test
    fun `太长留后面的`() {
        write("app.log", *Array(100) { "2026-10-15 10:00:%02d INFO  line $it".format(it % 60) })
        val bundle = ReproLogBundle.build(logs, at("2026-10-15 10:00:00"), at("2026-10-15 10:01:00"), emptyList(), maxBytes = 200)
        val body = bundle.text().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(bundle.truncated)
        assertTrue(body.last().endsWith("line 99"))
        assertTrue(body.sumOf { it.length + 1 } <= 200)
        assertTrue(bundle.text().contains("truncated"))
    }

    @Test
    fun `几段时间 — 重叠的合并，报告原文放在日志前面`() {
        write(
            "app.log",
            "2026-10-15 10:00:00 INFO  a",
            "2026-10-15 10:05:00 INFO  b 第一段",
            "2026-10-15 10:07:00 INFO  c 第一段与第二段重叠",
            "2026-10-15 10:20:00 INFO  d 两段之间",
            "2026-10-15 10:30:00 INFO  e 第三段",
        )
        val bundle = ReproLogBundle.build(
            logs,
            listOf(
                at("2026-10-15 10:29:00") to at("2026-10-15 10:31:00"),
                at("2026-10-15 10:04:00") to at("2026-10-15 10:06:00"),
                at("2026-10-15 10:06:00") to at("2026-10-15 10:08:00"),
            ),
            listOf("app" to "1.0.8"),
            preface = "# ---- 报告 ----\n{\"kind\": \"health\"}",
        )
        assertEquals(
            listOf(
                "# app: 1.0.8",
                "# ---- 报告 ----",
                "{\"kind\": \"health\"}",
                "# window: 2026-10-15 10:04:00 .. 2026-10-15 10:08:00",
                "# window: 2026-10-15 10:29:00 .. 2026-10-15 10:31:00",
                "2026-10-15 10:05:00 INFO  b 第一段",
                "2026-10-15 10:07:00 INFO  c 第一段与第二段重叠",
                "2026-10-15 10:30:00 INFO  e 第三段",
            ),
            bundle.text().lines().filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `没有日志目录 — 只有表头`() {
        val bundle = ReproLogBundle.build(Path(File(dir, "missing").path).inSystem, at("2026-10-15 10:00:00"), at("2026-10-15 10:01:00"), listOf("描述" to "两行\n合成一行"))
        assertEquals(0, bundle.lines)
        assertEquals("# 描述: 两行 合成一行", bundle.text().lines().first())
    }
}
