/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.blocksSelection
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.media_selector_item_cache_not_ready
import me.him188.ani.app.ui.lang.media_selector_item_no_subtitle
import me.him188.ani.app.ui.lang.media_selector_item_season_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_single_episode_resource
import me.him188.ani.app.ui.lang.media_selector_item_subject_title_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_unsupported_playback
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSourceResultPresentation
import me.him188.ani.app.ui.mediafetch.request.toEditingMediaFetchRequest
import me.him188.ani.app.ui.mediafetch.request.toMediaFetchRequestOrNull
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodePresentation
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.getString
import org.openani.mediamp.togglePlayWhenReady
import kotlin.reflect.KClass

/**
 * 播放页登记给 [TvRemoteControl] 的把手: 手机网页「播放器」标签的读写都经它.
 *
 * - **读** ([stateJson]) 在 HTTP 线程上跑, 只读这里缓存的最新快照 ([page] / [presentation]) 与播放器的
 *   StateFlow, 不碰组合.
 * - **写** 一律投到 [uiScope] (播放页组合的 scope, 主线程) 执行: 播放器必须在主线程上调 (跨线程调 ExoPlayer
 *   就是「显示 1.25 倍实际原速」那个 bug 的真因), 选源也与电视上点选走同一条路.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
internal class RemotePlayerHandle(
    val vm: EpisodeViewModel,
    private val uiScope: CoroutineScope,
    /** true = 保留会话在后台 (播放页不在组合里): 可换源 / 改查询请求, 不给播放控制 (见 [TvRemoteControl]). */
    val background: Boolean,
) {
    @Volatile
    var page: EpisodePageState? = null

    /** 数据源选择器的最新呈现 (候选 / 当前选中). 由 [RegisterTvRemotePlayer] 持续收集. */
    @Volatile
    var presentation: MediaSelectorState.Presentation? = null

    fun stateJson(filter: RemoteMediaFilter = RemoteMediaFilter.None): JsonObject {
        val page = page
        val pres = presentation
        val selected = pres?.selected
        return buildJsonObject {
            put("available", true)
            put("background", background)
            if (page != null) {
                put("title", page.subjectPresentation.title)
                val ep = page.episodePresentation
                put("episode", listOf("第 ${ep.sort} 话", ep.title).filter { it.isNotBlank() }.joinToString("  "))
            }
            put("selectedId", selected?.mediaId)
            put("selectedTitle", selected?.originalTitle)

            // 选集: 与电视上选集侧边栏同一份列表 (EpisodeSelectorState.items), 当前集由页面状态给出
            val currentEpisodeId = page?.episodePresentation?.episodeId
            putJsonArray("episodes") {
                for (ep in vm.episodeSelectorState.items) addJsonObject {
                    put("id", ep.episodeId)
                    put("label", episodeLabel(ep))
                    put("current", ep.episodeId == currentEpisodeId)
                }
            }

            // 当前查询条件 (「编辑查询请求」那几项), 手机上的表单用它预填
            val fetchRequest = page?.fetchRequest
            if (fetchRequest != null) {
                val editing = fetchRequest.toEditingMediaFetchRequest()
                putJsonObject("request") {
                    put("primary", editing.primaryName)
                    putJsonArray("others") { editing.complementaryNames.forEach { add(it) } }
                    put("sort", editing.episodeSort)
                    put("ep", editing.episodeEp)
                }
                put("requestIsDefault", fetchRequest.subjectNames == page.defaultFetchRequest?.subjectNames)
            }

            val sources = page?.mediaSourceResultListPresentation?.list.orEmpty()
            put("loading", sources.any { it.isWorking })
            putJsonArray("sources") {
                for (source in sources) {
                    // 本地缓存源是内部的, 用户无感 (与电视上的数据源列表一致)
                    if (source.kind == MediaSourceKind.LocalCache || source.isDisabled) continue
                    addJsonObject {
                        put("id", source.mediaSourceId)
                        put("name", source.info.displayName)
                        put("state", source.stateLabel())
                        put("count", source.totalCount)
                    }
                }
            }

            // 候选: 默认只列选择规则放行的 (Included); 勾了「显示被排除的」再加上 Excluded (附原因, 排在放行的后面).
            // 下拉筛选在截断之前做 —— 每个源最多 MAX_ITEMS_PER_SOURCE 条是筛选**之后**的, 不会漏掉符合条件的.
            // 下拉的选项与计数按「当前范围」(是否含被排除的) 算, 不受下拉本身影响, 选了一项其余选项不会消失.
            val all = pres?.filteredCandidates.orEmpty()
            put("excludedCount", all.count { it is MaybeExcludedMedia.Excluded })
            val scope = if (filter.showExcluded) all else all.filter { it is MaybeExcludedMedia.Included }
            putJsonObject("filters") {
                putOptions("resolution", scope.map { it.original.properties.resolution }, { it }, ::resolutionRank)
                putOptions("subtitle", scope.flatMap { it.original.properties.subtitleLanguageIds }, ::subtitleLabel)
                putOptions("alliance", scope.map { it.original.properties.alliance }, { it })
            }
            val candidates = scope.filter { filter.accepts(it.original) }
                .sortedBy { if (it is MaybeExcludedMedia.Included) 0 else 1 } // 稳定排序: 各自保持原顺序
            val reasonTexts = if (candidates.any { it.exclusionReason != null }) loadReasonTexts() else emptyMap()

            // 按数据源分组, 顺序跟数据源列表一致; 缓存 (已下载好的) 若有, 放最前面
            val byId = candidates.groupBy { it.original.mediaSourceId }
            val order = buildList {
                byId.keys.filter { id -> byId.getValue(id).first().original.kind == MediaSourceKind.LocalCache }
                    .let { addAll(it) }
                sources.map { it.mediaSourceId }.filter { it in byId && it !in this }.let { addAll(it) }
                byId.keys.filter { it !in this }.let { addAll(it) }
            }
            putJsonArray("groups") {
                for (sourceId in order) {
                    val list = byId[sourceId].orEmpty()
                    if (list.isEmpty()) continue
                    // 选中项必须在列表里 (否则手机上看不到"正在播放"那一条): 截断时把它挪到最前
                    val selectedEntry = list.firstOrNull { it.original == selected }
                    // 网页上对这个源勾了「显示全部」: 不截断
                    val cap = if (sourceId == filter.fullSource) Int.MAX_VALUE else MAX_ITEMS_PER_SOURCE
                    val shown = if (list.size <= cap) list else {
                        val head = list.take(cap)
                        if (selectedEntry != null && selectedEntry !in head) listOf(selectedEntry) + head.dropLast(1) else head
                    }
                    addJsonObject {
                        put("id", sourceId)
                        put("name", sourceName(sourceId, list.first().original, sources))
                        put("total", list.size)
                        put("more", list.size - shown.size)
                        putJsonArray("items") {
                            for (entry in shown) addJsonObject {
                                putMedia(entry.original)
                                entry.exclusionReason?.let { reason ->
                                    put("excluded", true)
                                    put("reason", reasonTexts[reason::class] ?: reason.toString())
                                    // 硬性不可用 (缓存还没下完) 电视上也不许选; 其余被排除的都能手动选
                                    put("blocked", reason.blocksSelection)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 选中 [mediaId] 对应的候选 (含被排除的). 只在当前候选里找 (手机上的列表可能已过时).
     * 与电视上在数据源弹窗里点选同一条路 ([MediaSelectorState.select]): 换源、记住分辨率 / 字幕组等偏好.
     * 被排除的也能选 (电视上同样可以), 只有硬性不可用的那档 ([blocksSelection], 缓存还没下完) 不行.
     * @return 出错时的提示文案; 成功为 null.
     */
    fun select(mediaId: String): String? {
        val page = page ?: return "电视当前不在播放页"
        val entry = presentation?.filteredCandidates.orEmpty().firstOrNull { it.original.mediaId == mediaId }
            ?: return "这个数据源已不在列表里，请刷新"
        if (entry.exclusionReason?.blocksSelection == true) return "这个资源现在不能播放（缓存还没下完）"
        uiScope.launch { page.mediaSelectorState.select(entry.original) }
        return null
    }

    /**
     * 换集: 与电视上选集条 / 选集侧边栏同一条路 ([EpisodeSelectorState.selectEpisodeId], 就地换集不导航).
     * 后台会话也能换 (照样加载, 只是被按住暂停); 保留会话记的「当前集」取自页面状态, 会跟着变.
     * @return 出错时的提示文案; 成功为 null.
     */
    fun switchEpisode(episodeId: Int): String? {
        val state = vm.episodeSelectorState
        if (page?.episodePresentation?.episodeId == episodeId) return null
        if (state.items.none { it.episodeId == episodeId }) return "这一集不在列表里，请刷新"
        uiScope.launch { state.selectEpisodeId(episodeId) }
        return null
    }

    /**
     * 改查询条件 (同电视上「编辑查询请求」的「保存并刷新」): 重启所有数据源的搜索, 条目名按条目记住
     * (见 [EpisodeViewModel.updateFetchRequest]). 校验与电视上的编辑框一致: 主搜索名不能空, 两种集数至少填一个.
     * @return 出错时的提示文案; 成功为 null.
     */
    fun updateRequest(primary: String, others: List<String>, sort: String, ep: String): String? {
        val page = page ?: return "电视当前不在播放页"
        val current = page.fetchRequest ?: return "数据源还在加载，请稍后再试"
        if (primary.isBlank()) return "主搜索名不能为空"
        if (sort.isBlank() && ep.isBlank()) return "两种集数至少要填一个"
        val request = current.toEditingMediaFetchRequest().copy(
            primaryName = primary.trim(),
            complementaryNames = others.map { it.trim() }.filter { it.isNotEmpty() },
            episodeSort = sort.trim(),
            episodeEp = ep.trim(),
        ).toMediaFetchRequestOrNull() ?: return "请求无效，请检查"
        uiScope.launch { vm.updateFetchRequest(request) }
        return null
    }

    /** 恢复默认查询条件 (Bangumi 名称与本集原本的集数); 同时清掉为本条目记住的搜索名. */
    fun resetRequest(): String? {
        val page = page ?: return "电视当前不在播放页"
        val default = page.defaultFetchRequest ?: return "数据源还在加载，请稍后再试"
        uiScope.launch { vm.updateFetchRequest(default) }
        return null
    }

    /**
     * 播放状态 (是否在播 / 位置 / 总长). 与 [stateJson] 分开: 位置每秒都变, 放进候选那份里的话版本号每秒都换,
     * 几百条候选就得每秒整份重发. 服务端每次轮询都附上这一小份, 不参与版本号.
     */
    fun playbackJson(): JsonObject = buildJsonObject {
        val player = vm.player
        put("playing", player.state.value.isPlaying)
        put("position", player.currentPositionMillis.value)
        put("duration", player.mediaProperties.value?.durationMillis ?: 0L)
    }

    /** 播放控制; 在播放页的主线程 scope 上执行. 未知动作返回 false. */
    fun control(action: String): Boolean {
        val run: (() -> Unit) = when (action) {
            // 与电视上的播放暂停键同一个动作 (按播放意图切, 缓冲中按一下是"暂停")
            "toggle" -> { { vm.player.togglePlayWhenReady() } }
            "back" -> { { vm.player.skip(-SEEK_STEP_MILLIS) } }
            "forward" -> { { vm.player.skip(SEEK_STEP_MILLIS) } }
            else -> return false
        }
        uiScope.launch { run() }
        return true
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putMedia(media: Media) {
        val props = media.properties
        put("id", media.mediaId)
        put("title", media.originalTitle)
        put("resolution", props.resolution.takeIf { it.isNotBlank() })
        put("subtitles", props.subtitleLanguageIds.joinToString("/") { subtitleLabel(it) }.takeIf { it.isNotBlank() })
        put("alliance", props.alliance.takeIf { it.isNotBlank() })
        put("size", props.size.takeIf { it.inBytes > 0 }?.toString())
        put("cached", media.kind == MediaSourceKind.LocalCache)
    }

    /** 一个下拉框的选项: 取值 / 显示文字 / 条数; 空值不列. [rank] 非 null 时按它从高到低排, 否则按条数. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putOptions(
        key: String,
        values: List<String>,
        label: (String) -> String,
        rank: ((String) -> Int)? = null,
    ) {
        val counts = values.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val ordered = if (rank != null) {
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { rank(it.key) }.thenByDescending { it.value })
        } else {
            counts.entries.sortedByDescending { it.value }
        }
        putJsonArray(key) {
            for ((value, count) in ordered) addJsonObject {
                put("value", value)
                put("label", label(value))
                put("count", count)
            }
        }
    }

    /** 分辨率按清晰度排: 4K/2160 最高, 其余取数字部分 (1080P -> 1080). */
    private fun resolutionRank(value: String): Int {
        val v = value.uppercase()
        if ("4K" in v || "2160" in v) return 2160
        return v.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
    }

    /** 排除原因的文字, 与电视上选择器卡片的一致 (MediaSelectorItem 的 mediaExclusionReasonText). */
    private fun loadReasonTexts(): Map<KClass<out MediaExclusionReason>, String> = runBlocking {
        mapOf(
            MediaExclusionReason.MediaWithoutSubtitle::class to getString(Lang.media_selector_item_no_subtitle),
            MediaExclusionReason.SingleEpisodeForCompleteSubject::class to
                    getString(Lang.media_selector_item_single_episode_resource),
            MediaExclusionReason.UnsupportedByPlatformPlayer::class to
                    getString(Lang.media_selector_item_unsupported_playback),
            MediaExclusionReason.FromSequelSeason::class to getString(Lang.media_selector_item_season_mismatch),
            MediaExclusionReason.FromSeriesSeason::class to getString(Lang.media_selector_item_season_mismatch),
            MediaExclusionReason.SubjectNameMismatch::class to getString(Lang.media_selector_item_subject_title_mismatch),
            MediaExclusionReason.CacheNotReady::class to getString(Lang.media_selector_item_cache_not_ready),
        )
    }

    /** 下拉框里的一集: 集号 + 标题 (同电视选集侧边栏); 看过的前面打勾, 确定还没播出的标出来. */
    private fun episodeLabel(ep: EpisodePresentation): String = buildString {
        if (ep.collectionType == UnifiedCollectionType.DONE) append("✓ ")
        append(ep.sort)
        if (ep.title.isNotBlank()) append("  ").append(ep.title)
        if (ep.isKnownNotYetAired) append("（未播出）")
    }

    private fun sourceName(sourceId: String, sample: Media, sources: List<MediaSourceResultPresentation>): String =
        when {
            sample.kind == MediaSourceKind.LocalCache -> "本地缓存"
            else -> sources.firstOrNull { it.mediaSourceId == sourceId }?.info?.displayName ?: sourceId
        }

    private fun MediaSourceResultPresentation.stateLabel(): String = when {
        isWorking -> "loading"
        isCaptchaRequired -> "captcha"
        isRateLimited -> "limited"
        isFailedOrAbandoned -> "failed"
        else -> "done"
    }

    private fun subtitleLabel(id: String): String = when (id.uppercase()) {
        "CHS" -> "简中"
        "CHT" -> "繁中"
        "JPN", "JP" -> "日语"
        "ENG", "EN" -> "英语"
        else -> id
    }

    private companion object {
        /** 手机上「后退 / 前进」一次跳多少. */
        const val SEEK_STEP_MILLIS = 10_000L

        /** 每个数据源最多列这么多条; BT 源一搜几百条, 手机上翻不完也没意义, 其余让用户在电视上看. */
        const val MAX_ITEMS_PER_SOURCE = 40
    }
}

/**
 * 播放页在组合里时把自己登记为 [TvRemoteControl] 的当前播放器, 离开组合即注销.
 *
 * 播放页登记的是**前台**把手; 播放页不在时, 保留会话的后台把手 ([RegisterTvRemoteBackgroundPlayer]) 顶上.
 * 两者都在时前台优先 —— 前台才有播放控制.
 */
@Composable
fun RegisterTvRemotePlayer(vm: EpisodeViewModel, page: EpisodePageState, background: Boolean = false) {
    val scope = rememberCoroutineScope()
    val handle = remember(vm, background) { RemotePlayerHandle(vm, scope, background) }
    SideEffect { handle.page = page }
    val selectorState = page.mediaSelectorState
    LaunchedEffect(handle, selectorState) {
        // presentationFlow 是 WhileSubscribed 的: 电视上数据源弹窗没开时没人订阅, 这里订着让它保持最新
        selectorState.presentationFlow.collect { handle.presentation = it }
    }
    DisposableEffect(handle) {
        TvRemoteControl.registerPlayer(handle)
        onDispose { TvRemoteControl.unregisterPlayer(handle) }
    }
}

/**
 * 保留播放会话在后台时 (播放页不在组合里) 由 TV 根组合调用: 把后台会话登记为 [TvRemoteControl] 的后台播放器,
 * 手机上照样能看候选、换源、改查询请求. 后台会话照常搜源解析 (Web 解析器由保留会话挂在应用根部), 只是被按住
 * 暂停, 所以新数据源静音加载, 回到播放器接着播. 播放页在前台时它自己的前台登记优先.
 */
@Composable
fun RegisterTvRemoteBackgroundPlayer(vm: EpisodeViewModel) {
    val page by vm.pageState.collectAsStateWithLifecycle()
    page?.let { RegisterTvRemotePlayer(vm, it, background = true) }
}

/**
 * 手机网页上的下拉筛选 (分辨率 / 字幕 / 字幕组) 与「显示被排除的」.
 *
 * **只筛手机上的列表, 不动电视上的偏好**: 电视选择器那排筛选是「偏好」, 会被记住并影响以后的自动选源;
 * 而且点选任何一个源都会把「数据源」偏好设成它 —— 手机列表若跟着偏好走, 点一次就只剩那一个源了.
 * 真正点选某个资源时仍然走 [MediaSelectorState.select], 偏好照电视上的规矩记住.
 */
internal class RemoteMediaFilter(
    val resolution: String?,
    val subtitle: String?,
    val alliance: String?,
    val showExcluded: Boolean,
    /** 网页上勾了「显示全部」的那个数据源 (只能是当时胶囊选中的那一个): 它的候选不按每源上限截断. */
    val fullSource: String? = null,
) {
    fun accepts(media: Media): Boolean {
        val p = media.properties
        return (resolution == null || p.resolution == resolution) &&
                (subtitle == null || subtitle in p.subtitleLanguageIds) &&
                (alliance == null || p.alliance == alliance)
    }

    companion object {
        val None = RemoteMediaFilter(null, null, null, showExcluded = false)
    }
}
