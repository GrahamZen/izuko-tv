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
import me.him188.ani.app.ui.mediaselect.bt.BtResourcesPageTestTags
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

/**
 * 自动匹配页点源的名字进这个源的全部条目. 夹具: 测试用的在线源 `source1` 里三个版本同一条线路 (同网盘里的几个文件), 自动匹配页只有一个线路按钮.
 */
@OptIn(TestOnly::class)
class AutoMatchSourcePageTest {
    private fun media(id: String, resolution: String): Media = createTestDefaultMedia(
        mediaId = "source1.$id",
        mediaSourceId = "source1",
        originalUrl = "https://example.com/$id",
        download = ResourceLocation.HttpStreamingFile("https://example.com/$id.mp4"),
        originalTitle = "file $id",
        publishedTime = 0,
        properties = createTestMediaProperties(subjectName = SUBJECT, resolution = resolution, alliance = "drive", size = 100.megaBytes),
        episodeRange = EpisodeRange.single(EpisodeSort(1)),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )

    private companion object {
        const val SUBJECT = "test subject"

        /** 源列表头一次收到结果前, 查询成功但还没有线路的源会先等一秒再判 (见 MediaSelectorState 的 delayToOvercomeCacheIssue). */
        const val SOURCE_LIST_TIMEOUT = 5_000L
    }

    private val files = listOf(media("uhd", "2160P"), media("fhd", "1080P"), media("hd", "720P"))

    /** 选择器按条目名与集号判匹配 (预览用的空环境里一条都算不上精确匹配, 自动匹配页就不列这个源): 给它条目名与第 1 集. */
    private val context = MediaSelectorContext.EmptyForPreview.copy(
        subjectInfo = SubjectInfo.Empty.copy(name = SUBJECT, nameCn = SUBJECT),
        episodeInfo = EpisodeInfo.Empty.copy(sort = EpisodeSort(1), ep = EpisodeSort(1)),
    )

    /** 节点还没出现时 isDisplayed 会直接抛错, waitUntil 里用它判存在. */
    private fun AniComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun AniComposeUiTest.setPage(onClickItem: (Media) -> Unit = {}) {
        setContent {
            ProvideCompositionLocalsForPreview {
                val scope = rememberBackgroundScope()
                val state = remember(scope) {
                    MediaSelectorState(
                        DefaultMediaSelector(
                            mediaSelectorContextNotCached = flowOf(context),
                            mediaListNotCached = MutableStateFlow(files),
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
    fun `tapping a source name lists every entry of that source and back returns`() = runAniComposeUiTest {
        setPage()
        waitUntil(timeoutMillis = SOURCE_LIST_TIMEOUT) { exists(AutoMatchPageTestTags.sourceName("source1")) }

        onNodeWithTag(AutoMatchPageTestTags.sourceName("source1")).performClick()

        waitUntil { exists(AutoMatchPageTestTags.SOURCE_PAGE) }
        for (file in files) onNodeWithTag(BtResourcesPageTestTags.row(file.mediaId)).assertExists()

        onNodeWithTag(AutoMatchPageTestTags.SOURCE_BACK).performClick()
        waitUntil { exists(AutoMatchPageTestTags.ROOT) }
        onNodeWithTag(AutoMatchPageTestTags.SOURCE_PAGE).assertDoesNotExist()
    }

    @Test
    fun `tapping an entry in the source page reports it`() = runAniComposeUiTest {
        var clicked: Media? = null
        setPage(onClickItem = { clicked = it })
        waitUntil(timeoutMillis = SOURCE_LIST_TIMEOUT) { exists(AutoMatchPageTestTags.sourceName("source1")) }
        onNodeWithTag(AutoMatchPageTestTags.sourceName("source1")).performClick()
        waitUntil { exists(BtResourcesPageTestTags.row("source1.hd")) }

        onNodeWithTag(BtResourcesPageTestTags.row("source1.hd")).performClick()

        runOnIdle { assertEquals("source1.hd", clicked?.mediaId) }
    }
}
