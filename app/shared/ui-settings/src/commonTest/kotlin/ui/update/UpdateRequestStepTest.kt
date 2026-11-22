/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import kotlinx.io.files.Path
import me.him188.ani.utils.io.inSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateRequestStepTest {
    private val version = NewVersion("1.0.9", emptyList(), emptyList(), "")

    @Test
    fun `an available or failed version starts downloading`() {
        assertEquals(UpdateRequestStep.Download, updateRequestStep(AppUpdateState.HasUpdate(version)))
        assertEquals(UpdateRequestStep.Download, updateRequestStep(AppUpdateState.DownloadFailed(version, IllegalStateException())))
    }

    @Test
    fun `a running download or install is only shown and never restarted`() {
        assertEquals(UpdateRequestStep.ShowProgress, updateRequestStep(AppUpdateState.Downloading(version, FileDownloaderStats.Placeholder)))
        assertEquals(UpdateRequestStep.ShowProgress, updateRequestStep(AppUpdateState.Installing(version)))
    }

    @Test
    fun `a downloaded version is installed without downloading again`() {
        assertEquals(UpdateRequestStep.Install, updateRequestStep(AppUpdateState.Downloaded(version, Path("izuko-1.0.9.apk").inSystem)))
    }
}
