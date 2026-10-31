/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.danmaku

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import me.him188.ani.app.ui.episode.danmaku.DanmakuSourceChips
import me.him188.ani.app.ui.episode.danmaku.DanmakuSourceItem
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.Imported
import kotlin.test.Test
import kotlin.test.assertTrue

class DanmakuServerChipTest {
    private fun item(id: DanmakuServiceId, count: Int) =
        DanmakuSourceItem(id, true, DanmakuMatchMethod.ExactId(1, 1), 0, count)

    @Test
    fun `server shows its name beside the count while built in sources keep the count only`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                DanmakuSourceChips(
                    listOf(
                        item(DanmakuServiceId.Dandanplay, 7182),
                        item(DanmakuServiceId.Imported, 3),
                        item(DanmakuServiceId("Home"), 17928),
                    ),
                    { _, _ -> },
                    {},
                    {},
                    Modifier.width(800.dp),
                )
            }
        }

        onNodeWithTag("danmaku-source-name-Home", useUnmergedTree = true).assertTextEquals("Home").assertIsDisplayed()
        onNodeWithText("17928").assertIsDisplayed()
        onNodeWithText("7182").assertIsDisplayed()
        onNodeWithText("3").assertIsDisplayed()
        onNodeWithTag("danmaku-source-name-${DanmakuServiceId.Dandanplay.value}", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("danmaku-source-name-${DanmakuServiceId.Imported.value}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `server with no danmaku still shows its name`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                DanmakuSourceChips(listOf(item(DanmakuServiceId("Home"), 0)), { _, _ -> }, {}, {})
            }
        }

        onNodeWithTag("danmaku-source-name-Home", useUnmergedTree = true).assertTextEquals("Home").assertIsDisplayed()
    }

    @Test
    fun `long server name is cut and the count stays visible`() = runAniComposeUiTest {
        val long = DanmakuServiceId("A very long danmaku server name from some subscription")
        setContent {
            ProvideCompositionLocalsForPreview {
                DanmakuSourceChips(listOf(item(long, 17928)), { _, _ -> }, {}, {}, Modifier.width(800.dp))
            }
        }

        val name = onNodeWithTag("danmaku-source-name-${long.value}", useUnmergedTree = true)
        name.assertWidthIsAtLeast(40.dp)
        assertTrue(name.getBoundsInRoot().width <= 90.dp, "name took ${name.getBoundsInRoot().width}")
        onNodeWithText("17928").assertIsDisplayed()
    }
}
