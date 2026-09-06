/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.tools.update.FileDownloadStage
import me.him188.ani.app.tools.update.FileDownloaderState
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_update_download_probing
import me.him188.ani.app.ui.lang.settings_update_download_switching
import me.him188.ani.app.ui.lang.settings_update_download_verifying
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.compose.resources.getString
import kotlin.test.Test

/**
 * 电视右下角的更新下载卡片: 进度条下面写在等什么 (挑线路 / 已下多少与速度 / 换线路 / 校验), 只在真在收数据时给百分比.
 */
@OptIn(TestOnly::class)
class DownloadingUpdatePopupCardTest {
    private val mib = 1024L * 1024

    private fun stats(stage: FileDownloadStage?, progress: Float = 0f) =
        FileDownloaderStats(progress, FileDownloaderState.Downloading, stage = stage)

    @Test
    fun `probing says how many lines answered and shows no percentage`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                DownloadingUpdatePopupCard(TestNewVersion, stats(FileDownloadStage.Probing(3, 5)), null, false, {}, {}, {})
            }
        }
        waitForIdle()
        onNodeWithText(runBlocking { getString(Lang.settings_update_download_probing, 3, 5) }).assertIsDisplayed()
        onNodeWithText("0%").assertDoesNotExist()
    }

    @Test
    fun `transferring shows bytes, speed and percentage`() = runAniComposeUiTest {
        val stage = FileDownloadStage.Transferring(1, 3, 38 * mib, 79 * mib, 2 * mib + 160 * 1024)
        setContent {
            ProvideCompositionLocalsForPreview {
                DownloadingUpdatePopupCard(TestNewVersion, stats(stage, progress = 0.48f), null, false, {}, {}, {})
            }
        }
        waitForIdle()
        onNodeWithText("38/79 MB · 2.1 MB/s").assertIsDisplayed()
        onNodeWithText("48%").assertIsDisplayed()
    }

    @Test
    fun `switching and verifying explain the wait`() = runAniComposeUiTest {
        var stage by mutableStateOf<FileDownloadStage>(FileDownloadStage.Switching(2, 3))
        setContent {
            ProvideCompositionLocalsForPreview {
                DownloadingUpdatePopupCard(TestNewVersion, stats(stage, progress = 1f), null, false, {}, {}, {})
            }
        }
        waitForIdle()
        onNodeWithText(runBlocking { getString(Lang.settings_update_download_switching, 2) }).assertIsDisplayed()
        // 换线路时进度从零重来, 不再挂着上一条线路的百分比
        onNodeWithText("100%").assertDoesNotExist()

        stage = FileDownloadStage.Verifying
        waitForIdle()
        onNodeWithText(runBlocking { getString(Lang.settings_update_download_verifying) }).assertIsDisplayed()
    }
}
