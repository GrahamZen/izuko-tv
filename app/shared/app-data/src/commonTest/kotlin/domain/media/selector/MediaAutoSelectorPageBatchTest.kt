/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.domain.media.fetch.BatchedSizedSource
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.selector.testFramework.FetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.channelTiers
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.tier
import me.him188.ani.datasources.api.source.MediaSourceKind.WEB
import me.him188.ani.test.DisabledOnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * 一页的资源按批交出 ([BatchedSizedSource]) 时, 选源器拿到的是整页: 过了「立即」档才到的一页上, 排在前面的差线路不会先被选中.
 */
@DisabledOnNative
class MediaAutoSelectorPageBatchTest {
    @Test
    fun `a page arriving after the instant stage is judged whole and its better line wins`() = runFetchMediaSelectorTestSuite {
        assertEquals("line-t1", selectedLineOfLatePage(itemByItem = false))
    }

    @Test
    fun `delivered item by item the line listed first is selected before the better one arrives`() = runFetchMediaSelectorTestSuite {
        // 不分批的数据源: 选源器先看到这一页的第一条, 那时它已是能选的里最好的
        assertEquals("line-t3", selectedLineOfLatePage(itemByItem = true))
    }

    /** 过了「立即」档, 一个线路分层级的源交出一页: 差线路 (T3) 列在前, 好线路 (T1) 在后. 返回选中的线路. */
    context(scope: TestScope)
    private fun FetchMediaSelectorTestSuite.selectedLineOfLatePage(itemByItem: Boolean): String? {
        initSubject("Example Series")
        preferenceApi.savedUserPreference.value = MediaPreference.Any
        preferenceApi.mediaSelectorSettings.value = MediaSelectorSettings.AllVisible.copy(
            preferKind = WEB, preferSeasons = false, hideSingleEpisodeForCompleted = false,
        )
        val (_, session, sources) = configureFetchSession {
            object {
                // 一直在查, 让选源停在「精确」阶段等它
                val slow by web { tier = 0 }
                val lines by web {
                    tier = 3
                    channelTiers("line-t3" to 3, "line-t1" to 1)
                    deliverItemByItem = itemByItem
                }
            }
        }
        launchSelection(session)
        scope.advanceTimeBy(5.1.seconds)
        scope.runCurrent()
        assertNull(selector.selected.value)

        sources.lines.complete(
            media(kind = WEB, subjectName = "Example Series", alliance = "line-t3"),
            media(kind = WEB, subjectName = "Example Series", alliance = "line-t1"),
        )
        scope.advanceTimeBy(1.seconds)
        scope.runCurrent()
        return selector.selected.value?.properties?.alliance
    }

    context(scope: TestScope)
    private fun FetchMediaSelectorTestSuite.launchSelection(session: MediaFetchSession): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            MediaAutoSelector(selector).select(
                session,
                MediaAutoSelector.Config(web = MediaAutoSelector.Web(sourceTiers = preferenceApi.sourceTiers!!)),
            )
        }
}
