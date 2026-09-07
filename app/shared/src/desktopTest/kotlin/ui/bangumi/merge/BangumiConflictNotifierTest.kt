/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.bangumi.merge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.bangumi_merge_conflict_notification
import me.him188.ani.app.ui.lang.bangumi_merge_conflict_notification_action
import me.him188.ani.app.ui.user.SelfInfoUiState
import me.him188.ani.app.ui.user.TestSelfInfoUiState
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 主界面 Bangumi 冲突提示 snackbar 的交互测试: 无状态部分 + 接入真实 [me.him188.ani.app.domain.bangumi.BangumiConflictChecker] 的有状态部分.
 */
@OptIn(TestOnly::class)
class BangumiConflictNotifierTest {
    private val now = Instant.fromEpochMilliseconds(1_753_000_000_000)

    // region 无状态

    @Test
    fun `NOTIFY-UI-01 有冲突时展示提示`() = runAniComposeUiTest {
        val message = runBlocking { getString(Lang.bangumi_merge_conflict_notification, 6) }
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    BangumiConflictNotifierContent(conflictCount = 6)
                }
            }
        }

        onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun `NOTIFY-UI-02 提示不带按钮`() = runAniComposeUiTest {
        // 只是个几秒就消失的提示: 既没有"去处理"动作, 也没有关闭按钮 (合并页从设置进)
        val message = runBlocking { getString(Lang.bangumi_merge_conflict_notification, 3) }
        val actionLabel = runBlocking { getString(Lang.bangumi_merge_conflict_notification_action) }
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    BangumiConflictNotifierContent(conflictCount = 3)
                }
            }
        }

        onNodeWithText(message).assertIsDisplayed()
        onNodeWithText(actionLabel).assertDoesNotExist()
    }

    @Test
    fun `NOTIFY-UI-03 无冲突时不展示提示`() = runAniComposeUiTest {
        val message = runBlocking { getString(Lang.bangumi_merge_conflict_notification, 0) }
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    BangumiConflictNotifierContent(conflictCount = 0)
                }
            }
        }

        onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun `NOTIFY-UI-04 提示自动消失后 同计数重组不重新出现`() = runAniComposeUiTest {
        val message = runBlocking { getString(Lang.bangumi_merge_conflict_notification, 3) }
        var unrelatedState by mutableStateOf(0)
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    // 读取无关状态, 使其变化时触发重组.
                    Box(Modifier.testTag("recomposeProbe$unrelatedState"))
                    BangumiConflictNotifierContent(conflictCount = 3)
                }
            }
        }

        onNodeWithText(message).assertIsDisplayed()
        // Short 时长自己走完, 不需要点任何东西
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(message).fetchSemanticsNodes().isEmpty() }

        // 计数不变时, 无关重组不会让提示重新出现.
        unrelatedState = 1
        waitForIdle()
        onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun `NOTIFY-UI-05 冲突数变化后重新提示`() = runAniComposeUiTest {
        val message3 = runBlocking { getString(Lang.bangumi_merge_conflict_notification, 3) }
        val message5 = runBlocking { getString(Lang.bangumi_merge_conflict_notification, 5) }
        var count by mutableStateOf(3)
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    BangumiConflictNotifierContent(conflictCount = count)
                }
            }
        }

        // 等当前提示自己消失.
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(message3).fetchSemanticsNodes().isEmpty() }

        // 冲突数变化: dismissed 以计数为 key, 重新提示.
        count = 5
        waitForIdle()
        onNodeWithText(message5).assertIsDisplayed()
    }

    // endregion

    // region 有状态: 接入 checker

    @Test
    fun `NOTIFY-UI-06 会话有效且已绑定时触发检查并展示冲突数`() = runAniComposeUiTest {
        val message = runBlocking { getString(Lang.bangumi_merge_conflict_notification, 6) }
        val repository = FakeBangumiMergeRepository({ createTestBangumiMergeState(now) })
        val checker = createTestConflictChecker(repository)
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    BangumiConflictNotifier(
                        selfInfo = TestSelfInfoUiState,
                        checker = checker,
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(message).assertIsDisplayed()
        assertEquals(6, checker.conflictCount.value)
        // 重组不会重复检查 (checker 节流).
        assertEquals(1, repository.summaryCalls)
    }

    @Test
    fun `NOTIFY-UI-07 提示自动消失后不清空计数`() = runAniComposeUiTest {
        val message = runBlocking { getString(Lang.bangumi_merge_conflict_notification, 6) }
        val repository = FakeBangumiMergeRepository({ createTestBangumiMergeState(now) })
        val checker = createTestConflictChecker(repository)
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    BangumiConflictNotifier(
                        selfInfo = TestSelfInfoUiState,
                        checker = checker,
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(message).fetchSemanticsNodes().isEmpty() }
        // 计数保留: 设置里的入口仍要显示数量; 再提示由 dismissed (以计数为 key) 抑制.
        assertEquals(6, checker.conflictCount.value)
    }

    @Test
    fun `NOTIFY-UI-08 会话失效时 reset 且不展示`() = runAniComposeUiTest {
        val message = runBlocking { getString(Lang.bangumi_merge_conflict_notification, 6) }
        val repository = FakeBangumiMergeRepository({ createTestBangumiMergeState(now) })
        val checker = createTestConflictChecker(repository)
        var selfInfo by mutableStateOf(TestSelfInfoUiState)
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    BangumiConflictNotifier(
                        selfInfo = selfInfo,
                        checker = checker,
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }

        // 登出: 提示关闭, checker 清空.
        selfInfo = selfInfo.copy(isSessionValid = false)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(message).fetchSemanticsNodes().isEmpty() }
        runOnIdle { assertEquals(0, checker.conflictCount.value) }
    }

    @Test
    fun `NOTIFY-UI-09 未绑定 Bangumi 时不检查`() = runAniComposeUiTest {
        val repository = FakeBangumiMergeRepository({ createTestBangumiMergeState(now) })
        val checker = createTestConflictChecker(repository)
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    BangumiConflictNotifier(
                        selfInfo = TestSelfInfoUiState.copy(bangumiConnected = false),
                        checker = checker,
                    )
                }
            }
        }

        waitForIdle()
        // 若错误地启动了检查, 它跑在 Default 线程上: 先等它结束再做否定断言, 否则断言可能在计数增加前就通过.
        runBlocking { checker.joinCheck() }
        runOnIdle {
            assertEquals(0, repository.summaryCalls)
            assertEquals(0, checker.conflictCount.value)
        }
    }

    @Test
    fun `NOTIFY-UI-10 会话加载中不 reset 不检查`() = runAniComposeUiTest {
        val repository = FakeBangumiMergeRepository({ createTestBangumiMergeState(now) })
        val checker = createTestConflictChecker(repository)
        val loading: SelfInfoUiState = TestSelfInfoUiState.copy(isSessionValid = null, bangumiConnected = null)
        setContent {
            ProvideCompositionLocalsForPreview {
                Box(Modifier.fillMaxSize()) {
                    BangumiConflictNotifier(
                        selfInfo = loading,
                        checker = checker,
                    )
                }
            }
        }

        waitForIdle()
        runBlocking { checker.joinCheck() }
        runOnIdle { assertEquals(0, repository.summaryCalls) }
    }

    // endregion
}
