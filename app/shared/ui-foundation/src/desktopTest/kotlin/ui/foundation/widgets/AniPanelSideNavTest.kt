/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @see AniPanelSideNavHost
 */
class AniPanelSideNavTest {
    private val items = listOf("one", "two", "three")

    @Test
    fun `arrows and swipes step through items and stop at the ends`() = runAniComposeUiTest {
        var index by mutableIntStateOf(0)
        setContent {
            // 箭头与横滑只在触屏上有
            CompositionLocalProvider(LocalTvTouchInputEnabled provides true) {
                AniScrollableTextDialog(
                    title = items[index],
                    text = "body $index",
                    onDismissRequest = {},
                    onHorizontalNav = { delta -> (index + delta).takeIf { it in items.indices }?.let { index = it } },
                    canHorizontalNav = { delta -> index + delta in items.indices },
                )
            }
        }
        onNodeWithTag(TAG_PANEL_NAV_PREVIOUS).assertIsNotEnabled()
        onNodeWithTag(TAG_PANEL_NAV_NEXT).assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, index) }
        onNodeWithTag(TAG_PANEL_NAV_NEXT).performClick()
        runOnIdle { assertEquals(2, index) }
        onNodeWithTag(TAG_PANEL_NAV_NEXT).assertIsNotEnabled()

        // 往右滑 = 上一个, 往左滑 = 下一个
        onNodeWithText("body 2").performTouchInput { swipeRight() }
        runOnIdle { assertEquals(1, index) }
        onNodeWithText("body 1").performTouchInput { swipeLeft() }
        runOnIdle { assertEquals(2, index) }
        // 到头了再往左滑不动
        onNodeWithText("body 2").performTouchInput { swipeLeft() }
        runOnIdle { assertEquals(2, index) }
    }

    @Test
    fun `no arrows without touch input`() = runAniComposeUiTest {
        setContent {
            AniScrollableTextDialog(
                title = "one",
                text = "body",
                onDismissRequest = {},
                onHorizontalNav = {},
            )
        }
        onNodeWithText("body").assertExists()
        onNodeWithTag(TAG_PANEL_NAV_PREVIOUS).assertDoesNotExist()
        onNodeWithTag(TAG_PANEL_NAV_NEXT).assertDoesNotExist()
    }
}
