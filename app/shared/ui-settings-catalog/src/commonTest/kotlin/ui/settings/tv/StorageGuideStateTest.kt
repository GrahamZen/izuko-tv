/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import me.him188.ani.app.domain.media.cache.library.MediaStorageSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「缓存放在哪」引导的步骤 ([StorageGuideState]): 选片库又没权限时先去授权, 给下来接着走; 没有要搬的跳过「已有的缓存」, 返回时也跳过;
 * 第一步按返回关掉引导; 系统不支持片库时只能放应用目录.
 */
class StorageGuideStateTest {
    private fun state(location: String? = null, library: Boolean = false, supported: Boolean = true) =
        StorageGuideState(MediaStorageSetup.Choice(location, library), supported)

    @Test
    fun `steps start from the current settings`() {
        val state = state(location = "usb", library = true)
        assertEquals(StorageGuideStep.Drive, state.step)
        assertEquals(MediaStorageSetup.Choice("usb", library = true), state.choice)
    }

    @Test
    fun `the library without permission goes through the permission step`() {
        val state = state()
        state.chooseDrive("usb")
        state.chooseMode(library = true, granted = false)
        assertEquals(StorageGuideStep.Permission, state.step)
        assertTrue(StorageGuideStep.Permission in state.visibleSteps)

        state.permissionGranted()
        assertEquals(StorageGuideStep.Existing, state.step)
        assertEquals(MediaStorageSetup.Choice("usb", library = true), state.choice)
    }

    @Test
    fun `with permission already given the permission step is skipped`() {
        val state = state()
        state.chooseDrive(null)
        state.chooseMode(library = true, granted = true)
        assertEquals(StorageGuideStep.Existing, state.step)
        assertFalse(StorageGuideStep.Permission in state.visibleSteps)
    }

    @Test
    fun `skipping the permission puts caches in the app folder`() {
        val state = state()
        state.chooseDrive(null)
        state.chooseMode(library = true, granted = false)
        state.skipPermission()
        assertEquals(StorageGuideStep.Existing, state.step)
        assertFalse(state.library)
    }

    @Test
    fun `nothing to move goes straight to confirm and back skips the existing step`() {
        val state = state()
        state.chooseDrive("usb")
        state.chooseMode(library = false, granted = true)
        state.nothingToMove()
        assertEquals(StorageGuideStep.Confirm, state.step)

        assertTrue(state.back())
        assertEquals(StorageGuideStep.Mode, state.step)
        assertTrue(state.back())
        assertEquals(StorageGuideStep.Drive, state.step)
        assertFalse(state.back(), "back on the first step closes the guide")
    }

    @Test
    fun `existing caches choice is kept for the confirm step`() {
        val state = state(library = true)
        state.chooseDrive(null)
        state.chooseMode(library = false, granted = true)
        assertTrue(state.turningLibraryOff)
        state.chooseExisting(move = true, keepLibraryInFolder = true)
        assertEquals(StorageGuideStep.Confirm, state.step)
        assertTrue(state.moveExisting)
        assertTrue(state.keepLibraryInFolder)
    }

    @Test
    fun `without library support only the app folder is chosen`() {
        val state = state(library = true, supported = false)
        assertFalse(state.library)
        state.chooseDrive(null)
        state.chooseMode(library = true, granted = true)
        assertFalse(state.library)
        assertEquals(StorageGuideStep.Existing, state.step)
    }
}
