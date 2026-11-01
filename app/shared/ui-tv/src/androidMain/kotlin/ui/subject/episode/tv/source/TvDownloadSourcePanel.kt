/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.domain.media.fetch.restart
import me.him188.ani.app.domain.media.fetch.restartSources
import me.him188.ani.app.ui.download.subject.TvDownloadMediaPickerArgs
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.AniTheme
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_source_excluded_toast
import me.him188.ani.app.ui.lang.tv_source_manual_pick_failed
import me.him188.ani.app.ui.subject.details.layout.TvBlurredBackdropImage
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import org.jetbrains.compose.resources.stringResource

/**
 * 缓存页的选源 (TvDownloadMediaPickerVariant 的 TV 实现): 同一块选源面板, 装在铺满屏幕的独立窗口里盖在缓存页上.
 * 背后整个盖住缓存页 (剧集列表透上来会和面板的行叠在一起): 铺这部番横版背景图的模糊版 (见 [TvDownloadPickerBackdrop]),
 * 没有横版图时只铺底色. 面板是按深色画的 (白字、半透明黑底), 这一层不跟主题, 恒按深色.
 * 背景图还没好时面板先不出来, 最多等 [TV_DOWNLOAD_PICKER_BACKDROP_WAIT] (期间停在缓存页上, 不先变黑);
 * 等不到就先出面板, 背后是压暗的缓存页, 图好了再淡入. 图已经好了就第一帧画上, 不淡入.
 * 选了资源进入选集 ([TvDownloadMediaPickerArgs.onSelect]); 手动查找点的一集经会话自己进入选集. 返回在进去的那一层里先退一层, 否则直接关.
 * 没有完整搜索、缓存这一条 (这里本来就是在缓存).
 */
@Composable
fun TvDownloadSourcePanel(args: TvDownloadMediaPickerArgs) {
    val controller = remember { TvSourcePanelController() }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val navigator = LocalNavigator.current
    val currentArgs by rememberUpdatedState(args)
    var editingKeywords by remember { mutableStateOf<MediaFetchRequest?>(null) }
    var editingManualKeyword by remember { mutableStateOf<String?>(null) }
    val excludedToast = stringResource(Lang.tv_source_excluded_toast)
    val manualPickFailed = stringResource(Lang.tv_source_manual_pick_failed)

    val state = remember(args.selectorState, args.fetchSession) {
        val host = object : TvSourcePanelHost {
            override fun pick(media: Media) = currentArgs.onSelect(media)
            override fun restartSource(instanceId: String) = currentArgs.fetchSession.restart(instanceId)
            override fun refresh() = currentArgs.fetchSession.restartSources(includeDisabled = false)
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

            override fun openSettings() {
                currentArgs.onDismiss()
                navigator.navigateSettings(SettingsTab.MEDIA_SOURCE)
            }

            override fun onManualPickFailed() {
                toaster.toast(manualPickFailed)
            }
        }
        TvSourcePanelState(
            selector = args.selectorState,
            sourceResults = args.sourceResults,
            manual = args.manualBrowse,
            fullSearch = flowOf(null),
            canEditKeywords = true,
            canCache = false,
            host = host,
            scope = scope,
            canOpenSettings = true,
            sourceTypes = tvSourceTypesFlow(),
        )
    }

    var subjectId by remember(args.fetchSession) { mutableStateOf<Int?>(null) }
    var show by remember(args.fetchSession) { mutableStateOf(false) }
    val backdropKey = rememberTvDownloadPickerBackdropKey(subjectId)
    val sketch = LocalSketch.current
    val context = LocalContext.current.applicationContext
    val windowSize = LocalWindowInfo.current.containerSize
    val blurSpec = tvBackdropBlurSpec(LocalThemeSettings.current.tvDetailsBackdropBlur)
    LaunchedEffect(args.fetchSession) {
        val id = args.fetchSession.request.first().subjectId.toIntOrNull()
        subjectId = id
        if (id != null && windowSize.width > 0 && windowSize.height > 0) {
            val key = TvDownloadPickerBackdrop.Key(id, windowSize, blurSpec)
            withTimeoutOrNull(TV_DOWNLOAD_PICKER_BACKDROP_WAIT) { TvDownloadPickerBackdrop.prepare(key, sketch, context).await() }
        }
        show = true
    }
    if (!show) return

    Dialog(
        onDismissRequest = args.onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogWindowDimAmount(0f)
        AniTheme(darkModeOverride = DarkMode.DARK) {
            val ready = TvDownloadPickerBackdrop.ready?.takeIf { it.key == backdropKey }
            // 首次组合时就好了: 直接是终值, 不淡入; 等不到先出来的: 压暗的缓存页, 图好了 (或确定没有图) 再淡到不透明
            val imageAlpha by animateFloatAsState(if (ready?.image != null) 1f else 0f)
            val baseAlpha by animateFloatAsState(if (ready != null) 1f else 0.85f)
            val pageColor = AniThemeDefaults.pageContentBackgroundColor
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(pageColor.copy(alpha = baseAlpha)) }
                    // 返回键先给面板 (退出筛选 / 内容里退一层), 面板不要了才关窗口
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyUp && !controller.back()) currentArgs.onDismiss()
                        true
                    },
            ) {
                // 压暗同详情页的模糊底
                TvBlurredBackdropImage(
                    ready?.image,
                    alpha = { imageAlpha },
                    maskColor = pageColor.copy(alpha = TV_FULLSCREEN_BACKDROP_DIM_ALPHA),
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvSourcePanel(
                    state = state,
                    controller = controller,
                    leaving = false,
                    onClose = args.onDismiss,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
