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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import me.him188.ani.app.ui.richtext.UIRichElement
import me.him188.ani.app.ui.comment.UIRichText
import me.him188.ani.app.ui.comment.generateUiComment
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_CONTENT_PADDING
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.search.createTestPager
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** 评论弹窗的单列列表: 折叠的长评论点一下展开. 顺带把排出来的样子存成图 (build/screenshots), 对照稿用, 不进仓库. */
@OptIn(TestOnly::class)
class CommentsListTest {
    private val longText = UIRichText(
        listOf(
            UIRichElement.AnnotatedText(
                listOf(
                    UIRichElement.Annotated.Text(
                        "后期剧情崩坏。奥米的人物作用在哪？莫名其妙出现的谜妹，莫名其妙的加入，起到一个ATM的作用。7，8女主逛街两集，" +
                                "塑造特别单薄啊，一整季下来就一精神小妹。d脑子里就只有复仇，到最后都没想明白自己的意义吧。罗曼前期智商在线，观感不错。" +
                                "但是后期就完全变了一个人，整个故事收得很仓促。结尾那几集的节奏也很奇怪，前面埋下的线索基本都没有回收，配角的故事线更是直接消失了，看完只觉得可惜。",
                    ),
                ),
            ),
        ),
    )
    private val shortText = UIRichText(listOf(UIRichElement.AnnotatedText(listOf(UIRichElement.Annotated.Text("相信扳机社，爱饭信坂等坂")))))

    // 长短交替, 同详情页常见的样子
    private val comments = generateUiComment(3, content = longText).zip(generateUiComment(3, content = shortText)) { a, b -> listOf(a, b) }.flatten()

    @Test
    fun `tapping a collapsed comment expands it`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                CompositionLocalProvider(LocalTvTouchInputEnabled provides true) {
                    // 电视上面板 0.88 屏高, 去掉标题行剩给列表的大约这么高
                    Surface(Modifier.size(800.dp, 400.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(CENTERED_PANEL_CONTENT_PADDING)) {
                            val items = remember { createTestPager(comments) }.collectAsLazyPagingItems()
                            CommentsList(items, showRating = true, initialFocusIndex = 0, initialFocus = Modifier)
                        }
                    }
                }
            }
        }
        waitForIdle()
        saveScreenshot("comments-list")

        val first = onAllNodesWithTag(COMMENT_LIST_ITEM_TAG)[0]
        val collapsed = first.fetchSemanticsNode().size.height
        first.performClick()
        waitForIdle()
        assertTrue(first.fetchSemanticsNode().size.height > collapsed)
        saveScreenshot("comments-list-expanded")
    }

    private fun AniComposeUiTest.saveScreenshot(name: String) {
        val out = File(System.getProperty("ani.screenshot.out") ?: "build/screenshots").also { it.mkdirs() }
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: return
        File(out, "$name.png").writeBytes(png)
    }
}
