/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.platform.AppRestarter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 换人与改成本地用户的重启: 重启前放过场 ([ProfileSwitchTransition]), 与垫数据同时做; 过场出错或超时照样重启. */
class UserProfileManagerTest {
    private class CountingRestarter : AppRestarter {
        override val isSupported: Boolean get() = true
        var restarts = 0
        override fun restart() {
            restarts++
        }
    }

    private val registry = UserProfileRegistry.inMemory(
        UserProfilesSave(
            listOf(UserProfile(UserProfile.PRIMARY_ID), UserProfile(2, name = "Mika", kind = UserProfileKind.LOCAL)),
            UserProfile.PRIMARY_ID,
            3,
        ),
    )
    private val restarter = CountingRestarter()

    @Test
    fun `换人前放过场与垫数据 两件事同时做 都做完才重启`() = runTest {
        val seeding = CompletableDeferred<Unit>()
        val transitionDone = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val manager = UserProfileManager(
            registry,
            restarter,
            deleteFiles = {},
            beforeSwitch = { events += "seed ${it.id}"; seeding.await() },
        )
        manager.transition = ProfileSwitchTransition { events += "transition ${it.name}"; transitionDone.await() }

        val job = launch { manager.switchTo(2) }
        runCurrent()
        assertEquals(listOf("seed 2", "transition Mika"), events)
        assertEquals(0, restarter.restarts)

        transitionDone.complete(Unit)
        runCurrent()
        assertEquals(0, restarter.restarts)
        assertEquals(UserProfile.PRIMARY_ID, registry.state.value.currentId)

        seeding.complete(Unit)
        job.join()
        assertEquals(1, restarter.restarts)
        assertEquals(2, registry.state.value.currentId)
    }

    @Test
    fun `过场一直不结束或出错 照样重启`() = runTest {
        val manager = UserProfileManager(registry, restarter, deleteFiles = {})
        manager.transition = ProfileSwitchTransition { awaitCancellation() }
        manager.switchTo(2)
        assertEquals(1, restarter.restarts)

        registry.setCurrent(UserProfile.PRIMARY_ID)
        manager.transition = ProfileSwitchTransition { error("no window") }
        manager.switchTo(2)
        assertEquals(2, restarter.restarts)
    }

    @Test
    fun `没装过场 直接重启`() = runTest {
        val manager = UserProfileManager(registry, restarter, deleteFiles = {})
        manager.switchTo(2)
        advanceUntilIdle()
        assertEquals(1, restarter.restarts)
    }

    @Test
    fun `选的还是自己 不放过场也不重启`() = runTest {
        val manager = UserProfileManager(registry, restarter, deleteFiles = {})
        var played = false
        manager.transition = ProfileSwitchTransition { played = true }
        manager.switchTo(UserProfile.PRIMARY_ID)
        assertEquals(0, restarter.restarts)
        assertFalse(played)
    }

    @Test
    fun `改成本地用户 过场里是改好的自己`() = runTest {
        val manager = UserProfileManager(registry, restarter, deleteFiles = {})
        var played: UserProfile? = null
        manager.transition = ProfileSwitchTransition { played = it }
        manager.convertCurrentToLocal(defaultName = "用户 1")
        assertEquals(UserProfileKind.LOCAL, played?.kind)
        assertEquals("用户 1", played?.name)
        assertEquals(1, restarter.restarts)
    }
}
