/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.platform.navigation.rememberAsyncBrowserNavigator
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.effects.rememberNoticeSoundPlayer
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_REDUCED_MARQUEE_ITERATIONS
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHost
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.tvGlassColors
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.aniFocusContainerColor
import me.him188.ani.app.ui.foundation.widgets.aniFocusContentColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings
import me.him188.ani.app.ui.lang.settings_mediasource_cancel
import me.him188.ani.app.ui.settings.SettingsViewModel
import me.him188.ani.app.ui.settings.UiScaleSyncEffect
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.settings.account.ProfileViewModel
import me.him188.ani.app.ui.update.AppUpdateViewModel
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.jetbrains.compose.resources.stringResource

/**
 * 电视设置页: 原生三栏 ([TvSettingsView]), 内容由 [TvSettingsState] 按清单 ([TvSettingsCatalogEntries]) 建出.
 * 动作打开的整页与对话框 ([TvSettingsOverlay]) 盖在上面; 整页盖着时返回先关它.
 *
 * @param vm 原来设置页的 ViewModel: 它已经建好的状态 (PikPak、调试、关于) 照用
 * @param initialTab 从别处直接跳到某一类 (如动作面板长按服务连通 → 代理); null = 落回上次停的地方.
 */
@Composable
fun TvSettingsPage(
    vm: SettingsViewModel,
    initialTab: SettingsTab?,
    legacy: @Composable (SettingsTab) -> Unit,
    loadOpenSourceLibrariesJsons: suspend () -> List<ByteArray>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appUpdate = viewModel { AppUpdateViewModel() }
    val profile = viewModel { ProfileViewModel() }
    val sketch = LocalSketch.current
    val last = TvSettingsLastPosition
    val deps = remember {
        TvSettingsDeps(context, vm, appUpdate, profile, sketch, loadOpenSourceLibrariesJsons, scope)
    }
    val state = remember {
        TvSettingsState(
            catalog = TvSettingsCatalogEntries,
            repository = GlobalKoin.get<SettingsRepository>(),
            deps = deps,
            initialCategory = initialTab?.name ?: last.categoryId,
            scope = scope,
            extraKeys = listOf(TvSettingKeys.debug, TvSettingKeys.consoleUrl),
        )
    }
    val playNoticeSound = rememberNoticeSoundPlayer()
    val toaster = LocalToaster.current
    val browser = rememberAsyncBrowserNavigator()
    LaunchedEffect(state) {
        state.attach(TvSettingsHost(playNoticeSound, toast = { toaster.toast(it) }, browser))
        // 说明栏里的控制台二维码用最新的地址 (换过网络、地址变了)
        TvRemoteControl.refreshAddress()
    }
    // 界面缩放改了: 离开设置页时把窗口层 (弹窗 / 菜单) 一并对齐 (同原来的设置页)
    UiScaleSyncEffect()

    val style = rememberTvSettingsStyle()
    val pageTitle = stringResource(Lang.settings)
    val overlay by state.overlay.collectAsState()
    val coversPage = overlay is TvSettingsOverlay.Legacy
    val holder = remember { arrayOfNulls<TvSettingsView>(1) }
    val navigator = LocalNavigator.current
    val images = remember { TvSettingsImages(scope, style.infoImageSizePx) }

    BackHandler {
        when {
            // 盖着的整页自己处理不了的返回 (它最外一层) 落到这里: 关掉它
            coversPage -> state.dismissOverlay()
            holder[0]?.handleBack() == true -> {}
            else -> navigator.popBackStack()
        }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TvNativeHost(
            factory = { ctx ->
                TvSettingsView(ctx, style, pageTitle).also { view ->
                    holder[0] = view
                    view.imageLoader = images::load
                    view.listener = object : TvSettingsViewListener {
                        override fun onRailFocused(categoryId: String) = state.onRailFocused(categoryId)
                        override fun onRowClicked(rowId: String) = state.onRowClicked(rowId)
                        override fun onBackInRight(): Boolean = state.backInRight()
                        override fun onPositionChanged(categoryId: String, rowId: String?) {
                            last.categoryId = categoryId
                            last.rowId = rowId
                            state.onRowFocused(rowId)
                        }

                        override fun onRowsReordered(rowId: String, order: List<String>) = state.onRowsReordered(rowId, order)
                    }
                    // 没指定去哪一类时落回上次停的那一行
                    if (initialTab == null) {
                        val category = last.categoryId
                        val row = last.rowId
                        if (category != null && row != null) view.resumeRow = category to row
                    } else {
                        // 从别处直接跳到某一类 (如「去登录」到账号): 焦点直接进中栏, 说明栏 (登录二维码) 当场就有
                        view.enterListOnOpen = true
                    }
                    view.requestEntryFocus()
                }
            },
            update = { view ->
                holder[0] = view
                view.applyStyle(style)
                view.suspended = coversPage
            },
        )
        LaunchedEffect(state) {
            state.content.filterNotNull().collect { holder[0]?.submit(it) }
        }

        when (val current = overlay) {
            is TvSettingsOverlay.Legacy -> CoveringPage(current) { legacy(current.tab) }
            is TvSettingsOverlay.Dialog -> current.content(state::dismissOverlay)
            null -> {}
        }
    }
    // 盖着的整页关掉后焦点回到原生页, 落在打开它的那一行上
    val wasCovered = remember { booleanArrayOf(false) }
    LaunchedEffect(coversPage) {
        if (coversPage) {
            wasCovered[0] = true
        } else if (wasCovered[0]) {
            wasCovered[0] = false
            holder[0]?.requestReturnFocus()
        }
    }

    TvSettingsUpdateHost(appUpdate)
    TvPikPakLegacyNoticeHost(vm)

    val pending by state.pendingConfirm.collectAsState()
    pending?.let { p -> TvSettingsConfirmDialog(p.confirm, onConfirm = state::confirmPending, onDismiss = state::dismissPending) }
}

/**
 * 说明栏的图 (头像、标志): 从资源读出来按 [sizePx] 缩小解码, 解过的留着 (一页里就十几张). 回调在主线程.
 */
private class TvSettingsImages(private val scope: CoroutineScope, private val sizePx: Int) {
    private val cache = HashMap<DrawableResource, Bitmap?>()

    fun load(image: TvImage, done: (Bitmap?) -> Unit) {
        if (image.res in cache) return done(cache[image.res])
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { decode(getDrawableResourceBytes(getSystemResourceEnvironment(), image.res)) }
                    .onFailure { logger.warn(it) { "Failed to decode settings image" } }
                    .getOrNull()
            }
            cache[image.res] = bitmap
            done(bitmap)
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        private val logger = logger<TvSettingsImages>()
    }
}

/** 盖住原生页的一整页: 铺底、焦点进去. */
@Composable
private fun CoveringPage(key: Any, content: @Composable () -> Unit) {
    val requester = remember(key) { FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).focusRequester(requester).focusGroup(),
    ) {
        content()
    }
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/** 写之前问的那一句. 焦点默认在「取消」上: 遥控器上顺手按一下确定不该就改掉. */
@Composable
private fun TvSettingsConfirmDialog(
    confirm: TvSettingsConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = confirm.title?.resolve()
    AniAlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { Text(confirm.text.resolve()) },
        confirmButton = {
            AniTextButton(
                onClick = onConfirm,
                colors = if (confirm.destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirm.confirmLabel.resolve()) }
        },
        dismissButton = {
            AniTextButton(onClick = onDismiss, modifier = Modifier.tvWindowInitialFocus()) {
                Text(stringResource(Lang.settings_mediasource_cancel))
            }
        },
    )
}

/** 在组合里把 [TvText] 换成字 (弹窗用; 页面本身的字由状态里读好的模板换, 见 [TvTextResolver]). */
@Composable
internal fun TvText.resolve(): String = when (this) {
    is TvText.Plain -> text
    is TvText.Res -> {
        val resolvedArgs = args.map { if (it is TvText) it.resolve() else it }
        stringResource(res, *resolvedArgs.toTypedArray())
    }
}

/** 上次在设置页停的位置 (进程内): 下次打开 (没指定去哪一类时) 落回去. */
internal object TvSettingsLastPosition {
    var categoryId: String? = null
    var rowId: String? = null
}

/** 设置页的尺寸与配色 (见 [TvSettingsStyle]): 跟应用主题走, 字号取主题的排版. */
@Composable
fun rememberTvSettingsStyle(): TvSettingsStyle {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val visualEffects = LocalThemeSettings.current.visualEffects
    val focusedBg = aniFocusContainerColor()
    val focusedText = aniFocusContentColor()
    // 浅色主题照 tvOS: 聚焦行是白底配黑字 (同顶栏玻璃控件的调色板), 不用主题色 tone 40 的深色实底
    val glass = tvGlassColors()
    val marqueeRepeat = when {
        !visualEffects.marquee -> 0
        visualEffects.ambient -> -1
        else -> TV_REDUCED_MARQUEE_ITERATIONS
    }
    val transitions = visualEffects.transitions
    return remember(density, typography, colors, focusedBg, focusedText, glass, marqueeRepeat, transitions) {
        with(density) {
            val onSurface = colors.onSurface
            val secondary = onSurface.copy(alpha = 0.6f)
            val darkTheme = colors.surface.luminance() < 0.5f
            TvSettingsStyle(
                // 左边距同其他页面的内容起点
                paddingStartPx = 48.dp.roundToPx(),
                paddingEndPx = 48.dp.roundToPx(),
                paddingTopPx = 32.dp.roundToPx(),
                paddingBottomPx = 24.dp.roundToPx(),
                titleGapPx = 18.dp.roundToPx(),
                railWidthPx = 200.dp.roundToPx(),
                infoWidthFraction = 0.28f,
                columnGapPx = 28.dp.roundToPx(),
                rowGapPx = 4.dp.roundToPx(),
                rowHeightPx = 46.dp.roundToPx(),
                headerTopGapPx = 14.dp.roundToPx(),
                headerBottomGapPx = 4.dp.roundToPx(),
                cornerPx = 10.dp.toPx(),
                rowPaddingHPx = 16.dp.roundToPx(),
                trailingGapPx = 16.dp.roundToPx(),
                swatchSizePx = 18.dp.roundToPx(),
                swatchGapPx = 12.dp.roundToPx(),
                drillTitleBottomGapPx = 6.dp.roundToPx(),
                infoTitleGapPx = 10.dp.roundToPx(),
                focusBleedPx = 8.dp.roundToPx(),
                qrSizePx = 240.dp.roundToPx(),
                qrGapPx = 12.dp.roundToPx(),
                infoImageSizePx = 72.dp.roundToPx(),
                pageTitle = typography.headlineSmall.copy(fontWeight = FontWeight.Bold).toTvNativeTextStyle(density, onSurface),
                railTitle = typography.bodyLarge.toTvNativeTextStyle(density, onSurface),
                rowTitle = typography.bodyLarge.toTvNativeTextStyle(density, onSurface),
                rowValue = typography.bodyMedium.toTvNativeTextStyle(density, secondary),
                header = typography.labelMedium.toTvNativeTextStyle(density, onSurface.copy(alpha = 0.5f)),
                drillTitle = typography.titleSmall.toTvNativeTextStyle(density, secondary),
                infoTitle = typography.titleMedium.copy(fontWeight = FontWeight.Bold).toTvNativeTextStyle(density, onSurface),
                infoBody = typography.bodyMedium.toTvNativeTextStyle(density, secondary),
                activeBg = onSurface.copy(alpha = 0.1f).toArgb(),
                focusedBg = (if (darkTheme) focusedBg else glass.focusedPlatter).toArgb(),
                // 浅底深码 (同 Web 控制台的二维码): 深色主题的 primary 本身是浅色, 当底、onPrimary 画码; 浅色主题换 primaryContainer
                qrBg = (if (darkTheme) colors.primary else colors.primaryContainer).toArgb(),
                qrFg = (if (darkTheme) colors.onPrimary else colors.onPrimaryContainer).toArgb(),
                text = onSurface.toArgb(),
                textSecondary = secondary.toArgb(),
                focusedText = (if (darkTheme) focusedText else glass.focusedContent).toArgb(),
                // 浅色是 tvOS 的 LabelSecondary (黑 60%)
                focusedTextSecondary = if (darkTheme) focusedText.copy(alpha = 0.8f).toArgb() else glass.focusedContent.copy(alpha = 0.6f).toArgb(),
                focusScale = 1.03f,
                marqueeRepeat = marqueeRepeat,
                focusInMillis = 150,
                focusOutMillis = 90,
                animated = transitions,
            )
        }
    }
}
