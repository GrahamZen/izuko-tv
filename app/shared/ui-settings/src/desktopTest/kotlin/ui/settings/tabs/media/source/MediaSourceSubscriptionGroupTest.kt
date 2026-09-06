/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media.source

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_source_subscription_updating
import me.him188.ani.app.ui.settings.SettingsTab
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 订阅「全部更新」: 更新中按钮照旧在原处 (遥控器焦点常停在它上面, 换成别的东西焦点就丢了), 旁边写更新到第几个.
 */
class MediaSourceSubscriptionGroupTest {
    @Test
    fun `refresh button keeps focus and shows which subscription is updating`() = runAniComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        setContent {
            ProvideCompositionLocalsForPreview {
                val scope = rememberCoroutineScope()
                val state = remember {
                    MediaSourceSubscriptionGroupState(
                        subscriptionsState = mutableStateOf(emptyList<MediaSourceSubscription>()),
                        onUpdateAll = { onProgress ->
                            runs++
                            onProgress(1, 3)
                            gate.await()
                        },
                        onAdd = {},
                        onDelete = {},
                        onExportLocalChangesToString = { "" },
                        backgroundScope = scope,
                    )
                }
                SettingsTab { MediaSourceSubscriptionGroup(state) }
            }
        }
        val updating = runBlocking { getString(Lang.settings_media_source_subscription_updating, 1, 3) }
        val refresh = onNodeWithTag(MediaSourceSubscriptionGroupTestTags.REFRESH)
        refresh.requestFocus()
        refresh.assertIsFocused()

        refresh.performClick()
        waitForIdle()
        onNodeWithText(updating).assertIsDisplayed()
        refresh.assertIsFocused()

        // 更新中再按不会重来
        refresh.performClick()
        waitForIdle()
        assertEquals(1, runs)

        gate.complete(Unit)
        waitForIdle()
        onNodeWithText(updating).assertDoesNotExist()
        refresh.assertIsFocused()
    }
}
