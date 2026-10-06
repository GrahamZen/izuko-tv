/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_source_excluded_toast
import me.him188.ani.app.ui.lang.tv_source_keywords
import me.him188.ani.app.ui.lang.tv_source_keywords_hint
import me.him188.ani.app.ui.lang.tv_source_keywords_reset
import me.him188.ani.app.ui.lang.tv_source_manual_keyword_title
import me.him188.ani.app.ui.lang.tv_source_manual_pick_failed
import me.him188.ani.app.ui.remote.RemoteCache
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.tools.getOrZero
import org.koin.mp.KoinPlatform

/**
 * 播放器里的选源面板 (TvPlayerLayer.SOURCES): 面板状态接到播放页的选源状态上 —— 选了就交给选择器 (选完关不关面板看设置「选择数据源后自动关闭」),
 * 手动查找用播放页那份 ManualBrowseState, 改搜索词经 EpisodeViewModel.updateFetchRequest.
 * 面板开着期间搜索一直查完, 关上时正在播又没开完整搜索就暂停 (见 EpisodeViewModel.onMediaSelectorShown / onMediaSelectorHidden).
 *
 * @param onClose 关面板 (回控制层).
 */
@Composable
internal fun TvPlayerSourcePanel(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    controller: TvSourcePanelController,
    leaving: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(vm) {
        vm.onMediaSelectorShown()
        onDispose { vm.onMediaSelectorHidden() }
    }
    val toaster = LocalToaster.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    val currentPage by rememberUpdatedState(page)
    var editingKeywords by remember { mutableStateOf(false) }
    var editingManualKeyword by remember { mutableStateOf<String?>(null) }
    val excludedToast = stringResource(Lang.tv_source_excluded_toast)
    val manualPickFailed = stringResource(Lang.tv_source_manual_pick_failed)

    val selector = page.mediaSelectorState
    // 上次按返回隐藏的: 落回当时停的那一项 / 那一行 (同一集才算)
    val resume = remember(controller) { controller.takeResume(vm.episodeSelectorState.current?.episodeId) }
    val state = remember(vm, selector) {
        val host = object : TvSourcePanelHost {
            override fun pick(media: Media) {
                selector.select(media)
                if (vm.videoScaffoldConfig.hideSelectorOnSelect) currentOnClose()
            }

            override fun restartSource(instanceId: String) = vm.restartSource(instanceId)
            override fun refresh() = vm.refreshFetch()
            override fun setFullSearch(enabled: Boolean) = vm.setFullMediaSearch(enabled)

            override fun editKeywords() {
                if (currentPage.fetchRequest != null) editingKeywords = true
            }

            override fun editManualKeyword(current: String) {
                editingManualKeyword = current
            }

            override fun openDownloads() {
                currentOnClose()
                navigator.navigateSubjectCaches(vm.subjectId)
            }

            override fun cache(media: Media) {
                val episodeId = vm.episodeSelectorState.current?.episodeId ?: return
                scope.launch {
                    // 与 Web 控制台播放页的「缓存」同一条路 (先查这一集的缓存状态再建)
                    val result = withContext(Dispatchers.IO) { RemoteCache.cacheMedia(vm.subjectId, episodeId, media) }
                    result["message"]?.jsonPrimitive?.content?.let { toaster.toast(it) }
                }
            }

            override fun excludeAlliance(alliance: String) {
                scope.launch {
                    excludeAllianceInSettings(alliance)
                    toaster.toast(excludedToast.format(alliance))
                }
            }

            override fun onManualPicked() {
                if (vm.videoScaffoldConfig.hideSelectorOnSelect) currentOnClose()
            }

            override fun onManualPickFailed() {
                toaster.toast(manualPickFailed)
            }
        }
        TvSourcePanelState(
            selector = selector,
            sourceResults = vm.pageState.filterNotNull().map { it.mediaSourceResultListPresentation }.distinctUntilChanged(),
            manual = vm.manualBrowseState,
            fullSearch = vm.fullMediaSearch,
            initialMode = page.initialMediaSelectorMode,
            canEditKeywords = true,
            canCache = true,
            host = host,
            scope = scope,
            canOpenDownloads = true,
            downloads = episodeDownloads(vm),
            initialRailKey = resume?.railKey,
            playbackDiskCache = vm.videoScaffoldConfig.enablePlaybackDiskCache,
        )
    }

    TvSourcePanel(
        state = state,
        controller = controller,
        leaving = leaving,
        onClose = onClose,
        modifier = modifier,
        resumeRow = resume?.rowId?.let { resume.railKey to it },
    )

    if (editingKeywords) {
        val request = page.fetchRequest
        if (request == null) {
            editingKeywords = false
        } else {
            TvSourceKeywordsDialog(
                request = request,
                default = page.defaultFetchRequest,
                onSubmit = { vm.updateFetchRequest(it) },
                onDismiss = { editingKeywords = false },
            )
        }
    }
    editingManualKeyword?.let { current ->
        TvSourceManualKeywordDialog(current, state, onDismiss = { editingManualKeyword = null })
    }
}

/**
 * 改搜索词: 一行一个名字 (MediaFetchRequest.subjectNames). [default] 非 null 时多一颗「恢复 Bangumi 名称」. 名字没变不提交.
 */
@Composable
internal fun TvSourceKeywordsDialog(
    request: MediaFetchRequest,
    default: MediaFetchRequest?,
    onSubmit: (MediaFetchRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    TvSourceTextInputDialog(
        title = stringResource(Lang.tv_source_keywords),
        initial = request.subjectNames.joinToString("\n"),
        multiline = true,
        hint = stringResource(Lang.tv_source_keywords_hint),
        resetLabel = stringResource(Lang.tv_source_keywords_reset),
        onReset = default?.let {
            {
                onDismiss()
                onSubmit(request.copy(subjectNames = it.subjectNames))
            }
        },
        onConfirm = { text ->
            onDismiss()
            val names = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (names.isNotEmpty() && names != request.subjectNames) onSubmit(request.copy(subjectNames = names))
        },
        onDismiss = onDismiss,
    )
}

/** 手动查找的关键词: 填好后在 [state] 里重新搜索. */
@Composable
internal fun TvSourceManualKeywordDialog(current: String, state: TvSourcePanelState, onDismiss: () -> Unit) {
    TvSourceTextInputDialog(
        title = stringResource(Lang.tv_source_manual_keyword_title),
        initial = current,
        onConfirm = { text ->
            onDismiss()
            text.trim().takeIf { it.isNotEmpty() }?.let { state.submitManualKeyword(it) }
        },
        onDismiss = onDismiss,
    )
}

/** 把字幕组加进设置里的「排除的字幕组」(MediaPreference.excludedAlliancePatterns). */
internal suspend fun excludeAllianceInSettings(alliance: String) {
    GlobalKoin.get<SettingsRepository>().defaultMediaPreference.update {
        copy(excludedAlliancePatterns = (excludedAlliancePatterns.orEmpty() + alliancePattern(alliance)).distinct())
    }
}

private val regexSpecials = "\\^$.|?*+()[]{}".toSet()

/** 字幕组名写成排除规则: 规则按正则匹配, 名字里的正则符号转义掉 (没有就原样, 设置里读得懂). */
internal fun alliancePattern(alliance: String): String = buildString {
    for (c in alliance.trim()) {
        if (c in regexSpecials) append('\\')
        append(c)
    }
}

/**
 * 正在播的这一集的缓存 (已下完的和正在下的), 给「下载」的分支. 进度取整到百分之一再去重: 下载中每秒报好几次进度, 不取整的话面板每次都重拼.
 * 换集时跟着换 (选集状态是快照状态, 用 snapshotFlow 订).
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun episodeDownloads(vm: EpisodeViewModel): Flow<List<TvSourceCacheItem>> {
    val manager = KoinPlatform.getKoin().get<MediaDownloadManager>()
    return snapshotFlow { vm.episodeSelectorState.current?.episodeId?.toString() }
        .distinctUntilChanged()
        .flatMapLatest { episodeId ->
            if (episodeId == null) return@flatMapLatest flowOf(emptyList())
            combine(manager.downloadsForSubject(vm.subjectId), manager.snapshots(vm.subjectId)) { downloads, snapshots ->
                val progress = snapshots.associate { it.id to it.progress.getOrZero() }
                downloads.filter { it.metadata.episodeId == episodeId }.map { download ->
                    val fraction = progress[download.id] ?: 0f
                    TvSourceCacheItem(
                        cacheId = download.id,
                        origin = download.origin,
                        percent = (fraction * 100).toInt().coerceIn(0, 100),
                        finished = fraction >= 1f,
                    )
                }
            }
        }
        .distinctUntilChanged()
}
