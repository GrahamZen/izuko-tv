/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.download.subject

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaSourceResultsFilterer
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSourceInfoProvider
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresentation
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresenter
import me.him188.ani.app.ui.mediafetch.rememberMediaSelectorState
import me.him188.ani.app.ui.mediaselect.manual.ManualBrowseState
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind

/**
 * 遥控器形态的缓存选源界面, 由 TV 包注入 ([LocalTvDownloadMediaPickerVariant], 见 TvPageVariants): 全屏盖在缓存页上, 选了就进入选集步骤.
 * 没注入 (手机 / 桌面) 时缓存页照旧用底部弹窗里的三模式选源页.
 */
fun interface TvDownloadMediaPickerVariant {
    @Composable
    fun Content(args: TvDownloadMediaPickerArgs)
}

/**
 * @property hasBt 会话里有没有 BT 源 (含禁用的); 有就先落 BT 与缓存那一栏.
 * @property manualBrowse 本次选源会话的手动查找, 点选的一集经它交给会话 (之后由会话进入选集), 不经 [onSelect].
 * @property onSelect 选了资源: 进入选集步骤.
 */
class TvDownloadMediaPickerArgs(
    val selectorState: MediaSelectorState,
    val sourceResults: Flow<MediaSourceResultListPresentation>,
    val fetchSession: MediaFetchSession,
    val manualBrowse: ManualBrowseState?,
    val hasBt: Boolean,
    val onSelect: (Media) -> Unit,
    val onDismiss: () -> Unit,
    /** 选集那一步 (平板注入了 [LocalDownloadEpisodePickerVariant] 且选的资源覆盖别的集时): 画在这个选源窗口里, 见 [TvDownloadEpisodeStep]. */
    val episodeStep: TvDownloadEpisodeStep? = null,
)

/**
 * 选源之后的选集那一步, 画在选源的同一个窗口里: 两步之间不关一个窗口再开一个, 不会露出底下的缓存页.
 *
 * @param onBack 回到选源 (系统返回也走这里)
 */
class TvDownloadEpisodeStep(
    val onBack: () -> Unit,
    val content: @Composable () -> Unit,
)

val LocalTvDownloadMediaPickerVariant = staticCompositionLocalOf<TvDownloadMediaPickerVariant?> { null }

/**
 * 选集步骤的另一套界面, 由平板模式 / iPad 注入 ([LocalDownloadEpisodePickerVariant]): 全屏「选择集数」. 没注入时照旧在底部弹窗里画
 * [DownloadEpisodePicker].
 */
fun interface DownloadEpisodePickerVariant {
    /**
     * @param onBack 回到选源.
     * @param onConfirm 确认要一并下载的集.
     */
    @Composable
    fun Content(state: DownloadEpisodePickerState, onBack: () -> Unit, onConfirm: (Set<Int>) -> Unit)
}

val LocalDownloadEpisodePickerVariant = staticCompositionLocalOf<DownloadEpisodePickerVariant?> { null }

/**
 * 缓存页 (单独的条目缓存页与缓存总页的右栏) 打开时调用, 参数是条目 id: 让 TV 包提前准备这一部的选源要用的东西 (选源背后的模糊背景图),
 * 按下载时不用再等. 没注入 (手机 / 桌面) 时什么都不做.
 */
val LocalTvDownloadPickerPrewarm = staticCompositionLocalOf<(@Composable (subjectId: Int) -> Unit)?> { null }

/**
 * 电视 / 平板接管下载请求的弹窗 ([LocalTvDownloadMediaPickerVariant]) 时画出来并返回 true; 没接管 (手机、桌面) 返回 false, 照上游的底部弹窗.
 * 选集那一步: 平板 ([LocalDownloadEpisodePickerVariant]) 画在选源的同一个窗口里, 电视照旧用底部弹窗.
 */
@Composable
internal fun TvDownloadRequest(
    selection: DownloadMediaPickerState,
    episodePicker: DownloadEpisodePickerState?,
    sourceInfoProvider: MediaSourceInfoProvider,
    settings: Flow<MediaSelectorSettings>,
    onDismiss: () -> Unit,
    onSelect: (Media) -> Unit,
    onConfirmEpisodes: (Set<Int>) -> Unit,
    onBack: () -> Unit,
): Boolean {
    val variant = LocalTvDownloadMediaPickerVariant.current ?: return false
    val episodeVariant = LocalDownloadEpisodePickerVariant.current
    if (episodePicker != null && episodeVariant == null) return false
    val episodeStep = if (episodePicker != null && episodeVariant != null) {
        TvDownloadEpisodeStep(onBack) { episodeVariant.Content(episodePicker, onBack, onConfirmEpisodes) }
    } else {
        null
    }
    TvDownloadMediaPicker(variant, selection, sourceInfoProvider, settings, onDismiss, onSelect, episodeStep)
    return true
}

/** 选源步骤交给注入的 [variant]: 选源状态的取法同底部弹窗里的 DownloadMediaPicker. */
@Composable
private fun TvDownloadMediaPicker(
    variant: TvDownloadMediaPickerVariant,
    selection: DownloadMediaPickerState,
    sourceInfoProvider: MediaSourceInfoProvider,
    settings: Flow<MediaSelectorSettings>,
    onDismiss: () -> Unit,
    onSelect: (Media) -> Unit,
    episodeStep: TvDownloadEpisodeStep?,
) {
    val scope = rememberCoroutineScope()
    val filteredResults = remember(selection, settings) {
        MediaSourceResultsFilterer(flowOf(selection.fetchSession.mediaSourceResults), settings, scope).filteredSourceResults
    }
    val sourceResults = remember(filteredResults) {
        MediaSourceResultListPresenter(
            filteredResults,
            includedMediaFlow = selection.selector.filteredCandidatesMedia,
        ).presentationFlow.map { MediaSourceResultListPresentation(it) }
    }
    val selectorState = rememberMediaSelectorState(sourceInfoProvider, filteredResults) { selection.selector }
    val hasBt = remember(selection) {
        selection.fetchSession.mediaSourceResults.any { it.kind == MediaSourceKind.BitTorrent }
    }
    variant.Content(
        TvDownloadMediaPickerArgs(
            selectorState = selectorState,
            sourceResults = sourceResults,
            fetchSession = selection.fetchSession,
            manualBrowse = selection.manualBrowse,
            hasBt = hasBt,
            onSelect = onSelect,
            onDismiss = onDismiss,
            episodeStep = episodeStep,
        ),
    )
}
