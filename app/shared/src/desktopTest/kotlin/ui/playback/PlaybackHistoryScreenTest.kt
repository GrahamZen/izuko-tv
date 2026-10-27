/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.playback

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.ui.foundation.LONG_PRESS_MIN_HOLD
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_subject_delete
import me.him188.ani.app.ui.lang.playback_history_cover
import me.him188.ani.app.ui.lang.playback_history_delete_selected
import me.him188.ani.app.ui.lang.playback_history_enter_selection_mode
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.playback_history_selected_count
import me.him188.ani.app.ui.lang.playback_history_sync_delete_pending
import me.him188.ani.app.ui.lang.playback_history_sync_status_pending
import me.him188.ani.app.ui.lang.playback_history_sync_status_synced
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackHistoryScreenTest {
    @Test
    fun `shows playback history fields with subject cover`() = runAniComposeUiTest {
        val item = testHistory()
        val coverText = runBlocking { getString(Lang.playback_history_cover, item.subjectName!!) }
        val episodeLabel = runBlocking { getString(Lang.playback_history_episode_label, "2") }

        setContent {
            ProvideCompositionLocalsForPreview {
                PlaybackHistoryScreen(
                    histories = listOf(item),
                    onNavigateBack = {},
                    onOpenHistory = {},
                    onDelete = {},
                )
            }
        }

        onNodeWithContentDescription(coverText).assertExists()
        onNodeWithText("葬送的芙莉莲", substring = true).assertExists()
        onNodeWithText(episodeLabel, substring = true).assertExists()
        onNodeWithText("别离", substring = true).assertExists()
        onNodeWithText("1:30 / 25:00", substring = true).assertExists()
        onAllNodesWithTag(PlaybackHistoryTestTags.DATE, useUnmergedTree = true)[0].assertExists()
    }

    @Test
    fun `batch delete selected playback histories`() = runAniComposeUiTest {
        val histories = listOf(
            testHistory(episodeId = 1),
            testHistory(episodeId = 2, episodeName = "魔法"),
        )
        val enterSelectionText = runBlocking { getString(Lang.playback_history_enter_selection_mode) }
        val selectedCountText = runBlocking { getString(Lang.playback_history_selected_count, 2) }
        val deleteSelectedText = runBlocking { getString(Lang.playback_history_delete_selected) }
        val deleteText = runBlocking { getString(Lang.cache_subject_delete) }
        var deletedIds = emptyList<Int>()

        setContent {
            ProvideCompositionLocalsForPreview {
                PlaybackHistoryScreen(
                    histories = histories,
                    onNavigateBack = {},
                    onOpenHistory = {},
                    onDelete = { deletedIds = it.sorted() },
                )
            }
        }

        onNodeWithContentDescription(enterSelectionText).performClick()
        onNodeWithTag("${PlaybackHistoryTestTags.ITEM_PREFIX}1").performClick()
        onNodeWithTag("${PlaybackHistoryTestTags.ITEM_PREFIX}2").performClick()
        onNodeWithText(selectedCountText).assertExists()

        onNodeWithContentDescription(deleteSelectedText).performClick()
        onNodeWithText(deleteText).performClick()

        runOnIdle {
            assertEquals(listOf(1, 2), deletedIds)
        }
    }

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun `holding confirm on a history opens its subject and a short press still plays it`() = runAniComposeUiTest {
        var played = emptyList<Int>()
        var opened = emptyList<Int>()
        setContent {
            ProvideCompositionLocalsForPreview {
                PlaybackHistoryScreen(
                    histories = listOf(testHistory()),
                    onNavigateBack = {},
                    onOpenHistory = { played = played + it.episodeId },
                    onDelete = {},
                    onOpenSubject = { opened = opened + it.subjectId!! },
                )
            }
        }
        val item = onNodeWithTag("${PlaybackHistoryTestTags.ITEM_PREFIX}1")
        item.requestFocus()

        item.performKeyPress(KeyEvent(Key.DirectionCenter, KeyEventType.KeyDown))
        item.performKeyPress(KeyEvent(Key.DirectionCenter, KeyEventType.KeyUp))
        runOnIdle {
            assertEquals(listOf(1), played)
            assertEquals(emptyList(), opened)
        }

        // 按住: 第二发 KeyDown 且距按下够久才算长按 (见 tvLongPressKey); 不进多选
        item.performKeyPress(KeyEvent(Key.DirectionCenter, KeyEventType.KeyDown))
        Thread.sleep(LONG_PRESS_MIN_HOLD.inWholeMilliseconds + 100)
        item.performKeyPress(KeyEvent(Key.DirectionCenter, KeyEventType.KeyDown))
        item.performKeyPress(KeyEvent(Key.DirectionCenter, KeyEventType.KeyUp))
        runOnIdle {
            assertEquals(listOf(100), opened)
            assertEquals(listOf(1), played)
        }
        onNodeWithText(runBlocking { getString(Lang.playback_history_selected_count, 1) }).assertDoesNotExist()
    }

    private fun testHistory(
        episodeId: Int = 1,
        episodeName: String = "别离",
    ): PlaybackHistoryUiItem {
        return PlaybackHistoryUiItem(
            episodeId = episodeId,
            subjectId = 100,
            episodeSort = 2f,
            subjectName = "葬送的芙莉莲",
            subjectImageUrl = "https://example.com/cover.jpg",
            episodeName = episodeName,
            positionMillis = 90_000,
            durationMillis = 1_500_000,
            updatedAtMillis = 1_700_000_000_000,
        )
    }
}
