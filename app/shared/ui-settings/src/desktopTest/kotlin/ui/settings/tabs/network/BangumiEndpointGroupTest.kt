/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.network

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.settings.SettingsTab
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * 「登录与收藏同步也经过镜像」: 打开前必须在弹窗里确认风险, 关掉直接生效.
 */
@OptIn(TestOnly::class)
class BangumiEndpointGroupTest {
    /** @return 读当前设置值 */
    private fun AniComposeUiTest.showGroup(initial: BangumiEndpointSettings): () -> BangumiEndpointSettings {
        val value = mutableStateOf(initial)
        setContent {
            ProvideCompositionLocalsForPreview {
                SettingsTab {
                    val scope = rememberCoroutineScope()
                    // 占位值要是另一个实例: 与当前值是同一个对象时算「加载中」, 开关是禁用的
                    val state = remember {
                        SettingsState(value, onUpdate = { value.value = it }, placeholder = initial.copy(), scope)
                    }
                    BangumiEndpointGroup(state, mirrors = listOf("bangumi.vip"))
                }
            }
        }
        waitForIdle()
        return { value.value }
    }

    @Test
    fun `打开前要先在弹窗里确认风险`() = runAniComposeUiTest {
        val settings = showGroup(BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO))

        onNodeWithTag(BangumiEndpointGroupTestTags.CREDENTIALS_SWITCH).performClick()
        waitForIdle()
        // 弹窗出来了, 但确认之前不写入
        onNodeWithTag(BangumiEndpointGroupTestTags.RISK_CONFIRM).assertExists()
        assertFalse(settings().allowCredentialsViaMirror)

        onNodeWithTag(BangumiEndpointGroupTestTags.RISK_CONFIRM).performClick()
        waitUntil { settings().allowCredentialsViaMirror }
        onNodeWithTag(BangumiEndpointGroupTestTags.RISK_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `在弹窗里取消就保持关闭`() = runAniComposeUiTest {
        val settings = showGroup(BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO))

        onNodeWithTag(BangumiEndpointGroupTestTags.CREDENTIALS_SWITCH).performClick()
        waitForIdle()
        onNodeWithTag(BangumiEndpointGroupTestTags.RISK_CANCEL).performClick()
        waitForIdle()

        onNodeWithTag(BangumiEndpointGroupTestTags.RISK_CANCEL).assertDoesNotExist()
        assertFalse(settings().allowCredentialsViaMirror)
    }

    @Test
    fun `关掉直接生效不用确认`() = runAniComposeUiTest {
        val settings = showGroup(
            BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO, allowCredentialsViaMirror = true),
        )

        onNodeWithTag(BangumiEndpointGroupTestTags.CREDENTIALS_SWITCH).performClick()
        waitUntil { !settings().allowCredentialsViaMirror }
        onNodeWithTag(BangumiEndpointGroupTestTags.RISK_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `用镜像这一档同样要先确认风险`() = runAniComposeUiTest {
        val settings = showGroup(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR))

        onNodeWithTag(BangumiEndpointGroupTestTags.CREDENTIALS_SWITCH).performClick()
        waitForIdle()
        onNodeWithTag(BangumiEndpointGroupTestTags.RISK_CONFIRM).assertExists()
        assertFalse(settings().allowCredentialsViaMirror)
    }

    @Test
    fun `只连官方时没有这个开关`() = runAniComposeUiTest {
        showGroup(BangumiEndpointSettings(mode = BangumiEndpointMode.DIRECT))
        onNodeWithTag(BangumiEndpointGroupTestTags.CREDENTIALS_SWITCH).assertDoesNotExist()
    }
}
