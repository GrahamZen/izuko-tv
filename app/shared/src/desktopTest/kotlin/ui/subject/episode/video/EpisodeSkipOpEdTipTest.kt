/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.video

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.him188.ani.app.data.models.preference.SkipOpEdMode
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.foundation.stateOf
import me.him188.ani.app.ui.framework.doesNotExist
import me.him188.ani.app.ui.framework.exists
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.metadata.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * @see EpisodeSkipOpEdTip
 */
@OptIn(InternalMediampApi::class)
class EpisodeSkipOpEdTipTest {
    private val opChapter = listOf(Chapter("op", 90_000L, 10_000L))

    @Test
    fun `manual mode offers skip inside op and jumps to its end`() = runAniComposeUiTest {
        var skipTo = 0L
        val state = PlayerSkipOpEdState(
            stateOf(opChapter),
            onSkip = { skipTo = it },
            stateOf(24.minutes),
            mode = stateOf(SkipOpEdMode.MANUAL),
        )
        setContent {
            ProvideCompositionLocalsForPreview { EpisodeSkipOpEdTip(state) }
        }
        runOnIdle { assertEquals(true, onNodeWithTag(TAG_SKIP_OP_ED_NOW).doesNotExist()) }

        runOnIdle { state.update(20_000L) }
        waitUntil { onNodeWithTag(TAG_SKIP_OP_ED_NOW).exists() }
        onNodeWithText("OP", substring = true).assertExists()

        onNodeWithTag(TAG_SKIP_OP_ED_NOW).performClick()
        runOnIdle { assertEquals(100_000L, skipTo) }
        waitUntil { onNodeWithTag(TAG_SKIP_OP_ED_NOW).doesNotExist() }
    }

    @Test
    fun `auto mode countdown shows cancel instead of skip`() = runAniComposeUiTest {
        val state = PlayerSkipOpEdState(stateOf(opChapter), onSkip = {}, stateOf(24.minutes))
        setContent {
            ProvideCompositionLocalsForPreview { EpisodeSkipOpEdTip(state) }
        }
        runOnIdle { state.update(7_000L) }
        runOnIdle { assertEquals(true, state.showSkipTips) }
        waitUntil { onNodeWithText("取消", substring = true).exists() || onNodeWithText("Cancel", substring = true).exists() }
        runOnIdle { assertEquals(true, onNodeWithTag(TAG_SKIP_OP_ED_NOW).doesNotExist()) }
    }
}
