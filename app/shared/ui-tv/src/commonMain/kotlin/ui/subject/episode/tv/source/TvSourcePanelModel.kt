/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.runtime.Immutable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.isPerfectMatch
import me.him188.ani.app.domain.mediasource.clouddrive.DriveVideoBitrates
import me.him188.ani.app.ui.media.MediaDetailsRenderer
import me.him188.ani.app.ui.media.MediaDetailsStrings
import me.him188.ani.app.ui.media.renderSubtitleLanguage
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresentation
import me.him188.ani.app.ui.mediafetch.MediaSourceResultPresentation
import me.him188.ani.app.ui.mediaselect.MediaSelectorMode
import me.him188.ani.app.ui.mediaselect.bt.BtListPresentation
import me.him188.ani.app.ui.mediaselect.bt.BtRow
import me.him188.ani.app.ui.mediaselect.bt.BtRowExclusion
import me.him188.ani.app.ui.mediaselect.manual.ManualBrowsePresentation
import me.him188.ani.app.ui.mediaselect.manual.ManualLoadState
import me.him188.ani.app.ui.mediaselect.selector.WebSource
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.BrowseSubject
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.isSingleEpisode
import kotlin.time.Instant

/*
 * TV 选源面板 (TvSourcePanelView) 的数据模型: 上游选源状态 (MediaSelectorState / BT 列表投影 / 数据源状态 / 手动查找) 加上面板自己的
 * 导航状态 ([TvSourceNav]), 由 [buildTvSourcePanel] 拼成两栏要画的行. 纯函数, 不碰 Compose 与视图.
 *
 * 两栏的分工: 左栏 = 去哪里找 (BT 与缓存、下载、各个在线源、查询失败的源、手动查找、几个操作), 右栏 = 左栏聚焦的那一项里有什么.
 * 最顶上是「筛选」(分辨率 / 字幕, 对下面各个源一起生效, 见 TvSourcePanelFilters), 其下是 BT: 不用的人往下一按就滑走了,
 * 用的人不用在一长串在线源后面找.
 * 右栏还能「进去一层」(筛选的取值、资源的长按菜单、资源详情、手动查找的数据源与线路), 见 [TvSourceDrill]; 整块面板最多三层.
 */

/** 左栏各项的键. 在线源是 `web:` + 数据源实例 id. */
internal object TvSourceRailKeys {
    const val WEB_PREFIX = "web:"
    const val BT = "bt"
    const val MANUAL = "manual"
    const val FAILED = "failed"
    const val DOWNLOADS = "downloads"
    const val FILTER = "filter"
    const val ACTION_REFRESH = "action:refresh"
    const val ACTION_FULL_SEARCH = "action:full-search"
    const val ACTION_KEYWORDS = "action:keywords"

    fun web(instanceId: String) = WEB_PREFIX + instanceId
}

/** 一行的版式 (原生视图按它选行视图与行高). */
enum class TvSourceRowStyle {
    /** 左栏: 图标 + 一行名字 + 右端状态. */
    Rail,

    /** 右栏两行: 名字一行 + 信息一行 (在线源的线路、失败的源、手动查找的条目). */
    Line,

    /** 右栏多行: 标题三行 + 信息一行 (BT 资源). */
    Resource,

    /**
     * 右栏: 标题至多两行 + 信息一行, 高度按内容 (网盘这类一个文件一行的在线源, 见 webFileRow).
     * 文件名多半一行, 照 [Resource] 的固定三行高会在上下空出一大块.
     */
    File,

    /** 右栏一行: 操作 / 筛选取值. */
    Option,

    /** 不可聚焦的说明 (正在查询、没有结果). */
    Status,

    /** 右栏顶上那排筛选胶囊. */
    Pill,

    /** 手动查找的剧集方块. */
    Cell,
}

/** 行首图标. 原生视图把每种画成一张位图, [Source] 画数据源图标 ([TvSourceRow.iconUrl]). */
enum class TvSourceRowIcon {
    None,
    Source,
    Playing,
    Check,
    Refresh,
    Search,
    Edit,
    Warning,
    Download,
    Block,
    Info,
    Torrent,

    /** 去缓存页 (下载其他集). */
    Downloads,

    /** 左栏的「筛选」. */
    Filter,
}

/** 右端字的强调: [Error] 红 (失败 / 排除原因), [Attention] 主要文字色 (要验证 / 正在播放), 其余次要色. */
enum class TvSourceAccent { None, Attention, Error }

/**
 * 面板里的一行 (两栏、筛选胶囊、剧集方块都是它). [id] 在所在列表里唯一且稳定 —— 列表按它做差异更新、记焦点.
 *
 * @property selected 当前生效的那一项 (正在播放的资源 / 筛选的当前值): 行首画 [icon], 未聚焦时底色稍亮.
 * @property dimmed 被排除的资源、查询失败的源: 未聚焦时文字压暗.
 * @property loading 右端画转圈 (数据源还在查).
 * @property action 确定键做什么; null = 只能看.
 * @property longAction 长按确定键做什么; null = 有 [details] 就开详情弹窗, 否则什么都不做.
 * @property details 长按弹出的详情 (见 [TvSourceDetails]); null = 这一行没有详情 (操作、筛选取值、说明).
 */
@Immutable
data class TvSourceRow(
    val id: String,
    val style: TvSourceRowStyle,
    val title: String,
    val meta: String = "",
    val trailing: String = "",
    val icon: TvSourceRowIcon = TvSourceRowIcon.None,
    val iconUrl: String? = null,
    val selected: Boolean = false,
    val dimmed: Boolean = false,
    val loading: Boolean = false,
    val accent: TvSourceAccent = TvSourceAccent.None,
    val metaAccent: TvSourceAccent = TvSourceAccent.None,
    val dividerAbove: Boolean = false,
    val action: TvSourceAction? = null,
    val longAction: TvSourceAction? = null,
    val details: TvSourceDetails? = null,
) {
    val focusable: Boolean get() = style != TvSourceRowStyle.Status
}

/**
 * 长按一行弹出的详情 (TvSourceDetailsDialog): 完整标题、能列的信息都列上, 右边一列按钮 —— [primary] 是「选择」那一个 (弹窗打开时焦点就在它上面,
 * 直接按确定 = 选), [secondary] 是其余操作 (缓存这一条、排除字幕组). 左右键切到同一栏的上一条 / 下一条.
 *
 * @property note 一句要强调的话 (排除原因), 红字.
 */
@Immutable
data class TvSourceDetails(
    val title: String,
    val subtitle: String = "",
    val fields: List<TvSourceDetailField> = emptyList(),
    val note: String = "",
    val primary: TvSourceButton? = null,
    val secondary: List<TvSourceButton> = emptyList(),
)

/**
 * 详情里的一项. [short] = 取值只有几个字 (类型、分辨率、字幕、大小、日期、集数、状态…): 弹窗里排进上面的表格; 其余的 (字幕组、数据源、
 * 链接、线路列表…) 长短不定, 一项一行排在下面, 最多 [maxLines] 行 (链接只占一行).
 */
@Immutable
data class TvSourceDetailField(
    val label: String,
    val value: String,
    val short: Boolean = false,
    val maxLines: Int = Int.MAX_VALUE,
)

@Immutable
data class TvSourceButton(val label: String, val action: TvSourceAction, val icon: TvSourceRowIcon = TvSourceRowIcon.None)

/** 筛选维度: 分辨率 / 字幕在左栏的「筛选」里 (见 TvSourcePanelFilters), 字幕组 / 来源是 BT 与缓存那一栏顶上的胶囊. 集号是开关, 不在这里. */
enum class TvSourceFilter { Resolution, Subtitle, Alliance, Source }

/** 右栏「进去的那一层」. */
sealed interface TvSourceDrill {
    data class Filter(val filter: TvSourceFilter) : TvSourceDrill

    /** 手动查找: 换数据源. */
    data object ManualSources : TvSourceDrill

    /** 手动查找: 换线路. */
    data object ManualChannels : TvSourceDrill
}

/** 确定键 / 长按确定键的动作, 由面板状态 (TvSourcePanelState) 执行. */
sealed interface TvSourceAction {
    /** 播放这一条 (选源). */
    data class Play(val media: Media) : TvSourceAction

    data class RestartSource(val instanceId: String) : TvSourceAction
    data class ResolveCaptcha(val instanceId: String) : TvSourceAction
    data object Refresh : TvSourceAction
    data class SetFullSearch(val enabled: Boolean) : TvSourceAction
    data object EditKeywords : TvSourceAction

    /** 去这部番的缓存页 (原来播放器图标行上的「下载」). */
    data object OpenDownloads : TvSourceAction

    data object ToggleEpisodeFilter : TvSourceAction
    data object ToggleExcluded : TvSourceAction

    /** 进入一层 (筛选取值 / 手动查找的数据源与线路). */
    data class Open(val drill: TvSourceDrill) : TvSourceAction

    /**
     * 打开 [rowId] 那一行的详情弹窗; [inRail] = 那一行在左栏. [railKey] = 右栏的行属于左栏哪一项 (不同在线源的线路 id 会重),
     * 左栏的行为 null. 弹窗里左右键换行就是换成序列里的另一条 (见 [buildTvSourceDetailsSequence]).
     */
    data class ShowDetails(val rowId: String, val inRail: Boolean, val railKey: String? = null) : TvSourceAction

    /** 选一个筛选取值; [value] = null 是「全部」. */
    data class PickFilter(val filter: TvSourceFilter, val value: String?) : TvSourceAction

    data class Cache(val media: Media) : TvSourceAction
    data class ExcludeAlliance(val alliance: String) : TvSourceAction

    data class ManualSelectSource(val instanceId: String) : TvSourceAction
    data object ManualEditKeyword : TvSourceAction
    data object ManualRetry : TvSourceAction
    data class ManualOpenSubject(val subject: BrowseSubject) : TvSourceAction
    data object ManualCloseSubject : TvSourceAction
    data class ManualSelectChannel(val index: Int) : TvSourceAction
    data class ManualSetRemember(val remember: Boolean) : TvSourceAction
    data class ManualPlay(val episodeIndex: Int) : TvSourceAction
}

/**
 * 面板自己的导航状态.
 *
 * @property railKey 左栏聚焦的那一项 (右栏跟着它); null = 还没定, 按正在播放的资源落.
 * @property drill 右栏进去的那一层; null = 右栏本身.
 * @property frozenOrder 用户正在右栏里按键时钉住的行序 (见 [applyFrozenOrder]); null = 不钉.
 */
@Immutable
data class TvSourceNav(
    val railKey: String? = null,
    val drill: TvSourceDrill? = null,
    val showExcluded: Boolean = false,
    val frozenOrder: List<String>? = null,
)

/**
 * 拼面板用的上游状态快照.
 *
 * @property manual 手动查找; null = 宿主没有手动查找 (左栏没有这一项).
 * @property fullSearch 「完整搜索」开关的值; null = 不显示 (设置里开了始终完整搜索, 或宿主没有这个开关).
 * @property canEditKeywords 宿主能改搜索词.
 * @property canCache 资源菜单里给不给「缓存这一条」.
 * @property canOpenDownloads 左栏给不给「下载」(这一集的缓存 + 去缓存页); 缓存页自己开的面板不给.
 * @property downloads 这一集的缓存 (已下完的和正在下的), 「下载」那一项的分支列的就是它们.
 * @property playbackDiskCache 设置里「边下边播」开着 (在线播放时看过的部分存在本机, 不是下载).
 */
@Immutable
data class TvSourcePanelInput(
    val selector: MediaSelectorState.Presentation,
    val bt: BtListPresentation,
    val sources: MediaSourceResultListPresentation,
    val manual: ManualBrowsePresentation?,
    val fullSearch: Boolean?,
    val canEditKeywords: Boolean,
    val canCache: Boolean,
    val initialMode: MediaSelectorMode,
    val nowMillis: Long,
    val canOpenDownloads: Boolean = false,
    val downloads: List<TvSourceCacheItem> = emptyList(),
    val playbackDiskCache: Boolean = false,
)

/**
 * 这一集的一条缓存 (见 [TvSourcePanelInput.downloads]): [origin] 是缓存的来源资源, [percent] 下了多少 (0..100), [finished] 下完了没有.
 * 要播的是选择器里对应的那条本地缓存资源, 按 [cacheId] / 来源资源对上 (见 [downloadsPane]).
 */
@Immutable
data class TvSourceCacheItem(val cacheId: String, val origin: Media, val percent: Int, val finished: Boolean)

/** 右栏: [key] 变了就是换了一屏内容 (视图据此复位滚动与焦点), 同一屏里只是行变了. */
@Immutable
data class TvSourceRight(
    val key: String,
    val title: String? = null,
    val pills: List<TvSourceRow> = emptyList(),
    val rows: List<TvSourceRow> = emptyList(),
    val columns: Int = 1,
    /** 进右栏时的落点 (正在播放的那一条 / 当前值); null = 第一条可聚焦的. */
    val focusId: String? = null,
    /** 标题普遍很长 (BT 资源、下载): 分支用固定的宽框, 标题折三行; 其余的分支按内容定宽、字只占一行. */
    val wide: Boolean = false,
)

@Immutable
data class TvSourcePanelContent(
    val status: String,
    val rail: List<TvSourceRow>,
    /** 左栏落在哪一项 ([TvSourceNav.railKey] 生效后的值). */
    val railKey: String?,
    val right: TvSourceRight,
)

/**
 * 面板上的全部文案, 由 Compose 一次解析好传进来. 带 `%1$d` / `%1$s` 的是格式串.
 */
@Immutable
data class TvSourceStrings(
    val searching: String,
    val searched: String,
    val bt: String,
    val manual: String,
    val failed: String,
    val refresh: String,
    val fullSearch: String,
    val on: String,
    val off: String,
    val keywords: String,
    val playing: String,
    val line: String,
    val loading: String,
    val noResult: String,
    val captchaRequired: String,
    val captchaAction: String,
    val captchaHint: String,
    val captchaUnsupported: String,
    val resolvingCaptcha: String,
    val rateLimited: String,
    val rateLimitedHint: String,
    /** 限流倒计时走完、自动重试也没过: 不再自动重试, 等用户按重试. */
    val rateLimitedExpired: String,
    val rateLimitedExpiredHint: String,
    val failedState: String,
    val retry: String,
    val retryHint: String,
    val disabled: String,
    val episodeFilter: String,
    val allEpisodes: String,
    val resolution: String,
    val subtitle: String,
    val alliance: String,
    val source: String,
    val all: String,
    val showExcluded: String,
    val hideExcluded: String,
    val sourcesCount: String,
    val cached: String,
    val pack: String,
    val noSubtitle: String,
    val btLoading: String,
    val btEmpty: String,
    val menuCache: String,
    val menuExclude: String,
    val pick: String,
    val open: String,
    val navHint: String,
    val labelStatus: String,
    val labelLines: String,
    val labelSource: String,
    val labelSize: String,
    val labelDate: String,
    val labelEpisodes: String,
    val labelSources: String,
    val labelKind: String,
    val labelLink: String,
    val labelError: String,
    val labelEpisode: String,
    val statusDone: String,
    val statusSearching: String,
    val kindWeb: String,
    val kindBt: String,
    val kindCache: String,
    val manualSource: String,
    val manualKeyword: String,
    val manualSearching: String,
    val manualFailed: String,
    val manualEmpty: String,
    val manualNoSources: String,
    val manualChannel: String,
    val manualRemember: String,
    val manualBack: String,
    val manualLoadingEpisodes: String,
    val manualNoEpisodes: String,
    val reasonEpisodeMismatch: String,
    val reasonBelowPreference: String,
    val reasonNoSubtitle: String,
    val reasonSingleEpisode: String,
    val reasonUnsupported: String,
    val reasonSeasonMismatch: String,
    val reasonTitleMismatch: String,
    val reasonCacheNotReady: String,
    val reasonExcludedAlliance: String,
    val downloads: String,
    val downloadsOpen: String,
    val downloadCaching: String,
    val downloadsEmpty: String,
    val downloadsEmptyDiskCache: String,
    val reasonFuzzyTitle: String,
    val reasonEpisodeUnknown: String,
    val showOthers: String,
    val hideOthers: String,
    val railOthers: String,
    val webNoExact: String,
    val episodesSeason: String,
    val episodesWholeSeason: String,
    val filter: String,
    val details: MediaDetailsStrings,
    val timeZone: TimeZone,
) {
    fun exclusion(exclusion: BtRowExclusion): String = when (exclusion) {
        BtRowExclusion.BelowPreference -> reasonBelowPreference
        is BtRowExclusion.Reason -> when (exclusion.reason) {
            is MediaExclusionReason.EpisodeMismatch -> reasonEpisodeMismatch
            MediaExclusionReason.MediaWithoutSubtitle -> reasonNoSubtitle
            is MediaExclusionReason.SingleEpisodeForCompleteSubject -> reasonSingleEpisode
            MediaExclusionReason.UnsupportedByPlatformPlayer -> reasonUnsupported
            MediaExclusionReason.FromSequelSeason, MediaExclusionReason.FromSeriesSeason -> reasonSeasonMismatch
            MediaExclusionReason.SubjectNameMismatch -> reasonTitleMismatch
            MediaExclusionReason.CacheNotReady -> reasonCacheNotReady
            MediaExclusionReason.ExcludedAlliance -> reasonExcludedAlliance
        }
    }
}

/**
 * BT 与缓存里的一行: 同一个种子 (infohash 相同, 同一个发布被几个站点收录) 合成一组, 播放组里第一条 (排序最靠前的).
 * 下载速度只看种子的做种情况, 与从哪个站点拿到链接无关. 认不出种子的不合并: 标题相同的也可能是重新压制后用原标题再发的另一个种子.
 */
@Immutable
data class TvBtGroup(
    val key: String,
    val rows: List<BtRow>,
) {
    val first: BtRow get() = rows.first()
    val isSelected: Boolean get() = rows.any { it.isSelected }
    val sourceIds: List<String> get() = rows.map { it.media.mediaSourceId }.distinct()
}

/** 按种子合并 [rows] (保持首次出现的顺序). */
fun groupBtRows(rows: List<BtRow>): List<TvBtGroup> {
    val groups = LinkedHashMap<String, MutableList<BtRow>>()
    for (row in rows) {
        groups.getOrPut(btGroupKey(row.media)) { ArrayList(2) }.add(row)
    }
    return groups.map { (key, list) -> TvBtGroup(key, list) }
}

/** 合并用的键: 本地缓存按资源 id (每条缓存都是单独的一份), 其余按种子 ([torrentHashOf]), 认不出种子的按资源 id. */
internal fun btGroupKey(media: Media): String =
    if (media.kind == MediaSourceKind.LocalCache) "cache:" + media.mediaId
    else torrentHashOf(media)?.let { "btih:$it" } ?: ("media:" + media.mediaId)

/**
 * 用户正在右栏里按键时钉住行序: [frozen] 里有的按它的顺序排, 新来的追加在后面 (保持 [rows] 里的先后), 没了的去掉.
 * 不钉的话搜索途中结果一到列表就重排, 正要按的那一行被挤到别处 (焦点按 id 跟着它走, 但用户看到的是整页在跳).
 */
fun applyFrozenOrder(rows: List<TvSourceRow>, frozen: List<String>?): List<TvSourceRow> {
    if (frozen == null) return rows
    val byId = rows.associateBy { it.id }
    val result = ArrayList<TvSourceRow>(rows.size)
    val placed = HashSet<String>()
    for (id in frozen) {
        val row = byId[id] ?: continue
        result.add(row)
        placed.add(id)
    }
    for (row in rows) {
        if (row.id !in placed) result.add(row)
    }
    return result
}

/** 拼出整个面板. 见文件头. */
fun buildTvSourcePanel(input: TvSourcePanelInput, nav: TvSourceNav, strings: TvSourceStrings): TvSourcePanelContent {
    val selected = input.selector.selected
    val webSources = input.selector.webSources
    // 查询失败的在线源收进「查询失败」那一项, 不在列表里占位置; 结果全都对不上的源也留着 (见 allWebSources)
    val liveWebSources = allWebSources(input, strings).filterNot { it.isError }
    val failedSources = failedSourcesOf(input)
    val hasBt = input.sources.btSources.isNotEmpty() || input.bt.totalCount > 0
    val manual = input.manual?.takeIf { it.isPlaceholder || it.sources.isNotEmpty() }

    val playingWebSource = webSources.firstOrNull { source -> source.channels.any { it.original == selected } }
        ?: liveWebSources.firstOrNull { source ->
            selected != null && selected.kind == MediaSourceKind.WEB && selected.mediaSourceId == source.mediaSourceId
        }
    val playingInBt = selected != null &&
            (selected.kind == MediaSourceKind.BitTorrent || selected.kind == MediaSourceKind.LocalCache)
    // 分辨率 / 字幕筛选对 BT 与一个文件一行的在线源生效 (见 TvSourcePanelFilters); 只有线路式的在线源时没东西可筛, 左栏不列「筛选」
    val fileSources = liveWebSources.mapNotNull { webFilesOf(it, input, strings) }
    val filterable = hasBt || fileSources.isNotEmpty()

    val rail = buildList {
        if (hasBt) {
            val btWorking = input.sources.btSources.any { it.isWorking }
            add(
                TvSourceRow(
                    id = TvSourceRailKeys.BT,
                    style = TvSourceRowStyle.Rail,
                    title = strings.bt,
                    icon = if (playingInBt) TvSourceRowIcon.Playing else TvSourceRowIcon.Torrent,
                    selected = playingInBt,
                    trailing = if (btWorking && input.bt.included.isEmpty()) "" else input.bt.included.size.toString(),
                    loading = btWorking && input.bt.included.isEmpty(),
                ),
            )
        }
        // 挨着「BT 与缓存」: 这一集的缓存在它的分支里一眼看全 (不用逐个数据源翻), 去缓存页下别的集也从这儿走
        if (input.canOpenDownloads) {
            add(
                TvSourceRow(
                    id = TvSourceRailKeys.DOWNLOADS,
                    style = TvSourceRowStyle.Rail,
                    title = strings.downloads,
                    icon = TvSourceRowIcon.Downloads,
                    trailing = input.downloads.size.takeIf { it > 0 }?.toString().orEmpty(),
                ),
            )
        }
        val topGroup = hasBt || input.canOpenDownloads
        liveWebSources.forEachIndexed { index, source ->
            val row = webRailRow(source, playing = source == playingWebSource, input, strings)
            add(if (index == 0 && topGroup) row.copy(dividerAbove = true) else row)
        }
        if (failedSources.isNotEmpty()) {
            add(
                TvSourceRow(
                    id = TvSourceRailKeys.FAILED,
                    style = TvSourceRowStyle.Rail,
                    title = strings.failed,
                    icon = TvSourceRowIcon.Warning,
                    trailing = failedSources.size.toString(),
                    dimmed = true,
                    dividerAbove = true,
                ),
            )
        }
        // 手动查找在查询失败下面: 往下跨数据源时 (BT → 下载 → 各在线源 → 查询失败) 一路都是有右栏的, 它与几个操作挨着
        if (manual != null) {
            add(
                TvSourceRow(
                    id = TvSourceRailKeys.MANUAL,
                    style = TvSourceRowStyle.Rail,
                    title = strings.manual,
                    icon = TvSourceRowIcon.Search,
                    dividerAbove = failedSources.isEmpty() && (topGroup || liveWebSources.isNotEmpty()),
                ),
            )
        }
        add(
            TvSourceRow(
                id = TvSourceRailKeys.ACTION_REFRESH,
                style = TvSourceRowStyle.Rail,
                title = strings.refresh,
                icon = TvSourceRowIcon.Refresh,
                dividerAbove = true,
                action = TvSourceAction.Refresh,
            ),
        )
        input.fullSearch?.let { full ->
            add(
                TvSourceRow(
                    id = TvSourceRailKeys.ACTION_FULL_SEARCH,
                    style = TvSourceRowStyle.Rail,
                    title = strings.fullSearch,
                    icon = TvSourceRowIcon.Search,
                    trailing = if (full) strings.on else strings.off,
                    accent = if (full) TvSourceAccent.Attention else TvSourceAccent.None,
                    action = TvSourceAction.SetFullSearch(!full),
                ),
            )
        }
        if (input.canEditKeywords) {
            add(
                TvSourceRow(
                    id = TvSourceRailKeys.ACTION_KEYWORDS,
                    style = TvSourceRowStyle.Rail,
                    title = strings.keywords,
                    icon = TvSourceRowIcon.Edit,
                    action = TvSourceAction.EditKeywords,
                ),
            )
        }
    }.let { if (filterable) it.withFilterRow(filterRailRow(input, strings)) else it }

    // 左栏落点: 用户选过的 (还在) → 正在播放的资源所在的那一项 → 初始模式是 BT 时 BT → 第一个在线源 (BT 虽在最顶上, 不用它的人不该一打开就落在它上面) → 第一项
    val playingKey = when {
        playingWebSource != null && !playingWebSource.isError -> TvSourceRailKeys.web(playingWebSource.instanceId)
        playingInBt && hasBt -> TvSourceRailKeys.BT
        else -> null
    }
    val railKey = nav.railKey?.takeIf { key -> rail.any { it.id == key } }
        ?: playingKey
        ?: TvSourceRailKeys.BT.takeIf { input.initialMode == MediaSelectorMode.BT && hasBt }
        ?: liveWebSources.firstOrNull()?.let { TvSourceRailKeys.web(it.instanceId) }
        ?: rail.firstOrNull { it.id != TvSourceRailKeys.FILTER }?.id

    val right = when {
        railKey == null -> TvSourceRight(key = "empty")
        railKey.startsWith(TvSourceRailKeys.WEB_PREFIX) -> {
            val source = liveWebSources.firstOrNull { TvSourceRailKeys.web(it.instanceId) == railKey }
            if (source == null) TvSourceRight(key = railKey) else webPane(railKey, source, input, strings, nav)
        }

        railKey == TvSourceRailKeys.BT -> btPane(input, nav, strings)
        railKey == TvSourceRailKeys.DOWNLOADS -> downloadsPane(input, strings)
        railKey == TvSourceRailKeys.MANUAL -> manualPane(manual ?: ManualBrowsePresentation.Empty, nav, strings)
        railKey == TvSourceRailKeys.FAILED -> failedPane(failedSources, strings)
        railKey == TvSourceRailKeys.FILTER -> filterPane(input, fileSources.flatMap { it.matched }, strings)
        else -> TvSourceRight(key = railKey)
    }

    val status = if (input.sources.anyLoading) {
        strings.searching.format(input.sources.finishedSourceCount, input.sources.enabledSourceCount)
    } else {
        strings.searched.format(input.sources.enabledSourceCount)
    }
    return TvSourcePanelContent(
        status = status,
        rail = rail,
        railKey = railKey,
        right = right.copy(rows = applyFrozenOrder(right.rows, nav.frozenOrder)),
    )
}

/** 查询失败的源 (本地缓存除外): 收进左栏「查询失败」那一项. */
private fun failedSourcesOf(input: TvSourcePanelInput): List<MediaSourceResultPresentation> =
    input.sources.list.filter { it.isFailedOrAbandoned && it.kind != MediaSourceKind.LocalCache }

/** 详情弹窗里左右键能走到的一条: [railKey] 同 [TvSourceAction.ShowDetails.railKey]. */
@Immutable
data class TvSourceDetailsEntry(val railKey: String?, val rowId: String, val details: TvSourceDetails)

/** [entries] 是按 [inRail] 那一栏拼的 (弹窗刚打开、换栏时要等它跟上). */
@Immutable
data class TvSourceDetailsSequence(val inRail: Boolean, val entries: List<TvSourceDetailsEntry>) {
    /** [target] 在序列里的位置; 不在 (那一行没了 / 序列是另一栏的) 为 -1. */
    fun indexOf(target: TvSourceAction.ShowDetails): Int =
        if (target.inRail != inRail) -1 else entries.indexOfFirst { it.rowId == target.rowId && (inRail || it.railKey == target.railKey) }
}

/**
 * 详情弹窗左右键走的那一串. 左栏开的 ([inRail]): 左栏里有详情的行 (各在线源). 右栏开的: **跨数据源连成一串**, 按左栏顺序 ——
 * BT 与缓存 → 这一集的缓存 → 各在线源的线路 → 查询失败的源, 每一项里的顺序与右栏显示的一致 (眼前这一项直接取 [content] 的行,
 * 钉住的行序也算; 别的项按刚切过去时的样子拼). 手动查找是另一条路 (查的是别的源的条目), 只在它自己那一屏里走.
 */
fun buildTvSourceDetailsSequence(
    input: TvSourcePanelInput,
    strings: TvSourceStrings,
    content: TvSourcePanelContent,
    inRail: Boolean,
): TvSourceDetailsSequence {
    fun entries(railKey: String?, rows: List<TvSourceRow>) =
        rows.mapNotNull { row -> row.details?.let { TvSourceDetailsEntry(railKey, row.id, it) } }
    if (inRail) return TvSourceDetailsSequence(true, entries(null, content.rail))
    if (content.railKey == TvSourceRailKeys.MANUAL) {
        return TvSourceDetailsSequence(false, entries(TvSourceRailKeys.MANUAL, content.right.rows))
    }
    val list = content.rail.flatMap { railRow ->
        val key = railRow.id
        val rows = when {
            key == content.railKey && content.right.key == key -> content.right.rows
            key.startsWith(TvSourceRailKeys.WEB_PREFIX) -> allWebSources(input, strings)
                .firstOrNull { TvSourceRailKeys.web(it.instanceId) == key }
                ?.let { webPane(key, it, input, strings).rows }
                .orEmpty()

            key == TvSourceRailKeys.BT -> btPane(input, TvSourceNav(railKey = key), strings).rows
            key == TvSourceRailKeys.DOWNLOADS -> downloadsPane(input, strings).rows
            key == TvSourceRailKeys.FAILED -> failedPane(failedSourcesOf(input), strings).rows
            else -> emptyList()
        }
        entries(key, rows)
    }
    return TvSourceDetailsSequence(false, list)
}

private fun webRailRow(source: WebSource, playing: Boolean, input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceRow {
    val nowMillis = input.nowMillis
    val loadingEmpty = source.isLoading && source.channels.isEmpty()
    val (trailing, accent) = when {
        source.isCaptchaRequired -> strings.captchaRequired to TvSourceAccent.Attention
        source.channels.isEmpty() && !source.isLoading && !source.isRateLimited ->
            webOthersOf(source, input, strings).size.takeIf { it > 0 }?.let { strings.railOthers.format(it) to TvSourceAccent.None }
                ?: ("0" to TvSourceAccent.None)
        source.isRateLimitExpired(nowMillis) -> strings.rateLimitedExpired to TvSourceAccent.None
        source.isRateLimited -> strings.rateLimited.format(rateLimitSeconds(source.rateLimitedUntilMillis, nowMillis)) to TvSourceAccent.None
        loadingEmpty -> "" to TvSourceAccent.None
        else -> (webFilesOf(source, input, strings)?.let { partitionByFilters(it.matched, input).first.size } ?: source.channels.size)
            .toString() to TvSourceAccent.None
    }
    return TvSourceRow(
        id = TvSourceRailKeys.web(source.instanceId),
        style = TvSourceRowStyle.Rail,
        title = source.name,
        icon = if (playing) TvSourceRowIcon.Playing else TvSourceRowIcon.Source,
        iconUrl = source.iconUrl.takeIf { it.isNotBlank() },
        selected = playing,
        trailing = trailing,
        accent = accent,
        loading = loadingEmpty || source.isResolvingCaptcha,
        details = webSourceDetails(source, input, strings),
    )
}

/** 在线源的详情: 状态、线路一一列出; 「选择」= 播排在最前的那条线路 (要验证时是「完成验证」), 「下载」= 下那条线路. */
private fun webSourceDetails(source: WebSource, input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceDetails {
    val status = when {
        source.isCaptchaRequired -> strings.captchaRequired
        source.isRateLimitExpired(input.nowMillis) -> strings.rateLimitedExpiredHint
        source.isRateLimited -> strings.rateLimitedHint.format(rateLimitSeconds(source.rateLimitedUntilMillis, input.nowMillis))
        source.isLoading -> strings.statusSearching
        else -> strings.statusDone
    }
    val lines = source.channels.mapIndexed { index, channel ->
        val name = channel.name.ifBlank { strings.line.format(index + 1) }
        val meta = channel.original?.let { lineMeta(it, strings) }.orEmpty()
        if (meta.isEmpty()) name else "$name ($meta)"
    }
    val primary = when {
        source.isCaptchaRequired && source.isCaptchaSupported ->
            TvSourceButton(strings.captchaAction, TvSourceAction.ResolveCaptcha(source.instanceId), TvSourceRowIcon.Warning)

        source.channels.isEmpty() && source.isRateLimitExpired(input.nowMillis) ->
            TvSourceButton(strings.retry, TvSourceAction.RestartSource(source.instanceId), TvSourceRowIcon.Refresh)

        else -> source.channels.firstNotNullOfOrNull { it.original }?.let {
            TvSourceButton(strings.pick, TvSourceAction.Play(it), TvSourceRowIcon.Playing)
        }
    }
    return TvSourceDetails(
        title = source.name,
        subtitle = strings.kindWeb,
        fields = buildList {
            add(TvSourceDetailField(strings.labelStatus, status, short = true))
            if (lines.isNotEmpty()) add(TvSourceDetailField(strings.labelLines, lines.joinToString("\n")))
        },
        primary = primary,
        secondary = listOfNotNull(source.channels.firstNotNullOfOrNull { it.original }?.let { downloadButton(it, input, strings) }),
    )
}

/**
 * 详情里的「下载」: 点了直接开始下 (宿主的 cache, 同 Web 控制台的「缓存」). 宿主不让缓存 (缓存页自己的面板)、本身就是本地缓存、
 * 这一集已经有缓存 (下完的或正在下的, 含播 BT 时边下边播建的那份, 见 [TvSourcePanelInput.downloads]) 时不给 —— 一集只缓存一份,
 * 点了也只会被拒 (「这一集已经在下载了」).
 */
private fun downloadButton(media: Media, input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceButton? {
    if (!input.canCache || media.kind == MediaSourceKind.LocalCache) return null
    if (input.downloads.isNotEmpty()) return null
    return TvSourceButton(strings.menuCache, TvSourceAction.Cache(media), TvSourceRowIcon.Download)
}

private fun rateLimitSeconds(untilMillis: Long?, nowMillis: Long): Long =
    (((untilMillis ?: nowMillis) - nowMillis + 999) / 1000).coerceAtLeast(0)

/** 限流倒计时已走完: 到点的那次自动重试也被限流时停在这里, 不会再自动重试 (见 `MediaFetcher` 的自动重试次数). */
private fun WebSource.isRateLimitExpired(nowMillis: Long): Boolean =
    isRateLimited && rateLimitSeconds(rateLimitedUntilMillis, nowMillis) == 0L

private fun webPane(
    key: String,
    source: WebSource,
    input: TvSourcePanelInput,
    strings: TvSourceStrings,
    nav: TvSourceNav = TvSourceNav(railKey = key),
): TvSourceRight {
    val selected = input.selector.selected
    val nowMillis = input.nowMillis
    val rows = buildList {
        when {
            source.isCaptchaRequired && !source.isCaptchaSupported -> add(status("captcha", strings.captchaUnsupported))
            source.isResolvingCaptcha -> add(status("captcha", strings.resolvingCaptcha))
            source.isCaptchaRequired -> add(
                TvSourceRow(
                    id = "captcha",
                    style = TvSourceRowStyle.Line,
                    title = strings.captchaAction,
                    meta = strings.captchaHint,
                    icon = TvSourceRowIcon.Warning,
                    action = TvSourceAction.ResolveCaptcha(source.instanceId),
                ),
            )

            source.isRateLimitExpired(nowMillis) -> add(
                TvSourceRow(
                    id = "rate-limited",
                    style = TvSourceRowStyle.Line,
                    title = strings.retry,
                    meta = strings.rateLimitedExpiredHint,
                    icon = TvSourceRowIcon.Refresh,
                    action = TvSourceAction.RestartSource(source.instanceId),
                ),
            )

            source.isRateLimited -> add(
                status("rate-limited", strings.rateLimitedHint.format(rateLimitSeconds(source.rateLimitedUntilMillis, nowMillis))),
            )
        }
        val files = webFilesOf(source, input, strings)
        if (files != null) {
            // 一个文件一行 (同 BT): 对得上又符合筛选的在前; 低于偏好的、被排除的、名字对不上的直接跟在后面, 调暗并写上原因
            val (preferred, below) = partitionByFilters(files.matched, input)
            for (media in preferred) add(webFileRow(source, media, other = null, input, strings))
            for (media in below) add(webFileRow(source, media, TvWebOther(media, strings.reasonBelowPreference, excluded = false), input, strings))
            for (other in files.others) add(webFileRow(source, other.media, other, input, strings))
            if (files.matched.isEmpty() && files.others.isEmpty() && !source.isCaptchaRequired && !source.isRateLimited) {
                add(status("empty", if (source.isLoading) strings.loading else strings.noResult))
            }
            return@buildList
        }
        source.channels.forEachIndexed { index, channel ->
            val media = channel.original
            val playing = media != null && media == selected
            add(
                TvSourceRow(
                    id = "line:$index:${channel.name}",
                    style = TvSourceRowStyle.Line,
                    title = channel.name.ifBlank { strings.line.format(index + 1) },
                    meta = media?.let { lineMeta(it, strings) }.orEmpty(),
                    trailing = if (playing) strings.playing else "",
                    accent = if (playing) TvSourceAccent.Attention else TvSourceAccent.None,
                    icon = if (playing) TvSourceRowIcon.Playing else TvSourceRowIcon.None,
                    selected = playing,
                    action = media?.let { TvSourceAction.Play(it) },
                    details = media?.let { lineDetails(source, channel.name.ifBlank { strings.line.format(index + 1) }, it, input, strings) },
                ),
            )
        }
        val others = webOthersOf(source, input, strings)
        if (source.channels.isEmpty() && !source.isCaptchaRequired && !source.isRateLimited) {
            add(
                status(
                    "empty",
                    when {
                        source.isLoading -> strings.loading
                        others.isNotEmpty() -> strings.webNoExact
                        else -> strings.noResult
                    },
                ),
            )
        }
        if (others.isNotEmpty()) {
            // 有对得上的线路时其余的收在开关后面; 一条都没有时直接列出来
            val expanded = source.channels.isEmpty() || nav.showExcluded
            if (source.channels.isNotEmpty()) {
                add(
                    TvSourceRow(
                        id = "web:others-toggle",
                        style = TvSourceRowStyle.Option,
                        title = (if (nav.showExcluded) strings.hideOthers else strings.showOthers).format(others.size),
                        dividerAbove = true,
                        action = TvSourceAction.ToggleExcluded,
                    ),
                )
            }
            if (expanded) for (other in others) add(webOtherRow(source, other, input, strings))
        }
    }
    return TvSourceRight(
        key = key,
        rows = rows,
        focusId = rows.firstOrNull { it.selected }?.id,
        // 一个文件一行的源标题是文件名 (常带文件夹路径), 同 BT 用宽框
        wide = webFilesOf(source, input, strings) != null,
    )
}

/** 一个文件一行的在线源的结果: [matched] = 条目名与集数都对得上的 (按选择器的排序), [others] = 其余的 (同 [webOthersOf]). */
private class TvWebFiles(val matched: List<Media>, val others: List<TvWebOther>)

/**
 * 一个文件一行的在线源 (网盘、网盘分享搜索、添加的分享) 的结果; 线路式的源返回 null.
 *
 * 选择器给在线源的是「每条线路一个」(WebSource.channels, 按线路名去重), 线路名取资源的字幕组字段. 网盘类的源在这个字段写的是
 * 数据源名, 一个源的十几个文件撞同一个线路名 —— 去重后只剩一行, 行上也只有数据源名. 认法: 有结果的线路名就是数据源名,
 * 或对得上的结果里有两条线路名相同.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
private fun webFilesOf(source: WebSource, input: TvSourcePanelInput, strings: TvSourceStrings): TvWebFiles? {
    val matched = input.selector.filteredCandidates.mapNotNull { candidate ->
        candidate.original.takeIf {
            it.kind == MediaSourceKind.WEB && it.mediaSourceId == source.mediaSourceId && candidate.isPerfectMatch()
        }
    }
    val others = webOthersOf(source, input, strings)
    val lineIsSourceName = (matched.asSequence() + others.asSequence().map { it.media }).any { it.properties.alliance == source.name }
    val sharedLine = matched.distinctBy { it.properties.alliance }.size < matched.size
    return if (lineIsSourceName || sharedLine) TvWebFiles(matched, others) else null
}

/**
 * 一个文件一行的在线源的一行 (同 BT 的资源行): 标题是文件名, 信息行是分辨率、大小、码率 (网盘文件, 见 [DriveVideoBitrates])、日期、字幕;
 * [other] 非空 = 没列进对得上的那些, 调暗并在信息行最前面写原因 (被选择器排除的红字).
 */
private fun webFileRow(source: WebSource, media: Media, other: TvWebOther?, input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceRow {
    val playing = media == input.selector.selected
    val line = media.properties.alliance.takeIf { it.isNotBlank() && it != source.name }
    val reason = other?.reason?.takeIf { it.isNotEmpty() }
    val meta = buildList {
        reason?.let { add(it) }
        media.properties.resolution.takeIf { it.isNotBlank() }?.let { add(it) }
        formatSize(media.properties.size).takeIf { it.isNotEmpty() }?.let { add(it) }
        DriveVideoBitrates.label(media.mediaId)?.let { add(it) }
        formatDate(media.publishedTime, strings.timeZone).takeIf { it.isNotEmpty() }?.let { add(it) }
        line?.let { add(it) }
        val subtitles = media.properties.subtitleLanguageIds.map { renderSubtitleLanguage(it, strings.details) }
        if (subtitles.isNotEmpty()) add(subtitles.joinToString("/"))
    }.joinToString(" · ")
    return TvSourceRow(
        id = "file:" + media.mediaId,
        style = TvSourceRowStyle.File,
        title = media.originalTitle,
        meta = meta,
        metaAccent = if (other?.excluded == true) TvSourceAccent.Error else TvSourceAccent.None,
        icon = if (playing) TvSourceRowIcon.Playing else TvSourceRowIcon.None,
        selected = playing,
        dimmed = other != null && !playing,
        trailing = if (playing) strings.playing else "",
        accent = if (playing) TvSourceAccent.Attention else TvSourceAccent.None,
        action = TvSourceAction.Play(media),
        details = TvSourceDetails(
            title = media.originalTitle,
            subtitle = listOfNotNull(source.name, line).joinToString(" · "),
            fields = mediaFields(media, strings, sourceNames = listOf(source.name)),
            note = reason.orEmpty(),
            primary = TvSourceButton(strings.pick, TvSourceAction.Play(media), TvSourceRowIcon.Playing),
            secondary = listOfNotNull(downloadButton(media, input, strings)),
        ),
    )
}

/**
 * 在线源里没列成线路的一条结果 (同旧版选源「详细模式」能看到的): 条目名不完全一致的、被排除的 (属于别的集的不算).
 * [reason] 是给人看的原因, [excluded] = 选择器排除了它 (不只是名字对不上).
 */
@Immutable
data class TvWebOther(val media: Media, val reason: String, val excluded: Boolean)

/**
 * [source] 里没列成线路的结果. 线路只收条目名、集数都对得上的 (见 MediaSelectorState 的 isPerfectMatch), 网站换了个叫法、繁简不同、
 * 多了「第二季」之类的就进不了线路 —— 这里把它们找回来, 写上原因, 照样能选.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
internal fun webOthersOf(source: WebSource, input: TvSourcePanelInput, strings: TvSourceStrings): List<TvWebOther> =
    input.selector.filteredCandidates.mapNotNull { candidate ->
        val media = candidate.original
        if (media.kind != MediaSourceKind.WEB || media.mediaSourceId != source.mediaSourceId) return@mapNotNull null
        if (candidate.exclusionReason is MediaExclusionReason.EpisodeMismatch || candidate.isPerfectMatch()) return@mapNotNull null
        when (candidate) {
            is MaybeExcludedMedia.Excluded ->
                TvWebOther(media, strings.exclusion(BtRowExclusion.Reason(candidate.exclusionReason)), excluded = true)

            is MaybeExcludedMedia.Included -> {
                val reason = buildList {
                    if (candidate.metadata.subjectMatchKind != MatchMetadata.SubjectMatchKind.EXACT) add(strings.reasonFuzzyTitle)
                    if (candidate.metadata.episodeMatchKind < MatchMetadata.EpisodeMatchKind.EP) add(strings.reasonEpisodeUnknown)
                }.joinToString("、")
                TvWebOther(media, reason, excluded = false)
            }
        }
    }

/**
 * 左栏要列的在线源: 选择器给的 (至少有一条对得上的线路, 或还在查 / 要验证 / 限流) + 结果全都对不上、选择器藏起来的那些
 * (查询完成但线路是空的; 以「其余 N」列在后面, 右栏直接列出那些结果).
 */
private fun allWebSources(input: TvSourcePanelInput, strings: TvSourceStrings): List<WebSource> {
    val shown = input.selector.webSources
    val hidden = input.sources.list.asSequence()
        .filter { it.kind == MediaSourceKind.WEB && !it.isFailedOrAbandoned && shown.none { web -> web.instanceId == it.instanceId } }
        .map { result ->
            WebSource(
                instanceId = result.instanceId,
                mediaSourceId = result.mediaSourceId,
                iconUrl = result.info.iconUrl.orEmpty(),
                name = result.info.displayName,
                channels = emptyList(),
                isLoading = false,
                isError = false,
                isPreferred = false,
            )
        }
        .filter { webOthersOf(it, input, strings).isNotEmpty() }
        .toList()
    return shown + hidden
}

private fun webOtherRow(source: WebSource, other: TvWebOther, input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceRow {
    val media = other.media
    val playing = media == input.selector.selected
    val line = media.properties.alliance
    val meta = buildList {
        if (other.reason.isNotEmpty()) add(other.reason)
        line.takeIf { it.isNotBlank() }?.let { add(it) }
        lineMeta(media, strings).takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" · ")
    return TvSourceRow(
        id = "other:" + media.mediaId,
        style = TvSourceRowStyle.Line,
        title = media.originalTitle.ifBlank { line },
        meta = meta,
        metaAccent = if (other.excluded) TvSourceAccent.Error else TvSourceAccent.None,
        icon = if (playing) TvSourceRowIcon.Playing else TvSourceRowIcon.None,
        selected = playing,
        dimmed = !playing,
        trailing = if (playing) strings.playing else "",
        accent = if (playing) TvSourceAccent.Attention else TvSourceAccent.None,
        action = TvSourceAction.Play(media),
        details = TvSourceDetails(
            title = media.originalTitle.ifBlank { line },
            subtitle = listOf(source.name, line).filter { it.isNotBlank() }.joinToString(" · "),
            fields = mediaFields(media, strings, sourceNames = listOf(source.name)),
            note = other.reason,
            primary = TvSourceButton(strings.pick, TvSourceAction.Play(media), TvSourceRowIcon.Playing),
            secondary = listOfNotNull(downloadButton(media, input, strings)),
        ),
    )
}

/** 在线源一条线路的详情. */
private fun lineDetails(source: WebSource, line: String, media: Media, input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceDetails =
    TvSourceDetails(
        title = media.originalTitle.ifBlank { line },
        subtitle = source.name + " · " + line,
        fields = mediaFields(media, strings, sourceNames = listOf(source.name)),
        primary = TvSourceButton(strings.pick, TvSourceAction.Play(media), TvSourceRowIcon.Playing),
        secondary = listOfNotNull(downloadButton(media, input, strings)),
    )

/**
 * 一个资源能列的都列上: 类型、分辨率、字幕 (形式 + 语言)、字幕组、大小、发布日期、集数、来源、链接. 空的项不列.
 */
/**
 * 一条资源的各项信息. 取值只有几个字的六项 (类型、分辨率、字幕、大小、日期、集数) 总是都给, 缺的写「—」占住格子 —— 弹窗里排成固定的
 * 两行表格, 左右换一条时形状不变, 高度基本不跳; 字幕组、数据源、链接长短不定, 排在下面.
 */
private fun mediaFields(media: Media, strings: TvSourceStrings, sourceNames: List<String>): List<TvSourceDetailField> = buildList {
    fun cell(label: String, value: String?) = add(TvSourceDetailField(label, value?.takeIf { it.isNotBlank() } ?: MISSING_VALUE, short = true))
    val kind = when (media.kind) {
        MediaSourceKind.WEB -> strings.kindWeb
        MediaSourceKind.BitTorrent -> strings.kindBt
        MediaSourceKind.LocalCache -> strings.kindCache
    }
    val subtitle = buildList {
        media.properties.subtitleKind?.let { add(MediaDetailsRenderer.renderSubtitleKind(it, strings.details)) }
        for (id in media.properties.subtitleLanguageIds) add(renderSubtitleLanguage(id, strings.details))
    }
    cell(strings.labelKind, kind)
    cell(strings.resolution, media.properties.resolution)
    cell(strings.subtitle, subtitle.joinToString(" · "))
    cell(strings.labelSize, listOfNotNull(formatSize(media.properties.size).ifEmpty { null }, DriveVideoBitrates.label(media.mediaId)).joinToString(" · "))
    cell(strings.labelDate, formatDate(media.publishedTime, strings.timeZone))
    cell(strings.labelEpisodes, media.episodeRange?.let { renderEpisodeRange(it, strings) })
    media.properties.alliance.takeIf { it.isNotBlank() }?.let { add(TvSourceDetailField(strings.alliance, it)) }
    if (sourceNames.isNotEmpty()) add(TvSourceDetailField(strings.labelSources, sourceNames.joinToString("、")))
    media.originalUrl.takeIf { it.isNotBlank() }?.let { add(TvSourceDetailField(strings.labelLink, it, maxLines = 1)) }
}

/** 详情表格里没有的那一格. */
private const val MISSING_VALUE = "—"

/**
 * 集数给人看: 单集「05」, 连续的「01–12」, 不连续的用「、」连起来; 整季的合集 (标题里只有 BD / 全集 / 剧场版这类字样, 不知道具体哪几集)
 * 写「第 N 季」, 连第几季都不知道写「整季」. EpisodeRange.toString 是调试写法 (「5..5」「S?」), 不直接显示.
 */
internal fun renderEpisodeRange(range: EpisodeRange, strings: TvSourceStrings): String = when {
    range.isEmpty() -> ""
    range is EpisodeRange.Season -> range.numberOrNull?.let { strings.episodesSeason.format(it) } ?: strings.episodesWholeSeason
    range.isSingleEpisode() -> range.knownSorts.first().toString()
    range is EpisodeRange.Combined ->
        listOf(range.first, range.second).map { renderEpisodeRange(it, strings) }.filter { it.isNotEmpty() }.joinToString("、")

    else -> range.knownSorts.toList().let { sorts -> "${sorts.first()}–${sorts.last()}" }
}


private fun lineMeta(media: Media, strings: TvSourceStrings): String = buildList {
    media.properties.resolution.takeIf { it.isNotBlank() }?.let { add(it) }
    for (id in media.properties.subtitleLanguageIds) add(renderSubtitleLanguage(id, strings.details))
}.joinToString(" · ")

private fun status(id: String, text: String) =
    TvSourceRow(id = "status:$id", style = TvSourceRowStyle.Status, title = text)

private fun btPane(input: TvSourcePanelInput, nav: TvSourceNav, strings: TvSourceStrings): TvSourceRight {
    val bt = input.bt
    val drill = nav.drill
    if (drill is TvSourceDrill.Filter) return filterOptionsPane(drill.filter, input, strings)
    val pills = listOf(
        TvSourceRow(
            id = "pill:episode",
            style = TvSourceRowStyle.Pill,
            title = if (bt.episodeFilterEnabled) strings.episodeFilter else strings.allEpisodes,
            selected = bt.episodeFilterEnabled,
            action = TvSourceAction.ToggleEpisodeFilter,
        ),
        // 分辨率 / 字幕在左栏的「筛选」里, 对网盘类的在线源也生效 (见 TvSourcePanelFilters)
        filterPill(TvSourceFilter.Alliance, strings.alliance, bt.alliance, strings),
        filterPill(
            TvSourceFilter.Source,
            strings.source,
            bt.sourceFilter?.let { id -> input.sources.list.firstOrNull { it.mediaSourceId == id }?.info?.displayName ?: id },
            strings,
        ),
    )
    val included = groupBtRows(bt.included)
    val excluded = groupBtRows(bt.excluded)
    val rows = buildList {
        for (group in included) add(btRow(group, input, strings))
        if (excluded.isNotEmpty()) {
            add(
                TvSourceRow(
                    id = "bt:excluded-toggle",
                    style = TvSourceRowStyle.Option,
                    title = (if (nav.showExcluded) strings.hideExcluded else strings.showExcluded).format(excluded.size),
                    dividerAbove = included.isNotEmpty(),
                    action = TvSourceAction.ToggleExcluded,
                ),
            )
            if (nav.showExcluded) {
                for (group in excluded) add(btRow(group, input, strings))
            }
        }
        if (included.isEmpty() && excluded.isEmpty()) {
            val working = input.sources.btSources.any { it.isWorking }
            add(status("bt-empty", if (working || bt.isPlaceholder) strings.btLoading else strings.btEmpty))
        }
    }
    return TvSourceRight(
        key = TvSourceRailKeys.BT,
        pills = pills,
        rows = rows,
        focusId = rows.firstOrNull { it.selected }?.id,
        wide = true,
    )
}

/**
 * 「下载」的分支: 第一行去缓存页 (下别的集), 下面是这一集的每一条缓存 —— 来自哪个源、下完了没有 / 下了多少. 下完且选择器里有对应的本地缓存
 * 资源的, 确定就是播它; 还在下的确定开详情 (同 BT 里「缓存未完成」). 不受 BT 那排筛选影响.
 */
private fun downloadsPane(input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceRight {
    val rows = buildList {
        add(optionRow("downloads-open", strings.downloadsOpen, TvSourceRowIcon.Downloads, TvSourceAction.OpenDownloads))
        if (input.downloads.isEmpty()) {
            // 正在播在线源且开着边下边播: 说一句看过的部分存在本机 (那不是下载, 列不成条目)
            val diskCaching = input.playbackDiskCache && input.selector.selected?.kind == MediaSourceKind.WEB
            add(status("downloads-empty", if (diskCaching) strings.downloadsEmptyDiskCache else strings.downloadsEmpty))
        }
        for (item in input.downloads) add(downloadRow(item, input, strings))
    }
    return TvSourceRight(
        key = TvSourceRailKeys.DOWNLOADS,
        rows = rows,
        focusId = rows.firstOrNull { it.selected }?.id,
        wide = true,
    )
}

private fun downloadRow(item: TvSourceCacheItem, input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceRow {
    val origin = item.origin
    // 选择器里能选的 (没被排除的) 本地缓存资源: 先按缓存 id 认 (同一资源两个引擎各存一份时 mediaId 一样), 认不出再按来源资源
    val cachedCandidates = input.selector.filteredCandidates.mapNotNull { it.result as? CachedMedia }
    val playable = if (!item.finished) {
        null
    } else {
        cachedCandidates.firstOrNull { it.cacheProperties?.cacheId == item.cacheId }
            ?: cachedCandidates.firstOrNull { it.origin.mediaId == origin.mediaId }
    }
    val selected = playable != null && input.selector.selected?.mediaId == playable.mediaId
    val source = sourceName(origin.mediaSourceId, input.sources)
    val status = if (item.finished) strings.cached else strings.downloadCaching.format("${item.percent}%")
    val meta = buildList {
        add(status)
        origin.properties.resolution.takeIf { it.isNotBlank() }?.let { add(it) }
        formatSize(origin.properties.size).takeIf { it.isNotEmpty() }?.let { add(it) }
        add(source)
    }.joinToString(" · ")
    val id = "dl:" + item.cacheId
    return TvSourceRow(
        id = id,
        style = TvSourceRowStyle.Resource,
        title = origin.originalTitle,
        meta = meta,
        icon = if (selected) TvSourceRowIcon.Playing else TvSourceRowIcon.Download,
        selected = selected,
        dimmed = !item.finished,
        trailing = if (selected) strings.playing else "",
        accent = if (selected) TvSourceAccent.Attention else TvSourceAccent.None,
        action = playable?.let { TvSourceAction.Play(it) } ?: TvSourceAction.ShowDetails(id, inRail = false, railKey = TvSourceRailKeys.DOWNLOADS),
        details = TvSourceDetails(
            title = origin.originalTitle,
            subtitle = status,
            fields = mediaFields(origin, strings, sourceNames = listOf(source)),
            primary = playable?.let { TvSourceButton(strings.pick, TvSourceAction.Play(it), TvSourceRowIcon.Playing) },
        ),
    )
}

private fun filterPill(filter: TvSourceFilter, label: String, value: String?, strings: TvSourceStrings) = TvSourceRow(
    id = "pill:" + filter.name,
    style = TvSourceRowStyle.Pill,
    title = if (value == null) label else "$label · $value",
    selected = value != null,
    action = TvSourceAction.Open(TvSourceDrill.Filter(filter)),
)

private fun btRow(group: TvBtGroup, input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceRow {
    val row = group.first
    val media = row.media
    val exclusion = group.rows.firstOrNull { it.exclusion != null }?.exclusion?.takeIf { group.rows.all { r -> r.isExcluded } }
    // 一行放不下时尾巴被省略: 短而有用的 (分辨率、大小、合集、来源) 排前面, 字幕语言名字长 (英文界面尤其), 放后面
    val meta = buildList {
        if (row.isCached) add(strings.cached)
        media.properties.resolution.takeIf { it.isNotBlank() }?.let { add(it) }
        formatSize(media.properties.size).takeIf { it.isNotEmpty() }?.let { add(it) }
        if (media.episodeRange?.isSingleEpisode() == false) add(strings.pack)
        val sources = group.sourceIds
        if (sources.size > 1) {
            add(strings.sourcesCount.format(sources.size))
        } else if (media.kind != MediaSourceKind.LocalCache) {
            add(sourceName(media.mediaSourceId, input.sources))
        }
        formatDate(media.publishedTime, strings.timeZone).takeIf { it.isNotEmpty() }?.let { add(it) }
        val subtitles = media.properties.subtitleLanguageIds.map { renderSubtitleLanguage(it, strings.details) }
        if (subtitles.isNotEmpty()) add(subtitles.joinToString("/"))
        MediaDetailsRenderer.renderSubtitleKind(media.properties.subtitleKind, strings.details)?.let { add(it) }
    }.joinToString(" · ")
    val selected = group.isSelected
    val id = "bt:" + group.key
    val cacheNotReady = exclusion is BtRowExclusion.Reason && exclusion.reason == MediaExclusionReason.CacheNotReady
    return TvSourceRow(
        id = id,
        style = TvSourceRowStyle.Resource,
        title = media.originalTitle,
        meta = if (exclusion != null) strings.exclusion(exclusion) + " · " + meta else meta,
        metaAccent = if (exclusion != null) TvSourceAccent.Error else TvSourceAccent.None,
        icon = when {
            selected -> TvSourceRowIcon.Playing
            row.isCached -> TvSourceRowIcon.Download
            else -> TvSourceRowIcon.None
        },
        selected = selected,
        dimmed = exclusion != null,
        trailing = if (selected) strings.playing else "",
        accent = if (selected) TvSourceAccent.Attention else TvSourceAccent.None,
        // 「缓存未完成」的资源选不了 (播放器拿不到完整文件), 确定也是开详情
        action = if (cacheNotReady) TvSourceAction.ShowDetails(id, inRail = false, railKey = TvSourceRailKeys.BT) else TvSourceAction.Play(media),
        details = TvSourceDetails(
            title = media.originalTitle,
            subtitle = if (row.isCached) strings.cached else "",
            fields = mediaFields(media, strings, sourceNames = group.rows.map { sourceName(it.media.mediaSourceId, input.sources) }.distinct()),
            note = exclusion?.let { strings.exclusion(it) }.orEmpty(),
            primary = if (cacheNotReady) null else TvSourceButton(strings.pick, TvSourceAction.Play(media), TvSourceRowIcon.Playing),
            secondary = buildList {
                if (!row.isCached) downloadButton(media, input, strings)?.let { add(it) }
                media.properties.alliance.takeIf { it.isNotBlank() }?.let { alliance ->
                    add(TvSourceButton(strings.menuExclude.format(alliance), TvSourceAction.ExcludeAlliance(alliance), TvSourceRowIcon.Block))
                }
            },
        ),
    )
}

private fun sourceName(mediaSourceId: String, sources: MediaSourceResultListPresentation): String =
    sources.list.firstOrNull { it.mediaSourceId == mediaSourceId }?.info?.displayName ?: mediaSourceId

private fun filterOptionsPane(filter: TvSourceFilter, input: TvSourcePanelInput, strings: TvSourceStrings): TvSourceRight {
    val bt = input.bt
    val (title, current, values) = when (filter) {
        TvSourceFilter.Resolution -> Triple(strings.resolution, bt.resolution, bt.availableResolutions.map { it to it })
        TvSourceFilter.Subtitle -> Triple(
            strings.subtitle,
            bt.subtitleLanguageId,
            bt.availableSubtitleLanguageIds.map { it to renderSubtitleLanguage(it, strings.details) },
        )

        TvSourceFilter.Alliance -> Triple(strings.alliance, bt.alliance, bt.availableAlliances.map { it to it })
        TvSourceFilter.Source -> Triple(
            strings.source,
            bt.sourceFilter,
            input.sources.btSources.map { it.mediaSourceId to it.info.displayName },
        )
    }
    val rows = buildList {
        add(
            TvSourceRow(
                id = "opt:all",
                style = TvSourceRowStyle.Option,
                title = strings.all,
                trailing = if (filter == TvSourceFilter.Source) bt.totalCount.toString() else "",
                icon = if (current == null) TvSourceRowIcon.Check else TvSourceRowIcon.None,
                selected = current == null,
                action = TvSourceAction.PickFilter(filter, null),
            ),
        )
        for ((value, label) in values) {
            val source = if (filter == TvSourceFilter.Source) input.sources.btSources.firstOrNull { it.mediaSourceId == value } else null
            add(
                TvSourceRow(
                    id = "opt:$value",
                    style = TvSourceRowStyle.Option,
                    title = label,
                    trailing = source?.let { sourceStatus(it, bt.sourceCounts[value] ?: 0, strings) }.orEmpty(),
                    loading = source?.isWorking == true,
                    dimmed = source != null && (source.isDisabled || source.isFailedOrAbandoned),
                    icon = if (value == current) TvSourceRowIcon.Check else TvSourceRowIcon.None,
                    selected = value == current,
                    action = TvSourceAction.PickFilter(filter, value),
                ),
            )
        }
    }
    return TvSourceRight(
        key = TvSourceRailKeys.BT + "/filter/" + filter.name,
        title = title,
        rows = rows,
        focusId = rows.firstOrNull { it.selected }?.id,
    )
}

private fun sourceStatus(source: MediaSourceResultPresentation, count: Int, strings: TvSourceStrings): String = when {
    source.isDisabled -> strings.disabled
    source.isFailedOrAbandoned -> strings.failedState
    source.isWorking -> ""
    else -> count.toString()
}

private fun failedPane(failed: List<MediaSourceResultPresentation>, strings: TvSourceStrings): TvSourceRight = TvSourceRight(
    key = TvSourceRailKeys.FAILED,
    rows = failed.map { source ->
        TvSourceRow(
            id = "failed:" + source.instanceId,
            style = TvSourceRowStyle.Line,
            title = source.info.displayName,
            meta = strings.retryHint,
            icon = TvSourceRowIcon.Source,
            iconUrl = source.info.iconUrl,
            loading = source.isWorking,
            dimmed = true,
            action = TvSourceAction.RestartSource(source.instanceId),
            details = TvSourceDetails(
                title = source.info.displayName,
                fields = buildList {
                    add(TvSourceDetailField(strings.labelStatus, strings.failedState, short = true))
                    (source.state as? MediaSourceFetchState.Failed)?.cause?.let { cause ->
                        add(TvSourceDetailField(strings.labelError, cause.message?.takeIf { it.isNotBlank() } ?: cause::class.simpleName.orEmpty()))
                    }
                    source.info.websiteUrl?.takeIf { it.isNotBlank() }?.let { add(TvSourceDetailField(strings.labelLink, it, maxLines = 1)) }
                },
                primary = TvSourceButton(strings.retry, TvSourceAction.RestartSource(source.instanceId), TvSourceRowIcon.Refresh),
            ),
        )
    },
)

private fun manualPane(manual: ManualBrowsePresentation, nav: TvSourceNav, strings: TvSourceStrings): TvSourceRight {
    if (!manual.isPlaceholder && manual.sources.isEmpty()) {
        return TvSourceRight(key = TvSourceRailKeys.MANUAL, rows = listOf(status("no-sources", strings.manualNoSources)))
    }
    when (nav.drill) {
        TvSourceDrill.ManualSources -> {
            val rows = manual.sources.map { source ->
                val current = source.instanceId == manual.selectedSourceId
                TvSourceRow(
                    id = "msrc:" + source.instanceId,
                    style = TvSourceRowStyle.Option,
                    title = source.info.displayName,
                    icon = if (current) TvSourceRowIcon.Check else TvSourceRowIcon.None,
                    selected = current,
                    action = TvSourceAction.ManualSelectSource(source.instanceId),
                )
            }
            return TvSourceRight(
                key = TvSourceRailKeys.MANUAL + "/sources",
                title = strings.manualSource.format("").trimEnd(' ', '·', ':', '：'),
                rows = rows,
                focusId = rows.firstOrNull { it.selected }?.id,
            )
        }

        TvSourceDrill.ManualChannels -> {
            val channels = (manual.channels as? ManualLoadState.Success)?.value.orEmpty()
            val rows = channels.mapIndexed { index, channel ->
                val current = index == manual.selectedChannelIndex
                TvSourceRow(
                    id = "mch:$index",
                    style = TvSourceRowStyle.Option,
                    title = channel.label?.takeIf { it.isNotBlank() } ?: strings.line.format(index + 1),
                    trailing = channel.episodes.size.toString(),
                    icon = if (current) TvSourceRowIcon.Check else TvSourceRowIcon.None,
                    selected = current,
                    action = TvSourceAction.ManualSelectChannel(index),
                )
            }
            return TvSourceRight(
                key = TvSourceRailKeys.MANUAL + "/channels",
                title = strings.manualChannel.format("").trimEnd(' ', '·', ':', '：'),
                rows = rows,
                focusId = rows.firstOrNull { it.selected }?.id,
            )
        }

        else -> {}
    }
    val opened = manual.openedSubject
    if (opened == null) {
        val pills = listOf(
            TvSourceRow(
                id = "mpill:source",
                style = TvSourceRowStyle.Pill,
                title = strings.manualSource.format(manual.selectedSource?.info?.displayName.orEmpty()),
                action = TvSourceAction.Open(TvSourceDrill.ManualSources),
            ),
            TvSourceRow(
                id = "mpill:keyword",
                style = TvSourceRowStyle.Pill,
                title = strings.manualKeyword.format(manual.keyword),
                action = TvSourceAction.ManualEditKeyword,
            ),
        )
        val rows = when (val results = manual.results) {
            ManualLoadState.Idle, ManualLoadState.Loading -> listOf(status("searching", strings.manualSearching))
            is ManualLoadState.Failed -> if (results.captchaUnsupported) {
                listOf(status("failed", strings.captchaUnsupported))
            } else {
                listOf(
                    status("failed", strings.manualFailed),
                    optionRow("retry", strings.retry, TvSourceRowIcon.Refresh, TvSourceAction.ManualRetry),
                )
            }

            is ManualLoadState.Success -> if (results.value.isEmpty()) {
                listOf(status("empty", strings.manualEmpty))
            } else {
                results.value.mapIndexed { index, subject ->
                    TvSourceRow(
                        id = "msub:$index:${subject.name}",
                        style = TvSourceRowStyle.Option,
                        title = subject.name,
                        action = TvSourceAction.ManualOpenSubject(subject),
                        details = TvSourceDetails(
                            title = subject.name,
                            fields = buildList {
                                manual.selectedSource?.info?.displayName?.let { add(TvSourceDetailField(strings.labelSource, it, short = true)) }
                                add(TvSourceDetailField(strings.labelLink, subject.url, maxLines = 1))
                            },
                            primary = TvSourceButton(strings.open, TvSourceAction.ManualOpenSubject(subject), TvSourceRowIcon.Search),
                        ),
                    )
                }
            }
        }
        return TvSourceRight(key = TvSourceRailKeys.MANUAL + "/search/" + manual.selectedSourceId, pills = pills, rows = rows)
    }

    val channels = manual.channels
    val channel = manual.selectedChannel
    val pills = buildList {
        add(
            TvSourceRow(
                id = "mpill:back",
                style = TvSourceRowStyle.Pill,
                title = strings.manualBack,
                action = TvSourceAction.ManualCloseSubject,
            ),
        )
        if (channels is ManualLoadState.Success && channels.value.size > 1) {
            add(
                TvSourceRow(
                    id = "mpill:channel",
                    style = TvSourceRowStyle.Pill,
                    title = strings.manualChannel.format(channel?.label?.takeIf { it.isNotBlank() } ?: strings.line.format(manual.selectedChannelIndex + 1)),
                    action = TvSourceAction.Open(TvSourceDrill.ManualChannels),
                ),
            )
        }
        add(
            TvSourceRow(
                id = "mpill:remember",
                style = TvSourceRowStyle.Pill,
                title = strings.manualRemember + " · " + (if (manual.rememberSelection) strings.on else strings.off),
                selected = manual.rememberSelection,
                action = TvSourceAction.ManualSetRemember(!manual.rememberSelection),
            ),
        )
    }
    val rows = when (channels) {
        ManualLoadState.Idle, ManualLoadState.Loading -> listOf(status("loading", strings.manualLoadingEpisodes))
        is ManualLoadState.Failed -> listOf(
            status("failed", if (channels.captchaUnsupported) strings.captchaUnsupported else strings.manualFailed),
            optionRow("retry", strings.retry, TvSourceRowIcon.Refresh, TvSourceAction.ManualRetry),
        )

        is ManualLoadState.Success -> {
            val episodes = channel?.episodes.orEmpty()
            if (episodes.isEmpty()) {
                listOf(status("empty", strings.manualNoEpisodes))
            } else {
                episodes.mapIndexed { index, episode ->
                    val current = index == manual.selectedEpisodeIndex
                    TvSourceRow(
                        id = "mep:$index",
                        style = TvSourceRowStyle.Cell,
                        title = episode.name.ifBlank { (index + 1).toString() },
                        selected = current,
                        icon = if (current) TvSourceRowIcon.Check else TvSourceRowIcon.None,
                        action = TvSourceAction.ManualPlay(index),
                        details = TvSourceDetails(
                            title = episode.name.ifBlank { (index + 1).toString() },
                            subtitle = opened.name,
                            fields = buildList {
                                val line = channel?.label?.takeIf { it.isNotBlank() } ?: strings.line.format(manual.selectedChannelIndex + 1)
                                add(TvSourceDetailField(strings.labelLines, line, short = true))
                                episode.episodeSort?.let { add(TvSourceDetailField(strings.labelEpisode, it.toString(), short = true)) }
                                add(TvSourceDetailField(strings.labelLink, episode.url, maxLines = 1))
                            },
                            primary = TvSourceButton(strings.pick, TvSourceAction.ManualPlay(index), TvSourceRowIcon.Playing),
                        ),
                    )
                }
            }
        }
    }
    val cells = rows.all { it.style == TvSourceRowStyle.Cell }
    return TvSourceRight(
        key = TvSourceRailKeys.MANUAL + "/subject/" + opened.url + "/" + manual.selectedChannelIndex,
        title = opened.name,
        pills = pills,
        rows = rows,
        columns = if (cells) TV_SOURCE_EPISODE_COLUMNS else 1,
        focusId = rows.firstOrNull { it.selected }?.id,
    )
}

/** 手动查找的剧集方块一行几个. */
internal const val TV_SOURCE_EPISODE_COLUMNS = 4

private fun formatSize(size: FileSize): String =
    if (size == FileSize.Zero || size == FileSize.Unspecified) "" else size.toString()

private fun formatDate(millis: Long, timeZone: TimeZone): String {
    if (millis == 0L) return ""
    val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
    return "%02d-%02d".format(date.month.ordinal + 1, date.day)
}

private fun optionRow(id: String, title: String, icon: TvSourceRowIcon, action: TvSourceAction) =
    TvSourceRow(id = "option:$id", style = TvSourceRowStyle.Option, title = title, icon = icon, action = action)
