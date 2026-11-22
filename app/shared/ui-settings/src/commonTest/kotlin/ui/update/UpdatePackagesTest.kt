/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import me.him188.ani.app.tools.update.FileDownloaderState
import me.him188.ani.utils.io.inSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePackagesTest {
    private fun urls(version: String, extension: String = "apk") = listOf(
        "https://github.com/x/y/releases/download/v$version/izuko-$version-arm64-v8a.$extension",
        "https://github.com/x/y/releases/download/v$version/izuko-$version-universal.$extension",
    )

    @Test
    fun `a package of the same version is recognised`() {
        val version = urls("1.0.9-alpha05")
        assertTrue(isPackageOfVersion("izuko-1.0.9-alpha05-arm64-v8a.apk", version, version))
        assertTrue(isPackageOfVersion("izuko-1.0.9-alpha05-universal.apk", version, version))
    }

    @Test
    fun `a stable package left over is not a package of the test version`() {
        val version = urls("1.0.9-alpha05")
        assertFalse(isPackageOfVersion("izuko-1.0.8-arm64-v8a.apk", version, version))
    }

    @Test
    fun `an older test package is not a package of the newer test version`() {
        val version = urls("1.0.9-alpha05")
        assertFalse(isPackageOfVersion("izuko-1.0.9-alpha04-arm64-v8a.apk", version, version))
    }

    @Test
    fun `both the published and the prepared file names count`() {
        val published = urls("1.0.9", "AppImage")
        val prepared = published.map { "$it.zsync" }
        assertTrue(isPackageOfVersion("izuko-1.0.9-universal.AppImage", published, prepared))
        assertTrue(isPackageOfVersion("izuko-1.0.9-universal.AppImage.zsync", published, prepared))
    }

    private val version = NewVersion("1.0.9-alpha05", emptyList(), urls("1.0.9-alpha05"), "")

    private fun settled(downloader: FileDownloaderState.Completed) = settledUpdateState(version, downloader, version.downloadUrlAlternatives)

    private fun succeed(fileName: String) = FileDownloaderState.Succeed("https://x", Path(fileName).inSystem, checked = true)

    @Test
    fun `a cancelled download is an available version again`() {
        assertEquals(AppUpdateState.HasUpdate(version), settled(FileDownloaderState.Cancelled(CancellationException())))
    }

    @Test
    fun `a failed download stays failed`() {
        val error = IllegalStateException()
        assertEquals(AppUpdateState.DownloadFailed(version, error), settled(FileDownloaderState.Failed(error)))
    }

    @Test
    fun `a finished download counts only for its own version`() {
        val own = succeed("izuko-1.0.9-alpha05-arm64-v8a.apk")
        assertEquals(AppUpdateState.Downloaded(version, own.file), settled(own))
        assertEquals(AppUpdateState.HasUpdate(version), settled(succeed("izuko-1.0.8-arm64-v8a.apk")))
    }

    @Test
    fun `nothing matches when the version has no packages`() {
        assertFalse(isPackageOfVersion("izuko-1.0.8-arm64-v8a.apk", emptyList(), emptyList()))
    }
}
