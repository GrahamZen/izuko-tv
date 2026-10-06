/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.media.fetch.restart
import me.him188.ani.app.ui.download.subject.TvDownloadMediaPickerArgs
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_source_excluded_toast
import me.him188.ani.app.ui.lang.tv_source_manual_pick_failed
import me.him188.ani.app.ui.mediaselect.MediaSelectorMode
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import org.jetbrains.compose.resources.stringResource

/**
 * 缓存页的选源 (TvDownloadMediaPickerVariant 的 TV 实现): 同一块选源面板, 装在铺满屏幕的独立窗口里盖在缓存页上 (窗口不压暗, 面板自己画渐变底).
 * 选了资源进入选集 ([TvDownloadMediaPickerArgs.onSelect]); 手动查找点的一集经会话自己进入选集. 返回在进去的那一层里先退一层, 否则直接关.
 * 没有完整搜索、缓存这一条 (这里本来就是在缓存).
 */
@Composable
fun TvDownloadSourcePanel(args: TvDownloadMediaPickerArgs) {
    val controller = remember { TvSourcePanelController() }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val currentArgs by rememberUpdatedState(args)
    var editingKeywords by remember { mutableStateOf<MediaFetchRequest?>(null) }
    var editingManualKeyword by remember { mutableStateOf<String?>(null) }
    val excludedToast = stringResource(Lang.tv_source_excluded_toast)
    val manualPickFailed = stringResource(Lang.tv_source_manual_pick_failed)

    val state = remember(args.selectorState, args.fetchSession) {
        val host = object : TvSourcePanelHost {
            override fun pick(media: Media) = currentArgs.onSelect(media)
            override fun restartSource(instanceId: String) = currentArgs.fetchSession.restart(instanceId)
            override fun refresh() = currentArgs.fetchSession.restartAll()
            override fun setFullSearch(enabled: Boolean) = Unit

            override fun editKeywords() {
                scope.launch { editingKeywords = currentArgs.fetchSession.latestRequest.first() }
            }

            override fun editManualKeyword(current: String) {
                editingManualKeyword = current
            }

            override fun cache(media: Media) = Unit

            override fun excludeAlliance(alliance: String) {
                scope.launch {
                    excludeAllianceInSettings(alliance)
                    toaster.toast(excludedToast.format(alliance))
                }
            }

            override fun onManualPicked() = Unit

            override fun onManualPickFailed() {
                toaster.toast(manualPickFailed)
            }
        }
        TvSourcePanelState(
            selector = args.selectorState,
            sourceResults = args.sourceResults,
            manual = args.manualBrowse,
            fullSearch = flowOf(null),
            initialMode = if (args.hasBt) MediaSelectorMode.BT else MediaSelectorMode.AUTO,
            canEditKeywords = true,
            canCache = false,
            host = host,
            scope = scope,
        )
    }

    Dialog(
        onDismissRequest = args.onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogWindowDimAmount(0f)
        Box(
            Modifier
                .fillMaxSize()
                // 面板背后不铺暗底 (在播放器里画面要露出来); 缓存页上背后是剧集列表, 压暗免得和卡片混在一起
                .background(Color.Black.copy(alpha = 0.85f))
                // 返回键先给面板 (右栏退一层 / 回左栏), 面板不要了才关窗口
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp && !controller.back()) currentArgs.onDismiss()
                    true
                },
        ) {
            TvSourcePanel(
                state = state,
                controller = controller,
                leaving = false,
                onClose = args.onDismiss,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    editingKeywords?.let { request ->
        TvSourceKeywordsDialog(
            request = request,
            default = null,
            onSubmit = { currentArgs.fetchSession.setFetchRequest(it) },
            onDismiss = { editingKeywords = null },
        )
    }
    editingManualKeyword?.let { current ->
        TvSourceManualKeywordDialog(current, state, onDismiss = { editingManualKeyword = null })
    }
}
