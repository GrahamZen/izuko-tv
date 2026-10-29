/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import kotlinx.datetime.TimeZone
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.TestMatchMetadata
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAuthException
import me.him188.ani.app.domain.mediasource.rule.SiteIpBlockedException
import me.him188.ani.app.ui.media.MediaDetailsStrings
import me.him188.ani.app.ui.mediafetch.MediaPreferenceItemState
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresentation
import me.him188.ani.app.ui.mediafetch.MediaSourceResultPresentation
import me.him188.ani.app.ui.mediaselect.bt.BtListPresentation
import me.him188.ani.app.ui.mediaselect.bt.BtRow
import me.him188.ani.app.ui.mediaselect.bt.BtRowExclusion
import me.him188.ani.app.ui.mediaselect.manual.ManualBrowsePresentation
import me.him188.ani.app.ui.mediaselect.manual.ManualBrowseSource
import me.him188.ani.app.ui.mediaselect.manual.ManualLoadState
import me.him188.ani.app.ui.mediaselect.selector.WebSource
import me.him188.ani.app.ui.mediaselect.selector.WebSourceChannel
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.BrowseChannel
import me.him188.ani.datasources.api.source.BrowseEpisode
import me.him188.ani.datasources.api.source.BrowseSubject
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @see buildTvSourcePanel
 */
@OptIn(TestOnly::class)
class TvSourcePanelModelTest {
    private fun media(
        id: String,
        source: String,
        title: String = "[喵萌奶茶屋] 孤独摇滚 05 [1080P]",
        kind: MediaSourceKind = MediaSourceKind.BitTorrent,
        alliance: String = "喵萌奶茶屋",
        download: ResourceLocation = ResourceLocation.HttpStreamingFile("https://example.com/$id.mp4"),
    ): DefaultMedia = createTestDefaultMedia(
        mediaId = id,
        mediaSourceId = source,
        originalUrl = "https://example.com/$id",
        download = download,
        originalTitle = title,
        publishedTime = 0,
        properties = createTestMediaProperties(alliance = alliance),
        episodeRange = null,
        location = MediaSourceLocation.Online,
        kind = kind,
    )

    private fun sourceResult(
        instanceId: String,
        kind: MediaSourceKind,
        state: MediaSourceFetchState = MediaSourceFetchState.Succeed(1),
    ) = MediaSourceResultPresentation(
        instanceId = instanceId,
        mediaSourceId = instanceId,
        state = state,
        info = MediaSourceInfo(displayName = "name-$instanceId"),
        kind = kind,
        totalCount = 1,
        isPreferred = false,
    )

    private fun webSource(instanceId: String, channels: List<WebSourceChannel>, isError: Boolean = false) = WebSource(
        instanceId = instanceId,
        mediaSourceId = instanceId,
        iconUrl = "",
        name = "name-$instanceId",
        channels = channels,
        isLoading = false,
        isError = isError,
        isPreferred = false,
    )

    private fun selector(webSources: List<WebSource>, selected: DefaultMedia?) = MediaSelectorState.Presentation(
        filteredCandidates = emptyList(),
        preferredCandidates = emptyList(),
        selected = selected,
        alliance = MediaPreferenceItemState.Presentation.placeholder(),
        resolution = MediaPreferenceItemState.Presentation.placeholder(),
        subtitleLanguageId = MediaPreferenceItemState.Presentation.placeholder(),
        mediaSource = MediaPreferenceItemState.Presentation.placeholder(),
        webSources = webSources,
        selectedWebSource = null,
        selectedWebSourceChannel = null,
    )

    private fun bt(included: List<BtRow>, excluded: List<BtRow> = emptyList(), resolution: String? = null) =
        BtListPresentation.Placeholder.copy(
            included = included,
            excluded = excluded,
            totalCount = included.size + excluded.size,
            resolution = resolution,
            availableResolutions = listOf("1080P", "720P"),
            isPlaceholder = false,
        )

    private val webA1 = media("a1", "web-a", kind = MediaSourceKind.WEB, title = "线路1")
    private val webA2 = media("a2", "web-a", kind = MediaSourceKind.WEB, title = "线路2")
    private val webB1 = media("b1", "web-b", kind = MediaSourceKind.WEB, title = "线路1")
    private val btDmhy = media("bt1", "dmhy", download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:E6ZW3NCJPYP4OUI727BMMBLWMAB2STZD&tr=x"))

    // 与 btDmhy 同一个种子: 磁力链接写的是 Base32, 蜜柑的种子文件地址里是十六进制
    private val btMikan = media(
        "bt2", "mikan",
        download = ResourceLocation.HttpTorrentFile("https://mikanani.me/Download/20261002/27b36db4497e1fc7511fd7c2c605766003a94f23.torrent"),
    )
    private val btOther = media(
        "bt3", "dmhy", title = "[桜都字幕组] 孤独摇滚 05 [1080P]", alliance = "桜都字幕组",
        download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:03500680fe4bd5251091af1301c46b28808eb679"),
    )

    private val sources = MediaSourceResultListPresentation(
        listOf(
            sourceResult("web-a", MediaSourceKind.WEB),
            sourceResult("web-b", MediaSourceKind.WEB),
            sourceResult("web-c", MediaSourceKind.WEB, MediaSourceFetchState.Failed(IllegalStateException(), 1)),
            sourceResult("dmhy", MediaSourceKind.BitTorrent),
            sourceResult("mikan", MediaSourceKind.BitTorrent),
        ),
    )

    private val webSources = listOf(
        webSource("web-a", listOf(WebSourceChannel("线路1", webA1), WebSourceChannel("线路2", webA2))),
        webSource("web-b", listOf(WebSourceChannel("线路1", webB1))),
        webSource("web-c", emptyList(), isError = true),
    )

    private fun input(
        selected: DefaultMedia? = null,
        bt: BtListPresentation = bt(listOf(BtRow(btDmhy, null, false), BtRow(btMikan, null, false), BtRow(btOther, null, false))),
        manual: ManualBrowsePresentation? = null,
        sources: MediaSourceResultListPresentation = this.sources,
        webSources: List<WebSource> = this.webSources,
        nowMillis: Long = 0,
    ) = TvSourcePanelInput(
        selector = selector(webSources, selected),
        bt = bt,
        sources = sources,
        manual = manual,
        fullSearch = false,
        canEditKeywords = true,
        canCache = true,
        nowMillis = nowMillis,
    )

    @Test
    fun `rail lists the filter and BT first then source groups failed sources and actions`() {
        val content = buildTvSourcePanel(input(), TvSourceNav(), strings)
        assertEquals(
            listOf(
                TvSourceRailKeys.FILTER, TvSourceRailKeys.BT, TvSourceRailKeys.GROUP_ONLINE, TvSourceRailKeys.FAILED,
                TvSourceRailKeys.ACTION_REFRESH, TvSourceRailKeys.ACTION_FULL_SEARCH, TvSourceRailKeys.ACTION_KEYWORDS,
            ),
            content.rail.map { it.id },
        )
        // 组的胶囊数有结果的源; 组里的源排成源那一栏, 各自数线路
        assertEquals("2", content.rail.first { it.id == TvSourceRailKeys.GROUP_ONLINE }.trailing)
        val online = buildTvSourcePanel(input(), TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE), strings)
        assertEquals(listOf("web:web-a", "web:web-b"), online.sources.map { it.id })
        assertEquals("2", online.sources.first { it.id == "web:web-a" }.trailing)
        assertEquals("1", content.rail.first { it.id == TvSourceRailKeys.FAILED }.trailing)
    }

    @Test
    fun `paused sources show as paused in the status and on the full search command`() {
        val paused = MediaSourceResultListPresentation(
            listOf(
                sourceResult("web-a", MediaSourceKind.WEB),
                sourceResult("web-b", MediaSourceKind.WEB, MediaSourceFetchState.Paused(1)),
                sourceResult("dmhy", MediaSourceKind.BitTorrent, MediaSourceFetchState.Paused(1)),
            ),
        )
        fun fullSearch(content: TvSourcePanelContent) = content.rail.single { it.id == TvSourceRailKeys.ACTION_FULL_SEARCH }.trailing
        val content = buildTvSourcePanel(input(sources = paused), TvSourceNav(), strings)
        // 暂停的不算查完: 写已暂停、已查 1/3, 不写成「已查询」
        assertEquals("searchPaused 1/3", content.status)
        assertEquals("fullSearchPaused", fullSearch(content))
        // 开了完整搜索写「开」; 没有暂停的写「关」
        assertEquals("on", fullSearch(buildTvSourcePanel(input(sources = paused).copy(fullSearch = true), TvSourceNav(), strings)))
        assertEquals("off", fullSearch(buildTvSourcePanel(input(), TvSourceNav(), strings)))
        assertEquals("searched 5", buildTvSourcePanel(input(), TvSourceNav(), strings).status)
    }

    @Test
    fun `a paused source without results shows a pause icon in place and is not folded as empty`() {
        val sources = MediaSourceResultListPresentation(
            listOf(
                sourceResult("web-a", MediaSourceKind.WEB),
                sourceResult("web-b", MediaSourceKind.WEB, MediaSourceFetchState.Paused(1)),
                sourceResult("web-z", MediaSourceKind.WEB),
            ),
        )
        // 选择器把暂停又没结果的、查完 0 条的都藏掉, 只给有结果的
        val content = buildTvSourcePanel(
            input(sources = sources, webSources = listOf(webSource("web-a", listOf(WebSourceChannel("线路1", webA1))))),
            TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE),
            strings,
        )
        assertEquals(listOf("web:web-a", "web:web-b", TvSourceRailKeys.EMPTY_SOURCES), content.sources.map { it.id })
        val paused = content.sources.single { it.id == "web:web-b" }
        assertEquals(TvSourceRowIcon.Pause, paused.trailingIcon)
        assertEquals("", paused.trailing)
        assertFalse(paused.dimmed)
        assertNull(content.sources.single { it.id == "web:web-a" }.trailingIcon)
        // 组里有结果: 胶囊照旧写条数
        val group = content.rail.single { it.id == TvSourceRailKeys.GROUP_ONLINE }
        assertEquals("1", group.trailing)
        assertNull(group.trailingIcon)
    }

    @Test
    fun `a group with only paused sources shows a pause icon and the source offers to resume it`() {
        val sources = MediaSourceResultListPresentation(
            listOf(
                sourceResult("web-a", MediaSourceKind.WEB, MediaSourceFetchState.Paused(1)),
                sourceResult("web-b", MediaSourceKind.WEB, MediaSourceFetchState.Paused(1)),
            ),
        )
        val content = buildTvSourcePanel(
            input(sources = sources, webSources = emptyList()),
            TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE),
            strings,
        )
        val group = content.rail.single { it.id == TvSourceRailKeys.GROUP_ONLINE }
        assertEquals(TvSourceRowIcon.Pause, group.trailingIcon)
        assertEquals("", group.trailing)
        assertEquals(listOf("web:web-a", "web:web-b"), content.sources.map { it.id })
        // 右边不说没有找到资源, 给一行只放开这一个源
        val resume = content.right.rows.single()
        assertEquals("resumeSource", resume.title)
        assertEquals("pausedHint", resume.meta)
        assertEquals(TvSourceAction.RestartSource("web-a"), resume.action)
    }

    @Test
    fun `web sources group by type in a fixed order`() {
        val ids = listOf("rss", "rule", "drive", "selector", "share", "jelly", "api", "json")
        val input = input(
            sources = MediaSourceResultListPresentation(ids.map { sourceResult(it, MediaSourceKind.WEB) }),
            webSources = emptyList(),
        ).copy(
            sourceTypes = mapOf(
                "rss" to TvSourceType("rss", "RSS", 0),
                "rule" to TvSourceType("rule", "Rule", 1),
                "drive" to TvSourceType("cloud-drive", "Drive", 2),
                "selector" to TvSourceType("web-selector", "Selector", 3),
                "share" to TvSourceType("cloud-drive-share-search", "Share", 4),
                "jelly" to TvSourceType("jellyfin", "Jellyfin", 5),
                "api" to TvSourceType("direct-api", "API", 6),
                "json" to TvSourceType("json-api", "JSON API", 7),
            ),
        )
        val content = buildTvSourcePanel(input, TvSourceNav(), strings)
        val groups = content.rail.filter { it.id.startsWith(TvSourceRailKeys.GROUP_PREFIX) }
        assertEquals(
            listOf(TvSourceRailKeys.GROUP_DRIVE, TvSourceRailKeys.GROUP_ONLINE, TvSourceRailKeys.GROUP_DIRECT, "group:jellyfin"),
            groups.map { it.id },
        )
        assertEquals(listOf("groupDrive", "groupOnline", "groupDirect", "Jellyfin"), groups.map { it.title })
        assertEquals(TvSourceRowIcon.Drive, groups.first().icon)
        // 组里按数据源设置里的顺序
        // (这几个源都查完没结果, 折在「没有结果的 N 个」后面, 展开了按顺序列)
        fun ids(group: String) = buildTvSourcePanel(input, TvSourceNav(railKey = group, showEmptySources = true), strings)
            .sources.map { it.id }.filter { it != TvSourceRailKeys.EMPTY_SOURCES }
        assertEquals(listOf("web:rule", "web:selector", "web:json"), ids(TvSourceRailKeys.GROUP_ONLINE))
        assertEquals(listOf("web:rss", "web:api"), ids(TvSourceRailKeys.GROUP_DIRECT))
        assertEquals(TvSourceRailKeys.GROUP_DRIVE, content.sourceGroups["web:share"])
    }

    @Test
    fun `the source just resumed stays in place when it ends without results and others fold`() {
        val input = input().copy(
            sourceTypes = mapOf(
                "web-b" to TvSourceType("rule", "Rule", 0),
                "web-a" to TvSourceType("rule", "Rule", 1),
                "web-z" to TvSourceType("rule", "Rule", 2),
            ),
            sources = MediaSourceResultListPresentation(sources.list + sourceResult("web-z", MediaSourceKind.WEB)),
        )
        // 刚按了「继续查」的 web-z 查完 0 条 (焦点多半在它上面): 留在原处调暗, 不折, 不在焦点底下抽走
        val selected = buildTvSourcePanel(
            input,
            TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE, sourceKey = "web:web-z", keepInPlace = "web:web-z"),
            strings,
        )
        assertEquals(listOf("web:web-b", "web:web-a", "web:web-z"), selected.sources.map { it.id })
        assertEquals("web:web-z", selected.sourceKey)
        assertTrue(selected.sources.last().dimmed)
        // 换到别的源: 它折进最后一行
        val moved = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE, sourceKey = "web:web-a"), strings)
        assertEquals(listOf("web:web-b", "web:web-a", TvSourceRailKeys.EMPTY_SOURCES), moved.sources.map { it.id })
        // 只是选中 (在展开的那段里走到它上面再收起): 照常折, 不挪到上面; 它在源那一栏里看不见, 右边也空着
        val plain = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE, sourceKey = "web:web-z"), strings)
        assertEquals(listOf("web:web-b", "web:web-a", TvSourceRailKeys.EMPTY_SOURCES), plain.sources.map { it.id })
        assertTrue(plain.right.rows.isEmpty())
        assertTrue(selected.right.rows.isNotEmpty())
        // 展开着时选中没结果的: 照旧排在下面那段, 不挪上去 (在那段里上下走时不来回跳)
        val expanded = buildTvSourcePanel(
            input,
            TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE, sourceKey = "web:web-z", showEmptySources = true),
            strings,
        )
        assertEquals(listOf("web:web-b", "web:web-a", TvSourceRailKeys.EMPTY_SOURCES, "web:web-z"), expanded.sources.map { it.id })
    }

    @Test
    fun `sources without results fold into the last row dimmed and do not count`() {
        val input = input().copy(
            sourceTypes = mapOf(
                "web-b" to TvSourceType("rule", "Rule", 0),
                "web-a" to TvSourceType("rule", "Rule", 1),
                "web-z" to TvSourceType("rule", "Rule", 2),
            ),
            sources = MediaSourceResultListPresentation(sources.list + sourceResult("web-z", MediaSourceKind.WEB)),
        )
        val content = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE), strings)
        // 有结果的按设置里的顺序; 查完 0 条的折成最后一行, 确定展开, 展开后跟在它后面, 调暗写「—」
        assertEquals(listOf("web:web-b", "web:web-a", TvSourceRailKeys.EMPTY_SOURCES), content.sources.map { it.id })
        val toggle = content.sources.last()
        assertEquals("emptySources 1", toggle.title)
        assertEquals(TvSourceAction.ToggleEmptySources, toggle.action)
        val expanded = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE, showEmptySources = true), strings)
        assertEquals(listOf("web:web-b", "web:web-a", TvSourceRailKeys.EMPTY_SOURCES, "web:web-z"), expanded.sources.map { it.id })
        assertEquals("hideEmptySources 1", expanded.sources[2].title)
        val empty = expanded.sources.last()
        assertEquals("—", empty.trailing)
        assertTrue(empty.dimmed)
        assertFalse(content.sources.first().dimmed)
        assertEquals("2", content.rail.single { it.id == TvSourceRailKeys.GROUP_ONLINE }.trailing)
        // 落在源那一栏第一行; 没结果的没有能选的行
        assertEquals("web:web-b", content.sourceKey)
        assertEquals(setOf("web:web-b", "web:web-a"), content.enterableSources)
        // 展开着选中它: 右边写没有找到资源
        val pane = buildTvSourcePanel(
            input,
            TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE, sourceKey = "web:web-z", showEmptySources = true),
            strings,
        )
        assertEquals(listOf("noResult"), pane.right.rows.map { it.title })
    }

    @Test
    fun `group pill spins while its sources are still searching without results`() {
        val loading = webSource("web-a", emptyList()).copy(isLoading = true)
        val content = buildTvSourcePanel(input(webSources = listOf(loading)), TvSourceNav(), strings)
        val pill = content.rail.single { it.id == TvSourceRailKeys.GROUP_ONLINE }
        assertTrue(pill.loading)
        assertEquals("", pill.trailing)
    }

    @Test
    fun `a group with a single source has no source column`() {
        val input = input().copy(sourceTypes = mapOf("web-a" to TvSourceType("rss", "RSS", 0), "web-b" to TvSourceType("rule", "Rule", 1)))
        val content = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.GROUP_DIRECT), strings)
        assertEquals(TvSourceRailKeys.GROUP_DIRECT, content.railKey)
        assertTrue(content.sources.isEmpty())
        assertEquals("web:web-a", content.sourceKey)
        assertEquals("web:web-a", content.right.key)
        assertEquals(listOf("线路1", "线路2"), content.right.rows.map { it.title })
    }

    @Test
    fun `source landing in a group prefers the choice then last visited then results even in the playing group`() {
        val input = input().copy(
            sourceTypes = mapOf(
                "web-z" to TvSourceType("rule", "Rule", 0),
                "web-a" to TvSourceType("rule", "Rule", 1),
                "web-b" to TvSourceType("rule", "Rule", 2),
            ),
            sources = MediaSourceResultListPresentation(sources.list + sourceResult("web-z", MediaSourceKind.WEB)),
        )
        val group = TvSourceRailKeys.GROUP_ONLINE
        fun land(nav: TvSourceNav, selected: DefaultMedia? = null) =
            buildTvSourcePanel(input.copy(selector = input.selector.copy(selected = selected)), nav, strings).sourceKey
        // 没结果的排在前面也跳过
        assertEquals("web:web-a", land(TvSourceNav(railKey = group)))
        assertEquals("web:web-b", land(TvSourceNav(railKey = group, groupSources = mapOf(group to "web:web-b"))))
        // 在播的组也不先落正在播的源 (回在播的那一条按表头的「正在播放」): 上次停的 → 源那一栏第一行
        assertEquals("web:web-a", land(TvSourceNav(railKey = group, groupSources = mapOf(group to "web:web-a")), selected = webB1))
        assertEquals("web:web-a", land(TvSourceNav(railKey = group), selected = webB1))
        // 第一行还在查 (没结果, 但不折): 也落它, 不跳过
        val loadingFirst = input.copy(
            sources = MediaSourceResultListPresentation(
                sources.list + sourceResult("web-z", MediaSourceKind.WEB, MediaSourceFetchState.Working),
            ),
            selector = input.selector.copy(webSources = input.selector.webSources + webSource("web-z", emptyList()).copy(isLoading = true)),
        )
        val loadingContent = buildTvSourcePanel(loadingFirst, TvSourceNav(railKey = group), strings)
        assertEquals("web:web-z", loadingContent.sources.first().id)
        assertEquals("web:web-z", loadingContent.sourceKey)
        assertEquals("web:web-z", land(TvSourceNav(railKey = group, sourceKey = "web:web-z"), selected = webB1))
        // 落到一个源 (落回上次停的源、详情换到别的源): 落它所在的组与它
        val content = buildTvSourcePanel(input, TvSourceNav(railKey = "web:web-b"), strings)
        assertEquals(group, content.railKey)
        assertEquals("web:web-b", content.sourceKey)
    }

    @Test
    fun `the settings gear is the last header command when the host can open settings`() {
        assertTrue(buildTvSourcePanel(input(), TvSourceNav(), strings).rail.none { it.id == TvSourceRailKeys.ACTION_SETTINGS })
        val rail = buildTvSourcePanel(input().copy(canOpenSettings = true), TvSourceNav(), strings).rail
        val settings = rail.last()
        assertEquals(TvSourceRailKeys.ACTION_SETTINGS, settings.id)
        assertEquals(TvSourceAction.OpenSettings, settings.action)
        assertEquals(TvSourceRowIcon.Settings, settings.icon)
        assertTrue(isCompactCommand(settings.id))
    }

    @Test
    fun `downloads entry comes first next to BT and its branch lists the caches of this episode`() {
        val downloads = listOf(
            TvSourceCacheItem("c1", btDmhy, percent = 100, finished = true),
            TvSourceCacheItem("c2", btOther, percent = 42, finished = false),
        )
        val input = input().copy(canOpenDownloads = true, downloads = downloads)
        val content = buildTvSourcePanel(input, TvSourceNav(), strings)
        assertEquals(listOf(TvSourceRailKeys.DOWNLOADS, TvSourceRailKeys.BT, TvSourceRailKeys.GROUP_ONLINE), content.rail.drop(1).take(3).map { it.id })
        assertEquals("2", content.rail[1].trailing)
        assertTrue(content.rail[3].dividerAbove)
        assertTrue(buildTvSourcePanel(input(), TvSourceNav(), strings).rail.none { it.id == TvSourceRailKeys.DOWNLOADS })

        val branch = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.DOWNLOADS), strings).right
        assertEquals(listOf("option:downloads-open", "dl:c1", "dl:c2"), branch.rows.map { it.id })
        assertEquals(TvSourceAction.OpenDownloads, branch.rows[0].action)
        assertTrue(branch.rows[2].meta.startsWith("caching 42%"))
        // 选择器里没有对应的本地缓存资源 (或还没下完): 确定开详情
        assertIs<TvSourceAction.ShowDetails>(branch.rows[1].action)

        // 没有缓存: 正在播在线源且开着边下边播时多说一句
        val empty = input().copy(canOpenDownloads = true)
        fun emptyText(input: TvSourcePanelInput) =
            buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.DOWNLOADS), strings).right.rows.last().title
        assertEquals("downloadsEmpty", emptyText(empty))
        assertEquals("downloadsEmpty", emptyText(empty.copy(playbackDiskCache = true)))
        assertEquals("downloadsEmptyDiskCache", emptyText(input(selected = webA1).copy(canOpenDownloads = true, playbackDiskCache = true)))
    }

    private val localFile = media("l1", "local-a", kind = MediaSourceKind.WEB, title = "[Test] 孤独摇滚 - 05 [1080p].mp4")

    /** 一个本地文件夹源 (local-a, 类型是网盘引擎但标了 local) 加在默认的源后面, 能去缓存页, 这一集一条下完、一条在下. */
    private fun localInput(): TvSourcePanelInput = input(
        sources = MediaSourceResultListPresentation(sources.list + sourceResult("local-a", MediaSourceKind.WEB)),
        webSources = webSources + webSource("local-a", listOf(WebSourceChannel("LocalTest", localFile))),
    ).copy(
        canOpenDownloads = true,
        downloads = listOf(
            TvSourceCacheItem("c1", btDmhy, percent = 100, finished = true),
            TvSourceCacheItem("c2", btOther, percent = 42, finished = false),
        ),
        sourceTypes = mapOf("local-a" to TvSourceType("cloud-drive", "Drive", 0, local = true)),
    )

    @Test
    fun `local sources form the first group and the downloads entry moves into it`() {
        val input = localInput()
        val content = buildTvSourcePanel(input, TvSourceNav(), strings)
        // 「本地」取代「下载」排第一个, 后面照旧是 BT 与在线源的组; 胶囊数 = 下完的缓存 + 有结果的本地源
        assertEquals(listOf(TvSourceRailKeys.GROUP_LOCAL, TvSourceRailKeys.BT, TvSourceRailKeys.GROUP_ONLINE), content.rail.drop(1).take(3).map { it.id })
        assertTrue(content.rail.none { it.id == TvSourceRailKeys.DOWNLOADS })
        val local = content.rail[1]
        assertEquals("groupLocal", local.title)
        assertEquals(TvSourceRowIcon.Local, local.icon)
        assertTrue(local.trailing.startsWith("2"), local.trailing)
        assertTrue(content.rail[3].dividerAbove)
        // 不按类型: 网盘引擎的本地源不在「网盘」组
        assertEquals(TvSourceRailKeys.GROUP_LOCAL, content.sourceGroups["web:local-a"])
        assertTrue(content.rail.none { it.id == TvSourceRailKeys.GROUP_DRIVE })

        // 进这一组: 源那一栏「下载」排第一, 默认落在它上面, 右边是这一集的缓存
        val inGroup = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.GROUP_LOCAL), strings)
        assertEquals(listOf(TvSourceRailKeys.DOWNLOADS, "web:local-a"), inGroup.sources.map { it.id })
        assertEquals(TvSourceRailKeys.DOWNLOADS, inGroup.sourceKey)
        assertEquals(listOf("option:downloads-open", "dl:c1", "dl:c2"), inGroup.right.rows.map { it.id })
        assertEquals(TvSourceRailKeys.GROUP_LOCAL, inGroup.sourceGroups[TvSourceRailKeys.DOWNLOADS])
        assertTrue(TvSourceRailKeys.DOWNLOADS in inGroup.enterableSources)
        // 本地源那一行: 右边是它的文件; 记着上次停在它上面也落回它
        val onLocal = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.GROUP_LOCAL, sourceKey = "web:local-a"), strings)
        assertEquals("web:local-a", onLocal.sourceKey)
        assertEquals("web:local-a", onLocal.right.key)
        val remembered = TvSourceNav(railKey = TvSourceRailKeys.GROUP_LOCAL, groupSources = mapOf(TvSourceRailKeys.GROUP_LOCAL to "web:local-a"))
        assertEquals("web:local-a", buildTvSourcePanel(input, remembered, strings).sourceKey)
        // 要落到「下载」(详情弹窗跟过去): 落「本地」组的「下载」
        val toDownloads = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.DOWNLOADS), strings)
        assertEquals(TvSourceRailKeys.GROUP_LOCAL, toDownloads.railKey)
        assertEquals(TvSourceRailKeys.DOWNLOADS, toDownloads.sourceKey)

        // 右边开的详情左右键: 「本地」组先走这一集的缓存, 再走本地源的文件
        val sequence = buildTvSourceDetailsSequence(input, strings, inGroup, inRail = false)
        assertEquals(listOf(TvSourceRailKeys.DOWNLOADS, "web:local-a"), sequence.entries.map { it.railKey }.distinct().take(2))
    }

    @Test
    fun `without the downloads entry the local group is an ordinary group at the front`() {
        // 缓存页自己的面板不能去缓存页: 「本地」只有一个源, 不出源那一栏
        val content = buildTvSourcePanel(localInput().copy(canOpenDownloads = false), TvSourceNav(railKey = TvSourceRailKeys.GROUP_LOCAL), strings)
        assertEquals(listOf(TvSourceRailKeys.GROUP_LOCAL, TvSourceRailKeys.BT), content.rail.drop(1).take(2).map { it.id })
        assertTrue(content.sources.isEmpty())
        assertEquals("web:local-a", content.sourceKey)
        assertEquals(null, content.sourceGroups[TvSourceRailKeys.DOWNLOADS])
    }

    @Test
    fun `lands on the source of the playing web line and the line is highlighted`() {
        val content = buildTvSourcePanel(input(selected = webA2), TvSourceNav(), strings)
        assertEquals(TvSourceRailKeys.GROUP_ONLINE, content.railKey)
        assertEquals("web:web-a", content.sourceKey)
        val playing = content.right.rows.single { it.selected }
        // 进列表落第一行, 不先落在播那一行 (回在播的那一条按表头的「正在播放」)
        assertNull(content.right.focusId)
        assertEquals("线路2", playing.title)
        // 只有资源那一行画正在播放的图标; 源与组不换图标 (在播的看表头的「正在播放」)
        assertEquals(TvSourceRowIcon.Playing, playing.icon)
        assertEquals(TvSourceRowIcon.Source, content.sources.first { it.id == "web:web-a" }.icon)
        assertEquals(TvSourceRowIcon.Source, content.rail.first { it.id == TvSourceRailKeys.GROUP_ONLINE }.icon)
        // 正在播放的资源、源、组都标成 playing (视图不给它们描边, 描边只标显示的是哪一项)
        assertTrue(playing.playing)
        assertTrue(content.sources.first { it.id == "web:web-a" }.playing)
        assertTrue(content.rail.first { it.id == TvSourceRailKeys.GROUP_ONLINE }.playing)
        assertFalse(content.sources.first { it.id == "web:web-b" }.playing)
    }

    @Test
    fun `now playing names the playing entry and points at its row`() {
        assertNull(buildTvSourcePanel(input(), TvSourceNav(), strings).nowPlaying)

        // 在线源: 眼前是另一组 (BT) 时也找得到那一行
        val web = assertNotNull(buildTvSourcePanel(input(selected = webA2), TvSourceNav(railKey = TvSourceRailKeys.BT), strings).nowPlaying)
        assertEquals(TvSourceFocusPosition(TvSourceRailKeys.GROUP_ONLINE, "web:web-a", "line:1:线路2"), web.target)
        assertEquals("name-web-a · 线路2", web.row.title)
        assertEquals(TvSourceRowIcon.Playing, web.row.icon)
        assertEquals("1080P", web.row.trailing)
        assertFalse(web.failed)
        // 播不了了: 右端红字写着, 打开面板落胶囊行
        val failed = assertNotNull(buildTvSourcePanel(input(selected = webA2).copy(playback = TvSourcePlayback.Failed), TvSourceNav(), strings).nowPlaying)
        assertEquals("playbackFailed", failed.row.trailing)
        assertEquals(TvSourceAccent.Error, failed.row.accent)
        assertTrue(failed.failed)
        val buffering = assertNotNull(buildTvSourcePanel(input(selected = webA2).copy(playback = TvSourcePlayback.Buffering), TvSourceNav(), strings).nowPlaying)
        assertEquals("playbackBuffering", buffering.row.trailing)
        // 长按只开详情, 背后的焦点不跳过去
        assertEquals(TvSourceAction.ShowDetails("line:1:线路2", inRail = false, railKey = "web:web-a", followFocus = false), web.row.longAction)

        // BT: 合并后的那一行
        val bt = bt(listOf(BtRow(btDmhy, null, isSelected = true), BtRow(btOther, null, false)))
        val torrent = assertNotNull(buildTvSourcePanel(input(selected = btDmhy, bt = bt), TvSourceNav(), strings).nowPlaying)
        assertEquals(TvSourceFocusPosition(TvSourceRailKeys.BT, null, "bt:" + btGroupKey(btDmhy)), torrent.target)
        assertTrue(torrent.row.title.startsWith("name-dmhy · "), torrent.row.title)
    }

    @Test
    fun `lands on BT when the playing resource is a torrent`() {
        val bt = bt(listOf(BtRow(btDmhy, null, isSelected = true), BtRow(btOther, null, false)))
        val content = buildTvSourcePanel(input(selected = btDmhy, bt = bt), TvSourceNav(), strings)
        assertEquals(TvSourceRailKeys.BT, content.railKey)
        assertNull(content.right.focusId)
        assertTrue(content.right.wide)
    }

    @Test
    fun `without a playing resource lands on the first rail item whether or not it has results`() {
        // 胶囊行第一项是 BT (表头的「筛选」不算): 不按有没有结果往后跳
        assertEquals(TvSourceRailKeys.BT, buildTvSourcePanel(input(), TvSourceNav(), strings).railKey)
        val noBt = input(bt = bt(emptyList()), sources = MediaSourceResultListPresentation(sources.webSources))
        assertEquals(TvSourceRailKeys.GROUP_ONLINE, buildTvSourcePanel(noBt, TvSourceNav(), strings).railKey)
    }

    @Test
    fun `user choice of rail item wins over the playing resource`() {
        val content = buildTvSourcePanel(input(selected = webA1), TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE, sourceKey = "web:web-b"), strings)
        assertEquals("web:web-b", content.sourceKey)
        assertEquals(listOf("线路1"), content.right.rows.map { it.title })
    }

    @Test
    fun `BT rows merge the same torrent from different sites only`() {
        val content = buildTvSourcePanel(input(), TvSourceNav(railKey = TvSourceRailKeys.BT), strings)
        val rows = content.right.rows.filter { it.style == TvSourceRowStyle.Resource }
        assertEquals(2, rows.size)
        assertTrue(rows[0].meta.contains("sourcesCount:2"), rows[0].meta)
        assertEquals(btOther.originalTitle, rows[1].title)
        // 顶上: 仅本集、字幕组、来源 (分辨率 / 字幕在表头的「筛选」里)
        assertEquals(listOf("pill:episode", "pill:Alliance", "pill:Source"), content.right.pills.map { it.id })
    }

    @Test
    fun `BT rows with the same title but different torrents stay apart`() {
        // 重新压制后用原标题再发的另一个种子; 认不出种子的也不合并
        val reencoded = media("bt4", "nyaa", download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:752d71fa50fbdedb3c044630e9a33cf7b99c6480"))
        val unknown = media("bt5", "nyaa")
        val groups = groupBtRows(listOf(btDmhy, btMikan, reencoded, unknown).map { BtRow(it, null, false) })
        assertEquals(listOf(listOf("bt1", "bt2"), listOf("bt4"), listOf("bt5")), groups.map { group -> group.rows.map { it.media.mediaId } })
    }

    @Test
    fun `excluded rows hide behind a toggle`() {
        val excluded = BtRow(btOther, BtRowExclusion.Reason(MediaExclusionReason.MediaWithoutSubtitle), false)
        val input = input(bt = bt(listOf(BtRow(btDmhy, null, false)), listOf(excluded)))
        val hidden = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.BT), strings)
        assertEquals(listOf("bt:" + btGroupKey(btDmhy), "bt:excluded-toggle"), hidden.right.rows.map { it.id })
        val shown = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.BT, showExcluded = true), strings)
        val excludedRow = shown.right.rows.last()
        assertTrue(excludedRow.dimmed)
        assertEquals(TvSourceAccent.Error, excludedRow.metaAccent)
        assertTrue(excludedRow.meta.startsWith("reasonNoSubtitle"), excludedRow.meta)
    }

    @Test
    fun `rows hidden by the filter get their own count row that opens the filter`() {
        val belowFilter = BtRow(btMikan, BtRowExclusion.BelowPreference, false)
        val excluded = BtRow(btOther, BtRowExclusion.Reason(MediaExclusionReason.MediaWithoutSubtitle), false)
        val input = input(bt = bt(emptyList(), listOf(belowFilter, excluded), resolution = "1080P"))
        val collapsed = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.BT), strings)
        assertEquals(listOf("bt:hidden-by-filter", "bt:excluded-toggle"), collapsed.right.rows.map { it.id })
        val (hiddenRow, toggle) = collapsed.right.rows
        assertEquals("hiddenByFilter 1", hiddenRow.title)
        assertEquals(TvSourceAction.OpenFilter, hiddenRow.action)
        assertEquals("showExcluded 1", toggle.title)

        // 展开的只有真被排除的那条
        val expanded = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.BT, showExcluded = true), strings)
        assertEquals(
            listOf("bt:hidden-by-filter", "bt:excluded-toggle", "bt:" + btGroupKey(btOther)),
            expanded.right.rows.map { it.id },
        )
    }

    @Test
    fun `bt rows stay listed but marked below preference when the episode lacks the filter value`() {
        val base = input(bt = bt(listOf(BtRow(btDmhy, null, false))))
        fun withResolution(resolution: String) =
            base.copy(selector = base.selector.copy(resolution = MediaPreferenceItemState.Presentation(listOf("4K", "1080P"), resolution)))

        // 筛 4K, 这一集的 BT 只有 1080P: 照样在主列表, 不调暗, 红字写「低于偏好」
        val row = buildTvSourcePanel(withResolution("4K"), TvSourceNav(railKey = TvSourceRailKeys.BT), strings).right.rows.single()
        assertTrue(row.meta.startsWith("reasonBelowPreference"), row.meta)
        assertEquals(TvSourceAccent.Error, row.metaAccent)
        assertFalse(row.dimmed)

        val matching = buildTvSourcePanel(withResolution("1080P"), TvSourceNav(railKey = TvSourceRailKeys.BT), strings).right.rows.single()
        assertEquals(TvSourceAccent.None, matching.metaAccent)
    }

    @Test
    fun `drive sources that need a login stay in their group and open the login`() {
        val drive = sourceResult("drive", MediaSourceKind.WEB, MediaSourceFetchState.Failed(CloudDriveAuthException(driveId = "quark"), 1))
        val input = input(
            sources = MediaSourceResultListPresentation(sources.list + drive),
            webSources = listOf(webSource("drive", emptyList(), isError = true)) + webSources,
        ).copy(
            sourceTypes = mapOf(
                "drive" to TvSourceType("cloud-drive", "Drive", 0),
                "web-b" to TvSourceType("cloud-drive-added-shares", "Shares", 1),
                "web-a" to TvSourceType("rule", "Rule", 2),
            ),
        )
        val group = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.GROUP_DRIVE), strings)
        assertEquals(listOf(TvSourceRailKeys.web("drive"), TvSourceRailKeys.web("web-b")), group.sources.map { it.id })
        // 进这一组落源那一栏第一行, 没登录的也不跳过 (右边是登录那一行)
        assertEquals(TvSourceRailKeys.web("drive"), group.sourceKey)
        val row = group.sources.first()
        assertEquals("driveNotLoggedIn", row.trailing)
        assertFalse(row.dimmed)
        assertEquals(TvSourceAction.LoginDrive("quark"), row.action)

        // 不算「查询失败」; 右栏只有登录这一行
        val failed = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.FAILED), strings).right.rows
        assertEquals(listOf("failed:web-c"), failed.map { it.id })
        val pane = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.web("drive")), strings).right.rows
        assertEquals(TvSourceAction.LoginDrive("quark"), pane.single().action)
    }

    @Test
    fun `a source the site blocked says until when instead of retry`() {
        val blocked = sourceResult(
            "web-c", MediaSourceKind.WEB,
            MediaSourceFetchState.Failed(SiteIpBlockedException("blocked.example.com", untilMillis = 0), 1),
        )
        val input = input(sources = MediaSourceResultListPresentation(sources.list.filter { it.instanceId != "web-c" } + blocked))
        val row = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.FAILED), strings).right.rows.single()
        assertTrue(row.meta.startsWith("siteIpBlocked "), row.meta)
        assertTrue(row.details!!.fields.any { it.value.startsWith("siteIpBlocked ") })
    }

    @Test
    fun `rows hidden only by the alliance filter open the alliance pill and others open the filter dialog`() {
        val belowFilter = BtRow(btMikan, BtRowExclusion.BelowPreference, false)
        val bt = bt(listOf(BtRow(btDmhy, null, false)), listOf(belowFilter)).copy(alliance = "ANi")
        val rows = buildTvSourcePanel(input(bt = bt), TvSourceNav(railKey = TvSourceRailKeys.BT), strings).right.rows
        val hiddenRow = rows.single { it.id == "bt:hidden-by-filter" }
        assertTrue(hiddenRow.dividerAbove)
        assertEquals(TvSourceAction.Open(TvSourceDrill.Filter(TvSourceFilter.Alliance)), hiddenRow.action)
        // 分辨率也在筛: 去筛选弹窗
        val withResolution = buildTvSourcePanel(input(bt = bt.copy(resolution = "1080P")), TvSourceNav(railKey = TvSourceRailKeys.BT), strings)
        assertEquals(TvSourceAction.OpenFilter, withResolution.right.rows.single { it.id == "bt:hidden-by-filter" }.action)
    }

    @Test
    fun `web results that are not perfect matches hide behind a toggle`() {
        val fuzzy = media("a3", "web-a", kind = MediaSourceKind.WEB, title = "孤獨搖滾")
        val renamed = media("a4", "web-a", kind = MediaSourceKind.WEB, title = "别的番")
        val otherEpisode = media("a5", "web-a", kind = MediaSourceKind.WEB, title = "孤独摇滚 06")
        val base = input()
        val input = base.copy(
            selector = base.selector.copy(
                filteredCandidates = listOf(
                    MaybeExcludedMedia.Included(webA1, TestMatchMetadata),
                    MaybeExcludedMedia.Included(fuzzy, TestMatchMetadata.copy(subjectMatchKind = MatchMetadata.SubjectMatchKind.FUZZY)),
                    MaybeExcludedMedia.Excluded(renamed, MediaExclusionReason.SubjectNameMismatch),
                    MaybeExcludedMedia.Excluded(otherEpisode, MediaExclusionReason.EpisodeMismatch(null)),
                ),
            ),
        )
        val key = TvSourceRailKeys.web("web-a")
        val hidden = buildTvSourcePanel(input, TvSourceNav(railKey = key), strings)
        // 属于别的集的不算 (同旧版详细模式)
        assertEquals("showOthers 2", hidden.right.rows.last().title)
        assertEquals(TvSourceAction.ToggleExcluded, hidden.right.rows.last().action)

        val shown = buildTvSourcePanel(input, TvSourceNav(railKey = key, showExcluded = true), strings)
        val (fuzzyRow, renamedRow) = shown.right.rows.takeLast(2)
        assertEquals("hideOthers 2", shown.right.rows[shown.right.rows.size - 3].title)
        assertEquals("other:a3", fuzzyRow.id)
        assertTrue(fuzzyRow.meta.startsWith("reasonFuzzyTitle"), fuzzyRow.meta)
        assertEquals(TvSourceAccent.None, fuzzyRow.metaAccent)
        assertEquals(TvSourceAction.Play(fuzzy), fuzzyRow.action)
        assertTrue(renamedRow.meta.startsWith("reasonTitleMismatch"), renamedRow.meta)
        assertEquals(TvSourceAccent.Error, renamedRow.metaAccent)
        assertTrue(renamedRow.dimmed)
    }

    @Test
    fun `web source whose every result is hidden still shows up`() {
        val fuzzy = media("d1", "web-d", kind = MediaSourceKind.WEB, title = "孤獨搖滾")
        val base = input(sources = MediaSourceResultListPresentation(sources.list + sourceResult("web-d", MediaSourceKind.WEB)))
        val input = base.copy(
            selector = base.selector.copy(
                filteredCandidates = listOf(
                    MaybeExcludedMedia.Included(
                        fuzzy,
                        TestMatchMetadata.copy(
                            subjectMatchKind = MatchMetadata.SubjectMatchKind.FUZZY,
                            episodeMatchKind = MatchMetadata.EpisodeMatchKind.NONE,
                        ),
                    ),
                ),
            ),
        )
        val key = TvSourceRailKeys.web("web-d")
        val content = buildTvSourcePanel(input, TvSourceNav(railKey = key), strings)
        val railRow = content.sources.single { it.id == key }
        assertEquals("others 1", railRow.trailing)
        assertTrue(railRow.dimmed)
        // 一条对得上的都没有: 不用开关, 直接列出来
        assertEquals(listOf("webNoExact", fuzzy.originalTitle), content.right.rows.map { it.title })
        assertEquals("reasonFuzzyTitle、reasonEpisodeUnknown", content.right.rows.last().meta.substringBefore(" · "))

        // 选中的是它时源那一栏那一行标成正在播放
        val playing = buildTvSourcePanel(input.copy(selector = input.selector.copy(selected = fuzzy)), TvSourceNav(railKey = key), strings)
        assertTrue(playing.sources.single { it.id == key }.selected)
        assertTrue(playing.right.rows.last().selected)
    }

    @Test
    fun `episode ranges render for people not as debug strings`() {
        assertEquals("05", renderEpisodeRange(EpisodeRange.single(EpisodeSort(5)), strings))
        assertEquals("01–12", renderEpisodeRange(EpisodeRange.range(1, 12), strings))
        assertEquals("01、03", renderEpisodeRange(EpisodeRange.range(listOf(EpisodeSort(1), EpisodeSort(3))), strings))
        assertEquals("season 2", renderEpisodeRange(EpisodeRange.season(2), strings))
        assertEquals("wholeSeason", renderEpisodeRange(EpisodeRange.unknownSeason(), strings))
    }

    @Test
    fun `cloud drive like sources list every file like BT`() {
        // 网盘类的源: 线路名写的是数据源名, 选择器按线路去重后只剩 d1 一条
        val d1 = media("d1", "web-d", kind = MediaSourceKind.WEB, title = "孤独摇滚 / 05.mkv", alliance = "name-web-d")
        val d2 = media("d2", "web-d", kind = MediaSourceKind.WEB, title = "孤独摇滚 / [字幕组] 05.mp4", alliance = "name-web-d")
        val d3 = media("d3", "web-d", kind = MediaSourceKind.WEB, title = "孤独摇滚 / 05 生肉.mp4", alliance = "name-web-d")
        val base = input(sources = MediaSourceResultListPresentation(sources.list + sourceResult("web-d", MediaSourceKind.WEB)))
        val input = base.copy(
            selector = base.selector.copy(
                webSources = base.selector.webSources + webSource("web-d", listOf(WebSourceChannel("name-web-d", d1))),
                filteredCandidates = listOf(
                    MaybeExcludedMedia.Included(d1, TestMatchMetadata),
                    MaybeExcludedMedia.Included(d2, TestMatchMetadata),
                    MaybeExcludedMedia.Excluded(d3, MediaExclusionReason.MediaWithoutSubtitle),
                ),
                selected = d2,
            ),
        )
        val key = TvSourceRailKeys.web("web-d")
        val content = buildTvSourcePanel(input, TvSourceNav(railKey = key), strings)
        // 每个文件一行, 标题是文件名; 被排除的直接跟在后面, 调暗、红字写原因
        assertEquals(listOf("file:d1", "file:d2", "file:d3"), content.right.rows.map { it.id })
        assertEquals(d1.originalTitle, content.right.rows[0].title)
        assertTrue(content.right.wide)
        assertTrue(content.right.rows[1].selected)
        val excluded = content.right.rows[2]
        assertTrue(excluded.dimmed)
        assertEquals(TvSourceAccent.Error, excluded.metaAccent)
        assertTrue(excluded.meta.startsWith("reasonNoSubtitle"), excluded.meta)
        // 源那一栏数对得上的文件; 选中的不是去重后剩下的那条, 也标正在播放
        val rail = content.sources.single { it.id == key }
        // 数目后面跟着这个源最高的分辨率
        assertEquals("2 · 1080P", rail.trailing)
        assertTrue(rail.selected)
        // 线路式的源照旧一条线路一行
        val lines = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.web("web-a")), strings)
        assertTrue(lines.right.rows.all { it.id.startsWith("line:") }, lines.right.rows.map { it.id }.toString())
    }

    @Test
    fun `filter at the top of the rail also applies to cloud drive files`() {
        fun file(id: String, resolution: String) = createTestDefaultMedia(
            mediaId = id,
            mediaSourceId = "web-d",
            originalUrl = "https://example.com/$id",
            download = ResourceLocation.HttpStreamingFile("https://example.com/$id.mp4"),
            originalTitle = "孤独摇滚 / $id.mkv",
            publishedTime = 0,
            properties = createTestMediaProperties(alliance = "name-web-d", resolution = resolution, subtitleLanguageIds = listOf("CHS")),
            episodeRange = null,
            location = MediaSourceLocation.Online,
            kind = MediaSourceKind.WEB,
        )
        val uhd = file("uhd", "4K")
        val fhd = file("fhd", "1080P")
        val base = input(sources = MediaSourceResultListPresentation(sources.list + sourceResult("web-d", MediaSourceKind.WEB)))
        fun withResolution(resolution: String?) = base.copy(
            selector = base.selector.copy(
                webSources = base.selector.webSources + webSource("web-d", listOf(WebSourceChannel("name-web-d", uhd))),
                filteredCandidates = listOf(MaybeExcludedMedia.Included(uhd, TestMatchMetadata), MaybeExcludedMedia.Included(fhd, TestMatchMetadata)),
                resolution = MediaPreferenceItemState.Presentation(listOf("4K", "1080P"), resolution),
            ),
        )
        val key = TvSourceRailKeys.web("web-d")

        // 「筛选」排在 rail 最前面 (视图放进表头), 与后面隔开; 没选时右端不写
        val none = buildTvSourcePanel(withResolution(null), TvSourceNav(railKey = key), strings)
        assertEquals(TvSourceRailKeys.FILTER, none.rail.first().id)
        assertEquals("", none.rail.first().trailing)
        assertTrue(none.rail[1].dividerAbove)
        assertEquals(listOf("file:uhd", "file:fhd"), none.right.rows.map { it.id })

        // 选了 1080P: 4K 的调暗排到后面写「低于偏好」, 胶囊上只数符合的
        val filtered = buildTvSourcePanel(withResolution("1080P"), TvSourceNav(railKey = key), strings)
        assertEquals("1080P", filtered.rail.first().trailing)
        assertEquals(listOf("file:fhd", "file:uhd"), filtered.right.rows.map { it.id })
        assertTrue(filtered.right.rows[1].dimmed)
        assertTrue(filtered.right.rows[1].meta.startsWith("reasonBelowPreference"), filtered.right.rows[1].meta)
        // 胶囊上只数符合的, 后面跟着这个源里最高的分辨率 (低于偏好的 4K 也算: 让人看得出有)
        assertEquals("1 · 4K", filtered.sources.single { it.id == key }.trailing)

        // 这个源里没有的取值不筛 (偏好随选中的资源变, 可能是别的源里的取值)
        val absent = buildTvSourcePanel(withResolution("720P"), TvSourceNav(railKey = key), strings)
        assertEquals(listOf("file:uhd", "file:fhd"), absent.right.rows.map { it.id })
        assertTrue(absent.right.rows.none { it.dimmed })

        // 筛选弹窗: 分辨率 (BT 与网盘文件里有的, 高的在前) 与字幕两组, 确定就改这部番的偏好; 表头的「筛选」确定打开它
        assertEquals(TvSourceAction.OpenFilter, filtered.rail.first().action)
        val options = filtered.filter
        assertEquals(
            listOf(
                "filter:Resolution:", "filter:Resolution:4K", "filter:Resolution:1080P", "filter:Resolution:720P",
                "filter:Subtitle:", "filter:Subtitle:CHS",
            ),
            options.filter { it.focusable }.map { it.id },
        )
        assertEquals("filter:Resolution:1080P", options.single { it.selected && it.id.startsWith("filter:Resolution:") }.id)
        assertEquals(TvSourceAction.PickFilter(TvSourceFilter.Resolution, "4K"), options.single { it.id == "filter:Resolution:4K" }.action)
        // 弹窗里换勾不当场生效; 关弹窗时只生效与当前选中不同的几项 (换过又换回来的不算, 换成「全部」算)
        assertEquals(mapOf(TvSourceFilter.Resolution to "4K"), changedFilters(options, mapOf(TvSourceFilter.Resolution to "4K")))
        assertEquals(emptyMap(), changedFilters(options, mapOf(TvSourceFilter.Resolution to "1080P")))
        assertEquals(mapOf(TvSourceFilter.Resolution to null), changedFilters(options, mapOf(TvSourceFilter.Resolution to null)))

        // 只有线路式的在线源: 没东西可筛, 不列「筛选」, 也没有弹窗内容
        val linesOnly = input(bt = BtListPresentation.Placeholder, sources = MediaSourceResultListPresentation(sources.list.filter { it.kind == MediaSourceKind.WEB }))
        val linesOnlyPanel = buildTvSourcePanel(linesOnly, TvSourceNav(), strings)
        assertTrue(linesOnlyPanel.rail.none { it.id == TvSourceRailKeys.FILTER })
        assertTrue(linesOnlyPanel.filter.isEmpty())
    }

    @Test
    fun `cache that is not ready opens details instead of playing`() {
        val notReady = BtRow(btDmhy.copy(kind = MediaSourceKind.LocalCache), BtRowExclusion.Reason(MediaExclusionReason.CacheNotReady), false)
        val content = buildTvSourcePanel(
            input(bt = bt(emptyList(), listOf(notReady))),
            TvSourceNav(railKey = TvSourceRailKeys.BT, showExcluded = true),
            strings,
        )
        val row = content.right.rows.last()
        assertEquals(TvSourceAction.ShowDetails(row.id, inRail = false, railKey = TvSourceRailKeys.BT), row.action)
        val details = assertNotNull(row.details)
        // 没准备好的不给「选择」, 本地缓存不给「缓存这一条」, 只剩排除字幕组
        assertNull(details.primary)
        assertEquals(listOf(TvSourceAction.ExcludeAlliance("喵萌奶茶屋")), details.secondary.map { it.action })
        assertEquals("reasonCacheNotReady", details.note)
    }

    @Test
    fun `BT details show the full title every field and the actions`() {
        val content = buildTvSourcePanel(input(), TvSourceNav(railKey = TvSourceRailKeys.BT), strings)
        val details = assertNotNull(content.right.rows.first { it.style == TvSourceRowStyle.Resource }.details)
        assertEquals(btDmhy.originalTitle, details.title)
        val fields = details.fields.associate { it.label to it.value }
        // 几个字的六项总是都在 (排成表格), 缺的写「—」
        assertEquals(
            listOf("labelKind", "resolution", "subtitle", "labelSize", "labelDate", "labelEpisodes"),
            details.fields.filter { it.short }.map { it.label },
        )
        assertEquals("—", fields["labelEpisodes"])
        assertEquals(1, details.fields.single { it.label == "labelLink" }.maxLines)
        assertEquals("kindBt", fields["labelKind"])
        assertEquals("1080P", fields["resolution"])
        assertEquals("喵萌奶茶屋", fields["alliance"])
        assertEquals("name-dmhy、name-mikan", fields["labelSources"])
        assertEquals("https://example.com/bt1", fields["labelLink"])
        assertEquals(TvSourceAction.Play(btDmhy), details.primary?.action)
        assertEquals(
            listOf(TvSourceAction.Cache(btDmhy), TvSourceAction.ExcludeAlliance("喵萌奶茶屋")),
            details.secondary.map { it.action },
        )
    }

    @Test
    fun `details sequence from the right column runs across BT then web sources then failed sources`() {
        val input = input()
        val content = buildTvSourcePanel(input, TvSourceNav(railKey = "web:web-a"), strings)
        val sequence = buildTvSourceDetailsSequence(input, strings, content, inRail = false)
        assertEquals(
            listOf(TvSourceRailKeys.BT, TvSourceRailKeys.BT, "web:web-a", "web:web-a", "web:web-b", TvSourceRailKeys.FAILED),
            sequence.entries.map { it.railKey },
        )
        // 不同在线源的线路 id 会重: 按 railKey 分
        val webBLine = TvSourceAction.ShowDetails("line:0:线路1", inRail = false, railKey = "web:web-b")
        assertEquals(4, sequence.indexOf(webBLine))
        assertEquals(-1, sequence.indexOf(webBLine.copy(inRail = true)))
    }

    @Test
    fun `details sequence from the rail lists the web sources`() {
        val input = input()
        val content = buildTvSourcePanel(input, TvSourceNav(railKey = TvSourceRailKeys.GROUP_ONLINE), strings)
        val sequence = buildTvSourceDetailsSequence(input, strings, content, inRail = true)
        assertEquals(listOf("web:web-a", "web:web-b"), sequence.entries.map { it.rowId })
        assertTrue(sequence.entries.all { it.railKey == null })
    }

    @Test
    fun `web lines and sources have details whose primary action selects`() {
        val content = buildTvSourcePanel(input(selected = webA2), TvSourceNav(), strings)
        val line = assertNotNull(content.right.rows.first { it.title == "线路1" }.details)
        assertEquals("name-web-a · 线路1", line.subtitle)
        assertEquals(TvSourceAction.Play(webA1), line.primary?.action)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.Cache(webA1)), line.secondary.map { it.action })
        // 已经有它的缓存了: 不再给「下载」
        val cachedInput = input(selected = webA2).copy(downloads = listOf(TvSourceCacheItem("c", webA1, percent = 10, finished = false)))
        val cachedLine = assertNotNull(buildTvSourcePanel(cachedInput, TvSourceNav(), strings).right.rows.first { it.title == "线路1" }.details)
        assertTrue(cachedLine.secondary.isEmpty())
        val source = assertNotNull(content.sources.first { it.id == "web:web-a" }.details)
        assertEquals(TvSourceAction.Play(webA1), source.primary?.action)
        assertTrue(source.fields.any { it.label == "labelLines" && it.value.lines().size == 2 })
        // 操作行没有详情
        assertNull(content.rail.first { it.id == TvSourceRailKeys.ACTION_REFRESH }.details)
    }

    @Test
    fun `alliance and source are pills on top of BT and the filter dialog holds only resolution and subtitle`() {
        val bt = bt(listOf(BtRow(btDmhy, null, false))).copy(availableAlliances = listOf("喵萌奶茶屋", "桜都字幕组"), alliance = "喵萌奶茶屋")
        val content = buildTvSourcePanel(input(bt = bt), TvSourceNav(railKey = TvSourceRailKeys.BT), strings)
        // BT 那一栏顶上: 仅本集、字幕组 (选了的写在后面)、来源; 确定进去选
        val alliance = content.right.pills.single { it.id == "pill:Alliance" }
        assertEquals("${strings.alliance} · 喵萌奶茶屋", alliance.title)
        assertTrue(alliance.selected)
        assertEquals(TvSourceAction.Open(TvSourceDrill.Filter(TvSourceFilter.Alliance)), alliance.action)
        assertEquals(listOf("pill:episode", "pill:Alliance", "pill:Source"), content.right.pills.map { it.id })
        // 进去那一层: 全部 + 各个取值, 选中的打勾, 确定就选
        val drilled = buildTvSourcePanel(input(bt = bt), TvSourceNav(railKey = TvSourceRailKeys.BT, drill = TvSourceDrill.Filter(TvSourceFilter.Source)), strings)
        assertEquals(listOf("opt:all", "opt:dmhy", "opt:mikan"), drilled.right.rows.filter { it.id.startsWith("opt:") }.map { it.id })
        assertEquals(TvSourceAction.PickFilter(TvSourceFilter.Source, "mikan"), drilled.right.rows.single { it.id == "opt:mikan" }.action)
        // 筛选弹窗与表头「筛选」的右端只有分辨率 / 字幕
        assertTrue(content.filter.none { it.id.contains("Alliance") || it.id.contains("Source") }, content.filter.map { it.id }.toString())
        assertFalse(content.rail.first { it.id == TvSourceRailKeys.FILTER }.trailing.contains("喵萌奶茶屋"))
    }

    @Test
    fun `failed sources pane retries each source`() {
        val content = buildTvSourcePanel(input(), TvSourceNav(railKey = TvSourceRailKeys.FAILED), strings)
        assertEquals(listOf(TvSourceAction.RestartSource("web-c")), content.right.rows.map { it.action })
    }

    @Test
    fun `rate limit counts down then offers a retry once the countdown runs out`() {
        val limited = webSource("web-a", emptyList()).copy(rateLimitedUntilMillis = 5_000)
        val nav = TvSourceNav(railKey = TvSourceRailKeys.web("web-a"))

        val counting = buildTvSourcePanel(input(webSources = listOf(limited), nowMillis = 0), nav, strings)
        assertEquals("rateLimited 5", counting.sources.first { it.id == TvSourceRailKeys.web("web-a") }.trailing)
        assertEquals("rateLimitedHint 5", counting.right.rows.first().title)

        // 到点那次自动重试也被限流: 不会再自动重试, 改成让用户按重试
        val expired = buildTvSourcePanel(input(webSources = listOf(limited), nowMillis = 5_000), nav, strings)
        assertEquals("rateLimitedExpired", expired.sources.first { it.id == TvSourceRailKeys.web("web-a") }.trailing)
        val retry = expired.right.rows.first()
        assertEquals("rateLimitedExpiredHint", retry.meta)
        assertEquals(TvSourceAction.RestartSource("web-a"), retry.action)
    }

    @Test
    fun `manual browse shows results then episodes as a grid`() {
        val source = ManualBrowseSource("site", "site", MediaSourceInfo("站点"))
        val subject = BrowseSubject(name = "孤独摇滚", url = "https://example.com/s/1")
        val searching = ManualBrowsePresentation.Empty.copy(
            sources = listOf(source),
            selectedSourceId = "site",
            keyword = "孤独摇滚",
            results = ManualLoadState.Success(listOf(subject)),
            isPlaceholder = false,
        )
        val list = buildTvSourcePanel(input(manual = searching), TvSourceNav(railKey = TvSourceRailKeys.MANUAL), strings)
        assertEquals(listOf("mpill:source", "mpill:keyword"), list.right.pills.map { it.id })
        assertEquals(TvSourceAction.ManualOpenSubject(subject), list.right.rows.single().action)

        val opened = searching.copy(
            openedSubject = subject,
            channels = ManualLoadState.Success(
                listOf(BrowseChannel("线路1", episodes = (1..6).map { BrowseEpisode("第${it}集", "https://example.com/e/$it") })),
            ),
            selectedEpisodeIndex = 4,
        )
        val grid = buildTvSourcePanel(input(manual = opened), TvSourceNav(railKey = TvSourceRailKeys.MANUAL), strings)
        assertEquals(TV_SOURCE_EPISODE_COLUMNS, grid.right.columns)
        assertEquals("mep:4", grid.right.focusId)
        assertEquals(listOf("mpill:back", "mpill:remember"), grid.right.pills.map { it.id })
    }

    @Test
    fun `manual browse is hidden when no source supports it`() {
        val content = buildTvSourcePanel(
            input(manual = ManualBrowsePresentation.Empty.copy(isPlaceholder = false)),
            TvSourceNav(),
            strings,
        )
        assertNull(content.rail.firstOrNull { it.id == TvSourceRailKeys.MANUAL })
    }

    @Test
    fun `frozen order keeps rows in place and appends new ones`() {
        fun row(id: String) = TvSourceRow(id = id, style = TvSourceRowStyle.Option, title = id)
        val result = applyFrozenOrder(listOf(row("c"), row("a"), row("d")), listOf("a", "b", "c"))
        assertEquals(listOf("a", "c", "d"), result.map { it.id })
        assertEquals(listOf("x"), applyFrozenOrder(listOf(row("x")), null).map { it.id })
    }

    @Test
    fun `status line counts finished sources while searching`() {
        val working = MediaSourceResultListPresentation(
            sources.list + sourceResult("web-d", MediaSourceKind.WEB, MediaSourceFetchState.Working),
        )
        val content = buildTvSourcePanel(input(sources = working), TvSourceNav(), strings)
        assertEquals("searching 5/6", content.status)
    }

    @Test
    fun `alliance names become literal exclusion patterns`() {
        assertEquals("喵萌奶茶屋", alliancePattern("喵萌奶茶屋"))
        assertEquals("""LoliHouse \(x265\)""", alliancePattern(" LoliHouse (x265) "))
    }

    private val strings = TvSourceStrings(
        searching = "searching %1\$d/%2\$d",
        searched = "searched %1\$d",
        bt = "bt",
        manual = "manual",
        failed = "failed",
        refresh = "refresh",
        fullSearch = "fullSearch",
        on = "on",
        off = "off",
        keywords = "keywords",
        playing = "playing",
        line = "line %1\$d",
        loading = "loading",
        noResult = "noResult",
        captchaRequired = "captchaRequired",
        captchaAction = "captchaAction",
        captchaHint = "captchaHint",
        captchaUnsupported = "captchaUnsupported",
        resolvingCaptcha = "resolvingCaptcha",
        rateLimited = "rateLimited %1\$d",
        rateLimitedHint = "rateLimitedHint %1\$d",
        rateLimitedExpired = "rateLimitedExpired",
        rateLimitedExpiredHint = "rateLimitedExpiredHint",
        failedState = "failedState",
        retry = "retry",
        retryHint = "retryHint",
        disabled = "disabled",
        episodeFilter = "episodeFilter",
        allEpisodes = "allEpisodes",
        resolution = "resolution",
        subtitle = "subtitle",
        alliance = "alliance",
        source = "source",
        all = "all",
        showExcluded = "showExcluded %1\$d",
        hideExcluded = "hideExcluded %1\$d",
        hiddenByFilter = "hiddenByFilter %1\$d",
        sourcesCount = "sourcesCount:%1\$d",
        cached = "cached",
        pack = "pack",
        noSubtitle = "noSubtitle",
        btLoading = "btLoading",
        btEmpty = "btEmpty",
        menuCache = "menuCache",
        menuExclude = "menuExclude %1\$s",
        pick = "pick",
        open = "open",
        navHint = "navHint",
        labelStatus = "labelStatus",
        labelLines = "labelLines",
        labelSource = "labelSource",
        labelSize = "labelSize",
        labelDate = "labelDate",
        labelEpisodes = "labelEpisodes",
        labelSources = "labelSources",
        labelKind = "labelKind",
        labelLink = "labelLink",
        labelError = "labelError",
        labelEpisode = "labelEpisode",
        statusDone = "statusDone",
        statusSearching = "statusSearching",
        kindWeb = "kindWeb",
        kindBt = "kindBt",
        kindCache = "kindCache",
        downloads = "downloads",
        downloadsOpen = "downloadsOpen",
        downloadCaching = "caching %1\$s",
        downloadsEmpty = "downloadsEmpty",
        downloadsEmptyDiskCache = "downloadsEmptyDiskCache",
        reasonFuzzyTitle = "reasonFuzzyTitle",
        reasonEpisodeUnknown = "reasonEpisodeUnknown",
        showOthers = "showOthers %1\$d",
        hideOthers = "hideOthers %1\$d",
        railOthers = "others %1\$d",
        webNoExact = "webNoExact",
        episodesSeason = "season %1\$d",
        episodesWholeSeason = "wholeSeason",
        filter = "filter",
        driveNotLoggedIn = "driveNotLoggedIn",
        driveLoginAction = "driveLoginAction",
        driveLoginHint = "driveLoginHint",
        siteIpBlocked = "siteIpBlocked %1\$s",
        groupLocal = "groupLocal",
        groupDrive = "groupDrive",
        groupOnline = "groupOnline",
        groupDirect = "groupDirect",
        emptySources = "emptySources %1\$d",
        hideEmptySources = "hideEmptySources %1\$d",
        playbackFailed = "playbackFailed",
        playbackBuffering = "playbackBuffering",
        settings = "settings",
        searchPaused = "searchPaused %1\$d/%2\$d",
        fullSearchPaused = "fullSearchPaused",
        resumeSource = "resumeSource",
        pausedHint = "pausedHint",
        manualSource = "manualSource %1\$s",
        manualKeyword = "manualKeyword %1\$s",
        manualSearching = "manualSearching",
        manualFailed = "manualFailed",
        manualEmpty = "manualEmpty",
        manualNoSources = "manualNoSources",
        manualChannel = "manualChannel %1\$s",
        manualRemember = "manualRemember",
        manualBack = "manualBack",
        manualLoadingEpisodes = "manualLoadingEpisodes",
        manualNoEpisodes = "manualNoEpisodes",
        reasonEpisodeMismatch = "reasonEpisodeMismatch",
        reasonBelowPreference = "reasonBelowPreference",
        reasonNoSubtitle = "reasonNoSubtitle",
        reasonSingleEpisode = "reasonSingleEpisode",
        reasonUnsupported = "reasonUnsupported",
        reasonSeasonMismatch = "reasonSeasonMismatch",
        reasonTitleMismatch = "reasonTitleMismatch",
        reasonCacheNotReady = "reasonCacheNotReady",
        reasonExcludedAlliance = "reasonExcludedAlliance",
        reasonPublishedBeforeAiring = "reasonPublishedBeforeAiring",
        details = MediaDetailsStrings("e", "c", "ep", "ed", "ced", "unknown", "yue", "chs", "cht", "jp", "en"),
        timeZone = TimeZone.UTC,
    )
}
