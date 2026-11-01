/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.auto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.selector.DefaultMediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.mediasource.web.captcha.createTestWebSessionManager
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.foundation.rememberBackgroundScope
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.createTestMediaSourceInfoProvider
import me.him188.ani.app.ui.mediafetch.createTestMediaSourceResultsFilterer
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize.Companion.megaBytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertEquals

/** 自动匹配页顶上的「已缓存」一行: 这一集的缓存列在在线源前面, 点了上报; 没下完的显示但不可点, 别的集的缓存不列. */
@OptIn(TestOnly::class)
class AutoMatchCachedRowTest {
    private fun cache(id: String, episode: Int): Media = createTestDefaultMedia(
        mediaId = "cache.$id",
        mediaSourceId = "local-cache",
        originalUrl = "file:///cache/$id",
        download = ResourceLocation.LocalFile("/cache/$id.mkv"),
        originalTitle = "cached $id",
        publishedTime = 0,
        properties = createTestMediaProperties(subjectName = SUBJECT, resolution = "1080P", alliance = "group $id", size = 100.megaBytes),
        episodeRange = EpisodeRange.single(EpisodeSort(episode)),
        location = MediaSourceLocation.Local,
        kind = MediaSourceKind.LocalCache,
    )

    private val ready = cache("ready", 1)
    private val downloading = cache("downloading", 1)
    private val otherEpisode = cache("other", 2)

    private val context = MediaSelectorContext.EmptyForPreview.copy(
        subjectInfo = SubjectInfo.Empty.copy(name = SUBJECT, nameCn = SUBJECT),
        episodeInfo = EpisodeInfo.Empty.copy(sort = EpisodeSort(1), ep = EpisodeSort(1)),
        unplayableCacheMediaIds = setOf(downloading.mediaId),
    )

    private fun AniComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun AniComposeUiTest.setPage(onClickItem: (Media) -> Unit = {}) {
        setContent {
            ProvideCompositionLocalsForPreview {
                val scope = rememberBackgroundScope()
                val state = remember(scope) {
                    MediaSelectorState(
                        DefaultMediaSelector(
                            mediaSelectorContextNotCached = flowOf(context),
                            mediaListNotCached = MutableStateFlow(listOf(ready, downloading, otherEpisode)),
                            savedUserPreference = flowOf(MediaPreference.Empty),
                            savedDefaultPreference = flowOf(MediaPreference.Empty),
                            mediaSelectorSettings = flowOf(MediaSelectorSettings.Default),
                        ),
                        mediaSourceFetchResults = createTestMediaSourceResultsFilterer(scope.backgroundScope).filteredSourceResults,
                        createTestMediaSourceInfoProvider(),
                        preferredWebMediaSource = flowOf(null),
                        scope.backgroundScope,
                        createTestWebSessionManager(scope.backgroundScope),
                    )
                }
                Box(Modifier.size(400.dp, 800.dp)) {
                    AutoMatchPage(state, onClickItem = onClickItem, onRestartSource = {}, onRequestManualSearch = null)
                }
            }
        }
    }

    @Test
    fun `cached copies of this episode are listed and tapping one reports it`() = runAniComposeUiTest {
        var clicked: Media? = null
        setPage(onClickItem = { clicked = it })
        waitUntil { exists(AutoMatchPageTestTags.cachedItem(ready.mediaId)) }

        onNodeWithTag(AutoMatchPageTestTags.cachedItem(ready.mediaId)).assertIsEnabled().performClick()
        runOnIdle { assertEquals(ready.mediaId, clicked?.mediaId) }
    }

    @Test
    fun `unfinished cache is shown disabled and other episodes are left out`() = runAniComposeUiTest {
        setPage()
        waitUntil { exists(AutoMatchPageTestTags.cachedItem(downloading.mediaId)) }

        onNodeWithTag(AutoMatchPageTestTags.cachedItem(downloading.mediaId)).assertIsNotEnabled()
        onNodeWithTag(AutoMatchPageTestTags.cachedItem(otherEpisode.mediaId)).assertDoesNotExist()
    }

    private companion object {
        const val SUBJECT = "test subject"
    }
}
