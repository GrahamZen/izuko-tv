/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import me.him188.ani.app.data.models.preference.QuarkConfig
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用真实的夸克账号走一遍: 搜索 -> 匹配 -> 取直链 -> 带请求头读前 64 KiB. 没配置时跳过.
 *
 * 环境变量:
 *   ANI_QUARK_COOKIE_FILE  存着登录 Cookie 的文件
 *   ANI_QUARK_SUBJECT      可选, 条目名 (多个用 | 分隔), 默认是「在超市后门吸烟的二人」及其别名
 *
 * ./gradlew :app:shared:app-data:desktopTest --tests 'me.him188.ani.app.domain.mediasource.quark.QuarkLiveSmokeTest'
 */
class QuarkLiveSmokeTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    @Test
    fun `finds and opens videos in the drive`() = runBlocking {
        val cookieFile = System.getenv("ANI_QUARK_COOKIE_FILE")?.let(::File)
        if (cookieFile == null || !cookieFile.isFile) {
            println("[skip] ANI_QUARK_COOKIE_FILE not set")
            return@runBlocking
        }
        val names = System.getenv("ANI_QUARK_SUBJECT")?.split('|')
            ?: listOf(
                "在超市后门吸烟的二人",
                "スーパーの裏でヤニ吸うふたり",
                "躲在超市后门抽烟的两人",
                "Super no Ura de Yani Suu Futari",
                "Smoking Behind the Supermarket with You",
            )
        val settings = MemorySettings(QuarkConfig(cookie = cookieFile.readText().trim()))
        val client = QuarkApi.createHttpClient()
        val service = QuarkDriveService(settings, client)

        val account = service.refreshAccount()
        println("account: nickname=${account.nickname} member=${account.memberType}")

        val request = MediaFetchRequest(
            subjectId = "",
            episodeId = "",
            subjectNames = names,
            episodeSort = EpisodeSort(1),
            episodeName = "",
        )
        val medias = QuarkMediaSource(service).fetch(request).results.toList()
        medias.forEach { println("${it.media.episodeRange}  ${it.media.originalTitle}  ${it.media.properties.size}") }
        assertTrue(medias.isNotEmpty(), "no media found for $names")

        val first = medias.first().media
        val playback = service.resolvePlayback(QuarkMediaSource.fileIdOf(first.download.uri)!!)
        println("playback host: ${Url(playback.url).host}")
        val response = client.get(playback.url) {
            playback.headers.forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.Range, "bytes=0-65535")
        }
        val bytes = response.bodyAsChannel().readRemaining(65536).readByteArray()
        println("range response: ${response.status} ${response.headers[HttpHeaders.ContentRange]} read=${bytes.size}")
        assertEquals(206, response.status.value)
        assertEquals(65536, bytes.size)

        cookieFile.writeText(settings.state.value.cookie)
    }
}
