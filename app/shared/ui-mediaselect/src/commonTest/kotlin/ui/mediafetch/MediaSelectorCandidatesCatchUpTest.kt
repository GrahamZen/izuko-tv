/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediafetch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.selector.DefaultMediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.mediasource.web.captcha.createTestWebSessionManager
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 数据源刚查完、选择器过滤后的候选还没跟上 (见 [candidatesBehind]): 照旧当成在查, 不当 0 条藏起.
 */
@OptIn(TestOnly::class)
class MediaSelectorCandidatesCatchUpTest {
    private val media = TestMediaList.first()

    @Test
    fun `a finished source whose results are not candidates yet stays loading until the candidates catch up`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val candidates = MutableStateFlow(emptyList<Media>())
        val source = CatchUpFetchResult(media.mediaSourceId, MediaSourceFetchState.Succeed(1), listOf(media))
        val state = createState(source, candidates, scope)
        try {
            // 状态已是查完, 候选里还没有它的结果: 还在查, 不藏
            state.presentationFlow.first { p -> p.webSources.any { it.instanceId == source.instanceId && it.isLoading } }
            // 候选跟上了: 不再当成在查
            candidates.value = listOf(media)
            state.presentationFlow.first { p -> p.webSources.none { it.instanceId == source.instanceId && it.isLoading } }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a source that finished without results hides right away`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val source = CatchUpFetchResult(media.mediaSourceId, MediaSourceFetchState.Succeed(1), emptyList())
        val state = createState(source, MutableStateFlow(emptyList()), scope)
        try {
            val presentation = state.presentationFlow.first { !it.isPlaceholder }
            assertTrue(presentation.webSources.none { it.instanceId == source.instanceId }, "查完 0 条: 藏起")
        } finally {
            scope.cancel()
        }
    }

    private fun createState(
        source: MediaSourceFetchResult,
        candidates: Flow<List<Media>>,
        scope: CoroutineScope,
    ) = MediaSelectorState(
        mediaSelector = DefaultMediaSelector(
            mediaSelectorContextNotCached = flowOf(MediaSelectorContext.EmptyForPreview),
            mediaListNotCached = candidates,
            savedUserPreference = flowOf(MediaPreference.Empty),
            savedDefaultPreference = flowOf(MediaPreference.Empty),
            mediaSelectorSettings = flowOf(MediaSelectorSettings.Default),
        ),
        mediaSourceFetchResults = flowOf(listOf(source)),
        mediaSourceInfoProvider = createTestMediaSourceInfoProvider(),
        preferredWebMediaSource = flowOf(null),
        backgroundScope = scope,
        webSessionManager = createTestWebSessionManager(scope),
    )
}

private class CatchUpFetchResult(
    override val mediaSourceId: String,
    initialState: MediaSourceFetchState,
    initialResults: List<Media>,
) : MediaSourceFetchResult {
    override val instanceId: String = mediaSourceId
    override val sourceInfo: MediaSourceInfo = MediaSourceInfo(displayName = mediaSourceId)
    override val kind: MediaSourceKind = MediaSourceKind.WEB
    override val state = MutableStateFlow(initialState)
    override val results: Flow<List<Media>> = MutableStateFlow(initialResults)

    override fun restart() {
        state.value = MediaSourceFetchState.Working
    }

    override fun pause() {}

    override fun enable() {}
}
