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
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用真实的网盘账号走一遍: 核对账号 -> 搜索 -> 匹配 -> 取直链 -> 带请求头读前 64 KiB. 没配置时跳过.
 *
 * 网盘的协议来自 `ANI_CLOUD_DRIVE_SUBSCRIPTION_FILE`, 登录 Cookie 来自 `ANI_CLOUD_DRIVE_COOKIE_FILE` (见 [CloudDriveLiveConfig]).
 * 条目名默认是「在超市后门吸烟的二人」及其别名.
 *
 * ./gradlew :app:shared:app-data:desktopTest --tests 'me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveLiveSmokeTest'
 */
class CloudDriveLiveSmokeTest {
    @Test
    fun `finds and opens videos in the drive`() = runBlocking {
        val cookieFile = CloudDriveLiveConfig.cookieFile
        val loaded = CloudDriveLiveConfig.load()
        if (loaded == null || cookieFile == null) {
            println("[skip] ANI_CLOUD_DRIVE_SUBSCRIPTION_FILE or ANI_CLOUD_DRIVE_COOKIE_FILE not set")
            return@runBlocking
        }
        val names = CloudDriveLiveConfig.subjectNames(
            listOf(
                "在超市后门吸烟的二人",
                "スーパーの裏でヤニ吸うふたり",
                "躲在超市后门抽烟的两人",
                "Super no Ura de Yani Suu Futari",
                "Smoking Behind the Supermarket with You",
            ),
        )
        val protocol = loaded.drive.protocol
        val settings = accountsOf(CloudDriveAccount(cookie = cookieFile.readText().trim()), protocol.id)
        val client = CloudDriveApi.createHttpClient()
        val service = CloudDriveService(protocol, settings, client)

        val account = service.refreshAccount()
        println("${protocol.id}: nickname=${account.nickname} tier=${account.tier} connections=${service.parallelConnectionsFor(account.tier)}")

        val request = MediaFetchRequest(
            subjectId = "",
            episodeId = "",
            subjectNames = names,
            episodeSort = EpisodeSort(1),
            episodeName = "",
        )
        val medias = CloudDriveMediaSource(service, loaded.drive).fetch(request).results.toList()
        medias.forEach { println("${it.media.episodeRange}  ${it.media.originalTitle}  ${it.media.properties.size}") }
        assertTrue(medias.isNotEmpty(), "no media found for $names")

        val first = medias.first().media
        val playback = service.resolvePlayback(assertNotNull(service.placeholders.fileIdOf(first.download.uri)))
        println("playback host: ${Url(playback.url).host}, subtitles: ${playback.subtitles.map { it.label }}")
        val response = client.get(playback.url) {
            playback.headers
                .filterKeys { it != PlaybackRequestHints.PARALLEL_RANGE_HEADER && it != PlaybackRequestHints.CACHE_KEY_HEADER }
                .forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.Range, "bytes=0-65535")
        }
        val bytes = response.bodyAsChannel().readRemaining(65536).readByteArray()
        println("range response: ${response.status} ${response.headers[HttpHeaders.ContentRange]} read=${bytes.size}")
        assertEquals(206, response.status.value)
        assertEquals(65536, bytes.size)

        cookieFile.writeText(settings.state.value.of(protocol.id).cookie)
    }
}
