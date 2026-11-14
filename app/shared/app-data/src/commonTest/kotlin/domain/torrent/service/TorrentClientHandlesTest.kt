/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.service

import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentClientHandlesTest {
    private val alive = mutableSetOf<Int>()
    private val checked = mutableListOf<Int>()
    private val handles = TorrentClientHandles<String, String> { pid ->
        checked += pid
        pid in alive
    }

    @Test
    fun `handles a gone app process left on a file are handed back when a new process opens that file`() {
        alive += 100
        assertEquals(emptyList(), handles.register("old-e1", "e1", 100))
        assertEquals(emptyList(), handles.register("old-e1-again", "e1", 100))
        assertEquals(emptyList(), handles.register("old-e2", "e2", 100))

        // 应用进程 100 重启成 200, 恢复 e1: e1 上 100 留下的两个交出来关掉; e2 上的等 200 开 e2 时再交
        alive -= 100
        alive += 200
        assertEquals(setOf("old-e1", "old-e1-again"), handles.register("new-e1", "e1", 200).toSet())
        assertEquals(2, handles.size)
        assertEquals(listOf("old-e2"), handles.register("new-e2", "e2", 200))
        assertEquals(2, handles.size)
    }

    @Test
    fun `handles of a process that is still alive are kept`() {
        alive += setOf(100, 200)
        handles.register("a", "e1", 100)
        assertEquals(emptyList(), handles.register("b", "e1", 200))
        assertEquals(2, handles.size)
    }

    @Test
    fun `a closed handle is forgotten and each other process is checked once`() {
        alive += 100
        handles.register("a1", "e1", 100)
        handles.register("a2", "e1", 100)
        handles.register("a3", "e1", 100)
        handles.unregister("a3")
        alive -= 100
        checked.clear()
        assertEquals(setOf("a1", "a2"), handles.register("b", "e1", 200).toSet())
        assertEquals(listOf(100), checked)
        assertEquals(1, handles.size)
    }
}
