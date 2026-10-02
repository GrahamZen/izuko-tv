/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class ShareFolderListingsTest {
    private val time = TestTimeSource()
    private var listed = 0

    private fun listing(token: String = "t1") = ShareFolderListings.Listed(listOf(DriveFile("f${++listed}")), token)

    @Test
    fun `concurrent listings of the same folder hit the drive once`() = runTest {
        val listings = ShareFolderListings(timeSource = time)
        val gate = CompletableDeferred<Unit>()
        val first = async { listings.getOrList("s", "folder", "t1") { gate.await(); listing() } }
        val second = async { listings.getOrList("s", "folder", "t1") { listing() } }
        runCurrent()
        gate.complete(Unit)
        assertEquals(first.await().map { it.fid }, second.await().map { it.fid })
        assertEquals(1, listed)
    }

    @Test
    fun `different folders and share tokens list again`() = runTest {
        val listings = ShareFolderListings(timeSource = time)
        listings.getOrList("s", "a", "t1") { listing() }
        listings.getOrList("s", "b", "t1") { listing() }
        assertEquals(2, listed)
        // 文件的转存凭证只配列它时的分享令牌: 换了令牌重新列
        listings.getOrList("s", "a", "t2") { listing("t2") }
        assertEquals(3, listed)
        listings.getOrList("s", "a", "t2") { listing("t2") }
        assertEquals(3, listed)
    }

    @Test
    fun `listing expires after ttl`() = runTest {
        val listings = ShareFolderListings(ttl = 3.minutes, timeSource = time)
        listings.getOrList("s", "a", "t1") { listing() }
        time += 2.minutes
        listings.getOrList("s", "a", "t1") { listing() }
        assertEquals(1, listed)
        time += 2.minutes
        listings.getOrList("s", "a", "t1") { listing() }
        assertEquals(2, listed)
    }
}
