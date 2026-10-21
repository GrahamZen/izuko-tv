/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent.session

import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.FilePriority.HIGH
import me.him188.ani.app.torrent.api.files.FilePriority.IGNORE
import me.him188.ani.app.torrent.api.files.FilePriority.LOW
import me.him188.ani.app.torrent.api.files.FilePriority.NORMAL
import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentFileDownloadOrderTest {
    private fun request(path: String, priority: FilePriority, downloaded: Boolean = false) =
        FileDownloadRequest(path, priority, downloaded)

    @Test
    fun `cached files download one at a time in episode order`() {
        val files = listOf(
            request("Pack - 10.mkv", NORMAL),
            request("Pack - 2.mkv", NORMAL),
            request("Pack - 1.mkv", NORMAL),
        )
        assertEquals(listOf(LOW, LOW, NORMAL), orderedPriorities(files))
    }

    @Test
    fun `a downloaded file gives its turn to the next`() {
        val files = listOf(
            request("Pack - 01.mkv", NORMAL, downloaded = true),
            request("Pack - 02.mkv", NORMAL),
            request("Pack - 03.mkv", NORMAL),
        )
        assertEquals(listOf(NORMAL, NORMAL, LOW), orderedPriorities(files))
    }

    @Test
    fun `playback neither waits nor takes the first place`() {
        assertEquals(
            listOf(NORMAL, HIGH, LOW),
            orderedPriorities(listOf(request("Pack - 01.mkv", NORMAL), request("Pack - 02.mkv", HIGH), request("Pack - 03.mkv", NORMAL))),
        )
        assertEquals(
            listOf(HIGH, NORMAL),
            orderedPriorities(listOf(request("Pack - 01.mkv", HIGH), request("Pack - 02.mkv", NORMAL))),
        )
    }

    @Test
    fun `paused and untouched files are not queued`() {
        val files = listOf(
            request("Pack - 01.mkv", IGNORE),
            request("Pack - 02.mkv", NORMAL),
            request("Pack - 03.mkv", NORMAL),
            request("Pack - 04.mkv", IGNORE),
        )
        assertEquals(listOf(IGNORE, NORMAL, LOW, IGNORE), orderedPriorities(files))
    }

    @Test
    fun `a single cached file downloads as requested`() {
        assertEquals(listOf(NORMAL), orderedPriorities(listOf(request("movie.mkv", NORMAL))))
        assertEquals(listOf(NORMAL, IGNORE), orderedPriorities(listOf(request("b.mkv", NORMAL), request("a.mkv", IGNORE))))
    }

    @Test
    fun `numbers in paths compare by value`() {
        val sorted = listOf("S01E10.mkv", "S01E02.mkv", "S02E01.mkv", "S01E1.mkv", "ep 9.mkv", "ep 10.mkv", "Ep 3.mkv")
            .sortedWith(NaturalPathOrder)
        assertEquals(listOf("Ep 3.mkv", "ep 9.mkv", "ep 10.mkv", "S01E1.mkv", "S01E02.mkv", "S01E10.mkv", "S02E01.mkv"), sorted)
    }

    @Test
    fun `paths that differ only in padding or case still have a fixed order`() {
        assertEquals(listOf("a01", "a1"), listOf("a1", "a01").sortedWith(NaturalPathOrder))
        assertEquals(listOf("a01", "a1"), listOf("a01", "a1").sortedWith(NaturalPathOrder))
        assertEquals(listOf("A", "a"), listOf("a", "A").sortedWith(NaturalPathOrder))
        assertEquals(0, NaturalPathOrder.compare("x 2", "x 2"))
    }
}
