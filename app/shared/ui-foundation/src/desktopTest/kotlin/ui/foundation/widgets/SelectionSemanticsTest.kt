/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 自己画选中 / 勾选的控件在接点击的那个节点上报出状态 (见 SelectionSemantics.kt), 读屏与调试界面接口靠它. */
class SelectionSemanticsTest {
    @Test
    fun `a chip reports whether it is the selected one on the node that takes the click`() = runAniComposeUiTest {
        setContent {
            Row {
                AniFocusChip("新剧集", selected = true, onClick = {}, modifier = Modifier.testTag("on"))
                AniFocusChip("全部剧集", selected = false, onClick = {}, modifier = Modifier.testTag("off"))
            }
        }
        onNodeWithTag("on", useUnmergedTree = true).assertHasClickAction().assertIsSelected()
        onNodeWithTag("off", useUnmergedTree = true).assertHasClickAction().assertIsNotSelected()
    }

    @Test
    fun `the selectable surfaces report their selected state`() = runAniComposeUiTest {
        setContent {
            Row {
                AniFocusSelectableSurface(onClick = {}, selected = true, shape = RoundedCornerShape(8.dp), modifier = Modifier.testTag("cell")) {
                    Box(Modifier.size(40.dp))
                }
                AniFocusRingSurface(onClick = {}, selected = true, shape = RoundedCornerShape(8.dp), modifier = Modifier.testTag("card")) {
                    Box(Modifier.size(40.dp))
                }
            }
        }
        onNodeWithTag("cell", useUnmergedTree = true).assertIsSelected()
        onNodeWithTag("card", useUnmergedTree = true).assertIsSelected()
    }

    @Test
    fun `a hand drawn check row reports on and off`() = runAniComposeUiTest {
        setContent {
            Row {
                Box(Modifier.testTag("checked").clickable {}.checkedSemantics(true)) { Text("不再询问") }
                Box(Modifier.testTag("unchecked").clickable {}.checkedSemantics(false)) { Text("同时取消订阅") }
            }
        }
        onNodeWithTag("checked", useUnmergedTree = true).assertIsOn()
        onNodeWithTag("unchecked", useUnmergedTree = true).assertIsOff()
    }

    @Test
    fun `a control that is not an option reports no selected state`() = runAniComposeUiTest {
        setContent {
            AniFocusActionButton(onClick = {}, modifier = Modifier.testTag("button")) { Text("订阅") }
        }
        val config = onNodeWithTag("button", useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals(null, config.getOrNull(SemanticsProperties.Selected))
    }
}
