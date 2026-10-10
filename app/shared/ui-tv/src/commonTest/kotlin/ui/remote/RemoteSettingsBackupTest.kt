/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import me.him188.ani.app.data.models.danmaku.DanmakuRegexFilter
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepository
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepositoryImpl
import me.him188.ani.app.data.repository.user.PreferencesRepositoryImpl
import me.him188.ani.app.data.repository.user.SettingsBackupCodec
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.data.repository.user.TokenSave
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设置备份 ([SettingsBackupCodec] + Web 控制台的 [RemoteSettingsBackup]): 格式与设置页复制到剪贴板的那份一致 (对照 ui-settings 里
 * `SettingsViewModel` 的 `SettingsBackup`), 导出再导入还原得了, 不是备份的内容什么都不写.
 */
class RemoteSettingsBackupTest {
    private class Repos {
        val settings: SettingsRepository = PreferencesRepositoryImpl(MemoryDataStore(mutablePreferencesOf()))
        val filters: DanmakuRegexFilterRepository = DanmakuRegexFilterRepositoryImpl(MemoryDataStore(emptyList()))
        val tokens = TokenRepository(MemoryDataStore(TokenSave.Initial))
        val codec = SettingsBackupCodec(settings, filters, tokens)
    }

    /** 设置页那份备份的序列化器 (`SettingsViewModel.kt` 里的私有类, 按类名取) */
    @Suppress("UNCHECKED_CAST")
    private val upstream: KSerializer<Any> =
        serializer(Class.forName("me.him188.ani.app.ui.settings.SettingsBackup")) as KSerializer<Any>

    /** 同设置页那份的 Json 配置 */
    private val json = Json { ignoreUnknownKeys = true }

    private val token = json.decodeFromString(
        TokenSave.serializer(),
        """{"refreshToken":"r-token","accessTokens":{"bangumiAccessToken":"b-token","aniAccessToken":"","expiresAtMillis":1790000000000},"loginFlowVersion":1}""",
    )

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `export has the same fields and encoding as the settings page backup`() = runBlocking {
        val repos = Repos()
        repos.tokens.restoreFromTokenSave(token)
        val exported = repos.codec.export()

        val keys = json.parseToJsonElement(exported).jsonObject.keys
        val upstreamKeys = (0 until upstream.descriptor.elementsCount).map { upstream.descriptor.getElementName(it) }
        assertEquals(upstreamKeys, keys.toList())
        // 设置页那边读得懂, 再写出来一字不差
        assertEquals(exported, json.encodeToString(upstream, json.decodeFromString(upstream, exported)))
    }

    /** 缺哪个字段时读不了, 两边一致 (老备份能不能导入, 结果相同) */
    @Test
    fun `required fields match the settings page backup`() = runBlocking {
        val full = json.parseToJsonElement(Repos().codec.export()).jsonObject
        for (key in full.keys) {
            val partial = JsonObject(full - key).toString()
            val upstreamOk = runCatching { json.decodeFromString(upstream, partial) }.isSuccess
            val ours = runCatching { Repos().codec.restore(partial) }
            assertEquals(upstreamOk, ours.isSuccess, "缺 $key 时: 设置页 ${if (upstreamOk) "能" else "不能"}读")
        }
    }

    @Test
    fun `export then restore brings the settings back`() = runBlocking {
        val source = Repos()
        source.settings.danmakuEnabled.set(false)
        source.settings.anitorrentConfig.update { copy(extraTrackers = "udp://a.example:1337/announce") }
        source.settings.torrentPeerConfig.update { copy(ipBlackList = listOf("1.2.3.4")) }
        source.settings.pikpakConfig.set(PikPakConfig(enabled = true, username = "me@example.com", password = "secret"))
        source.filters.add(DanmakuRegexFilter(id = "1", name = "", regex = "剧透", enabled = true))
        source.tokens.restoreFromTokenSave(token)
        val exported = source.codec.export()
        // PikPak 账号不出备份
        assertFalse(exported.contains("secret"))
        assertFalse(exported.contains("me@example.com"))

        val target = Repos()
        target.settings.pikpakConfig.set(PikPakConfig(username = "kept"))
        target.codec.restore(exported)

        assertFalse(target.settings.danmakuEnabled.flow.first())
        assertEquals("udp://a.example:1337/announce", target.settings.anitorrentConfig.flow.first().extraTrackers)
        assertEquals(listOf("1.2.3.4"), target.settings.torrentPeerConfig.flow.first().ipBlackList)
        assertEquals(listOf("剧透"), target.filters.flow.first().map { it.regex })
        assertEquals(token, target.tokens.getTokenSaveSnapshot())
        // 导入不动 PikPak 账号
        assertEquals("kept", target.settings.pikpakConfig.flow.first().username)
    }

    @Test
    fun `content that is not a backup writes nothing`() = runBlocking {
        val repos = Repos()
        for (bad in listOf("", "not json", "[]", """{"format":"izuko-tv-profile","collections":[]}""")) {
            assertFailsWith<IllegalArgumentException> { repos.codec.restore(bad) }
        }
        assertTrue(repos.settings.danmakuEnabled.flow.first())
    }

    @Test
    fun `console exports a file and imports it back`() {
        val repos = Repos()
        startKoin {
            modules(
                module {
                    single { repos.settings }
                    single { repos.filters }
                    single { repos.tokens }
                },
            )
        }
        runBlocking { repos.settings.danmakuEnabled.set(false) }

        val exported = RemoteSettingsBackup.handle(LanHttpRequest("GET", RemoteSettingsBackup.EXPORT_PATH, "", ByteArray(0)))!!
        assertTrue(exported["ok"]!!.jsonPrimitive.boolean)
        val content = exported["content"]!!.jsonPrimitive.content

        runBlocking { repos.settings.danmakuEnabled.set(true) }
        // 记事本存的文件可能带 BOM
        val imported = RemoteSettingsBackup.handle(
            LanHttpRequest("POST", RemoteSettingsBackup.IMPORT_PATH, "", ("\uFEFF" + content + "\n").encodeToByteArray()),
        )!!
        assertTrue(imported["ok"]!!.jsonPrimitive.boolean, imported.toString())
        assertFalse(runBlocking { repos.settings.danmakuEnabled.flow.first() })

        val rejected = RemoteSettingsBackup.handle(
            LanHttpRequest("POST", RemoteSettingsBackup.IMPORT_PATH, "", "{}".encodeToByteArray()),
        )!!
        assertFalse(rejected["ok"]!!.jsonPrimitive.boolean)

        // 只有导入接口放宽请求体上限
        assertEquals(4L * 1024 * 1024, RemoteSettingsBackup.maxBodyBytes(RemoteSettingsBackup.IMPORT_PATH))
        assertNull(RemoteSettingsBackup.maxBodyBytes(RemoteSettingsBackup.EXPORT_PATH))
        assertNull(RemoteSettingsBackup.maxBodyBytes("api/settings/generic/set"))
    }
}
