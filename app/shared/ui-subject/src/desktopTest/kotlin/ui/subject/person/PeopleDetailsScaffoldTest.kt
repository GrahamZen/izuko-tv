/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.person

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.person.InfoboxRowInfo
import me.him188.ani.app.ui.comment.createTestCommentState
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.subject.details.layout.SubjectDetailsLayoutParams
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 人物 / 角色整页的多栏布局 (电视 960dp 宽即是): 左栏大图在上、基本信息接在它下面, 中栏 (标题 / 简介 / 横滑条 / 评论) 只有一份.
 */
@OptIn(TestOnly::class)
class PeopleDetailsScaffoldTest {
    @Test
    fun `multi-column layout puts basic info under the portrait and composes the center column once`() =
        runAniComposeUiTest {
            setContent {
                ProvideCompositionLocalsForPreview {
                    val scope = rememberCoroutineScope()
                    Box(Modifier.requiredSize(WIDTH, 540.dp)) {
                        PeopleDetailsScaffold(
                            topBarTitle = TITLE,
                            navigationIcon = {},
                            windowInsets = WindowInsets(0),
                            isPlaceholder = false,
                            sidebarImageUrl = null,
                            sidebarInfo = listOf(InfoboxRowInfo(INFO_KEY, "男"), InfoboxRowInfo("生日", "4月1日")),
                            titleBlock = { Text(TITLE) },
                            summary = "简介",
                            centerStrips = {},
                            commentState = remember { createTestCommentState(scope, emptyList()) },
                            compactContent = {},
                        )
                    }
                }
            }

            onAllNodesWithText(TITLE).assertCountEquals(1)
            val title = onNodeWithText(TITLE).getBoundsInRoot()
            val info = onNodeWithText(INFO_KEY).getBoundsInRoot()
            // 大图与中栏标题同在首行顶端; 没有图时按 340:482 占位
            val portraitHeight = SubjectDetailsLayoutParams.calculate(WIDTH).sidebarWidth * (482f / 340f)
            assertTrue(
                info.top >= title.top + portraitHeight,
                "基本信息 (top=${info.top}) 应在大图 (高 $portraitHeight, 顶端 ${title.top}) 下面",
            )
        }

    private companion object {
        val WIDTH = 960.dp
        const val TITLE = "菜月昂"
        const val INFO_KEY = "性别"
    }
}
