/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.models.subject.TestSubjectInfo
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_mediasource_retry
import me.him188.ani.app.ui.lang.subject_details_tv_error_back_hint
import me.him188.ani.app.ui.user.TestSelfInfoUiState
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 电视界面 (immersiveShell) 的条目加载失败页: 遥控器上只有一句「按返回键」; 触屏 (平板 / iPad) 没有返回键, 要有能点的返回与重试.
 */
@OptIn(TestOnly::class, ExperimentalTestApi::class)
class SubjectDetailsErrorPageTest {
    private val retryText = runBlocking { getString(Lang.settings_mediasource_retry) }
    private val backHintText = runBlocking { getString(Lang.subject_details_tv_error_back_hint) }

    private fun ComposeUiTest.showError(behavior: AniUiBehavior, onRetry: () -> Unit = {}, onBack: () -> Unit = {}) {
        setContent {
            ProvideCompositionLocalsForPreview {
                CompositionLocalProvider(LocalAniUiBehavior provides behavior) {
                    SubjectDetailsScreen(
                        SubjectDetailsLoadState.Err(TestSubjectInfo.subjectId, TestSubjectInfo, LoadError.NetworkError),
                        TestSelfInfoUiState,
                        onPlay = {},
                        onLoadErrorRetry = onRetry,
                        onClickTag = {},
                        onEpisodeCollectionUpdate = {},
                        navigationIcon = {
                            IconButton(onBack, Modifier.testTag(BACK_TAG)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                        },
                    )
                }
            }
        }
    }

    @Test
    fun `touch tv shell error page can go back and retry`() = runAniComposeUiTest {
        var retries = 0
        var backs = 0
        showError(AniUiBehavior(immersiveShell = true, showBackNavigationButton = true), onRetry = { retries++ }, onBack = { backs++ })

        onNodeWithTag(BACK_TAG).performClick()
        onNodeWithText(retryText).performClick()
        runOnIdle {
            assertEquals(1, backs)
            assertEquals(1, retries)
        }
        onAllNodesWithText(backHintText).assertCountEquals(0)
    }

    @Test
    fun `remote tv shell error page only tells to press back`() = runAniComposeUiTest {
        showError(AniUiBehavior(immersiveShell = true, showBackNavigationButton = false))

        onNodeWithText(backHintText).assertExists()
        onAllNodesWithTag(BACK_TAG).assertCountEquals(0)
        onAllNodesWithText(retryText).assertCountEquals(0)
    }

    private companion object {
        const val BACK_TAG = "back"
    }
}
