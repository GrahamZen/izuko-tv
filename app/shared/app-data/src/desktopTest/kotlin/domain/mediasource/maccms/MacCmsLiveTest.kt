/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.maccms

import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceDataList
import me.him188.ani.app.domain.mediasource.quark.QuarkApi
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 用真实的资源站接口走一遍「搜索 -> 对片名 -> 对集」. 只读. 没配置时跳过.
 *
 * 环境变量:
 *   ANI_MACCMS_SOURCE_FILE  导出的数据源 JSON (含若干 maccms 源), 站点地址只放在这个文件里
 *   ANI_MACCMS_SUBJECT      可选, 条目名与集数 (`名字:集数`, 多个用 | 分隔), 默认「葬送的芙莉莲 第二季:10」
 *
 * ./gradlew :app:shared:app-data:desktopTest --tests 'me.him188.ani.app.domain.mediasource.maccms.MacCmsLiveTest'
 */
class MacCmsLiveTest {
    @Test
    fun `finds episodes on real sites`() = runBlocking {
        val sourceFile = System.getenv("ANI_MACCMS_SOURCE_FILE")?.let(::File)
        if (sourceFile == null || !sourceFile.isFile) {
            println("[skip] ANI_MACCMS_SOURCE_FILE not set")
            return@runBlocking
        }
        val json = Json { ignoreUnknownKeys = true }
        val sources = json.decodeFromString(ExportedMediaSourceDataList.serializer(), sourceFile.readText()).mediaSources
            .filter { it.factoryId == MacCmsMediaSource.FactoryId }
            .map { json.decodeFromJsonElement(MacCmsMediaSourceArguments.serializer(), it.arguments) }
        val subjects = (System.getenv("ANI_MACCMS_SUBJECT") ?: "葬送的芙莉莲 第二季:10").split('|').map { spec ->
            spec.substringBefore(':') to spec.substringAfter(':', "12").toInt()
        }
        val client = QuarkApi.createHttpClient()
        var found = 0
        for ((name, count) in subjects) {
            val request = MediaFetchRequest(
                subjectId = "1",
                episodeId = "1",
                subjectNames = listOf(name),
                episodeSort = EpisodeSort(1),
                episodeName = "",
                episodes = (1..count).map { MediaFetchRequest.Episode("e$it", EpisodeSort(it)) },
            )
            for (arguments in sources) {
                val engine = MacCmsEngine(arguments.config, arguments.name) { url -> client.get(url).readRawBytes() }
                val result = runCatching { engine.queryLinks(request) }
                result.onSuccess { links ->
                    if (links.isNotEmpty()) found++
                    val episodes = links.mapNotNull { it.episodeRange?.knownSorts?.singleOrNull() }.distinct()
                    println("$name | ${arguments.name}: ${links.size} links, episodes ${episodes.joinToString(",")} | ${links.firstOrNull()?.title} ${links.firstOrNull()?.url}")
                }.onFailure {
                    println("$name | ${arguments.name}: FAILED $it")
                }
            }
        }
        assertTrue(found > 0, "no source found anything")
    }
}
