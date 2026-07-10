/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.api

import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归测试: 回收种子目录必须**先失效 fastresume 再删数据文件**.
 *
 * 递归删除不是原子的, 半途失败留下"陈旧 fastresume + 残缺数据"时, 新会话会信任旧 piece 位图秒判
 * "已完成", 把被删文件按稀疏文件重建并交给播放器 —— 真机上表现为"重新缓存立刻完成但播放失败".
 */
class ReclaimTorrentSaveDirTest {
    @TempDir
    private lateinit var dir: File

    @Test
    fun `deletes fast resume file together with data`() {
        val fastResume = File(dir, TORRENT_FAST_RESUME_FILENAME).apply { writeBytes(byteArrayOf(1)) }
        val data = File(dir, "01.mkv").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val warnings = mutableListOf<String>()
        val started = dir.toKtPath().inSystem.reclaimTorrentSaveDir { message, _ -> warnings += message }

        assertTrue(started)
        assertFalse(fastResume.exists())
        assertFalse(data.exists())
        assertFalse(dir.exists())
        assertTrue(warnings.isEmpty(), "正常路径不应有告警: $warnings")
    }

    @Test
    fun `keeps data untouched when fast resume cannot be deleted`() {
        // 用"非空目录"占住 fastresume 这个名字: 删除它必定失败 (各平台一致), 以此模拟删不掉的情况.
        val fastResume = File(dir, TORRENT_FAST_RESUME_FILENAME).apply { mkdirs() }
        File(fastResume, "blocker").writeBytes(byteArrayOf(1))
        val data = File(dir, "01.mkv").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val warnings = mutableListOf<String>()
        val started = dir.toKtPath().inSystem.reclaimTorrentSaveDir { message, _ -> warnings += message }

        assertFalse(started, "fastresume 删不掉时必须报告没有开始删数据")
        assertTrue(data.exists(), "fastresume 还在时一个数据文件都不能删 —— 否则重缓存会秒判完成并交出坏文件")
        assertTrue(fastResume.exists())
        assertContains(warnings.single(), "fast resume")
    }

    @Test
    fun `succeeds when there is no fast resume file`() {
        val data = File(dir, "01.mkv").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val warnings = mutableListOf<String>()
        val started = dir.toKtPath().inSystem.reclaimTorrentSaveDir { message, _ -> warnings += message }

        assertTrue(started)
        assertFalse(data.exists())
        assertTrue(warnings.isEmpty(), "正常路径不应有告警: $warnings")
    }
}
