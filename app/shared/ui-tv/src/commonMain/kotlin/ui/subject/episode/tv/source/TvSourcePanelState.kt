/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresentation
import me.him188.ani.app.ui.mediaselect.MediaSelectorMode
import me.him188.ani.app.ui.mediaselect.manual.ManualBrowseState
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.platform.currentTimeMillis

/**
 * 选源面板的宿主 (播放页 / 缓存页): 面板只管导航与上游选源状态, 真正「选了之后怎么办」与要开窗口的事交给宿主.
 */
interface TvSourcePanelHost {
    /** 选了 [media]. 播放页交给选择器 (之后关不关面板看设置), 缓存页进入选集. */
    fun pick(media: Media)

    fun restartSource(instanceId: String)

    /** 重新搜索全部数据源. */
    fun refresh()

    fun setFullSearch(enabled: Boolean)

    /** 改搜索词 (宿主开输入框). */
    fun editKeywords()

    /** 去这部番的缓存页 (「下载」分支的第一行, 只在 canOpenDownloads 时有). */
    fun openDownloads() {}

    /** 改手动查找的关键词 (宿主开输入框, 填好后调 [TvSourcePanelState.submitManualKeyword]). */
    fun editManualKeyword(current: String)

    fun cache(media: Media)

    fun excludeAlliance(alliance: String)

    /** 手动查找点了一集且已交给选择器 / 宿主. */
    fun onManualPicked()

    /** 手动查找点了一集但没生成出资源. */
    fun onManualPickFailed()
}

/**
 * 选源面板的状态: 上游选源状态 + 面板导航 ([TvSourceNav]) → [content] (后台线程拼, 节流到每 [THROTTLE_MILLIS] 一次), 并执行面板上的动作.
 * 不碰 Compose 与视图: 视图经 TvSourcePanelListener 报事件, 宿主把 [content] 交给视图.
 *
 * @param fullSearch 「完整搜索」的值, 见 [TvSourcePanelInput.fullSearch].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvSourcePanelState(
    private val selector: MediaSelectorState,
    sourceResults: Flow<MediaSourceResultListPresentation>,
    private val manual: ManualBrowseState?,
    fullSearch: Flow<Boolean?>,
    initialMode: MediaSelectorMode,
    private val canEditKeywords: Boolean,
    private val canCache: Boolean,
    private val host: TvSourcePanelHost,
    private val scope: CoroutineScope,
    private val canOpenDownloads: Boolean = false,
    /** 这一集的缓存, 见 [TvSourcePanelInput.downloads]. */
    downloads: Flow<List<TvSourceCacheItem>> = flowOf(emptyList()),
    /** 打开时左栏落在哪一项 (落回上次隐藏时的位置, 见 TvSourceResume); null = 按正在播放的资源落. */
    initialRailKey: String? = null,
    /** 见 [TvSourcePanelInput.playbackDiskCache]. */
    private val playbackDiskCache: Boolean = false,
) {
    private val nav = MutableStateFlow(TvSourceNav(railKey = initialRailKey))
    private val strings = MutableStateFlow<TvSourceStrings?>(null)

    /** 开着的详情弹窗是哪一行的 (见 TvSourceDetailsDialog); null = 没开. 弹窗里左右键换行就是改它. */
    val detailsTarget: MutableStateFlow<TvSourceAction.ShowDetails?> = MutableStateFlow(null)

    private val inputs: Flow<TvSourcePanelInput> = run {
        val core = combine(
            selector.presentationFlow,
            selector.btPresentationFlow,
            sourceResults,
            manual?.presentationFlow ?: flowOf(null),
            fullSearch,
        ) { selectorPresentation, bt, sources, manualPresentation, full ->
            TvSourcePanelInput(
                selector = selectorPresentation,
                bt = bt,
                sources = sources,
                manual = manualPresentation,
                fullSearch = full,
                canEditKeywords = canEditKeywords,
                canCache = canCache,
                initialMode = initialMode,
                nowMillis = currentTimeMillis(),
                canOpenDownloads = canOpenDownloads,
                playbackDiskCache = playbackDiskCache,
            )
        }
            .combine(downloads.distinctUntilChanged()) { input, items -> input.copy(downloads = items) }
        // 有限流中的源时每秒重拼一次 (倒计时), 没有就不跑
        core.map { input -> input.selector.webSources.any { it.isRateLimited } }
            .distinctUntilChanged()
            .flatMapLatest { ticking ->
                if (!ticking) {
                    core
                } else {
                    combine(core, tickerFlow(1000)) { input, now -> input.copy(nowMillis = now) }
                }
            }
    }

    /** 详情弹窗开着时它在哪一栏 (要拼哪一串序列); null = 没开, 不拼. */
    private val detailsColumn = detailsTarget.map { it?.inRail }.distinctUntilChanged()

    private val built: StateFlow<Pair<TvSourcePanelContent, TvSourceDetailsSequence?>?> = combine(
        inputs.throttleLatest(THROTTLE_MILLIS),
        nav,
        strings.filterNotNull(),
        detailsColumn,
    ) { input, nav, strings, column ->
        val content = buildTvSourcePanel(input, nav, strings)
        content to column?.let { buildTvSourceDetailsSequence(input, strings, content, inRail = it) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 拼好的面板内容; null = 还没拼出第一份 (文案或上游状态未到). */
    val content: StateFlow<TvSourcePanelContent?> = built.map { it?.first }.stateIn(scope, SharingStarted.Eagerly, null)

    /** 详情弹窗左右键走的那一串 (见 [buildTvSourceDetailsSequence]); null = 弹窗没开或还没拼好. 与 [content] 同一次拼出. */
    val detailsSequence: StateFlow<TvSourceDetailsSequence?> =
        built.map { it?.second }.stateIn(scope, SharingStarted.Eagerly, null)

    fun setStrings(value: TvSourceStrings) {
        strings.value = value
    }

    fun onRailFocused(key: String) {
        nav.update { if (it.railKey == key) it else it.copy(railKey = key, drill = null, frozenOrder = null, showExcluded = false) }
        if (key == TvSourceRailKeys.MANUAL) manual?.searchIfNeeded()
    }

    /** 焦点进右栏: 钉住此刻的行序 (见 [applyFrozenOrder]); 出右栏: 放开. */
    fun onRightFocusChanged(inRight: Boolean) {
        nav.update { current ->
            if (!inRight) {
                current.copy(frozenOrder = null)
            } else {
                current.copy(frozenOrder = content.value?.right?.rows?.map { it.id })
            }
        }
    }

    /** 右栏里的返回: 退出进去的那一层 / 手动查找的条目页. 没有可退的返回 false. */
    fun backInRight(): Boolean {
        val current = nav.value
        if (current.drill != null) {
            nav.update { it.copy(drill = null, frozenOrder = null) }
            return true
        }
        if (current.railKey == TvSourceRailKeys.MANUAL && manual != null && content.value?.right?.key?.startsWith("${TvSourceRailKeys.MANUAL}/subject/") == true) {
            manual.closeSubject()
            return true
        }
        return false
    }

    fun submitManualKeyword(keyword: String) {
        val manual = manual ?: return
        manual.setKeyword(keyword)
        manual.search()
    }

    fun perform(action: TvSourceAction) {
        when (action) {
            is TvSourceAction.Play -> host.pick(action.media)
            is TvSourceAction.RestartSource -> host.restartSource(action.instanceId)
            is TvSourceAction.ResolveCaptcha -> scope.launch { selector.resolveCaptcha(action.instanceId) }
            TvSourceAction.Refresh -> host.refresh()
            is TvSourceAction.SetFullSearch -> host.setFullSearch(action.enabled)
            TvSourceAction.EditKeywords -> host.editKeywords()
            TvSourceAction.OpenDownloads -> host.openDownloads()
            TvSourceAction.ToggleEpisodeFilter -> selector.btFilterState.episodeFilterEnabled.update { !it }
            TvSourceAction.ToggleExcluded -> nav.update { it.copy(showExcluded = !it.showExcluded, frozenOrder = null) }
            is TvSourceAction.Open -> nav.update { it.copy(drill = action.drill, frozenOrder = null) }
            is TvSourceAction.ShowDetails -> detailsTarget.value = action
            is TvSourceAction.PickFilter -> {
                pickFilter(action.filter, action.value)
                closeDrill()
            }

            is TvSourceAction.Cache -> {
                host.cache(action.media)
                closeDrill()
            }

            is TvSourceAction.ExcludeAlliance -> {
                host.excludeAlliance(action.alliance)
                closeDrill()
            }

            is TvSourceAction.ManualSelectSource -> {
                manual?.selectSource(action.instanceId)
                manual?.searchIfNeeded()
                closeDrill()
            }

            TvSourceAction.ManualEditKeyword -> host.editManualKeyword(manual?.presentationFlow?.value?.keyword.orEmpty())
            TvSourceAction.ManualRetry -> manual?.retry()
            is TvSourceAction.ManualOpenSubject -> manual?.openSubject(action.subject)
            TvSourceAction.ManualCloseSubject -> manual?.closeSubject()
            is TvSourceAction.ManualSelectChannel -> {
                manual?.selectChannel(action.index)
                closeDrill()
            }

            is TvSourceAction.ManualSetRemember -> manual?.setRememberSelection(action.remember)
            is TvSourceAction.ManualPlay -> {
                val manual = manual ?: return
                scope.launch {
                    when (manual.play(action.episodeIndex)) {
                        true -> host.onManualPicked()
                        false -> host.onManualPickFailed()
                        null -> {} // 上一次点选还在进行, 这一次忽略
                    }
                }
            }
        }
    }

    private fun closeDrill() {
        nav.update { it.copy(drill = null, frozenOrder = null) }
    }

    private fun pickFilter(filter: TvSourceFilter, value: String?) {
        if (filter == TvSourceFilter.Source) {
            selector.btFilterState.sourceFilter.value = value
            // 选了一个停用 / 查询失败的源: 顺带让它重新查, 否则筛出来一直是空的
            if (value != null) restartIfIdle(value)
            return
        }
        val item = when (filter) {
            TvSourceFilter.Resolution -> selector.resolution
            TvSourceFilter.Subtitle -> selector.subtitleLanguageId
            TvSourceFilter.Alliance -> selector.alliance
            TvSourceFilter.Source -> return
        }
        scope.launch {
            if (value == null) item.removePreference() else item.prefer(value)
        }
    }

    private val lastSources = MutableStateFlow(MediaSourceResultListPresentation.Empty)

    init {
        scope.launch { sourceResults.collect { lastSources.value = it } }
    }

    private fun restartIfIdle(mediaSourceId: String) {
        val source = lastSources.value.btSources.firstOrNull { it.mediaSourceId == mediaSourceId } ?: return
        if (source.isDisabled || source.isFailedOrAbandoned) host.restartSource(source.instanceId)
    }

    private companion object {
        /** 上游状态在搜索途中变得很勤 (每个源每返回一批都变): 拼面板最多每这么久一次. */
        const val THROTTLE_MILLIS = 200L
    }
}

/** 第一个值当场发, 之后最多每 [periodMillis] 发一次最新的. */
internal fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(periodMillis)
}

private fun tickerFlow(periodMillis: Long): Flow<Long> = flow {
    while (true) {
        emit(currentTimeMillis())
        delay(periodMillis)
    }
}
