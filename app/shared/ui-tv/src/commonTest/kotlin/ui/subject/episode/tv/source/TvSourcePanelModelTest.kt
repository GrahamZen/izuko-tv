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
import me.him188.ani.app.ui.media.MediaDetailsStrings
import me.him188.ani.app.ui.mediafetch.MediaPreferenceItemState
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresentation
import me.him188.ani.app.ui.mediafetch.MediaSourceResultPresentation
import me.him188.ani.app.ui.mediaselect.MediaSelectorMode
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
    ): DefaultMedia = createTestDefaultMedia(
        mediaId = id,
        mediaSourceId = source,
        originalUrl = "https://example.com/$id",
        download = ResourceLocation.HttpStreamingFile("https://example.com/$id.mp4"),
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
    private val btDmhy = media("bt1", "dmhy")
    private val btMikan = media("bt2", "mikan") // 与 btDmhy 同一个标题: 同一个发布
    private val btOther = media("bt3", "dmhy", title = "[桜都字幕组] 孤独摇滚 05 [1080P]", alliance = "桜都字幕组")

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
        initialMode: MediaSelectorMode = MediaSelectorMode.AUTO,
        sources: MediaSourceResultListPresentation = this.sources,
    ) = TvSourcePanelInput(
        selector = selector(webSources, selected),
        bt = bt,
        sources = sources,
        manual = manual,
        fullSearch = false,
        canEditKeywords = true,
        canCache = true,
        initialMode = initialMode,
        nowMillis = 0,
    )

    @Test
    fun `rail lists BT first then live web sources failed sources and actions`() {
        val content = buildTvSourcePanel(input(), TvSourceNav(), strings)
        assertEquals(
            listOf(
                TvSourceRailKeys.BT, "web:web-a", "web:web-b", TvSourceRailKeys.FAILED,
                TvSourceRailKeys.ACTION_REFRESH, TvSourceRailKeys.ACTION_FULL_SEARCH, TvSourceRailKeys.ACTION_KEYWORDS,
            ),
            content.rail.map { it.id },
        )
        assertEquals("2", content.rail.first { it.id == "web:web-a" }.trailing)
        assertEquals("1", content.rail.first { it.id == TvSourceRailKeys.FAILED }.trailing)
    }

    @Test
    fun `downloads entry sits right under BT and its branch lists the caches of this episode`() {
        val downloads = listOf(
            TvSourceCacheItem("c1", btDmhy, percent = 100, finished = true),
            TvSourceCacheItem("c2", btOther, percent = 42, finished = false),
        )
        val input = input().copy(canOpenDownloads = true, downloads = downloads)
        val content = buildTvSourcePanel(input, TvSourceNav(), strings)
        assertEquals(listOf(TvSourceRailKeys.BT, TvSourceRailKeys.DOWNLOADS, "web:web-a"), content.rail.take(3).map { it.id })
        assertEquals("2", content.rail[1].trailing)
        assertTrue(content.rail[2].dividerAbove)
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

    @Test
    fun `lands on the source of the playing web line and the line is highlighted`() {
        val content = buildTvSourcePanel(input(selected = webA2), TvSourceNav(), strings)
        assertEquals("web:web-a", content.railKey)
        val playing = content.right.rows.single { it.selected }
        assertEquals(content.right.focusId, playing.id)
        assertEquals("线路2", playing.title)
        assertEquals(TvSourceRowIcon.Playing, content.rail.first { it.id == "web:web-a" }.icon)
    }

    @Test
    fun `lands on BT when the playing resource is a torrent`() {
        val bt = bt(listOf(BtRow(btDmhy, null, isSelected = true), BtRow(btOther, null, false)))
        val content = buildTvSourcePanel(input(selected = btDmhy, bt = bt), TvSourceNav(), strings)
        assertEquals(TvSourceRailKeys.BT, content.railKey)
        assertEquals(content.right.rows.first { it.selected }.id, content.right.focusId)
        assertTrue(content.right.wide)
    }

    @Test
    fun `without a playing resource lands on the first rail item or BT in BT mode`() {
        val auto = buildTvSourcePanel(input(), TvSourceNav(), strings)
        assertEquals("web:web-a", auto.railKey)
        assertNull(auto.right.focusId)
        val btMode = buildTvSourcePanel(input(initialMode = MediaSelectorMode.BT), TvSourceNav(), strings)
        assertEquals(TvSourceRailKeys.BT, btMode.railKey)
    }

    @Test
    fun `user choice of rail item wins over the playing resource`() {
        val content = buildTvSourcePanel(input(selected = webA1), TvSourceNav(railKey = "web:web-b"), strings)
        assertEquals("web:web-b", content.railKey)
        assertEquals(listOf("线路1"), content.right.rows.map { it.title })
    }

    @Test
    fun `BT rows merge identical titles from different sites only`() {
        val content = buildTvSourcePanel(input(), TvSourceNav(railKey = TvSourceRailKeys.BT), strings)
        val rows = content.right.rows.filter { it.style == TvSourceRowStyle.Resource }
        assertEquals(2, rows.size)
        assertTrue(rows[0].meta.contains("sourcesCount:2"), rows[0].meta)
        assertEquals(btOther.originalTitle, rows[1].title)
        assertEquals(listOf("pill:episode", "pill:Resolution", "pill:Subtitle", "pill:Alliance", "pill:Source"), content.right.pills.map { it.id })
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
        val railRow = content.rail.single { it.id == key }
        assertEquals("others 1", railRow.trailing)
        // 一条对得上的都没有: 不用开关, 直接列出来
        assertEquals(listOf("webNoExact", fuzzy.originalTitle), content.right.rows.map { it.title })
        assertEquals("reasonFuzzyTitle、reasonEpisodeUnknown", content.right.rows.last().meta.substringBefore(" · "))

        // 选中的是它时左栏那一项标成正在播放
        val playing = buildTvSourcePanel(input.copy(selector = input.selector.copy(selected = fuzzy)), TvSourceNav(railKey = key), strings)
        assertTrue(playing.rail.single { it.id == key }.selected)
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
        val content = buildTvSourcePanel(input, TvSourceNav(), strings)
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
        val source = assertNotNull(content.rail.first { it.id == "web:web-a" }.details)
        assertEquals(TvSourceAction.Play(webA1), source.primary?.action)
        assertTrue(source.fields.any { it.label == "labelLines" && it.value.lines().size == 2 })
        // 操作行没有详情
        assertNull(content.rail.first { it.id == TvSourceRailKeys.ACTION_REFRESH }.details)
    }

    @Test
    fun `filter drill lists values with the current one checked`() {
        val content = buildTvSourcePanel(
            input(bt = bt(listOf(BtRow(btDmhy, null, false)), resolution = "720P")),
            TvSourceNav(railKey = TvSourceRailKeys.BT, drill = TvSourceDrill.Filter(TvSourceFilter.Resolution)),
            strings,
        )
        assertEquals("resolution", content.right.title)
        assertEquals(listOf("opt:all", "opt:1080P", "opt:720P"), content.right.rows.map { it.id })
        assertEquals("opt:720P", content.right.focusId)
        assertEquals(TvSourceAction.PickFilter(TvSourceFilter.Resolution, null), content.right.rows.first().action)
    }

    @Test
    fun `failed sources pane retries each source`() {
        val content = buildTvSourcePanel(input(), TvSourceNav(railKey = TvSourceRailKeys.FAILED), strings)
        assertEquals(listOf(TvSourceAction.RestartSource("web-c")), content.right.rows.map { it.action })
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
        details = MediaDetailsStrings("e", "c", "ep", "ed", "ced", "unknown", "yue", "chs", "cht", "jp", "en"),
        timeZone = TimeZone.UTC,
    )
}
