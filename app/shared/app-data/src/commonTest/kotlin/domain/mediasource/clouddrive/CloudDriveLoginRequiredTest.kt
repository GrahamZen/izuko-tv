/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudDriveLoginRequiredTest {
    @Test
    fun `search failed for want of a drive login names the drive`() {
        val state = MediaSourceFetchState.Failed(CloudDriveAuthException(driveId = "quark"), id = 0)
        assertEquals("quark", state.cloudDriveLoginRequired?.driveId)
    }

    @Test
    fun `other failures and successes are not login required`() {
        assertNull(MediaSourceFetchState.Failed(IllegalStateException("boom"), id = 0).cloudDriveLoginRequired)
        assertNull(MediaSourceFetchState.Succeed(id = 0).cloudDriveLoginRequired)
    }

    @Test
    fun `playback failure wrapping the drive login is recognized`() {
        val wrapped = RuntimeException("resolve failed", CloudDriveAuthException(driveId = "quark"))
        assertTrue(wrapped.isCloudDriveLoginRequired())
        assertFalse(RuntimeException("resolve failed", IllegalStateException()).isCloudDriveLoginRequired())
    }
}
