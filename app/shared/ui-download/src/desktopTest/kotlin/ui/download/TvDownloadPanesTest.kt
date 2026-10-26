/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.download

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.tools.toProgress
import me.him188.ani.app.ui.download.components.DownloadItem
import me.him188.ani.app.ui.download.components.DownloadStatus
import me.him188.ani.app.ui.download.components.SubjectDownloadGroup
import me.him188.ani.app.ui.download.components.createTestDownloadItem
import me.him188.ani.app.ui.download.subject.EpisodeDownloadItem
import me.him188.ani.app.ui.download.subject.SubjectDownloadActions
import me.him188.ani.app.ui.download.subject.SubjectDownloadsContent
import me.him188.ani.app.ui.download.subject.SubjectDownloadsUiState
import me.him188.ani.app.ui.download.subject.buildSubjectDownloadItems
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.foundation.LONG_PRESS_MIN_HOLD
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_episode_pause_download
import me.him188.ani.app.ui.lang.cache_episode_resume_download
import me.him188.ani.app.ui.lang.cache_management_play
import me.him188.ani.app.ui.lang.cache_subject_delete
import me.him188.ani.app.ui.mediafetch.MediaSourceInfoProvider
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * 缓存总页电视版的两栏焦点 ([TvDownloadPanes]): 左边一块可聚焦的方块代替侧边栏, 内容区是一个焦点组, 同主壳.
 */
@OptIn(TestOnly::class, InternalComposeUiApi::class)
class TvDownloadPanesTest {
    private val finishedA = createTestDownloadItem(3, subjectName = "Group A", subjectId = 1, episodeId = 103, progress = 1f.toProgress())
    private val pausedA = createTestDownloadItem(4, subjectName = "Group A", subjectId = 1, episodeId = 104, initialState = DownloadStatus.PAUSED)
    private val downloadingB = createTestDownloadItem(1, subjectName = "Group B", subjectId = 2, episodeId = 201, initialState = DownloadStatus.IN_PROGRESS)

    private val groupA = SubjectDownloadGroup(1, "Group A", listOf(finishedA, pausedA), UnifiedCollectionType.DOING)
    private val groupB = SubjectDownloadGroup(2, "Group B", listOf(downloadingB), UnifiedCollectionType.DOING)

    /** 只缓存了靠后的一集: 右栏的第一条下载行在屏幕外. */
    private val finishedC = createTestDownloadItem(28, subjectName = "Group C", subjectId = 3, episodeId = 328, progress = 1f.toProgress())
    private val groupC = SubjectDownloadGroup(3, "Group C", listOf(finishedC), UnifiedCollectionType.DOING)

    private val episodes = mapOf(
        1 to (1..4).map { episode(100 + it, it) },
        2 to (1..2).map { episode(200 + it, it) },
        3 to (1..30).map { episode(300 + it, it) },
    )

    private fun episode(id: Int, sort: Int) =
        EpisodeDownloadItem(id, EpisodeSort(sort), "Episode $sort", UnifiedCollectionType.WISH, hasPublished = true)

    private val content = FocusRequester()
    private lateinit var focusManager: FocusManager
    private var shownSubject by mutableStateOf<Int?>(null)
    private val played = mutableListOf<DownloadItem>()
    private val resumed = mutableListOf<String>()

    private var pageState by mutableStateOf(DownloadManagementUiState.Placeholder)

    private fun AniComposeUiTest.setPage(groups: List<SubjectDownloadGroup> = listOf(groupA, groupB)) {
        pageState = DownloadManagementUiState(MediaStats.Unspecified, groups)
        setPageContent()
        // 侧边栏按右 = 对内容区焦点组 requestFocus (见 TvMainScreenLayout)
        runOnIdle { content.requestFocus() }
        waitForIdle()
    }

    private fun AniComposeUiTest.setPageContent() {
        setContent {
            focusManager = LocalFocusManager.current
            ProvideCompositionLocalsForPreview {
                CompositionLocalProvider(LocalAniUiBehavior provides AniUiBehavior(focusDrivenNavigation = true)) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.size(48.dp).focusable())
                        Box(Modifier.weight(1f).focusRequester(content).focusGroup()) {
                            DownloadManagementScreen(
                                state = pageState,
                                selfInfo = null,
                                onPlay = {}, onResume = {}, onPause = {}, onViewDetail = {}, onDelete = {}, onClickLogin = {},
                                detailPaneContent = { group, selection ->
                                    SideEffect { shownSubject = group?.subjectId }
                                    if (group != null) {
                                        SubjectDownloadsContent(
                                            state = SubjectDownloadsUiState(
                                                items = buildSubjectDownloadItems(episodes.getValue(group.subjectId), group.entries),
                                                downloads = group.entries,
                                                episodesLoading = false,
                                                downloadsLoading = false,
                                            ),
                                            selection = selection,
                                            actions = SubjectDownloadActions(
                                                download = {}, cancelRequest = {}, pause = {}, resume = { resumed += it },
                                                delete = {}, pauseAll = {}, resumeAll = {}, reload = {},
                                            ),
                                            sourceInfoProvider = MediaSourceInfoProvider(getSourceInfoFlow = { flowOf(null) }),
                                            onPlay = { played += it },
                                            onViewDetail = null,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 同 Android: 方向键没人接就按方向移动焦点 (桌面上方向键本身不移动焦点).
     */
    private fun AniComposeUiTest.press(key: Key) {
        val consumed = onRoot().performKeyPress(KeyEvent(key, KeyEventType.KeyDown))
        val direction = when (key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            else -> null
        }
        if (!consumed && direction != null) runOnIdle { focusManager.moveFocus(direction) }
        onRoot().performKeyPress(KeyEvent(key, KeyEventType.KeyUp))
    }

    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    @Test
    fun `entering the page focuses the viewed group card`() = runAniComposeUiTest {
        setPage()
        onNodeWithText("Group A").assertIsFocused()
    }

    @Test
    fun `entering before the list loads waits for the first card`() = runAniComposeUiTest {
        setPageContent()
        mainClock.autoAdvance = false
        runOnIdle { content.requestFocus() }
        mainClock.advanceTimeBy(100)
        // 没有先落到右上角或筛选栏的按钮上
        onAllNodes(isFocused()).assertCountEquals(0)
        runOnIdle { pageState = DownloadManagementUiState(MediaStats.Unspecified, listOf(groupA, groupB)) }
        mainClock.advanceTimeBy(100)
        onNodeWithText("Group A").assertIsFocused()
    }

    @Test
    fun `the detail pane follows list focus only after it rests`() = runAniComposeUiTest {
        setPage()
        runOnIdle { assertEquals(1, shownSubject) }
        mainClock.autoAdvance = false
        press(Key.DirectionDown)
        onNodeWithText("Group B").assertIsFocused()
        mainClock.advanceTimeBy(TV_DOWNLOAD_FOLLOW_DELAY.inWholeMilliseconds / 2)
        assertEquals(1, shownSubject)
        mainClock.advanceTimeBy(TV_DOWNLOAD_FOLLOW_DELAY.inWholeMilliseconds)
        assertEquals(2, shownSubject)
    }

    @Test
    fun `right on a card focuses its first cached episode`() = runAniComposeUiTest {
        setPage()
        press(Key.DirectionRight)
        waitForIdle()
        onNodeWithContentDescription(text(Lang.cache_management_play)).assertIsFocused()
    }

    @Test
    fun `right scrolls the detail to a cached episode further down`() = runAniComposeUiTest {
        setPage(listOf(groupC))
        onNodeWithContentDescription(text(Lang.cache_management_play)).assertDoesNotExist()
        press(Key.DirectionRight)
        waitForIdle()
        onNodeWithContentDescription(text(Lang.cache_management_play)).assertIsFocused()
    }

    @Test
    fun `right before the list rests switches the detail then focuses its episode`() = runAniComposeUiTest {
        setPage()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        waitForIdle()
        assertEquals(2, shownSubject)
        onNodeWithContentDescription(text(Lang.cache_episode_pause_download)).assertIsFocused()
    }

    @Test
    fun `left from the detail returns to the viewed group card`() = runAniComposeUiTest {
        setPage()
        press(Key.DirectionRight)
        waitForIdle()
        press(Key.DirectionDown)
        onNodeWithContentDescription(text(Lang.cache_episode_resume_download)).assertIsFocused()
        press(Key.DirectionLeft)
        waitForIdle()
        onNodeWithText("Group A").assertIsFocused()
        assertEquals(1, shownSubject)
    }

    @Test
    fun `right returns to the episode last focused in that subject`() = runAniComposeUiTest {
        setPage()
        press(Key.DirectionRight)
        waitForIdle()
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        waitForIdle()
        press(Key.DirectionRight)
        waitForIdle()
        onNodeWithContentDescription(text(Lang.cache_episode_resume_download)).assertIsFocused()
    }

    @Test
    fun `confirm runs the primary action and holding it opens the menu`() = runAniComposeUiTest {
        setPage()
        press(Key.DirectionRight)
        waitForIdle()
        onNodeWithContentDescription(text(Lang.cache_management_play)).assertIsFocused()
        press(Key.DirectionCenter)
        runOnIdle { assertEquals(listOf(finishedA.id), played.map { it.id }) }

        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        runOnIdle { assertEquals(listOf(pausedA.id), resumed) }

        // 按住: 第二发 KeyDown 且距按下够久才算长按 (见 tvLongPressKey)
        onRoot().performKeyPress(KeyEvent(Key.DirectionCenter, KeyEventType.KeyDown))
        Thread.sleep(LONG_PRESS_MIN_HOLD.inWholeMilliseconds + 100)
        onRoot().performKeyPress(KeyEvent(Key.DirectionCenter, KeyEventType.KeyDown))
        // 菜单已经弹出, 抬起落在菜单的窗口上
        onAllNodes(isRoot()).onLast().performKeyPress(KeyEvent(Key.DirectionCenter, KeyEventType.KeyUp))
        waitForIdle()
        onNodeWithText(text(Lang.cache_subject_delete)).assertExists()
        // 按钮已经是「继续」, 菜单里只剩按钮上没有的
        onNodeWithText(text(Lang.cache_episode_resume_download)).assertDoesNotExist()
        onNodeWithText(text(Lang.cache_management_play)).assertExists()
        // 长按那一次的余波不会点中菜单项, 也不会再触发一次主操作
        runOnIdle { assertEquals(listOf(pausedA.id), resumed) }
    }
}
