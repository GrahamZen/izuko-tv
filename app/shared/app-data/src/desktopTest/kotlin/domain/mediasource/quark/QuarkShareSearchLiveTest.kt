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
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.preference.QuarkConfig
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceDataList
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 用真实的站点与夸克分享走一遍「搜站点 -> 打开分享 -> 对集」. 只读, 不登录、不转存. 没配置时跳过.
 *
 * 环境变量:
 *   ANI_QUARK_SHARE_SOURCE_FILE  导出的数据源 JSON (含一个 quark-share-search 源), 站点地址只放在这个文件里
 *   ANI_QUARK_SHARE_SUBJECT      可选, 条目名 (多个用 | 分隔), 默认「葬送的芙莉莲」
 *
 * ./gradlew :app:shared:app-data:desktopTest --tests 'me.him188.ani.app.domain.mediasource.quark.QuarkShareSearchLiveTest'
 */
class QuarkShareSearchLiveTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    @Test
    fun `finds episodes in shares found by the site`() = runBlocking {
        val sourceFile = System.getenv("ANI_QUARK_SHARE_SOURCE_FILE")?.let(::File)
        if (sourceFile == null || !sourceFile.isFile) {
            println("[skip] ANI_QUARK_SHARE_SOURCE_FILE not set")
            return@runBlocking
        }
        val json = Json { ignoreUnknownKeys = true }
        val exported = json.decodeFromString(ExportedMediaSourceDataList.serializer(), sourceFile.readText())
        val data = exported.mediaSources.first { it.factoryId == QuarkShareSearchMediaSource.FactoryId }
        val arguments = json.decodeFromJsonElement(QuarkShareSearchArguments.serializer(), data.arguments)
        val names = System.getenv("ANI_QUARK_SHARE_SUBJECT")?.split('|') ?: listOf("葬送的芙莉莲")

        val quarkClient = QuarkApi.createHttpClient()
        val service = QuarkDriveService(MemorySettings(QuarkConfig.Default), quarkClient)
        val siteClient = QuarkApi.createHttpClient()
        val engine = QuarkShareSearchEngine(arguments.config, service.shareBrowser) { url ->
            runCatching { siteClient.get(url).readRawBytes() }.getOrNull()
        }

        val request = MediaFetchRequest(
            subjectId = "",
            episodeId = "",
            subjectNames = names,
            episodeSort = EpisodeSort(1),
            episodeName = "",
        )
        val matches = engine.search(request)
        matches.forEach {
            println("${it.episode}  ${it.share.siteTitle} | ${it.share.shareId} | ${(it.folders + it.file.fileName).joinToString("/")}  ${it.file.size / 1048576} MB")
        }
        assertTrue(matches.isNotEmpty(), "no episodes found for $names")
    }
}
