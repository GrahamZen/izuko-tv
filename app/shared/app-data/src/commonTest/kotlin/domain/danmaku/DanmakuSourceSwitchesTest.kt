/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.DanmakuSettings
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.episode.DanmakuOriginConfig
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.Imported
import kotlin.test.Test
import kotlin.test.assertEquals

class DanmakuSourceSwitchesTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    /** 一个播放页: 开关表与改开关的入口, 照 EpisodeDanmakuLoader 的样子 */
    private class Player(settings: Settings<DanmakuSettings>, scope: CoroutineScope) {
        val config = MutableStateFlow(emptyMap<DanmakuServiceId, DanmakuOriginConfig>())

        fun setEnabled(id: DanmakuServiceId, enabled: Boolean) {
            config.update { it + (id to (it[id] ?: DanmakuOriginConfig.Default).copy(enabled = enabled)) }
        }

        fun enabled(id: DanmakuServiceId): Boolean = config.value[id]?.enabled ?: true

        init {
            syncDanmakuSourceSwitches(config, ::setEnabled, settings, scope)
        }
    }

    private val server = DanmakuServiceId("测试服务器")

    @Test
    fun `remembered switches apply when the player opens`() = runTest {
        val settings = MemorySettings(DanmakuSettings(disabledServices = setOf("测试服务器")))
        val player = Player(settings, backgroundScope)
        runCurrent()

        assertEquals(false, player.enabled(server))
        assertEquals(true, player.enabled(DanmakuServiceId.Dandanplay))
        assertEquals(setOf("测试服务器"), settings.state.value.disabledServices)
    }

    @Test
    fun `switching a source in the player is remembered`() = runTest {
        val settings = MemorySettings(DanmakuSettings())
        val player = Player(settings, backgroundScope)
        runCurrent()

        player.setEnabled(server, false)
        runCurrent()
        assertEquals(setOf("测试服务器"), settings.state.value.disabledServices)

        player.setEnabled(DanmakuServiceId.Bilibili, false)
        player.setEnabled(server, true)
        runCurrent()
        assertEquals(setOf(DanmakuServiceId.Bilibili.value), settings.state.value.disabledServices)
    }

    @Test
    fun `next player starts with the switches of the last one`() = runTest {
        val settings = MemorySettings(DanmakuSettings())
        val first = Player(settings, backgroundScope)
        runCurrent()
        first.setEnabled(server, false)
        runCurrent()

        val next = Player(settings, backgroundScope)
        runCurrent()

        assertEquals(false, next.enabled(server))
    }

    @Test
    fun `switch made in another player applies here`() = runTest {
        val settings = MemorySettings(DanmakuSettings(disabledServices = setOf("测试服务器")))
        val background = Player(settings, backgroundScope)
        val front = Player(settings, backgroundScope)
        runCurrent()

        front.setEnabled(server, true)
        runCurrent()

        assertEquals(true, background.enabled(server))
        assertEquals(emptySet(), settings.state.value.disabledServices)
    }

    @Test
    fun `imported danmaku is not remembered`() = runTest {
        val settings = MemorySettings(DanmakuSettings(disabledServices = setOf(DanmakuServiceId.Imported.value)))
        val player = Player(settings, backgroundScope)
        runCurrent()
        assertEquals(true, player.enabled(DanmakuServiceId.Imported))

        player.setEnabled(DanmakuServiceId.Imported, false)
        runCurrent()
        assertEquals(setOf(DanmakuServiceId.Imported.value), settings.state.value.disabledServices)
        assertEquals(false, player.enabled(DanmakuServiceId.Imported))
    }

    @Test
    fun `time shift is not a switch`() = runTest {
        val settings = MemorySettings(DanmakuSettings())
        val player = Player(settings, backgroundScope)
        runCurrent()

        player.config.update { it + (server to DanmakuOriginConfig.Default.copy(shiftMillis = 1500)) }
        runCurrent()

        assertEquals(emptySet(), settings.state.value.disabledServices)
    }
}
