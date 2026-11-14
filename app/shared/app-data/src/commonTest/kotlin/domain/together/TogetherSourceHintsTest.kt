/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.flow.MutableStateFlow
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle
import me.him188.ani.app.domain.media.ExternalLink
import me.him188.ani.app.domain.media.ExternalLinkMedia
import me.him188.ani.app.domain.media.continuation.DriveFolderLineage
import me.him188.ani.app.domain.media.continuation.DriveShareLineage
import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import me.him188.ani.app.domain.media.continuation.TorrentLineage
import me.him188.ani.app.domain.media.continuation.WebSourceLineage
import me.him188.ani.app.domain.mediasource.clouddrive.DriveFolderLocation
import me.him188.ani.app.domain.media.resolver.WebViewDeferral
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.mediasource.clouddrive.DriveShareLocation
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TogetherSourceHintsTest {
    private val web = WebSourceLineage("site", subjectName = "条目", channel = "线路1", subjectUrl = "https://example.com/s/1")
    private val share = DriveShareLineage("drive-share", "drive", DriveShareLocation("s1", "", "条目", "d1", emptyList()))
    // 播放出错的站点记在全局 (ExternalLinkMedia), 这里的站点名与别的测试不重
    private val link = ExternalLink("https://hints-cdn.example/v/7.m3u8", mapOf("Referer" to "https://example.com/"), "第 7 集")

    private fun watching(episodeId: Int = 7, link: ExternalLink? = null, lineage: PlaybackLineage? = null, subjectId: Int = 100) = TogetherWatchingInfo(
        subjectId = subjectId, episodeId = episodeId, subjectName = "S", episodeSort = "07", episodeName = "E",
        positionMillis = 0, positionAtMillis = 0, durationMillis = 1_440_000, paused = false, link = link, lineage = lineage,
    )

    private fun member(id: String, isHost: Boolean = false, watching: TogetherWatchingInfo? = null, state: TogetherMemberState = TogetherMemberState.WATCHING) =
        TogetherMember(id, id, isHost = isHost, following = true, state = state, lastSeenAt = 0, watching = watching)

    private fun snapshot(vararg members: TogetherMember) =
        TogetherRoomSnapshot(version = 1, status = TogetherRoomStatus.OPEN, hostId = "host", roomTime = 0, members = members.toList())

    private fun media(kind: MediaSourceKind = MediaSourceKind.WEB, id: String = "own"): Media = DefaultMedia(
        mediaId = id, mediaSourceId = "site", originalUrl = "https://example.com/play/1",
        download = ResourceLocation.WebVideo("https://example.com/play/1"), originalTitle = "第 7 集", publishedTime = 0,
        properties = MediaProperties(
            subjectName = null, episodeName = null, subtitleLanguageIds = emptyList(), resolution = "", alliance = "",
            size = FileSize.Unspecified, subtitleKind = null,
        ),
        episodeRange = null, location = MediaSourceLocation.Online, kind = kind,
    )

    @Test
    fun `only lineages of online sources and shared folders are handed to the room`() {
        assertSame(web, web.shareableForTogether())
        assertSame(share, share.shareableForTogether())
        // BT 不交; 自己网盘里的文件夹别人看不到
        assertNull(TorrentLineage("bt", packMediaId = null, alliance = "组", resolution = "1080P", subtitleLanguageIds = emptyList()).shareableForTogether())
        assertNull(DriveFolderLineage("drive", "drive", DriveFolderLocation("f1", emptyList())).shareableForTogether())
    }

    @Test
    fun `the source of the host comes first and only others on the same episode count`() {
        val other = web.copy(channel = "线路2")
        val hints = sourceHintsOf(
            snapshot(
                member("a", watching = watching(lineage = other)),
                member("host", isHost = true, watching = watching(link = link, lineage = web)),
                // 自己的、掉线的、别的集的、还没装好的不算
                member("me", watching = watching(lineage = web.copy(channel = "自己"))),
                member("gone", watching = watching(link = link.copy(url = "https://cdn.example/gone")), state = TogetherMemberState.DISCONNECTED),
                member("b", watching = watching(episodeId = 8, lineage = web.copy(channel = "别的集"))),
                member("c", watching = watching()),
                // 只交了地址的也算; 与别人重复的只留一条
                member("d", watching = watching(link = link.copy(url = "https://cdn.example/d"))),
                member("e", watching = watching(lineage = other)),
            ),
            selfId = "me", subjectId = 100, episodeId = 7,
        )
        assertEquals(
            listOf(TogetherSourceHint(link, web), TogetherSourceHint(null, other), TogetherSourceHint(link.copy(url = "https://cdn.example/d"), null)),
            hints,
        )
    }

    @Test
    fun `a handed-over link replaces only what the device has not settled on`() {
        val loaded = VideoLoadingState.Succeed(engineKey = null)
        val own = media()
        // 还没选上; 自动选上还在解析、解析失败
        assertTrue(replaceable(null, VideoLoadingState.Initial, manual = null))
        assertTrue(replaceable(own, VideoLoadingState.ResolvingSource, manual = null))
        assertTrue(replaceable(own, VideoLoadingState.NetworkError, manual = null))
        // 已经装好在播; 用户自己选的; 本机缓存; 本来就是别人交来的
        assertFalse(replaceable(own, loaded, manual = null))
        assertFalse(replaceable(own, VideoLoadingState.ResolvingSource, manual = own))
        assertFalse(replaceable(media(MediaSourceKind.LocalCache), VideoLoadingState.ResolvingSource, manual = null))
        val handed = ExternalLinkMedia.create(ExternalLinkMedia.Origin(TOGETHER_LINK_ORIGIN_ID, ExternalLinkMedia.OnError.FALL_BACK), link)
        assertFalse(replaceable(handed, VideoLoadingState.ResolvingSource, manual = null))
    }

    @Test
    fun `a link replaces an automatic choice that is still loading and gives it back when it fails`() = runFetchMediaSelectorTestSuite {
        initSubject("Example Series")
        val (_, session, _) = configureFetchSession {
            object {
                val site by web()
            }
        }
        val episode = preferenceApi.mediaSelectorContext.value.episodeInfo!!
        val bundle = MediaFetchSelectBundle(session, selector)
        val hints = TogetherSourceHints(subjectId = 100, selection = null, continuation = null)
        val tried = HashSet<Any>()
        val loading = MutableStateFlow<VideoLoadingState>(VideoLoadingState.ResolvingSource)
        val manual = MutableStateFlow<Media?>(null)
        val own = this@TogetherSourceHintsTest.media(id = "own")
        assertNotNull(selector.selectAutomatically(own, expectedSelection = null))

        // 自动选上的那条还在解析: 换成房间里交来的地址; 被顶掉的那条不算坏
        hints.use(listOf(TogetherSourceHint(link, web)), tried, episode, bundle, loading, manual)
        val handed = assertNotNull(selector.selected.value)
        assertTrue(ExternalLinkMedia.isExternalLink(handed))
        assertEquals(link.url, handed.download.uri)
        assertTrue(WebViewDeferral.takeSwitchingAway(own.mediaId))

        // 交来的地址播不了: 换回被顶掉的那条; 这个地址不再试第二次
        ExternalLinkMedia.recoverFromError(handed, selector) { error("the replaced media should be selected again") }
        assertSame(own, selector.selected.value)
        hints.use(listOf(TogetherSourceHint(link, web)), tried, episode, bundle, loading, manual)
        assertSame(own, selector.selected.value)

        // 别人交来的另一个地址: 自己那条已经装好在播就不换; 用户手动选的不换
        val another = link.copy(url = "https://hints-cdn2.example/v/7b.m3u8")
        loading.value = VideoLoadingState.Succeed(engineKey = null)
        hints.use(listOf(TogetherSourceHint(another, null)), tried, episode, bundle, loading, manual)
        assertSame(own, selector.selected.value)
        loading.value = VideoLoadingState.ResolvingSource
        manual.value = own
        hints.use(listOf(TogetherSourceHint(another, null)), tried, episode, bundle, loading, manual)
        assertSame(own, selector.selected.value)
        // 刚在本机播不了的站点交来的别的地址 (下一集的也一样): 一阵子内不再试
        manual.value = null
        hints.use(listOf(TogetherSourceHint(link.copy(url = "https://hints-cdn.example/v/8.m3u8"), null)), tried, episode, bundle, loading, manual)
        assertSame(own, selector.selected.value)
        // 收不得的地址 (局域网里的) 不用
        hints.use(listOf(TogetherSourceHint(link.copy(url = "http://192.168.1.1/v.m3u8"), null)), tried, episode, bundle, loading, manual)
        assertSame(own, selector.selected.value)
        // 别的站点交来的照常换上
        hints.use(listOf(TogetherSourceHint(another, null)), tried, episode, bundle, loading, manual)
        assertEquals(another.url, selector.selected.value?.download?.uri)
    }

    @Test
    fun `the link and the lineage travel in the room messages`() {
        val report = TogetherMessage.Report("me", TogetherSelfReport("Me", following = true, watching = watching(link = link, lineage = web)))
        assertEquals(report, TogetherMessageCodec.decode(TogetherMessageCodec.encode(report)))
    }

    @Test
    fun `a new link or lineage is told to the room`() {
        val before = watching()
        assertTrue(before.predicts(before.copy(positionMillis = 500, positionAtMillis = 500), 1_500))
        assertFalse(before.predicts(before.copy(lineage = web), 1_500))
        assertFalse(before.predicts(before.copy(link = link), 1_500))
    }
}
