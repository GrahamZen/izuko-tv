/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 用订阅里真实的分享搜索配置走一遍「查固定的分享 / 搜站点 -> 打开分享 -> 对集」. 只读, 不登录、不转存. 没配置时跳过.
 *
 * 网盘的协议与分享搜索数据源 (`cloud-drive-share-search`, 网盘 id 要对上) 都来自 `ANI_CLOUD_DRIVE_SUBSCRIPTION_FILE`
 * (见 [CloudDriveLiveConfig]). 条目名默认「葬送的芙莉莲」.
 *
 * ./gradlew :app:shared:app-data:desktopTest --tests 'me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveShareSearchLiveTest'
 */
class CloudDriveShareSearchLiveTest {
    @Test
    fun `finds episodes in shares found by the configured sources`() = runBlocking {
        val loaded = CloudDriveLiveConfig.load()
        if (loaded == null || loaded.shareSearches.isEmpty()) {
            println("[skip] ANI_CLOUD_DRIVE_SUBSCRIPTION_FILE not set or has no share search source for the drive")
            return@runBlocking
        }
        val names = CloudDriveLiveConfig.subjectNames(listOf("葬送的芙莉莲"))
        val protocol = loaded.drive.protocol
        val service = CloudDriveService(protocol, accountsOf(CloudDriveAccount.Default, protocol.id), CloudDriveApi.createHttpClient())
        val siteClient = CloudDriveApi.createHttpClient()

        val request = MediaFetchRequest(
            subjectId = "",
            episodeId = "",
            subjectNames = names,
            episodeSort = EpisodeSort(1),
            episodeName = "",
        )
        var found = 0
        for (arguments in loaded.shareSearches) {
            val engine = DriveShareSearchEngine(arguments.config, service.shareBrowser, service.shareLinks) { url ->
                runCatching { siteClient.get(url).readRawBytes() }.getOrNull()
            }
            val matches = engine.search(request)
            println("== ${arguments.name}: ${matches.size} episodes")
            matches.forEach {
                println("${it.episode}  ${it.share.siteTitle} | ${it.share.shareId} | ${(it.folders + it.file.fileName).joinToString("/")}  ${it.file.size / 1048576} MB")
            }
            found += matches.size
        }
        assertTrue(found > 0, "no episodes found for $names")
    }
}
