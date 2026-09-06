/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import me.him188.ani.app.data.models.preference.VideoEnhancementDefaultMode
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.settings.SettingsTab
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「默认画质增强」: 选「关闭」以外的档位要先在弹窗里确认, 取消就保持原档; 改回「关闭」直接生效.
 */
@OptIn(TestOnly::class)
class VideoEnhancementDefaultItemTest {
    /** @return 读当前设置里的档位 */
    private fun AniComposeUiTest.showItem(initial: VideoEnhancementDefaultMode): () -> VideoEnhancementDefaultMode {
        val value = mutableStateOf(VideoScaffoldConfig.Default.copy(videoEnhancementDefaultMode = initial))
        setContent {
            ProvideCompositionLocalsForPreview {
                SettingsTab {
                    val scope = rememberCoroutineScope()
                    // 占位值要是另一个实例: 与当前值是同一个对象时算「加载中」
                    val state = remember {
                        SettingsState(value, onUpdate = { value.value = it }, placeholder = value.value.copy(), scope)
                    }
                    val config by state
                    VideoEnhancementDefaultItem(config, state)
                }
            }
        }
        waitForIdle()
        return { value.value.videoEnhancementDefaultMode }
    }

    private fun AniComposeUiTest.choose(mode: VideoEnhancementDefaultMode) {
        onNodeWithTag(VideoEnhancementDefaultItemTestTags.ITEM).performClick()
        waitForIdle()
        // 菜单项把里面的文字合并进了自己的语义节点
        onNodeWithTag(VideoEnhancementDefaultItemTestTags.option(mode), useUnmergedTree = true).performClick()
        waitForIdle()
    }

    @Test
    fun `选关闭以外的档位要先确认`() = runAniComposeUiTest {
        val mode = showItem(VideoEnhancementDefaultMode.OFF)

        choose(VideoEnhancementDefaultMode.PERFORMANCE)
        // 弹窗出来了, 但确认之前不写入
        onNodeWithTag(VideoEnhancementDefaultItemTestTags.CONFIRM).assertExists()
        assertEquals(VideoEnhancementDefaultMode.OFF, mode())

        onNodeWithTag(VideoEnhancementDefaultItemTestTags.CONFIRM).performClick()
        waitUntil { mode() == VideoEnhancementDefaultMode.PERFORMANCE }
        onNodeWithTag(VideoEnhancementDefaultItemTestTags.CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `在弹窗里取消就保持原档`() = runAniComposeUiTest {
        val mode = showItem(VideoEnhancementDefaultMode.OFF)

        choose(VideoEnhancementDefaultMode.QUALITY)
        onNodeWithTag(VideoEnhancementDefaultItemTestTags.CANCEL).performClick()
        waitForIdle()

        onNodeWithTag(VideoEnhancementDefaultItemTestTags.CANCEL).assertDoesNotExist()
        assertEquals(VideoEnhancementDefaultMode.OFF, mode())
    }

    @Test
    fun `开着时换另一档也要确认`() = runAniComposeUiTest {
        val mode = showItem(VideoEnhancementDefaultMode.PERFORMANCE)

        choose(VideoEnhancementDefaultMode.QUALITY)
        onNodeWithTag(VideoEnhancementDefaultItemTestTags.CONFIRM).assertExists()
        assertEquals(VideoEnhancementDefaultMode.PERFORMANCE, mode())
    }

    @Test
    fun `改回关闭直接生效不用确认`() = runAniComposeUiTest {
        val mode = showItem(VideoEnhancementDefaultMode.QUALITY)

        choose(VideoEnhancementDefaultMode.OFF)
        waitUntil { mode() == VideoEnhancementDefaultMode.OFF }
        onNodeWithTag(VideoEnhancementDefaultItemTestTags.CONFIRM).assertDoesNotExist()
    }
}
