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
)

val LocalTvDownloadMediaPickerVariant = staticCompositionLocalOf<TvDownloadMediaPickerVariant?> { null }

/** 选源步骤交给注入的 [variant]: 选源状态的取法同底部弹窗里的 DownloadMediaPicker. */
@Composable
internal fun TvDownloadMediaPicker(
    variant: TvDownloadMediaPickerVariant,
    selection: DownloadMediaPickerState,
    sourceInfoProvider: MediaSourceInfoProvider,
    settings: Flow<MediaSelectorSettings>,
    onDismiss: () -> Unit,
    onSelect: (Media) -> Unit,
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
        ),
    )
}
