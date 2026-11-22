/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import kotlinx.io.files.Path
import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.ui.update.AppUpdatePresentation
import me.him188.ani.app.ui.update.AppUpdateState
import me.him188.ani.app.ui.update.FileDownloaderStats
import me.him188.ani.app.ui.update.NewVersion
import me.him188.ani.utils.io.inSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvUpdateRowTest {
    private val version = NewVersion("1.0.8", emptyList(), emptyList(), "")
    private val hasUpdate = AppUpdateState.HasUpdate(version)
    private val downloaded = AppUpdateState.Downloaded(version, Path("izuko-1.0.8.apk").inSystem)
    private val failed = AppUpdateState.DownloadFailed(version, IllegalStateException())
    private val downloading = AppUpdateState.Downloading(version, FileDownloaderStats.Placeholder)

    private fun presentation(state: AppUpdateState, checking: Boolean = false, error: LoadError? = null) = AppUpdatePresentation(
        newVersion = version,
        state = state,
        fileDownloaderStats = FileDownloaderStats.Placeholder,
        isCheckingUpdate = checking,
        checkUpdateError = error,
    )

    @Test
    fun `the first press always checks whatever happened before`() {
        for (state in listOf(hasUpdate, downloaded, failed, AppUpdateState.AlreadyUpToDate, AppUpdateState.ClickToCheck)) {
            assertEquals(TvUpdateRowStep.Check, tvUpdateRowStep(presentation(state), checked = false), state.toString())
        }
    }

    @Test
    fun `after a version was found the next press updates`() {
        for (state in listOf(hasUpdate, failed, downloaded)) {
            assertEquals(TvUpdateRowStep.Update, tvUpdateRowStep(presentation(state), checked = true), state.toString())
        }
    }

    @Test
    fun `without a new version every press checks again`() {
        assertEquals(TvUpdateRowStep.Check, tvUpdateRowStep(presentation(AppUpdateState.AlreadyUpToDate), checked = true))
    }

    @Test
    fun `a failed check is checked again and never installs the older result`() {
        assertEquals(TvUpdateRowStep.Check, tvUpdateRowStep(presentation(hasUpdate, error = LoadError.NetworkError), checked = true))
        assertEquals(TvUpdateRowStep.Check, tvUpdateRowStep(presentation(downloaded, error = LoadError.NetworkError), checked = true))
    }

    @Test
    fun `a running download or install is not checked over even before checking`() {
        for (state in listOf(downloading, AppUpdateState.Installing(version))) {
            assertEquals(TvUpdateRowStep.Update, tvUpdateRowStep(presentation(state), checked = false))
            assertEquals(TvUpdateRowStep.Update, tvUpdateRowStep(presentation(state), checked = true))
        }
    }

    @Test
    fun `only the latest checked update type counts`() {
        assertFalse(TvUpdateChecked(null).isFor(ReleaseClass.STABLE))
        assertFalse(TvUpdateChecked(null).isFor(null))
        assertTrue(TvUpdateChecked(ReleaseClass.STABLE).isFor(ReleaseClass.STABLE))
        // 正式版查过之后换成测试版: 又从头先查
        assertFalse(TvUpdateChecked(ReleaseClass.STABLE).isFor(ReleaseClass.ALPHA))
    }

    @Test
    fun `nothing happens while a check is running`() {
        assertEquals(TvUpdateRowStep.None, tvUpdateRowStep(presentation(hasUpdate, checking = true), checked = false))
        assertEquals(TvUpdateRowStep.None, tvUpdateRowStep(presentation(hasUpdate, checking = true), checked = true))
    }
}
