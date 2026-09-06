/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.download.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.ui.download.DownloadManagementTestTags
import me.him188.ani.app.tools.toProgress
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.lang.cache_episode_pause_download
import me.him188.ani.app.ui.lang.cache_episode_status_awaiting_bt_service
import me.him188.ani.app.ui.lang.cache_episode_status_merging
import me.him188.ani.app.ui.lang.cache_management_more_actions
import me.him188.ani.app.ui.lang.cache_subject_delete
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_episode_watched_progress
import me.him188.ani.app.ui.lang.cache_filter_status_finished
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.compose.resources.getString

@OptIn(TestOnly::class)
class DownloadRowUiTest {
    @Test
    fun `busy state disables row actions and an open delete confirmation`() = runAniComposeUiTest {
        var episode by mutableStateOf(createTestDownloadItem(1, initialState = DownloadStatus.IN_PROGRESS).copy(isBusy = true))
        var deletions = 0
        val pause = runBlocking { getString(Lang.cache_episode_pause_download) }
        val delete = runBlocking { getString(Lang.cache_subject_delete) }
        setContent {
            ProvideCompositionLocalsForPreview {
                DownloadRow(
                    episode = episode,
                    mediaSourceInfoProvider = null,
                    selectionMode = false,
                    selected = false,
                    onToggleSelected = {},
                    onEnterSelection = {},
                    onPlay = {},
                    onResume = {},
                    onPause = {},
                    onDelete = { deletions++ },
                    onViewDetail = null,
                )
            }
        }
        onNodeWithContentDescription(pause).assertIsNotEnabled()
        onNodeWithContentDescription(runBlocking { getString(Lang.cache_management_more_actions) }).performClick()
        onNodeWithText(pause).assertIsNotEnabled()
        onNodeWithText(delete).assertIsNotEnabled()
        runOnIdle { episode = episode.copy(isBusy = false) }
        onNodeWithText(delete).assertIsEnabled().performClick()
        runOnIdle { episode = episode.copy(isBusy = true) }
        onNodeWithTag(DownloadManagementTestTags.DELETE_CONFIRM_BUTTON).assertIsNotEnabled()
        runOnIdle { episode = episode.copy(isBusy = false) }
        onNodeWithTag(DownloadManagementTestTags.DELETE_CONFIRM_BUTTON).assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, deletions) }
    }

    @Test
    fun `completed cache shows watched progress beside finished without progress semantics`() = runAniComposeUiTest {
        val episode = createTestDownloadItem(
            sort = 1,
            initialState = DownloadStatus.COMPLETED,
            progress = 1f.toProgress(),
            downloadSpeed = FileSize.Unspecified,
            playbackProgress = 0.5f.toProgress(),
        )
        val metadataText = runBlocking {
            "${episode.detailedSizeText} · ${getString(Lang.cache_filter_status_finished)} · " +
                    getString(Lang.cache_episode_watched_progress, "50.0%")
        }

        setContent {
            ProvideCompositionLocalsForPreview {
                DownloadRow(
                    episode = episode,
                    mediaSourceInfoProvider = null,
                    selectionMode = false,
                    selected = false,
                    onToggleSelected = {},
                    onEnterSelection = {},
                    onPlay = {},
                    onResume = {},
                    onPause = {},
                    onDelete = {},
                    onViewDetail = null,
                )
            }
        }

        onNodeWithText(metadataText, useUnmergedTree = true).assertExists()
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    @Test
    fun `status slot shows merge progress while merging and the torrent service state while waiting`() = runAniComposeUiTest {
        var episode by mutableStateOf(
            createTestDownloadItem(1, initialState = DownloadStatus.IN_PROGRESS, progress = 1f.toProgress())
                .copy(isMerging = true, mergeProgress = 0.634f.toProgress()),
        )
        val merging = runBlocking { getString(Lang.cache_episode_status_merging) }
        val awaiting = runBlocking { getString(Lang.cache_episode_status_awaiting_bt_service) }
        setContent {
            ProvideCompositionLocalsForPreview {
                DownloadRow(
                    episode = episode,
                    mediaSourceInfoProvider = null,
                    selectionMode = false,
                    selected = false,
                    onToggleSelected = {},
                    onEnterSelection = {},
                    onPlay = {},
                    onResume = {},
                    onPause = {},
                    onDelete = {},
                    onViewDetail = null,
                )
            }
        }

        onNodeWithText(merging, useUnmergedTree = true).assertExists()
        onNodeWithText("63%", useUnmergedTree = true).assertExists()
        // 合并期间下载进度恒为 100%, 不能再显示它
        onNodeWithText("100.0%", useUnmergedTree = true).assertDoesNotExist()

        runOnIdle {
            episode = createTestDownloadItem(2, initialState = DownloadStatus.IN_PROGRESS)
                .copy(awaitingTorrentService = true)
        }
        onNodeWithText(awaiting, useUnmergedTree = true).assertExists()
        onNodeWithText(merging, useUnmergedTree = true).assertDoesNotExist()
    }
}
