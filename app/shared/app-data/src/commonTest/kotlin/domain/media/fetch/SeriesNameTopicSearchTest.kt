/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.paging.SinglePagePagedSource
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.paging.emptySizedSource
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.DownloadSearchQuery
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.TestHttpMediaSource
import me.him188.ani.datasources.api.source.TopicMediaSource
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.Topic
import me.him188.ani.datasources.api.topic.TopicCategory
import me.him188.ani.datasources.api.topic.TopicDetails
import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesNameTopicSearchTest {
    /** 按关键词给出发布的 BT 源, 记下搜过的关键词. */
    private class FakeTopicSource(private val byKeyword: Map<String, List<Topic>>) : TopicMediaSource() {
        val searched = mutableListOf<String>()
        override val mediaSourceId: String get() = "fake-bt"
        override val info: MediaSourceInfo get() = MediaSourceInfo(displayName = "fake-bt")
        override suspend fun checkConnection(): ConnectionStatus = ConnectionStatus.SUCCESS
        override suspend fun startSearch(query: DownloadSearchQuery): SizedSource<Topic> {
            searched += query.keywords
            return SinglePagePagedSource { byKeyword[query.keywords].orEmpty().asFlow() }
        }
    }

    private fun topic(title: String, episode: String) = topic(title, EpisodeRange.single(episode))

    private fun topic(title: String, range: EpisodeRange) = Topic(
        topicId = title,
        publishedTimeMillis = null,
        category = TopicCategory.ANIME,
        rawTitle = title,
        commentsCount = 0,
        downloadLink = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:${title.hashCode()}"),
        size = FileSize.Zero,
        alliance = "",
        author = null,
        details = TopicDetails(
            tags = emptyList(),
            chineseTitle = null,
            otherTitles = emptyList(),
            episodeRange = range,
            resolution = null,
            frameRate = null,
            mediaOrigin = null,
            subtitleLanguages = emptyList(),
            subtitleKind = null,
        ),
        originalLink = "",
    )

    /** 药屋少女的呢喃 第三季第 1 集: 系列内序号 49. */
    private val thirdSeason = MediaFetchRequest(
        subjectId = "568244",
        episodeId = "1",
        subjectNames = listOf("药屋少女的呢喃 第三季", "薬屋のひとりごと 第3期"),
        episodeSort = EpisodeSort(49),
        episodeEp = EpisodeSort(1),
        episodeName = "",
        fallbackSearchKeywords = listOf("药屋少女的呢喃 第三季", "药屋少女的呢喃"),
    )

    private suspend fun FakeTopicSource.titles(request: MediaFetchRequest) =
        fetchWithSeriesName(request).results.toList().map { it.media.originalTitle }.sorted()

    @Test
    fun `sequel releases titled with the series name are found`() = runTest {
        val source = FakeTopicSource(
            mapOf(
                "药屋少女的呢喃 第三季" to listOf(topic("[绿茶字幕组] 药屋少女的呢喃 第三季 [49]", "49")),
                "药屋少女的呢喃" to listOf(
                    topic("[喵萌奶茶屋][药屋少女的呢喃][49][1080p]", "49"),
                    topic("[豌豆字幕组][药屋少女的呢喃 / Kusuriya no Hitorigoto S3][01(49)]", "01"),
                    // 第一季第 1 集与第二季的最后一集: 分不出是第三季, 不要
                    topic("[喵萌奶茶屋][药屋少女的呢喃][01][1080p]", "01"),
                    topic("[喵萌奶茶屋][药屋少女的呢喃][48][1080p]", "48"),
                    // 标题只写了「S3」、没有集号的被当成整季, 分不出是哪一集
                    topic("[豌豆字幕组][药屋少女的呢喃 / Kusuriya no Hitorigoto S3][简体]", EpisodeRange.season(3)),
                ),
            ),
        )
        assertEquals(
            listOf(
                "[喵萌奶茶屋][药屋少女的呢喃][49][1080p]",
                "[绿茶字幕组] 药屋少女的呢喃 第三季 [49]",
                "[豌豆字幕组][药屋少女的呢喃 / Kusuriya no Hitorigoto S3][01(49)]",
            ),
            source.titles(thirdSeason),
        )
        assertEquals(listOf("药屋少女的呢喃 第三季", "薬屋のひとりごと 第3期", "药屋少女的呢喃"), source.searched)
    }

    @Test
    fun `series name is not searched when its results cannot be told apart`() = runTest {
        // 条目名没写季, 集号也是每季从 1 编: 系列名搜到的「[01]」分不出是哪一季
        val request = thirdSeason.copy(
            subjectNames = listOf("更多 出包王女"),
            episodeSort = EpisodeSort(1),
            fallbackSearchKeywords = listOf("出包王女"),
        )
        val source = FakeTopicSource(mapOf("出包王女" to listOf(topic("[字幕组] 出包王女 [01]", "01"))))
        assertEquals(emptyList(), source.titles(request))
        assertEquals(listOf("更多 出包王女"), source.searched)
    }

    @Test
    fun `subjects without a series name search only their own names`() = runTest {
        val source = FakeTopicSource(emptyMap())
        source.titles(thirdSeason.copy(fallbackSearchKeywords = listOf("药屋少女的呢喃 第三季")))
        assertEquals(listOf("药屋少女的呢喃 第三季", "薬屋のひとりごと 第3期"), source.searched)
    }

    @Test
    fun `sources that do not search titles by keyword are untouched`() = runTest {
        val requests = mutableListOf<MediaFetchRequest>()
        val source = TestHttpMediaSource(fetch = { requests += it; emptySizedSource() })
        source.fetchWithSeriesName(thirdSeason)
        assertEquals(listOf(thirdSeason), requests)
    }
}
