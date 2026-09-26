/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediafetch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSourceResultListPresenterTest {
    @Test
    fun `source states show before the candidate list first arrives`() = runTest {
        // 重新搜索换了会话: 选择器的候选还没出第一份, 数据源的「搜索中」要先显示出来
        val presenter = MediaSourceResultListPresenter(
            flowOf(listOf(FakeSource("web1", MediaSourceFetchState.Working))),
            includedMediaFlow = MutableSharedFlow<List<Media>>(),
        )
        val list = withTimeout(1_000) { presenter.presentationFlow.first() }
        assertEquals(MediaSourceFetchState.Working, list.single().state)
        assertEquals(0, list.single().totalCount)
    }

    private class FakeSource(
        override val instanceId: String,
        initialState: MediaSourceFetchState,
    ) : MediaSourceFetchResult {
        override val mediaSourceId: String get() = instanceId
        override val sourceInfo: MediaSourceInfo = MediaSourceInfo(displayName = instanceId)
        override val kind: MediaSourceKind = MediaSourceKind.WEB
        override val state = MutableStateFlow(initialState)
        override val results: Flow<List<Media>> = MutableStateFlow(emptyList())
        override fun restart() {}
        override fun pause() {}
        override fun enable() {}
    }
}
