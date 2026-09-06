/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.subject.person.PeopleRowItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「查看全部」弹窗的外壳 ([ViewAllPanelDialog]) 与 Compose 版人物画廊 ([ComposeViewAllGalleries], 没有原生实现的平台用).
 */
class ViewAllGalleryTest {
    @Test
    fun `panel header shows title count and subtitle`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                ViewAllPanelDialog("Characters", onDismissRequest = {}, count = 34, subtitle = "Some show") {}
            }
        }
        onNodeWithText("Characters").assertExists()
        onNodeWithText("34").assertExists()
        onNodeWithText("Some show").assertExists()
        // 关闭钮只在触屏上有
        onNodeWithTag(VIEW_ALL_CLOSE_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun `touch input shows a close button that dismisses`() = runAniComposeUiTest {
        var dismissed = false
        setContent {
            ProvideCompositionLocalsForPreview {
                CompositionLocalProvider(LocalTvTouchInputEnabled provides true) {
                    ViewAllPanelDialog("Staff", onDismissRequest = { dismissed = true }) {}
                }
            }
        }
        onNodeWithTag(VIEW_ALL_CLOSE_BUTTON_TAG).performClick()
        runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun `compose people gallery shows the badge and reports clicks`() = runAniComposeUiTest {
        var clicked = -1
        setContent {
            ProvideCompositionLocalsForPreview {
                Column {
                    ComposeViewAllGalleries.PeopleGallery(
                        items = listOf(
                            PeopleRowItem(null, "Alice", "CV Bob", badge = "Lead"),
                            PeopleRowItem(null, "Carol", ""),
                        ),
                        onClick = { clicked = it },
                        onLongPress = null,
                        onBind = {},
                        initialFocus = Modifier,
                        modifier = Modifier,
                    )
                }
            }
        }
        onNodeWithText("Lead").assertExists()
        onNodeWithText("CV Bob").assertExists()
        onNodeWithText("Carol").performClick()
        runOnIdle { assertEquals(1, clicked) }
    }
}
