/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 分享里按名字搜文件夹 ([DriveShareBrowser.searchFolders]) 按协议的 [DriveApiConfig.searchShare] 发请求. */
class DriveShareFolderSearchTest {
    private val share = mapOf(
        TestDrive.SHARE_ROOT to listOf(dir("autumn", "2023 秋")),
        "autumn" to listOf(dir("frieren", "A 4k 葬送的芙莉莲"), video("pv", "葬送的芙莉莲 PV.mp4")),
    )

    @Test
    fun `folders are searched by name with the share token`() = runTest {
        val shares = FakeShares(mutableMapOf("s1" to share))
        val service = testDriveService(accountsOf(CloudDriveAccount())) { shares.respond(this, it) ?: error("unexpected request ${it.url}") }

        val found = assertNotNull(service.shareBrowser.searchFolders("s1", "", "葬送的芙莉莲"))
        // 文件不要; 父文件夹 id 带回来
        assertEquals(listOf("frieren" to "autumn"), found.map { it.fid to it.parentFid })
        assertEquals(listOf("s1" to "葬送的芙莉莲"), shares.searched)
    }

    @Test
    fun `the share browser used by share search sources searches folders too`() = runTest {
        val shares = FakeShares(mutableMapOf("s1" to share))
        val service = testDriveService(accountsOf(CloudDriveAccount())) { shares.respond(this, it) ?: error("unexpected request ${it.url}") }
        // 合集与搜网盘站的数据源用的是这个 (列目录排队、复用), 不是 shareBrowser
        val found = assertNotNull(service.shareSearchBrowser.searchFolders("s1", "", "葬送的芙莉莲"))
        assertEquals(listOf("frieren"), found.map { it.fid })
    }

    @Test
    fun `drives without share search say so without a request`() = runTest {
        val protocol = TestDrive.protocol.copy(api = TestDrive.protocol.api.copy(searchShare = null))
        val service = testDriveService(accountsOf(CloudDriveAccount()), protocol) { error("unexpected request ${it.url}") }
        assertNull(service.shareBrowser.searchFolders("s1", "", "葬送的芙莉莲"))
    }
}
